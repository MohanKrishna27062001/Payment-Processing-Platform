-- One-time setup for running without Docker against a locally installed PostgreSQL.
-- Run as the postgres superuser:
--   psql -U postgres -f scripts/create-local-db.sql
-- Flyway creates all tables and demo data when the application starts.

CREATE USER payments WITH PASSWORD 'payments';
CREATE DATABASE payments OWNER payments;
