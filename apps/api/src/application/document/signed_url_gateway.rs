use crate::{
    application::transaction::TransactionFuture, domain::document::document::GcsObjectName,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedUrl {
    pub url: String,
    pub expires_at: String,
}

pub trait SignedUrlGateway: Send + Sync {
    type Error: Send;

    fn generate_read_url<'a>(
        &'a self,
        object_name: &'a GcsObjectName,
    ) -> TransactionFuture<'a, Result<SignedUrl, Self::Error>>;
}
