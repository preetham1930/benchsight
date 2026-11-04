"""Thin, traced wrappers around the three Meta models BenchSight uses at runtime.

- spark_json(): Muse Spark with a JSON schema (structured output) and a chosen reasoning effort.
- sam_boxes():  SAM 3.1 open-vocabulary segmentation, returns bounding boxes.
- transcribe(): Muse Voice Transcribe on a WAV clip.

Every call is retried on transient failure and written to the session trace.
"""
import base64
import io
import json
import os
import re
import time
import uuid

import requests
from openai import OpenAI
from PIL import Image

from . import config, trace

_client = None


def _key() -> str:
    key = os.environ.get("MODEL_API_KEY")
    if not key:
        raise ModelError("MODEL_API_KEY is not set. Put your key in .env (see .env.example) or set the "
                         "MODEL_API_KEY environment variable. Judge mode (recorded runs) works without a key.")
    return key


def client() -> OpenAI:
    global _client
    if _client is None:
        _client = OpenAI(base_url=config.BASE_URL, api_key=_key(), timeout=90, max_retries=2)
    return _client


class ModelError(RuntimeError):
    pass


# ---------- images ----------

def image_to_data_url(img: Image.Image, max_side: int = 1600, quality: int = 90) -> str:
    img = img.convert("RGB")
    if max(img.size) > max_side:
        img = img.copy()
        img.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def text_part(text: str) -> dict:
    return {"type": "input_text", "text": text}


def image_part(img: Image.Image, max_side: int = 1600) -> dict:
    return {"type": "input_image", "image_url": image_to_data_url(img, max_side)}


# ---------- Muse Spark (structured output) ----------

def spark_json(session_dir: str, purpose: str, schema_name: str, schema: dict, content: list,
               effort: str = "low", max_output_tokens: int = 4000, attempts: int = 3) -> dict:
    """Call Muse Spark and return a dict that matches `schema`.

    Reasoning tokens share the output budget, so an empty reply usually means the budget ran out:
    on retry we raise max_output_tokens.
    """
    trace.check_budget()
    last_err = None
    for attempt in range(1, attempts + 1):
        t0 = time.time()
        rec = {"model": config.SPARK, "purpose": purpose, "effort": effort, "attempt": attempt,
               "max_output_tokens": max_output_tokens}
        try:
            resp = client().responses.create(
                model=config.SPARK,
                input=[{"type": "message", "role": "user", "content": content}],
                reasoning={"effort": effort},
                max_output_tokens=max_output_tokens,
                text={"format": {"type": "json_schema", "name": schema_name, "schema": schema}},
            )
            text = resp.output_text or ""
            u = resp.usage.model_dump() if resp.usage else {}
            tin, tout = u.get("input_tokens", 0) or 0, u.get("output_tokens", 0) or 0
            rec.update({
                "latency_s": round(time.time() - t0, 2), "input_tokens": tin, "output_tokens": tout,
                "reasoning_tokens": (u.get("output_tokens_details") or {}).get("reasoning_tokens"),
                "est_usd": round(tin / 1e6 * config.SPARK_IN_PER_M + tout / 1e6 * config.SPARK_OUT_PER_M, 6),
                "response_id": getattr(resp, "id", None),
            })
            if not text.strip():
                raise ModelError("empty reply (reasoning may have used the whole output budget)")
            data = json.loads(text)
            rec.update({"ok": True, "output": data})
            trace.log(session_dir, rec)
            return data
        except Exception as e:  # noqa: BLE001 - we log and retry every failure type
            last_err = e
            rec.update({"ok": False, "error": f"{type(e).__name__}: {e}"[:500],
                        "latency_s": round(time.time() - t0, 2)})
            trace.log(session_dir, rec)
            max_output_tokens = int(max_output_tokens * 1.6)
            time.sleep(min(2 * attempt, 6))
    raise ModelError(f"Muse Spark failed for '{purpose}' after {attempts} attempts: {last_err}")


# ---------- SAM 3.1 ----------

_BOX_RE = re.compile(r"<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+);w=(\d+);h=(\d+)\|>")


def sam_boxes(session_dir: str, img: Image.Image, noun: str, attempts: int = 2) -> list:
    """Return SAM boxes for a short noun prompt, scaled to `img`'s own pixel size.

    Each box: dict(x1, y1, x2, y2). An empty list means no match (a valid SAM outcome).
    """
    trace.check_budget()
    data_url = image_to_data_url(img)
    W, H = img.size
    last_err = None
    for attempt in range(1, attempts + 1):
        t0 = time.time()
        rec = {"model": config.SAM, "purpose": f"locate:{noun}", "attempt": attempt}
        try:
            chunks = []
            with client().responses.stream(
                model=config.SAM,
                input=[{"type": "message", "role": "user",
                        "content": [text_part(noun), {"type": "input_image", "image_url": data_url}]}],
            ) as stream:
                for ev in stream:
                    if ev.type == "response.output_text.delta":
                        chunks.append(ev.delta)
            raw = "".join(chunks)
            boxes = []
            for x1, y1, x2, y2, w, h in _BOX_RE.findall(raw):
                sx, sy = W / int(w), H / int(h)
                boxes.append({"x1": round(int(x1) * sx), "y1": round(int(y1) * sy),
                              "x2": round(int(x2) * sx), "y2": round(int(y2) * sy)})
            rec.update({"ok": True, "latency_s": round(time.time() - t0, 2), "matches": len(boxes),
                        "boxes": boxes[:40], "est_usd": config.SAM_PER_IMAGE})
            trace.log(session_dir, rec)
            return boxes
        except Exception as e:  # noqa: BLE001
            last_err = e
            rec.update({"ok": False, "error": f"{type(e).__name__}: {e}"[:500]})
            trace.log(session_dir, rec)
            time.sleep(2)
    raise ModelError(f"SAM failed for '{noun}': {last_err}")


# ---------- Muse Voice Transcribe ----------

def transcribe(session_dir: str, wav_bytes: bytes, seconds: float) -> str:
    trace.check_budget()
    settings = {
        "mode": "PUSH_TO_TALK",
        "model": config.ASR,
        "audioEncoding": "WAV",
        "languageBias": config.LANGUAGE_BIAS,
        "keywords": config.ASR_KEYWORDS,
    }
    t0 = time.time()
    rec = {"model": config.ASR, "purpose": "transcribe", "audio_s": round(seconds, 1)}
    r = requests.post(
        f"{config.BASE_URL}/asr/transcribe?sessionId=benchsight-{uuid.uuid4()}",
        headers={"Authorization": f"Bearer {_key()}"},
        files={"request": (None, json.dumps(settings), "application/json"),
               "audio": ("clip.wav", wav_bytes, "audio/wav")},
        timeout=120,
    )
    rec.update({"http": r.status_code, "latency_s": round(time.time() - t0, 2),
                "est_usd": round(seconds / 3600 * config.ASR_PER_HOUR, 6)})
    if r.status_code != 200:
        rec.update({"ok": False, "error": r.text[:500]})
        trace.log(session_dir, rec)
        raise ModelError(f"Transcribe failed: HTTP {r.status_code} {r.text[:200]}")
    text = (r.json() or {}).get("transcript") or ""
    rec.update({"ok": True, "transcript": text})
    trace.log(session_dir, rec)
    return text
