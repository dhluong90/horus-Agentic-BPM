#!/bin/sh
# Runs once, when the postgres container initialises an empty data directory (docker-compose.yml).
#
# Two roles (issue 700, docs/DEPLOYING.md "Two database roles"), when the secret db_owner_password is mounted:
#   horus_owner  owns the database horus and its tables and runs the migrations of the host and the engines.
#   horus        is the role the host serves requests with. It owns nothing and cannot ALTER, DROP or TRUNCATE a
#                table or touch a trigger, so it cannot switch off the append-only and approval-seal triggers.
# Neither is a superuser, can create roles or databases, or can bypass row-level security. The work is done by
# deploy/postgres/app-role-grants.sql, which an operator can run on their own database.
#
# One role, when db_owner_password is not mounted (the soak test's compose file): "horus" owns the database and
# runs the migrations, which is for development and not for production.
#
# Passwords are read from the mounted secret files by psql itself, never from argv or the environment.
set -eu
app_secret="${HORUS_DB_PASSWORD_FILE:-/run/secrets/db_password}"
owner_secret="${HORUS_DB_OWNER_PASSWORD_FILE:-/run/secrets/db_owner_password}"
grants="${HORUS_DB_GRANTS_FILE:-/opt/horus/app-role-grants.sql}"
if [ ! -s "$app_secret" ]; then
  echo "10-application-role.sh: the secret file $app_secret is missing or empty; run deploy/create-secrets.sh" >&2
  exit 1
fi
if [ -s "$owner_secret" ]; then
  if [ ! -s "$grants" ]; then
    echo "10-application-role.sh: $grants is missing; mount deploy/postgres/app-role-grants.sql there" >&2
    exit 1
  fi
  psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v database="$POSTGRES_DB" -v owner=horus_owner -v app=horus \
    -v owner_secret_file="$owner_secret" -v app_secret_file="$app_secret" -v grants_file="$grants" <<'SQL'
\set owner_password `cat :'owner_secret_file'`
\set app_password `cat :'app_secret_file'`
\i :grants_file
SQL
  exit 0
fi
echo "10-application-role.sh: no db_owner_password secret; creating one role that owns the database (development only)" >&2
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v secret_file="$app_secret" <<'SQL'
\set app_password `cat :'secret_file'`
CREATE ROLE horus LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
ALTER DATABASE horus OWNER TO horus;
REVOKE ALL ON DATABASE horus FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE horus TO horus;
SQL
