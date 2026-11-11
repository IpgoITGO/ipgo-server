-- 폐기용 DB의 실제 PostgreSQL 제약/역할 검사. 완료 후 데이터는 rollback한다.
BEGIN;
DO $$
DECLARE
    table_name TEXT;
    owner_id BIGINT;
    other_id BIGINT;
BEGIN
    IF (SELECT COUNT(*) FROM pg_tables WHERE schemaname='public') <> 37 THEN
        RAISE EXCEPTION 'unexpected billing migration table count';
    END IF;
    FOREACH table_name IN ARRAY ARRAY['billing_subscriptions','billing_methods','billing_sessions','billing_invoices','billing_attempts','billing_commands','billing_webhook_inbox','billing_refunds'] LOOP
        IF NOT has_table_privilege('ipgo_server',table_name,'SELECT')
            OR NOT has_table_privilege('ipgo_server',table_name,'INSERT')
            OR NOT has_table_privilege('ipgo_server',table_name,'UPDATE')
            OR has_table_privilege('ipgo_server',table_name,'DELETE')
            OR has_table_privilege('ipgo_ai_reader',table_name,'SELECT')
            OR has_table_privilege('ipgo_ai_service_reader',table_name,'SELECT') THEN
            RAISE EXCEPTION 'billing role boundary failed';
        END IF;
    END LOOP;
    INSERT INTO users(email,password_hash) VALUES ('billing-schema-owner@example.test','fixture') RETURNING id INTO owner_id;
    INSERT INTO users(email,password_hash) VALUES ('billing-schema-other@example.test','fixture') RETURNING id INTO other_id;
    INSERT INTO billing_subscriptions(user_id,merchant_id,mode,status,plan_id,price_version,amount,trial_end,customer_key)
        VALUES (owner_id,'fixture-mid','TEST','TRIALING','trial','fixture',0,CURRENT_TIMESTAMP,'schema-customer-owner'),
               (other_id,'fixture-mid','TEST','TRIALING','trial','fixture',0,CURRENT_TIMESTAMP,'schema-customer-other');
    INSERT INTO billing_methods(id,user_id,encrypted_key,fingerprint,masked_number,status,created_at)
        VALUES ('schema-method',owner_id,'fixture-ciphertext','schema-fingerprint','****0000','ACTIVE',CURRENT_TIMESTAMP);
    BEGIN
        UPDATE billing_subscriptions SET default_method_id='schema-method' WHERE user_id=other_id;
        RAISE EXCEPTION 'cross-owner default billing method accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    INSERT INTO billing_invoices(id,user_id,cycle_no,plan_id,price_version,amount,order_name,currency,period_start,period_end,status)
        VALUES ('schema-invoice',owner_id,1,'seller','fixture',49000,'fixture monthly','KRW',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 month','OPEN');
    INSERT INTO billing_attempts(id,invoice_id,user_id,method_id,attempt_no,order_id,idempotency_key,body_hash,status,created_at)
        VALUES ('schema-attempt1','schema-invoice',owner_id,'schema-method',1,'schema-order1','schema-idem1','fixture','READY',CURRENT_TIMESTAMP);
    BEGIN
        INSERT INTO billing_attempts(id,invoice_id,user_id,method_id,attempt_no,order_id,idempotency_key,body_hash,status,created_at)
            VALUES ('schema-attempt2','schema-invoice',owner_id,'schema-method',2,'schema-order2','schema-idem2','fixture','READY',CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'duplicate live billing attempt accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO billing_attempts(id,invoice_id,user_id,method_id,attempt_no,order_id,idempotency_key,body_hash,status,created_at)
            VALUES ('schema-cross','schema-invoice',other_id,'schema-method',3,'schema-cross-order','schema-cross-idem','fixture','DECLINED',CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'cross-owner billing attempt accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    UPDATE billing_attempts SET status='SUCCEEDED' WHERE id='schema-attempt1';
    BEGIN
        INSERT INTO billing_attempts(id,invoice_id,user_id,method_id,attempt_no,order_id,idempotency_key,body_hash,status,created_at)
            VALUES ('schema-attempt3','schema-invoice',owner_id,'schema-method',4,'schema-order3','schema-idem3','fixture','SUCCEEDED',CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'duplicate successful billing attempt accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO billing_refunds(id,invoice_id,user_id,reason,provider_key,status,created_at,lease_until)
            VALUES ('schema-refund','schema-invoice',other_id,'fixture','refund-provider','PROCESSING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'cross-owner refund accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;
ROLLBACK;
