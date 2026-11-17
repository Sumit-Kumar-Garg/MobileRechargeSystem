-- One row per background job; the holder renews it, others skip their run while it is valid.
CREATE TABLE job_leases (
  name         VARCHAR(60) PRIMARY KEY,
  owner        VARCHAR(80) NOT NULL,
  locked_until TIMESTAMPTZ NOT NULL
);
