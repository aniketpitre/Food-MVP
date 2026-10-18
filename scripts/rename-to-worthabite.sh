#!/usr/bin/env bash
# One-time move for a server set up before the rename: the database, photos and HTTPS
# certificates go from the "foodfeed" Docker names to "worthabite".
#
#   cd ~/Food-MVP && git pull && scripts/rename-to-worthabite.sh
#
# It backs the database up first (backups/pre-rename-<date>.dump), checks the counts match
# after the move, and keeps the old volumes untouched so you can go back. It does nothing
# on a server that was never on the old names, or that has already moved.
set -euo pipefail
cd "$(dirname "$0")/.."

OLD=foodfeed
NEW=worthabite
IMAGE=postgis/postgis:16-3.5
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
vol() { docker volume inspect "$1" >/dev/null 2>&1; }

if ! vol "${OLD}_pgdata"; then
  echo "Nothing to move: there is no ${OLD}_pgdata volume (new server, or already moved)."
  exit 0
fi
if vol "${NEW}_pgdata" && [ "${FORCE:-}" != "1" ]; then
  echo "${NEW}_pgdata already exists, so this server looks moved already. Nothing changed."
  echo "(To move again over it anyway, run with FORCE=1.)"
  exit 1
fi
[ -f .env ] || { echo "No .env here; run this from the Food-MVP folder on the server."; exit 1; }

mkdir -p backups
stamp=$(date +%F-%H%M)
dump="backups/pre-rename-$stamp.dump"

say "1/6  Stopping the old stack (its data stays in place)"
docker compose -p "$OLD" down --remove-orphans || true

say "2/6  Backing up the old database to $dump"
docker rm -f wab-rename-old >/dev/null 2>&1 || true
docker run -d --name wab-rename-old -e POSTGRES_PASSWORD=unused -v "${OLD}_pgdata:/var/lib/postgresql/data" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do docker exec wab-rename-old pg_isready -U "$OLD" -d "$OLD" >/dev/null 2>&1 && break; sleep 2; done
count() { docker exec "$1" psql -U "$2" -d "$2" -tAc "select (select count(*) from profiles) || '/' || (select count(*) from posts) || '/' || (select count(*) from dishes)"; }
before=$(count wab-rename-old "$OLD")
docker exec wab-rename-old pg_dump -U "$OLD" -d "$OLD" -Fc > "$dump"
docker rm -f wab-rename-old >/dev/null
echo "   saved ($(du -h "$dump" | cut -f1)); people/posts/dishes = $before"

say "3/6  Copying photos (and HTTPS certificates, if any) to the new volumes"
for v in media caddy_data; do
  if vol "${OLD}_$v"; then
    docker volume create "${NEW}_$v" >/dev/null
    docker run --rm -v "${OLD}_$v:/from:ro" -v "${NEW}_$v:/to" alpine:3.20 sh -c 'cp -a /from/. /to/'
    echo "   ${OLD}_$v -> ${NEW}_$v"
  fi
done

say "4/6  Starting the new database"
docker compose up -d db
# A brand-new database first runs a short setup server, then restarts: wait for the real one.
db=$(docker compose ps -q db)
for _ in $(seq 1 90); do
  docker logs "$db" 2>&1 | grep -q "PostgreSQL init process complete" && break
  docker logs "$db" 2>&1 | grep -q "Skipping initialization" && break
  sleep 2
done
for _ in $(seq 1 60); do docker compose exec -T db pg_isready -U "$NEW" -d "$NEW" >/dev/null 2>&1 && break; sleep 2; done
sleep 2

say "5/6  Restoring into it"
# Extensions the new database already has are reported as "already exists"; that's fine.
docker compose exec -T db pg_restore -U "$NEW" -d "$NEW" --no-owner --role="$NEW" < "$dump" 2> backups/pre-rename-$stamp.restore.log || true
after=$(count "$(docker compose ps -q db)" "$NEW")
echo "   people/posts/dishes before = $before, after = $after"
if [ "$before" != "$after" ]; then
  echo
  echo "The counts don't match, so the move stopped here. Your old data is untouched."
  echo "Details: backups/pre-rename-$stamp.restore.log. To go back to the old setup:"
  echo "   docker compose down && git checkout <previous version> && docker compose up -d"
  exit 1
fi

say "6/6  Starting WorthAbite on the new names"
if [ "${NO_START:-}" != "1" ]; then docker compose up -d --build; fi

cat <<EOF

Done. Everything now runs as "$NEW"; the backup is $dump.
The old volumes are still there (${OLD}_pgdata, ${OLD}_media). Once you've checked the app
for a few days, remove them to free the space:
   docker volume rm ${OLD}_pgdata ${OLD}_media ${OLD}_caddy_data
If you use nightly backups, start them again with:  docker compose --profile backup up -d
EOF
