DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pdf_reader_owner') THEN
    CREATE ROLE pdf_reader_owner;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pdf_reader_app') THEN
    CREATE ROLE pdf_reader_app;
  END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS pdf_reader;

ALTER SCHEMA pdf_reader OWNER TO pdf_reader_owner;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA pdf_reader FROM PUBLIC;
GRANT USAGE ON SCHEMA pdf_reader TO pdf_reader_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pdf_reader TO pdf_reader_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pdf_reader_owner IN SCHEMA pdf_reader
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pdf_reader_app;
