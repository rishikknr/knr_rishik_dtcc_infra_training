import json, secrets, os, subprocess, sys
from pathlib import Path
root=Path(os.environ.get('TRACE_TEST_STATE_DIR','/tmp/trace-final-hardening'))
root.mkdir(parents=True,exist_ok=True)
root.chmod(0o700)
private_path=root/'private-env.json'
if not private_path.exists():
    private={'TRACE_DATABASE_URL':'jdbc:postgresql://localhost:55437/trace_hardening_browser','TRACE_RUNTIME_PASSWORD':'trace-test-runtime-only','TRACE_MIGRATION_PASSWORD':'trace-test-migration-only','TRACE_PROCESSOR_TOKEN':secrets.token_urlsafe(32),'TRACE_CONNECTOR_ENCRYPTION_KEY':__import__('base64').b64encode(secrets.token_bytes(32)).decode()}
    private_path.write_text(json.dumps(private));private_path.chmod(0o600)
if '--initialize-secrets' in sys.argv:
    print('Private disposable configuration initialized outside the repository')
    raise SystemExit(0)
private=json.loads((root/'private-env.json').read_text())
password=json.loads((root/'browser-fixture.json').read_text())['password'] if (root/'browser-fixture.json').exists() else secrets.token_urlsafe(24)
fixture={'password':password,'recoveryUserId':'c1000000-0000-4000-8000-000000000004'}
for name,data in [('private-env.json',private),('browser-fixture.json',fixture)]:
    target=root/name;target.write_text(json.dumps(data));target.chmod(0o600)
# Refuse reuse of an application database or unknown fixtures.
check=subprocess.run(['docker','exec','trace-final-hardening-db','psql','-U','trace_test_admin','-d','trace_hardening_browser','-X','-tA','-v','ON_ERROR_STOP=1','-c',"SELECT trace_schema_version(); SELECT count(*) FROM organizations WHERE slug NOT IN ('hardening-a','hardening-b','browser-provisioned','legacy-unassigned'); SELECT count(*) FROM users WHERE email NOT LIKE '%@hardening.example.test'; SELECT count(*) FROM users;"],text=True,capture_output=True)
if check.returncode: raise SystemExit('Provision/migrate only the dedicated disposable browser database first')
values=[x.strip() for x in check.stdout.splitlines() if x.strip()]
if values[:3] != ['15','0','0']: raise SystemExit('Refusing to reset an incompatible schema or unrecognized data')
if int(values[3]) and '--reset' not in sys.argv: raise SystemExit('Synthetic fixtures already exist. Use --reset only to intentionally discard these disposable fixtures.')
# Only our dedicated, newly created disposable database. No reset of existing fixtures.
sql="""TRUNCATE organizations,users CASCADE;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO organizations(id,name,slug) VALUES('a1000000-0000-4000-8000-000000000001','Hardening A','hardening-a'),('b1000000-0000-4000-8000-000000000001','Hardening B','hardening-b');
"""
for i,name in [(1,'owner'),(2,'viewer'),(3,'platform'),(4,'recovery')]:
    uid=f'c1000000-0000-4000-8000-{i:012d}'
    sql+=f"INSERT INTO users(id,email,display_name,platform_role) VALUES('{uid}','{name}@hardening.example.test','Hardening {name}','{'PLATFORM_ADMIN' if name=='platform' else 'USER'}');\n"
    sql+=f"INSERT INTO user_credentials(user_id,password_hash) VALUES('{uid}',crypt('{password}',gen_salt('bf',10)));\n"
    if i!=4:
        sql+=f"INSERT INTO organization_memberships(organization_id,user_id,role) VALUES('a1000000-0000-4000-8000-000000000001','{uid}','{'VIEWER' if i==2 else 'OWNER'}');\n"
sql+="""INSERT INTO organization_memberships(organization_id,user_id,role) VALUES('b1000000-0000-4000-8000-000000000001','c1000000-0000-4000-8000-000000000001','OWNER');
INSERT INTO teams(id,organization_id,name,slug) VALUES('a2000000-0000-4000-8000-000000000001','a1000000-0000-4000-8000-000000000001','Hardening Engineering','hardening-engineering');
INSERT INTO team_memberships(organization_id,team_id,user_id,role) VALUES('a1000000-0000-4000-8000-000000000001','a2000000-0000-4000-8000-000000000001','c1000000-0000-4000-8000-000000000001','OWNER');
"""
result=subprocess.run(['docker','exec','-i','trace-final-hardening-db','psql','-U','trace_test_admin','-d','trace_hardening_browser','-X','-v','ON_ERROR_STOP=1'],input=sql,text=True,capture_output=True)
if result.returncode: raise RuntimeError('Disposable fixture provisioning failed; inspect locally without exposing private SQL')
print('Synthetic identities and two isolated organizations provisioned; private test secrets saved outside repository')
