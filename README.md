# Voice Attribute Service

A Spring Boot API that accepts speech audio and returns estimated vocal gender, age bracket, thresholded confidence, processing time, and audio quality. It is designed as an assignment demonstration, not an identity, eligibility, or high-stakes decision system.

## Architecture

`client → Spring Boot → FFmpeg normalization → quality gate → internal FastAPI/ONNX → confidence mapping → response`.

Spring Boot is the public boundary: it validates uploads, invokes ffmpeg to decode and normalize, assesses quality, applies result thresholds, measures time, logs safe metadata, and deletes temporary files. The private Python service loads the model once and receives only an in-memory normalized WAV request. Spring Boot is used for its mature HTTP validation, Actuator health support, DTO/error handling, and straightforward operational integration. The inference container is not published by Compose.

## Design Write-up

This design keeps a conventional Spring Boot service as the public API because Java is a strong fit for request validation, safe multipart handling, Actuator health checks, structured error responses, and deployment in existing backend environments. Audio classifiers are more practical in Python, so a small internal FastAPI worker owns the model. It loads audEERING’s publicly downloadable six-layer Wav2Vec2 age/gender ONNX model once at startup, avoiding per-request model loading. Each upload is ignored by filename, size limited, decoded through ffmpeg, converted to mono 16 kHz signed-16-bit WAV, and examined for duration, energy, silence, and clipping. Silent/too-short recordings produce `insufficient` and unknown outputs; degraded recordings reduce age confidence. The worker softmaxes the model’s female/male/child logits and applies a configurable gender threshold. The age regression score is converted to years and mapped to the required brackets; its conservative confidence measures distance from bracket boundaries, not a claimed calibrated probability. Temporary input and normalized files are removed in all backend outcomes, while the inference worker reads bytes in memory. Warm latency depends heavily on CPU and model download/cold start is much longer; sub-500 ms is a target, not a guarantee. With more time I would validate/calibrate against consented, representative data. At 1,000 concurrent calls, independently autoscaled GPU/CPU inference workers, backpressure, batching, and stateless Spring replicas behind a load balancer are required.

## Model and limitations

The inference model is [`audeering/wav2vec2-large-robust-6-ft-age-gender`](https://github.com/audeering/w2v2-age-gender-how-to), downloaded automatically from its public Zenodo release. It is a Wav2Vec2 model fine-tuned on aGender, Mozilla Common Voice, TIMIT, and VoxCeleb2. Its `audonnx` invocation returns a mapping with `hidden_states`, `logits_age`, and `logits_gender`; only the named age/gender outputs are used. `logits_age` is documented as a 0–1 age score, which the service converts to years. The published ONNX example contains negative gender values, so `logits_gender` is treated as logits and passed through stable softmax in the documented `female`, `male`, `child` order. The release is **CC BY-NC-SA 4.0** and therefore appropriate for this non-commercial assignment/demo only; obtain the relevant rights before commercial use.

Gender is an estimated vocal/biological-sex classifier output, not a person’s self-identified gender; a highest `child` class or low confidence becomes `unknown`. Voice age is inherently uncertain, can be biased by language, health, microphone, accent, and training demographics, and must not be used for sensitive decisions. Age mapping is `18–30`, `31–45`, `46–60`, and `>60`; estimated under-18, over-100, or child results become unknown. **Age confidence is a heuristic confidence score derived from the model's age estimate and its distance from the requested bracket boundaries. It is not a calibrated probability.**

## API

`POST /analyze` accepts `multipart/form-data` with a UUID `contact_id` and `audio` file. WAV, MP3, OGG, FLAC, AAC, and other ffmpeg-decodable formats work via multipart. Raw `audio/wav`, `audio/mpeg`, `audio/ogg`, `audio/flac`, or `audio/aac` is also accepted with `contact_id` as a query parameter.

```bash
curl -X POST http://localhost:8080/analyze \
  -F contact_id=123e4567-e89b-12d3-a456-426614174000 \
  -F 'audio=@sample.wav;type=audio/wav'
```

```json
{"contact_id":"123e4567-e89b-12d3-a456-426614174000","gender":{"prediction":"male","confidence":0.87},"age_bracket":{"prediction":"31-45","confidence":0.63},"processing_ms":142,"audio_quality":"good"}
```

`GET /actuator/health` includes the internal inference dependency. Errors use `{timestamp,status,error,message,path}`. Typical codes are `INVALID_REQUEST` (400), `EMPTY_AUDIO` (400), `INVALID_AUDIO` (400), `FILE_TOO_LARGE` (413), `AUDIO_TOO_LONG` (422), `INFERENCE_UNAVAILABLE` (503), and `INFERENCE_TIMEOUT` (504). Stack traces and audio data are never returned.

## Audio, quality, privacy, and observability

ffmpeg converts all accepted media to mono, 16 kHz, PCM WAV. The quality check labels a decoded recording `insufficient` when short, silent, or low energy; clipping, limited non-silence, and low energy result in `degraded`; otherwise it is `good`. Insufficient input bypasses model inference and returns unknown predictions with zero confidence.

The backend uses randomly named OS temporary input/output files only for decoding and removes input immediately after conversion and normalized output in `finally`, including inference failures. The Python service never writes request audio. No database, object storage, filename logging, audio bytes, or transcript is used. Logs contain request ID (via `X-Request-ID`), contact ID, duration, quality, outcome, and processing time only.

## Run with Docker

```bash
docker compose up --build
./scripts/smoke-test.sh
curl http://localhost:8080/actuator/health
```

The first inference startup downloads public model weights and can take several minutes depending on network; its healthcheck stays down until the model is loaded. No host Java, Python, ffmpeg, or model installation is needed. The synthetic smoke WAV is intentionally not meaningful speech, so it is only an ingestion/quality smoke test and may return unknown.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `MAX_FILE_SIZE` | `15MB` | Spring multipart upload cap |
| `MAX_FILE_BYTES` | `15728640` | streaming input cap |
| `MAX_AUDIO_DURATION_SECONDS` | `60` | decoded duration cap |
| `MIN_AUDIO_DURATION_SECONDS` | `1.0` | insufficient threshold |
| `INFERENCE_TIMEOUT_MS` | `15000` | backend-to-worker timeout |
| `GENDER_CONFIDENCE_THRESHOLD` | `0.60` | unknown cutoff |
| `AGE_CONFIDENCE_THRESHOLD` | `0.45` | unknown cutoff |
| `INFERENCE_SERVICE_URL` | `http://localhost:8000` | internal worker URL |

## Tests and evaluation

```bash
./mvnw test
```

The fast suite includes controller success/missing-field tests, corrupt/empty audio validation, silent/short/usable quality classification, all required age mappings, female/male/child/low-confidence mapping, structured inference JSON parsing, cleanup, inference failure, and health-indicator tests. Most tests mock the worker, so model weights are never downloaded.

Run the Python output-mapping unit tests inside the inference image after building it:

```bash
docker compose run --rm inference python -m unittest discover -s tests
```

`evaluation/evaluate.py results.csv` optionally summarizes labelled results supplied by an operator; it deliberately does not download a dataset.

## Sample audio and benchmark

See [sample/README.md](sample/README.md) for obtaining a suitably licensed real speech sample. The generated sine WAV tests ingestion only and is not an accuracy test. With a local five-second speech file and the stack running, collect actual end-to-end timing (min/max/average/p50/p95/p99) with:

```bash
./scripts/benchmark.sh sample/public-speech.wav 20
```

The script reports measured total request latency and deliberately does not invent a decode/inference split. The backend logs `processingMs`; model load and hardware materially affect results. No benchmark figures are published because none were measured in this repository environment.

## Latency, scaling, and security

The model stays resident in one worker and ONNX runs with one CPU thread by default. Cold start includes model download/load; warm performance varies with clip length and CPU/GPU and is not guaranteed under 500 ms. For 1,000 calls, use stateless Spring Boot replicas behind a load balancer with upload/request limits and connection pooling; route to a separately autoscaled inference pool with model-per-worker, GPU workers, bounded queues/backpressure, circuit breakers/timeouts, and safe batching. CPU-only workers are unlikely to comfortably sustain that load.

Implemented protections are upload/duration limits, accepted-media routing, filename disregard, decode validation, timeouts, cleanup, no stack-trace exposure, and internal-only inference networking. Production still needs TLS, authentication/authorization, rate limiting, WAF/API gateway, network policy, malware scanning policy, secret management, and consent/retention governance. WebSocket progressive inference is intentionally omitted to keep the REST path robust. Future work includes diarization, voice-activity detection, model calibration across consented demographics, authentication, and load testing.
