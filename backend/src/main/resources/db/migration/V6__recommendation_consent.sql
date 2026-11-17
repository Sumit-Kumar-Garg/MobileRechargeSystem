CREATE TABLE consents (
  id         UUID PRIMARY KEY,
  user_id    UUID        NOT NULL,
  purpose    VARCHAR(30) NOT NULL,
  granted    BOOLEAN     NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_consent_user_purpose UNIQUE (user_id, purpose)
);

CREATE TABLE usage_summary (
  id            UUID PRIMARY KEY,
  number_id     UUID NOT NULL REFERENCES subscriber_numbers (id),
  month         DATE NOT NULL,
  data_mb_used  INT  NOT NULL DEFAULT 0,
  voice_min_used INT NOT NULL DEFAULT 0,
  sms_used      INT  NOT NULL DEFAULT 0,
  CONSTRAINT uq_usage_number_month UNIQUE (number_id, month)
);

CREATE TABLE recommendation_log (
  id            UUID PRIMARY KEY,
  user_id       UUID        NOT NULL,
  number_id     UUID        NOT NULL,
  plan_id       UUID        NOT NULL,
  reason        VARCHAR(30) NOT NULL,
  model_version VARCHAR(30) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_reco_user_created ON recommendation_log (user_id, created_at DESC);
