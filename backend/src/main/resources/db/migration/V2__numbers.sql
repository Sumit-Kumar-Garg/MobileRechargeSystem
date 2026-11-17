CREATE TABLE subscriber_numbers (
  id            UUID PRIMARY KEY,
  user_id       UUID        NOT NULL,
  msisdn_enc    BYTEA       NOT NULL,                 -- AES-GCM encrypted E.164 number
  msisdn_hash   VARCHAR(64) NOT NULL,                 -- HMAC-SHA256 for lookups
  msisdn_masked VARCHAR(32) NOT NULL,                 -- safe to show / log
  market        VARCHAR(2)  NOT NULL,
  status        VARCHAR(20) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_numbers_user_hash UNIQUE (user_id, msisdn_hash)
);
CREATE INDEX idx_numbers_user ON subscriber_numbers (user_id);
