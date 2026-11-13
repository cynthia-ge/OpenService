import io
import json
import pytest
from sqlalchemy import select
from openservice.store import Record, Event
from conftest import add

SOURCE={'sys_id':'a'*32,'number':'INC0099999','short_description':'Preserve me','state':'1','priority':'2',
        'sys_created_on':'2020-01-02 03:04:05','sys_created_by':'original.author',
        'assigned_to':{'value':'b'*32,'display_value':'Alice'},'u_custom_field':'Keep this too', 'description':None}

def test_preview_commit_idempotency_and_original(admin,app):
    url='/api/openservice/import/incident'
    response=admin.post(url,json={'result':[SOURCE]})
    assert response.json['dry_run'] and response.json['created']==1
    assert admin.get('/api/now/table/incident').json['result']==[]
    report=admin.post(url+'?dry_run=false',json=[SOURCE]).json
    assert report['created']==1 and report['preserved_fields']==['u_custom_field']
    item=admin.get('/api/now/table/incident/'+'a'*32).json['result']
    assert item['sys_created_on']==SOURCE['sys_created_on']
    assert item['sys_created_by']==SOURCE['sys_created_by']
    assert item['assigned_to']=='b'*32
    assert item['description']==''
    assert admin.post(url+'?dry_run=false',json=[SOURCE]).json['skipped']==1
    exported=admin.get('/export/incident').json['result'][0]
    assert exported['u_custom_field']=='Keep this too'
    assert admin.get('/export/incident?source=true').json['result']==[SOURCE]
    with app.extensions['db']() as db:
        assert db.scalar(select(Record)).original==SOURCE
        assert [event.action for event in db.scalars(select(Event))]==['imported']
    assert admin.post('/api/openservice/import/problem?dry_run=false',json=[SOURCE]).status_code==400

@pytest.mark.parametrize('rows',[{},None,'text',[{}],['bad'],[{**SOURCE,'sys_id':'wrong'}],[SOURCE,SOURCE],[SOURCE,{}],list(range(10001))])
def test_bad_import_never_partially_writes(admin,rows):
    response=admin.post('/api/openservice/import/incident?dry_run=false',data=json.dumps(rows),content_type='application/json')
    assert response.status_code==400
    assert admin.get('/api/now/table/incident').json['result']==[]


def test_import_defaults_empty_and_arguments(admin):
    assert admin.post('/api/openservice/import/incident',json=[]).json['total']==0
    assert admin.post('/api/openservice/import/incident?dry_run=maybe',json=[]).status_code==400
    assert admin.post('/api/openservice/import/incident?wrong=true',json=[]).status_code==400
    assert admin.post('/api/openservice/import/missing',json=[]).status_code==404
    rows=[{'sys_id':'c'*32,'short_description':123,'assigned_to':{}}]
    assert admin.post('/api/openservice/import/incident?dry_run=false',json=rows).status_code==200
    item=admin.get('/api/now/table/incident/'+'c'*32).json['result']
    assert item['short_description']=='123' and item['number'].startswith('INC')


def test_migration_ui_json_csv_and_errors(admin):
    assert admin.get('/migration').status_code==200
    assert admin.post('/migration').status_code==400
    assert admin.post('/migration',data={'file':(io.BytesIO(b'broken'),'x.json')}).status_code==400
    assert admin.post('/migration',data={'file':(io.BytesIO(b'\xff'),'x.csv')}).status_code==400
    response=admin.post('/migration',data={'file':(io.BytesIO(json.dumps([SOURCE]).encode()),'x.json'),'table':'incident'})
    assert response.status_code==200 and b'Dry-run report' in response.data
    assert admin.get('/api/now/table/incident').json['result']==[]
    csv=('sys_id,short_description\n'+'d'*32+',Migrated from CSV\n').encode()
    response=admin.post('/migration',data={'file':(io.BytesIO(csv),'x.csv'),'table':'incident','mode':'import'})
    assert response.status_code==200 and b'Import complete' in response.data
    assert len(admin.get('/api/now/table/incident').json['result'])==1
    assert admin.get('/export/incident').status_code==200
    assert admin.get('/export/missing').status_code==404
    response=admin.post('/migration',data={'file':(io.BytesIO(b'[{}]'),'x.json'),'table':'incident','mode':'import'})
    assert b'Nothing was imported' in response.data


def test_source_export_for_native_records(admin):
    item=add(admin)
    assert admin.get('/export/incident?source=true').json['result']==[item]
