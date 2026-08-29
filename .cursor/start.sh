#!/usr/bin/env bash
# Per-boot startup for the Cloud Agent environment: bring up the local
# PostgreSQL cluster and ensure the dev role + database exist. Safe to re-run.
set -euo pipefail

# Serialize overlapping invocations from environment.json "start" and the
# "app" terminal so two CREATE ROLE / CREATE DATABASE cannot race.
exec 9>/tmp/kjv-pg-bootstrap.lock
flock 9

# Package cluster listens on 5432. Honor KJV_DB_PORT only when unset or 5432;
# any other value is a configuration error (do not rewrite postgresql.conf).
DB_PORT="${KJV_DB_PORT:-5432}"
if [ "${DB_PORT}" != "5432" ]; then
  echo "KJV_DB_PORT=${DB_PORT} is not supported: the package PostgreSQL cluster listens on 5432 (will not rewrite postgresql.conf)" >&2
  exit 1
fi

# Start the cluster created by the postgresql package (idempotent — tolerates
# an already-running server). Prefer the "main" cluster from pg_lsclusters;
# fall back to 16 (Ubuntu 24.04 default).
cluster_ver="$(pg_lsclusters --no-header 2>/dev/null | awk '$2 == "main" { print $1; exit }')"
cluster_ver="${cluster_ver:-16}"
sudo pg_ctlcluster "${cluster_ver}" main start || true

# Wait until the server accepts connections before provisioning. Fail closed.
ready=0
for _ in $(seq 1 30); do
  if sudo -u postgres pg_isready -q -p "${DB_PORT}"; then
    ready=1
    break
  fi
  sleep 1
done
if [ "${ready}" -ne 1 ]; then
  echo "PostgreSQL did not become ready within 30s" >&2
  exit 1
fi

DB_NAME="${KJV_DB_NAME:-readthekjv}"
DB_USER="${KJV_DB_USERNAME:-kjv}"
DB_PASS="${KJV_DB_PASSWORD:-kjv}"

# Identifiers and password go through psql :'var' + format(%I/%L), never
# interpolated into SQL by the shell. CREATE ROLE / CREATE DATABASE have no
# IF NOT EXISTS on PG 16 — use catalog guards + \gexec. ALTER ROLE still
# sets the password when the role already exists so ENV matches Postgres.
sudo -u postgres psql -p "${DB_PORT}" -v ON_ERROR_STOP=1 \
  --set=db_name="${DB_NAME}" \
  --set=db_user="${DB_USER}" \
  --set=db_pass="${DB_PASS}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_pass')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_user');
\gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_pass');
\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name');
\gexec
SQL

# When KJV_DB_USERNAME changes across restarts, move datdba and Flyway
# objects off the previous role. Capture prior owner before ALTER DATABASE.
PRIOR_OWNER="$(sudo -u postgres psql -p "${DB_PORT}" -v ON_ERROR_STOP=1 -tA \
  --set=db_name="${DB_NAME}" \
  --set=db_user="${DB_USER}" <<'SQL'
SELECT pg_get_userbyid(datdba)
FROM pg_database
WHERE datname = :'db_name'
  AND pg_get_userbyid(datdba) IS DISTINCT FROM :'db_user';
SQL
)"

if [ -n "${PRIOR_OWNER}" ]; then
  sudo -u postgres psql -p "${DB_PORT}" -v ON_ERROR_STOP=1 \
    --set=db_name="${DB_NAME}" \
    --set=db_user="${DB_USER}" <<'SQL'
SELECT format('ALTER DATABASE %I OWNER TO %I', :'db_name', :'db_user');
\gexec
SQL
  sudo -u postgres psql -p "${DB_PORT}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 \
    --set=old_user="${PRIOR_OWNER}" \
    --set=db_user="${DB_USER}" <<'SQL'
SELECT format('REASSIGN OWNED BY %I TO %I', :'old_user', :'db_user');
\gexec
SQL
fi

echo "PostgreSQL ready — database '${DB_NAME}' present (owner '${DB_USER}')."
