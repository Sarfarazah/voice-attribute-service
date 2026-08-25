# Voice Attribute Service

A Spring Boot API that accepts speech audio and returns estimated vocal gender, age bracket, thresholded confidence, processing time, and audio quality. It is designed as an assignment demonstration, not an identity, eligibility, or high-stakes decision system.

## Architecture

```text
Client
  |
  v
Spring Boot REST API
  |
  +-- Upload validation
  |
  +-- FFmpeg audio normalization
  |
  +-- Audio quality gate
  |
  v
Internal FastAPI / ONNX inference service
  |
  +-- Wav2Vec2 age/gender model
  |
  v
Prediction mapping + confidence thresholds
  |
  v
Structured API response
```
The Spring Boot service is the public API boundary. It validates uploads, invokes FFmpeg to decode and normalize audio, performs basic audio-quality checks, applies result thresholds, measures processing time, logs safe metadata, and removes temporary files.

The inference service is a private FastAPI service. It loads the ONNX model once during startup and receives only the normalized WAV bytes in memory. The inference container is not exposed publicly by Docker Compose.

The separation allows the API layer and inference layer to be scaled independently.

## Design Write-up

This design keeps Spring Boot as the public API because Java is a strong fit for request validation, multipart handling, structured error responses, health checks, and operational integration with existing backend environments.

Audio inference is implemented as a small Python FastAPI service because the selected ONNX model and audonnx runtime are Python-oriented.

The model is loaded once during inference-service startup so that model initialization is not repeated for every request.

Each upload is validated for size and duration, decoded through FFmpeg, converted to mono 16 kHz PCM WAV, and evaluated for basic audio quality using duration, energy, silence, and clipping checks.

Low-quality or insufficient recordings are surfaced through the audio_quality field instead of silently treating every input as reliable.

The inference worker receives the normalized audio in memory and does not persist request audio.

Gender logits are converted using softmax and a configurable confidence threshold. The age regression output is converted into an estimated age and then mapped to the required age brackets. Age confidence is a conservative heuristic based on the estimated age and its distance from bracket boundaries; it is not a calibrated probability.

With more time, I would validate and calibrate the model against representative, consented data and investigate hardware acceleration and model optimization.

For 1,000 concurrent calls, I would use stateless Spring Boot replicas behind a load balancer and a separately autoscaled inference pool, with bounded queues, backpressure, GPU workers where available, connection pooling, timeouts, circuit breakers, and controlled batching.

## Model and limitations

Model and Limitations

The inference model is:

audeering/wav2vec2-large-robust-6-ft-age-gender

The model is downloaded automatically from its public Zenodo release during inference-service startup.

It is a Wav2Vec2-based model trained/fine-tuned using datasets including aGender, Mozilla Common Voice, TIMIT, and VoxCeleb2.

The audonnx invocation returns a mapping containing:

hidden_states
logits_age
logits_gender

Only the named age and gender outputs are used by this service.

The age output is documented as a normalized age score and is converted to an estimated age before mapping it to the required brackets.

The gender output is treated as logits and passed through a numerically stable softmax using the documented class ordering.

The model release is CC BY-NC-SA 4.0. It is therefore suitable for this non-commercial assignment/demo, subject to the applicable license terms. Appropriate rights and licensing would need to be obtained before commercial use.

Prediction limitations

Gender is an estimated vocal/biological-sex classification signal and is not a person's self-identified gender.

If the model predicts the child class or the confidence is below the configured threshold, the service returns unknown.

Voice-based age estimation is inherently uncertain and can be affected by:

- language
- accent
- microphone quality
- background noise
- compression
- speaking style
- health
- demographic differences between training and production data

Age predictions outside the supported range are returned as unknown.

Age brackets returned by the service are:

- 18–30
- 31–45
- 46–60
- 60+
- Unknown

Age confidence is a heuristic score and should not be interpreted as a calibrated probability.

## API

`POST /analyze` accepts `multipart/form-data` with a UUID `contact_id` and `audio` file. WAV, MP3, OGG, FLAC, AAC, and other ffmpeg-decodable formats work via multipart. Raw `audio/wav`, `audio/mpeg`, `audio/ogg`, `audio/flac`, or `audio/aac` is also accepted with `contact_id` as a query parameter.

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/analyze \
  -F 'contact_id=123e4567-e89b-12d3-a456-426614174000' \
  -F 'audio=@sample/public-speech.flac;type=audio/flac'
```
### Example response
```json
{"contact_id":"123e4567-e89b-12d3-a456-426614174000","gender":{"prediction":"male","confidence":0.87},"age_bracket":{"prediction":"31-45","confidence":0.63},"processing_ms":142,"audio_quality":"good"}
```
### Health
`curl http://localhost:8080/actuator/health` includes the internal inference dependency. Errors use `{timestamp,status,error,message,path}`. Typical codes are `INVALID_REQUEST` (400), `EMPTY_AUDIO` (400), `INVALID_AUDIO` (400), `FILE_TOO_LARGE` (413), `AUDIO_TOO_LONG` (422), `INFERENCE_UNAVAILABLE` (503), and `INFERENCE_TIMEOUT` (504). Stack traces and audio data are never returned.

### Error handling

Errors use a structured response similar to:

```json
{
  "timestamp": "...",
  "status": 400,
  "error": "INVALID_AUDIO",
  "message": "Audio cannot be decoded or is unsupported",
  "path": "/analyze"
}
```

Typical status codes include:

| Error | HTTP Status |
|---|---:|
| `INVALID_REQUEST` | 400 |
| `EMPTY_AUDIO` | 400 |
| `INVALID_AUDIO` | 400 |
| `FILE_TOO_LARGE` | 413 |
| `AUDIO_TOO_LONG` | 422 |
| `INFERENCE_UNAVAILABLE` | 503 |
| `INFERENCE_TIMEOUT` | 504 |

Stack traces and audio data are never returned to clients.

## Audio Quality

The backend performs a basic quality gate before inference.

Audio is classified as:

### good
The recording has sufficient duration and usable signal characteristics.

### degraded

The recording contains usable speech but shows potentially problematic characteristics such as:

- clipping
- low signal energy
- limited non-silent content
- noisy/degraded input characteristics
### insufficient

The recording is too short, silent, or has insufficient usable signal.

Insufficient recordings bypass model inference and return unknown predictions with zero confidence.

This prevents obviously unusable audio from being treated as a valid model input.


### Audio Normalization

All accepted media is normalized using FFmpeg to:

- **Channels:** Mono
- **Sample rate:** 16 kHz
- **Encoding:** PCM signed 16-bit WAV

The model therefore receives a consistent input format regardless of the original codec.

Temporary files are used only during the decoding/normalization stage and are removed after processing.

The inference service itself reads normalized WAV data in memory and does not write request audio to disk.

## Privacy

Caller audio is treated as sensitive input.

The service does not intentionally persist uploaded audio.

The backend:

- does not store audio in a database
- does not upload audio to object storage
- does not log audio bytes
- ignores the uploaded filename
- uses temporary OS files only when required for FFmpeg processing
- removes temporary files after processing
- removes temporary files even when inference fails
- sends only normalized audio to the private inference service
- does not perform transcription
- does not persist model input audio

Logs contain operational metadata such as request ID, contact ID, duration, quality, outcome, and processing time.

Production deployment should additionally implement authentication, authorization, TLS, rate limiting, retention controls, access auditing, and appropriate consent/privacy governance.

---

## Latency and Performance

### Assignment Target

The assignment specifies a target of:

**< 500 ms**

for end-to-end inference on a 5-second audio chunk.

### Local CPU Observation

During local Docker testing, the main bottleneck was identified as **model inference**, not audio decoding.

For example, the inference service produced timings in this range:

```text
decode_ms      ≈ 1–3 ms
inference_ms   ≈ 11,000–13,000 ms
total_ms       ≈ 11,000–13,000 ms
```

A representative log entry was:

```text
inference_timing decode_ms=2.82 inference_ms=11854.71 total_ms=11857.53
```

Therefore, the current CPU-only local environment does **not** meet the assignment's 500 ms target.

The important observation is that the 128 KB / small audio file size is not the primary bottleneck. File transfer and WAV decoding take only a few milliseconds. The expensive operation is running the Wav2Vec2 ONNX model on the CPU.

### Why Splitting the Audio into Chunks Does Not Directly Solve This

The model expects the waveform as a sequence and performs feature extraction and neural-network inference over the audio.

Simply splitting one 5-second recording into multiple pieces and running multiple CPU threads would not automatically make one prediction 20x faster.

It could also change the model's input characteristics and produce inconsistent predictions.

Parallel inference is useful for increasing **throughput** across multiple independent calls, but it does not necessarily reduce the latency of a single call.

### Current Optimization Direction

The primary performance optimization opportunities are:

1. ONNX Runtime hardware acceleration
2. GPU inference
3. Model quantization
4. Smaller/distilled models
5. ONNX graph optimization
6. Reducing unnecessary model input duration
7. Batching independent requests for throughput
8. Multiple inference workers for concurrent calls

The current implementation keeps the model resident in memory, so model download/load time is not included in normal per-request inference.

### Important Distinction

There are two different performance goals:

```text
Single-request latency
        vs.
Overall system throughput
```

Multiple workers and parallel processing are primarily useful for increasing throughput.

Hardware acceleration, model optimization, quantization, or selecting a smaller model are more relevant to reducing the latency of a single request.

The current CPU measurement is therefore treated as a profiling result rather than a claim that the 500 ms assignment target has been achieved.

---

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `MAX_FILE_SIZE` | `15MB` | Spring multipart upload cap |
| `MAX_FILE_BYTES` | `15728640` | Streaming input cap |
| `MAX_AUDIO_DURATION_SECONDS` | `60` | Maximum decoded duration |
| `MIN_AUDIO_DURATION_SECONDS` | `1.0` | Minimum usable duration |
| `INFERENCE_TIMEOUT_MS` | `15000` | Backend-to-inference timeout |
| `GENDER_CONFIDENCE_THRESHOLD` | `0.60` | Gender unknown cutoff |
| `AGE_CONFIDENCE_THRESHOLD` | `0.45` | Age unknown cutoff |
| `INFERENCE_SERVICE_URL` | `http://localhost:8000` | Inference URL outside Compose |

Inside Docker Compose, Spring Boot uses:

```text
http://inference:8000
```

because Docker's service name `inference` provides internal service discovery.

## Running with Docker
No host Java, Python, FFmpeg, or model installation is required.

Start the complete stack:
```text
docker compose up --build
```
Check service status:
```text
docker compose ps
```
Expected state:

- **backend** — healthy
- **inference** — healthy

Check the backend:

```bash
curl http://localhost:8080/actuator/health
```

The first inference startup downloads and loads the public model. Depending on network speed and machine resources, startup can take several minutes.

The inference service is considered healthy only after the model has successfully loaded.

## Tests

## Tests

Run the Spring Boot test suite:

```bash
./mvnw test
```

The test suite covers areas including:

- Controller success cases
- Missing request fields
- Corrupt audio
- Empty audio
- Silent audio
- Short audio
- Usable audio
- Age bracket mapping
- Gender mapping
- Child prediction handling
- Low-confidence handling
- Structured inference response parsing
- Cleanup behavior
- Inference failure handling
- Health indicator behavior

Most tests mock the inference service, so the model does not need to be downloaded for the normal unit-test suite.

### Python Tests

Run the inference-service unit tests inside Docker:

```bash
docker compose run --rm inference \
  python -m unittest discover -s tests
```

These tests focus on inference output mapping and do not require running a full model evaluation.

---

## Sample Audio

The repository contains:

```text
sample/
├── README.md
└── public-speech.flac
```

The sample recording should be a short, consented speech recording sourced from a dataset whose license permits the intended use.

One possible source is Mozilla Common Voice:

https://commonvoice.mozilla.org/en/datasets

Review the current dataset license and attribution requirements before using a recording.

A real speech recording is preferable for an end-to-end smoke test because the generated synthetic WAV only validates audio ingestion and quality-gate behavior.

### Run the Sample

Start the services first, then run:

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/analyze \
  -F 'contact_id=123e4567-e89b-12d3-a456-426614174000' \
  -F 'audio=@sample/public-speech.flac;type=audio/flac'
```

The exact prediction depends on the recording and should not be hardcoded as a test expectation.

## Benchmark

The repository contains:

```text
scripts/benchmark.sh
```

It performs real end-to-end API requests and reports:

- Minimum latency
- Maximum latency
- Average latency
- P50 latency
- P95 latency
- P99 latency

### Run the Benchmark

```bash
./scripts/benchmark.sh sample/public-speech.flac 20
```

The benchmark measures client-observed end-to-end latency across the complete request path:

```text
HTTP request
    ↓
Spring Boot
    ↓
Audio processing
    ↓
Inference
    ↓
HTTP response
```

It does not fabricate benchmark values.

The inference service additionally logs:

```text
decode_ms
inference_ms
total_ms
```

This makes it possible to identify whether latency is caused by audio processing or model inference.

In the current local CPU environment, profiling showed that model inference dominates total processing time.

---

## Evaluation

An optional evaluation script is available:

```text
evaluation/evaluate.py
```

It can summarize labelled results supplied by an operator.

The script deliberately does not automatically download a dataset because dataset licensing, consent, attribution, and preprocessing requirements should be reviewed before evaluation.

A proper evaluation should report at least:

- Gender accuracy
- Age-bracket accuracy
- Unknown/rejection rate
- Confidence distribution
- Confidence calibration
- Performance across relevant audio conditions

A production-quality evaluation should use representative and consented data rather than relying only on a single public sample.

## Scaling to 1,000 Concurrent Calls

A single CPU inference worker is not sufficient for 1,000 concurrent real-time calls.

The proposed production architecture is:

```text
                    Load Balancer
                         |
            +------------+------------+
            |            |            |
         Spring       Spring       Spring
         Replica      Replica      Replica
            |            |            |
            +------------+------------+
                         |
                   Inference Queue
                         |
            +------------+------------+
            |            |            |
         Worker       Worker       Worker
         GPU/CPU      GPU/CPU      GPU/CPU
```

The API layer should remain stateless.

Inference workers should be independently autoscaled based on:

- Request queue depth
- CPU/GPU utilization
- Inference latency
- Active requests

Additional mechanisms should include:

- Bounded queues
- Backpressure
- Connection pooling
- Request timeouts
- Circuit breakers
- Rate limiting
- Health checks
- Autoscaling
- GPU acceleration where available
- Controlled batching where appropriate

For a 1,000-concurrent-call production system, the number of workers should be determined through load testing rather than assumed from local benchmark results.

---

## Security and Operational Considerations

Implemented protections include:

- Upload size limits
- Decoded duration limits
- Accepted-media validation
- Filename disregard
- Audio decoding validation
- Inference timeout
- Temporary-file cleanup
- Structured errors
- No stack-trace exposure
- No audio logging
- Private inference networking

Production deployment should additionally include:

- TLS
- Authentication
- Authorization
- Rate limiting
- API gateway/WAF
- Network policies
- Secret management
- Monitoring and alerting
- Audit logging
- Retention policy
- Consent management

## Known Limitations

1. The current CPU implementation does not meet the assignment's 500 ms target on the local development machine.
2. Wav2Vec2 inference is the dominant latency bottleneck.
3. The model's age confidence is heuristic and not calibrated.
4. Voice-based age and gender estimation can be affected by language, accent, microphone quality, noise, and demographic differences.
5. The service does not currently provide real-time WebSocket progressive predictions.
6. The evaluation harness requires externally supplied labelled data.
7. The current implementation is designed as an assignment/demo system rather than a production identity or eligibility system.
8. CPU-only inference is unlikely to support 1,000 concurrent real-time calls without substantial horizontal scaling and/or model optimization.

---

## Bonus Tasks

### Real-time Streaming

Not implemented in the current version.

A future WebSocket implementation could accumulate audio chunks and periodically run inference on a rolling window, emitting progressive predictions.

### Language / Accent Detection

Not implemented in the current version.

A future implementation could add a language-identification model as an independent inference stage.

### Evaluation Harness

A basic evaluation script is included for operator-supplied labelled results.

A future version could integrate a public dataset after validating its licensing and preprocessing requirements and calculate accuracy and confidence-calibration metrics automatically.

---

## Future Improvements

The most important improvements would be:

1. Profile and optimize ONNX Runtime inference.
2. Evaluate GPU acceleration.
3. Benchmark quantized/smaller models.
4. Evaluate models specifically optimized for short real-time speech segments.
5. Build a representative evaluation dataset.
6. Calibrate confidence scores.
7. Add voice-activity detection.
8. Improve noise robustness.
9. Add real-time WebSocket streaming.
10. Perform load testing against the target concurrency.
11. Add production authentication and authorization.
12. Add monitoring dashboards and latency alerts.

The current implementation intentionally prioritizes a clear architecture, safe audio handling, explainable processing stages, and reproducible Docker-based execution.