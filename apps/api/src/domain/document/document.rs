use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct DocumentId(uuid::Uuid);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientId(String);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocumentTitle(String);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct DocumentVersionNumber(i32);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GcsObjectName(String);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContentHash(String);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProgressPositionType {
    HeadingAnchor,
    Line,
    Offset,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProgressPositionValue(String);

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ProgressRatio(f64);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Document {
    id: DocumentId,
    title: DocumentTitle,
    current_version: DocumentVersionNumber,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocumentVersion {
    id: uuid::Uuid,
    document_id: DocumentId,
    version: DocumentVersionNumber,
    gcs_object_name: GcsObjectName,
    content_hash: ContentHash,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReadingProgress {
    client_id: ClientId,
    document_id: DocumentId,
    version: DocumentVersionNumber,
    position_type: ProgressPositionType,
    position_value: ProgressPositionValue,
    progress_ratio: ProgressRatio,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum DocumentError {
    #[error("invalid document id: {0}")]
    InvalidDocumentId(String),
    #[error("client_id must not be empty")]
    EmptyClientId,
    #[error("title must not be empty")]
    EmptyTitle,
    #[error("version must be greater than or equal to 1")]
    InvalidVersion,
    #[error("gcs_object_name must not be empty")]
    EmptyGcsObjectName,
    #[error("content_hash must not be empty")]
    EmptyContentHash,
    #[error("invalid position_type: {0}")]
    InvalidPositionType(String),
    #[error("position_value must not be empty")]
    EmptyPositionValue,
    #[error("progress_ratio must be between 0.0 and 1.0")]
    InvalidProgressRatio,
}

impl DocumentId {
    pub fn new(id: uuid::Uuid) -> Self {
        Self(id)
    }

    pub fn parse(value: &str) -> Result<Self, DocumentError> {
        uuid::Uuid::parse_str(value)
            .map(Self)
            .map_err(|_| DocumentError::InvalidDocumentId(value.to_string()))
    }

    pub fn value(self) -> uuid::Uuid {
        self.0
    }
}

impl ClientId {
    pub fn new(value: impl Into<String>) -> Result<Self, DocumentError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(DocumentError::EmptyClientId);
        }
        Ok(Self(value))
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl DocumentTitle {
    pub fn new(value: impl Into<String>) -> Result<Self, DocumentError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(DocumentError::EmptyTitle);
        }
        Ok(Self(value))
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl DocumentVersionNumber {
    pub fn new(value: i32) -> Result<Self, DocumentError> {
        if value < 1 {
            return Err(DocumentError::InvalidVersion);
        }
        Ok(Self(value))
    }

    pub fn value(self) -> i32 {
        self.0
    }
}

impl GcsObjectName {
    pub fn new(value: impl Into<String>) -> Result<Self, DocumentError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(DocumentError::EmptyGcsObjectName);
        }
        Ok(Self(value))
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl ContentHash {
    pub fn new(value: impl Into<String>) -> Result<Self, DocumentError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(DocumentError::EmptyContentHash);
        }
        Ok(Self(value))
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl ProgressPositionType {
    pub fn parse(value: &str) -> Result<Self, DocumentError> {
        match value {
            "heading_anchor" => Ok(Self::HeadingAnchor),
            "line" => Ok(Self::Line),
            "offset" => Ok(Self::Offset),
            other => Err(DocumentError::InvalidPositionType(other.to_string())),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::HeadingAnchor => "heading_anchor",
            Self::Line => "line",
            Self::Offset => "offset",
        }
    }
}

impl ProgressPositionValue {
    pub fn new(value: impl Into<String>) -> Result<Self, DocumentError> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(DocumentError::EmptyPositionValue);
        }
        Ok(Self(value))
    }

    pub fn value(&self) -> &str {
        &self.0
    }
}

impl ProgressRatio {
    pub fn new(value: f64) -> Result<Self, DocumentError> {
        if !(0.0..=1.0).contains(&value) {
            return Err(DocumentError::InvalidProgressRatio);
        }
        Ok(Self(value))
    }

    pub fn value(self) -> f64 {
        self.0
    }
}

impl Document {
    pub fn new(
        id: DocumentId,
        title: DocumentTitle,
        current_version: DocumentVersionNumber,
    ) -> Self {
        Self {
            id,
            title,
            current_version,
        }
    }

    pub fn id(&self) -> DocumentId {
        self.id
    }

    pub fn title(&self) -> &DocumentTitle {
        &self.title
    }

    pub fn current_version(&self) -> DocumentVersionNumber {
        self.current_version
    }
}

impl DocumentVersion {
    pub fn new(
        id: uuid::Uuid,
        document_id: DocumentId,
        version: DocumentVersionNumber,
        gcs_object_name: GcsObjectName,
        content_hash: ContentHash,
    ) -> Self {
        Self {
            id,
            document_id,
            version,
            gcs_object_name,
            content_hash,
        }
    }

    pub fn id(&self) -> uuid::Uuid {
        self.id
    }

    pub fn document_id(&self) -> DocumentId {
        self.document_id
    }

    pub fn version(&self) -> DocumentVersionNumber {
        self.version
    }

    pub fn gcs_object_name(&self) -> &GcsObjectName {
        &self.gcs_object_name
    }

    pub fn content_hash(&self) -> &ContentHash {
        &self.content_hash
    }
}

impl ReadingProgress {
    pub fn new(
        client_id: ClientId,
        document_id: DocumentId,
        version: DocumentVersionNumber,
        position_type: ProgressPositionType,
        position_value: ProgressPositionValue,
        progress_ratio: ProgressRatio,
    ) -> Self {
        Self {
            client_id,
            document_id,
            version,
            position_type,
            position_value,
            progress_ratio,
        }
    }

    pub fn client_id(&self) -> &ClientId {
        &self.client_id
    }

    pub fn document_id(&self) -> DocumentId {
        self.document_id
    }

    pub fn version(&self) -> DocumentVersionNumber {
        self.version
    }

    pub fn position_type(&self) -> ProgressPositionType {
        self.position_type
    }

    pub fn position_value(&self) -> &ProgressPositionValue {
        &self.position_value
    }

    pub fn progress_ratio(&self) -> ProgressRatio {
        self.progress_ratio
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_empty_title() {
        assert_eq!(DocumentTitle::new("  "), Err(DocumentError::EmptyTitle));
    }

    #[test]
    fn rejects_version_less_than_one() {
        assert_eq!(
            DocumentVersionNumber::new(0),
            Err(DocumentError::InvalidVersion)
        );
    }

    #[test]
    fn rejects_empty_gcs_object_name() {
        assert_eq!(
            GcsObjectName::new(""),
            Err(DocumentError::EmptyGcsObjectName)
        );
    }

    #[test]
    fn rejects_empty_content_hash() {
        assert_eq!(ContentHash::new(""), Err(DocumentError::EmptyContentHash));
    }

    #[test]
    fn rejects_invalid_position_type() {
        assert_eq!(
            ProgressPositionType::parse("paragraph"),
            Err(DocumentError::InvalidPositionType("paragraph".to_string()))
        );
    }

    #[test]
    fn rejects_progress_ratio_outside_zero_to_one() {
        assert_eq!(
            ProgressRatio::new(1.01),
            Err(DocumentError::InvalidProgressRatio)
        );
        assert_eq!(
            ProgressRatio::new(-0.01),
            Err(DocumentError::InvalidProgressRatio)
        );
    }

    #[test]
    fn creates_valid_reading_progress() {
        let document_id = DocumentId::new(uuid::Uuid::now_v7());
        let progress = ReadingProgress::new(
            ClientId::new("client-1").unwrap(),
            document_id,
            DocumentVersionNumber::new(1).unwrap(),
            ProgressPositionType::parse("heading_anchor").unwrap(),
            ProgressPositionValue::new("chapter-1").unwrap(),
            ProgressRatio::new(0.5).unwrap(),
        );

        assert_eq!(progress.client_id().value(), "client-1");
        assert_eq!(progress.document_id(), document_id);
        assert_eq!(progress.position_type().as_str(), "heading_anchor");
        assert_eq!(progress.progress_ratio().value(), 0.5);
    }
}
