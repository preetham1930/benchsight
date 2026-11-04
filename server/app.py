"""BenchSight local server. Run from the project folder:

    python -m uvicorn server.app:app --host 0.0.0.0 --port 8000

The phone app (or the test page at http://localhost:8000) sends images, voice and text here;
this server calls Meta's models and runs the diagnosis agent.
"""
import io
import json
import os
import re
import wave

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from . import config, trace
from .agent import Session
from .models import ModelError

app = FastAPI(title="BenchSight")
SESSIONS: dict = {}
STATIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")


def _get(sid: str) -> Session:
    s = SESSIONS.get(sid)
    if not s:
        raise HTTPException(404, "Unknown session. Start a new one.")
    return s


def _guard(fn, *a):
    try:
        return fn(*a)
    except trace.BudgetExceeded as e:
        return JSONResponse({"error": str(e)}, status_code=402)
    except ModelError as e:
        return JSONResponse({"error": f"Meta model call failed: {e}"}, status_code=503)


@app.get("/")
def index():
    return FileResponse(os.path.join(STATIC, "index.html"))


@app.get("/api/health")
def health():
    return {"ok": True, "spent_usd_est": round(trace.spent_usd(), 4), "budget_usd": config.BUDGET_USD,
            "models": [config.SPARK, config.SAM, config.ASR]}


@app.post("/api/session")
def new_session():
    s = Session()
    SESSIONS[s.id] = s
    s.save()
    return {"session": s.id, "say_en": "New session. Show me the board.", "phase": s.phase}


@app.post("/api/session/{sid}/image")
async def upload_image(sid: str, file: UploadFile = File(...), kind: str = Form("overview")):
    s = _get(sid)
    data = await file.read()
    n = len([f for f in os.listdir(s.dir) if f.startswith("img_")]) + 1
    path = os.path.join(s.dir, f"img_{n}_{kind}.jpg")
    with open(path, "wb") as f:
        f.write(data)
    return _guard(s.add_image, path, kind)


@app.post("/api/session/{sid}/audio")
async def upload_audio(sid: str, file: UploadFile = File(...)):
    from .models import transcribe
    s = _get(sid)
    data = await file.read()
    try:
        with wave.open(io.BytesIO(data)) as w:
            seconds = w.getnframes() / float(w.getframerate())
    except wave.Error:
        raise HTTPException(400, "Audio must be a WAV file.")
    n = len([f for f in os.listdir(s.dir) if f.startswith("voice_")]) + 1
    with open(os.path.join(s.dir, f"voice_{n}.wav"), "wb") as f:
        f.write(data)

    def run():
        text = transcribe(s.dir, data, seconds)
        return s.handle_text(text)
    return _guard(run)


class TextIn(BaseModel):
    text: str


@app.post("/api/session/{sid}/text")
def send_text(sid: str, body: TextIn):
    s = _get(sid)
    return _guard(s.handle_text, body.text)


@app.get("/api/latest")
def latest_session():
    """Most recently created session (e.g. the phone's), so the laptop screen can follow it live."""
    if not SESSIONS:
        return {"session": None}
    s = list(SESSIONS.values())[-1]
    return {"session": s.id, "state": s.snapshot(), "calls": _read_trace(s.dir),
            "highlight": f"highlight_{s.highlight_n}.jpg" if s.highlight_n else None,
            "image": os.path.basename(s.overview_path) if s.overview_path else None}


@app.get("/api/session/{sid}/state")
def state(sid: str):
    return _get(sid).snapshot()


@app.get("/api/session/{sid}/trace")
def session_trace(sid: str):
    return {"calls": _read_trace(_get(sid).dir)}


def _read_trace(d: str) -> list:
    path = os.path.join(d, "trace.jsonl")
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


# ---------- judge mode: recorded runs (no API key needed) ----------

@app.get("/api/runs")
def list_runs():
    if not os.path.isdir(config.DEMO_RUNS_DIR):
        return {"runs": []}
    runs = sorted(d for d in os.listdir(config.DEMO_RUNS_DIR)
                  if os.path.exists(os.path.join(config.DEMO_RUNS_DIR, d, "state.json")))
    return {"runs": runs}


@app.get("/api/runs/{rid}")
def get_run(rid: str):
    if not SAFE_NAME.match(rid):
        raise HTTPException(400, "bad name")
    d = os.path.join(config.DEMO_RUNS_DIR, rid)
    if not os.path.exists(os.path.join(d, "state.json")):
        raise HTTPException(404, "not found")
    with open(os.path.join(d, "state.json"), encoding="utf-8") as f:
        state = json.load(f)
    files = sorted(f for f in os.listdir(d) if f.endswith(".jpg"))
    return {"state": state, "calls": _read_trace(d), "images": files}


@app.get("/api/runs/{rid}/file/{name}")
def run_file(rid: str, name: str):
    if not SAFE_NAME.match(rid) or not SAFE_NAME.match(name):
        raise HTTPException(400, "bad name")
    path = os.path.join(config.DEMO_RUNS_DIR, rid, name)
    if not os.path.exists(path):
        raise HTTPException(404, "not found")
    return FileResponse(path)


@app.get("/api/session/{sid}/file/{name}")
def session_file(sid: str, name: str):
    s = _get(sid)
    if not SAFE_NAME.match(name):
        raise HTTPException(400, "bad name")
    path = os.path.join(s.dir, name)
    if not os.path.exists(path):
        raise HTTPException(404, "not found")
    return FileResponse(path)
