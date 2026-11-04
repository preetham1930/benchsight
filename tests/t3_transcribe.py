"""Test 3: Muse Voice Transcribe on a short recording from your microphone.

Usage:  python t3_transcribe.py [seconds, default 10]
Speak naturally: Telugu, Hindi, English or a mix, like you would at the bench.
"""
import json
import os
import sys
import uuid
import wave

import requests
import sounddevice as sd

from common import BASE_URL, OUT_DIR, auth_headers

RATE = 24_000
seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 10

dev = sd.query_devices(kind="input")
print(f"Microphone: {dev['name']}")
input(f"Press Enter, then speak for the full {seconds} seconds, close to the mic...")
audio = sd.rec(int(seconds * RATE), samplerate=RATE, channels=1, dtype="int16")
sd.wait()
import numpy as np
per_sec = [int(np.sqrt(np.mean(audio[i:i + RATE].astype(np.float64) ** 2))) for i in range(0, len(audio), RATE)]
print("Loudness per second:", per_sec)
if max(per_sec) < 1000:
    print("Warning: the recording is very quiet. Move closer to the mic or check Windows input volume.")
print("Recorded. Sending to Muse Voice Transcribe...")

os.makedirs(OUT_DIR, exist_ok=True)
wav_path = os.path.join(OUT_DIR, "t3_clip.wav")
with wave.open(wav_path, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(audio.tobytes())

settings = {
    "mode": "PUSH_TO_TALK",
    "model": "muse-voice-transcribe-1.0",
    "audioEncoding": "WAV",
    "languageBias": ["Telugu", "Hindi", "English"],
    "keywords": ["motherboard", "multimeter", "MOSFET", "capacitor", "short", "3.3 volt", "charging IC"],
}
with open(wav_path, "rb") as f:
    r = requests.post(
        f"{BASE_URL}/asr/transcribe?sessionId=benchsight-{uuid.uuid4()}",
        headers=auth_headers(),
        files={
            "request": (None, json.dumps(settings), "application/json"),
            "audio": ("clip.wav", f, "audio/wav"),
        },
        timeout=120,
    )
rid = r.headers.get("x-request-id") or r.headers.get("request-id")
print(f"HTTP {r.status_code} (request id {rid})")
try:
    body = r.json()
    print("Transcript:", body.get("transcript"))
    with open(os.path.join(OUT_DIR, "t3_transcript.json"), "w", encoding="utf-8") as out:
        json.dump(body, out, ensure_ascii=False, indent=2)
except ValueError:
    print(r.text[:800])
