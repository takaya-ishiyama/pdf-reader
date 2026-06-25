use sqlx::{PgConnection, Row};

use crate::{
    application::{document::repository::DocumentRepository, transaction::TransactionFuture},
    domain::document::document::{Document, DocumentId},
};

#[derive(Debug, Clone, Default)]
pub struct SqlxDocumentRepository;

impl SqlxDocumentRepository {
    pub fn new() -> Self {
        Self
    }
}

impl DocumentRepository<PgConnection> for SqlxDocumentRepository {
    type Error = sqlx::Error;

    fn find_by_id<'a>(
        &'a self,
        tx: &'a mut PgConnection,
        id: DocumentId,
    ) -> TransactionFuture<'a, Result<Option<Document>, Self::Error>> {
        Box::pin(async move {
            let row = sqlx::query(
                r#"
                SELECT id, title, latest_read_line, version, document_url
                FROM documents
                WHERE id = $1
                "#,
            )
            .bind(id.0)
            .fetch_optional(&mut *tx)
            .await?;

            Ok(row.map(|row| {
                Document::new(
                    Some(DocumentId(row.get("id"))),
                    row.get("title"),
                    row.get("latest_read_line"),
                    row.get("version"),
                    row.get("document_url"),
                )
            }))
        })
    }

    fn save<'a>(
        &'a self,
        tx: &'a mut PgConnection,
        document: &'a Document,
    ) -> TransactionFuture<'a, Result<(), Self::Error>> {
        Box::pin(async move {
            sqlx::query(
                r#"
                INSERT INTO documents (
                    id,
                    title,
                    latest_read_line,
                    version,
                    document_url
                )
                VALUES ($1, $2, $3, $4, $5)
                "#,
            )
            .bind(document.id.0)
            .bind(&document.title)
            .bind(document.latest_read_line)
            .bind(document.version)
            .bind(&document.document_url)
            .execute(&mut *tx)
            .await?;

            Ok(())
        })
    }
}
