#!/usr/bin/env bash
# Per-boot startup for the Cloud Agent environment: bring up the local
# PostgreSQL cluster and ensure the dev role + database exist. Safe to re-run.
set -euo pipefail

# Start the cluster created by the postgresql package (idempotent — tolerates
# an already-running server).
sudo pg_ctlcluster 16 main start || true

# Wait until the server accepts connections before provisioning.
for _ in $(seq 1 30); do
  if sudo -u postgres pg_isready -q; then
    break
  fi
  sleep 1
done

DB_NAME="${KJV_DB_NAME:-readthekjv}"
DB_USER="${KJV_DB_USERNAME:-kjv}"
DB_PASS="${KJV_DB_PASSWORD:-kjv}"

# Create the dev login role if it is missing.
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" | grep -q 1; then
  sudo -u postgres psql -c "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASS}';"
fi

# Create the application database owned by that role if it is missing.
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
  sudo -u postgres psql -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"
fi

echo "PostgreSQL ready — database '${DB_NAME}' present (owner '${DB_USER}')."
