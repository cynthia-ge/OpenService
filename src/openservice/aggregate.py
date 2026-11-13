"""Read-only count aggregates, filtered and grouped by stored field values."""
from flask import Blueprint, g, jsonify, request
from sqlalchemy import func, select
from werkzeug.exceptions import BadRequest
from .service import schema, selection

api = Blueprint('aggregate', __name__)


@api.get('/api/now/stats/<table>')
def stats(table):
    args = request.args
    allowed = {'sysparm_count', 'sysparm_query', 'sysparm_group_by', 'sysparm_order_by', 'sysparm_display_value'}
    if set(args) - allowed or any(len(args.getlist(key)) != 1 for key in args):
        raise BadRequest('Unsupported or repeated aggregate parameter')
    if args.get('sysparm_count') != 'true' or args.get('sysparm_display_value', 'false') != 'false':
        raise BadRequest('This subset requires sysparm_count=true and stored values')
    definition = schema(g.db, table)
    source = selection(g.db, table, args.get('sysparm_query', '')).order_by(None).subquery()
    columns = {name: func.coalesce(source.c.data[name].as_string(), '') for name in definition['fields']}
    columns.update(number=source.c.data['number'].as_string(), sys_id=source.c.sys_id,
                   sys_created_on=source.c.created, sys_updated_on=source.c.updated)
    groups = args.get('sysparm_group_by', '').split(',') if args.get('sysparm_group_by') else []
    if len(set(groups)) != len(groups) or set(groups) - set(columns):
        raise BadRequest('Unknown or duplicate grouping field')
    count = func.count()
    expressions = [columns[name] for name in groups]
    query = select(count, *expressions).select_from(source).group_by(*expressions)
    order = args.get('sysparm_order_by', '')
    if order:
        field = order.removesuffix('^DESC')
        if not groups or field not in ['COUNT', *groups]:
            raise BadRequest('Order by COUNT or a grouping field, optionally followed by ^DESC')
        column = count if field == 'COUNT' else columns[field]
        query = query.order_by(column.desc() if order.endswith('^DESC') else column.asc())
    query = query.order_by(*expressions)
    result = []
    for row in g.db.execute(query):
        value = {'stats': {'count': str(row[0])}}
        if groups:
            value['groupby_fields'] = [{'field': name, 'value': row[index+1]} for index, name in enumerate(groups)]
        result.append(value)
    return jsonify(result=result if groups else result[0])
