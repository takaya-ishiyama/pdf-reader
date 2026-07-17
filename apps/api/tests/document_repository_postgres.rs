use api::{
    application::document::repository::{DocumentRepository, UpsertReadingProgressError},
    domain::document::document::{
        ClientId, DocumentId, DocumentVersionNumber, ProgressPositionType, ProgressPositionValue,
        ProgressRatio, ReadingProgress,
    },
    infrastructure::document_repository::SqlxDocumentRepository,
};
use tokio::sync::OnceCell;

static SCHEMA_INITIALIZED: OnceCell<()> = OnceCell::const_new();

#[tokio::test]
async fn sqlx_document_repository_uses_pdf_reader_schema_and_upserts_progress() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let repository = SqlxDocumentRepository::new(pool.clone());

    let document_id = uuid::Uuid::now_v7();
    let version_id = uuid::Uuid::now_v7();
    sqlx::query(
        r#"
        INSERT INTO pdf_reader.documents (document_id, title, current_version)
        VALUES ($1, 'Sample', 1)
        "#,
    )
    .bind(document_id)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        r#"
        INSERT INTO pdf_reader.document_versions (
            document_version_id,
            document_id,
            version,
            gcs_object_name,
            content_hash
        )
        VALUES ($1, $2, 1, 'documents/sample/v1/content.md', 'sha256:test')
        "#,
    )
    .bind(version_id)
    .bind(document_id)
    .execute(&pool)
    .await
    .unwrap();

    let client_id = ClientId::new(format!("client-{}", uuid::Uuid::now_v7())).unwrap();
    let detail = repository
        .find_detail(DocumentId::new(document_id), &client_id)
        .await
        .unwrap()
        .unwrap();

    assert_eq!(detail.title, "Sample");
    assert_eq!(
        detail.gcs_object_name.value(),
        "documents/sample/v1/content.md"
    );
    assert_eq!(detail.content_hash.value(), "sha256:test");
    assert!(detail.reading_progress.is_none());

    let progress = ReadingProgress::new(
        client_id.clone(),
        DocumentId::new(document_id),
        DocumentVersionNumber::new(1).unwrap(),
        ProgressPositionType::HeadingAnchor,
        ProgressPositionValue::new("chapter-1").unwrap(),
        ProgressRatio::new(0.25).unwrap(),
    );
    let first_update = repository.upsert_progress(&progress).await.unwrap();
    assert!(!first_update.updated_at.is_empty());

    let updated_progress = ReadingProgress::new(
        client_id.clone(),
        DocumentId::new(document_id),
        DocumentVersionNumber::new(1).unwrap(),
        ProgressPositionType::HeadingAnchor,
        ProgressPositionValue::new("chapter-2").unwrap(),
        ProgressRatio::new(0.75).unwrap(),
    );
    let second_update = repository.upsert_progress(&updated_progress).await.unwrap();
    assert!(!second_update.updated_at.is_empty());

    let detail = repository
        .find_detail(DocumentId::new(document_id), &client_id)
        .await
        .unwrap()
        .unwrap();
    let progress = detail.reading_progress.unwrap();

    assert_eq!(progress.position_value, "chapter-2");
    assert_eq!(progress.progress_ratio, 0.75);

    let public_document_exists: bool = sqlx::query_scalar(
        r#"
        SELECT EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = 'public'
            AND table_name = 'documents'
        )
        "#,
    )
    .fetch_one(&pool)
    .await
    .unwrap();

    assert!(!public_document_exists);
}

#[tokio::test]
async fn sqlx_document_repository_maps_unknown_version_to_conflict() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let repository = SqlxDocumentRepository::new(pool.clone());
    let document_id = insert_document_fixture(&pool, "Conflict Sample").await;
    let progress = ReadingProgress::new(
        ClientId::new(format!("client-{}", uuid::Uuid::now_v7())).unwrap(),
        DocumentId::new(document_id),
        DocumentVersionNumber::new(2).unwrap(),
        ProgressPositionType::HeadingAnchor,
        ProgressPositionValue::new("missing-version").unwrap(),
        ProgressRatio::new(0.5).unwrap(),
    );

    let result = repository.upsert_progress(&progress).await;

    assert!(matches!(
        result,
        Err(UpsertReadingProgressError::VersionConflict)
    ));
}

#[tokio::test]
async fn reading_progress_progress_ratio_constraint_rejects_out_of_range_values() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let document_id = insert_document_fixture(&pool, "Constraint Sample").await;

    let result = sqlx::query(
        r#"
        INSERT INTO pdf_reader.reading_progress (
            client_id,
            document_id,
            version,
            position_type,
            position_value,
            progress_ratio
        )
        VALUES ($1, $2, 1, 'heading_anchor', 'chapter-1', 1.5)
        "#,
    )
    .bind(format!("client-{}", uuid::Uuid::now_v7()))
    .bind(document_id)
    .execute(&pool)
    .await;

    let err = result.unwrap_err();
    let code = err.as_database_error().and_then(|db_error| db_error.code());
    assert_eq!(code.as_deref(), Some("23514"));
}

#[tokio::test]
async fn reading_progress_position_type_constraint_rejects_unknown_values() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let document_id = insert_document_fixture(&pool, "Position Type Constraint Sample").await;

    let result = sqlx::query(
        r#"
        INSERT INTO pdf_reader.reading_progress (
            client_id,
            document_id,
            version,
            position_type,
            position_value,
            progress_ratio
        )
        VALUES ($1, $2, 1, 'paragraph', 'chapter-1', 0.5)
        "#,
    )
    .bind(format!("client-{}", uuid::Uuid::now_v7()))
    .bind(document_id)
    .execute(&pool)
    .await;

    let err = result.unwrap_err();
    let code = err.as_database_error().and_then(|db_error| db_error.code());
    assert_eq!(code.as_deref(), Some("23514"));
}

#[tokio::test]
async fn documents_title_constraint_rejects_blank_values() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;

    let result = sqlx::query(
        r#"
        INSERT INTO pdf_reader.documents (document_id, title, current_version)
        VALUES ($1, '   ', 1)
        "#,
    )
    .bind(uuid::Uuid::now_v7())
    .execute(&pool)
    .await;

    let err = result.unwrap_err();
    let code = err.as_database_error().and_then(|db_error| db_error.code());
    assert_eq!(code.as_deref(), Some("23514"));
}

async fn insert_document_fixture(pool: &sqlx::PgPool, title: &str) -> uuid::Uuid {
    let document_id = uuid::Uuid::now_v7();
    let version_id = uuid::Uuid::now_v7();
    sqlx::query(
        r#"
        INSERT INTO pdf_reader.documents (document_id, title, current_version)
        VALUES ($1, $2, 1)
        "#,
    )
    .bind(document_id)
    .bind(title)
    .execute(pool)
    .await
    .unwrap();
    sqlx::query(
        r#"
        INSERT INTO pdf_reader.document_versions (
            document_version_id,
            document_id,
            version,
            gcs_object_name,
            content_hash
        )
        VALUES ($1, $2, 1, $3, 'sha256:test')
        "#,
    )
    .bind(version_id)
    .bind(document_id)
    .bind(format!("documents/{document_id}/v1/content.md"))
    .execute(pool)
    .await
    .unwrap();

    document_id
}

async fn apply_schema(pool: &sqlx::PgPool) {
    SCHEMA_INITIALIZED
        .get_or_init(|| async {
            for statement in include_str!("../db/schema.sql").split(';') {
                let statement = statement.trim();
                if statement.is_empty() {
                    continue;
                }
                sqlx::query(statement).execute(pool).await.unwrap();
            }
        })
        .await;
}
