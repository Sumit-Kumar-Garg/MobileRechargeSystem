#!/usr/bin/env bash
# End-to-end walk through the Recharge API (demo profile).
# Needs the app running with SPRING_PROFILES_ACTIVE=demo. Run: ./scripts/demo-flow.sh [base-url]
set -e
BASE=${1:-http://localhost:8080}
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
step() { printf '\n=== %s\n' "$1"; }

step "1. Get a demo token (public)"
TOKEN=$(curl -s -X POST $BASE/v1/demo/token | json 'd["accessToken"]')
echo "TOKEN=${TOKEN:0:25}..."
AUTH="Authorization: Bearer $TOKEN"

step "2. Browse plans for Germany (public)"
curl -s "$BASE/v1/plans?market=DE" | json '"\n".join("%-10s %6.2f %s  %s" % (p["code"], p["price"]["amountMinor"]/100, p["price"]["currency"], p["name"]) for p in d["plans"])'

step "3. Link your phone number"
NUM=$(curl -s -X POST $BASE/v1/numbers -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"msisdn":"+491701234567"}' | json 'd["id"]')
echo "NUM=$NUM"
curl -s $BASE/v1/numbers -H "$AUTH"; echo

step "4. (Optional) Allow personalised recommendations"
curl -s -X PUT $BASE/v1/consents/personalization -H "$AUTH" -H 'Content-Type: application/json' -d '{"granted":true}'; echo

step "5. (Optional, demo only) Tell the server how much data you use"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST $BASE/v1/demo/usage -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"numberId\":\"$NUM\",\"dataMbUsed\":9500}"

step "6. Get a recommendation"
curl -s "$BASE/v1/recommendations?numberId=$NUM" -H "$AUTH" -H 'Accept-Language: en'; echo

step "7. Which payment methods exist for EUR"
curl -s "$BASE/v1/payment-methods?currency=EUR" -H "$AUTH"; echo

step "8. Create an order (pick the plan by code)"
PLAN=$(curl -s "$BASE/v1/plans?market=DE" | json '[p["id"] for p in d["plans"] if p["code"]=="M-28"][0]')
KEY=$(uuidgen | tr -d -)
ORDER=$(curl -s -X POST $BASE/v1/orders -H "$AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "{\"numberId\":\"$NUM\",\"planId\":\"$PLAN\"}" | json 'd["id"]')
echo "ORDER=$ORDER"

step "9. Pay for it"
curl -s -X POST $BASE/v1/orders/$ORDER/pay -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"paymentToken":"tok_success","method":"card"}' | json 'd["status"]'

step "10. Poll the order until it is COMPLETED"
for i in 1 2 3 4 5 6 7 8 9 10; do
  S=$(curl -s $BASE/v1/orders/$ORDER -H "$AUTH" | json 'd["status"]'); echo "status: $S"
  [ "$S" = COMPLETED ] || [ "$S" = REFUNDED ] && break; sleep 2
done
curl -s $BASE/v1/orders/$ORDER -H "$AUTH"; echo

step "11. Order history"
curl -s $BASE/v1/orders -H "$AUTH" | json '[(o["planCode"], o["status"]) for o in d]'

step "12. Notification settings and push device"
curl -s $BASE/v1/notification-preferences -H "$AUTH"; echo
curl -s -X PUT $BASE/v1/notification-preferences -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"pushEnabled":true,"smsEnabled":true,"emailEnabled":false,"locale":"en"}'; echo
curl -s -o /dev/null -w "register device: HTTP %{http_code}\n" -X PUT $BASE/v1/devices -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"token":"demo-fcm-token-1234567890abcdef"}'

step "13. Consents and app telemetry"
curl -s $BASE/v1/consents -H "$AUTH"; echo
curl -s -X POST $BASE/v1/telemetry/batch -H "$AUTH" -H 'Content-Type: application/json' -d "{
  \"sessionId\":\"session-12345678\",\"appVersion\":\"1.0.0\",
  \"events\":[{\"id\":\"$(uuidgen)\",\"type\":\"network_call\",\"name\":\"plans_loaded\",
  \"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"props\":{\"endpoint\":\"/v1/plans\",\"durationMs\":120,\"outcome\":\"success\",\"networkType\":\"wifi\"}}]}"; echo
