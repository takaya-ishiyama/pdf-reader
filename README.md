# pdf-reader

Markdown documents are stored in GCS and viewed from an Android app. The Rust API stores document metadata, versions, and reading progress in PostgreSQL, then returns GCS signed URLs for the Android client to fetch Markdown directly.

## API

```sh
cd apps/api
cargo test
cargo run
```

Required runtime environment:

```sh
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require
GCS_BUCKET=your-markdown-bucket
GOOGLE_SERVICE_ACCOUNT_EMAIL=cloud-run-signer@PROJECT_ID.iam.gserviceaccount.com
GOOGLE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"
SIGNED_URL_TTL_SECONDS=3600
```

The API exposes:

- `GET /healthz`
- `GET /v1/documents?client_id={client_id}`
- `GET /v1/documents/{document_id}?client_id={client_id}`
- `PUT /v1/documents/{document_id}/progress`

## Database

The PostgreSQL schema is `pdf_reader`, not `public`.

Schema source:

- `apps/api/db/schema.sql`
- `apps/api/db/migrations/20260629000000_init_pdf_reader.sql`
- `apps/api/db/roles.sql`

Atlas migration example:

```sh
cd apps/api
DATABASE_URL=postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require atlas migrate apply --env production
psql "$DATABASE_URL" -f db/roles.sql
```

Repository integration tests use a real PostgreSQL database when `TEST_DATABASE_URL` is set:
They cover the `pdf_reader` schema, constraints, UPSERT behavior, and transaction commit/rollback behavior.

```sh
cd apps/api
TEST_DATABASE_URL=postgres://postgres:postgres@localhost:5432/pdf_reader_test cargo test
```

## Cloud Run

A service template is available at:

- `apps/api/deploy/cloud-run-service.yaml`

Before applying it, replace `PROJECT_ID`, `REGION`, image tag, bucket, service account, and secret names.

Secrets expected by the template:

- `markdown-reader-database-url`
- `markdown-reader-google-service-account-email`
- `markdown-reader-google-private-key`

The Cloud Run service account needs GCS object read permission and signing permission appropriate for the configured signed URL strategy.

## Android

The Android module is under `apps/android/app`.

It includes:

- Document list and detail loading
- Signed URL Markdown download
- File-backed Markdown cache keyed by `document_id + version + content_hash`
- SharedPreferences-backed document metadata cache for cached-first display and offline fallback
- Debounced reading-progress sync with immediate heading-anchor sync, persistent offline queue, and retry backoff
- Basic Markdown display formatting
- Android TextToSpeech adapter with paragraph navigation, speech-rate controls, and scroll/progress linkage

Run unit tests with Gradle:

```sh
./gradlew :android-app:testDebugUnitTest
```

The default API base URL is set in `apps/android/app/build.gradle.kts` as `http://10.0.2.2:8080` for the Android emulator.

## CI

GitHub Actions workflow:

- `.github/workflows/test.yml`

It runs:

- `cargo test` with a PostgreSQL service
- `./gradlew :android-app:testDebugUnitTest`
