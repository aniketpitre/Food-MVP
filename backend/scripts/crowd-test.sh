#!/bin/sh
# Starts a throwaway server on a fresh test database (docker container ff-testdb on :5433, see
# README 'Tests'), runs the simulated crowd against it, then stops it.
# usage: scripts/crowd-test.sh <SPREAD 0|1> <dbname>   (needs `npm run build` first)
SP=${TMPDIR:-/tmp}/worthabite-crowd; mkdir -p $SP
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/.."
pkill -f "^node dist/server.js" 2>/dev/null; sleep 1
docker exec ff-testdb psql -U postgres -c "drop database if exists $2 with (force)" -c "create database $2" >/dev/null
rm -rf $SP/crowd-media; mkdir -p $SP/crowd-media
export DATABASE_URL=postgres://postgres:postgres@localhost:5433/$2 JWT_SECRET=crowd-secret-that-is-long-enough-1234567890 MEDIA_DIR=$SP/crowd-media PUBLIC_URL=http://localhost:8099 PORT=8099 ADMIN_EMAILS=admin@sim.test SEED_ON_START=true DEMO_ACTIVITY=false RANK_REFRESH_SECONDS=30 AUTH_RATE_LIMIT_PER_MINUTE=100000 LOG_LEVEL=warn ROUTING_URL=
node dist/cli.js migrate >/dev/null && node dist/cli.js seed >/dev/null 2>&1 && node dist/cli.js import-overture nashik seed/places/nashik.overture.json >/dev/null 2>&1
(nohup node dist/server.js > $SP/crowd-server-$1.log 2>&1 &)
for i in $(seq 40); do curl -sf localhost:8099/health >/dev/null && break; sleep 1; done
SPREAD=$1 DB=$2 OUT=$SP/crowd-report-$1.json node scripts/crowd-test.mjs
RC=$?
pkill -f "^node dist/server.js"
echo "server log errors: $(grep -ciE 'error|fatal' $SP/crowd-server-$1.log)"
exit $RC
