# Suggested alerts and dashboards

Alerts (Prometheus):
- `max(recharge_orders_stuck) > 0` for 10m: customers paid but did not get their recharge. Page someone.
  Use `max`, not `sum`: every instance reports the same database count, so `sum` multiplies it by the
  number of instances.
- Payment success ratio: `sum(rate(recharge_payments_total{outcome="succeeded"}[10m])) / sum(rate(recharge_payments_total{outcome=~"succeeded|declined"}[10m])) < 0.85`.
- `sum(rate(recharge_payments_total{outcome="unknown"}[10m])) > 0.1`: PSP is timing out.
- `increase(recharge_kafka_dlt_total[10m]) > 0`: events landed on a dead-letter topic.
- p95 order creation: `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri="/v1/orders"}[5m])) by (le)) > 0.3`.
- Kafka consumer lag for groups `notification` and `telemetry` (from the Kafka exporter / broker metrics).
- `increase(recharge_notifications_total{status="failed"}[15m])` per channel.

Dashboards (Grafana, Postgres data source):
- Funnel: `select * from v_journey_funnel`.
- Client vs server latency by network type: `select * from v_network_latency`.
- Latency histogram buckets per endpoint: `select * from telemetry_rollups where bucket_hour > now() - interval '24 hours'`.
