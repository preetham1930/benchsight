"""Append-only trace of every Meta model call: what was called, how long it took, tokens and estimated cost.

Traces are the evidence judges asked for ("judicable collateral") and they feed the budget guard.
"""
import json
import os
import threading
import time

from . import config

_lock = threading.Lock()
_spent = {"usd": 0.0}


class BudgetExceeded(RuntimeError):
    pass


def spent_usd() -> float:
    return _spent["usd"]


def check_budget():
    if _spent["usd"] >= config.BUDGET_USD:
        raise BudgetExceeded(
            f"BenchSight budget of ${config.BUDGET_USD:.2f} reached (spent ~${_spent['usd']:.3f}). "
            "Raise BENCHSIGHT_BUDGET_USD to continue."
        )


def log(session_dir: str, record: dict):
    record = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), **record}
    with _lock:
        _spent["usd"] += float(record.get("est_usd", 0.0) or 0.0)
        record["session_total_usd_est"] = round(_spent["usd"], 5)
        os.makedirs(session_dir, exist_ok=True)
        with open(os.path.join(session_dir, "trace.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
