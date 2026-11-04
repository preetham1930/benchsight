"""Shared helpers for the BenchSight API smoke tests.

The API key is read only from the MODEL_API_KEY environment variable.
It is never printed, logged or written to disk.
"""
import base64
import io
import mimetypes
import os
import sys

BASE_URL = "https://api.meta.ai/v1"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def get_key() -> str:
    key = os.environ.get("MODEL_API_KEY")
    if not key:
        sys.exit(
            "MODEL_API_KEY is not set in this window.\n"
            "If you already ran setx, close this PowerShell window and open a new one."
        )
    return key


def auth_headers() -> dict:
    return {"Authorization": f"Bearer {get_key()}"}


def client():
    from openai import OpenAI

    return OpenAI(base_url=BASE_URL, api_key=get_key())


def list_model_ids():
    import requests

    r = requests.get(f"{BASE_URL}/models", headers=auth_headers(), timeout=30)
    rid = r.headers.get("x-request-id") or r.headers.get("request-id")
    if r.status_code != 200:
        sys.exit(f"GET /models failed: HTTP {r.status_code} (request id {rid})\n{r.text[:500]}")
    return [m.get("id") for m in r.json().get("data", [])], rid


def pick_model(ids, *must_contain):
    """Return the first model id containing every given substring, preferring 'contributor'."""
    matches = [i for i in ids if all(s in i for s in must_contain)]
    contrib = [i for i in matches if "contributor" in i]
    return (contrib or matches or [None])[0]


def image_data_url(path: str, max_side: int = 1600) -> str:
    """Base64 data URL for a local image, downscaled so phone photos stay small."""
    if not os.path.exists(path):
        sys.exit(f"Image not found: {path}")
    try:
        from PIL import Image

        img = Image.open(path)
        img = img.convert("RGB")
        img.thumbnail((max_side, max_side))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=90)
        data, mime = buf.getvalue(), "image/jpeg"
    except ImportError:
        with open(path, "rb") as f:
            data = f.read()
        mime = mimetypes.guess_type(path)[0] or "image/jpeg"
    return f"data:{mime};base64,{base64.b64encode(data).decode()}"


def save_out(name: str, text: str) -> str:
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path
