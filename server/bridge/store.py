"""SQLite is the authority for identity, replay, cancellation and send budgets."""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager

from . import policy


class Store:
    def __init__(self, path, clock=time.time):
        self.clock, self.lock = clock, threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        if self.db.execute('PRAGMA user_version').fetchone()[0] not in {0, 1}:
            self.db.close()
            raise RuntimeError('Unsupported bridge database version; preserve the database')
        if path != ":memory:":
            os.chmod(path, 0o600)
        self.db.executescript("""
            PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, token TEXT UNIQUE NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS jobs(
                id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id), fingerprint TEXT NOT NULL,
                lane TEXT NOT NULL, peer TEXT NOT NULL, session TEXT NOT NULL UNIQUE,
                created INTEGER NOT NULL, expires INTEGER NOT NULL, state TEXT NOT NULL,
                payload TEXT, run TEXT, result TEXT, frozen TEXT, digest TEXT, granted INTEGER,
                receipt TEXT, updated INTEGER NOT NULL, stop_pending INTEGER NOT NULL DEFAULT 0,
                create_started INTEGER, retries INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0,
                scrubbed INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS jobs_work ON jobs(state, updated);
            CREATE TABLE IF NOT EXISTS blocks(device TEXT NOT NULL, peer TEXT NOT NULL, PRIMARY KEY(device,peer));
            CREATE TABLE IF NOT EXISTS grants(job TEXT PRIMARY KEY REFERENCES jobs(id), device TEXT NOT NULL,
                peer TEXT NOT NULL, parts INTEGER NOT NULL, day INTEGER NOT NULL);
        """)
        expected = {'create_started', 'retries', 'next_try', 'scrubbed', 'fingerprint', 'stop_pending'}
        if not expected <= {row[1] for row in self.db.execute('PRAGMA table_info(jobs)')}:
            self.db.close()
            raise RuntimeError('Bridge database requires a reviewed migration; preserve the database')
        self.db.execute('PRAGMA user_version=1')
        self.db.execute("INSERT OR IGNORE INTO settings VALUES('fingerprint_key',?)", (secrets.token_hex(32),))
        self.secret = self.db.execute("SELECT value FROM settings WHERE key='fingerprint_key'").fetchone()[0].encode()

    def close(self):
        self.db.close()

    @contextmanager
    def transaction(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def fingerprint(self, value):
        return hmac.new(self.secret, policy.canonical(value).encode(), hashlib.sha256).hexdigest()

    def enroll(self):
        device, token = str(uuid.uuid4()), secrets.token_urlsafe(48)
        with self.transaction():
            self.db.execute("INSERT INTO devices(id,token) VALUES(?,?)", (device, hashlib.sha256(token.encode()).hexdigest()))
        return device, token

    def authenticate(self, token):
        policy.require(isinstance(token, str) and 32 <= len(token) <= 256, "unauthorized", 401)
        with self.lock:
            row = self.db.execute("SELECT id FROM devices WHERE token=? AND revoked=0", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        policy.require(row is not None, "unauthorized", 401)
        return row[0]

    def active(self, device):
        row = self.db.execute("SELECT revoked FROM devices WHERE id=?", (device,)).fetchone()
        policy.require(row is not None and not row[0], "revoked", 401)

    def revoke(self, device):
        with self.transaction():
            self.db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device,))
            self.db.execute("UPDATE jobs SET state='revoked',stop_pending=1,updated=? WHERE device=? AND state NOT IN ('received','cancelled','expired','failed','unresolved')",
                            (int(self.clock()), device))

    def _row(self, device, job):
        self.active(device)
        row = self.db.execute("SELECT * FROM jobs WHERE id=? AND device=?", (job, device)).fetchone()
        policy.require(row is not None, "not_found", 404)
        return dict(row)

    def submit(self, device, value):
        now = int(self.clock())
        with self.transaction():
            self.active(device)
            # Check replay before TTL validation: a lost response to an old ID is still a replay.
            old = self.db.execute("SELECT * FROM jobs WHERE id=?", (value.get("id"),)).fetchone()
            if old:
                policy.require(old["device"] == device, "not_found", 404)
                policy.require(hmac.compare_digest(old["fingerprint"], self.fingerprint(value)), "id_conflict", 409)
                return self.public(dict(old))
            policy.request(value, now)
            policy.require(not self.blocked(device, value["peer_id"]), "blocked", 403)
            count = self.db.execute("SELECT COUNT(*) FROM jobs WHERE device=? AND created>=?", (device, now - now % 86400)).fetchone()[0]
            policy.require(count < 50, "daily_model_limit", 429)
            pending = self.db.execute("SELECT COUNT(*) FROM jobs WHERE device=? AND state IN ('queued','running','ready','frozen','stopping')", (device,)).fetchone()[0]
            policy.require(pending < 20, "queue_full", 429)
            # This session is unique even if a peer or conversation is maliciously reused.
            session = "r1-" + value["lane"] + "-" + str(uuid.uuid4())
            self.db.execute("INSERT INTO jobs(id,device,fingerprint,lane,peer,session,created,expires,state,payload,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            (value["id"], device, self.fingerprint(value), value["lane"], value["peer_id"], session, now,
                             value["expires"], "queued", policy.canonical(value), now))
            return self.public(self._row(device, value["id"]))

    def public(self, row):
        return {"version": policy.VERSION, "id": row["id"], "state": row["state"], "expires": row["expires"],
                "result": row["result"], "digest": row["digest"], "receipt": row["receipt"]}

    def get(self, device, job):
        with self.transaction():
            return self.public(self._row(device, job))

    def freeze(self, device, job, value):
        policy.frozen(value)
        now = int(self.clock())
        with self.transaction():
            row = self._row(device, job)
            encoded = policy.canonical(value)
            if row["frozen"] is not None:
                policy.require(row["frozen"] == encoded, "frozen_conflict", 409)
                return self.public(row)
            policy.require(row["state"] == "ready" and now < row["expires"], "not_dispatchable", 409)
            original = json.loads(row["payload"])
            policy.require(row["lane"] == "owner" and original["mode"] == "send" and original["sim"] == value["sim"], "no_authority", 403)
            result = json.loads(row["result"])
            policy.require(result.get("kind") in {"sms", "sms_exact"} and result.get("body") == value["body"], "body_conflict", 409)
            policy.require(not self.blocked(device, row["peer"]), "blocked", 403)
            self.db.execute("UPDATE jobs SET frozen=?,digest=?,state='frozen',updated=? WHERE id=?", (encoded, policy.digest(value), now, job))
            return self.public(self._row(device, job))

    def grant(self, device, job, value):
        policy.require(isinstance(value, dict) and set(value) == {"digest", "attempt"})
        now = int(self.clock())
        with self.transaction():
            row = self._row(device, job)
            policy.require(row["state"] == "frozen" and now < row["expires"] and row["frozen"] is not None, "not_dispatchable", 409)
            policy.require(row["digest"] == value["digest"] and json.loads(row["frozen"])["attempt"] == value["attempt"], "frozen_conflict", 409)
            policy.require(not self.blocked(device, row["peer"]), "blocked", 403)
            grant = self.db.execute("SELECT job FROM grants WHERE job=?", (job,)).fetchone()
            if not grant:
                day = now // 86400
                # Bind budgets to the resolved number, not a caller-chosen peer ID.
                recipient = self.fingerprint(json.loads(row["frozen"])["recipient"])
                used = self.db.execute("SELECT COALESCE(SUM(parts),0) FROM grants WHERE device=? AND day=?", (device, day)).fetchone()[0]
                peers = self.db.execute("SELECT COUNT(*) FROM grants WHERE device=? AND peer=? AND day=?", (device, recipient, day)).fetchone()[0]
                parts = json.loads(row["frozen"])["parts"]
                policy.require(used + parts <= 50 and peers < 10, "daily_sms_limit", 429)
                self.db.execute("INSERT INTO grants VALUES(?,?,?,?,?)", (job, device, recipient, parts, day))
            # A lost grant response can be replayed for the SAME native attempt only. Android's
            # transaction consumes that attempt before binder handoff; grants never create sends.
            self.db.execute("UPDATE jobs SET granted=?,updated=? WHERE id=?", (now, now, job))
            return {"id": job, "digest": row["digest"], "attempt": value["attempt"], "valid_until": min(now + 30, row["expires"])}

    def receipt(self, device, job, value):
        policy.require(isinstance(value, dict) and set(value) == {"attempt", "status"})
        policy.require(value["status"] in {"handoff", "SENDING", "SENT", "DELIVERED", "FAILED", "PARTLY_SENT", "UNKNOWN", "DELIVERY_FAILED"})
        with self.transaction():
            row = self._row(device, job)
            policy.require(row["frozen"] is not None and row["granted"] is not None and json.loads(row["frozen"])["attempt"] == value["attempt"], "unknown_attempt", 409)
            # Receipts after cancellation/revocation do not restore authority. A cancelled
            # in-flight native attempt can still receive a truthful delivery callback.
            if row["receipt"] != "DELIVERED":
                self.db.execute("UPDATE jobs SET receipt=?,state='received',updated=? WHERE id=?", (value["status"], int(self.clock()), job))
            return self.public(self._row(device, job))

    def cancel(self, device, job):
        with self.transaction():
            row = self._row(device, job)
            if row["state"] not in policy.TERMINAL:
                self.db.execute("UPDATE jobs SET state='stopping',stop_pending=1,updated=? WHERE id=?", (int(self.clock()), job))
            return self.public(self._row(device, job))

    def blocked(self, device, peer):
        return self.db.execute("SELECT 1 FROM blocks WHERE device=? AND peer=?", (device, peer)).fetchone() is not None

    def block(self, device, peer):
        policy.identifier(peer)
        with self.transaction():
            self.active(device)
            self.db.execute("INSERT OR IGNORE INTO blocks VALUES(?,?)", (device, peer))
            self.db.execute("UPDATE jobs SET state='stopping',stop_pending=1 WHERE device=? AND peer=? AND state NOT IN ('received','cancelled','expired','failed','unresolved','revoked')", (device, peer))
        return {"blocked": True}

    def work(self):
        now = int(self.clock())
        with self.transaction():
            self.db.execute("UPDATE jobs SET state='expired',stop_pending=1,next_try=0,updated=? WHERE expires<=? AND state IN ('queued','running','ready','frozen')", (now, now))
            # Bodies expire independently of replay IDs. Tombstones are never pruned automatically.
            self.db.execute("UPDATE jobs SET payload=NULL,result=NULL,frozen=NULL WHERE created<?", (now - policy.RETENTION,))
            return [dict(row) for row in self.db.execute("SELECT * FROM jobs WHERE next_try<=? AND (stop_pending=1 OR state IN ('queued','running')) ORDER BY created LIMIT 20", (now,))]

    def begin_create(self, job):
        """Persist before HTTP: distinguish an unsent queue entry from an uncertain create."""
        now = int(self.clock())
        with self.transaction():
            row = self.db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
            if row["state"] != "queued" or row["stop_pending"] or row["expires"] <= now:
                return None
            self.db.execute("UPDATE jobs SET create_started=COALESCE(create_started,?) WHERE id=?", (now, job["id"]))
            return dict(self.db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone())

    def retry(self, job):
        with self.transaction():
            delay = min(60, 5 * 2 ** min(job["retries"], 4))
            self.db.execute("UPDATE jobs SET retries=retries+1,next_try=? WHERE id=?", (int(self.clock()) + delay, job["id"]))

    def cleanup(self):
        with self.lock:
            return [dict(row) for row in self.db.execute(
                "SELECT * FROM jobs WHERE scrubbed=0 AND stop_pending=0 AND created<? AND next_try<=? LIMIT 20",
                (int(self.clock()) - policy.RETENTION, int(self.clock())))]

    def scrubbed(self, job):
        with self.transaction():
            self.db.execute("UPDATE jobs SET scrubbed=1 WHERE id=?", (job["id"],))

    def update_work(self, job, **fields):
        allowed = {"state", "run", "result", "stop_pending"}
        assert fields.keys() <= allowed
        with self.transaction():
            row = self.db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
            if row is None:
                return
            # Cancellation wins over a concurrently arriving completion or run-create ACK.
            if row["state"] != job["state"]:
                if "run" in fields and not row["run"]:
                    self.db.execute("UPDATE jobs SET run=?,stop_pending=1 WHERE id=?", (fields["run"], job["id"]))
                return
            assignments = ",".join(f"{key}=?" for key in fields)
            self.db.execute(f"UPDATE jobs SET {assignments},updated=?,retries=0,next_try=0 WHERE id=?", (*fields.values(), int(self.clock()), job["id"]))
