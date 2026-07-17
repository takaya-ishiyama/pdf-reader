use axum::{
    Json, Router,
    extract::{
        Path, Query, State,
        rejection::{JsonRejection, QueryRejection},
    },
    routing::{get, put},
};
use serde::{Deserialize, Serialize};

use crate::{
    application::document::{
        repository::{DocumentSummary, ReadingProgressView},
        usecase::{UpdateReadingProgressInput, UseCaseError},
    },
    di::AppState,
    presentation::http::error::{
        AppError, bad_request, conflict, database_error, invalid_progress, not_found,
        signed_url_failed,
    },
};

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/documents", get(list_documents))
        .route("/v1/documents/{document_id}", get(get_document))
        .route(
            "/v1/documents/{document_id}/progress",
            put(update_reading_progress),
        )
}

#[derive(Debug, Deserialize)]
struct ClientQuery {
    client_id: String,
}

#[derive(Debug, Serialize)]
struct ListDocumentsResponse {
    documents: Vec<DocumentSummaryResponse>,
}

#[derive(Debug, Serialize)]
struct DocumentSummaryResponse {
    document_id: String,
    title: String,
    version: i32,
    progress_ratio: Option<f64>,
    updated_at: Option<String>,
}

#[derive(Debug, Serialize)]
struct GetDocumentResponse {
    document_id: String,
    title: String,
    version: i32,
    content_hash: String,
    signed_url: String,
    signed_url_expires_at: String,
    cache_control: CacheControlResponse,
    reading_progress: Option<ReadingProgressResponse>,
}

#[derive(Debug, Serialize)]
struct CacheControlResponse {
    max_age_seconds: u64,
    stale_while_revalidate_seconds: u64,
}

#[derive(Debug, Serialize)]
struct ReadingProgressResponse {
    position_type: String,
    position_value: String,
    progress_ratio: f64,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
struct UpdateReadingProgressRequest {
    client_id: String,
    version: i32,
    position_type: String,
    position_value: String,
    progress_ratio: f64,
}

#[derive(Debug, Serialize)]
struct UpdateReadingProgressResponse {
    document_id: String,
    version: i32,
    updated_at: String,
}

async fn list_documents(
    State(state): State<AppState>,
    query: Result<Query<ClientQuery>, QueryRejection>,
) -> Result<Json<ListDocumentsResponse>, AppError> {
    let Query(query) = query.map_err(|err| bad_request(err.to_string()))?;
    let documents = state
        .usecases
        .list_documents
        .execute(query.client_id)
        .await
        .map_err(map_infallible_signed_url_error)?;

    Ok(Json(ListDocumentsResponse {
        documents: documents
            .into_iter()
            .map(DocumentSummaryResponse::from)
            .collect(),
    }))
}

async fn get_document(
    State(state): State<AppState>,
    Path(document_id): Path<String>,
    query: Result<Query<ClientQuery>, QueryRejection>,
) -> Result<Json<GetDocumentResponse>, AppError> {
    let document_id = parse_uuid(&document_id)?;
    let Query(query) = query.map_err(|err| bad_request(err.to_string()))?;
    let output = state
        .usecases
        .get_document
        .execute(document_id, query.client_id)
        .await
        .map_err(map_usecase_error)?;

    Ok(Json(GetDocumentResponse {
        document_id: output.detail.document_id.value().to_string(),
        title: output.detail.title,
        version: output.detail.version.value(),
        content_hash: output.detail.content_hash.value().to_string(),
        signed_url: output.signed_url.url,
        signed_url_expires_at: output.signed_url.expires_at,
        cache_control: CacheControlResponse {
            max_age_seconds: 86400,
            stale_while_revalidate_seconds: 604800,
        },
        reading_progress: output
            .detail
            .reading_progress
            .map(ReadingProgressResponse::from),
    }))
}

async fn update_reading_progress(
    State(state): State<AppState>,
    Path(document_id): Path<String>,
    request: Result<Json<UpdateReadingProgressRequest>, JsonRejection>,
) -> Result<Json<UpdateReadingProgressResponse>, AppError> {
    let document_id = parse_uuid(&document_id)?;
    let Json(request) = request.map_err(|err| bad_request(err.to_string()))?;
    let version = request.version;
    let output = state
        .usecases
        .update_reading_progress
        .execute(UpdateReadingProgressInput {
            client_id: request.client_id,
            document_id,
            version,
            position_type: request.position_type,
            position_value: request.position_value,
            progress_ratio: request.progress_ratio,
        })
        .await
        .map_err(map_infallible_signed_url_error)?;

    Ok(Json(UpdateReadingProgressResponse {
        document_id: output.document_id.to_string(),
        version: output.version,
        updated_at: output.updated_at,
    }))
}

impl From<DocumentSummary> for DocumentSummaryResponse {
    fn from(value: DocumentSummary) -> Self {
        Self {
            document_id: value.document_id.value().to_string(),
            title: value.title,
            version: value.version.value(),
            progress_ratio: value.progress_ratio,
            updated_at: value.updated_at,
        }
    }
}

fn parse_uuid(value: &str) -> Result<uuid::Uuid, AppError> {
    uuid::Uuid::parse_str(value).map_err(|_| bad_request("invalid document_id"))
}

impl From<ReadingProgressView> for ReadingProgressResponse {
    fn from(value: ReadingProgressView) -> Self {
        Self {
            position_type: value.position_type,
            position_value: value.position_value,
            progress_ratio: value.progress_ratio,
            updated_at: value.updated_at,
        }
    }
}

fn map_infallible_signed_url_error<E>(err: UseCaseError<E, std::convert::Infallible>) -> AppError
where
    E: std::fmt::Display,
{
    match err {
        UseCaseError::Domain(err) => invalid_progress(err.to_string()),
        UseCaseError::DocumentNotFound => not_found("document not found"),
        UseCaseError::DocumentVersionConflict => conflict("document version conflict"),
        UseCaseError::Repository(err) => database_error(err.to_string()),
        UseCaseError::SignedUrl(err) => match err {},
    }
}

fn map_usecase_error<E, G>(err: UseCaseError<E, G>) -> AppError
where
    E: std::fmt::Display,
    G: std::fmt::Display,
{
    match err {
        UseCaseError::Domain(err) => invalid_progress(err.to_string()),
        UseCaseError::DocumentNotFound => not_found("document not found"),
        UseCaseError::DocumentVersionConflict => conflict("document version conflict"),
        UseCaseError::Repository(err) => database_error(err.to_string()),
        UseCaseError::SignedUrl(err) => signed_url_failed(err.to_string()),
    }
}
