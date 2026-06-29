# Markdown Reader 設計書

## 1. 目的

AndroidアプリでMarkdown文書を閲覧し、読書位置をAPI経由で保存できるようにする。Markdown本文はGCSに配置し、APIはDBに保持したメタデータと読書位置をもとに、Androidアプリへ文書情報とGCS署名付きURLを返す。

現段階では認証は実装しない。将来的な認証追加に備え、DBとAPIの境界ではユーザーを識別できる列・パラメータを追加しやすい構造にするが、現在のAPI仕様には認証前提を含めない。

## 2. 前提技術

- API: Rust / Axum
- SQLライブラリ: sqlx
- DB: PostgreSQL / Neon
- DBマイグレーション: Atlas
- API実行基盤: Cloud Run
- Markdownストレージ: Google Cloud Storage
- Androidアプリ: Kotlin
- 認証: なし

## 3. 全体構成

```text
Android App
  | 1. 文書一覧/詳細取得
  v
Cloud Run API
  | 2. 文書メタデータ、読書位置を取得/更新
  v
Neon PostgreSQL

Android App
  | 3. APIから受け取った署名付きURLでMarkdown取得
  v
Google Cloud Storage
```

APIはMarkdown本文を中継しない。APIはGCSオブジェクト名をDBから取得し、短命の署名付きURLを生成して返す。Androidアプリは署名付きURLでGCSから直接Markdownを取得する。

## 4. ドメイン設計

### 4.1 Document

Markdown文書のメタデータを表す。

- `document_id`: 文書ID
- `title`: 表示名
- `gcs_object_name`: GCS上のオブジェクト名
- `current_version`: 最新バージョン番号
- `content_hash`: Markdown本文のハッシュ。キャッシュ判定に使用
- `created_at`: 作成日時
- `updated_at`: 更新日時

### 4.2 Document Version

Markdownは更新頻度が低いが、更新時に旧キャッシュと区別できるようバージョンを持つ。

- `document_version_id`: バージョンID
- `document_id`: 文書ID
- `version`: バージョン番号。文書ごとに単調増加
- `gcs_object_name`: このバージョンのGCSオブジェクト名
- `content_hash`: このバージョンの本文ハッシュ
- `published_at`: 公開日時

GCSの配置は以下を推奨する。

```text
documents/{document_id}/v{version}/content.md
```

バージョンごとにGCSオブジェクト名を変えることで、古いキャッシュと新しい本文が混ざる問題を避ける。

### 4.3 Reading Progress

最後に読んだ位置を表す。

- `document_id`: 文書ID
- `version`: 読書位置が紐づく文書バージョン
- `position_type`: 位置の表現方法
- `position_value`: 位置の値
- `progress_ratio`: 0.0から1.0の読了率
- `updated_at`: 更新日時

現段階ではユーザー認証がないため、1端末または1アプリインストールを識別する `client_id` をAndroid側で生成し、APIに送る設計にする。認証追加後は `client_id` を `user_id` に置き換える、または併用する。

## 5. DB設計

PostgreSQLのスキーマは `public` ではなく `pdf_reader` を使う。

### 5.1 ロール

Neon上に以下のロールを作成する。

- `pdf_reader_owner`: マイグレーション用。DDL実行権限を持つ
- `pdf_reader_app`: Cloud Run API用。必要なDML権限のみ持つ

権限方針:

- `public` スキーマへの依存を避ける
- API接続ユーザーに `CREATE` やスキーマ変更権限を与えない
- Atlasは `pdf_reader_owner` で実行する
- APIは `pdf_reader_app` で接続する

### 5.2 DDL案

```sql
CREATE SCHEMA IF NOT EXISTS pdf_reader;

CREATE TABLE pdf_reader.documents (
  document_id uuid PRIMARY KEY,
  title text NOT NULL,
  current_version integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pdf_reader.document_versions (
  document_version_id uuid PRIMARY KEY,
  document_id uuid NOT NULL REFERENCES pdf_reader.documents(document_id),
  version integer NOT NULL,
  gcs_object_name text NOT NULL,
  content_hash text NOT NULL,
  published_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, version)
);

CREATE TABLE pdf_reader.reading_progress (
  client_id text NOT NULL,
  document_id uuid NOT NULL REFERENCES pdf_reader.documents(document_id),
  version integer NOT NULL,
  position_type text NOT NULL,
  position_value text NOT NULL,
  progress_ratio numeric(5,4) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (client_id, document_id)
);

CREATE INDEX reading_progress_document_id_idx
  ON pdf_reader.reading_progress(document_id);
```

`position_type` は最初は `heading_anchor` または `line` を想定する。Markdownの差分更新に強くするため、Android側で見出しアンカーを計算できる場合は `heading_anchor` を優先する。単純実装から始める場合は `line` でもよい。

## 6. Atlas設計

Atlasは `apps/api/db` 配下で管理する。

- `schema.sql`: 期待するDBスキーマ
- `atlas.hcl`: Neon接続先、開発DB、差分生成設定
- マイグレーションファイル: `apps/api/db/migrations`

運用方針:

- PRでは `schema.sql` と生成されたmigrationを両方レビューする
- Cloud Runの起動時にマイグレーションは実行しない
- デプロイ前のCIまたは手動手順でAtlas migrationを適用する

## 7. API設計

Base pathは `/v1` とする。

### 7.1 文書一覧取得

```http
GET /v1/documents?client_id={client_id}
```

レスポンス:

```json
{
  "documents": [
    {
      "document_id": "018f0000-0000-7000-8000-000000000001",
      "title": "Sample",
      "version": 3,
      "progress_ratio": 0.42,
      "updated_at": "2026-06-29T00:00:00Z"
    }
  ]
}
```

### 7.2 文書詳細取得

```http
GET /v1/documents/{document_id}?client_id={client_id}
```

レスポンス:

```json
{
  "document_id": "018f0000-0000-7000-8000-000000000001",
  "title": "Sample",
  "version": 3,
  "content_hash": "sha256:...",
  "signed_url": "https://storage.googleapis.com/...",
  "signed_url_expires_at": "2026-06-29T01:00:00Z",
  "cache_control": {
    "max_age_seconds": 86400,
    "stale_while_revalidate_seconds": 604800
  },
  "reading_progress": {
    "position_type": "heading_anchor",
    "position_value": "chapter-2",
    "progress_ratio": 0.42,
    "updated_at": "2026-06-29T00:00:00Z"
  }
}
```

### 7.3 読書位置更新

```http
PUT /v1/documents/{document_id}/progress
Content-Type: application/json
```

リクエスト:

```json
{
  "client_id": "android-installation-id",
  "version": 3,
  "position_type": "heading_anchor",
  "position_value": "chapter-2",
  "progress_ratio": 0.42
}
```

レスポンス:

```json
{
  "document_id": "018f0000-0000-7000-8000-000000000001",
  "version": 3,
  "updated_at": "2026-06-29T00:00:00Z"
}
```

DB更新はUPSERTにする。

```sql
INSERT INTO pdf_reader.reading_progress (...)
VALUES (...)
ON CONFLICT (client_id, document_id)
DO UPDATE SET
  version = EXCLUDED.version,
  position_type = EXCLUDED.position_type,
  position_value = EXCLUDED.position_value,
  progress_ratio = EXCLUDED.progress_ratio,
  updated_at = now();
```

## 8. 読書位置同期方式

### 8.1 RESTを推奨

現段階ではREST APIを推奨する。読書位置はリアルタイム双方向通信を必要とせず、更新頻度も高くないため、WebSocketより運用と実装が単純になる。

WebSocketが必要になるのは、複数端末で同時閲覧しながら位置を即時同期したい場合や、サーバーから閲覧状態をプッシュしたい場合である。現在の要求では不要。

### 8.2 送信タイミング

Androidアプリは以下のタイミングで読書位置を送信する。

- 画面を閉じるとき
- アプリがバックグラウンドに移るとき
- 最後の送信から30秒以上経過し、かつ読書位置が変わったとき
- 見出しをまたいだとき

スクロールのたびに送信しない。ローカル状態を更新し、debounceしてAPIに送る。

推奨値:

- debounce: 3秒
- periodic sync: 30秒
- retry: 指数バックオフ
- オフライン時: RoomまたはDataStoreに未送信イベントを保存し、次回オンライン時に送信

### 8.3 衝突解決

認証なしで `client_id` 単位の進捗を持つため、同一 `client_id` では `updated_at` が新しいものを正とする。将来ユーザー認証を追加し複数端末同期を行う場合は、端末ごとの進捗を持ったうえでサーバー側で最新更新を採用する。

## 9. キャッシュ設計

### 9.1 GCS

Markdownはバージョンごとにオブジェクト名を変える。GCSオブジェクトには以下のCache-Controlを設定する。

```text
Cache-Control: public, max-age=86400, stale-while-revalidate=604800
```

更新頻度が低く、バージョン付きURL/オブジェクト名を使うため、長めのキャッシュが可能。

### 9.2 署名付きURL

署名付きURLの有効期限は1時間を基本とする。AndroidアプリはMarkdown本文をローカルキャッシュしておき、URL期限切れ時はAPIから文書詳細を再取得する。

### 9.3 Androidローカルキャッシュ

Android側では `document_id + version + content_hash` をキーにMarkdown本文を保存する。

- 本文キャッシュ: ファイルまたはRoom
- メタデータ: Room
- 軽量な設定値: DataStore

アプリ起動時はローカルキャッシュを即時表示し、バックグラウンドでAPIから最新versionを確認する。versionまたはcontent_hashが変わっていれば新しいMarkdownを取得する。

## 10. Android設計

### 10.1 主要コンポーネント

- `DocumentRepository`: APIとローカルキャッシュを抽象化
- `DocumentApiClient`: RetrofitまたはKtor ClientでAPI呼び出し
- `MarkdownStorage`: Markdown本文のローカル保存
- `ProgressSyncManager`: 読書位置のdebounce、retry、offline queueを担当
- `MarkdownViewerViewModel`: 表示状態と読書位置を管理
- `TextToSpeechController`: 読み上げを管理

### 10.2 表示フロー

1. 文書一覧をAPIから取得する
2. 文書詳細APIで署名付きURLと進捗を取得する
3. ローカルキャッシュに同じ `document_id + version + content_hash` があればそれを表示する
4. キャッシュがなければ署名付きURLからGCSのMarkdownを取得する
5. Markdownをパースして表示する
6. 保存済み読書位置にスクロールする
7. 読書位置変更を `ProgressSyncManager` に渡す

### 10.3 Markdownパーサー

Androidでは以下のどちらかを使う。

- Jetpack Compose中心なら、Markdown ASTを作ってCompose UIに変換する
- 既存ライブラリを使うなら Markwon などを採用する

読書位置を安定させるため、見出しからアンカーを生成する。見出しがない文書では行番号または文字オフセットを使う。

## 11. 読み上げ機能

Android標準の `TextToSpeech` を使う。

### 11.1 基本機能

- 再生
- 一時停止
- 停止
- 読み上げ速度変更
- 読み上げ位置の表示
- 画面の読書位置と読み上げ位置の連動

### 11.2 Markdownから読み上げテキストへの変換

Markdown本文をそのまま読み上げると記号が多くなるため、読み上げ用テキストへ変換する。

- 見出し記号 `#` は除去
- リンクは表示テキストのみ読み上げる
- コードブロックは初期設定ではスキップ
- 表は簡易テキスト化する
- 画像はaltテキストがあれば読み上げる

読み上げ位置は段落単位で管理する。APIへ保存する読書位置は、通常スクロールと同じ `position_type` / `position_value` を使う。

## 12. Cloud Run設計

### 12.1 環境変数

- `DATABASE_URL`: Neon接続URL
- `DATABASE_MAX_CONNECTIONS`: sqlx pool上限
- `DATABASE_MIN_CONNECTIONS`: sqlx pool下限
- `GCS_BUCKET`: Markdown配置先bucket
- `SIGNED_URL_TTL_SECONDS`: 署名付きURL有効期限
- `GOOGLE_CLOUD_PROJECT`: GCP project id

### 12.2 サービスアカウント

Cloud Runのサービスアカウントには対象GCS bucketの読み取り権限と署名付きURL生成に必要な権限のみ付与する。

推奨権限:

- `roles/storage.objectViewer`
- 署名方式に応じたサービスアカウント署名権限

## 13. エラー設計

APIはJSONでエラーを返す。

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "document not found"
  }
}
```

主なエラー:

- `DOCUMENT_NOT_FOUND`: 文書が存在しない
- `DOCUMENT_VERSION_CONFLICT`: クライアントが古いversionの進捗を送信した
- `INVALID_PROGRESS`: `progress_ratio` や `position_type` が不正
- `SIGNED_URL_FAILED`: 署名付きURL生成に失敗
- `DATABASE_ERROR`: DBエラー

## 14. 実装順序

1. DBスキーマを `pdf_reader` スキーマ前提に修正する
2. Atlas migrationを作成する
3. Rustドメインモデルを `Document` / `DocumentVersion` / `ReadingProgress` に分ける
4. sqlx repositoryを実装する
5. 文書一覧APIを実装する
6. 文書詳細APIとGCS署名付きURL生成を実装する
7. 読書位置更新APIを実装する
8. Android側のAPI clientとローカルキャッシュを実装する
9. Markdown表示と読書位置復元を実装する
10. `ProgressSyncManager` を実装する
11. 読み上げ機能を実装する
12. Cloud Run / Neon / GCSの本番設定を整備する

## 15. 今後の拡張

- 認証追加時は `client_id` を `user_id` に移行する
- 複数端末同期を行う場合は、端末別進捗とユーザー代表進捗を分ける
- Markdownアップロード機能を追加する場合は、管理者APIとGCSアップロード用署名付きURLを別途設計する
- 文書検索が必要になった場合はPostgreSQL全文検索または外部検索基盤を検討する
