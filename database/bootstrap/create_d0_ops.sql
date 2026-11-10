-- 설치 전용. 운영 적용은 별도 승인 필요. 기존 role/password/schema 이력을 바꾸지 않는다.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
SELECT pg_advisory_xact_lock(hashtextextended('ipgo:d0:installation',0));
DO $install$
BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ('ipgo_d0_owner','ipgo_d0_bootstrap'))
     OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='d0_ops') THEN
    RAISE EXCEPTION 'D0_PREEXISTING_OBJECT_REVIEW_REQUIRED';
  END IF;
  IF current_setting('d0.install_operation_id',true) IS NULL
     OR current_setting('d0.install_operation_id')::uuid IS NULL THEN
    RAISE EXCEPTION 'D0_INSTALL_OPERATION_REQUIRED';
  END IF;
  CREATE ROLE ipgo_d0_owner NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT;
  CREATE ROLE ipgo_d0_bootstrap NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT;
  EXECUTE format('COMMENT ON ROLE ipgo_d0_bootstrap IS %L',
    'ipgo:d0:install:'||current_setting('d0.install_operation_id'));
END $install$;
-- CREATEROLE은 superuser가 아니다. 관리자 자신의 owner/migrator membership 옵션을
-- transaction 안에서만 사용하고 원래 옵션으로 복원한다. Server/AI/조회 역할은 변경하지 않는다.
DO $owner_access$
DECLARE original_set boolean; original_inherit boolean; original_admin boolean; had_membership boolean;
 role_name text; saved jsonb='{}';
BEGIN
 FOREACH role_name IN ARRAY ARRAY['ipgo_d0_owner','ipgo_migrator'] LOOP
   SELECT m.set_option,m.inherit_option,m.admin_option INTO original_set,original_inherit,original_admin
     FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member
     WHERE r.rolname=role_name AND u.rolname=current_user AND m.grantor=current_user::regrole;
   had_membership:=FOUND;
   saved:=saved||jsonb_build_object(role_name,jsonb_build_object('member',had_membership,
     'set',coalesce(original_set,false),'inherit',coalesce(original_inherit,false),'admin',coalesce(original_admin,false)));
   EXECUTE format('GRANT %I TO %I WITH INHERIT TRUE, SET TRUE GRANTED BY %I',role_name,current_user,current_user);
 END LOOP;
 PERFORM set_config('d0.install_memberships',saved::text,true);
END $owner_access$;
DO $connect$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO ipgo_d0_bootstrap',current_database());
END $connect$;
CREATE SCHEMA d0_ops AUTHORIZATION ipgo_d0_owner;
REVOKE ALL ON SCHEMA d0_ops FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ipgo_d0_owner;
GRANT INSERT(email,password_hash,company_name,manager_name,business_reg_no),
      SELECT(id,created_at,company_name,manager_name,business_reg_no) ON public.users TO ipgo_d0_owner;
GRANT USAGE ON SEQUENCE public.users_id_seq TO ipgo_d0_owner;
GRANT SELECT ON public.warehouses,public.products,public.upload_files,public.calculation_runs,
 public.calculation_results,public.ai_explanations,public.ai_explanation_calls,
 public.engine_jobs,public.planned_receipts TO ipgo_d0_owner;
CREATE TABLE d0_ops.operations(
 operation_id uuid PRIMARY KEY,
 scope text NOT NULL CHECK(scope='ipgo-d0-receipts-v1'),
 d0_date date NOT NULL,
 user_id bigint UNIQUE,
 sealed_credentials text NOT NULL,
 state text NOT NULL CHECK(state IN('SEEDED','UPLOADED','RECEIPT_80','RECEIPT_40','RECEIPT_100','VERIFIED','FAILED')),
 baseline jsonb NOT NULL,
 evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(scope,d0_date)
);
ALTER TABLE d0_ops.operations OWNER TO ipgo_d0_owner;

CREATE FUNCTION d0_ops.preflight(p_operation uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,d0_ops SET TimeZone='UTC' AS $function$
DECLARE
 op d0_ops.operations%ROWTYPE; result jsonb; snapshots jsonb='{}'; entry jsonb;
 relation text; max_id bigint; total bigint; digest text; old_rows_unchanged boolean=true;
 uid bigint; warehouse_count bigint; defaults_confirmed boolean;
 product_count bigint; upload_count bigint; receipt_count bigint; run_count bigint; calls bigint;
BEGIN
 IF p_operation IS NOT NULL THEN
   SELECT * INTO STRICT op FROM d0_ops.operations WHERE operation_id=p_operation;
   uid:=op.user_id;
 END IF;
 FOREACH relation IN ARRAY ARRAY['users','warehouses','products','upload_files','calculation_runs','calculation_results','ai_explanations','planned_receipts'] LOOP
   IF p_operation IS NULL THEN
     EXECUTE format('SELECT coalesce(max(id),0) FROM public.%I',relation) INTO max_id;
   ELSE max_id:=(op.baseline->relation->>'maxId')::bigint; END IF;
   IF relation='users' THEN
     SELECT count(*),md5(coalesce(jsonb_agg(to_jsonb(x) ORDER BY id)::text,'[]')) INTO total,digest
       FROM (SELECT id,created_at,company_name,manager_name,business_reg_no FROM public.users WHERE id<=max_id) x;
   ELSE
     EXECUTE format('SELECT count(*),md5(coalesce(jsonb_agg(to_jsonb(x) ORDER BY id)::text,''[]'')) FROM public.%I x WHERE id<=$1',relation)
       INTO total,digest USING max_id;
   END IF;
   entry:=jsonb_build_object('maxId',max_id,'count',total,'hash',digest);
   snapshots:=snapshots||jsonb_build_object(relation,entry);
   IF p_operation IS NOT NULL AND entry IS DISTINCT FROM op.baseline->relation THEN old_rows_unchanged:=false; END IF;
 END LOOP;
 result:=jsonb_build_object('baseline',snapshots,'oldRowsUnchanged',old_rows_unchanged,
  'privilegeBoundaryOk',
    NOT EXISTS(SELECT 1 FROM unnest(ARRAY['users','warehouses','products','upload_files','calculation_runs','calculation_results',
                                         'ai_explanations','ai_explanation_calls','engine_jobs','planned_receipts']) t
      WHERE has_any_column_privilege('ipgo_d0_bootstrap','public.'||t,'SELECT,INSERT,UPDATE,REFERENCES')
         OR has_table_privilege('ipgo_d0_bootstrap','public.'||t,'DELETE,TRUNCATE,TRIGGER'))
    AND NOT has_schema_privilege('ipgo_d0_bootstrap','public','CREATE')
    AND NOT has_schema_privilege('ipgo_d0_bootstrap','d0_ops','CREATE')
    AND NOT has_database_privilege('ipgo_d0_bootstrap',current_database(),'CREATE')
    AND NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ('ipgo_d0_owner','ipgo_d0_bootstrap')
                   AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR
                        (rolname='ipgo_d0_owner' AND rolcanlogin)))
    AND NOT EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='ipgo_d0_bootstrap'),
  'activeJobs',(SELECT count(*) FROM public.engine_jobs WHERE status NOT IN ('DONE','FAILED')),
  'pendingInsights',(SELECT count(*) FROM public.ai_explanations WHERE status IN ('PENDING','PROCESSING')));
 IF uid IS NOT NULL THEN
   SELECT count(*),coalesce(bool_or(default_lead_time_days IS NOT NULL AND default_review_cycle_days IS NOT NULL AND default_order_unit_qty IS NOT NULL),false)
     INTO warehouse_count,defaults_confirmed FROM public.warehouses WHERE user_id=uid;
   SELECT count(*) INTO product_count FROM public.products WHERE user_id=uid;
   SELECT count(*) INTO upload_count FROM public.upload_files u JOIN public.warehouses w ON w.id=u.warehouse_id WHERE w.user_id=uid;
   SELECT count(*) INTO receipt_count FROM public.planned_receipts r JOIN public.warehouses w ON w.id=r.warehouse_id WHERE w.user_id=uid;
   SELECT count(*) INTO run_count FROM public.calculation_runs r JOIN public.warehouses w ON w.id=r.warehouse_id WHERE w.user_id=uid;
   SELECT count(*) INTO calls FROM public.ai_explanation_calls c JOIN public.calculation_results r ON r.id=c.result_id
     JOIN public.calculation_runs cr ON cr.id=r.run_id JOIN public.warehouses w ON w.id=cr.warehouse_id
     WHERE w.user_id=uid AND c.dispatched_at IS NOT NULL;
   result:=result||jsonb_build_object('userId',uid,'state',op.state,'warehouseCount',warehouse_count,
    'defaultsConfirmed',defaults_confirmed,'productCount',product_count,'uploadCount',upload_count,
    'receiptCount',receipt_count,'runCount',run_count,'llmCalls',calls,'sealedCredentials',op.sealed_credentials);
 END IF;
 RETURN result;
END $function$;

CREATE FUNCTION d0_ops.seed_user(p_operation uuid,p_day date,p_hash text,p_sealed text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,d0_ops SET TimeZone='UTC' AS $function$
DECLARE op d0_ops.operations%ROWTYPE; uid bigint; before_state jsonb;
BEGIN
 IF p_day IS DISTINCT FROM (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
    OR p_operation IS NULL OR p_hash IS NULL OR p_hash!~'^\{pbkdf2-sha256-600k\}[0-9a-f]{96}$'
    OR p_sealed IS NULL OR length(p_sealed)<256 OR length(p_sealed)>2048
    OR p_sealed!~'^[A-Za-z0-9+/]+={0,2}$' THEN
   RAISE EXCEPTION 'D0_INPUT_BOUNDARY';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('ipgo:d0:'||p_day::text,0));
 SELECT * INTO op FROM d0_ops.operations WHERE operation_id=p_operation;
 IF FOUND THEN
   IF op.d0_date<>p_day THEN RAISE EXCEPTION 'D0_OPERATION_CONFLICT'; END IF;
   RETURN jsonb_build_object('userId',op.user_id,'state',op.state,'replayed',true,'sealedCredentials',op.sealed_credentials);
 END IF;
 IF EXISTS(SELECT 1 FROM d0_ops.operations WHERE scope='ipgo-d0-receipts-v1' AND d0_date=p_day) THEN
   RAISE EXCEPTION 'D0_ALREADY_PREPARED';
 END IF;
 before_state:=d0_ops.preflight();
 IF NOT (before_state->>'privilegeBoundaryOk')::boolean THEN RAISE EXCEPTION 'D0_ROLE_BOUNDARY'; END IF;
 IF (before_state->>'activeJobs')::bigint<>0 OR (before_state->>'pendingInsights')::bigint<>0 THEN
   RAISE EXCEPTION 'D0_BUSY';
 END IF;
 INSERT INTO public.users(email,password_hash,company_name,manager_name,business_reg_no)
   VALUES('d0-'||replace(p_operation::text,'-','')||'@example.invalid',p_hash,'D0 합성전용','합성검증','0000000000') RETURNING id INTO uid;
 INSERT INTO d0_ops.operations(operation_id,scope,d0_date,user_id,sealed_credentials,state,baseline)
   VALUES(p_operation,'ipgo-d0-receipts-v1',p_day,uid,p_sealed,'SEEDED',before_state->'baseline');
 RETURN jsonb_build_object('userId',uid,'state','SEEDED','replayed',false,'sealedCredentials',p_sealed);
END $function$;

CREATE FUNCTION d0_ops.record_step(p_operation uuid,p_step text,p_evidence jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,d0_ops SET TimeZone='UTC' AS $function$
DECLARE op d0_ops.operations%ROWTYPE; chain text[]:=ARRAY['SEEDED','UPLOADED','RECEIPT_80','RECEIPT_40','RECEIPT_100','VERIFIED'];
 current_position integer; state jsonb; valid_receipts bigint;
BEGIN
 SELECT * INTO STRICT op FROM d0_ops.operations WHERE operation_id=p_operation FOR UPDATE;
 IF op.d0_date<>(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date THEN RAISE EXCEPTION 'D0_DATE_CHANGED'; END IF;
 IF jsonb_typeof(p_evidence) IS DISTINCT FROM 'object' OR length(p_evidence::text)>2048
    OR EXISTS(SELECT 1 FROM jsonb_object_keys(p_evidence) k WHERE k NOT IN ('uploadId','productId','receiptId','quantity','status','reasonCode','fixtureSha256')) THEN
   RAISE EXCEPTION 'D0_EVIDENCE_BOUNDARY';
 END IF;
 IF EXISTS(SELECT 1 FROM jsonb_each(p_evidence) e WHERE
      (e.key IN ('uploadId','productId','receiptId','quantity') AND (jsonb_typeof(e.value)<>'number' OR e.value::text!~'^[1-9][0-9]{0,17}$'))
      OR (e.key='status' AND e.value#>>'{}' NOT IN ('DONE','PENDING','VERIFIED'))
      OR (e.key='reasonCode' AND e.value#>>'{}' NOT IN ('D0_FAILED','TASK_DEADLINE','TASK_STOPPED'))
      OR (e.key='fixtureSha256' AND e.value#>>'{}'!~'^[a-f0-9]{64}$')) THEN RAISE EXCEPTION 'D0_EVIDENCE_VALUE'; END IF;
 IF op.state=p_step THEN
   IF op.evidence->p_step IS DISTINCT FROM p_evidence THEN RAISE EXCEPTION 'D0_STEP_CONFLICT'; END IF;
   RETURN d0_ops.preflight(p_operation);
 ELSIF op.state IN ('VERIFIED','FAILED') THEN RAISE EXCEPTION 'D0_TERMINAL';
 ELSIF p_step='FAILED' THEN
   NULL;
 ELSE
   current_position:=array_position(chain,op.state);
   IF current_position IS NULL OR p_step IS DISTINCT FROM chain[current_position+1] THEN RAISE EXCEPTION 'D0_STEP_ORDER'; END IF;
 END IF;
 state:=d0_ops.preflight(p_operation);
 IF p_step<>'FAILED' AND (NOT (state->>'privilegeBoundaryOk')::boolean OR (state->>'defaultsConfirmed')::boolean OR (state->>'runCount')::bigint<>0
       OR (state->>'llmCalls')::bigint<>0 OR NOT (state->>'oldRowsUnchanged')::boolean) THEN RAISE EXCEPTION 'D0_STATE_CHANGED'; END IF;
 IF p_step='VERIFIED' THEN
   SELECT count(*) INTO valid_receipts FROM public.planned_receipts r JOIN public.warehouses w ON w.id=r.warehouse_id
    WHERE w.user_id=op.user_id AND r.expected_on=op.d0_date+1 AND r.received_on IS NULL
      AND r.status='PENDING' AND (r.created_at AT TIME ZONE 'Asia/Seoul')::date=op.d0_date;
   IF (state->>'warehouseCount')::int<>1 OR (state->>'productCount')::int<>1 OR (state->>'uploadCount')::int<>1
       OR (state->>'receiptCount')::int<>3 OR valid_receipts<>3
       OR (SELECT array_agg(r.quantity ORDER BY r.quantity) FROM public.planned_receipts r JOIN public.warehouses w ON w.id=r.warehouse_id WHERE w.user_id=op.user_id)
          IS DISTINCT FROM ARRAY[40,80,100]::bigint[]
       OR EXISTS(SELECT 1 FROM public.upload_files u JOIN public.warehouses w ON w.id=u.warehouse_id
          WHERE w.user_id=op.user_id AND (u.status<>'DONE'
            OR u.period_start<>(date_trunc('month',op.d0_date)-interval '2 months')::date
            OR u.period_end<>(date_trunc('month',op.d0_date)-interval '1 month'-interval '1 day')::date)) THEN
     RAISE EXCEPTION 'D0_FINAL_BOUNDARY';
   END IF;
 END IF;
 UPDATE d0_ops.operations SET state=p_step,evidence=evidence||jsonb_build_object(p_step,p_evidence),updated_at=CURRENT_TIMESTAMP WHERE operation_id=p_operation;
 RETURN d0_ops.preflight(p_operation);
END $function$;
ALTER FUNCTION d0_ops.preflight(uuid) OWNER TO ipgo_d0_owner;
ALTER FUNCTION d0_ops.seed_user(uuid,date,text,text) OWNER TO ipgo_d0_owner;
ALTER FUNCTION d0_ops.record_step(uuid,text,jsonb) OWNER TO ipgo_d0_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA d0_ops FROM PUBLIC,ipgo_d0_bootstrap;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA d0_ops FROM PUBLIC;
GRANT USAGE ON SCHEMA d0_ops TO ipgo_d0_bootstrap;
GRANT EXECUTE ON FUNCTION d0_ops.preflight(uuid),d0_ops.seed_user(uuid,date,text,text),
 d0_ops.record_step(uuid,text,jsonb) TO ipgo_d0_bootstrap;
DO $restore_owner_access$
DECLARE entry record;
BEGIN
 FOR entry IN SELECT * FROM jsonb_each(current_setting('d0.install_memberships')::jsonb) LOOP
   IF (entry.value->>'member')::boolean THEN
     EXECUTE format('GRANT %I TO %I WITH ADMIN %s, INHERIT %s, SET %s GRANTED BY %I',entry.key,current_user,
       entry.value->>'admin',entry.value->>'inherit',entry.value->>'set',current_user);
   ELSE
     EXECUTE format('REVOKE %I FROM %I GRANTED BY %I',entry.key,current_user,current_user);
   END IF;
 END LOOP;
END $restore_owner_access$;
COMMIT;
