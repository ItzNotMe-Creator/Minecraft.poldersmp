#!/usr/bin/env python3
"""Package verified build outputs without server worlds, databases, or secrets."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
dist = root / 'dist'
dist.mkdir(exist_ok=True)
plugins = [('core', 'PolderCore'), ('lobby', 'PolderLobby'), ('velocity', 'PolderVelocity')]
for module, name in plugins:
    shutil.copy2(root / f'polder-{module}/target/{name}.jar', dist / f'{name}.jar')
source = []
for path in root.rglob('*'):
    rel = path.relative_to(root)
    if not path.is_file() or any(part in {'.git', 'target', 'dist', '__pycache__'} for part in rel.parts):
        continue
    # Deliberate allowlist: do not package accidental private runtime configuration.
    if rel.parts[0] in {'polder-common', 'polder-core', 'polder-lobby', 'polder-velocity', '.github', 'scripts'} or str(rel) in {'pom.xml', 'README.md', 'BUILD-REPORT.txt', 'AGENTS.md', '.gitignore'}:
        source.append(path)
with zipfile.ZipFile(dist / 'PolderSMP-Phase1-Source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source):
        archive.write(path, 'PolderSMP/' + str(path.relative_to(root)))
with zipfile.ZipFile(dist / 'PolderSMP-Phase1.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for _, name in plugins:
        archive.write(dist / f'{name}.jar', f'plugins/{name}.jar')
        archive.write(root / 'polder-common/src/main/resources/foundation.properties', f'config/{name}/foundation.properties')
    for name in ['README.md', 'BUILD-REPORT.txt']:
        archive.write(root / name, name)
    archive.write(dist / 'PolderSMP-Phase1-Source.zip', 'PolderSMP-Phase1-Source.zip')
(dist / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(dist.iterdir()) if p.is_file() and p.name != 'SHA256SUMS.txt'))
print('Packaged Phase 1 plugins, source and configuration in', dist)
