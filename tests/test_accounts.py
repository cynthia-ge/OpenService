import pytest
from sqlalchemy import select
from werkzeug.security import check_password_hash
from werkzeug.exceptions import BadRequest
from openservice.store import User
from openservice.accounts import create_account
from conftest import PASSWORD, auth


def sign_in(client, name='admin', password=PASSWORD):
    client.get('/login')
    with client.session_transaction() as state:
        token = state['csrf']
    assert client.post('/login', data={'csrf': token, 'username': name, 'password': password}).status_code == 302
    with client.session_transaction() as state:
        return state['csrf']


def test_admin_can_create_accounts_in_browser_and_new_user_can_sign_in(client, app):
    token = sign_in(client)
    page = client.get('/admin/accounts')
    assert page.status_code == 200
    assert b'Create account' in page.data
    assert b'option value="reader" selected' in page.data
    response = client.post('/admin/accounts', data={'csrf': token, 'name': 'Evaluator', 'password': PASSWORD, 'role': 'agent'})
    assert response.status_code == 302
    assert b'Evaluator' in client.get(response.location).data
    with app.extensions['db']() as db:
        account = db.get(User, 'Evaluator')
        assert account.role == 'agent'
        assert account.password != PASSWORD and check_password_hash(account.password, PASSWORD)
        encoded_password = account.password.encode()
    page = client.get('/admin/accounts')
    assert PASSWORD.encode() not in page.data and encoded_password not in page.data
    evaluator = app.test_client()
    sign_in(evaluator, 'Evaluator')
    assert evaluator.get('/workspace/incident').status_code == 200
    assert evaluator.get('/admin/accounts').status_code == 403


def test_account_workspace_permission_and_csrf_boundaries(actors, app):
    anonymous = app.test_client()
    assert anonymous.get('/admin/accounts').status_code == 302
    for role in ['agent', 'reader']:
        client = app.test_client()
        token = sign_in(client, role)
        assert b'/admin/accounts' not in client.get('/').data
        assert client.get('/admin/accounts').status_code == 403
        assert client.post('/admin/accounts', data={'csrf': token, 'name': 'Blocked', 'password': PASSWORD, 'role': 'admin'}).status_code == 403
    client = app.test_client()
    sign_in(client)
    for csrf in ['', 'incorrect']:
        assert client.post('/admin/accounts', data={'csrf': csrf, 'name': 'Blocked', 'password': PASSWORD, 'role': 'admin'}).status_code == 403
    with app.extensions['db']() as db:
        assert db.get(User, 'Blocked') is None


def test_invalid_browser_creation_is_inline_and_never_echoes_password(client, app):
    token = sign_in(client)
    password = 'not-to-be-echoed-secret'
    for fields, message in [
        ({'name': '<script>alert(1)</script>', 'role': 'agent'}, 'Username must'),
        ({'name': 'admin', 'role': 'agent'}, 'User already exists'),
        ({'name': 'Incomplete', 'role': 'root'}, 'Choose an admin'),
    ]:
        response = client.post('/admin/accounts', data={'csrf': token, 'password': password, **fields})
        assert response.status_code == 400
        assert b'role="alert"' in response.data and message.encode() in response.data
        assert password.encode() not in response.data
        assert b'<script>' not in response.data
        assert b'name="password" type="password"' in response.data
    response = client.post('/admin/accounts', data={'csrf': token, 'name': 'Incomplete', 'role': 'reader'})
    assert response.status_code == 400 and b'Password must' in response.data
    with app.extensions['db']() as db:
        assert db.get(User, 'Incomplete') is None
        assert db.get(User, 'admin').role == 'admin'
        assert check_password_hash(db.get(User, 'admin').password, PASSWORD)


@pytest.mark.parametrize('change', [
    {'name': ''}, {'name': 'a'*81}, {'name': 'with space'}, {'name': 'é'}, {'name': []},
    {'password': 42}, {'password': 'x'*11}, {'password': 'x'*1025},
    {'role': []}, {'role': 'unknown'},
])
def test_api_and_browser_share_creation_limits(admin, change):
    response = admin.post('/api/openservice/users', json={'name': 'ValidName', 'password': PASSWORD, 'role': 'reader', **change})
    assert response.status_code == 400
    assert PASSWORD not in response.get_data(as_text=True)


def test_account_boundary_values_and_reader_permissions(admin, app):
    name = 'A'*80
    password = 'p'*1024
    response = admin.post('/api/openservice/users', json={'name': name, 'password': password, 'role': 'reader'})
    assert response.status_code == 201 and response.json['result'] == {'name': name, 'role': 'reader'}
    assert admin.get('/api/now/table/incident', headers=auth(name, password)).status_code == 200
    assert admin.post('/api/now/table/incident', json={'short_description': 'Cannot write'}, headers=auth(name, password)).status_code == 403
    assert admin.get('/admin/accounts', headers=auth(name, password)).status_code == 403
    with app.extensions['db']() as db:
        assert check_password_hash(db.get(User, name).password, password)


def test_account_listing_is_bounded_ordered_and_secret_free(admin, app):
    with app.extensions['db'].begin() as db:
        password_hash = db.get(User, 'admin').password
        db.add_all(User(name=f'evaluator{number:02}', role='reader', password=password_hash) for number in range(30))
    first = admin.get('/admin/accounts')
    assert b'evaluator23' in first.data and b'evaluator24' not in first.data
    assert b'Page 1 of 2' in first.data and b'Next' in first.data
    second = admin.get('/admin/accounts?page=2')
    assert b'evaluator24' in second.data and b'evaluator29' in second.data
    assert b'evaluator23' not in second.data and b'Previous' in second.data
    assert b'Next' not in second.data
    assert password_hash.encode() not in first.data + second.data
    response = admin.post('/admin/accounts?page=2', data={'name': 'zznew', 'password': PASSWORD, 'role': 'reader'})
    assert response.status_code == 302 and response.location.endswith('?page=2')
    assert b'zznew' in admin.get(response.location).data


@pytest.mark.parametrize('query', ['page=0', 'page=-1', 'page=01', 'page=x', 'page=1000000', 'page=2', 'page=1&page=1', 'extra=1'])
def test_account_listing_rejects_invalid_pages(admin, query):
    assert admin.get('/admin/accounts?' + query).status_code == 400


def test_duplicate_constraint_during_creation_rolls_back(app, monkeypatch):
    with app.extensions['db']() as db:
        old_hash = db.get(User, 'admin').password
        # Simulate a stale existence check while exercising the real unique constraint.
        monkeypatch.setattr(db, 'get', lambda *args: None)
        with pytest.raises(BadRequest, match='User already exists') as caught:
            create_account(db, {'name': 'admin', 'password': 'replacement-secret', 'role': 'reader'})
        assert caught.value.code == 400
        persisted = db.scalar(select(User).where(User.name == 'admin'))
        assert persisted.password == old_hash and persisted.role == 'admin'
        account = create_account(db, {'name': 'AfterRollback', 'password': PASSWORD, 'role': 'reader'})
        db.commit()
        assert account.name == 'AfterRollback'
