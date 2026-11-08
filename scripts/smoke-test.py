#!/usr/bin/env python3
"""Test built plugins against supplied official server JARs in a new directory."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--paper-jar', type=Path, required=True)
parser.add_argument('--velocity-jar', type=Path, required=True)
parser.add_argument('--accept-eula', action='store_true', help='Accept https://aka.ms/MinecraftEULA for this isolated test')
parser.add_argument('--output', type=Path, help='New directory for logs and temporary server data; must not exist')
args = parser.parse_args()
if not args.accept_eula:
    parser.error('Paper requires explicit --accept-eula; review https://aka.ms/MinecraftEULA first')
project = Path(__file__).resolve().parent.parent
output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='polder-smoke-'))
if args.output:
    output.mkdir(parents=True, exist_ok=False)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'


def run(name, server, plugins, expected, commands):
    folder = output / name
    (folder / 'plugins').mkdir(parents=True)
    shutil.copy2(server.resolve(), folder / 'server.jar')
    for plugin in plugins:
        shutil.copy2(plugin, folder / 'plugins')
    if name == 'paper':
        (folder / 'eula.txt').write_text('eula=true\n')
        (folder / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25575\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nmax-players=1\n')
    else:
        (folder / 'velocity.toml').write_text('config-version="2.7"\nbind="127.0.0.1:25577"\nonline-mode=false\nplayer-info-forwarding-mode="NONE"\n[servers]\nlobby="127.0.0.1:25575"\ntry=["lobby"]\n[forced-hosts]\n"lobby.example.com"=["lobby"]\n"factions.example.com"=["lobby"]\n"minigames.example.com"=["lobby"]\n')
    log = folder / 'smoke.log'
    with log.open('w') as stream:
        process = subprocess.Popen([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xmx1G', '-jar', 'server.jar'] + (['--nogui'] if name == 'paper' else []), cwd=folder, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 240
            while 'Done (' not in log.read_text(errors='replace'):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f'{name} failed startup; inspect {log}')
                time.sleep(0.5)
            # Give asynchronous SQLite initialization time before issuing diagnostics.
            time.sleep(2)
            for command in commands:
                process.stdin.write(command + '\n')
                process.stdin.flush()
                time.sleep(1)
            process.stdin.write(('stop' if name == 'paper' else 'shutdown') + '\n')
            process.stdin.flush()
            process.wait(timeout=60)
            content = log.read_text(errors='replace')
            for marker in expected:
                if marker not in content:
                    raise AssertionError(f'Missing {marker!r}; inspect {log}')
            if process.returncode != 0:
                raise RuntimeError(f'{name} exit code {process.returncode}')
            print(f'{name}: PASS; {log}', flush=True)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


run('paper', args.paper_jar, [project / 'polder-core/target/PolderCore.jar', project / 'polder-lobby/target/PolderLobby.jar'], ['Enabling PolderCore', 'Enabling PolderLobby', 'Lobby foundation enabled', 'SQLite=ready', 'Configuration reloaded.', 'database=ready'], ['poldercore info', 'poldercore reload', 'poldercore debug'])
run('velocity', args.velocity_jar, [project / 'polder-velocity/target/PolderVelocity.jar'], ['Loaded plugin poldervelocity', 'Velocity foundation enabled', 'Closing endpoint'], [])
