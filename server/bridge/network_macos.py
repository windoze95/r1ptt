"""Private HTTPS for Tailscale primary and a LAN/WireGuard fallback on macOS.

Requires the already-enrolled userspace Tailscale daemon, tailnet HTTPS enabled,
and Homebrew Caddy. This creates no public listener or router port forwarding.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import re
import ssl
import subprocess
import tempfile


STATE = Path.home() / 'Library/Application Support/robotOS/bridge'
SOCKET = Path.home() / 'Library/Application Support/robotOS/tailscale/tailscaled.sock'
TS = ['/opt/homebrew/bin/tailscale', '--socket=' + str(SOCKET)]
LABEL = 'ai.robotos.bridge-https'


def write(path, data):
    path.write_text(data)
    path.chmod(0o600)


def renew(hostname):
    """Keep the old working certificate if issuance fails; reload only on a change."""
    certs = STATE / 'tls'
    certs.mkdir(exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(dir=certs) as directory:
        cert, key = Path(directory) / 'cert.pem', Path(directory) / 'key.pem'
        subprocess.run(TS + ['cert', '--min-validity=48h', '--cert-file=' + str(cert), '--key-file=' + str(key), hostname], check=True)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)  # Reject an incomplete or mismatched pair before activation.
        active = certs / 'current'
        if active.is_dir() and (active / 'cert.pem').read_bytes() == cert.read_bytes():
            return False
        generation = certs / ('generation-' + os.urandom(8).hex())
        generation.mkdir(mode=0o700)
        for source in (cert, key):
            target = generation / source.name
            target.write_bytes(source.read_bytes()); target.chmod(0o600)
        temporary = certs / 'next'
        temporary.symlink_to(generation.name, target_is_directory=True)
        temporary.replace(active)  # Swap the pair atomically.
        # Remove only prior generations created by this module, after activation.
        for old in certs.glob('generation-*'):
            if old != generation and old.is_dir() and not old.is_symlink():
                for name in ('cert.pem', 'key.pem'):
                    (old / name).unlink(missing_ok=True)
                old.rmdir()
    return True


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hostname', required=True)
    parser.add_argument('--lan-address')
    parser.add_argument('--wireguard-peer')
    parser.add_argument('--renew', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+\.[a-z0-9-]+\.ts\.net', args.hostname):
        raise SystemExit('Expected this host\'s Tailscale DNS name')
    state = json.loads(subprocess.check_output(TS + ['status', '--json'], text=True))
    if state['Self']['DNSName'].rstrip('.') != args.hostname:
        raise SystemExit('Hostname differs from this Tailscale node')
    if args.renew:
        if renew(args.hostname):
            subprocess.run(['launchctl', 'kickstart', '-k', 'gui/' + str(os.getuid()) + '/' + LABEL], check=True)
        return
    address = str(ipaddress.IPv4Address(args.lan_address))
    peer = str(ipaddress.IPv4Address(args.wireguard_peer))
    if not ipaddress.ip_address(address).is_private or not ipaddress.ip_address(peer).is_private:
        raise SystemExit('Only private LAN and WireGuard addresses are supported')
    agents = Path.home() / 'Library/LaunchAgents'
    if any((agents / (label + '.plist')).exists() for label in (LABEL, LABEL + '-renew')):
        raise SystemExit('HTTPS agents already exist; inspect before replacing them')
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    renew(args.hostname)
    cert = STATE / 'tls/current/cert.pem'
    key = STATE / 'tls/current/key.pem'
    config = STATE / 'Caddyfile'
    write(config, f'''{{
    admin off
    auto_https off
}}
https://{args.hostname}:8443 {{
    bind {address}
    tls "{cert}" "{key}"
    @peer remote_ip {peer}
    handle @peer {{
        reverse_proxy 127.0.0.1:8650
    }}
    handle {{
        respond 403
    }}
}}
''')
    subprocess.run(['/opt/homebrew/bin/caddy', 'validate', '--config', str(config)], check=True)
    repo = Path(__file__).resolve().parents[2]
    for label, command, schedule in (
        (LABEL, ['/opt/homebrew/bin/caddy', 'run', '--config', str(config)], {'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 30}),
        (LABEL + '-renew', ['/opt/homebrew/bin/python3', '-m', 'server.bridge.network_macos', '--hostname', args.hostname, '--renew'], {'StartInterval': 86400, 'RunAtLoad': True}),
    ):
        path = agents / (label + '.plist')
        value = {'Label': label, 'ProgramArguments': command, 'WorkingDirectory': str(repo),
                 'StandardOutPath': '/dev/null', 'StandardErrorPath': '/dev/null', **schedule}
        path.write_bytes(plistlib.dumps(value)); path.chmod(0o600)
        subprocess.run(['launchctl', 'bootstrap', 'gui/' + str(os.getuid()), str(path)], check=True)
    subprocess.run(TS + ['serve', '--bg', '--https=443', 'http://127.0.0.1:8650'], check=True, timeout=60)
    print('Tailscale HTTPS and peer-restricted LAN HTTPS are configured. Verify both from the R1.')


if __name__ == '__main__':
    main()
