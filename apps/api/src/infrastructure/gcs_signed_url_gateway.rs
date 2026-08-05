use std::{
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use base64::{Engine as _, engine::general_purpose};
use gcp_auth::TokenProvider;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{
    application::{
        document::signed_url_gateway::{SignedUrl, SignedUrlGateway},
        transaction::TransactionFuture,
    },
    domain::document::document::GcsObjectName,
};

#[derive(Clone)]
pub struct ConfiguredSignedUrlGateway {
    bucket: String,
    ttl_seconds: u64,
    service_account_email: String,
    signer: BlobSigner,
}

#[derive(Clone)]
pub struct SignedUrlConfig {
    pub bucket: String,
    pub ttl_seconds: u64,
    pub service_account_email: Option<String>,
}

#[derive(Clone)]
enum BlobSigner {
    Iam {
        token_provider: Arc<dyn TokenProvider>,
        client: Client,
    },
    Fixed(String),
}

#[derive(Debug, Error)]
pub enum SignedUrlInitializationError {
    #[error("failed to initialize Google application default credentials: {0}")]
    Authentication(#[from] gcp_auth::Error),
    #[error("failed to resolve Google service account email from metadata server: {0}")]
    Metadata(#[from] reqwest::Error),
}

#[derive(Debug, Error)]
pub enum SignedUrlError {
    #[error("failed to obtain Google access token: {0}")]
    Authentication(#[from] gcp_auth::Error),
    #[error("IAM signBlob request failed: {0}")]
    IamRequest(#[from] reqwest::Error),
    #[error("IAM signBlob returned an invalid signature: {0}")]
    InvalidSignature(#[from] base64::DecodeError),
}

impl ConfiguredSignedUrlGateway {
    pub async fn from_config(
        config: SignedUrlConfig,
    ) -> Result<Self, SignedUrlInitializationError> {
        let token_provider = gcp_auth::provider().await?;
        let service_account_email = match config.service_account_email {
            Some(email) => email,
            None => metadata_service_account_email().await?,
        };
        Ok(Self {
            bucket: config.bucket,
            ttl_seconds: config.ttl_seconds,
            service_account_email,
            signer: BlobSigner::Iam {
                token_provider,
                client: Client::new(),
            },
        })
    }

    pub fn new_with_fixed_signature(
        bucket: String,
        ttl_seconds: u64,
        service_account_email: String,
        signature: String,
    ) -> Self {
        Self {
            bucket,
            ttl_seconds,
            service_account_email,
            signer: BlobSigner::Fixed(signature),
        }
    }

    async fn generate_read_url_at(
        &self,
        object_name: &GcsObjectName,
        now: SystemTime,
    ) -> Result<SignedUrl, SignedUrlError> {
        let timestamp = GcsTimestamp::from_system_time(now);
        let credential_scope = format!("{}/auto/storage/goog4_request", timestamp.date);
        let credential = format!("{}/{}", self.service_account_email, credential_scope);
        let canonical_uri = canonical_uri(&self.bucket, object_name);
        let expires = self.ttl_seconds.to_string();
        let signed_headers = "host";
        let host = "storage.googleapis.com";

        let mut query = [
            ("X-Goog-Algorithm", "GOOG4-RSA-SHA256".to_string()),
            ("X-Goog-Credential", credential),
            ("X-Goog-Date", timestamp.full.clone()),
            ("X-Goog-Expires", expires),
            ("X-Goog-SignedHeaders", signed_headers.to_string()),
        ];
        query.sort_by(|a, b| a.0.cmp(b.0));
        let canonical_query = query
            .iter()
            .map(|(key, value)| format!("{key}={}", uri_encode_query(value)))
            .collect::<Vec<_>>()
            .join("&");
        let canonical_request = format!(
            "GET\n{canonical_uri}\n{canonical_query}\nhost:{host}\n\n{signed_headers}\nUNSIGNED-PAYLOAD"
        );
        let canonical_request_hash = hex::encode(Sha256::digest(canonical_request.as_bytes()));
        let string_to_sign = format!(
            "GOOG4-RSA-SHA256\n{}\n{}\n{}",
            timestamp.full, credential_scope, canonical_request_hash
        );
        let signature = self.sign(string_to_sign.as_bytes()).await?;
        let signed_query = format!("{canonical_query}&X-Goog-Signature={signature}");
        let expires_at = timestamp
            .plus_seconds(self.ttl_seconds)
            .to_iso8601_seconds_string();

        Ok(SignedUrl {
            url: format!("https://{host}{canonical_uri}?{signed_query}"),
            expires_at,
        })
    }

    async fn sign(&self, message: &[u8]) -> Result<String, SignedUrlError> {
        let (token_provider, client) = match &self.signer {
            BlobSigner::Iam {
                token_provider,
                client,
            } => (token_provider, client),
            BlobSigner::Fixed(signature) => return Ok(signature.clone()),
        };

        const SCOPES: &[&str] = &["https://www.googleapis.com/auth/cloud-platform"];
        let token = token_provider.token(SCOPES).await?;
        let endpoint = format!(
            "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/{}:signBlob",
            self.service_account_email
        );
        let response = client
            .post(endpoint)
            .bearer_auth(token.as_str())
            .json(&SignBlobRequest {
                payload: general_purpose::STANDARD.encode(message),
            })
            .send()
            .await?
            .error_for_status()?
            .json::<SignBlobResponse>()
            .await?;
        let signature = general_purpose::STANDARD.decode(response.signed_blob)?;

        Ok(hex::encode(signature))
    }
}

async fn metadata_service_account_email() -> Result<String, reqwest::Error> {
    Client::new()
        .get("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email")
        .header("Metadata-Flavor", "Google")
        .send()
        .await?
        .error_for_status()?
        .text()
        .await
}

#[derive(Serialize)]
struct SignBlobRequest {
    payload: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SignBlobResponse {
    signed_blob: String,
}

impl SignedUrlGateway for ConfiguredSignedUrlGateway {
    type Error = SignedUrlError;

    fn generate_read_url<'a>(
        &'a self,
        object_name: &'a GcsObjectName,
    ) -> TransactionFuture<'a, Result<SignedUrl, Self::Error>> {
        Box::pin(async move {
            self.generate_read_url_at(object_name, SystemTime::now())
                .await
        })
    }
}

fn canonical_uri(bucket: &str, object_name: &GcsObjectName) -> String {
    format!(
        "/{}/{}",
        uri_encode_query(bucket),
        uri_encode_path(object_name.value())
    )
}

fn uri_encode_path(value: &str) -> String {
    value
        .split('/')
        .map(uri_encode_query)
        .collect::<Vec<_>>()
        .join("/")
}

fn uri_encode_query(value: &str) -> String {
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                encoded.push(byte as char);
            }
            other => encoded.push_str(&format!("%{other:02X}")),
        }
    }
    encoded
}

#[derive(Debug, Clone)]
struct GcsTimestamp {
    date: String,
    full: String,
    unix_seconds: u64,
}

impl GcsTimestamp {
    fn from_system_time(value: SystemTime) -> Self {
        let unix_seconds = value
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_secs();
        Self::from_unix_seconds(unix_seconds)
    }

    fn from_unix_seconds(unix_seconds: u64) -> Self {
        let days = (unix_seconds / 86_400) as i64;
        let seconds_of_day = unix_seconds % 86_400;
        let (year, month, day) = civil_from_days(days);
        let hour = seconds_of_day / 3600;
        let minute = (seconds_of_day % 3600) / 60;
        let second = seconds_of_day % 60;
        let date = format!("{year:04}{month:02}{day:02}");
        let full = format!("{date}T{hour:02}{minute:02}{second:02}Z");

        Self {
            date,
            full,
            unix_seconds,
        }
    }

    fn plus_seconds(&self, seconds: u64) -> Self {
        Self::from_unix_seconds(self.unix_seconds + seconds)
    }

    fn to_iso8601_seconds_string(&self) -> String {
        format!(
            "{}-{}-{}T{}:{}:{}Z",
            &self.full[0..4],
            &self.full[4..6],
            &self.full[6..8],
            &self.full[9..11],
            &self.full[11..13],
            &self.full[13..15]
        )
    }
}

fn civil_from_days(days_since_unix_epoch: i64) -> (i64, u64, u64) {
    let z = days_since_unix_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    let year = y + if month <= 2 { 1 } else { 0 };
    (year, month as u64, day as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_gcs_timestamp_from_unix_seconds() {
        let timestamp = GcsTimestamp::from_unix_seconds(1_783_708_496);

        assert_eq!(timestamp.date, "20260710");
        assert_eq!(timestamp.full, "20260710T183456Z");
        assert_eq!(
            timestamp.to_iso8601_seconds_string(),
            "2026-07-10T18:34:56Z"
        );
    }

    #[test]
    fn encodes_path_but_preserves_slashes() {
        assert_eq!(
            uri_encode_path("documents/sample file/v1/content.md"),
            "documents/sample%20file/v1/content.md"
        );
    }

    #[test]
    fn canonical_uri_includes_bucket_for_path_style_gcs_url() {
        let object_name = GcsObjectName::new("documents/sample file/v1/content.md").unwrap();

        assert_eq!(
            canonical_uri("markdown bucket", &object_name),
            "/markdown%20bucket/documents/sample%20file/v1/content.md"
        );
    }
}
