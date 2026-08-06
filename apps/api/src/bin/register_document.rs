use std::{env, fs, path::PathBuf, process::ExitCode};

use sha2::{Digest, Sha256};
use sqlx::{PgPool, postgres::PgPoolOptions};
use uuid::Uuid;

struct Args {
    database: DatabaseSource,
    title: String,
    gcs_object_name: String,
    markdown_file: PathBuf,
    version: i32,
    document_id: Uuid,
    document_version_id: Uuid,
}

#[derive(Debug)]
enum DatabaseSource {
    Url(String),
    File(String),
}

/**
cargo run --bin register_document -- \
       --database-url-file .secrets/local-database-url \
       --title 'データ思考アプリケーションデザイン' \
       --gcs-object-name 'pdf-reader-dev-assets/document-markdown/oreilly-DDIA.md' \
       --markdown-file '../../storage/original-files/oreilly-DDIA.md'

- --database-url-file
    - PostgreSQLの接続URLを書いたファイル
    - ファイル内容例:

      postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require

- --title
    - Androidアプリの一覧に表示する名前

- --gcs-object-name
    - バケット内のオブジェクトパス
    - gs://バケット名/...ではなく、バケット名より後ろだけを指定します

- --markdown-file
    - gcsにあるmdと同じ内容のローカルファイル
    - Android側のキャッシュ識別に使います
**/
#[tokio::main]
async fn main() -> ExitCode {
    let args = match Args::parse(env::args().skip(1)) {
        Ok(args) => args,
        Err(ParseResult::Help) => {
            print_usage();
            return ExitCode::SUCCESS;
        }
        Err(ParseResult::Error(message)) => {
            eprintln!("Error: {message}\n");
            print_usage();
            return ExitCode::from(2);
        }
    };

    match register_document(args).await {
        Ok(registered) => {
            println!("Document registered successfully.");
            println!("document_id={}", registered.document_id);
            println!("document_version_id={}", registered.document_version_id);
            println!("version={}", registered.version);
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("Failed to register document: {error}");
            ExitCode::FAILURE
        }
    }
}

struct RegisteredDocument {
    document_id: Uuid,
    document_version_id: Uuid,
    version: i32,
}

async fn register_document(args: Args) -> Result<RegisteredDocument, Box<dyn std::error::Error>> {
    let database_url = match &args.database {
        DatabaseSource::Url(url) => url.clone(),
        DatabaseSource::File(path) => fs::read_to_string(path)?.trim().to_owned(),
    };

    if database_url.is_empty() {
        return Err("database URL is empty".into());
    }

    let markdown = fs::read(&args.markdown_file)?;
    let content_hash = content_hash(&markdown);

    let pool = PgPoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await?;
    Ok(insert_document(&pool, &args, &content_hash).await?)
}

fn content_hash(content: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(content))
}

async fn insert_document(
    pool: &PgPool,
    args: &Args,
    content_hash: &str,
) -> Result<RegisteredDocument, sqlx::Error> {
    let mut transaction = pool.begin().await?;

    sqlx::query(
        r#"
        INSERT INTO pdf_reader.documents (document_id, title, current_version)
        VALUES ($1, $2, $3)
        "#,
    )
    .bind(args.document_id)
    .bind(&args.title)
    .bind(args.version)
    .execute(&mut *transaction)
    .await?;

    sqlx::query(
        r#"
        INSERT INTO pdf_reader.document_versions (
            document_version_id,
            document_id,
            version,
            gcs_object_name,
            content_hash
        )
        VALUES ($1, $2, $3, $4, $5)
        "#,
    )
    .bind(args.document_version_id)
    .bind(args.document_id)
    .bind(args.version)
    .bind(&args.gcs_object_name)
    .bind(content_hash)
    .execute(&mut *transaction)
    .await?;

    transaction.commit().await?;

    Ok(RegisteredDocument {
        document_id: args.document_id,
        document_version_id: args.document_version_id,
        version: args.version,
    })
}

#[derive(Debug)]
enum ParseResult {
    Help,
    Error(String),
}

impl Args {
    fn parse(arguments: impl Iterator<Item = String>) -> Result<Self, ParseResult> {
        let mut database_url = None;
        let mut database_url_file = None;
        let mut title = None;
        let mut gcs_object_name = None;
        let mut markdown_file = None;
        let mut version = 1;
        let mut document_id = None;
        let mut document_version_id = None;
        let mut arguments = arguments;

        while let Some(argument) = arguments.next() {
            if argument == "-h" || argument == "--help" {
                return Err(ParseResult::Help);
            }

            let value = arguments
                .next()
                .ok_or_else(|| ParseResult::Error(format!("{argument} requires a value")))?;

            match argument.as_str() {
                "--database-url" => database_url = Some(value),
                "--database-url-file" => database_url_file = Some(value),
                "--title" => title = Some(value),
                "--gcs-object-name" => gcs_object_name = Some(value),
                "--markdown-file" => markdown_file = Some(value),
                "--version" => {
                    version = value.parse().map_err(|_| {
                        ParseResult::Error("--version must be a positive integer".into())
                    })?;
                }
                "--document-id" => document_id = Some(parse_uuid("--document-id", &value)?),
                "--document-version-id" => {
                    document_version_id = Some(parse_uuid("--document-version-id", &value)?);
                }
                _ => return Err(ParseResult::Error(format!("unknown argument: {argument}"))),
            }
        }

        let database = match (database_url, database_url_file) {
            (Some(url), None) => DatabaseSource::Url(url),
            (None, Some(file)) => DatabaseSource::File(file),
            (Some(_), Some(_)) => {
                return Err(ParseResult::Error(
                    "use only one of --database-url and --database-url-file".into(),
                ));
            }
            (None, None) => {
                return Err(ParseResult::Error(
                    "--database-url or --database-url-file is required".into(),
                ));
            }
        };

        let title = required("--title", title)?;
        let gcs_object_name = required("--gcs-object-name", gcs_object_name)?;
        let markdown_file = PathBuf::from(required("--markdown-file", markdown_file)?);

        if version < 1 {
            return Err(ParseResult::Error(
                "--version must be a positive integer".into(),
            ));
        }
        if gcs_object_name.starts_with("gs://") || gcs_object_name.starts_with('/') {
            return Err(ParseResult::Error(
                "--gcs-object-name must be a path within the bucket".into(),
            ));
        }

        Ok(Self {
            database,
            title,
            gcs_object_name,
            markdown_file,
            version,
            document_id: document_id.unwrap_or_else(Uuid::now_v7),
            document_version_id: document_version_id.unwrap_or_else(Uuid::now_v7),
        })
    }
}

fn required(name: &str, value: Option<String>) -> Result<String, ParseResult> {
    match value {
        Some(value) if !value.trim().is_empty() => Ok(value),
        _ => Err(ParseResult::Error(format!("{name} is required"))),
    }
}

fn parse_uuid(name: &str, value: &str) -> Result<Uuid, ParseResult> {
    Uuid::parse_str(value).map_err(|_| ParseResult::Error(format!("{name} must be a valid UUID")))
}

fn print_usage() {
    println!(
        r#"Usage:
  cargo run --bin register_document -- \
    (--database-url URL | --database-url-file FILE) \
    --title TITLE \
    --gcs-object-name OBJECT_NAME \
    --markdown-file FILE \
    [--version VERSION] \
    [--document-id UUID] \
    [--document-version-id UUID]

Registers a new document and its first version in PostgreSQL. The content hash
is calculated from FILE using SHA-256.
The GCS object name is a path within the configured bucket, not a gs:// URL."#
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_required_arguments_and_generates_ids() {
        let args = Args::parse(
            [
                "--database-url",
                "postgres://localhost/pdf_reader",
                "--title",
                "Guide",
                "--gcs-object-name",
                "documents/guide/v1/content.md",
                "--markdown-file",
                "guide.md",
            ]
            .into_iter()
            .map(str::to_owned),
        )
        .unwrap();

        assert_eq!(args.title, "Guide");
        assert_eq!(args.version, 1);
        assert_eq!(args.document_id.get_version_num(), 7);
        assert_eq!(args.document_version_id.get_version_num(), 7);
    }

    #[test]
    fn rejects_gs_url_as_object_name() {
        let result = Args::parse(
            [
                "--database-url",
                "postgres://localhost/pdf_reader",
                "--title",
                "Guide",
                "--gcs-object-name",
                "gs://bucket/content.md",
                "--markdown-file",
                "guide.md",
            ]
            .into_iter()
            .map(str::to_owned),
        );

        assert!(matches!(result, Err(ParseResult::Error(_))));
    }

    #[test]
    fn calculates_sha256_content_hash() {
        assert_eq!(
            content_hash(b"abc"),
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
