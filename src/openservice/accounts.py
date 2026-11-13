"""Local account creation and bounded administrator listings."""
import re
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from werkzeug.exceptions import BadRequest
from werkzeug.security import generate_password_hash
from .store import User

PAGE_SIZE = 25
ROLES = {
    "reader": ("Reader", "View records, activity and attachments. Cannot change records."),
    "agent": ("Agent", "Create and update records, add notes and upload attachments."),
    "admin": ("Administrator", "Manage accounts, import data, approve changes and delete records."),
}


def create_account(db, payload):
    if not isinstance(payload, dict) or payload.keys() != {"name", "password", "role"}:
        raise BadRequest("Provide name, password and role")
    name, password, role = payload["name"], payload["password"], payload["role"]
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9]{1,80}", name):
        raise BadRequest("Username must contain 1–80 ASCII letters or digits")
    if not isinstance(password, str) or not 12 <= len(password) <= 1024:
        raise BadRequest("Password must contain 12–1024 characters")
    if not isinstance(role, str) or role not in ROLES:
        raise BadRequest("Choose an admin, agent or reader role")
    if db.get(User, name) is not None:
        raise BadRequest("User already exists")
    account = User(name=name, password=generate_password_hash(password), role=role)
    db.add(account)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise BadRequest("User already exists") from None
    return account


def account_page(db, arguments):
    if set(arguments) - {"page"} or len(arguments.getlist("page")) > 1:
        raise BadRequest("Use a single page parameter")
    value = arguments.get("page", "1")
    if not re.fullmatch(r"[1-9][0-9]{0,5}", value):
        raise BadRequest("Page must be a positive integer of at most six digits")
    number = int(value)
    total = db.scalar(select(func.count()).select_from(User))
    pages = max(1, (total + PAGE_SIZE - 1) // PAGE_SIZE)
    if number > pages:
        raise BadRequest("Page is outside the account list")
    rows = db.execute(select(User.name, User.role).order_by(User.name).offset((number - 1) * PAGE_SIZE).limit(PAGE_SIZE)).all()
    return rows, total, number, pages
