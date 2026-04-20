env "local" {
  url = "file://schema.sql"
  src = getenv("DATABASE_URL")
  dev = "sqlite://dev?mode=memory"
}
