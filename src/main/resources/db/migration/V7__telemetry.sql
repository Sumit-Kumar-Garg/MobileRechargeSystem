CREATE TABLE journey_events (
  id             UUID PRIMARY KEY,                 -- client/server event id => ingestion is idempotent
  user_pseudo_id VARCHAR(64) NOT NULL,             -- HMAC of user id, never the raw id
  session_id     VARCHAR(64),
  journey_id     UUID,
  order_id       UUID,
  source         VARCHAR(10) NOT NULL,             -- android | server
  type           VARCHAR(20) NOT NULL,
  name           VARCHAR(40) NOT NULL,
  occurred_at    TIMESTAMPTZ NOT NULL,
  received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  props          TEXT
);
CREATE INDEX idx_journey_name_time ON journey_events (name, occurred_at);
CREATE INDEX idx_journey_journey   ON journey_events (journey_id) WHERE journey_id IS NOT NULL;

CREATE TABLE telemetry_rollups (
  bucket_hour   TIMESTAMPTZ  NOT NULL,
  endpoint      VARCHAR(100) NOT NULL,
  network_type  VARCHAR(12)  NOT NULL,
  app_version   VARCHAR(20)  NOT NULL,
  calls         BIGINT NOT NULL DEFAULT 0,
  errors        BIGINT NOT NULL DEFAULT 0,
  sum_ms        BIGINT NOT NULL DEFAULT 0,
  sum_server_ms BIGINT NOT NULL DEFAULT 0,
  le_100        BIGINT NOT NULL DEFAULT 0,         -- cumulative histogram buckets (ms)
  le_250        BIGINT NOT NULL DEFAULT 0,
  le_500        BIGINT NOT NULL DEFAULT 0,
  le_1000       BIGINT NOT NULL DEFAULT 0,
  le_2500       BIGINT NOT NULL DEFAULT 0,
  le_inf        BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_hour, endpoint, network_type, app_version)
);

CREATE VIEW v_journey_funnel AS
SELECT name AS step, COUNT(DISTINCT journey_id) AS journeys
FROM journey_events
WHERE journey_id IS NOT NULL
  AND occurred_at > now() - interval '30 days'
  AND name IN ('number_entered', 'plans_viewed', 'plan_selected', 'payment_started', 'recharge_completed')
GROUP BY name;

CREATE VIEW v_network_latency AS
SELECT endpoint, network_type,
       SUM(calls) AS calls,
       SUM(errors) AS errors,
       ROUND(SUM(sum_ms)::numeric / NULLIF(SUM(calls), 0), 1) AS avg_client_ms,
       ROUND(SUM(sum_server_ms)::numeric / NULLIF(SUM(calls), 0), 1) AS avg_server_ms
FROM telemetry_rollups
WHERE bucket_hour > now() - interval '24 hours'
GROUP BY endpoint, network_type;
