use api::{
    build_router, di::AppState, infrastructure::gcs_signed_url_gateway::ConfiguredSignedUrlGateway,
};
use axum::{
    body::{Body, to_bytes},
    http::{Request, StatusCode},
};
use tower::ServiceExt;

#[tokio::test]
async fn documents_http_routes_translate_requests_and_responses() {
    let Some(database_url) = std::env::var("TEST_DATABASE_URL").ok() else {
        eprintln!("skipping HTTP integration test because TEST_DATABASE_URL is not set");
        return;
    };

    let pool = sqlx::PgPool::connect(&database_url).await.unwrap();
    apply_schema(&pool).await;

    let document_id = uuid::Uuid::now_v7();
    let version_id = uuid::Uuid::now_v7();
    sqlx::query(
        r#"
        INSERT INTO pdf_reader.documents (document_id, title, current_version)
        VALUES ($1, 'HTTP Sample', 1)
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
        VALUES ($1, $2, 1, 'documents/http/v1/content.md', 'sha256:http')
        "#,
    )
    .bind(version_id)
    .bind(document_id)
    .execute(&pool)
    .await
    .unwrap();

    let app_state = AppState::new_with_signed_url_gateway(
        pool,
        ConfiguredSignedUrlGateway::new_with_fixed_signature(
            "test-bucket".to_string(),
            3600,
            "test@example.iam.gserviceaccount.com".to_string(),
            "deadbeef".to_string(),
        ),
    );
    let app = build_router(app_state);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_string(response).await;
    assert!(body.contains(r#""status":"ok""#));

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/v1/documents?client_id=http-client")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_string(response).await;
    assert!(body.contains("HTTP Sample"));
    assert!(body.contains(&document_id.to_string()));

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/v1/documents")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = body_string(response).await;
    assert!(body.contains(r#""error":{"code":"BAD_REQUEST""#));

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!("/v1/documents/{document_id}?client_id=http-client"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_string(response).await;
    assert!(body.contains(r#""title":"HTTP Sample""#));
    assert!(body.contains(r#""content_hash":"sha256:http""#));
    assert!(
        body.contains("https://storage.googleapis.com/test-bucket/documents/http/v1/content.md")
    );
    assert!(body.contains("X-Goog-Signature=deadbeef"));
    assert!(body.contains(r#""max_age_seconds":86400"#));
    assert!(body.contains(r#""reading_progress":null"#));

    let missing_document_id = uuid::Uuid::now_v7();
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri(format!(
                    "/v1/documents/{missing_document_id}?client_id=http-client"
                ))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let body = body_string(response).await;
    assert!(body.contains(r#""code":"DOCUMENT_NOT_FOUND""#));

    let progress_body = format!(
        r#"{{
            "client_id": "http-client",
            "version": 1,
            "position_type": "heading_anchor",
            "position_value": "chapter-1",
            "progress_ratio": 0.5
        }}"#
    );
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/v1/documents/{document_id}/progress"))
                .header("content-type", "application/json")
                .body(Body::from(progress_body))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = body_string(response).await;
    assert!(body.contains(&document_id.to_string()));
    assert!(body.contains(r#""version":1"#));
    assert!(body.contains(r#""updated_at":"#));

    let invalid_progress_body = format!(
        r#"{{
            "client_id": "http-client",
            "version": 1,
            "position_type": "heading_anchor",
            "position_value": "chapter-1",
            "progress_ratio": 1.5
        }}"#
    );
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/v1/documents/{document_id}/progress"))
                .header("content-type", "application/json")
                .body(Body::from(invalid_progress_body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = body_string(response).await;
    assert!(body.contains(r#""code":"INVALID_PROGRESS""#));

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/v1/documents/{document_id}/progress"))
                .header("content-type", "application/json")
                .body(Body::from(r#"{"client_id":"http-client""#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = body_string(response).await;
    assert!(body.contains(r#""error":{"code":"BAD_REQUEST""#));

    let conflicting_version_body = format!(
        r#"{{
            "client_id": "http-client",
            "version": 2,
            "position_type": "heading_anchor",
            "position_value": "chapter-1",
            "progress_ratio": 0.5
        }}"#
    );
    let response = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri(format!("/v1/documents/{document_id}/progress"))
                .header("content-type", "application/json")
                .body(Body::from(conflicting_version_body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let body = body_string(response).await;
    assert!(body.contains(r#""code":"DOCUMENT_VERSION_CONFLICT""#));
}

async fn body_string(response: axum::response::Response) -> String {
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    String::from_utf8(bytes.to_vec()).unwrap()
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
