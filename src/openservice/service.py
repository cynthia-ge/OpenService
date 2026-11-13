"""Record, workflow, query and migration behavior shared by UI and API."""
import re
from sqlalchemy import select, delete, func
from werkzeug.exceptions import BadRequest, Conflict, Forbidden, NotFound
from .catalog import SYSTEM
from .store import Schema, Record, Event, Attachment, audit, now, serialize


def schema(db, name):
    item = db.get(Schema, name)
    if item is None:
        raise NotFound("Unknown table")
    return item.definition


def record(db, table, identifier):
    item = db.scalar(select(Record).where(Record.table == table, Record.sys_id == identifier).with_for_update())
    if item is None:
        raise NotFound("Record not found")
    return item


def validate(definition, payload):
    if not isinstance(payload, dict):
        raise BadRequest("A JSON object is required")
    invalid = set(payload) - set(definition["fields"])
    if invalid:
        raise BadRequest("Unknown or read-only fields: " + ", ".join(sorted(invalid)))
    if any(not isinstance(value, str) for value in payload.values()):
        raise BadRequest("Field values must be strings")
    if any(len(value) > 65536 for value in payload.values()):
        raise BadRequest("Field exceeds 65536 characters")
    if "priority" in payload and payload["priority"] not in ["1", "2", "3", "4", "5"]:
        raise BadRequest("Priority must be 1–5")
    if "state" in payload and payload["state"] not in definition["states"]:
        raise BadRequest("Unknown state")


def create(db, table, payload, actor):
    definition = schema(db, table)
    validate(definition, payload)
    if not payload.get("short_description", "").strip():
        raise BadRequest("Summary is required")
    data = dict.fromkeys(definition["fields"], "")
    if definition["states"]:
        data["state"] = next(iter(definition["states"]))
    if "priority" in data:
        data["priority"] = "3"
    if "state" in payload and payload["state"] != data["state"]:
        raise BadRequest("Create in the initial state, then use a transition")
    data.update(payload)
    item = Record(table=table, data=data, creator=actor, updater=actor)
    db.add(item)
    db.flush()
    item.data = {**data, "number": f'{definition["prefix"]}{item.seq:07d}'}
    audit(db, item, actor, "created", {"after": serialize(item)})
    return item


def update(db, table, item, payload, actor, role, revision=None):
    definition = schema(db, table)
    validate(definition, payload)
    if revision is not None and str(item.revision) != revision:
        raise Conflict("Record changed; reload before saving")
    if "short_description" in payload and not payload["short_description"].strip():
        raise BadRequest("Summary is required")
    if "state" in payload and payload["state"] != item.data["state"]:
        if payload["state"] not in definition["flow"].get(item.data["state"], []):
            raise Conflict("Transition is not allowed")
        if table == "change_request" and payload["state"] in ["approved", "rejected"] and role != "admin":
            raise Forbidden("An administrator must approve or reject changes")
    before = serialize(item)
    item.data = {**item.data, **payload}
    item.updated, item.updater = now(), actor
    item.revision += 1
    audit(db, item, actor, "updated", {"before": before, "after": serialize(item)})
    return item


def remove(db, item, actor):
    audit(db, item, actor, "deleted", {"before": serialize(item)})
    db.execute(delete(Attachment).where(Attachment.record_id == item.sys_id))
    db.delete(item)


def selection(db, table, encoded=""):
    definition = schema(db, table)
    columns = {name: Record.data[name].as_string() for name in definition["fields"]}
    columns.update({"number": Record.data["number"].as_string(), "sys_id": Record.sys_id, "sys_created_on": Record.created, "sys_updated_on": Record.updated})
    query = select(Record).where(Record.table == table)
    ordering = []
    for term in filter(None, encoded.split("^")):
        if term.startswith("ORDERBY"):
            descending = term.startswith("ORDERBYDESC")
            field = term[11:] if descending else term[7:]
            if field not in columns:
                raise BadRequest("Unknown ordering field")
            ordering.append(columns[field].desc() if descending else columns[field].asc())
            continue
        match = re.fullmatch(r'([a-z_][a-z_0-9]*)(!=|=|LIKE|STARTSWITH|NOT IN|IN)(.*)', term)
        if not match or match[1] not in columns:
            raise BadRequest("Supported query subset: field=value, !=, LIKE, STARTSWITH, IN, NOT IN, AND (^), ORDERBY[DESC]")
        field, operator, value = match.groups()
        col = columns[field]
        if operator in ('IN', 'NOT IN'):
            values = value.split(',')
            if len(values) > 100 or any(v == '' for v in values):
                raise BadRequest('Membership requires 1–100 nonempty comma-separated values')
            condition = col.in_(values)
            query = query.where(~condition if operator == 'NOT IN' else condition)
            continue
        conditions = {"=": lambda: col == value, "!=": lambda: col != value,
                      "LIKE": lambda: col.contains(value, autoescape=True),
                      "STARTSWITH": lambda: col.startswith(value, autoescape=True)}
        query = query.where(conditions[operator]())
    return query.order_by(*ordering, Record.seq)


def page(db, table, args, search=""):
    supported = {"sysparm_query", "sysparm_limit", "sysparm_offset", "sysparm_fields"}
    if set(args) - supported:
        raise BadRequest("Unsupported query parameter")
    try:
        limit, offset = int(args.get("sysparm_limit", 10000)), int(args.get("sysparm_offset", 0))
    except (ValueError, TypeError):
        raise BadRequest("Limit and offset must be integers") from None
    if not 1 <= limit <= 10000 or not 0 <= offset <= 2147483647:
        raise BadRequest("Limit must be 1–10000 and offset 0–2147483647")
    query = selection(db, table, args.get("sysparm_query", ""))
    if search:
        query = query.where(Record.data["short_description"].as_string().contains(search, autoescape=True))
    total = db.scalar(select(func.count()).select_from(query.order_by(None).subquery()))
    items = db.scalars(query.limit(limit).offset(offset)).all()
    fields = args.get("sysparm_fields", "")
    return [project(serialize(item), fields) for item in items], total, limit, offset


def project(data, fields):
    if not fields:
        return data
    names = [name.strip() for name in fields.split(",")]
    return {name: data[name] for name in names if name in data}


def migrate(db, table, rows, actor, dry_run=True):
    definition = schema(db, table)
    if isinstance(rows, dict):
        rows = rows.get("result")
    if not isinstance(rows, list) or len(rows) > 10000:
        raise BadRequest("Import a result array or list with at most 10000 records")
    report = {"total": len(rows), "created": 0, "skipped": 0, "errors": [], "preserved_fields": [], "dry_run": dry_run}
    pending, seen, extra = [], set(), set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or not re.fullmatch('[a-f0-9]{32}', str(row.get('sys_id', ''))):
            report["errors"].append({"row": index + 1, "message": "A 32-character lowercase hex sys_id is required"})
            continue
        identifier = row["sys_id"]
        if identifier in seen:
            report["errors"].append({"row": index + 1, "message": "Duplicate sys_id in import"})
            continue
        seen.add(identifier)
        existing = db.scalar(select(Record).where(Record.sys_id == identifier))
        if existing:
            if existing.table != table:
                report["errors"].append({"row": index + 1, "message": "sys_id belongs to a different table"})
            else:
                report["skipped"] += 1
            continue
        normalized = {}
        for key in definition["fields"]:
            value = row.get(key, "")
            if isinstance(value, dict):
                value = value.get("value", "")
            normalized[key] = "" if value is None else str(value)
        normalized["number"] = str(row.get("number", definition["prefix"] + identifier[:8].upper()))
        extra.update(set(row) - set(definition["fields"]) - SYSTEM)
        pending.append(Record(sys_id=identifier, table=table, data=normalized, original=row,
                              created=str(row.get("sys_created_on", now())), updated=str(row.get("sys_updated_on", now())),
                              creator=str(row.get("sys_created_by", actor)), updater=str(row.get("sys_updated_by", actor))))
    report["preserved_fields"] = sorted(extra)
    report["created"] = len(pending)
    if not dry_run and not report["errors"]:
        for item in pending:
            db.add(item)
            audit(db, item, actor, "imported", {"source": "ServiceNow JSON", "original_preserved": True})
    return report
