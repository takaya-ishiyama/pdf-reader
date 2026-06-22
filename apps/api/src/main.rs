use axum::{Json, Router, response::IntoResponse, routing::get};
use serde::Serialize;

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
}

mod domain;

#[tokio::main]
async fn main() {
    // build our application with a single route

    let app = Router::new()
        .route("/", get(root))
        .route("/health", get(health));

    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".to_string());
    let addr = format!("0.0.0.0:{port}");

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|err| panic!("failed to bind to {addr}: {err}"));

    println!("listening on {addr}");

    axum::serve(listener, app).await.unwrap();
}

async fn root() -> &'static str {
    "Hello, Axum!"
}

async fn health() -> impl IntoResponse {
    Json(HealthResponse { status: "ok" })
}
