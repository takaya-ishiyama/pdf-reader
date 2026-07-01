use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

pub struct AppError(pub StatusCode, pub &'static str, pub String);

#[derive(Serialize)]
struct ErrorBody {
    error: ErrorDetail,
}

#[derive(Serialize)]
struct ErrorDetail {
    code: &'static str,
    message: String,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(ErrorBody {
                error: ErrorDetail {
                    code: self.1,
                    message: self.2,
                },
            }),
        )
            .into_response()
    }
}

pub fn bad_request(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::BAD_REQUEST, "BAD_REQUEST", msg.into())
}

pub fn invalid_progress(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::BAD_REQUEST, "INVALID_PROGRESS", msg.into())
}

pub fn not_found(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::NOT_FOUND, "DOCUMENT_NOT_FOUND", msg.into())
}

pub fn database_error(msg: impl Into<String>) -> AppError {
    AppError(
        StatusCode::INTERNAL_SERVER_ERROR,
        "DATABASE_ERROR",
        msg.into(),
    )
}

pub fn signed_url_failed(msg: impl Into<String>) -> AppError {
    AppError(
        StatusCode::INTERNAL_SERVER_ERROR,
        "SIGNED_URL_FAILED",
        msg.into(),
    )
}

pub fn conflict(msg: impl Into<String>) -> AppError {
    AppError(
        StatusCode::CONFLICT,
        "DOCUMENT_VERSION_CONFLICT",
        msg.into(),
    )
}
