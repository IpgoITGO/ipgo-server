-- Latest explanation remains compatible; calls keep their own request and terminal snapshot.
-- No historical provider usage is invented or backfilled.
CREATE TABLE ai_explanation_calls (
    attempt_id VARCHAR(36) PRIMARY KEY,
    result_id BIGINT NOT NULL REFERENCES calculation_results(id) ON DELETE CASCADE,
    source VARCHAR(20) NOT NULL CHECK (source IN ('LIVE', 'LEGACY', 'RECOVERY')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    dispatched_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING', 'DONE')),
    request_payload JSONB NOT NULL,
    output_path VARCHAR(100),
    mode VARCHAR(20) NOT NULL,
    raw_text TEXT,
    validation_passed BOOLEAN NOT NULL,
    inserted_values JSONB,
    rendered_text TEXT,
    reason_code VARCHAR(50),
    model_name VARCHAR(100),
    versions JSONB NOT NULL,
    input_tokens INTEGER CHECK (input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens >= 0),
    round_trip_ms INTEGER CHECK (round_trip_ms >= 0),
    latency_ms INTEGER CHECK (latency_ms >= 0),
    cost_usd NUMERIC(12,6) CHECK (cost_usd >= 0),
    CONSTRAINT ck_insight_call_finished CHECK (
        (status='PROCESSING' AND finished_at IS NULL) OR (status='DONE' AND finished_at IS NOT NULL))
);
CREATE INDEX idx_ai_explanation_calls_result ON ai_explanation_calls(result_id, started_at);
ALTER TABLE ai_explanations ADD COLUMN active_attempt_id VARCHAR(36);
