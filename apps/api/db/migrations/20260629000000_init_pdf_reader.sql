CREATE SCHEMA IF NOT EXISTS pdf_reader;

CREATE TABLE IF NOT EXISTS pdf_reader.documents (
  document_id uuid PRIMARY KEY,
  title text NOT NULL CHECK (btrim(title) <> ''),
  current_version integer NOT NULL CHECK (current_version >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS pdf_reader.document_versions (
  document_version_id uuid PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES pdf_reader.documents(document_id) ON DELETE CASCADE,
  version integer NOT NULL CHECK (version >= 1),
  gcs_object_name text NOT NULL CHECK (btrim(gcs_object_name) <> ''),
  content_hash text NOT NULL CHECK (btrim(content_hash) <> ''),
  published_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, version)
);

CREATE TABLE IF NOT EXISTS pdf_reader.reading_progress (
  client_id text NOT NULL CHECK (btrim(client_id) <> ''),
  document_id uuid NOT NULL REFERENCES pdf_reader.documents(document_id) ON DELETE CASCADE,
  version integer NOT NULL CHECK (version >= 1),
  position_type text NOT NULL CHECK (position_type IN ('heading_anchor', 'line', 'offset')),
  position_value text NOT NULL CHECK (btrim(position_value) <> ''),
  progress_ratio numeric(5,4) NOT NULL CHECK (progress_ratio >= 0 AND progress_ratio <= 1),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (client_id, document_id),
  FOREIGN KEY (document_id, version)
    REFERENCES pdf_reader.document_versions(document_id, version)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS reading_progress_document_id_idx
  ON pdf_reader.reading_progress(document_id);
