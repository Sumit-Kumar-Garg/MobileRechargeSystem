-- The latest advice of the recharge advisor agent per number. Served from here so the model is never on the
-- request path. Holds no phone number and no usage figures, only the chosen plan and the sentence shown.
CREATE TABLE recommendation_advice (
  number_id     UUID PRIMARY KEY REFERENCES subscriber_numbers (id),
  user_id       UUID         NOT NULL,
  plan_id       UUID         NOT NULL,
  plan_code     VARCHAR(40)  NOT NULL,
  explanation   VARCHAR(220) NOT NULL,
  language      VARCHAR(5)   NOT NULL,
  model         VARCHAR(60)  NOT NULL,
  steps         INT          NOT NULL,
  input_tokens  INT          NOT NULL DEFAULT 0,
  output_tokens INT          NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ  NOT NULL,
  valid_until   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_advice_user ON recommendation_advice (user_id);
