#!/usr/bin/env bash
# End-to-end check of a running FoodFeed stack through its public HTTP API.
# usage: scripts/smoke-test.sh [base-url]      (default http://localhost:8080)
set -euo pipefail
BASE="${1:-http://localhost:8080}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$1"; exit 1; }
json() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log(eval('o'+process.argv[1]))})" "$1"; }

echo "FoodFeed smoke test against $BASE"
[ "$(curl -fsS "$BASE/health" | json .ok)" = "true" ] && pass "health" || fail "health"

CITY=$(curl -fsS "$BASE/cities/nearest?lat=20.0&lng=73.78" | json .city.id)
[ -n "$CITY" ] && pass "nearest city id=$CITY" || fail "nearest city"

N=$(curl -fsS "$BASE/feed?city=$CITY&limit=20" | json .items.length)
[ "$N" -gt 0 ] && pass "guest feed returns $N dishes" || fail "feed empty"
DISH=$(curl -fsS "$BASE/feed?city=$CITY&limit=1" | json .items[0].id)
IMG=$(curl -fsS "$BASE/feed?city=$CITY&limit=1" | json .items[0].cover.imageUrl)
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE$IMG")" = "200" ] && pass "cover photo served" || fail "photo"

for tab in trending gems; do
  M=$(curl -fsS "$BASE/feed?city=$CITY&tab=$tab" | json .items.length)
  pass "$tab tab returns $M dishes"
done
[ "$(curl -fsS "$BASE/search?city=$CITY&q=wada%20pav" | json .dishes.length)" -gt 0 ] && pass "search 'wada pav'" || fail "search"

HANDLE="smoke_$RANDOM$RANDOM"
TOKEN=$(curl -fsS -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$HANDLE@example.com\",\"password\":\"smoke-test-pass\",\"handle\":\"$HANDLE\",\"acceptTerms\":true}" | json .token)
[ -n "$TOKEN" ] && pass "registered @$HANDLE" || fail "register"
AUTH="authorization: Bearer $TOKEN"

R=$(curl -fsS -X PUT "$BASE/dishes/$DISH/engagements/recommend" -H "$AUTH" | json .me.recommended)
[ "$R" = "true" ] && pass "recommend" || fail "recommend"
S=$(curl -fsS -X PUT "$BASE/dishes/$DISH/engagements/save" -H "$AUTH" | json .me.saved)
[ "$S" = "true" ] && pass "save" || fail "save"
[ "$(curl -fsS "$BASE/me/saved" -H "$AUTH" | json .items.length)" = "1" ] && pass "saved list" || fail "saved list"

# A 240x300 orange JPEG, so the test needs no image tools.
echo '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAEsAPADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDNooorxD6cKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigD/2Q==' | base64 -d > "$TMP/p.jpg"
REST=$(curl -fsS "$BASE/restaurants?city=$CITY&q=chai" | json [0].id)
POST=$(curl -fsS -X POST "$BASE/posts" -H "$AUTH" -F image=@"$TMP/p.jpg" -F idempotencyKey="smoke-$RANDOM$RANDOM" \
  -F dishName="Smoke Test Chai" -F restaurantId="$REST" -F priceInr=15 -F isVeg=true | json .status)
[ "$POST" = "live" ] && pass "posted a dish with a photo" || fail "post"

[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/d/$DISH")" = "200" ] && pass "share page" || fail "share page"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/privacy")" = "200" ] && pass "privacy page" || fail "privacy"

[ "$(curl -fsS -X DELETE "$BASE/me" -H "$AUTH" | json .ok)" = "true" ] && pass "deleted the test account" || fail "delete"
echo "All smoke checks passed."
