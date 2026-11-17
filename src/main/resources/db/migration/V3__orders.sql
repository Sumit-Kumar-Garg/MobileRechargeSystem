CREATE TABLE orders (
  id                    UUID PRIMARY KEY,
  user_id               UUID         NOT NULL,
  number_id             UUID         NOT NULL REFERENCES subscriber_numbers (id),
  plan_id               UUID         NOT NULL REFERENCES plans (id),
  plan_code             VARCHAR(40)  NOT NULL,        -- plan snapshot at order time
  plan_name             VARCHAR(120) NOT NULL,
  amount_minor          INT          NOT NULL CHECK (amount_minor > 0),
  currency              VARCHAR(3)   NOT NULL,
  validity_days         INT          NOT NULL,
  msisdn_masked         VARCHAR(32)  NOT NULL,
  status                VARCHAR(30)  NOT NULL,
  failure_code          VARCHAR(60),
  idempotency_key       VARCHAR(64)  NOT NULL,
  request_fingerprint   VARCHAR(100) NOT NULL,
  journey_id            UUID,
  expires_at            TIMESTAMPTZ  NOT NULL,
  provisioning_attempts INT          NOT NULL DEFAULT 0,
  version               BIGINT       NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ  NOT NULL,
  updated_at            TIMESTAMPTZ  NOT NULL,
  completed_at          TIMESTAMPTZ,
  CONSTRAINT uq_orders_user_idem UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_orders_user_created   ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status_updated ON orders (status, updated_at);
CREATE INDEX idx_orders_status_expires ON orders (status, expires_at);
CREATE INDEX idx_orders_number_status  ON orders (number_id, status, completed_at DESC);

CREATE TABLE order_events (
  id          BIGSERIAL PRIMARY KEY,
  order_id    UUID        NOT NULL REFERENCES orders (id),
  from_status VARCHAR(30),
  to_status   VARCHAR(30) NOT NULL,
  reason      VARCHAR(200),
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_order_events_order ON order_events (order_id, id);

CREATE TABLE outbox_events (
  id           BIGSERIAL PRIMARY KEY,
  topic        VARCHAR(100) NOT NULL,
  event_key    VARCHAR(64)  NOT NULL,
  type         VARCHAR(60)  NOT NULL,
  payload      TEXT         NOT NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ  NOT NULL,
  published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
