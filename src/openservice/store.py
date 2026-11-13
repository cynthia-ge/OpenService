"""Shared relational storage for local SQLite and managed PostgreSQL."""
from datetime import datetime, timezone
from uuid import uuid4
from sqlalchemy import JSON, Integer, LargeBinary, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker
from werkzeug.security import generate_password_hash
from .catalog import TABLES


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


class Base(DeclarativeBase):
    pass


class Schema(Base):
    __tablename__ = "os_schema"
    name: Mapped[str] = mapped_column(String(80), primary_key=True)
    definition: Mapped[dict] = mapped_column(JSON)


class User(Base):
    __tablename__ = "os_user"
    name: Mapped[str] = mapped_column(String(80), primary_key=True)
    password: Mapped[str] = mapped_column(Text)
    role: Mapped[str] = mapped_column(String(20))


class Record(Base):
    __tablename__ = "os_record"
    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    sys_id: Mapped[str] = mapped_column(String(32), unique=True, index=True, default=lambda: uuid4().hex)
    table: Mapped[str] = mapped_column(String(80), index=True)
    data: Mapped[dict] = mapped_column(JSON)
    original: Mapped[dict] = mapped_column(JSON, default=dict)
    created: Mapped[str] = mapped_column(String(40), default=now)
    updated: Mapped[str] = mapped_column(String(40), default=now)
    creator: Mapped[str] = mapped_column(String(80))
    updater: Mapped[str] = mapped_column(String(80))
    revision: Mapped[int] = mapped_column(Integer, default=0)


class Event(Base):
    __tablename__ = "os_event"
    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    record_id: Mapped[str] = mapped_column(String(32), index=True)
    actor: Mapped[str] = mapped_column(String(80))
    action: Mapped[str] = mapped_column(String(40))
    created: Mapped[str] = mapped_column(String(40), default=now)
    detail: Mapped[dict] = mapped_column(JSON)


class Attachment(Base):
    __tablename__ = "os_attachment"
    sys_id: Mapped[str] = mapped_column(String(32), primary_key=True, default=lambda: uuid4().hex)
    record_id: Mapped[str] = mapped_column(String(32), index=True)
    name: Mapped[str] = mapped_column(String(255))
    content: Mapped[bytes] = mapped_column(LargeBinary)


def initialize(url, admin_password):
    engine = create_engine(url, pool_pre_ping=True)
    Base.metadata.create_all(engine)
    factory = sessionmaker(engine, expire_on_commit=False)
    with factory.begin() as db:
        if not db.get(User, "admin"):
            db.add(User(name="admin", password=generate_password_hash(admin_password), role="admin"))
        for name, definition in TABLES.items():
            if not db.get(Schema, name):
                db.add(Schema(name=name, definition=definition))
    return factory


def serialize(record):
    return {**record.data, "sys_id": record.sys_id, "sys_class_name": record.table,
            "sys_created_on": record.created, "sys_updated_on": record.updated,
            "sys_created_by": record.creator, "sys_updated_by": record.updater,
            "sys_mod_count": str(record.revision)}


def audit(db, record, actor, action, detail):
    db.add(Event(record_id=record.sys_id, actor=actor, action=action, detail=detail))
