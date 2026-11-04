"""BenchSight diagnosis agent.

Flow: identify board (technician confirms) -> symptom -> one measurement at a time -> evidence log ->
proposed conclusion -> verification (code checks + Muse Spark judge, max 3 retries) -> technician sign-off.

Rules the agent cannot skip (enforced in code, not left to the model):
- A conclusion must cite recorded evidence ids, at least one of them abnormal, with >= 2 measurements total.
- Every conclusion goes through the judge; after 3 failed verifications the agent escalates to the human.
- Nothing is marked done until the technician confirms.
"""
import json
import os
import time
import uuid

from PIL import Image

from . import config, models, vision

LOCATE_NOUNS = ["chip", "connector", "capacitor"]  # nouns SAM 3.1 reliably found on our real boards
MAX_MEASUREMENTS = 12
MAX_JUDGE_RETRIES = 3

INTENT_SCHEMA = {
    "type": "object",
    "properties": {
        "intent": {"type": "string", "enum": [
            "confirm", "deny", "board_id", "symptom", "measurement", "question", "stop", "other"]},
        "language": {"type": "string", "enum": ["english", "telugu", "tamil", "hindi", "mixed"]},
        "board_id": {"type": ["string", "null"]},
        "symptom": {"type": ["string", "null"]},
        "value": {"type": ["number", "null"], "description": "Numeric reading if the technician gave one."},
        "unit": {"type": "string", "enum": ["V", "mV", "ohm", "kohm", "A", "beep", "no_beep", "none"]},
        "question": {"type": ["string", "null"]},
    },
    "required": ["intent", "language", "board_id", "symptom", "value", "unit", "question"],
}

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": [
            "ask_symptom", "request_measurement", "request_closeup", "answer", "conclude", "escalate"]},
        "test_point": {"type": "string", "description": "Physical description findable on the board."},
        "locate_noun": {"type": "string", "enum": LOCATE_NOUNS + ["none"]},
        "locate_description": {"type": "string", "description": "Which one, e.g. 'chip next to the DC jack'."},
        "meter_mode": {"type": "string", "enum": ["dc_voltage", "resistance", "diode", "continuity", "none"]},
        "power_state": {"type": "string", "enum": ["adapter_connected", "all_power_removed", "none"]},
        "expected_min": {"type": ["number", "null"]},
        "expected_max": {"type": ["number", "null"]},
        "expected_unit": {"type": "string", "enum": ["V", "ohm", "beep", "none"]},
        "why": {"type": "string", "description": "One short sentence, technician-facing."},
        "suspect_component": {"type": "string"},
        "evidence_ids": {"type": "array", "items": {"type": "integer"}},
        "fix": {"type": "string"},
        "say_en": {"type": "string", "description": "What to say, English, max 2 short sentences."},
        "say_local": {"type": "string", "description": "Same message in the technician's language."},
    },
    "required": ["action", "test_point", "locate_noun", "locate_description", "meter_mode", "power_state",
                 "expected_min", "expected_max", "expected_unit", "why", "suspect_component",
                 "evidence_ids", "fix", "say_en", "say_local"],
}

JUDGE_SCHEMA = {
    "type": "object",
    "properties": {
        "supported": {"type": "boolean"},
        "problems": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["supported", "problems"],
}

PLANNER_RULES = """You are BenchSight, a hands-free assistant guiding a laptop motherboard repair technician in a
small Indian repair shop. The technician's hands hold multimeter probes; keep every message short and spoken-style.

Hard rules:
- Ask for exactly ONE measurement at a time, with meter mode, power state and an expected range.
  For request_measurement, say_en MUST state: meter mode, where the red and black probes go, and the
  expected value range with units (e.g. 'about 19 to 20 volts').
- Resistance, diode and continuity checks: all power removed (adapter and battery unplugged).
- Describe test points so they can be found on THIS board (labels, nearby connectors, printed codes).
  Use locate_noun 'chip', 'connector' or 'capacitor' only when the point is on or at one of those; else 'none'.
- Never invent readings or facts. Base conclusions ONLY on the evidence log, citing evidence ids.
- Conclude only when the evidence clearly isolates one component or area; otherwise keep measuring.
- If the evidence is contradictory or you are not confident after many steps, choose 'escalate'.
- say_local: same meaning in the technician's language (Telugu script for Telugu, Tamil script for Tamil,
  Devanagari for Hindi, English otherwise). Keep technical terms (volts, ohms, MOSFET, fuse) in English.
- Unused fields: empty string, 'none', null or [] as appropriate."""

LOCAL_FALLBACK = {
    "confirm_board": "I can read the board ID {bid}. Is that correct?",
}


def _norm(value, unit):
    if value is None:
        return None, unit
    if unit == "mV":
        return value / 1000.0, "V"
    if unit == "kohm":
        return value * 1000.0, "ohm"
    return value, unit


class Session:
    def __init__(self, sid: str = None):
        self.id = sid or time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4]
        self.dir = os.path.join(config.RUNS_DIR, self.id)
        os.makedirs(self.dir, exist_ok=True)
        self.phase = "need_overview"
        self.board = {"views": [], "ids": [], "confirmed": False, "maker": "unknown", "labels": [], "damage": []}
        self.symptom = None
        self.evidence = []
        self.pending = None
        self.overview_path = None
        self.language = "english"
        self.history = []
        self.judge_failures = 0
        self.proposed = None
        self.highlight_n = 0

    # ---------- persistence ----------
    def snapshot(self) -> dict:
        return {k: getattr(self, k) for k in (
            "id", "phase", "board", "symptom", "evidence", "pending", "overview_path", "language",
            "history", "judge_failures", "proposed")}

    def save(self):
        with open(os.path.join(self.dir, "state.json"), "w", encoding="utf-8") as f:
            json.dump(self.snapshot(), f, ensure_ascii=False, indent=2)

    def _reply(self, say_en, say_local=None, highlight=None, heard=None, extra=None):
        say_local = say_local or say_en
        self.history.append({"heard": heard, "say_en": say_en, "say_local": say_local, "phase": self.phase})
        self.save()
        out = {"say_en": say_en, "say_local": say_local, "language": self.language, "phase": self.phase,
               "highlight": highlight, "heard": heard, "board": self.board, "evidence": self.evidence,
               "pending": self.pending, "proposed": self.proposed}
        if extra:
            out.update(extra)
        return out

    # ---------- images ----------
    def add_image(self, path: str, kind: str = "overview") -> dict:
        img = Image.open(path)
        if kind == "closeup" and self.pending and self.pending.get("action") == "request_closeup":
            return self._handle_closeup(img, path)

        read = vision.read_board(self.dir, img)
        self.overview_path = path
        self.board["views"].append({"path": os.path.basename(path), "read": read})
        self.board["labels"] = sorted(set(self.board["labels"]) | set(read.get("labels", [])))
        self.board["damage"] = read.get("damage", [])
        if read.get("maker") and read["maker"] != "unknown":
            self.board["maker"] = read["maker"]

        if self.board["confirmed"]:
            return self._reply("Got the new view of the board.", None)

        all_ids = [v["read"].get("board_ids", []) for v in self.board["views"]]
        latest = read.get("board_ids", [])
        disagreement = len(all_ids) > 1 and set(all_ids[-1]) != set(all_ids[-2]) and all_ids[-2]
        self.board["ids"] = latest
        damage = read.get("damage", [])
        dmg_txt = ""
        if damage:
            dmg_txt = " I also see " + "; ".join(f"{d['what']} at {d['where']}" for d in damage[:2]) + "."
        if not latest:
            self.phase = "confirm_board"
            return self._reply(
                "I can't read a board ID clearly. Please tell me the board model, or show me the printed ID "
                "closer." + dmg_txt)
        bid = latest[0]
        self.phase = "confirm_board"
        if disagreement:
            return self._reply(
                f"Two views disagree on the board ID: {', '.join(all_ids[-2])} versus {', '.join(latest)}. "
                f"Please read the ID on the board to me." + dmg_txt)
        others = [i for i in latest[1:]][:2]
        unc = read.get("uncertain", [])[:2]
        msg = f"I read the board number {bid}"
        if others:
            msg += f" (also {', '.join(others)})"
        msg += ". Please check it against the board."
        if unc:
            msg += f" Partly readable: {', '.join(unc)}."
        msg += " Is it correct?"
        return self._reply(msg + dmg_txt)

    def _handle_closeup(self, img, path):
        p = self.pending
        box, info = vision.locate(self.dir, img, "chip", p.get("locate_description") or "the chip in the centre")
        if box is None:
            marking = {"legible": False, "marking": "", "uncertain_chars": "no chip located"}
        else:
            marking = vision.read_marking(self.dir, img, box)
        ev = {"id": len(self.evidence) + 1, "kind": "marking", "test_point": p.get("test_point"),
              "value": marking.get("marking"), "legible": marking.get("legible"),
              "uncertain": marking.get("uncertain_chars"), "verdict": "observation", "confirmed": False}
        self.evidence.append(ev)
        self.pending = {"action": "confirm_marking", "evidence_id": ev["id"]}
        if not marking.get("legible"):
            return self._reply("I couldn't read that marking. Please move closer and light it from the side, "
                               "or read it to me.", None)
        hl = None
        if box is not None:
            self.highlight_n += 1
            hl = os.path.basename(vision.draw_highlight(
                img, box, marking.get("marking", "")[:24],
                os.path.join(self.dir, f"highlight_{self.highlight_n}.jpg")))
        return self._reply(f"The marking reads {marking['marking']}. Is that right?", None, highlight=hl)

    # ---------- speech / text ----------
    def handle_text(self, text: str) -> dict:
        text = (text or "").strip()
        if not text:
            return self._reply("I didn't catch that. Please say it again.", None, heard=text)
        intent = models.spark_json(
            self.dir, "parse_intent", "Intent", INTENT_SCHEMA,
            [models.text_part(
                "A laptop repair technician said this (may mix Telugu, Tamil, Hindi and English, transcribed by ASR, "
                "numbers may appear as digits or words). Current step: "
                + json.dumps({"phase": self.phase, "pending": self.pending}, ensure_ascii=False)
                + "\nClassify it. 'confirm' = yes/correct/avunu/sare/aamaa/seri/haan; 'deny' = no/kaadu/illa/nahi/wrong. "
                  "'mixed' means Telugu mixed with English.\n"
                  f"Utterance: {text}")],
            effort="low", max_output_tokens=2500,
        )
        self.language = intent.get("language") or self.language
        it = intent.get("intent")

        if it == "stop":
            self.phase = "done"
            return self._reply("Stopping here. The full log is saved.", None, heard=text)

        if self.pending and self.pending.get("action") == "confirm_marking":
            ev = self.evidence[self.pending["evidence_id"] - 1]
            if it == "confirm":
                ev["confirmed"] = True
            elif it in ("deny", "board_id", "other") and (intent.get("board_id") or it == "deny"):
                ev["value"] = intent.get("board_id") or ev["value"]
                ev["confirmed"] = bool(intent.get("board_id"))
                ev["corrected_by_technician"] = True
            self.pending = None
            return self._plan(heard=text)

        if self.phase in ("need_overview", "confirm_board"):
            if it == "confirm" and self.board["ids"]:
                self.board["confirmed"] = True
            elif it == "board_id" and intent.get("board_id"):
                self.board["ids"] = [intent["board_id"]]
                self.board["confirmed"] = True
                self.board["corrected_by_technician"] = True
            elif it == "deny":
                return self._reply("Okay. Please read the board ID printed on the board.", None, heard=text)
            if intent.get("symptom"):
                self.symptom = intent["symptom"]
            if not self.board["confirmed"]:
                return self._reply("First, let's confirm the board. Is the ID correct?", None, heard=text)
            if not self.symptom:
                self.phase = "need_symptom"
                return self._reply("Board confirmed. What is the problem with this laptop?", None, heard=text)
            self.phase = "diagnosing"
            return self._plan(heard=text)

        if self.phase == "need_symptom":
            self.symptom = intent.get("symptom") or text
            self.phase = "diagnosing"
            return self._plan(heard=text)

        if self.phase == "awaiting_signoff":
            if it == "confirm":
                self.phase = "done"
                return self._reply(
                    f"Marked as diagnosed: {self.proposed['suspect_component']}. Fix: {self.proposed['fix']}. "
                    "The full evidence log is saved.", None, heard=text)
            self.evidence.append({"id": len(self.evidence) + 1, "kind": "technician_note",
                                  "test_point": "conclusion rejected", "value": text, "verdict": "observation"})
            self.proposed = None
            self.phase = "diagnosing"
            return self._plan(heard=text)

        if self.phase == "diagnosing":
            if self.pending and self.pending.get("action") == "request_measurement" and (
                    it == "measurement" or intent.get("unit") in ("beep", "no_beep")):
                self._record_measurement(intent, text)
            elif it == "symptom" and intent.get("symptom"):
                self.symptom = f"{self.symptom}; {intent['symptom']}" if self.symptom else intent["symptom"]
            return self._plan(heard=text, question=intent.get("question") if it == "question" else None)

        return self._reply("This session is finished. Start a new one for the next board.", None, heard=text)

    def _record_measurement(self, intent, text):
        p = self.pending
        unit = intent.get("unit")
        value, unit_n = _norm(intent.get("value"), unit)
        if unit in ("beep", "no_beep"):
            value, unit_n = (1.0 if unit == "beep" else 0.0), "beep"
        lo, hi, eu = p.get("expected_min"), p.get("expected_max"), p.get("expected_unit")
        verdict = "unrated"
        if value is not None and eu == unit_n and (lo is not None or hi is not None):
            ok = (lo is None or value >= lo) and (hi is None or value <= hi)
            verdict = "normal" if ok else "abnormal"
        self.evidence.append({
            "id": len(self.evidence) + 1, "kind": "measurement", "test_point": p.get("test_point"),
            "meter_mode": p.get("meter_mode"), "power_state": p.get("power_state"),
            "expected": {"min": lo, "max": hi, "unit": eu}, "value": value, "unit": unit_n,
            "verdict": verdict, "heard": text,
        })
        self.pending = None

    # ---------- planning + verification ----------
    def _context(self) -> str:
        return json.dumps({
            "board": {"ids": self.board["ids"], "maker": self.board["maker"], "labels": self.board["labels"],
                      "visible_damage": self.board["damage"], "confirmed_by_technician": self.board["confirmed"]},
            "symptom": self.symptom,
            "evidence_log": self.evidence,
            "technician_language": self.language,
        }, ensure_ascii=False)

    def _plan(self, heard=None, question=None, feedback=None) -> dict:
        n_meas = sum(1 for e in self.evidence if e["kind"] == "measurement")
        if n_meas >= MAX_MEASUREMENTS:
            self.phase = "escalated"
            return self._reply(f"We have {n_meas} measurements and no clear single fault. I recommend checking "
                               "the schematic or an expert. The evidence log is saved.", None, heard=heard)
        content = [models.text_part(PLANNER_RULES + "\n\nCurrent case:\n" + self._context())]
        if question:
            content.append(models.text_part(f"The technician asked: {question}. Answer briefly (action 'answer'), "
                                            "then they will continue."))
        if feedback:
            content.append(models.text_part("Your previous conclusion FAILED verification:\n- "
                                            + "\n- ".join(feedback) + "\nFix this: measure more or escalate."))
        if self.overview_path:
            content.append(models.image_part(Image.open(self.overview_path), max_side=1400))
        plan = models.spark_json(self.dir, "plan_next_step", "Plan", PLAN_SCHEMA, content,
                                 effort="medium", max_output_tokens=7000)
        action = plan["action"]

        if action == "conclude":
            return self._verify_and_propose(plan, heard)
        if action == "escalate":
            self.phase = "escalated"
            return self._reply(plan["say_en"], plan["say_local"], heard=heard)
        if action in ("request_measurement", "request_closeup"):
            self.pending = {k: plan[k] for k in (
                "test_point", "locate_noun", "locate_description", "meter_mode", "power_state",
                "expected_min", "expected_max", "expected_unit", "why")}
            self.pending["action"] = action
            hl = None
            if action == "request_measurement" and plan["locate_noun"] in LOCATE_NOUNS and self.overview_path:
                img = Image.open(self.overview_path)
                box, info = vision.locate(self.dir, img, plan["locate_noun"], plan["locate_description"])
                self.pending["locate_info"] = info
                if box is not None:
                    self.highlight_n += 1
                    hl = os.path.basename(vision.draw_highlight(
                        img, box, "MEASURE HERE", os.path.join(self.dir, f"highlight_{self.highlight_n}.jpg")))
            return self._reply(plan["say_en"], plan["say_local"], highlight=hl, heard=heard)
        return self._reply(plan["say_en"], plan["say_local"], heard=heard)

    def _code_checks(self, plan) -> list:
        problems = []
        ids = plan.get("evidence_ids") or []
        by_id = {e["id"]: e for e in self.evidence}
        meas = [e for e in self.evidence if e["kind"] == "measurement"]
        if not plan.get("suspect_component"):
            problems.append("No suspect component named.")
        if len(meas) < 2:
            problems.append(f"Only {len(meas)} measurement(s) recorded; at least 2 are required.")
        missing = [i for i in ids if i not in by_id]
        if not ids:
            problems.append("Conclusion cites no evidence ids.")
        if missing:
            problems.append(f"Cited evidence ids do not exist: {missing}.")
        if ids and not any(by_id.get(i, {}).get("verdict") == "abnormal" for i in ids):
            problems.append("None of the cited evidence is an abnormal measurement.")
        return problems

    def _verify_and_propose(self, plan, heard):
        problems = self._code_checks(plan)
        if not problems:
            judge = models.spark_json(
                self.dir, "judge_conclusion", "Judge", JUDGE_SCHEMA,
                [models.text_part(
                    "You are a strict senior board-repair reviewer. Decide whether this conclusion follows from "
                    "the recorded evidence alone (no assumptions, no unrecorded readings). List concrete problems "
                    "if not.\nCase:\n" + self._context()
                    + "\nProposed conclusion:\n" + json.dumps(
                        {k: plan[k] for k in ("suspect_component", "evidence_ids", "fix", "why")},
                        ensure_ascii=False))],
                effort="medium", max_output_tokens=5000,
            )
            if not judge.get("supported"):
                problems = ["Judge: " + p for p in (judge.get("problems") or ["not supported by evidence"])]
        if problems:
            self.judge_failures += 1
            self.evidence_check = problems
            if self.judge_failures >= MAX_JUDGE_RETRIES:
                self.phase = "escalated"
                return self._reply(
                    "I could not verify a conclusion after 3 tries, so I won't guess. "
                    "Please check with the schematic or a senior technician. Evidence log saved.",
                    None, heard=heard, extra={"verification_problems": problems})
            return self._plan(heard=heard, feedback=problems)
        self.proposed = {k: plan[k] for k in ("suspect_component", "evidence_ids", "fix", "why")}
        self.phase = "awaiting_signoff"
        return self._reply(
            plan["say_en"] + " Verified against the evidence. Say confirm to mark it, or tell me if you disagree.",
            plan["say_local"], heard=heard, extra={"verified": True})
