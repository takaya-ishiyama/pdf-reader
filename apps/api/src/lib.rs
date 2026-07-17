pub mod application;
pub mod config;
pub mod di;
pub mod domain;
pub mod infrastructure;
pub mod presentation;

use axum::{Json, Router, response::IntoResponse, routing::get};
use di::AppState;
use serde::Serialize;

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
}

pub fn build_router(app_state: AppState) -> Router {
    Router::new()
        .route("/", get(root))
        .route("/healthz", get(health))
        .merge(presentation::http::document_routes())
        .with_state(app_state)
}

async fn root() -> &'static str {
    "Hello, Axum!"
}

async fn health() -> impl IntoResponse {
    Json(HealthResponse { status: "ok" })
}
