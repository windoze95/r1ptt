"""Prepare private launch agents on the Hermes host. Never changes the default profile.

Run after `hermes profile create r1-messaging --no-alias --no-skills`.
Secrets stay on this host; the device enrollment is a separate local CLI step.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import secrets
import subprocess

from .runtime import PIN, PROFILE


def write_private(path, value):
    path.write_text(value)
    path.chmod(0o600)


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--hermes', type=Path, required=True)
    p.add_argument('--model', required=True)
    p.add_argument('--start', action='store_true')
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    default = Path.home() / '.hermes'
    profile = default / 'profiles' / PROFILE
    state = Path.home() / 'Library/Application Support/robotOS/bridge'
    if subprocess.check_output(['git', '-C', str(args.hermes), 'rev-parse', 'HEAD'], text=True).strip() != PIN:
        raise SystemExit('Hermes pin mismatch')
    if not profile.is_dir() or profile.is_symlink():
        raise SystemExit('Create the empty named profile first')
    if (profile / 'config.yaml').exists() and (profile / '.robotos-bridge').exists():
        raise SystemExit('Already provisioned; inspect and restart the existing services instead')
    if (profile / 'auth.json').exists() or any((profile / 'memories').glob('*.md')):
        raise SystemExit('Refusing to repurpose a profile containing credentials or memory')
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    workspace = state / 'workspace'; workspace.mkdir(exist_ok=True, mode=0o700)
    key = secrets.token_urlsafe(48)
    config = {'model': {'provider': 'openai-codex', 'default': args.model},
              'agent': {'max_turns': 1, 'disabled_toolsets': ['memory']},
              'platform_toolsets': {'api_server': [], 'cli': []}, 'mcp_servers': {},
              'memory': {'memory_enabled': False, 'user_profile_enabled': False},
              'skills': {'disabled': ['hermes-agent']},
              'auxiliary': {'background_review': {'enabled': False}},
              'gateway': {'api_server': {'max_concurrent_runs': 1}},
              'display': {'show_reasoning': False}}
    write_private(profile / 'config.yaml', json.dumps(config, indent=2) + '\n')
    write_private(profile / '.env', '')
    write_private(profile / 'SOUL.md', '')
    write_private(profile / '.robotos-bridge', PIN + '\n')
    # Hermes' supported credential-pool fallback borrows the root grant with its refresh lock.
    # Do not copy OAuth refresh tokens into the new profile.
    environment = {'PATH': '/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
                   'HERMES_HOME': str(profile), 'HERMES_PROFILE': PROFILE,
                   'API_SERVER_KEY': key, 'R1_HERMES_KEY': key, 'PYTHONUNBUFFERED': '1'}
    write_private(state / 'service-env.json', json.dumps(environment))
    launcher = Path.home() / '.local/bin/hermes'
    command = json.loads(subprocess.check_output([str(launcher), '--print-runtime-command'], text=True))
    bootstrap = command[-1].split("runpy.run_module('hermes_cli.main'")[0]
    runtime = command[:-1] + [bootstrap + f"sys.path.insert(0, {str(repo)!r}); runpy.run_module('server.bridge.runtime', run_name='__main__')"]
    bridge = ['/opt/homebrew/bin/python3', '-m', 'server.bridge', '--db', str(state / 'bridge.db'),
              'serve', '--model', args.model]
    for label, argv, cwd in [('ai.robotos.hermes-messaging', runtime, workspace), ('ai.robotos.r1-bridge', bridge, repo)]:
        agent = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
        if agent.exists():
            raise SystemExit('Existing launch agent; inspect before replacing: ' + label)
        cfg = {'Label': label, 'ProgramArguments': argv, 'EnvironmentVariables': environment,
               'WorkingDirectory': str(cwd), 'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 30,
               'StandardOutPath': '/dev/null', 'StandardErrorPath': '/dev/null'}
        agent.write_bytes(plistlib.dumps(cfg)); agent.chmod(0o600)
        if args.start:
            subprocess.run(['/bin/launchctl', 'bootstrap', 'gui/' + str(os.getuid()), str(agent)], check=True)
    print('Private restricted Hermes profile and bridge launch agents prepared. No phone credential created yet.')


if __name__ == '__main__':
    main()
