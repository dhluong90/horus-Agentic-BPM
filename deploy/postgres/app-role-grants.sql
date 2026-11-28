-- Two database roles for production (docs/DEPLOYING.md, "Two database roles"; issue 700).
--
--   owner  owns the database and its tables, runs the migrations of the host and of both engines, and is never
--          used to serve a request.
--   app    serves every request. It owns nothing, cannot ALTER, DROP or TRUNCATE a table and holds no TRIGGER
--          privilege, so it cannot switch off the append-only and approval-seal triggers.
--
-- Run it once, as a superuser, connected to the target database (ALTER DEFAULT PRIVILEGES is per database):
--
--   psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname horus \
--     -v database=horus -v owner=horus_owner -v app=horus -v owner_password="$OWNER_PW" -v app_password="$APP_PW" \
--     -f deploy/postgres/app-role-grants.sql
--
-- The engines grant the app role its rights on their own tables after each migration (the Flyway callback
-- afterMigrate__app_role_grants.sql of each engine), and take UPDATE and DELETE away on the append-only ones. The
-- default privileges below cover the tables the host's own migrations create later, as the owner.
-- Idempotent only for the grants: CREATE ROLE fails when a role exists, which is the safe answer to a rerun that
-- would otherwise reset a password.

CREATE ROLE :"owner" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';

ALTER DATABASE :"database" OWNER TO :"owner";
REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"database" TO :"owner";
GRANT CONNECT, TEMPORARY ON DATABASE :"database" TO :"app";

-- The owner creates objects in public; the app reads and writes them, and creates nothing.
ALTER SCHEMA public OWNER TO :"owner";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app";

ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"app";
