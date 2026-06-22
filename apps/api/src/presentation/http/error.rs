use crate::domain::document::document::DocumentError;
use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

pub struct AppError(pub StatusCode, pub String);

#[derive(Serialize)]
struct ErrorBody {
    error: String,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (self.0, Json(ErrorBody { error: self.1 })).into_response()
    }
}

impl From<DocumentError> for AppError {
    fn from(e: DocumentError) -> Self {
        bad_request(e.to_string())
    }
}

pub fn bad_request(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::BAD_REQUEST, msg.into())
}

pub fn unauthorized(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::UNAUTHORIZED, msg.into())
}

pub fn internal(msg: impl Into<String>) -> AppError {
    AppError(StatusCode::INTERNAL_SERVER_ERROR, msg.into())
}
