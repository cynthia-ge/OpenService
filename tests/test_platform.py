import io
import json
import re
import pytest
from sqlalchemy import select
from werkzeug.exceptions import BadRequest
from openservice.app import create_app
from openservice.catalog import TABLES
from openservice.store import Record, Event, Attachment, initialize
from openservice.service import page
from conftest import auth, add, PASSWORD

API = '/api/now/table/'

def test_bootstrap_and_health(app, client):
    assert client.get('/healthz').json == {'status':'ok'}
    assert client.get('/').status_code == 302
    assert client.get(API+'incident').status_code == 401
    assert 'Basic' in client.get(API+'incident').headers['WWW-Authenticate']
    for name,password in [('nobody',PASSWORD),('admin','wrong')]:
        assert client.get(API+'incident',headers=auth(name,password)).status_code==401
    with pytest.raises(RuntimeError):
        create_app({'SECRET_KEY':None,'ADMIN_PASSWORD':None})
    initialize(app.config['DATABASE_URL'], 'different-password')
    assert client.get(API+'incident',headers=auth()).status_code==200
    assert client.get('/static/app.css').status_code==200
    assert client.get('/dictionary',headers=auth()).status_code==200
    assert client.get('/unknown',headers=auth()).status_code==404

@pytest.mark.parametrize('table',list(TABLES))
def test_record_lifecycle_and_ui(admin,app,table):
    assert admin.get('/workspace/'+table).status_code==200
    assert admin.get('/record/'+table+'/new').status_code==200
    item=add(admin,table)
    assert re.fullmatch('[a-f0-9]{32}', item['sys_id'])
    assert item['number'].startswith(TABLES[table]['prefix'])
    url=API+table+'/'+item['sys_id']
    response=admin.get(url)
    assert response.json['result']==item
    assert response.headers['ETag']=='"0"'
    assert admin.get(url+'?sysparm_fields=sys_id,missing,short_description').json['result']=={'sys_id':item['sys_id'],'short_description':item['short_description']}
    assert admin.get(url+'?sysparm_query=x').status_code==400
    response=admin.patch(url,json={'description':'Updated details'},headers={'If-Match':'"0"'})
    assert response.status_code==200
    assert response.json['result']['sys_mod_count']=='1'
    assert admin.patch(url,json={},headers={'If-Match':'"0"'}).status_code==409
    assert admin.put(url,json={'short_description':'Updated'}).status_code==200
    assert admin.get('/record/'+table+'/'+item['sys_id']).status_code==200
    assert admin.get('/workspace/'+table).status_code==200
    for method in ['patch','delete']:
        assert getattr(admin,method)(url+'?unsupported=true',json={}).status_code==400
    assert admin.delete(url).status_code==204
    assert admin.get(url).status_code==404
    assert admin.get('/audit').status_code==200
    with app.extensions['db']() as db:
        assert db.scalar(select(Event).where(Event.action=='deleted')).record_id==item['sys_id']

@pytest.mark.parametrize('payload',[[],None,{'sys_id':'a'*32},{'short_description':1},{'short_description':'x'*65537},{'short_description':'  '},{'short_description':'x','priority':'0'},{'short_description':'x','state':'impossible'},{'short_description':'x','state':'6'}])
def test_invalid_creation(admin,payload):
    response=admin.post(API+'incident',data=json.dumps(payload),content_type='application/json')
    assert response.status_code==400
    assert admin.get(API+'incident').json['result']==[]


def test_bad_http_bodies_and_tables(admin):
    assert admin.post(API+'incident',data='broken',content_type='application/json').status_code==400
    assert admin.post(API+'incident',data='data').status_code==415
    assert admin.post(API+'incident?x=1',json={}).status_code==400
    assert admin.get(API+'no_table').status_code==404
    assert admin.get(API+'incident/not-an-id').status_code==404
    assert admin.get(API+'no_table/'+'a'*32).status_code==404
    assert admin.patch(API+'incident/'+add(admin)['sys_id'],json={'short_description':''}).status_code==400
    assert admin.post(API+'incident',json={},headers={'Authorization':'Bearer missing'}).status_code==401


def test_queries_pagination_and_projection(admin):
    a=add(admin,short_description='Alpha 100%_exact',priority='1')
    b=add(admin,short_description='Beta',priority='3')
    c=add(admin,short_description="X' OR 1=1 --",priority='2')
    for query,expected in [('priority=1',[a]),('priority!=1',[b,c]),('short_descriptionLIKE%_', [a]),('short_descriptionSTARTSWITHBeta',[b]),('priority=1^short_descriptionLIKEAlpha',[a]),('ORDERBYDESCpriority',[b,c,a]),('ORDERBYpriority',[a,c,b]),("short_description=X' OR 1=1 --",[c]),('sys_id='+b['sys_id'],[b])]:
        response=admin.get(API+'incident',query_string={'sysparm_query':query})
        assert response.status_code==200,response.json
        assert response.json['result']==expected
    response=admin.get(API+'incident?sysparm_limit=1&sysparm_offset=1&sysparm_fields=number')
    assert response.json['result']==[{'number':b['number']}]
    assert response.headers['X-Total-Count']=='3'
    assert 'rel="prev"' in response.headers['Link'] and 'rel="next"' in response.headers['Link']
    assert admin.get(API+'incident?sysparm_offset=99').json['result']==[]
    assert admin.get(API+'incident?sysparm_fields=missing').json['result']==[{}, {}, {}]
    assert admin.get('/?q=priority%3D1').status_code==200
    assert admin.get('/workspace/incident?offset=30').status_code==200

@pytest.mark.parametrize('query',['not-an-expression','unknown=1','ORpriority=1','ORDERBYunknown','ORDERBYDESCunknown','javascript:bad'])
def test_reject_unsupported_queries(admin,query):
    assert admin.get(API+'incident',query_string={'sysparm_query':query}).status_code==400

@pytest.mark.parametrize('query',['sysparm_display_value=true','sysparm_limit=x','sysparm_offset=x','sysparm_limit=0','sysparm_limit=10001','sysparm_offset=-1','sysparm_offset=2147483648'])
def test_reject_invalid_pagination(admin,query):
    assert admin.get(API+'incident?'+query).status_code==400


def test_none_page_argument(app):
    with app.extensions['db']() as db,pytest.raises(BadRequest):
        page(db,'incident',{'sysparm_limit':None})


def test_authorization_and_change_approvals(actors):
    client=actors
    item=add(client,'change_request')
    url=API+'change_request/'+item['sys_id']
    assert client.patch(url,json={'state':'closed'}).status_code==409
    assert client.patch(url,json={'state':'review'},headers=auth('agent')).status_code==200
    assert client.patch(url,json={'state':'approved'},headers=auth('agent')).status_code==403
    assert client.patch(url,json={'state':'approved'}).status_code==200
    assert client.patch(url,json={'state':'approved'}).status_code==200
    assert client.get(url,headers=auth('reader')).status_code==200
    assert client.patch(url,json={'description':'no'},headers=auth('reader')).status_code==403
    assert client.delete(url,headers=auth('agent')).status_code==403
    for path in ['/api/openservice/users','/api/openservice/import/incident','/migration']:
        assert client.post(path,json={},headers=auth('agent')).status_code==403
    assert client.get('/record/change_request/'+item['sys_id'],headers=auth('reader')).status_code==200
    assert client.get('/migration',headers=auth('reader')).status_code==200

@pytest.mark.parametrize('payload',[[],{}, {'name':'a','password':'x','role':'admin'}, {'name':'a b','password':PASSWORD,'role':'agent'}, {'name':'á','password':PASSWORD,'role':'agent'}, {'name':'a'*81,'password':PASSWORD,'role':'agent'},{'name':None,'password':PASSWORD,'role':'agent'},{'name':'joe','password':None,'role':'agent'},{'name':'joe','password':PASSWORD,'role':'superuser'}])
def test_invalid_user(admin,payload):
    assert admin.post('/api/openservice/users',json=payload).status_code==400


def test_duplicate_user(admin):
    assert admin.post('/api/openservice/users',json={'name':'admin','password':PASSWORD,'role':'admin'}).status_code==400
    result=admin.get('/api/openservice/schema').json['result']
    assert set(result)==set(TABLES)


def csrf(client):
    client.get('/login')
    with client.session_transaction() as state:
        return state['csrf']


def test_session_login_csrf_ui_forms(client,app):
    token=csrf(client)
    assert client.post('/login',data={'username':'admin','password':PASSWORD}).status_code==403
    assert client.post('/login',data={'csrf':'不正','username':'admin','password':PASSWORD}).status_code==403
    for name,password in [('missing',PASSWORD),('admin','wrong')]:
        assert client.post('/login',data={'csrf':token,'username':name,'password':password}).status_code==401
    assert client.post('/login',data={'csrf':token,'username':'admin','password':PASSWORD}).status_code==302
    with client.session_transaction() as state: token=state['csrf']
    assert client.post('/record/incident/new',data={'short_description':'No csrf'}).status_code==403
    assert client.post('/record/incident/new',data={'csrf':'不正','short_description':'Unicode csrf'}).status_code==403
    result=client.post('/record/incident/new',data={'csrf':token,'short_description':'UI incident','priority':'2','state':'1'})
    assert result.status_code==302
    location=result.headers['Location']
    assert client.get(location).status_code==200
    assert client.post(location,data={'csrf':token,'short_description':'UI updated','revision':'0'}).status_code==302
    assert client.post(location+'/note',data={'csrf':token,'body':'Internal update'}).status_code==302
    assert client.post(location+'/note',data={'csrf':token,'body':''}).status_code==400
    assert client.post(location+'/note',data={'csrf':token,'body':'x'*65537}).status_code==400
    assert client.get('/audit').status_code==200
    assert client.post('/logout',data={'csrf':token}).status_code==302
    assert client.get('/').status_code==302


def test_attachments(admin,app):
    item=add(admin)
    url='/record/incident/'+item['sys_id']+'/attachment'
    assert admin.post(url).status_code==400
    assert admin.post(url,data={'file':(io.BytesIO(b'x'),'')}).status_code==400
    assert admin.post(url,data={'file':(io.BytesIO(b'x'*(5*1024*1024+1)),'large.txt')}).status_code==400
    for filename in ['../evidence.txt','???']:
        assert admin.post(url,data={'file':(io.BytesIO(b'<script>hello</script>'),filename)}).status_code==302
    with app.extensions['db']() as db: files=db.scalars(select(Attachment)).all()
    assert {f.name for f in files}=={'evidence.txt','attachment'}
    response=admin.get('/attachment/'+files[0].sys_id)
    assert response.data==b'<script>hello</script>'
    assert response.headers['Content-Disposition'].startswith('attachment;')
    assert response.headers['X-Content-Type-Options']=='nosniff'
    assert admin.get('/attachment/missing').status_code==404
    assert admin.get('/record/incident/'+item['sys_id']).status_code==200
    assert admin.delete(API+'incident/'+item['sys_id']).status_code==204
    assert admin.get('/attachment/'+files[0].sys_id).status_code==404
    assert admin.post(url,data={'file':(io.BytesIO(b'x'),'a')}).status_code==404


def test_reader_can_sign_out(actors,app):
    client=app.test_client()
    token=csrf(client)
    assert client.post('/login',data={'csrf':token,'username':'reader','password':PASSWORD}).status_code==302
    with client.session_transaction() as state: token=state['csrf']
    assert client.post('/logout',data={'csrf':token}).status_code==302


def test_friendly_search_is_literal(admin):
    item=add(admin,short_description='100%_special ^ OR')
    add(admin,short_description='100 normal')
    response=admin.get('/workspace/incident',query_string={'search':'%_special ^'})
    assert response.status_code==200
    assert item['number'].encode() in response.data
    assert b'100 normal' not in response.data
