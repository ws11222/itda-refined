import json
import logging
import time
from dotenv import load_dotenv
from langchain_core.output_parsers import JsonOutputParser
from langchain_google_genai import ChatGoogleGenerativeAI

from graph.prompts import build_trim_program_prompt

from .state import GraphState

load_dotenv()

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

llm = ChatGoogleGenerativeAI(model="gemini-3.1-flash-lite-preview", temperature=0)
parser = JsonOutputParser()

_last_call_time = 0
_MIN_INTERVAL = 4  # seconds between calls (stay under 15 RPM free tier)
_trim_count = 0


def trim_program(state: GraphState):
    global _last_call_time, _trim_count
    _trim_count += 1

    elapsed = time.time() - _last_call_time
    wait = max(0, _MIN_INTERVAL - elapsed)
    if wait > 0:
        logger.info(f"[trim #{_trim_count}] rate limit 대기 {wait:.1f}s")
        time.sleep(wait)

    raw_program = state["raw_program"]
    title = raw_program.get("title", raw_program.get("서비스명", "unknown"))
    logger.info(f"[trim #{_trim_count}] 요청 시작: {title[:40]}")

    prompt = build_trim_program_prompt(parser.get_format_instructions())
    chain = prompt | llm | parser

    start = time.time()
    trimmed_program = chain.invoke({"raw_program": json.dumps(raw_program)})
    duration = time.time() - start
    _last_call_time = time.time()

    logger.info(f"[trim #{_trim_count}] 완료 ({duration:.1f}s): {trimmed_program.get('title', '')[:40]}")

    return {"trimmed_program": trimmed_program}


def validate_program(state: GraphState):
    return {"is_valid": True}
