"""Pinned Hermes adapter in its own process/profile, without the gateway scheduler.

Start via the Hermes managed Python bootstrap; see deploy_macos.py. Nothing is
patched in the user's Hermes checkout. The subclass applies only in this process.
"""
import asyncio
import json
import logging
import os
from pathlib import Path
import signal
import subprocess

PIN = '38880bd2f1e90dbc9a1aeec03af62539ee64719a'
PROFILE = 'r1-messaging'


def restricted_type(base, model):
    class RestrictedAgent(base):
        def __init__(self, *args, **kwargs):
            if args or kwargs.get('model') != model or kwargs.get('platform') != 'api_server':
                raise RuntimeError('Restricted messaging runtime mismatch')
            kwargs.update(enabled_toolsets=[], disabled_toolsets=['memory'], max_iterations=1,
                          max_tokens=1024, run_budget_seconds=60, skip_context_files=True,
                          load_soul_identity=False, skip_memory=True, skip_background_review=True,
                          save_trajectories=False, verbose_logging=False, quiet_mode=True,
                          fallback_model=None, checkpoints_enabled=False,
                          reasoning_config={'enabled': True, 'effort': 'low'})
            super().__init__(**kwargs)
            if self.tools:
                raise RuntimeError('Messaging runtime unexpectedly exposed tools')
            if self.provider != 'openai-codex' or self.api_mode != 'codex_responses' or self._fallback_chain:
                raise RuntimeError('Messaging runtime changed provider or billing route')
    return RestrictedAgent


def validate(root, home):
    head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    if head != PIN:
        raise RuntimeError('Hermes revision changed; rerun the isolation acceptance checks')
    if home.name != PROFILE or home.parent.name != 'profiles' or home.is_symlink():
        raise RuntimeError('A separate r1-messaging profile is required')
    config = json.loads((home / 'config.yaml').read_text())
    if config['platform_toolsets']['api_server'] != [] or config.get('mcp_servers') != {}:
        raise RuntimeError('Tools must remain disabled')
    if config['memory'] != {'memory_enabled': False, 'user_profile_enabled': False}:
        raise RuntimeError('Messaging memory must remain disabled')
    if config.get('skills', {}).get('disabled') != ['hermes-agent']:
        raise RuntimeError('Bundled self-help skill must be disabled')
    # This Hermes revision seeds its public self-help package even with --no-skills.
    # It is explicitly disabled; reject any additional skill rather than importing it.
    for item in (home / 'skills').rglob('*'):
        relative = str(item.relative_to(home / 'skills'))
        if item.is_file() and relative != '.bundled_manifest' and relative != 'autonomous-ai-agents/DESCRIPTION.md' and not relative.startswith('autonomous-ai-agents/hermes-agent/'):
            raise RuntimeError('Messaging profile contains an additional skill')
    for name in ('plugins', 'hooks', 'memories'):
        if any(p.is_file() for p in (home / name).rglob('*')):
            raise RuntimeError('Messaging profile contains unreviewed context or extensions')
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import _get_platform_tools
    if _get_platform_tools(load_config(), 'api_server'):
        raise RuntimeError('Hermes resolved nonempty messaging tools')
    if config['model'].get('provider') != 'openai-codex':
        raise RuntimeError('A separately reviewed budget is required for another provider')
    return config['model']['default']


async def serve():
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={
        'host': '127.0.0.1', 'port': 8643, 'key': os.environ['API_SERVER_KEY']}))
    adapter._max_concurrent_runs = 1
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    if not await adapter.connect():
        raise RuntimeError('Restricted Hermes API could not start')
    if not adapter._run_idempotency_store.durable:
        await adapter.disconnect()
        raise RuntimeError('Durable Hermes replay storage is required')
    try:
        while not stop.is_set():
            # This supported lookup also prunes terminal replay results after 24 hours.
            # Run it during idle periods too, rather than retaining SMS output until a new request.
            await asyncio.to_thread(adapter._run_idempotency_store.lookup,
                                    'robotOS-maintenance', 'never-reserved', 'no-fingerprint')
            try:
                await asyncio.wait_for(stop.wait(), timeout=60)
            except TimeoutError:
                pass
    finally:
        await adapter.disconnect()


def main():
    os.umask(0o077)
    import run_agent
    from hermes_constants import get_hermes_home
    model = validate(Path(run_agent.__file__).parent, Path(get_hermes_home()))
    run_agent.AIAgent = restricted_type(run_agent.AIAgent, model)
    logging.disable(logging.CRITICAL)  # Content-free launchd output; no transcript access logs.
    asyncio.run(serve())


if __name__ == '__main__':
    main()
