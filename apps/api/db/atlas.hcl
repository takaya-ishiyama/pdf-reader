env "local" {
  src = "file://schema.sql"
  url = getenv("DATABASE_URL")
  dev = "docker://postgres/16/dev"
  migration {
      dir = "file://db/migrations"
  }
}

env "production" {
  src = "file://schema.sql"
  url = getenv("DATABASE_URL")
  dev = "docker://postgres/16/dev"
  migration {
    dir = "file://db/migrations"
  }
}
