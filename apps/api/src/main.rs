use axum::{Json, Router, response::IntoResponse, routing::get};
use serde::Serialize;

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
}

#[tokio::main]
async fn main() {
    // build our application with a single route

    let app = Router::new()
        .route("/", get(root))
        .route("/health", get(health));

    // run our app with hyper, listening globally on port 8000
    let listener = tokio::net::TcpListener::bind("0.0.0.0:8000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn root() -> &'static str {
    "Hello, Axum!"
}

async fn health() -> impl IntoResponse {
    Json(HealthResponse { status: "ok" })
}
