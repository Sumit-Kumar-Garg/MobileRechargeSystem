CREATE TABLE plans (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code          VARCHAR(40)  NOT NULL,
  market        VARCHAR(2)   NOT NULL CHECK (market ~ '^[A-Z]{2}$'),
  name          VARCHAR(120) NOT NULL,
  price_minor   INT          NOT NULL CHECK (price_minor > 0),      -- minor units (cents), never floats
  currency      VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  validity_days INT          NOT NULL CHECK (validity_days > 0),
  data_mb       INT          CHECK (data_mb >= 0),
  voice_min     INT          CHECK (voice_min >= 0),
  sms           INT          CHECK (sms >= 0),
  active        BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uq_plans_market_code UNIQUE (market, code)
);
CREATE INDEX idx_plans_market_active_price ON plans (market, active, price_minor);

CREATE TABLE plan_benefits (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_id    UUID         NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
  type       VARCHAR(30)  NOT NULL,
  label      VARCHAR(160) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_benefits_plan ON plan_benefits (plan_id, sort_order);
