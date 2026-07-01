use std::sync::Arc;

use thiserror::Error;

use crate::{
    application::document::{
        repository::{
            DocumentDetail, DocumentRepository, DocumentSummary, UpsertReadingProgressError,
        },
        signed_url_gateway::{SignedUrl, SignedUrlGateway},
    },
    domain::document::document::{
        ClientId, DocumentError, DocumentId, DocumentVersionNumber, ProgressPositionType,
        ProgressPositionValue, ProgressRatio, ReadingProgress,
    },
};

#[derive(Debug, Error)]
pub enum UseCaseError<RepositoryError, SignedUrlError> {
    #[error(transparent)]
    Domain(#[from] DocumentError),
    #[error("document not found")]
    DocumentNotFound,
    #[error("document version conflict")]
    DocumentVersionConflict,
    #[error("repository error: {0}")]
    Repository(RepositoryError),
    #[error("signed url error: {0}")]
    SignedUrl(SignedUrlError),
}

#[derive(Debug, Clone, PartialEq)]
pub struct GetDocumentOutput {
    pub detail: DocumentDetail,
    pub signed_url: SignedUrl,
}

#[derive(Debug, Clone)]
pub struct UpdateReadingProgressInput {
    pub client_id: String,
    pub document_id: uuid::Uuid,
    pub version: i32,
    pub position_type: String,
    pub position_value: String,
    pub progress_ratio: f64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateReadingProgressOutput {
    pub document_id: uuid::Uuid,
    pub version: i32,
    pub updated_at: String,
}

#[derive(Clone)]
pub struct ListDocumentsUseCase<R> {
    repository: Arc<R>,
}

#[derive(Clone)]
pub struct GetDocumentUseCase<R, G> {
    repository: Arc<R>,
    signed_url_gateway: Arc<G>,
}

#[derive(Clone)]
pub struct UpdateReadingProgressUseCase<R> {
    repository: Arc<R>,
}

impl<R> ListDocumentsUseCase<R> {
    pub fn new(repository: Arc<R>) -> Self {
        Self { repository }
    }
}

impl<R, G> GetDocumentUseCase<R, G> {
    pub fn new(repository: Arc<R>, signed_url_gateway: Arc<G>) -> Self {
        Self {
            repository,
            signed_url_gateway,
        }
    }
}

impl<R> UpdateReadingProgressUseCase<R> {
    pub fn new(repository: Arc<R>) -> Self {
        Self { repository }
    }
}

impl<R> ListDocumentsUseCase<R>
where
    R: DocumentRepository,
    R::Error: std::fmt::Display,
{
    pub async fn execute(
        &self,
        client_id: String,
    ) -> Result<Vec<DocumentSummary>, UseCaseError<R::Error, std::convert::Infallible>> {
        let client_id = ClientId::new(client_id)?;
        self.repository
            .list_with_progress(&client_id)
            .await
            .map_err(UseCaseError::Repository)
    }
}

impl<R, G> GetDocumentUseCase<R, G>
where
    R: DocumentRepository,
    R::Error: std::fmt::Display,
    G: SignedUrlGateway,
    G::Error: std::fmt::Display,
{
    pub async fn execute(
        &self,
        document_id: uuid::Uuid,
        client_id: String,
    ) -> Result<GetDocumentOutput, UseCaseError<R::Error, G::Error>> {
        let client_id = ClientId::new(client_id)?;
        let document_id = DocumentId::new(document_id);
        let detail = self
            .repository
            .find_detail(document_id, &client_id)
            .await
            .map_err(UseCaseError::Repository)?
            .ok_or(UseCaseError::DocumentNotFound)?;
        let signed_url = self
            .signed_url_gateway
            .generate_read_url(&detail.gcs_object_name)
            .await
            .map_err(UseCaseError::SignedUrl)?;

        Ok(GetDocumentOutput { detail, signed_url })
    }
}

impl<R> UpdateReadingProgressUseCase<R>
where
    R: DocumentRepository,
    R::Error: std::fmt::Display,
{
    pub async fn execute(
        &self,
        input: UpdateReadingProgressInput,
    ) -> Result<UpdateReadingProgressOutput, UseCaseError<R::Error, std::convert::Infallible>> {
        let document_id = DocumentId::new(input.document_id);
        let version = DocumentVersionNumber::new(input.version)?;

        let progress = ReadingProgress::new(
            ClientId::new(input.client_id)?,
            document_id,
            version,
            ProgressPositionType::parse(&input.position_type)?,
            ProgressPositionValue::new(input.position_value)?,
            ProgressRatio::new(input.progress_ratio)?,
        );

        let result = self
            .repository
            .upsert_progress(&progress)
            .await
            .map_err(|err| match err {
                UpsertReadingProgressError::VersionConflict => {
                    UseCaseError::DocumentVersionConflict
                }
                UpsertReadingProgressError::Repository(err) => UseCaseError::Repository(err),
            })?;

        Ok(UpdateReadingProgressOutput {
            document_id: document_id.value(),
            version: version.value(),
            updated_at: result.updated_at,
        })
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use crate::{
        application::document::repository::{
            UpsertReadingProgressError, UpsertReadingProgressResult,
        },
        application::{document::signed_url_gateway::SignedUrl, transaction::TransactionFuture},
        domain::document::document::{ContentHash, GcsObjectName},
    };

    use super::*;

    #[derive(Debug, Clone, PartialEq, Eq)]
    struct TestError(&'static str);

    impl std::fmt::Display for TestError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{}", self.0)
        }
    }

    #[derive(Default)]
    struct MockRepository {
        listed_client_id: Mutex<Option<String>>,
        detail: Mutex<Option<DocumentDetail>>,
        upsert_version_conflict: Mutex<bool>,
        upserted_progress_ratio: Mutex<Option<f64>>,
    }

    impl DocumentRepository for MockRepository {
        type Error = TestError;

        fn list_with_progress<'a>(
            &'a self,
            client_id: &'a ClientId,
        ) -> TransactionFuture<'a, Result<Vec<DocumentSummary>, Self::Error>> {
            Box::pin(async move {
                *self.listed_client_id.lock().unwrap() = Some(client_id.value().to_string());
                Ok(Vec::new())
            })
        }

        fn find_detail<'a>(
            &'a self,
            _document_id: DocumentId,
            _client_id: &'a ClientId,
        ) -> TransactionFuture<'a, Result<Option<DocumentDetail>, Self::Error>> {
            Box::pin(async move { Ok(self.detail.lock().unwrap().clone()) })
        }

        fn upsert_progress<'a>(
            &'a self,
            progress: &'a ReadingProgress,
        ) -> TransactionFuture<
            'a,
            Result<UpsertReadingProgressResult, UpsertReadingProgressError<Self::Error>>,
        > {
            Box::pin(async move {
                if *self.upsert_version_conflict.lock().unwrap() {
                    return Err(UpsertReadingProgressError::VersionConflict);
                }
                *self.upserted_progress_ratio.lock().unwrap() =
                    Some(progress.progress_ratio().value());
                Ok(UpsertReadingProgressResult {
                    updated_at: "2026-06-29 00:00:00+00".to_string(),
                })
            })
        }
    }

    #[derive(Default)]
    struct MockSignedUrlGateway {
        requested_object_name: Mutex<Option<String>>,
    }

    impl SignedUrlGateway for MockSignedUrlGateway {
        type Error = TestError;

        fn generate_read_url<'a>(
            &'a self,
            object_name: &'a GcsObjectName,
        ) -> TransactionFuture<'a, Result<SignedUrl, Self::Error>> {
            Box::pin(async move {
                *self.requested_object_name.lock().unwrap() = Some(object_name.value().to_string());
                Ok(SignedUrl {
                    url: "https://signed.example/content.md".to_string(),
                    expires_at: "2026-06-29T01:00:00Z".to_string(),
                })
            })
        }
    }

    #[tokio::test]
    async fn list_documents_passes_client_id_to_repository() {
        let repository = Arc::new(MockRepository::default());
        let usecase = ListDocumentsUseCase::new(repository.clone());

        usecase.execute("client-1".to_string()).await.unwrap();

        assert_eq!(
            repository.listed_client_id.lock().unwrap().as_deref(),
            Some("client-1")
        );
    }

    #[tokio::test]
    async fn get_document_generates_signed_url_from_detail_object_name() {
        let repository = Arc::new(MockRepository::default());
        let gateway = Arc::new(MockSignedUrlGateway::default());
        let document_id = DocumentId::new(uuid::Uuid::now_v7());
        *repository.detail.lock().unwrap() = Some(DocumentDetail {
            document_id,
            title: "Sample".to_string(),
            version: DocumentVersionNumber::new(1).unwrap(),
            gcs_object_name: GcsObjectName::new("documents/sample/v1/content.md").unwrap(),
            content_hash: ContentHash::new("sha256:test").unwrap(),
            reading_progress: None,
        });
        let usecase = GetDocumentUseCase::new(repository, gateway.clone());

        let output = usecase
            .execute(document_id.value(), "client-1".to_string())
            .await
            .unwrap();

        assert_eq!(output.signed_url.url, "https://signed.example/content.md");
        assert_eq!(
            gateway.requested_object_name.lock().unwrap().as_deref(),
            Some("documents/sample/v1/content.md")
        );
    }

    #[tokio::test]
    async fn update_progress_rejects_unknown_version() {
        let repository = Arc::new(MockRepository::default());
        *repository.upsert_version_conflict.lock().unwrap() = true;
        let usecase = UpdateReadingProgressUseCase::new(repository);

        let result = usecase
            .execute(UpdateReadingProgressInput {
                client_id: "client-1".to_string(),
                document_id: uuid::Uuid::now_v7(),
                version: 99,
                position_type: "heading_anchor".to_string(),
                position_value: "chapter-1".to_string(),
                progress_ratio: 0.4,
            })
            .await;

        assert!(matches!(result, Err(UseCaseError::DocumentVersionConflict)));
    }

    #[tokio::test]
    async fn update_progress_validates_and_upserts_progress() {
        let repository = Arc::new(MockRepository::default());
        let usecase = UpdateReadingProgressUseCase::new(repository.clone());

        let output = usecase
            .execute(UpdateReadingProgressInput {
                client_id: "client-1".to_string(),
                document_id: uuid::Uuid::now_v7(),
                version: 1,
                position_type: "heading_anchor".to_string(),
                position_value: "chapter-1".to_string(),
                progress_ratio: 0.4,
            })
            .await
            .unwrap();

        assert_eq!(
            *repository.upserted_progress_ratio.lock().unwrap(),
            Some(0.4)
        );
        assert_eq!(output.updated_at, "2026-06-29 00:00:00+00");
    }
}
