#!/usr/bin/env sh
# Measures real end-to-end calls; it prints no fabricated baseline values.
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
AUDIO_FILE="${1:?Usage: scripts/benchmark.sh /path/to/5-second-speech.wav [iterations]}"
ITERATIONS="${2:-20}"
CONTACT_ID="123e4567-e89b-12d3-a456-426614174000"
values="$(mktemp)"
trap 'rm -f "$values"' EXIT
i=1
while [ "$i" -le "$ITERATIONS" ]; do
  body="$(curl --fail-with-body -sS -w '\n%{time_total}' -X POST "$BASE_URL/analyze" -F "contact_id=$CONTACT_ID" -F "audio=@$AUDIO_FILE")"
  transport_ms="$(printf '%s\n' "$body" | tail -n1 | awk '{print $1 * 1000}')"
  printf '%s\n' "$transport_ms" >> "$values"
  i=$((i + 1))
done
awk 'BEGIN { min=1e99; max=0; sum=0 } { a[NR]=$1; sum+=$1; if($1<min)min=$1; if($1>max)max=$1 } END { for(i=1;i<=NR;i++)for(j=i+1;j<=NR;j++)if(a[i]>a[j]){t=a[i];a[i]=a[j];a[j]=t}; printf "samples=%d\ntotal_ms min=%.1f max=%.1f average=%.1f p50=%.1f p95=%.1f p99=%.1f\n", NR,min,max,sum/NR,a[int((NR-1)*.50)+1],a[int((NR-1)*.95)+1],a[int((NR-1)*.99)+1] }' "$values"
echo "This is end-to-end total_ms (client + backend decode + worker inference). The services log backend processing_ms; no decode/inference split is fabricated by this script."
