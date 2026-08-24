#!/usr/bin/env sh
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
python3 "$(dirname "$0")/generate-test-wav.py"
curl --fail-with-body -sS -X POST "$BASE_URL/analyze" \
  -F "contact_id=123e4567-e89b-12d3-a456-426614174000" \
  -F "audio=@/tmp/voice-smoke.wav;type=audio/wav"
printf '\n'
