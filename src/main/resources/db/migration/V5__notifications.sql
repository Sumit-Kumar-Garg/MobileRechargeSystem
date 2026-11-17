CREATE TABLE device_tokens (
  id           UUID PRIMARY KEY,
  user_id      UUID        NOT NULL,
  platform     VARCHAR(10) NOT NULL,
  token_hash   VARCHAR(64) NOT NULL,
  token_enc    BYTEA       NOT NULL,
  active       BOOLEAN     NOT NULL DEFAULT TRUE,
  last_seen_at TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_device_user_token UNIQUE (user_id, token_hash)
);
CREATE INDEX idx_device_user_active ON device_tokens (user_id, active);

CREATE TABLE notification_preferences (
  user_id       UUID PRIMARY KEY,
  push_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
  sms_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
  email_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
  locale        VARCHAR(8)  NOT NULL DEFAULT 'de-DE',
  email_enc     BYTEA,
  updated_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE notifications (
  id           UUID PRIMARY KEY,
  order_id     UUID        NOT NULL,
  user_id      UUID        NOT NULL,
  event_type   VARCHAR(40) NOT NULL,
  channel      VARCHAR(10) NOT NULL,
  status       VARCHAR(20) NOT NULL,
  attempts     INT         NOT NULL DEFAULT 0,
  provider_ref VARCHAR(80),
  failure_code VARCHAR(60),
  created_at   TIMESTAMPTZ NOT NULL,
  sent_at      TIMESTAMPTZ,
  CONSTRAINT uq_notification_once UNIQUE (order_id, event_type, channel)   -- idempotency guard
);
