use sqlx::Row;

use crate::{
    application::{
        document::repository::{
            DocumentDetail, DocumentRepository, DocumentSummary, ReadingProgressView,
            UpsertReadingProgressError, UpsertReadingProgressResult,
        },
        transaction::TransactionFuture,
    },
    domain::document::document::{
        ClientId, ContentHash, DocumentId, DocumentVersionNumber, GcsObjectName,
        ProgressPositionType, ProgressPositionValue, ProgressRatio, ReadingProgress,
    },
    infrastructure::database::DbPool,
};

#[derive(Debug, Clone)]
pub struct SqlxDocumentRepository {
    db_pool: DbPool,
}

impl SqlxDocumentRepository {
    pub fn new(db_pool: DbPool) -> Self {
        Self { db_pool }
    }
}

impl DocumentRepository for SqlxDocumentRepository {
    type Error = sqlx::Error;

    fn list_with_progress<'a>(
        &'a self,
        client_id: &'a ClientId,
    ) -> TransactionFuture<'a, Result<Vec<DocumentSummary>, Self::Error>> {
        Box::pin(async move {
            let rows = sqlx::query(
                r#"
                SELECT
                    d.document_id,
                    d.title,
                    d.current_version,
                    rp.progress_ratio::float8 AS progress_ratio,
                    rp.updated_at::text AS progress_updated_at
                FROM pdf_reader.documents d
                LEFT JOIN pdf_reader.reading_progress rp
                    ON rp.document_id = d.document_id
                    AND rp.client_id = $1
                ORDER BY d.title ASC
                "#,
            )
            .bind(client_id.value())
            .fetch_all(&self.db_pool)
            .await?;

            rows.into_iter()
                .map(|row| {
                    Ok(DocumentSummary {
                        document_id: DocumentId::new(row.get("document_id")),
                        title: row.get("title"),
                        version: DocumentVersionNumber::new(row.get("current_version"))
                            .expect("database version constraint should enforce valid versions"),
                        progress_ratio: row.try_get("progress_ratio").ok(),
                        updated_at: row.try_get("progress_updated_at").ok(),
                    })
                })
                .collect()
        })
    }

    fn find_detail<'a>(
        &'a self,
        document_id: DocumentId,
        client_id: &'a ClientId,
    ) -> TransactionFuture<'a, Result<Option<DocumentDetail>, Self::Error>> {
        Box::pin(async move {
            let row = sqlx::query(
                r#"
                SELECT
                    d.document_id,
                    d.title,
                    dv.version,
                    dv.gcs_object_name,
                    dv.content_hash,
                    rp.position_type,
                    rp.position_value,
                    rp.progress_ratio::float8 AS progress_ratio,
                    rp.updated_at::text AS progress_updated_at
                FROM pdf_reader.documents d
                JOIN pdf_reader.document_versions dv
                    ON dv.document_id = d.document_id
                    AND dv.version = d.current_version
                LEFT JOIN pdf_reader.reading_progress rp
                    ON rp.document_id = d.document_id
                    AND rp.client_id = $2
                WHERE d.document_id = $1
                "#,
            )
            .bind(document_id.value())
            .bind(client_id.value())
            .fetch_optional(&self.db_pool)
            .await?;

            Ok(row.map(|row| {
                let reading_progress =
                    row.try_get::<String, _>("position_type")
                        .ok()
                        .map(|position_type| ReadingProgressView {
                            position_type,
                            position_value: row.get("position_value"),
                            progress_ratio: row.get("progress_ratio"),
                            updated_at: row.get("progress_updated_at"),
                        });

                DocumentDetail {
                    document_id: DocumentId::new(row.get("document_id")),
                    title: row.get("title"),
                    version: DocumentVersionNumber::new(row.get("version"))
                        .expect("database version constraint should enforce valid versions"),
                    gcs_object_name: GcsObjectName::new(row.get::<String, _>("gcs_object_name"))
                        .expect("database constraint should enforce non-empty gcs object names"),
                    content_hash: ContentHash::new(row.get::<String, _>("content_hash"))
                        .expect("database constraint should enforce non-empty content hashes"),
                    reading_progress,
                }
            }))
        })
    }

    fn upsert_progress<'a>(
        &'a self,
        progress: &'a ReadingProgress,
    ) -> TransactionFuture<
        'a,
        Result<UpsertReadingProgressResult, UpsertReadingProgressError<Self::Error>>,
    > {
        Box::pin(async move {
            let updated_at: String = sqlx::query_scalar(
                r#"
                INSERT INTO pdf_reader.reading_progress (
                    client_id,
                    document_id,
                    version,
                    position_type,
                    position_value,
                    progress_ratio
                )
                VALUES ($1, $2, $3, $4, $5, $6)
                ON CONFLICT (client_id, document_id)
                DO UPDATE SET
                    version = EXCLUDED.version,
                    position_type = EXCLUDED.position_type,
                    position_value = EXCLUDED.position_value,
                    progress_ratio = EXCLUDED.progress_ratio,
                    updated_at = now()
                RETURNING updated_at::text
                "#,
            )
            .bind(progress.client_id().value())
            .bind(progress.document_id().value())
            .bind(progress.version().value())
            .bind(progress.position_type().as_str())
            .bind(progress.position_value().value())
            .bind(progress.progress_ratio().value())
            .fetch_one(&self.db_pool)
            .await
            .map_err(map_upsert_error)?;

            Ok(UpsertReadingProgressResult { updated_at })
        })
    }
}

fn map_upsert_error(err: sqlx::Error) -> UpsertReadingProgressError<sqlx::Error> {
    let is_foreign_key_violation = err
        .as_database_error()
        .and_then(|db_error| db_error.code())
        .is_some_and(|code| code == "23503");

    if is_foreign_key_violation {
        UpsertReadingProgressError::VersionConflict
    } else {
        UpsertReadingProgressError::Repository(err)
    }
}

#[allow(dead_code)]
fn _assert_domain_types_are_used(
    _: ProgressPositionType,
    _: ProgressPositionValue,
    _: ProgressRatio,
) {
}
