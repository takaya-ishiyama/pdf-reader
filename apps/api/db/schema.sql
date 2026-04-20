CREATE TABLE users (
  id integer PRIMARY KEY AUTOINCREMENT,
  email VARCHAR(255) NOT NULL UNIQUE,
  created_at text DEFAULT (datetime('now'))
);
