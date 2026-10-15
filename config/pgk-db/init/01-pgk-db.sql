-- Idempotent bootstrap of the `pgk` role and database used by pgk-proxy.
-- Runs via /docker-entrypoint-initdb.d on first start of an EMPTY volume. The \gexec
-- trick builds the statement conditionally, so the script is safe to run more than once
-- (e.g. against a volume that predates this feature). Existing pgk-db-data volumes still
-- need a `docker compose down -v` once for the role/database to be created.
SELECT 'CREATE ROLE pgk WITH LOGIN PASSWORD ''pgk'''
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgk')
\gexec

SELECT 'CREATE DATABASE pgk OWNER pgk'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'pgk')
\gexec
