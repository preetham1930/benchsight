"""Vision pipeline: Muse Spark understands, SAM 3.1 locates, and the two are combined.

- read_board():   Muse Spark reads board identity, labels and visible damage from an overview image.
- locate():       SAM proposes boxes for a noun ("chip", "connector"); boxes are drawn with numbers
                  and Muse Spark picks the one matching a description ("chip next to the charging jack").
- read_marking(): crop the chosen box from the full-resolution image and let Muse Spark read it.
- draw_highlight(): the image the technician sees, with the test point outlined.
"""
import os

from PIL import Image, ImageDraw, ImageFont

from . import models

BOARD_SCHEMA = {
    "type": "object",
    "properties": {
        "board_ids": {"type": "array", "items": {"type": "string"},
                      "description": "Board identifiers exactly as printed. Put the main board number first (short code like NM-D031, LA-G071P, 15341-1), then longer model lists. Only clearly readable strings."},
        "maker": {"type": "string", "description": "Maker only if a logo or name is readable, else 'unknown'."},
        "labels": {"type": "array", "items": {"type": "string"},
                   "description": "Readable connector or area labels, e.g. DCIN, BATT, LCD."},
        "damage": {"type": "array", "items": {
            "type": "object",
            "properties": {"what": {"type": "string"}, "where": {"type": "string"}},
            "required": ["what", "where"]}},
        "uncertain": {"type": "array", "items": {"type": "string"},
                      "description": "Text that is partly readable; never guess characters."},
        "side": {"type": "string", "enum": ["component_side", "solder_side", "in_chassis", "unclear"]},
    },
    "required": ["board_ids", "maker", "labels", "damage", "uncertain", "side"],
}

BOARD_PROMPT = (
    "You are the vision system of a laptop motherboard repair assistant. Read this photo strictly.\n"
    "Report only text you can read with certainty. If a character is unclear, put the whole string in "
    "'uncertain' instead of guessing. Report damage only if clearly visible (burn marks, corrosion, liquid "
    "residue, bulging or missing parts); dust is not damage. Unpopulated factory footprints are not damage."
)

PICK_SCHEMA = {
    "type": "object",
    "properties": {
        "index": {"type": "integer", "description": "Number of the matching box, or 0 if none match."},
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
        "why": {"type": "string"},
    },
    "required": ["index", "confidence", "why"],
}

MARKING_SCHEMA = {
    "type": "object",
    "properties": {
        "legible": {"type": "boolean"},
        "marking": {"type": "string", "description": "Exact text lines joined with ' / ', empty if not legible."},
        "uncertain_chars": {"type": "string", "description": "Which characters are doubtful, if any."},
    },
    "required": ["legible", "marking", "uncertain_chars"],
}


def _font(size: int):
    for name in ("arialbd.ttf", "arial.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def read_board(session_dir: str, img: Image.Image) -> dict:
    return models.spark_json(
        session_dir, "read_board", "BoardRead", BOARD_SCHEMA,
        [models.text_part(BOARD_PROMPT), models.image_part(img)],
        effort="low", max_output_tokens=5000,
    )


def draw_numbered(img: Image.Image, boxes: list) -> Image.Image:
    out = img.convert("RGB").copy()
    d = ImageDraw.Draw(out)
    lw = max(2, round(max(out.size) / 400))
    f = _font(max(14, round(max(out.size) / 45)))
    for i, b in enumerate(boxes, 1):
        d.rectangle([b["x1"], b["y1"], b["x2"], b["y2"]], outline=(255, 230, 0), width=lw)
        tx, ty = b["x1"], max(0, b["y1"] - f.size - 4)
        d.rectangle([tx, ty, tx + f.size * (len(str(i)) * 0.7 + 0.6), ty + f.size + 4], fill=(0, 0, 0))
        d.text((tx + 3, ty + 1), str(i), fill=(255, 230, 0), font=f)
    return out


def locate(session_dir: str, img: Image.Image, noun: str, description: str, max_boxes: int = 30):
    """Return (box or None, info). SAM proposes, Muse Spark chooses which proposal fits the description."""
    boxes = models.sam_boxes(session_dir, img, noun)
    if not boxes:
        return None, {"sam_matches": 0, "noun": noun}
    boxes = sorted(boxes, key=lambda b: (b["x2"] - b["x1"]) * (b["y2"] - b["y1"]), reverse=True)[:max_boxes]
    numbered = draw_numbered(img, boxes)
    pick = models.spark_json(
        session_dir, "pick_box", "BoxPick", PICK_SCHEMA,
        [models.text_part(
            f"Yellow numbered boxes mark every '{noun}' found on this laptop motherboard. Which single box "
            f"is: {description}? Answer 0 if none clearly matches."),
         models.image_part(numbered)],
        effort="low", max_output_tokens=3000,
    )
    idx = int(pick.get("index", 0))
    info = {"sam_matches": len(boxes), "noun": noun, "pick": pick}
    if 1 <= idx <= len(boxes):
        return boxes[idx - 1], info
    return None, info


def crop(img: Image.Image, box: dict, pad_ratio: float = 0.35) -> Image.Image:
    w, h = box["x2"] - box["x1"], box["y2"] - box["y1"]
    px, py = int(w * pad_ratio) + 4, int(h * pad_ratio) + 4
    x1, y1 = max(0, box["x1"] - px), max(0, box["y1"] - py)
    x2, y2 = min(img.width, box["x2"] + px), min(img.height, box["y2"] + py)
    return img.crop((x1, y1, x2, y2))


def read_marking(session_dir: str, img: Image.Image, box: dict) -> dict:
    c = crop(img, box)
    if max(c.size) < 800:  # upscale small crops so the model sees detail at a usable size
        s = 800 / max(c.size)
        c = c.resize((int(c.width * s), int(c.height * s)), Image.LANCZOS)
    return models.spark_json(
        session_dir, "read_marking", "Marking", MARKING_SCHEMA,
        [models.text_part("Read the laser-etched marking on the chip in the centre of this close-up. "
                          "Exact characters only; if any character is doubtful, list it in uncertain_chars. "
                          "If not legible, set legible=false."),
         models.image_part(c, max_side=1600)],
        effort="low", max_output_tokens=3000,
    )


def draw_highlight(img: Image.Image, box: dict, label: str, path: str) -> str:
    out = img.convert("RGB").copy()
    d = ImageDraw.Draw(out, "RGBA")
    lw = max(4, round(max(out.size) / 200))
    pad = lw * 2
    d.rectangle([box["x1"] - pad, box["y1"] - pad, box["x2"] + pad, box["y2"] + pad],
                outline=(0, 255, 120, 255), width=lw)
    f = _font(max(18, round(max(out.size) / 35)))
    tw = int(f.size * 0.62 * len(label)) + 16
    th = f.size + 8
    tx = min(max(0, box["x1"] - pad), max(0, out.width - tw))
    ty = box["y1"] - pad - th - 4
    if ty < 0:
        ty = min(out.height - th, box["y2"] + pad + 4)
    d.rectangle([tx, ty, tx + tw, ty + th], fill=(0, 0, 0, 200))
    d.text((tx + 8, ty + 3), label, fill=(0, 255, 120, 255), font=f)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path, "JPEG", quality=88)
    return path
