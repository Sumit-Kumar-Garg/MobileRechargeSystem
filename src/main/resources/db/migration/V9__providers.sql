-- Several plan providers can supply plans; each plan belongs to exactly one provider.
ALTER TABLE plans ADD COLUMN provider_id VARCHAR(40) NOT NULL DEFAULT 'operator';
ALTER TABLE plans ADD COLUMN synced_at TIMESTAMPTZ;
ALTER TABLE plans DROP CONSTRAINT uq_plans_market_code;
ALTER TABLE plans ADD CONSTRAINT uq_plans_provider_market_code UNIQUE (provider_id, market, code);

-- The order remembers who supplies the plan (fulfilment is routed there).
ALTER TABLE orders ADD COLUMN plan_provider VARCHAR(40) NOT NULL DEFAULT 'operator';

-- The payment remembers which payment provider handled it (lookup, cancel and refund go back there).
ALTER TABLE payments ADD COLUMN provider VARCHAR(40) NOT NULL DEFAULT 'mockpay';
ALTER TABLE payments ADD COLUMN method VARCHAR(30) NOT NULL DEFAULT 'card';
CREATE INDEX idx_payments_provider_status ON payments (provider, status);
