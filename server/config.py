"""BenchSight configuration. The API key comes only from the MODEL_API_KEY environment variable."""
import os


def _load_dotenv():
    """Read KEY=VALUE lines from a .env file in the project root, if present (never committed)."""
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))


_load_dotenv()

BASE_URL = "https://api.meta.ai/v1"

# Meta models used at runtime (all calls go through server/models.py)
SPARK = "muse-spark-1.3-contributor"   # vision + reasoning; Contributor variant is covered by hackathon credits
SAM = "sam-3.1"                        # open-vocabulary segmentation (boxes)
ASR = "muse-voice-transcribe-1.0"      # speech to text, Telugu / Hindi / English, code-switching

# Rough cost estimate used by the budget guard (US$). Spark: listed Muse Spark 1.3 price per 1M tokens,
# used as an upper bound for the Contributor variant. SAM: per image. ASR: per audio hour.
SPARK_IN_PER_M = 1.25
SPARK_OUT_PER_M = 4.25
SAM_PER_IMAGE = 0.0025
ASR_PER_HOUR = 0.18

BUDGET_USD = float(os.environ.get("BENCHSIGHT_BUDGET_USD", "10"))

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNS_DIR = os.path.join(ROOT, "runs")
DEMO_RUNS_DIR = os.path.join(ROOT, "demo_runs")  # recorded sessions committed for judges (replay, no API key)

LANGUAGE_BIAS = ["Telugu", "Tamil", "Hindi", "English"]
ASR_KEYWORDS = [
    "motherboard", "multimeter", "MOSFET", "capacitor", "inductor", "short", "ohms", "volts",
    "charging IC", "DC jack", "3.3 volt", "5 volt", "19 volt", "fuse", "diode mode", "beep",
]
