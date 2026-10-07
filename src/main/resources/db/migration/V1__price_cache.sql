CREATE TABLE price_snapshot (
    id VARCHAR(72) PRIMARY KEY,
    lookup_key VARCHAR(64) NOT NULL,
    normalized_name VARCHAR(300) NOT NULL,
    requested_bgg_id BIGINT,
    matched_bgg_id BIGINT,
    url VARCHAR(2048) NOT NULL,
    available_price NUMERIC(12, 2),
    best_price NUMERIC(12, 2),
    complete BOOLEAN NOT NULL,
    fetched_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT valid_prices CHECK ((available_price IS NOT NULL OR best_price IS NOT NULL)
        AND (available_price IS NULL OR available_price >= 0)
        AND (best_price IS NULL OR best_price >= 0)),
    CONSTRAINT valid_completeness CHECK (complete = (available_price IS NOT NULL AND best_price IS NOT NULL)),
    CONSTRAINT valid_time CHECK (expires_at > fetched_at),
    CONSTRAINT valid_identity CHECK (requested_bgg_id IS NULL OR requested_bgg_id = matched_bgg_id)
);
CREATE INDEX snapshot_lookup ON price_snapshot(lookup_key);
CREATE INDEX snapshot_expiry ON price_snapshot(expires_at);
CREATE TABLE lookup_attempt (
    lookup_key VARCHAR(64) PRIMARY KEY,
    attempted_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    failure_code VARCHAR(40),
    retry_at TIMESTAMP(6) WITH TIME ZONE
);
