#!/bin/sh
# Runs once, when the postgres container initialises an empty data directory (docker-compose.yml).
# Creates "horus", the role the example host connects with: it owns the database horus (and so its
# public schema) and runs the Flyway migrations of the host and the engines, but it is not a superuser,
# cannot create roles or databases and cannot bypass row-level security. PUBLIC loses its default
# rights on the database. The password is read from the mounted secret file by psql itself, never from
# argv or the environment.
set -eu
secret="${HORUS_DB_PASSWORD_FILE:-/run/secrets/db_password}"
if [ ! -s "$secret" ]; then
  echo "10-application-role.sh: the secret file $secret is missing or empty; run deploy/create-secrets.sh" >&2
  exit 1
fi
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v secret_file="$secret" <<'SQL'
\set app_password `cat :'secret_file'`
CREATE ROLE horus LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
ALTER DATABASE horus OWNER TO horus;
REVOKE ALL ON DATABASE horus FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE horus TO horus;
SQL
