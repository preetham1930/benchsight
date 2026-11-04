"""Test 2: SAM 3.1 finds components in a motherboard photo.

Usage:  python t2_sam.py <path-to-board-photo> [noun prompt] [more prompts...]
Default prompts: chip, capacitor, inductor, connector, burn mark.
SAM works best with a short noun phrase. An empty result means no match.
"""
import re
import sys

from common import client, image_data_url, save_out

if len(sys.argv) < 2:
    sys.exit('Usage: python t2_sam.py <path-to-board-photo> [prompt ...]')
image = sys.argv[1]
prompts = sys.argv[2:] or ["chip", "capacitor", "inductor", "connector", "burn mark"]
data_url = image_data_url(image)
box_re = re.compile(r"<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+);w=(\d+);h=(\d+)\|>")

for prompt in prompts:
    chunks = []
    with client().responses.stream(
        model="sam-3.1",
        input=[
            {
                "type": "message",
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt},
                    {"type": "input_image", "image_url": data_url},
                ],
            }
        ],
    ) as stream:
        for event in stream:
            if event.type == "response.output_text.delta":
                chunks.append(event.delta)
        final = stream.get_final_response()
    raw = "".join(chunks)
    save_out(f"t2_sam_{prompt.replace(' ', '_')}.txt", raw)
    boxes = box_re.findall(raw)
    print(f"\n{prompt!r}: {len(boxes)} match(es)")
    for b in boxes[:8]:
        x1, y1, x2, y2, w, h = map(int, b)
        print(f"   box ({x1},{y1})-({x2},{y2}) in a {w}x{h} image")
    if len(boxes) > 8:
        print(f"   ... {len(boxes) - 8} more")
    if final.usage:
        print("   images_processed:", getattr(final.usage, "images_processed", None) or final.usage.model_dump().get("images_processed"))
