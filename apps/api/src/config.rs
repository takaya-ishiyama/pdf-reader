use std::{env, net::Ipv4Addr, time::Duration};

use thiserror::Error;

use crate::infrastructure::{database::DatabaseConfig, gcs_signed_url_gateway::SignedUrlConfig};

const DEFAULT_PORT: u16 = 8080;
const DEFAULT_DATABASE_MAX_CONNECTIONS: u32 = 5;
const DEFAULT_DATABASE_MIN_CONNECTIONS: u32 = 1;
const DEFAULT_DATABASE_ACQUIRE_TIMEOUT_SECS: u64 = 5;
const DEFAULT_SIGNED_URL_TTL_SECONDS: u64 = 3600;

/// Complete process configuration, loaded once at the composition root.
#[derive(Clone)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub signed_url: SignedUrlConfig,
}

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub host: Ipv4Addr,
    pub port: u16,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ConfigError {
    #[error("missing environment variable: {0}")]
    Missing(&'static str),
    #[error("environment variable {key} has invalid value {value:?}")]
    Invalid { key: &'static str, value: String },
    #[error("DATABASE_MIN_CONNECTIONS must not exceed DATABASE_MAX_CONNECTIONS")]
    InvalidDatabasePoolSize,
}

impl AppConfig {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_source(|key| env::var(key).ok())
    }

    fn from_source(get: impl Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let max_connections = optional(
            &get,
            "DATABASE_MAX_CONNECTIONS",
            DEFAULT_DATABASE_MAX_CONNECTIONS,
        )?;
        let min_connections = optional(
            &get,
            "DATABASE_MIN_CONNECTIONS",
            DEFAULT_DATABASE_MIN_CONNECTIONS,
        )?;
        if min_connections > max_connections {
            return Err(ConfigError::InvalidDatabasePoolSize);
        }

        Ok(Self {
            server: ServerConfig {
                host: Ipv4Addr::UNSPECIFIED,
                port: optional(&get, "PORT", DEFAULT_PORT)?,
            },
            database: DatabaseConfig {
                url: required(&get, "DATABASE_URL")?,
                max_connections,
                min_connections,
                acquire_timeout: Duration::from_secs(optional(
                    &get,
                    "DATABASE_ACQUIRE_TIMEOUT_SECS",
                    DEFAULT_DATABASE_ACQUIRE_TIMEOUT_SECS,
                )?),
            },
            signed_url: SignedUrlConfig {
                bucket: required(&get, "GCS_BUCKET")?,
                ttl_seconds: optional(
                    &get,
                    "SIGNED_URL_TTL_SECONDS",
                    DEFAULT_SIGNED_URL_TTL_SECONDS,
                )?,
                service_account_email: required(&get, "GOOGLE_SERVICE_ACCOUNT_EMAIL")?,
                private_key_pem: required(&get, "GOOGLE_PRIVATE_KEY")?.replace("\\n", "\n"),
            },
        })
    }
}

fn required(
    get: &impl Fn(&str) -> Option<String>,
    key: &'static str,
) -> Result<String, ConfigError> {
    get(key)
        .filter(|value| !value.is_empty())
        .ok_or(ConfigError::Missing(key))
}

fn optional<T: std::str::FromStr>(
    get: &impl Fn(&str) -> Option<String>,
    key: &'static str,
    default: T,
) -> Result<T, ConfigError> {
    let Some(value) = get(key) else {
        return Ok(default);
    };
    value
        .parse()
        .map_err(|_| ConfigError::Invalid { key, value })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn required_values() -> HashMap<&'static str, String> {
        HashMap::from([
            ("DATABASE_URL", "postgres://localhost/app".into()),
            ("GCS_BUCKET", "documents".into()),
            ("GOOGLE_SERVICE_ACCOUNT_EMAIL", "signer@example.com".into()),
            ("GOOGLE_PRIVATE_KEY", "line-1\\nline-2".into()),
        ])
    }

    #[test]
    fn loads_defaults_and_normalizes_private_key() {
        let values = required_values();
        let config = AppConfig::from_source(|key| values.get(key).cloned()).unwrap();
        assert_eq!(config.server.port, 8080);
        assert_eq!(config.database.max_connections, 5);
        assert_eq!(config.database.min_connections, 1);
        assert_eq!(config.database.acquire_timeout, Duration::from_secs(5));
        assert_eq!(config.signed_url.ttl_seconds, 3600);
        assert_eq!(config.signed_url.private_key_pem, "line-1\nline-2");
    }

    #[test]
    fn rejects_invalid_optional_value() {
        let mut values = required_values();
        values.insert("PORT", "not-a-port".into());
        let result = AppConfig::from_source(|key| values.get(key).cloned());
        assert_eq!(
            result.err().expect("invalid port should fail"),
            ConfigError::Invalid {
                key: "PORT",
                value: "not-a-port".into()
            }
        );
    }
}
