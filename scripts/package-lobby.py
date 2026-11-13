#!/usr/bin/env python3
"""Build a Phase 8 review bundle from real outputs and tracked source, never live data."""
from pathlib import Path
import hashlib
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
dist = root / 'dist' / 'phase8'
dist.mkdir(parents=True, exist_ok=True)
plugins = [('lobby', 'PolderLobby', 'Lobby'), ('cinematics', 'PolderCinematics', 'Lobby'),
           ('core', 'PolderCore', 'SMP'), ('velocity', 'PolderVelocity', 'Velocity')]
for module, name, _ in plugins:
    jar = root / f'polder-{module}/target/{name}.jar'
    with zipfile.ZipFile(jar) as built:
        descriptor = 'velocity-plugin.json' if module == 'velocity' else 'plugin.yml'
        if descriptor not in built.namelist():
            raise RuntimeError(f'Missing plugin descriptor: {jar}')
source = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
allowed = {'polder-common', 'polder-core', 'polder-lobby', 'polder-cinematics',
           'polder-velocity', '.github', 'scripts', 'maintenance', 'docs'}
with zipfile.ZipFile(dist / 'PolderSMP-Phase8-Source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in source:
        path = Path(name)
        if not name or any(part in {'target', 'node_modules', '.git'} for part in path.parts):
            continue
        if path.parts[0] in allowed or name in {'pom.xml', 'README.md', 'BUILD-REPORT.txt', 'AGENTS.md', '.gitignore'}:
            archive.write(root / path, 'PolderSMP/' + name)
with zipfile.ZipFile(dist / 'PolderSMP-Phase8-Review.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for module, name, server in plugins:
        archive.write(root / f'polder-{module}/target/{name}.jar', f'{server}/plugins/{name}.jar')
        archive.write(root / 'polder-common/src/main/resources/foundation.properties',
                      f'{server}/plugins/{name}/foundation.properties')
        for path in sorted((root / f'polder-{module}/src/main/resources').glob('*.yml')):
            if path.name != 'plugin.yml':
                archive.write(path, f'{server}/plugins/{name}/{path.name}')
    for path in [root / 'README.md', root / 'BUILD-REPORT.txt', *sorted((root / 'docs').glob('*.md'))]:
        archive.write(path, path.relative_to(root))
    archive.write(dist / 'PolderSMP-Phase8-Source.zip', 'PolderSMP-Phase8-Source.zip')
(dist / 'SHA256SUMS.txt').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
    for path in sorted(dist.glob('*.zip'))))
print('Packaged real Phase 8 review artifacts in', dist)
