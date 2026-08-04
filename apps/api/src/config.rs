use std::{env, fs, net::Ipv4Addr, time::Duration};

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

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("missing environment variable: {0}")]
    Missing(&'static str),
    #[error("environment variable {key} has invalid value {value:?}")]
    Invalid { key: &'static str, value: String },
    #[error("set only one of {variable} and {file_variable}")]
    ConflictingSecretSources {
        variable: &'static str,
        file_variable: &'static str,
    },
    #[error("failed to read secret file configured by {key}: {source}")]
    SecretFile {
        key: &'static str,
        #[source]
        source: std::io::Error,
    },
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
                url: required_secret(&get, "DATABASE_URL", "DATABASE_URL_FILE")?,
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
                service_account_email: get("GOOGLE_SERVICE_ACCOUNT_EMAIL")
                    .filter(|value| !value.is_empty()),
            },
        })
    }
}

fn required_secret(
    get: &impl Fn(&str) -> Option<String>,
    variable: &'static str,
    file_variable: &'static str,
) -> Result<String, ConfigError> {
    match (get(variable), get(file_variable)) {
        (Some(_), Some(_)) => Err(ConfigError::ConflictingSecretSources {
            variable,
            file_variable,
        }),
        (Some(value), None) => non_empty(value, variable),
        (None, Some(path)) => {
            let value = fs::read_to_string(&path).map_err(|source| ConfigError::SecretFile {
                key: file_variable,
                source,
            })?;
            non_empty(value.trim_end().to_owned(), file_variable)
        }
        (None, None) => Err(ConfigError::Missing(variable)),
    }
}

fn non_empty(value: String, key: &'static str) -> Result<String, ConfigError> {
    if value.is_empty() {
        Err(ConfigError::Missing(key))
    } else {
        Ok(value)
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
        ])
    }

    #[test]
    fn loads_defaults_and_required_values() {
        let values = required_values();
        let config = AppConfig::from_source(|key| values.get(key).cloned()).unwrap();
        assert_eq!(config.server.port, 8080);
        assert_eq!(config.database.max_connections, 5);
        assert_eq!(config.database.min_connections, 1);
        assert_eq!(config.database.acquire_timeout, Duration::from_secs(5));
        assert_eq!(config.signed_url.ttl_seconds, 3600);
        assert_eq!(
            config.signed_url.service_account_email.as_deref(),
            Some("signer@example.com")
        );
    }

    #[test]
    fn allows_service_account_email_to_be_resolved_by_runtime() {
        let mut values = required_values();
        values.remove("GOOGLE_SERVICE_ACCOUNT_EMAIL");

        let config = AppConfig::from_source(|key| values.get(key).cloned()).unwrap();

        assert_eq!(config.signed_url.service_account_email, None);
    }

    #[test]
    fn rejects_invalid_optional_value() {
        let mut values = required_values();
        values.insert("PORT", "not-a-port".into());
        let result = AppConfig::from_source(|key| values.get(key).cloned());
        assert!(matches!(
            result,
            Err(ConfigError::Invalid {
                key: "PORT",
                value,
            }) if value == "not-a-port"
        ));
    }

    #[test]
    fn rejects_multiple_database_secret_sources() {
        let mut values = required_values();
        values.insert("DATABASE_URL_FILE", "/run/secrets/database-url".into());

        let result = AppConfig::from_source(|key| values.get(key).cloned());

        assert!(matches!(
            result,
            Err(ConfigError::ConflictingSecretSources {
                variable: "DATABASE_URL",
                file_variable: "DATABASE_URL_FILE",
            })
        ));
    }

    #[test]
    fn reads_database_url_from_file() {
        let path =
            std::env::temp_dir().join(format!("pdf-reader-database-url-{}", std::process::id()));
        std::fs::write(&path, "postgres://localhost/from-file\n").unwrap();
        let mut values = required_values();
        values.remove("DATABASE_URL");
        values.insert("DATABASE_URL_FILE", path.to_string_lossy().into_owned());

        let config = AppConfig::from_source(|key| values.get(key).cloned()).unwrap();

        assert_eq!(config.database.url, "postgres://localhost/from-file");
        std::fs::remove_file(path).unwrap();
    }
}
