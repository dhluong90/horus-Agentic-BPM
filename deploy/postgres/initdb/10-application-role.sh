#!/bin/sh
# Runs once, when the postgres container initialises an empty data directory (docker-compose.yml).
# Creates "horus", the role the example host connects with: it owns the database horus (and so its
# public schema) and runs the Flyway migrations of the host and the engines, but it is not a superuser,
# cannot create roles or databases and cannot bypass row-level security. PUBLIC loses its default
# rights on the database. The password is read from the environment with \getenv, never from argv.
set -eu
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_password HORUS_DB_PASSWORD
CREATE ROLE horus LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
ALTER DATABASE horus OWNER TO horus;
REVOKE ALL ON DATABASE horus FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE horus TO horus;
SQL
