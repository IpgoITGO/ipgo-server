-- No FK: deleting a result, user, or call record must never refund paid-call allowance.
CREATE TABLE insight_monthly_usage (
    month_key DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM month_key)=1),
    reserved_calls INTEGER NOT NULL CHECK (reserved_calls BETWEEN 0 AND 1000),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Count surviving calls already dispatched this calendar month conservatively before activation.
INSERT INTO insight_monthly_usage(month_key,reserved_calls)
SELECT date_trunc('month',clock_timestamp() AT TIME ZONE 'Asia/Seoul')::date,
       LEAST(1000,COUNT(*)::integer)
FROM ai_explanation_calls
WHERE dispatched_at >= date_trunc('month',clock_timestamp() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul';

-- PostgreSQL-only permissions and invariants. Test harness creates the portable table above.
CREATE FUNCTION protect_insight_monthly_usage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.month_key <> date_trunc('month',clock_timestamp() AT TIME ZONE 'Asia/Seoul')::date
           OR NEW.reserved_calls <> 0 THEN
            RAISE EXCEPTION 'runtime can only open the current month with zero calls' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.month_key <> OLD.month_key OR NEW.reserved_calls <> OLD.reserved_calls+1 THEN
        RAISE EXCEPTION 'monthly allowance is append-only and cannot be refunded' USING ERRCODE='23514';
    END IF;
    NEW.updated_at=clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER protect_insight_monthly_usage BEFORE INSERT OR UPDATE ON insight_monthly_usage
FOR EACH ROW EXECUTE FUNCTION protect_insight_monthly_usage();
REVOKE ALL ON insight_monthly_usage FROM ipgo_server,ipgo_ai_reader;
GRANT SELECT,INSERT ON insight_monthly_usage TO ipgo_server;
GRANT UPDATE(reserved_calls,updated_at) ON insight_monthly_usage TO ipgo_server;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_service_reader') THEN
        REVOKE ALL ON insight_monthly_usage FROM ipgo_ai_service_reader;
    END IF;
END $$;
