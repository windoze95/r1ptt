"""python -m server.bridge --help; all administration is local, never over HTTP."""
import argparse
import json
import os
import pathlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from . import policy
from .hermes import Hermes
from .store import Store
from .worker import Worker


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, store, profile):
        self.store, self.profile = store, profile
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # Never print tracebacks containing request bodies, phone numbers or tokens.
        pass


class Handler(BaseHTTPRequestHandler):
    server_version = "robotOS-bridge/1"

    def log_message(self, fmt, *args):
        pass

    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def dispatch(self, method):
        try:
            policy.require(not self.headers.get("Transfer-Encoding"), "invalid_request")
            auth = self.headers.get("Authorization", "")
            policy.require(auth.startswith("Bearer "), "unauthorized", 401)
            device = self.server.store.authenticate(auth[7:])
            length = int(self.headers.get("Content-Length", "0"))
            policy.require(0 <= length <= policy.MAX_BYTES, "request_limit", 413)
            body = None
            if method == "POST":
                policy.require(self.headers.get_content_type() == "application/json" and length > 0)
                body = json.loads(self.rfile.read(length))
                policy.require(isinstance(body, dict))
            else:
                policy.require(length == 0)
            route = urlsplit(self.path)
            policy.require(not route.query and not route.fragment, "not_found", 404)
            segments = route.path.split("/")[1:]
            store = self.server.store
            if method == "GET" and segments == ["v1", "device"]:
                reply = {"version": policy.VERSION, "device_id": device, "profile": self.server.profile,
                         "forwarding": "selected_only", "automatic_replies": False, "server_time": int(time.time())}
            elif method == "POST" and segments == ["v1", "commands"]:
                reply = store.submit(device, body)
            elif len(segments) in {3, 4} and segments[:2] == ["v1", "commands"]:
                job = policy.identifier(segments[2])
                if method == "GET" and len(segments) == 3:
                    reply = store.get(device, job)
                elif method == "POST" and len(segments) == 4:
                    action = segments[3]
                    if action == "freeze":
                        reply = store.freeze(device, job, body)
                    elif action == "grant":
                        reply = store.grant(device, job, body)
                    elif action == "cancel" and body == {}:
                        reply = store.cancel(device, job)
                    elif action == "receipt":
                        reply = store.receipt(device, job, body)
                    else:
                        raise policy.Rejected("not_found", 404)
                else:
                    raise policy.Rejected("not_found", 404)
            elif method == "POST" and segments == ["v1", "block"] and set(body) == {"peer_id"}:
                reply = store.block(device, body["peer_id"])
            else:
                raise policy.Rejected("not_found", 404)
            self.reply(200, reply)
        except policy.Rejected as error:
            self.reply(error.status, {"error": error.code})
        except (ValueError, TypeError, KeyError):
            self.reply(400, {"error": "invalid_request"})
        except Exception:
            self.reply(503, {"error": "unavailable"})

    def reply(self, status, value):
        raw = policy.canonical(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    enroll = sub.add_parser("enroll")
    enroll.add_argument("--url", required=True, help="HTTPS address reachable by the R1")
    enroll.add_argument("--wireguard-url", default="", help="HTTPS address of the SAME bridge over standalone WireGuard")
    enroll.add_argument("--wireguard-address", default="", help="Optional IPv4 LAN address; HTTPS still verifies the fallback URL hostname")
    enroll.add_argument("--output", required=True, help="New private JSON config file; never printed")
    revoke = sub.add_parser("revoke")
    revoke.add_argument("device")
    serve = sub.add_parser("serve")
    serve.add_argument("--port", type=int, default=8650)
    serve.add_argument("--profile", default="r1-messaging")
    serve.add_argument("--hermes-url", default="http://127.0.0.1:8643")
    serve.add_argument("--model", required=True)
    args = parser.parse_args()
    pathlib.Path(args.db).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    store = Store(args.db)
    if args.command == "enroll":
        if args.wireguard_address:
            import ipaddress
            ipaddress.IPv4Address(args.wireguard_address)
        for url in [args.url] + ([args.wireguard_url] if args.wireguard_url else []):
            parsed = urlsplit(url)
            policy.require(parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.query and not parsed.fragment and parsed.path in {"", "/"})
        # Refuse overwrite before enrolling a new credential.
        with open(args.output, "x") as output:
            device, token = store.enroll()
            json.dump({"bridge": {"enabled": False, "docked": False, "paused": False,
                                  "deviceId": device, "baseUrl": args.url.rstrip("/"), "token": token,
                                  "wireguardUrl": args.wireguard_url.rstrip("/"), "wireguardAddress": args.wireguard_address, "useWireguard": False}}, output)
        print("Enrollment written to the private config file. Enable from the R1 after provisioning.")
    elif args.command == "revoke":
        store.revoke(policy.identifier(args.device))
        print("Device revoked. Pending jobs cannot receive new dispatch grants.")
    else:
        hermes = Hermes(args.hermes_url, os.environ.get("R1_HERMES_KEY", ""), args.model, args.profile)
        hermes.capabilities()
        worker = Worker(store, hermes)
        def work():
            while True:
                try:
                    worker.step()
                except Exception:
                    # Transient storage failure must not silently kill the only worker.
                    # HTTP remains fail-closed and all authority stays in SQLite.
                    time.sleep(25)
                time.sleep(5)
        threading.Thread(target=work, daemon=True, name="bridge-worker").start()
        # Deliberately no --host switch: serve behind an authenticated HTTPS/VPN proxy.
        print("robotOS bridge listening on loopback; device authentication required.")
        Server(("127.0.0.1", args.port), store, args.profile).serve_forever()


if __name__ == "__main__":
    main()
