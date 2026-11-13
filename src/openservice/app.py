"""Authenticated HTTP application: workspaces, Table API and migration workbench."""
import csv
import io
import json
import os
import secrets
from urllib.parse import urlencode
from flask import Flask, Response, g, jsonify, redirect, render_template, request, session, url_for, flash
from sqlalchemy import select, func
from werkzeug.exceptions import BadRequest, Forbidden, HTTPException, NotFound, Unauthorized
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.utils import secure_filename
from .store import initialize, User, Schema, Record, Event, Attachment, serialize, audit
from .service import schema, record, create, update, remove, page, project, migrate


def create_app(config=None):
    app = Flask(__name__)
    app.config.update(SECRET_KEY=os.environ.get("SECRET_KEY"), ADMIN_PASSWORD=os.environ.get("ADMIN_PASSWORD"),
                      DATABASE_URL=os.environ.get("DATABASE_URL", "sqlite:///openservice.db"),
                      MAX_CONTENT_LENGTH=6 * 1024 * 1024, SESSION_COOKIE_HTTPONLY=True,
                      SESSION_COOKIE_SAMESITE="Lax", SESSION_COOKIE_SECURE=os.environ.get("COOKIE_SECURE", "true") == "true")
    app.config.update(config or {})
    if not app.config["SECRET_KEY"] or not app.config["ADMIN_PASSWORD"]:
        raise RuntimeError("Set SECRET_KEY and ADMIN_PASSWORD before starting OpenService")
    app.extensions["db"] = initialize(app.config["DATABASE_URL"], app.config["ADMIN_PASSWORD"])

    @app.before_request
    def before():
        g.db = app.extensions["db"]()
        session.setdefault("csrf", secrets.token_urlsafe(24))
        if request.endpoint in ["health", "login", "static"]:
            return None
        auth = request.authorization
        user = g.db.get(User, session.get("user", ""))
        if auth:
            user = g.db.get(User, auth.username or "")
            if not user or not check_password_hash(user.password, auth.password or ""):
                raise Unauthorized("Invalid credentials")
        if user is None:
            if request.path.startswith("/api/"):
                raise Unauthorized("Authentication required")
            return redirect(url_for("login"))
        g.user = user
        if request.method not in ["GET", "HEAD", "OPTIONS"]:
            if not auth and not secrets.compare_digest(request.headers.get("X-CSRF-Token", request.form.get("csrf", "")).encode(), session["csrf"].encode()):
                raise Forbidden("Invalid CSRF token")
            if user.role == "reader" and request.endpoint != "logout":
                raise Forbidden("Read-only account")
        return None

    @app.teardown_request
    def teardown(error):
        db = g.pop("db", None)
        if db is not None:
            db.close()

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'; base-uri 'self'"
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.errorhandler(HTTPException)
    def error(exc):
        if request.path.startswith("/api/"):
            response = jsonify(error={"message": exc.name, "detail": exc.description}, status="failure")
        else:
            response = app.make_response(render_template("error.html", error=exc))
        response.status_code = exc.code
        if exc.code == 401:
            response.headers["WWW-Authenticate"] = 'Basic realm="OpenService"'
        return response

    @app.get("/healthz")
    def health():
        g.db.execute(select(1))
        return {"status": "ok"}

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if request.method == "POST":
            if not secrets.compare_digest(request.form.get("csrf", "").encode(), session["csrf"].encode()):
                raise Forbidden("Invalid CSRF token")
            user = g.db.get(User, request.form.get("username", ""))
            if not user or not check_password_hash(user.password, request.form.get("password", "")):
                raise Unauthorized("Incorrect username or password")
            session.clear()
            session.update(user=user.name, csrf=secrets.token_urlsafe(24))
            return redirect(url_for("workspace"))
        return render_template("login.html")

    @app.post("/logout")
    def logout():
        session.clear()
        return redirect(url_for("login"))

    def context(**kwargs):
        tables = g.db.scalars(select(Schema).order_by(Schema.name)).all()
        counts = dict(g.db.execute(select(Record.table, func.count()).group_by(Record.table)).all())
        return {"tables": tables, "counts": counts, "user": g.user, **kwargs}

    @app.get("/")
    @app.get("/workspace/<table>")
    def workspace(table=None):
        chosen = table or "incident"
        definition = schema(g.db, chosen)
        rows, total, limit, offset = page(g.db, chosen, {"sysparm_query": request.args.get("q", ""), "sysparm_limit": "30", "sysparm_offset": request.args.get("offset", "0")}, search=request.args.get("search", ""))
        return render_template("workspace.html", **context(table=chosen, definition=definition, rows=rows, total=total, offset=offset, overview=table is None))

    @app.route("/record/<table>/<identifier>", methods=["GET", "POST"])
    def detail(table, identifier):
        definition = schema(g.db, table)
        item = None if identifier == "new" else record(g.db, table, identifier)
        if request.method == "POST":
            payload = {key: value for key, value in request.form.items() if key in definition["fields"]}
            if item is None:
                item = create(g.db, table, payload, g.user.name)
            else:
                update(g.db, table, item, payload, g.user.name, g.user.role, request.form.get("revision"))
            g.db.commit()
            flash("Record saved. Everything is up to date.")
            return redirect(url_for("detail", table=table, identifier=item.sys_id))
        events, attachments = [], []
        if item is not None:
            events = g.db.scalars(select(Event).where(Event.record_id == identifier).order_by(Event.seq.desc())).all()
            attachments = g.db.scalars(select(Attachment).where(Attachment.record_id == identifier)).all()
        return render_template("detail.html", **context(table=table, definition=definition, item=item, data=serialize(item) if item else {}, events=events, attachments=attachments))

    @app.post("/record/<table>/<identifier>/note")
    def note(table, identifier):
        item = record(g.db, table, identifier)
        body = request.form.get("body", "").strip()
        if not body or len(body) > 65536:
            raise BadRequest("Enter a note of 1–65536 characters")
        audit(g.db, item, g.user.name, "note", {"body": body})
        g.db.commit()
        return redirect(url_for("detail", table=table, identifier=identifier))

    @app.post("/record/<table>/<identifier>/attachment")
    def upload(table, identifier):
        item = record(g.db, table, identifier)
        file = request.files.get("file")
        if file is None or not file.filename:
            raise BadRequest("Choose a file")
        content = file.read(5 * 1024 * 1024 + 1)
        if len(content) > 5 * 1024 * 1024:
            raise BadRequest("Attachments are limited to 5 MB")
        name = secure_filename(file.filename) or "attachment"
        g.db.add(Attachment(record_id=item.sys_id, name=name, content=content))
        audit(g.db, item, g.user.name, "attachment", {"name": name})
        g.db.commit()
        return redirect(url_for("detail", table=table, identifier=identifier))

    @app.get("/attachment/<identifier>")
    def download(identifier):
        item = g.db.get(Attachment, identifier)
        if item is None:
            raise NotFound("Attachment not found")
        return Response(item.content, mimetype="application/octet-stream", headers={"Content-Disposition": f'attachment; filename="{item.name}"'})

    @app.route("/migration", methods=["GET", "POST"])
    def migration():
        report = None
        if request.method == "POST":
            if g.user.role != "admin":
                raise Forbidden("Only administrators can import")
            file = request.files.get("file")
            if file is None:
                raise BadRequest("Choose a JSON or CSV export")
            try:
                text = file.read().decode("utf-8-sig")
                rows = list(csv.DictReader(io.StringIO(text))) if file.filename.lower().endswith(".csv") else json.loads(text)
            except (ValueError, UnicodeError):
                raise BadRequest("The file is not valid UTF-8 JSON or CSV") from None
            report = migrate(g.db, request.form.get("table", "incident"), rows, g.user.name, request.form.get("mode") != "import")
            g.db.commit()
        return render_template("migration.html", **context(table="migration", report=report))

    @app.get("/export/<table>")
    def export(table):
        schema(g.db, table)
        rows = g.db.scalars(select(Record).where(Record.table == table).order_by(Record.seq)).all()
        data = [item.original or serialize(item) for item in rows] if request.args.get("source") == "true" else [{**item.original, **serialize(item)} for item in rows]
        return Response(json.dumps({"result": data}), mimetype="application/json", headers={"Content-Disposition": f'attachment; filename="{table}.json"'})

    @app.get("/audit")
    def audit_view():
        events = g.db.scalars(select(Event).order_by(Event.seq.desc()).limit(200)).all()
        return render_template("audit.html", **context(table="audit", events=events))

    @app.route("/api/now/table/<table>", methods=["GET", "POST"])
    def collection(table):
        if request.method == "POST":
            if request.args:
                raise BadRequest("Write query parameters are not yet supported")
            item = create(g.db, table, request.get_json(), g.user.name)
            g.db.commit()
            return jsonify(result=serialize(item)), 201, {"Location": url_for("resource", table=table, identifier=item.sys_id, _external=True)}
        rows, total, limit, offset = page(g.db, table, request.args)
        links = []
        for relation, index in [("first", 0), ("last", max(0, (total - 1) // limit * limit)), ("prev", offset - limit), ("next", offset + limit)]:
            if relation == "prev" and index < 0 or relation == "next" and index >= total:
                continue
            args = {**request.args, "sysparm_limit": limit, "sysparm_offset": index}
            links.append(f'<{request.base_url}?{urlencode(args)}>; rel="{relation}"')
        return jsonify(result=rows), 200, {"X-Total-Count": str(total), "Link": ", ".join(links)}

    @app.route("/api/now/table/<table>/<identifier>", methods=["GET", "PATCH", "PUT", "DELETE"])
    def resource(table, identifier):
        schema(g.db, table)
        item = record(g.db, table, identifier)
        if request.method == "GET":
            if set(request.args) - {"sysparm_fields"}:
                raise BadRequest("Unsupported query parameter")
            return jsonify(result=project(serialize(item), request.args.get("sysparm_fields", ""))), 200, {"ETag": f'"{item.revision}"'}
        if request.args:
            raise BadRequest("Write query parameters are not yet supported")
        if request.method == "DELETE":
            if g.user.role != "admin":
                raise Forbidden("Only administrators can delete records")
            remove(g.db, item, g.user.name)
            g.db.commit()
            return "", 204
        revision = request.headers.get("If-Match")
        if revision is not None:
            revision = revision.strip('"')
        update(g.db, table, item, request.get_json(), g.user.name, g.user.role, revision)
        g.db.commit()
        return jsonify(result=serialize(item))

    @app.get("/dictionary")
    def dictionary():
        return render_template("dictionary.html", **context(table="dictionary"))

    @app.get("/api/openservice/schema")
    def schemas():
        return jsonify(result={item.name: item.definition for item in g.db.scalars(select(Schema)).all()})

    @app.post("/api/openservice/users")
    def users():
        if g.user.role != "admin":
            raise Forbidden("Only administrators can manage users")
        payload = request.get_json()
        if not isinstance(payload, dict) or set(payload) != {"name", "password", "role"}:
            raise BadRequest("Provide name, password and role")
        name, password, role = payload["name"], payload["password"], payload["role"]
        if not isinstance(name, str) or not name.isascii() or not name.isalnum() or len(name) > 80 or not isinstance(password, str) or len(password) < 12 or role not in ["admin", "agent", "reader"]:
            raise BadRequest("Use an alphanumeric name, a password of at least 12 characters, and admin/agent/reader role")
        if g.db.get(User, name):
            raise BadRequest("User already exists")
        g.db.add(User(name=name, password=generate_password_hash(password), role=role))
        g.db.commit()
        return jsonify(result={"name": name, "role": role}), 201

    @app.post("/api/openservice/import/<table>")
    def api_import(table):
        if g.user.role != "admin":
            raise Forbidden("Only administrators can import")
        if set(request.args) - {"dry_run"} or request.args.get("dry_run", "true") not in ["true", "false"]:
            raise BadRequest("Use dry_run=true or false")
        report = migrate(g.db, table, request.get_json(), g.user.name, request.args.get("dry_run", "true") == "true")
        g.db.commit()
        return jsonify(report), 400 if report["errors"] else 200

    return app
