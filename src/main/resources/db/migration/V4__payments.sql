CREATE TABLE payments (
  id           UUID PRIMARY KEY,
  order_id     UUID        NOT NULL REFERENCES orders (id),
  attempt_no   INT         NOT NULL,
  psp_ref      VARCHAR(80) UNIQUE,
  status       VARCHAR(20) NOT NULL,
  amount_minor INT         NOT NULL,
  currency     VARCHAR(3)  NOT NULL,
  failure_code VARCHAR(60),
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_payments_order_attempt UNIQUE (order_id, attempt_no)
);
CREATE INDEX idx_payments_status_created ON payments (status, created_at);

CREATE TABLE refunds (
  id           UUID PRIMARY KEY,
  order_id     UUID        NOT NULL UNIQUE REFERENCES orders (id),   -- at most one refund per order
  payment_id   UUID        NOT NULL REFERENCES payments (id),
  psp_ref      VARCHAR(80),
  status       VARCHAR(20) NOT NULL,
  amount_minor INT         NOT NULL,
  currency     VARCHAR(3)  NOT NULL,
  reason       VARCHAR(100),
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL
);
