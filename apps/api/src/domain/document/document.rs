use thiserror::Error;

pub struct Document {
    pub id: String,
    pub title: String,
}

#[derive(Debug, Error)]
pub enum DocumentError {
    #[error("invalid id: {0}")]
    InvalidID(String),
}
