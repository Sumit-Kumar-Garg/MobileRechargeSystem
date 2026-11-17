INSERT INTO plans (code, market, name, price_minor, currency, validity_days, data_mb, voice_min, sms) VALUES
  ('S-28', 'DE', 'Prepaid S', 1000, 'EUR', 28,  3000, 100, 100),
  ('M-28', 'DE', 'Prepaid M', 1500, 'EUR', 28, 10000, 300, 300),
  ('L-28', 'DE', 'Prepaid L', 2500, 'EUR', 28, 30000, NULL, NULL),
  ('S-28', 'PL', 'Prepaid S', 3500, 'PLN', 28,  5000, 100, 100);

INSERT INTO plan_benefits (plan_id, type, label, sort_order)
  SELECT id, 'NETWORK', '5G network included', 1 FROM plans;
INSERT INTO plan_benefits (plan_id, type, label, sort_order)
  SELECT id, 'ROAMING', 'EU roaming included', 2 FROM plans WHERE market = 'DE';
INSERT INTO plan_benefits (plan_id, type, label, sort_order)
  SELECT id, 'VOICE', 'Unlimited calls and SMS', 3 FROM plans WHERE market = 'DE' AND code = 'L-28';
