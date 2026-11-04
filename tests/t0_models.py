"""Test 0: list the model IDs this API key can use. Free (no tokens)."""
from common import list_model_ids, save_out

ids, rid = list_model_ids()
print(f"{len(ids)} models available (request id {rid}):")
for i in sorted(ids):
    print("  ", i)
save_out("t0_models.txt", "\n".join(sorted(ids)))
