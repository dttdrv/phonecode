CREATE TABLE reports (
  id TEXT PRIMARY KEY NOT NULL CHECK (
    length(id) = 40
    AND substr(id, 1, 4) = 'PCR-'
    AND substr(id, 5) NOT GLOB '*[^0-9a-f-]*'
    AND substr(id, 13, 1) = '-'
    AND substr(id, 18, 1) = '-'
    AND substr(id, 23, 1) = '-'
    AND substr(id, 28, 1) = '-'
  ),
  protocol_version INTEGER NOT NULL CHECK (protocol_version = 1),
  category TEXT NOT NULL CHECK (
    category IN ('hate', 'harassment', 'sexual', 'violence', 'self_harm', 'illegal', 'privacy', 'other')
  ),
  note TEXT CHECK (note IS NULL OR length(note) <= 1000),
  app_version TEXT NOT NULL CHECK (length(app_version) BETWEEN 1 AND 64),
  platform TEXT NOT NULL CHECK (platform = 'android'),
  created_at INTEGER NOT NULL
) STRICT;

CREATE INDEX reports_created_at ON reports(created_at);

CREATE TABLE report_rate_limits (
  ip_hash TEXT PRIMARY KEY NOT NULL CHECK (
    length(ip_hash) = 64 AND ip_hash NOT GLOB '*[^0-9a-f]*'
  ),
  request_count INTEGER NOT NULL CHECK (request_count >= 1),
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL CHECK (last_seen_at >= first_seen_at)
) STRICT;

CREATE INDEX report_rate_limits_last_seen_at ON report_rate_limits(last_seen_at);
