import pytest
from conftest import add, auth


def test_membership_table_stats_and_pagination(admin, actors):
    records = [add(admin, priority=p, assignment_group=g) for p, g in [('1', 'ops'), ('2', 'ops'), ('3', 'dev'), ('4', '')]]
    add(admin, table='problem', priority='1')
    table = '/api/now/table/incident'
    stats = '/api/now/stats/incident'
    for query, expected in [('priorityIN1,2', records[:2]), ('priorityNOT IN1,2', records[2:]),
                            ('priorityIN1,1,3', [records[0], records[2]]),
                            ('priorityIN9', []), ('priorityNOT IN9', records),
                            ('priorityIN1,2,3^assignment_group=ops^ORDERBYDESCpriority', [records[1], records[0]]),
                            ('sys_idIN'+records[0]['sys_id']+','+records[3]['sys_id'], [records[0], records[3]])]:
        response = admin.get(table, query_string={'sysparm_query': query}, headers=auth('reader'))
        assert response.status_code == 200, response.json
        assert response.json['result'] == expected
        count = admin.get(stats, query_string={'sysparm_query': query, 'sysparm_count': 'true'})
        assert count.json['result']['stats']['count'] == str(len(expected))
    response = admin.get(table, query_string={'sysparm_query': 'priorityIN1,2,3', 'sysparm_limit': '1', 'sysparm_offset': '1'})
    assert response.json['result'] == [records[1]] and response.headers['X-Total-Count'] == '3'
    grouped = admin.get(stats, query_string={'sysparm_query': 'priorityIN1,2,3', 'sysparm_count': 'true', 'sysparm_group_by': 'assignment_group'})
    assert sorted(r['stats']['count'] for r in grouped.json['result']) == ['1', '2']
    assert admin.get(table, query_string={'sysparm_query': 'priorityIN1'}, headers=auth('missing')).status_code == 401


def test_membership_values_are_literal(admin):
    for text in ["x' OR 1=1 --", '%_', 'with space', 'with']:
        add(admin, short_description=text)
    table = '/api/now/table/incident'
    for text in ["x' OR 1=1 --", '%_', 'with space']:
        response = admin.get(table, query_string={'sysparm_query': 'short_descriptionIN'+text})
        assert response.status_code == 200
        assert [r['short_description'] for r in response.json['result']] == [text]
    assert len(admin.get(table, query_string={'sysparm_query': 'short_descriptionIN'+','.join(['with']*100)}).json['result']) == 1


@pytest.mark.parametrize('query', ['priorityIN', 'priorityNOT IN', 'priorityIN1,', 'priorityIN,1',
                                  'priorityIN1,,2', 'unknownIN1', 'priorityNOTIN1',
                                  'priorityIN'+','.join(['1']*101)])
def test_bad_membership_rejected_for_both_apis(admin, query):
    for path, extra in [('/api/now/table/incident', {}), ('/api/now/stats/incident', {'sysparm_count': 'true'})]:
        response = admin.get(path, query_string={'sysparm_query': query, **extra})
        assert response.status_code == 400
        assert response.json['status'] == 'failure'
