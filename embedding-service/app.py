from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

app = FastAPI()

MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
model = SentenceTransformer(MODEL_NAME)
MODEL_DIMENSION = model.get_sentence_embedding_dimension()


class EmbeddingRequest(BaseModel):
    text: str
    mode: str = "document"


class EmbeddingResponse(BaseModel):
    vector: list[float]
    model: str
    dimension: int


class InfoResponse(BaseModel):
    model: str
    dimension: int


@app.get("/info")
def info() -> InfoResponse:
    return InfoResponse(model=MODEL_NAME, dimension=MODEL_DIMENSION)


@app.post("/embeddings")
def embed(req: EmbeddingRequest) -> EmbeddingResponse:
    vector = model.encode(req.text).tolist()
    return EmbeddingResponse(vector=vector, model=MODEL_NAME, dimension=MODEL_DIMENSION)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "dimensions": MODEL_DIMENSION}
