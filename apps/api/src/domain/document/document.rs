use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct DocumentId(pub(crate) uuid::Uuid);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Document {
    pub(crate) id: DocumentId,
    pub(crate) title: String,
    pub(crate) latest_read_line: i32,
    pub(crate) version: i16,
    pub(crate) document_url: String,
}

#[derive(Debug, Error)]
pub enum DocumentError {
    #[error("invalid id: {0}")]
    InvalidID(String),
}

impl Document {
    pub(crate) fn new(
        id: Option<DocumentId>,
        title: String,
        latest_read_line: i32,
        version: i16,
        document_url: String,
    ) -> Self {
        let id = id.unwrap_or_else(|| DocumentId(uuid::Uuid::now_v7()));
        Self {
            id,
            title,
            latest_read_line,
            version,
            document_url,
        }
    }
}
