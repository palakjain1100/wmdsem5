import os
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
model: SentenceTransformer | None = None
dimensions: int | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global model, dimensions
    model = SentenceTransformer(MODEL_NAME)
    dimensions = model.get_sentence_embedding_dimension()
    if not dimensions:
        raise RuntimeError(f"Embedding model '{MODEL_NAME}' did not report its vector dimensions.")
    yield
    model = None
    dimensions = None


app = FastAPI(title="Document Embedding Service", lifespan=lifespan)


class EmbedRequest(BaseModel):
    model: str | None = None
    input: Annotated[list[str], Field(min_length=1, max_length=24)]


@app.get("/health")
def health():
    return {"status": "ok" if model is not None else "starting", "model": MODEL_NAME, "dimensions": dimensions}


@app.post("/embed")
def embed(request: EmbedRequest):
    if request.model and request.model != MODEL_NAME:
        raise HTTPException(status_code=400, detail=f"This service is configured for '{MODEL_NAME}'.")
    if model is None:
        raise HTTPException(status_code=503, detail="Embedding model is still loading.")
    if any(not text.strip() for text in request.input):
        raise HTTPException(status_code=400, detail="Embedding inputs must not be blank.")
    vectors = model.encode(request.input, normalize_embeddings=True, convert_to_numpy=True)
    return {
        "model": MODEL_NAME,
        "dimensions": dimensions,
        "embeddings": vectors.tolist(),
    }
