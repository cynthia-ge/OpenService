import base64
import os
from uuid import uuid4
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
import pytest
from openservice.app import create_app

PASSWORD = 'test-only-password-long'

def auth(name='admin', password=PASSWORD):
    return {'Authorization': 'Basic ' + base64.b64encode(f'{name}:{password}'.encode()).decode()}

@pytest.fixture
def app(tmp_path):
    url = os.environ.get('OPEN_SERVICE_TEST_DATABASE_URL')
    schema = 'test_' + uuid4().hex
    if url:
        engine = create_engine(url)
        with engine.begin() as connection:
            connection.execute(text('CREATE SCHEMA ' + schema))
        database_url = make_url(url).update_query_dict({'options': '-csearch_path=' + schema}).render_as_string(hide_password=False)
    else:
        database_url = 'sqlite:///' + str(tmp_path/'test.db')
    application = create_app({'TESTING': True, 'SECRET_KEY': 'test-secret', 'ADMIN_PASSWORD': PASSWORD,
                             'SESSION_COOKIE_SECURE': False, 'DATABASE_URL': database_url})
    yield application
    application.extensions['db'].kw['bind'].dispose()
    if url:
        with engine.begin() as connection:
            connection.execute(text('DROP SCHEMA ' + schema + ' CASCADE'))
        engine.dispose()

@pytest.fixture
def client(app):
    return app.test_client()

@pytest.fixture
def admin(client):
    client.environ_base['HTTP_AUTHORIZATION'] = auth()['Authorization']
    return client

@pytest.fixture
def actors(admin):
    for role in ['agent', 'reader']:
        assert admin.post('/api/openservice/users', json={'name': role, 'password': PASSWORD, 'role': role}).status_code == 201
    return admin


def add(client, table='incident', **fields):
    response = client.post('/api/now/table/'+table, json={'short_description': 'Email unavailable', **fields})
    assert response.status_code == 201, response.json
    return response.json['result']
