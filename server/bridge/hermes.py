"""Pinned Runs API integration: final status is authoritative, never stream deltas."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from . import policy


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Hermes:
    def __init__(self, url, key, model, profile):
        parsed = urllib.parse.urlparse(url)
        policy.require(parsed.scheme in {"https", "http"} and not parsed.username and not parsed.query and not parsed.fragment)
        policy.require(parsed.scheme == "https" or parsed.hostname in {"127.0.0.1", "localhost", "::1"}, "hermes_requires_tls")
        policy.require(bool(key) and re.fullmatch(r"[a-zA-Z0-9_-]+", profile) is not None, "restricted_profile_required")
        self.url, self.key, self.model, self.profile = url.rstrip("/"), key, model, profile
        self.http = urllib.request.build_opener(NoRedirect())

    def call(self, method, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        req = urllib.request.Request(self.url + path, data=policy.canonical(body).encode() if body is not None else None,
                                     headers=headers, method=method)
        with self.http.open(req, timeout=20) as reply:
            raw = reply.read(65_537)
            policy.require(len(raw) <= 65_536, "hermes_response_limit", 502)
            return json.loads(raw)

    def capabilities(self):
        value = self.call("GET", "/v1/capabilities")
        features = value.get("features", {})
        policy.require(all(features.get(k) is True for k in ("run_submission", "run_status", "run_stop")), "hermes_capabilities", 503)
        replay = features.get('runs_idempotency', {})
        policy.require(replay.get('supported') is True and replay.get('durable') is True and
                       type(replay.get('retention_seconds')) in {int, float} and replay['retention_seconds'] >= 86400,
                       'hermes_replay_storage', 503)
        toolsets = self.call("GET", "/v1/toolsets")
        policy.require(isinstance(toolsets.get("data"), list) and all(row.get("enabled") is False for row in toolsets["data"]), "hermes_tools_enabled", 503)
        # Tool isolation is enforced by the separately provisioned profile, not by a prompt.
        return value

    def create(self, job):
        request = json.loads(job["payload"])
        body = {"input": request["text"], "model": self.model,
                "session_id": job["session"], "conversation_history": [{"role": "system", "content": "This is an isolated messaging request with no prior conversation."}],
                "instructions": policy.OWNER_INSTRUCTIONS if request["lane"] == "owner" else policy.SELECTED_INSTRUCTIONS}
        # Explicit nonempty inert history prevents loading a stored session transcript. The server owns
        # session identities; no device may select a Hermes session or X-Hermes-Session-Key.
        return self.call("POST", "/v1/runs", body, "r1-" + job["id"])

    def status(self, run):
        return self.call("GET", "/v1/runs/" + urllib.parse.quote(run, safe=""))

    def delete_session(self, session):
        policy.require(session.startswith(("r1-owner-", "r1-selected-")), "session_scope")
        value = self.call("DELETE", "/api/sessions/" + urllib.parse.quote(session, safe=""))
        policy.require(value.get("id") == session and value.get("deleted") is True, "cleanup_pending", 503)

    def stop(self, run):
        return self.call("POST", "/v1/runs/" + urllib.parse.quote(run, safe="") + "/stop", {})
