"""Internal, non-public inference service. No audio is written to disk."""
import io, os, zipfile, urllib.request
from contextlib import asynccontextmanager
import numpy as np
import audonnx
import soundfile as sf
from fastapi import FastAPI, HTTPException, Request
from model_output import extract_prediction

MODEL_URL = os.getenv("MODEL_URL", "https://zenodo.org/records/7761387/files/w2v2-L-robust-6-age-gender.25c844af-1.1.1.zip")
MODEL_DIR = os.getenv("MODEL_DIR", "/models/age-gender")
MAX_AUDIO_BYTES = int(os.getenv("MAX_AUDIO_BYTES", "15728640"))
model = None

def load_model():
    global model
    if not os.path.exists(os.path.join(MODEL_DIR, "config.yaml")):
        os.makedirs(MODEL_DIR, exist_ok=True)
        archive = "/tmp/model.zip"
        try:
            urllib.request.urlretrieve(MODEL_URL, archive)
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(MODEL_DIR)
        finally:
            if os.path.exists(archive):
                os.remove(archive)
    model = audonnx.load(MODEL_DIR)

@asynccontextmanager
async def lifespan(_: FastAPI):
    load_model()
    yield

app = FastAPI(title="voice-attribute-inference", docs_url=None, redoc_url=None, lifespan=lifespan)

@app.get("/health")
def health():
    if model is None:
        raise HTTPException(503, "model not ready")
    return {"status": "UP"}

@app.post("/infer")
async def infer(request: Request):
    if model is None:
        raise HTTPException(503, "model not ready")
    if request.headers.get("content-type", "").split(";", 1)[0].lower() not in {"audio/wav", "audio/x-wav"}:
        raise HTTPException(415, "expected audio/wav")
    payload = await request.body()
    if not payload:
        raise HTTPException(400, "audio request body is empty")
    if len(payload) > MAX_AUDIO_BYTES:
        raise HTTPException(413, "audio request body is too large")
    try:
        signal, sample_rate = sf.read(io.BytesIO(payload), dtype="float32", always_2d=False)
        if signal.ndim > 1: signal = signal.mean(axis=1)
        if sample_rate != 16000 or signal.size < 16000:
            raise ValueError("expected normalized 16 kHz audio")
        # audonnx returns a mapping: hidden_states, logits_age, logits_gender.
        outputs = model(signal.astype(np.float32), sample_rate)
        return extract_prediction(outputs)
    except (RuntimeError, ValueError, TypeError):
        # Input/media failures and malformed model results are intentionally generic.
        raise HTTPException(422, "inference could not process normalized audio") from None
    except Exception:
        raise HTTPException(500, "inference failed") from None
