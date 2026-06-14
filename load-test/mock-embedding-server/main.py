import asyncio
import math
import os
import random
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

EMBEDDING_DIM = 1024

DelayMode = Literal["fixed", "random", "outage"]

MODE: DelayMode = os.getenv("DELAY_MODE", "fixed")  # type: ignore
FIXED_MS = int(os.getenv("DELAY_MS", "200"))
RANDOM_MEDIAN_MS = int(os.getenv("DELAY_RANDOM_MEDIAN_MS", "300"))
RANDOM_SIGMA = float(os.getenv("DELAY_RANDOM_SIGMA", "0.6"))
OUTAGE_MS = int(os.getenv("DELAY_OUTAGE_MS", "30000"))
API_KEY = os.getenv("API_KEY", "local-dev-key")

app = FastAPI(title="Mock Embedding Server")


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]


def _verify_api_key(x_api_key: str | None) -> None:
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


def _compute_delay_ms() -> int:
    if MODE == "fixed":
        return FIXED_MS
    if MODE == "outage":
        return OUTAGE_MS
    if MODE == "random":
        median_seconds = RANDOM_MEDIAN_MS / 1000.0
        mu = math.log(median_seconds)
        value_seconds = random.lognormvariate(mu, RANDOM_SIGMA)
        return int(value_seconds * 1000)
    return FIXED_MS


@app.get("/v1/health")
def health(x_api_key: str | None = Header(default=None, alias="X-API-Key")):
    _verify_api_key(x_api_key)
    return {"status": "ok", "model_loaded": True}


@app.post("/v1/embed", response_model=EmbedResponse)
async def embed(
    req: EmbedRequest,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
):
    _verify_api_key(x_api_key)
    delay_ms = _compute_delay_ms()
    if delay_ms > 0:
        await asyncio.sleep(delay_ms / 1000.0)
    seed = abs(hash(req.text)) % (2**32)
    rng = random.Random(seed)
    embedding = [rng.uniform(-1.0, 1.0) for _ in range(EMBEDDING_DIM)]
    return EmbedResponse(embedding=embedding)
