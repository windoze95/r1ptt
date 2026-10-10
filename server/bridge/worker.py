import json
import urllib.error

from . import policy


class Worker:
    def __init__(self, store, hermes):
        self.store, self.hermes = store, hermes

    def step(self):
        for job in self.store.work():
            try:
                if job["stop_pending"]:
                    if job["run"]:
                        self.hermes.stop(job["run"])
                        result = self.hermes.status(job["run"])
                        if result["status"] not in {"completed", "cancelled", "interrupted", "failed"}:
                            continue
                    elif job["create_started"] is not None:
                        # A queued create may have been accepted before its ACK was lost. Recover
                        # its original run within the idempotency window, then stop it.
                        if not job["payload"] or self.store.clock() - job["create_started"] >= 23 * 3600:
                            self.store.update_work(job, state="unresolved", stop_pending=0)
                            continue  # Never POST after Hermes' 24-hour replay window.
                        result = self.hermes.create(job)
                        self.validate_run(result)
                        self.store.update_work(job, run=result["run_id"])
                        continue
                    self.store.update_work(job, state="cancelled" if job["state"] == "stopping" else job["state"], stop_pending=0)
                elif job["state"] == "queued":
                    job = self.store.begin_create(job)
                    if job is None:
                        continue
                    result = self.hermes.create(job)
                    self.validate_run(result)
                    self.store.update_work(job, state="running", run=result["run_id"])
                else:
                    result = self.hermes.status(job["run"])
                    policy.require(result.get("session_id") == job["session"], "session_mismatch", 502)
                    if result["status"] == "completed":
                        output = result.get("output")
                        policy.require(isinstance(output, str) and 0 < len(output) <= 4096, "invalid_output", 502)
                        if job["lane"] == "owner":
                            intent = json.loads(output)
                            policy.require(isinstance(intent, dict) and intent.get("kind") in {"sms", "sms_exact", "draft", "clarify", "chat"}, "invalid_output", 502)
                            request = json.loads(job["payload"])
                            if request["mode"] == "draft":
                                policy.require(intent.get("kind") in {"draft", "clarify", "chat"}, "no_authority", 502)
                        self.store.update_work(job, state="ready", result=output)
                    elif result["status"] in {"failed", "cancelled", "interrupted"}:
                        self.store.update_work(job, state="failed")
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    # Expired server state is not permission to create another run.
                    self.store.update_work(job, state="unresolved", stop_pending=0)
                elif error.code in {400, 401, 403, 409}:
                    if job["stop_pending"]:
                        self.store.retry(job)  # A failed stop is not a stopped run.
                    else:
                        self.store.update_work(job, state="failed", stop_pending=1 if job["run"] else 0)
                else:
                    self.store.retry(job)
            except (policy.Rejected, ValueError, KeyError, TypeError):
                if job["stop_pending"]:
                    self.store.retry(job)
                else:
                    self.store.update_work(job, state="failed", stop_pending=1)
            except (OSError, TimeoutError):
                self.store.retry(job)
        for job in self.store.cleanup():
            try:
                if job["create_started"] is not None:
                    self.hermes.delete_session(job["session"])
                self.store.scrubbed(job)
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    self.store.scrubbed(job)
                else:
                    self.store.retry(job)
            except (OSError, policy.Rejected, ValueError):
                self.store.retry(job)

    @staticmethod
    def validate_run(result):
        policy.require(isinstance(result.get("run_id"), str) and 0 < len(result["run_id"]) < 256, "invalid_run", 502)
