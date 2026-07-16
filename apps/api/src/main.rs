use api::{
    build_router,
    config::AppConfig,
    di::AppState,
    infrastructure::{self, gcs_signed_url_gateway::ConfiguredSignedUrlGateway},
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let config = AppConfig::from_env()?;
    let db_pool = infrastructure::database::connect(&config.database).await?;
    let signed_url_gateway = ConfiguredSignedUrlGateway::from_config(config.signed_url);
    let app_state = AppState::new(db_pool, signed_url_gateway);
    let app = build_router(app_state);

    let addr = (config.server.host, config.server.port);

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|err| panic!("failed to bind to {addr:?}: {err}"));

    println!("listening on {}:{}", addr.0, addr.1);

    axum::serve(listener, app).await?;

    Ok(())
}
