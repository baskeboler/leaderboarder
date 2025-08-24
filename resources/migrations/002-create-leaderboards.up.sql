CREATE TABLE leaderboards (
  id INTEGER AUTO_INCREMENT PRIMARY KEY,
  creator_id INTEGER REFERENCES users(id),
  name VARCHAR,
  filters TEXT,
  min_users INTEGER DEFAULT 5
);
