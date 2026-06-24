#!/usr/bin/env bash
#
# End-to-end smoke test: submit an event through the Gateway, then read the
# balance back via the Gateway's balance proxy (which forwards to Account).
# Exits non-zero on the first failure. Run after `docker compose up --build`.
#
#   ./scripts/smoke-test.sh
#   GATEWAY_URL=http://localhost:8080 ./scripts/smoke-test.sh
#
set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
# Unique account per run so the asserted balance is deterministic.
ACCOUNT="smoke-$(date +%s)-$$"
EVENT="evt-${ACCOUNT}"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

POST_BODY="$(mktemp)"
BAL_BODY="$(mktemp)"
trap 'rm -f "$POST_BODY" "$BAL_BODY"' EXIT

echo "→ Gateway: $GATEWAY  (account=$ACCOUNT)"

# Wait for the Gateway to be reachable (it may still be starting).
echo -n "→ waiting for gateway /health "
for _ in $(seq 1 30); do
  if curl -fsS "$GATEWAY/health" >/dev/null 2>&1; then break; fi
  echo -n "."
  sleep 2
done
echo " ok"

# 1) Submit a CREDIT of 100.00.
code="$(curl -sS -o "$POST_BODY" -w '%{http_code}' \
  -X POST "$GATEWAY/events" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT\",\"accountId\":\"$ACCOUNT\",\"type\":\"CREDIT\",\"amount\":100.00,\"currency\":\"USD\",\"eventTimestamp\":\"$NOW\"}")"
echo "POST /events            -> HTTP $code"
cat "$POST_BODY"; echo
[ "$code" = "201" ] || { echo "FAIL: expected 201 from POST /events"; exit 1; }

# 2) Read the balance back through the proxy.
code="$(curl -sS -o "$BAL_BODY" -w '%{http_code}' "$GATEWAY/accounts/$ACCOUNT/balance")"
echo "GET  /accounts/.../balance -> HTTP $code"
cat "$BAL_BODY"; echo
[ "$code" = "200" ] || { echo "FAIL: expected 200 from balance proxy"; exit 1; }

# 3) The balance must reflect the credit.
if grep -Eq '"balance":[[:space:]]*100(\.0+)?' "$BAL_BODY"; then
  echo "✅ smoke test passed: balance reflects the 100.00 credit"
else
  echo "FAIL: balance did not reflect the credit"; exit 1
fi
