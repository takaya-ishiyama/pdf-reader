use axum::{Json, Router, response::IntoResponse, routing::get};
use di::AppState;
use infrastructure::database::DatabaseConfig;
use serde::Serialize;

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
}

mod application;
mod di;
mod domain;
mod infrastructure;
mod presentation;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let database_config = DatabaseConfig::from_env()?;
    let db_pool = infrastructure::database::connect(&database_config).await?;
    let app_state = AppState::new(db_pool);

    let app = Router::new()
        .route("/", get(root))
        .route("/health", get(health))
        .with_state(app_state);

    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|err| panic!("failed to bind to {addr}: {err}"));

    println!("listening on {addr}");

    axum::serve(listener, app).await?;

    Ok(())
}

async fn root() -> &'static str {
    "Hello, Axum!"
}

async fn health() -> impl IntoResponse {
    Json(HealthResponse { status: "ok" })
}
