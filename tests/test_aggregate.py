import pytest
from conftest import add, auth


def test_count_and_filtered_groups(admin, actors):
    url = '/api/now/stats/incident'
    assert admin.get(url + '?sysparm_count=true').json == {'result': {'stats': {'count': '0'}}}
    for priority, group in [('1', 'network'), ('1', 'network'), ('2', ''), ('3', 'network')]:
        add(admin, priority=priority, assignment_group=group)
    add(admin, table='problem')
    assert admin.get(url + '?sysparm_count=true').json['result']['stats']['count'] == '4'
    response = admin.get(url, query_string={'sysparm_count': 'true', 'sysparm_group_by': 'priority,assignment_group', 'sysparm_query': 'priority!=3'})
    assert response.json['result'] == [
        {'stats': {'count': '2'}, 'groupby_fields': [{'field': 'priority', 'value': '1'}, {'field': 'assignment_group', 'value': 'network'}]},
        {'stats': {'count': '1'}, 'groupby_fields': [{'field': 'priority', 'value': '2'}, {'field': 'assignment_group', 'value': ''}]}]
    response = admin.get(url, query_string={'sysparm_count': 'true', 'sysparm_group_by': 'priority', 'sysparm_order_by': 'COUNT^DESC'}, headers=auth('reader'))
    assert [r['stats']['count'] for r in response.json['result']] == ['2', '1', '1']
    assert admin.get(url+'?sysparm_count=true&sysparm_group_by=priority&sysparm_order_by=priority^DESC').json['result'][0]['groupby_fields'][0]['value'] == '3'
    assert admin.get(url+'?sysparm_count=true&sysparm_group_by=priority&sysparm_order_by=COUNT').json['result'][0]['stats']['count'] == '1'
    assert admin.get(url+'?sysparm_count=true&sysparm_query=priority=5&sysparm_group_by=priority').json == {'result': []}
    assert admin.get(url+'?sysparm_count=true&sysparm_query=priority=5').json['result']['stats']['count'] == '0'
    assert admin.get(url+'?sysparm_count=true&sysparm_display_value=false').status_code == 200
    assert admin.get(url+'?sysparm_count=true&sysparm_group_by=number&sysparm_order_by=number').status_code == 200
    assert admin.get(url+'?sysparm_count=true&sysparm_group_by=sys_id').status_code == 200


@pytest.mark.parametrize('query', ['', 'sysparm_count=false', 'sysparm_count=yes', 'sysparm_count=true&sysparm_count=false',
    'sysparm_count=true&sysparm_sum_fields=priority', 'sysparm_count=true&sysparm_group_by=unknown',
    'sysparm_count=true&sysparm_group_by=priority,priority', 'sysparm_count=true&sysparm_group_by=priority,',
    'sysparm_count=true&sysparm_display_value=all', 'sysparm_count=true&sysparm_order_by=priority',
    'sysparm_count=true&sysparm_group_by=priority&sysparm_order_by=state',
    'sysparm_count=true&sysparm_group_by=priority&sysparm_order_by=priority^BAD', 'sysparm_count=true&sysparm_query=unknown=value'])
def test_invalid_aggregate(admin, query):
    response = admin.get('/api/now/stats/incident?' + query)
    assert response.status_code == 400
    assert response.json['status'] == 'failure'


def test_auth_and_table(client, admin):
    assert client.get('/api/now/stats/incident?sysparm_count=true', headers=auth('missing')).status_code == 401
    assert admin.get('/api/now/stats/missing?sysparm_count=true').status_code == 404
    assert admin.post('/api/now/stats/incident?sysparm_count=true').status_code == 405


def test_count_exceeds_table_page_limit(app, admin):
    from sqlalchemy import insert
    from openservice.store import Record
    with app.extensions['db'].begin() as db:
        db.execute(insert(Record), [{'table': 'incident', 'data': {'priority': '1'},
                                    'creator': 'admin', 'updater': 'admin'} for _ in range(10001)])
    assert len(admin.get('/api/now/table/incident').json['result']) == 10000
    assert admin.get('/api/now/stats/incident?sysparm_count=true').json == {'result': {'stats': {'count': '10001'}}}
