use api::{
    application::transaction::TransactionManager,
    infrastructure::transaction::SqlxTransactionManager,
};

#[tokio::test]
async fn sqlx_transaction_manager_commits_successful_work() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL transaction test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let manager = SqlxTransactionManager::new(pool.clone());
    let document_id = uuid::Uuid::now_v7();

    manager
        .run_in_transaction(|connection| {
            Box::pin(async move {
                sqlx::query(
                    r#"
                    INSERT INTO pdf_reader.documents (document_id, title, current_version)
                    VALUES ($1, 'Committed', 1)
                    "#,
                )
                .bind(document_id)
                .execute(connection)
                .await?;

                Ok(())
            })
        })
        .await
        .unwrap();

    let exists: bool = sqlx::query_scalar(
        r#"
        SELECT EXISTS (
            SELECT 1 FROM pdf_reader.documents WHERE document_id = $1
        )
        "#,
    )
    .bind(document_id)
    .fetch_one(&pool)
    .await
    .unwrap();

    assert!(exists);
}

#[tokio::test]
async fn sqlx_transaction_manager_rolls_back_failed_work() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping PostgreSQL transaction test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;
    let manager = SqlxTransactionManager::new(pool.clone());
    let document_id = uuid::Uuid::now_v7();

    let result: Result<(), sqlx::Error> = manager
        .run_in_transaction(|connection| {
            Box::pin(async move {
                sqlx::query(
                    r#"
                    INSERT INTO pdf_reader.documents (document_id, title, current_version)
                    VALUES ($1, 'Rolled Back', 1)
                    "#,
                )
                .bind(document_id)
                .execute(connection)
                .await?;

                Err(sqlx::Error::RowNotFound)
            })
        })
        .await;

    assert!(matches!(result, Err(sqlx::Error::RowNotFound)));

    let exists: bool = sqlx::query_scalar(
        r#"
        SELECT EXISTS (
            SELECT 1 FROM pdf_reader.documents WHERE document_id = $1
        )
        "#,
    )
    .bind(document_id)
    .fetch_one(&pool)
    .await
    .unwrap();

    assert!(!exists);
}

async fn apply_schema(pool: &sqlx::PgPool) {
    for statement in include_str!("../db/schema.sql").split(';') {
        let statement = statement.trim();
        if statement.is_empty() {
            continue;
        }
        sqlx::query(statement).execute(pool).await.unwrap();
    }
}
