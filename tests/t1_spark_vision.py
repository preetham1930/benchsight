"""Test 1: Muse Spark reads a motherboard photo.

Usage:  python t1_spark_vision.py <path-to-board-photo> [model-id]
"""
import json
import sys

from common import client, image_data_url, list_model_ids, pick_model, save_out

PROMPT = (
    "This is a photo of a laptop motherboard on a repair bench.\n"
    "1. Board maker and model, only if a marking is readable.\n"
    "2. Every chip marking you can read exactly, with where it is on the board.\n"
    "3. Any visible damage: burn marks, corrosion, liquid residue, bulging or missing components.\n"
    "If something is not readable or not visible, say 'not visible'. Do not guess."
)

if len(sys.argv) < 2:
    sys.exit("Usage: python t1_spark_vision.py <path-to-board-photo> [model-id]")
image = sys.argv[1]
if len(sys.argv) > 2:
    model = sys.argv[2]
else:
    ids, _ = list_model_ids()
    model = pick_model(ids, "spark-1.3") or pick_model(ids, "spark")
    if not model:
        sys.exit("No Muse Spark model found. Run t0_models.py and pass a model id.")
print(f"Model: {model}")

resp = client().responses.create(
    model=model,
    input=[
        {
            "type": "message",
            "role": "user",
            "content": [
                {"type": "input_text", "text": PROMPT},
                {"type": "input_image", "image_url": image_data_url(image)},
            ],
        }
    ],
)
print("\n--- Answer ---\n" + (resp.output_text or "(empty)"))
usage = resp.usage.model_dump() if resp.usage else {}
print("\n--- Usage ---\n" + json.dumps(usage, indent=2))
save_out("t1_spark_vision.txt", f"model: {model}\n\n{resp.output_text}\n\nusage: {json.dumps(usage)}")
