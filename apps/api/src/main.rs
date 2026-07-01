use api::{build_router, di::AppState, infrastructure};

use infrastructure::database::DatabaseConfig;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let database_config = DatabaseConfig::from_env()?;
    let db_pool = infrastructure::database::connect(&database_config).await?;
    let app_state = AppState::new(db_pool)?;
    let app = build_router(app_state);

    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".to_string());
    let addr = format!("0.0.0.0:{port}");

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|err| panic!("failed to bind to {addr}: {err}"));

    println!("listening on {addr}");

    axum::serve(listener, app).await?;

    Ok(())
}
