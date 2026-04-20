use axum::{Json, Router, routing::get};
use serde_json::{Value, json};
use tower_http::trace::TraceLayer;
use tracing_subscriber::{EnvFilter, fmt};

#[tokio::main]
async fn main() {
    fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("info,tower_http=debug")),
        )
        .init();

    let app = Router::new()
        .route("/", get(root))
        .route("/health", get(health))
        .layer(TraceLayer::new_for_http());

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8000")
        .await
        .expect("failed to bind 0.0.0.0:8000");

    tracing::info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.expect("server error");
}

async fn root() -> &'static str {
    "Hello, Axum!"
}

async fn health() -> Json<Value> {
    Json(json!({ "status": "ok" }))
}
