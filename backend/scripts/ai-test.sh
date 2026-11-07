#!/bin/sh
# The full pre-launch test on a throwaway server: 200 scripted people use it for a launch week
# (scripts/crowd-test.mjs, SPREAD=week), then AI users with their own personalities try it
# (scripts/ai-users.mjs). Needs `npm run build`, the ff-testdb container (README 'Tests') and
# NVIDIA_API_KEY. usage: scripts/ai-test.sh [dbname]
DB=${1:-ai_launch}
SP=${TMPDIR:-/tmp}/worthabite-ai; mkdir -p $SP
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/.."
pkill -f "^node dist/server.js" 2>/dev/null; sleep 1
docker exec ff-testdb psql -U postgres -c "drop database if exists $DB with (force)" -c "create database $DB" >/dev/null
rm -rf $SP/media; mkdir -p $SP/media
export DATABASE_URL=postgres://postgres:postgres@localhost:5433/$DB JWT_SECRET=ai-test-secret-that-is-long-enough-1234567890 MEDIA_DIR=$SP/media PUBLIC_URL=http://localhost:8099 PORT=8099 ADMIN_EMAILS=admin@ai.test SEED_ON_START=true DEMO_ACTIVITY=false RANK_REFRESH_SECONDS=30 AUTH_RATE_LIMIT_PER_MINUTE=100000 LOG_LEVEL=warn ROUTING_URL=
node dist/cli.js migrate >/dev/null && node dist/cli.js seed >/dev/null 2>&1 && node dist/cli.js import-overture nashik seed/places/nashik.overture.json >/dev/null 2>&1
(nohup node dist/server.js > $SP/server.log 2>&1 &)
for i in $(seq 40); do curl -sf localhost:8099/health >/dev/null && break; sleep 1; done
echo "== 200 scripted people, launch week =="
HONEST=${HONEST:-200} SPREAD=week DB=$DB OUT=$SP/crowd-week.json node scripts/crowd-test.mjs
CROWD=$?
echo "== AI users =="
# Model calls go out through the environment's HTTPS proxy, if there is one; the test server is local.
NODE_USE_ENV_PROXY=1 NODE_NO_WARNINGS=1 NO_PROXY="localhost,127.0.0.1${NO_PROXY:+,$NO_PROXY}" OUT_DIR=$SP node scripts/ai-users.mjs
AI=$?
pkill -f "^node dist/server.js"
echo "server log errors: $(grep -ciE '"level":(50|60)|error' $SP/server.log)"
echo "reports in $SP"
[ $CROWD -eq 0 ] && [ $AI -eq 0 ]
