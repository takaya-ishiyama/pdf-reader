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
SIGNED_URL_TTL_SECONDS=3600
```

`DATABASE_URL_FILE` may be used instead of `DATABASE_URL`. Set exactly one of
the two. The file form is intended for production secret mounts:

```sh
DATABASE_URL_FILE=/var/run/app-secrets/database/database-url
```

GCS signed URLs are signed with the IAM Credentials `signBlob` API. The
application never loads a Google private key. For local development, initialize
Application Default Credentials once:

```sh
gcloud auth application-default login
gcloud auth application-default set-quota-project PROJECT_ID
```

The authenticated developer needs `iam.serviceAccounts.signBlob` on
`GOOGLE_SERVICE_ACCOUNT_EMAIL`. `roles/iam.serviceAccountTokenCreator` provides
that permission; a custom role containing only `iam.serviceAccounts.signBlob`
is more restrictive.

For a production-like local database setup, put the connection URL in the
Git-ignored `.local-secrets/database-url` file and run:

```sh
DATABASE_URL_FILE="$PWD/.local-secrets/database-url" \
GCS_BUCKET=your-markdown-bucket \
GOOGLE_SERVICE_ACCOUNT_EMAIL=cloud-run-signer@PROJECT_ID.iam.gserviceaccount.com \
cargo run
```

Using `DATABASE_URL` directly remains supported for convenient local
development. Never reuse the local PostgreSQL password in production.

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

Secret expected by the template:

- `markdown-reader-database-url`

The Cloud Run service account needs:

- Access to `markdown-reader-database-url` in Secret Manager
- GCS object read permission for the configured bucket
- `iam.serviceAccounts.signBlob` on the service account named by
  `GOOGLE_SERVICE_ACCOUNT_EMAIL`

Enable the IAM Service Account Credentials API before deployment. The Cloud Run
template mounts the database URL as a file and uses the service identity's
Application Default Credentials to call `signBlob`.

Example GCP setup (replace the values first):

```sh
PROJECT_ID=your-project
BUCKET=your-markdown-bucket
RUNTIME_SA=markdown-reader-api@$PROJECT_ID.iam.gserviceaccount.com
SIGNER_SA=$RUNTIME_SA

gcloud services enable \
  iamcredentials.googleapis.com \
  run.googleapis.com \
  secretmanager.googleapis.com \
  --project "$PROJECT_ID"

gcloud secrets add-iam-policy-binding markdown-reader-database-url \
  --project "$PROJECT_ID" \
  --member "serviceAccount:$RUNTIME_SA" \
  --role roles/secretmanager.secretAccessor

gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
  --member "serviceAccount:$RUNTIME_SA" \
  --role roles/storage.objectViewer

gcloud iam service-accounts add-iam-policy-binding "$SIGNER_SA" \
  --project "$PROJECT_ID" \
  --member "serviceAccount:$RUNTIME_SA" \
  --role roles/iam.serviceAccountTokenCreator
```

For local IAM signing, grant the developer identity the same signing role on
`SIGNER_SA`:

```sh
DEVELOPER_EMAIL=developer@example.com

gcloud iam service-accounts add-iam-policy-binding "$SIGNER_SA" \
  --project "$PROJECT_ID" \
  --member "user:$DEVELOPER_EMAIL" \
  --role roles/iam.serviceAccountTokenCreator
```

The predefined Token Creator role is convenient but broader than signing alone.
Use a custom role containing `iam.serviceAccounts.signBlob` when least privilege
is required.

After successfully deploying and testing IAM signing, disable and delete the
old user-managed Google service account key, then remove the obsolete
`markdown-reader-google-private-key` and
`markdown-reader-google-service-account-email` secrets.

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
