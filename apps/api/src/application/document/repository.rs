use crate::{
    application::transaction::TransactionFuture,
    domain::document::document::{
        ClientId, ContentHash, DocumentId, DocumentVersionNumber, GcsObjectName, ReadingProgress,
    },
};

#[derive(Debug, Clone, PartialEq)]
pub struct DocumentSummary {
    pub document_id: DocumentId,
    pub title: String,
    pub version: DocumentVersionNumber,
    pub progress_ratio: Option<f64>,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DocumentDetail {
    pub document_id: DocumentId,
    pub title: String,
    pub version: DocumentVersionNumber,
    pub gcs_object_name: GcsObjectName,
    pub content_hash: ContentHash,
    pub reading_progress: Option<ReadingProgressView>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReadingProgressView {
    pub position_type: String,
    pub position_value: String,
    pub progress_ratio: f64,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpsertReadingProgressResult {
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpsertReadingProgressError<E> {
    VersionConflict,
    Repository(E),
}

pub trait DocumentRepository: Send + Sync {
    type Error: Send;

    fn list_with_progress<'a>(
        &'a self,
        client_id: &'a ClientId,
    ) -> TransactionFuture<'a, Result<Vec<DocumentSummary>, Self::Error>>;

    fn find_detail<'a>(
        &'a self,
        document_id: DocumentId,
        client_id: &'a ClientId,
    ) -> TransactionFuture<'a, Result<Option<DocumentDetail>, Self::Error>>;

    fn upsert_progress<'a>(
        &'a self,
        progress: &'a ReadingProgress,
    ) -> TransactionFuture<
        'a,
        Result<UpsertReadingProgressResult, UpsertReadingProgressError<Self::Error>>,
    >;
}
