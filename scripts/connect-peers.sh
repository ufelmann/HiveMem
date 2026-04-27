#!/usr/bin/env bash
# connect-peers.sh — connect two HiveMem instances bidirectionally
#
# Usage:
#   ./connect-peers.sh \
#     --a-url  https://node-a.example.com  --a-token <admin-token-a> \
#     --b-url  https://node-b.example.com  --b-token <admin-token-b>
#
# What it does:
#   1. Fetch instance UUID of A and B
#   2. Create a writer token on B for A to use (outbound from A)
#   3. Create a writer token on A for B to use (outbound from B)
#   4. Register B as peer on A
#   5. Register A as peer on B

set -euo pipefail

# ── argument parsing ─────────────────────────────────────────────────────────
A_URL="" A_TOKEN="" B_URL="" B_TOKEN=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --a-url)   A_URL="$2";   shift 2 ;;
    --a-token) A_TOKEN="$2"; shift 2 ;;
    --b-url)   B_URL="$2";   shift 2 ;;
    --b-token) B_TOKEN="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [[ -z "$A_URL" || -z "$A_TOKEN" || -z "$B_URL" || -z "$B_TOKEN" ]]; then
  echo "Usage: $0 --a-url <url> --a-token <token> --b-url <url> --b-token <token>"
  exit 1
fi

A_URL="${A_URL%/}"
B_URL="${B_URL%/}"

# ── helpers ───────────────────────────────────────────────────────────────────
api() {
  local method="$1" url="$2" token="$3"
  shift 3
  curl -fsSL -X "$method" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    "$@" "$url"
}

jq_required() {
  if ! command -v jq &>/dev/null; then
    echo "ERROR: jq is required but not installed." >&2
    exit 1
  fi
}

jq_required

# ── 1. fetch instance UUIDs ───────────────────────────────────────────────────
echo "→ Fetching instance UUID from A ($A_URL)..."
A_UUID=$(api GET "$A_URL/admin/identity" "$A_TOKEN" | jq -r '.instance_uuid')
echo "  A UUID: $A_UUID"

echo "→ Fetching instance UUID from B ($B_URL)..."
B_UUID=$(api GET "$B_URL/admin/identity" "$B_TOKEN" | jq -r '.instance_uuid')
echo "  B UUID: $B_UUID"

if [[ "$A_UUID" == "$B_UUID" ]]; then
  echo "ERROR: A and B have the same instance UUID — are they the same instance?" >&2
  exit 1
fi

# ── 2. create token on B for A ────────────────────────────────────────────────
PEER_TOKEN_NAME_A="peer-${A_UUID:0:8}"
echo "→ Creating token '$PEER_TOKEN_NAME_A' on B for A..."
TOKEN_FOR_A=$(api POST "$B_URL/admin/tokens" "$B_TOKEN" \
  -d "{\"name\":\"$PEER_TOKEN_NAME_A\",\"role\":\"writer\"}" \
  | jq -r '.token')

# ── 3. create token on A for B ────────────────────────────────────────────────
PEER_TOKEN_NAME_B="peer-${B_UUID:0:8}"
echo "→ Creating token '$PEER_TOKEN_NAME_B' on A for B..."
TOKEN_FOR_B=$(api POST "$A_URL/admin/tokens" "$A_TOKEN" \
  -d "{\"name\":\"$PEER_TOKEN_NAME_B\",\"role\":\"writer\"}" \
  | jq -r '.token')

# ── 4. register B as peer on A ────────────────────────────────────────────────
echo "→ Registering B as peer on A..."
api POST "$A_URL/admin/peers" "$A_TOKEN" \
  -d "{\"peerUuid\":\"$B_UUID\",\"peerUrl\":\"$B_URL\",\"outboundToken\":\"$TOKEN_FOR_A\"}" \
  | jq .

# ── 5. register A as peer on B ────────────────────────────────────────────────
echo "→ Registering A as peer on B..."
api POST "$B_URL/admin/peers" "$B_TOKEN" \
  -d "{\"peerUuid\":\"$A_UUID\",\"peerUrl\":\"$A_URL\",\"outboundToken\":\"$TOKEN_FOR_B\"}" \
  | jq .

echo ""
echo "✓ Done. A ($A_UUID) ↔ B ($B_UUID) are now connected."
echo "  Pull interval: 60s (configurable via hivemem.sync.pull-interval-ms)"
