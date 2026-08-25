"""Internal, non-public inference service. No audio is written to disk."""

import io
import os
import time
import zipfile
import urllib.request
from contextlib import asynccontextmanager

import numpy as np
import audonnx
import soundfile as sf
from fastapi import FastAPI, HTTPException, Request

from model_output import extract_prediction


MODEL_URL = os.getenv(
    "MODEL_URL",
    "https://zenodo.org/records/7761387/files/"
    "w2v2-L-robust-6-age-gender.25c844af-1.1.1.zip",
)

MODEL_DIR = os.getenv(
    "MODEL_DIR",
    "/models/age-gender",
)

MAX_AUDIO_BYTES = int(
    os.getenv("MAX_AUDIO_BYTES", "15728640")
)

model = None


def load_model():
    """
    Download and load the age/gender model once during application startup.

    The model is downloaded only when it is not already present.
    The downloaded archive is deleted after extraction.
    """

    global model

    config_path = os.path.join(
        MODEL_DIR,
        "config.yaml",
    )

    if not os.path.exists(config_path):
        os.makedirs(
            MODEL_DIR,
            exist_ok=True,
        )

        archive = "/tmp/model.zip"

        try:
            urllib.request.urlretrieve(
                MODEL_URL,
                archive,
            )

            with zipfile.ZipFile(
                archive,
                "r",
            ) as zf:
                zf.extractall(MODEL_DIR)

        finally:
            if os.path.exists(archive):
                os.remove(archive)

    # IMPORTANT:
    # Load the model only after the model files have been
    # downloaded/extracted.
    #
    # Do not pass a dictionary as session_options here.
    # The installed audonnx/onnxruntime combination expects
    # audonnx to create/manage the runtime session.
    model = audonnx.load(MODEL_DIR)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """
    Load the model before the service starts accepting requests.
    """

    load_model()

    yield


app = FastAPI(
    title="voice-attribute-inference",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)


@app.get("/health")
def health():
    """
    Health endpoint used by Docker Compose.
    """

    if model is None:
        raise HTTPException(
            status_code=503,
            detail="model not ready",
        )

    return {
        "status": "UP"
    }


@app.post("/infer")
async def infer(request: Request):
    """
    Run age/gender inference on normalized WAV audio.

    Expected input:
      Content-Type: audio/wav
      Body: PCM WAV, mono, 16 kHz
    """

    if model is None:
        raise HTTPException(
            status_code=503,
            detail="model not ready",
        )

    content_type = (
        request.headers
        .get("content-type", "")
        .split(";", 1)[0]
        .lower()
    )

    if content_type not in {
        "audio/wav",
        "audio/x-wav",
    }:
        raise HTTPException(
            status_code=415,
            detail="expected audio/wav",
        )

    payload = await request.body()

    if not payload:
        raise HTTPException(
            status_code=400,
            detail="audio request body is empty",
        )

    if len(payload) > MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=413,
            detail="audio request body is too large",
        )

    try:
        decode_start = time.perf_counter()

        signal, sample_rate = sf.read(
            io.BytesIO(payload),
            dtype="float32",
            always_2d=False,
        )

        if signal.ndim > 1:
            signal = signal.mean(axis=1)

        decode_ms = (
            time.perf_counter() - decode_start
        ) * 1000

        # The Spring Boot service is responsible for normalizing
        # audio before calling this internal service.
        if sample_rate != 16000:
            raise ValueError(
                "expected normalized 16 kHz audio"
            )

        if signal.size < 16000:
            raise ValueError(
                "audio must contain at least one second"
            )

        inference_start = time.perf_counter()

        # sf.read(..., dtype="float32") already gives us float32.
        # Avoid an unnecessary array copy.
        outputs = model(
            signal,
            sample_rate,
        )

        inference_ms = (
            time.perf_counter() - inference_start
        ) * 1000

        total_ms = (
            decode_ms + inference_ms
        )

        print(
            "inference_timing "
            f"decode_ms={decode_ms:.2f} "
            f"inference_ms={inference_ms:.2f} "
            f"total_ms={total_ms:.2f}",
            flush=True,
        )

        return extract_prediction(outputs)

    except (
        RuntimeError,
        ValueError,
        TypeError,
    ):
        raise HTTPException(
            status_code=422,
            detail="inference could not process normalized audio",
        ) from None

    except Exception:
        raise HTTPException(
            status_code=500,
            detail="inference failed",
        ) from None