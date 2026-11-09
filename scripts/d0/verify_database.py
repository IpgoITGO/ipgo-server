"""실제 폐기 PostgreSQL의 role/함수/경쟁 검증. Docker label을 확인한 후만 사용."""
import concurrent.futures
import json
import subprocess
import sys
import unittest
from uuid import uuid4

CONTAINER = sys.argv.pop(1)
if not CONTAINER.startswith('ipgo-d0-test-'):
    raise SystemExit('폐기 컨테이너 이름만 허용')
meta = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER], text=True))[0]
if meta['Config'].get('Labels', {}).get('com.ipgo.disposable-test') != 'd0':
    raise SystemExit('폐기 컨테이너 label 없음')
DAY = "(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date"
HASH = '{pbkdf2-sha256-600k}' + 'a' * 96
SEALED = 'A' * 512


def query(text, *, role='ipgo_d0_bootstrap', ok=True):
    p = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-qAt',
                        '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-U', role, '-d', 'ipgo'],
                       input=text, text=True, capture_output=True)
    if ok and p.returncode:
        raise AssertionError('폐기 SQL 실패: ' + p.stderr[-400:])
    if not ok and not p.returncode:
        raise AssertionError('거부해야 하는 SQL이 성공')
    return p.stdout.strip()


def seed(operation=None, day=DAY, **kwargs):
    operation = operation or str(uuid4())
    output = query(f"SELECT d0_ops.seed_user('{operation}',{day},'{HASH}','{SEALED}');", **kwargs)
    return operation, json.loads(output) if output else None


class DatabaseTest(unittest.TestCase):
    def setUp(self):
        query("TRUNCATE public.users CASCADE; TRUNCATE d0_ops.operations;"
              "REVOKE TEMPORARY ON DATABASE ipgo FROM ipgo_d0_bootstrap;"
              "INSERT INTO public.users(id,email,password_hash,company_name) VALUES(1,'prior@example.invalid','test','prior');"
              "SELECT setval('public.users_id_seq',1);", role='postgres')

    def test_seed_replay_does_not_reset_or_duplicate(self):
        operation, first = seed()
        _, replay = seed(operation)
        self.assertFalse(first['replayed'])
        self.assertTrue(replay['replayed'])
        self.assertEqual(first['userId'], replay['userId'])
        self.assertEqual('2', query('SELECT count(*) FROM public.users', role='postgres'))
        self.assertEqual(SEALED, replay['sealedCredentials'])

    def test_direct_tables_passwords_and_other_users_are_denied(self):
        for statement in ["SELECT email,password_hash FROM public.users", "DELETE FROM public.users",
                          "UPDATE public.users SET company_name='bad'", "SELECT * FROM d0_ops.operations",
                          "INSERT INTO public.users(email,password_hash) VALUES('x@example.invalid','x')",
                          "CREATE TABLE public.d0_bad(id integer)", "SET ROLE ipgo_server"]:
            with self.subTest(statement=statement):
                query(statement, ok=False)
        query("SELECT d0_ops.preflight(NULL)", role='ipgo_server', ok=False)
        query("SET ROLE ipgo_d0_owner; SELECT password_hash FROM public.users", role='postgres', ok=False)

    def test_wrong_date_invalid_hash_and_second_operation_denied(self):
        seed(day=DAY + '-1', ok=False)
        query(f"SELECT d0_ops.seed_user('{uuid4()}',{DAY},'not-a-hash','{SEALED}')", ok=False)
        seed()
        seed(ok=False)
        self.assertEqual('2', query('SELECT count(*) FROM public.users', role='postgres'))

    def test_effective_privilege_drift_blocks_seed_without_changing_existing_grants(self):
        query('GRANT SELECT ON public.users TO ipgo_d0_bootstrap',role='postgres')
        try:
            state=json.loads(query('SELECT d0_ops.preflight(NULL)'))
            self.assertFalse(state['privilegeBoundaryOk'])
            seed(ok=False)
            self.assertEqual('1',query('SELECT count(*) FROM public.users',role='postgres'))
        finally:
            query('REVOKE SELECT ON public.users FROM ipgo_d0_bootstrap',role='postgres')

    def test_concurrent_day_claim_is_atomic(self):
        def run(_):
            try:
                seed()
                return True
            except AssertionError:
                return False
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(1, sum(pool.map(run, range(2))))
        self.assertEqual('1', query('SELECT count(*) FROM d0_ops.operations', role='postgres'))

    def test_old_rows_hash_order_failure_and_evidence_boundary(self):
        operation, _ = seed()
        state = json.loads(query(f"SELECT d0_ops.preflight('{operation}')"))
        self.assertTrue(state['oldRowsUnchanged'])
        self.assertTrue(state['privilegeBoundaryOk'])
        query(f"SELECT d0_ops.record_step('{operation}','RECEIPT_80','{{}}')", ok=False)
        query(f"""SELECT d0_ops.record_step('{operation}','UPLOADED','{{"password":"bad"}}')""", ok=False)
        query(f"""SELECT d0_ops.record_step('{operation}','UPLOADED','{{"status":"password-value"}}')""", ok=False)
        query("UPDATE public.users SET company_name='changed' WHERE id=1", role='postgres')
        state = json.loads(query(f"SELECT d0_ops.preflight('{operation}')"))
        self.assertFalse(state['oldRowsUnchanged'])
        query(f"SELECT d0_ops.record_step('{operation}','UPLOADED','{{}}')", ok=False)

    def test_failure_is_terminal_and_retains_created_account(self):
        operation, seeded = seed()
        query(f"""SELECT d0_ops.record_step('{operation}','FAILED','{{"reasonCode":"D0_FAILED"}}')""")
        query(f"SELECT d0_ops.record_step('{operation}','UPLOADED','{{}}')", ok=False)
        self.assertEqual('2', query('SELECT count(*) FROM public.users', role='postgres'))
        self.assertEqual('FAILED', seed(operation)[1]['state'])

    def test_verified_requires_real_owned_rows(self):
        operation, _ = seed()
        for step in ('UPLOADED','RECEIPT_80','RECEIPT_40','RECEIPT_100'):
            query(f"SELECT d0_ops.record_step('{operation}','{step}','{{}}')")
        query(f"SELECT d0_ops.record_step('{operation}','VERIFIED','{{}}')", ok=False)

    def test_fixed_search_path_ignores_temporary_shadow_table(self):
        query("GRANT TEMPORARY ON DATABASE ipgo TO ipgo_d0_bootstrap", role='postgres')
        result = query("CREATE TEMP TABLE users(id bigint); INSERT INTO users VALUES(999);"
                       "SELECT d0_ops.preflight(NULL);")
        self.assertEqual(1, json.loads(result)['baseline']['users']['count'])

    def test_final_verification_uses_real_owned_rows_and_preserves_history(self):
        operation, result = seed()
        uid = result['userId']
        wid = query(f"INSERT INTO public.warehouses(user_id,name) VALUES({uid},'synthetic') RETURNING id", role='postgres')
        pid = query(f"INSERT INTO public.products(user_id,sku_barcode,sku_name,unit_cost) VALUES({uid},'synthetic','synthetic',10) RETURNING id", role='postgres')
        query(f"""INSERT INTO public.upload_files(warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status)
          VALUES({wid},'synthetic.xlsx','local-only','{'a'*64}',1,
          (date_trunc('month',{DAY})-interval '2 months')::date,
          (date_trunc('month',{DAY})-interval '1 month'-interval '1 day')::date,'DONE')""", role='postgres')
        query(f"SELECT d0_ops.record_step('{operation}','UPLOADED','{{}}')")
        for quantity in (80,40,100):
            query(f"INSERT INTO public.planned_receipts(product_id,warehouse_id,quantity,expected_on,status,created_at,updated_at) VALUES({pid},{wid},{quantity},{DAY}+1,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", role='postgres')
            query(f"SELECT d0_ops.record_step('{operation}','RECEIPT_{quantity}','{{}}')")
        state = json.loads(query(f"SELECT d0_ops.record_step('{operation}','VERIFIED','{{}}')"))
        self.assertEqual('VERIFIED', state['state'])
        self.assertTrue(state['oldRowsUnchanged'])
        self.assertEqual(0,state['llmCalls'])
        query(f"SELECT d0_ops.record_step('{operation}','FAILED','{{}}')", ok=False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
