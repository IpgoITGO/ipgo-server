"""고정된 D0만 수행한다. 임의 URL/SQL/명령·HTTP 재시도·계산 요청은 없다."""
import base64
import hashlib
import http.cookiejar
import json
import os
import secrets
import signal
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from contract import API_ORIGIN, ASSET_PATH, ASSET_SHA, GateError, load_public, require, seal_credentials, verify_envelope
from fixture import make_fixture

CA_FILE = Path('/opt/d0-certs/global-bundle.pem')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise GateError('HTTP_REDIRECT')


class Api:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        self.bearer = None
        self.csrf = None

    def raw(self, method, path, body=None, content_type=None):
        require(path.startswith('/api/') and '?' not in path or path == '/api/products?page=0&size=20', 'API_PATH')
        headers = {'Origin': 'https://ipgo-dev.iwhya.kr', 'Accept': 'application/json'}
        if self.bearer:
            headers['Authorization'] = 'Bearer ' + self.bearer
        if content_type:
            headers['Content-Type'] = content_type
        if method != 'GET':
            if self.csrf is None:
                self.csrf = self.raw('GET', '/api/auth/csrf')
                require(self.csrf.get('headerName') == 'X-XSRF-TOKEN', 'CSRF_HEADER')
            headers[self.csrf['headerName']] = self.csrf['token']
        request = urllib.request.Request(API_ORIGIN + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                require(response.status in (200, 201, 202, 204), 'HTTP_STATUS')
                data = response.read(4 * 1024 * 1024 + 1)
                require(len(data) <= 4 * 1024 * 1024, 'HTTP_RESPONSE_SIZE')
                return json.loads(data) if data else None
        except (urllib.error.URLError, ValueError, KeyError):
            raise GateError('API_REQUEST_FAILED') from None

    def get(self, path):
        return self.raw('GET', path)

    def post(self, path, data):
        return self.raw('POST', path, json.dumps(data).encode(), 'application/json')

    def login(self, email, password):
        result = self.raw('POST', '/api/auth/login',
                         urllib.parse.urlencode({'email': email, 'password': password}).encode(),
                         'application/x-www-form-urlencoded')
        self.bearer = result['accessToken']
        require(isinstance(self.bearer, str) and len(self.bearer) < 8192, 'ACCESS_TOKEN')
        self.csrf = None

    def upload(self, name, data):
        boundary = 'd0-' + secrets.token_hex(16)
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="overwrite"\r\n\r\nfalse\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()
        body += data + f'\r\n--{boundary}--\r\n'.encode()
        return self.raw('POST', '/api/uploads', body, 'multipart/form-data; boundary=' + boundary)

    def logout(self):
        self.raw('POST', '/api/auth/logout', b'')
        self.bearer = self.csrf = None


class Database:
    def __init__(self, manifest, *, install=False):
        import psycopg
        require(os.environ.get('D0_DB_HOST') == manifest['dbHost']
                and os.environ.get('D0_DB_NAME') == manifest['dbName'], 'DB_TARGET_MISMATCH')
        require(CA_FILE.is_file(), 'RDS_CA_MISSING')
        self.connection = psycopg.connect(host=manifest['dbHost'], port=5432, dbname=manifest['dbName'],
            user=os.environ['D0_DB_USERNAME'], password=os.environ['D0_DB_PASSWORD'],
            sslmode='verify-full', sslrootcert=str(CA_FILE), connect_timeout=10, autocommit=True)
        with self.connection.cursor() as c:
            c.execute("SET lock_timeout='3s'")
            c.execute("SET statement_timeout='30s'")
            c.execute('SELECT current_user')
            role = c.fetchone()[0]
            require((install and role != 'ipgo_d0_bootstrap') or
                    (not install and role == 'ipgo_d0_bootstrap'), 'DB_ROLE_MISMATCH')

    def call(self, function, *args):
        require(function in ('preflight', 'seed_user', 'record_step'), 'DB_FUNCTION')
        with self.connection.cursor() as c:
            c.execute('SELECT d0_ops.' + function + '(' + ','.join(['%s'] * len(args)) + ')', args)
            return c.fetchone()[0]

    def step(self, operation, name, evidence):
        from psycopg.types.json import Jsonb
        return self.call('record_step', operation, name, Jsonb(evidence))

    def close(self):
        self.connection.close()


def check_state(state):
    require(state.get('privilegeBoundaryOk') is True, 'DB_ROLE_BOUNDARY')
    require(state.get('activeJobs') == 0 and state.get('pendingInsights') == 0, 'GLOBAL_WORK_PENDING')
    require(state.get('oldRowsUnchanged') is True, 'EXISTING_ROWS_CHANGED')
    require(not state.get('defaultsConfirmed', False) and state.get('runCount', 0) == 0
            and state.get('llmCalls', 0) == 0, 'D0_TRIGGERED_CALCULATION')


def verify_client(opener=None):
    request = urllib.request.Request('https://ipgo-dev.iwhya.kr' + ASSET_PATH)
    client = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with client.open(request, timeout=20) as response:
        require(response.status == 200, 'CLIENT_STATUS')
        payload = response.read(2 * 1024 * 1024 + 1)
        require(hashlib.sha256(payload).hexdigest() == ASSET_SHA, 'CLIENT_ASSET_CHANGED')


def prepare(manifest, database, api, *, wait=time.sleep, clock=time.monotonic, public_key=None):
    check_state(database.call('preflight', None))
    filename, barcode, fixture = make_fixture(manifest)
    require(hashlib.sha256(fixture).hexdigest() == manifest['fixtureSha256'], 'FIXTURE_CHANGED')
    password = 'Aa1!' + secrets.token_urlsafe(24)
    salt = os.urandom(16)
    hashed = '{pbkdf2-sha256-600k}' + (salt + hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000, 32)).hex()
    email = 'd0-' + manifest['operationId'].replace('-', '') + '@example.invalid'
    sealed = seal_credentials(public_key or load_public(), email, password)
    seeded = database.call('seed_user', manifest['operationId'], manifest['d0Date'], hashed, sealed)
    if seeded['replayed']:
        # 미확인 HTTP 쓰기를 재생하지 않는다. 기존 암호문/상태만 돌려준다.
        return {'status': 'ALREADY_SEEDED', 'operationId': manifest['operationId'], **seeded}
    uid, operation = seeded['userId'], manifest['operationId']
    logged_in = False
    try:
        api.login(email, password)
        logged_in = True
        require(api.get('/api/auth/me')['userId'] == uid, 'API_OWNER_MISMATCH')
        require(api.get('/api/replenishment-settings')['confirmed'] is False, 'DEFAULTS_CONFIRMED')
        require(api.get('/api/products?page=0&size=20')['items'] == [], 'ACCOUNT_NOT_EMPTY')
        require(api.get('/api/planned-receipts')['items'] == [], 'ACCOUNT_NOT_EMPTY')
        upload = api.upload(filename, fixture)
        upload_id = upload['uploadId']
        require(type(upload_id) is int and upload_id > 0, 'UPLOAD_ID')
        end = clock() + 180
        while True:
            result = api.get('/api/uploads/' + str(upload_id))
            require(result['status'] in ('PROCESSING', 'DONE'), 'CLEAN_FAILED')
            if result['status'] == 'DONE':
                break
            require(clock() < end, 'CLEAN_TIMEOUT')
            wait(2)
        require(result['runId'] is None and result['validation']['targetSkuCount'] == 1
                and result['validation']['excludedSkus'] == [], 'CLEAN_CONTRACT')
        products = api.get('/api/products?page=0&size=20')['items']
        require(len(products) == 1 and products[0]['skuBarcode'] == barcode and products[0]['unitCost'] == 10,
                'PRODUCT_CONTRACT')
        product_id = products[0]['productId']
        check_state(database.step(operation, 'UPLOADED', {'uploadId': upload_id, 'productId': product_id,
                                                        'fixtureSha256': manifest['fixtureSha256'], 'status': 'DONE'}))
        receipt_ids = []
        for index, quantity in enumerate((80, 40, 100), 1):
            require(api.get('/api/replenishment-settings')['confirmed'] is False, 'DEFAULTS_CONFIRMED')
            check_state(database.call('preflight', operation))
            created = api.post('/api/planned-receipts', {'productId': product_id, 'quantity': quantity,
                                                       'expectedOn': manifest['d1Date']})
            require(created['runId'] is None and type(created['receiptId']) is int
                    and created['receiptId'] not in receipt_ids, 'RECEIPT_CONTRACT')
            receipt_ids.append(created['receiptId'])
            items = api.get('/api/planned-receipts')['items']
            require(len(items) == index and sorted(x['quantity'] for x in items) == sorted((80, 40, 100)[:index])
                    and all(x['productId'] == product_id and x['expectedOn'] == manifest['d1Date']
                            and x['status'] == 'PENDING' and x['receivedOn'] is None for x in items), 'RECEIPT_STATE')
            check_state(database.step(operation, 'RECEIPT_' + str(quantity),
                                      {'receiptId': created['receiptId'], 'quantity': quantity, 'status': 'PENDING'}))
        logged_in = False
        api.logout()
        final = database.step(operation, 'VERIFIED', {'status': 'VERIFIED'})
        check_state(final)
        return {'status': 'VERIFIED', 'operationId': operation, 'userId': uid, 'productId': product_id,
                'uploadId': upload_id, 'receiptIds': receipt_ids, 'sealedCredentials': sealed,
                'llmCalls': final['llmCalls'], 'incrementalLlmUsd': 0, 'existingRowsUnchanged': final['oldRowsUnchanged']}
    except Exception:
        try:
            database.step(operation, 'FAILED', {'reasonCode': 'D0_FAILED'})
        except Exception:
            pass
        raise
    finally:
        if logged_in:
            try:
                api.logout()
            except Exception:
                # 첫 업무 오류를 보존한다. 실패한 업무 요청은 재전송하지 않는다.
                print('D0_LOGOUT_UNCONFIRMED', file=sys.stderr)


def main():
    require(len(sys.argv) == 2 and sys.argv[1] in ('install', 'read-only', 'prepare'), 'MODE')
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(GateError('TASK_DEADLINE')))
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(GateError('TASK_STOPPED')))
    signal.alarm(600)
    encoded = os.environ.get('D0_SIGNED_MANIFEST', '')
    require(0 < len(encoded) <= 6500, 'ENVELOPE_SIZE')
    try:
        envelope = json.loads(base64.b64decode(encoded, validate=True))
    except (ValueError, TypeError):
        raise GateError('ENVELOPE_ENCODING') from None
    manifest = verify_envelope(envelope)
    require(manifest['kind'] == sys.argv[1], 'MODE_SIGNATURE_MISMATCH')
    db = Database(manifest, install=sys.argv[1] == 'install')
    try:
        if sys.argv[1] == 'install':
            from install import install
            output = install(manifest, db)
        else:
            verify_client()
            if sys.argv[1] == 'read-only':
                state = db.call('preflight', None)
                check_state(state)
                output = {'status': 'READ_ONLY_OK', 'baseline': state['baseline'], 'llmCalls': 0}
            else:
                output = prepare(manifest, db, Api())
        print(json.dumps(output, sort_keys=True))
    finally:
        db.close()


if __name__ == '__main__':
    try:
        main()
    except GateError as error:
        print(json.dumps({'status': 'FAILED', 'reasonCode': str(error)}))
        sys.exit(1)
    except Exception:
        # DSN/HTTP 본문/예외 메시지를 출력하지 않는다.
        print(json.dumps({'status': 'FAILED', 'reasonCode': 'D0_INTERNAL_ERROR'}))
        sys.exit(1)
