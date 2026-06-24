use std::{env, time::Duration};

use sqlx::{PgPool, postgres::PgPoolOptions};

pub type DbPool = PgPool;

#[derive(Debug, Clone)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout: Duration,
}

impl DatabaseConfig {
    pub fn from_env() -> Result<Self, env::VarError> {
        let url = env::var("DATABASE_URL")?;

        Ok(Self {
            url,
            max_connections: env_u32("DATABASE_MAX_CONNECTIONS", 5),
            min_connections: env_u32("DATABASE_MIN_CONNECTIONS", 1),
            acquire_timeout: Duration::from_secs(env_u64("DATABASE_ACQUIRE_TIMEOUT_SECS", 5)),
        })
    }
}

pub async fn connect(config: &DatabaseConfig) -> Result<DbPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(config.max_connections)
        .min_connections(config.min_connections)
        .acquire_timeout(config.acquire_timeout)
        .connect(&config.url)
        .await
}

fn env_u32(key: &str, default: u32) -> u32 {
    env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn env_u64(key: &str, default: u64) -> u64 {
    env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}
