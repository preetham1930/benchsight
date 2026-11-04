# BenchSight

**A hands-free AI copilot for laptop motherboard repair, built for Meta AI glasses.**

A technician wearing Ray-Ban Meta glasses looks at a dead motherboard. BenchSight reads the board, asks for
one multimeter reading at a time (in Telugu, Tamil, Hindi or English, by voice), shows exactly where to put the
probes, logs every reading as evidence, and only proposes a fault once the evidence supports it, after a
verification step. The technician confirms before anything is marked as diagnosed.

Built during the Meta Global AI Developer Hackathon (Oct 26 – Nov 4, 2026), Indie Developer track,
theme **Build for Your World**: my family runs Krishna Computers, a computer sales and repair shop in
Suryapet, Telangana, India.

## Built during the hackathon

All code in this repository was written during the event. There is no pre-existing project code.
Third-party pieces, used as published:
- Meta Wearables Device Access Toolkit (`com.meta.wearable:mwdat-*` 1.0.1 from Maven Central), under the
  Meta Wearables Developer Terms.
- The standard Gradle wrapper files in `android/` (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), Gradle's own
  Apache-2.0 files, copied from Meta's public DAT sample so the build uses the same Gradle version.
- Open-source libraries listed in `requirements.txt` and `android/gradle/libs.versions.toml`.

## How Meta's models are used (every runtime model is a Meta model)

| Model | Where it runs in BenchSight | What it does |
|---|---|---|
| `muse-spark-1.3-contributor` | `server/vision.py`, `server/agent.py` | Reads the board (IDs, labels, visible damage); picks which SAM box matches a description; reads chip markings from full-resolution crops; parses what the technician said; plans the next measurement; acts as a strict judge of any proposed conclusion. Structured JSON output with chosen reasoning effort (low for live turns, medium for planning and judging). |
| `sam-3.1` | `server/vision.py` | Finds every candidate component ("chip", "connector", "capacitor") on the photo. Muse Spark then chooses the right one from numbered boxes, and BenchSight draws the green **MEASURE HERE** highlight. |
| `muse-voice-transcribe-1.0` | `server/models.py` | Push-to-talk speech to text with language bias for Telugu, Tamil, Hindi and English (code-switching) and keyword hints for repair terms. |
| Meta Wearables DAT 1.0 | `android/.../GlassesManager.kt` | Glasses session, camera stream and full-resolution photo capture. Mock Device Kit simulates Ray-Ban Meta glasses with the phone camera. |

Spoken replies use the phone's or browser's built-in system voice (no AI model), as required by the rules.
Every model call is logged with model ID, purpose, latency, tokens and estimated cost in
`runs/<session>/trace.jsonl` and shown live in the **Live Meta model calls** panel.

## Why it is reliable: verification built into the agent

Board repair is a niche domain, so the agent is not allowed to guess:
- **One measurement at a time**, each with meter mode, power state and an expected range. Readings are
  stored as evidence and rated normal/abnormal **in code**.
- **Code checks** before any conclusion: it must cite recorded evidence IDs, include an abnormal reading,
  and be based on at least two measurements.
- **AI judge**: a separate Muse Spark call checks that the conclusion follows from the evidence alone.
- **Max 3 retries**, then BenchSight escalates to the technician instead of guessing.
- **Human sign-off**: nothing is marked diagnosed until the technician says "confirm".
- Board IDs are always confirmed by the technician (vision misreads like S/5 and V/M were seen in testing).

## Run it

### 1. Server (Windows, macOS or Linux, Python 3.10+)
```
pip install -r requirements.txt
copy .env.example .env        # then put your Meta Model API key in .env
python -m uvicorn server.app:app --host 0.0.0.0 --port 8000
```
Open http://localhost:8000 for the test bench (photo upload, push-to-talk, evidence log, live Meta calls).

### 2. Judge mode without an API key
Start the server the same way (no key needed) and choose a session from **Recorded runs (judge mode)**.
Recorded runs in `demo_runs/` contain the real images, the conversation, the evidence log, the highlights
and the full trace of every Meta model call that produced them.

### 3. Android app (Meta glasses client)
Open `android/` in Android Studio (Gradle JDK 21), run on an Android 12+ phone on the same Wi-Fi as the
server. In the app: enter `http://<server-ip>:8000` → **Mock glasses** (or **Real glasses** with Ray-Ban
Meta and Developer Mode in the Meta AI app) → **Start camera** → **New repair** → **Show board** →
**Hold to talk**.

## Project layout
```
server/   agent.py (diagnosis + verification), vision.py (Spark + SAM), models.py (Meta API, retries,
          budget guard), trace.py, app.py (FastAPI), static/index.html (test bench + judge mode)
android/  Kotlin app: GlassesManager (DAT + Mock Device Kit), VoiceRecorder, Speaker, BenchClient
tests/    day-1 smoke tests for each Meta model
demo_runs/ recorded sessions for judges
```

## Limitations (honest)
- Built and tested with Meta's Mock Device Kit (simulated Ray-Ban Meta, phone camera as the glasses camera);
  real glasses use the same code path plus registration through the Meta AI app.
- Small chip markings need close-ups; whole-board photos are used for board ID and layout only.
- Diagnosis quality depends on the technician's readings; BenchSight never invents readings.
