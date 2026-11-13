#!/usr/bin/env python3
"""Package only the standalone cinematic plugin and public default configuration."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
out = root / 'dist' / 'cinematics'
out.mkdir(parents=True, exist_ok=True)
jar = root / 'polder-cinematics/target/PolderCinematics.jar'
with zipfile.ZipFile(jar) as built:
    assert 'plugin.yml' in built.namelist(), 'Missing plugin descriptor'
    assert not any('CelestialProbe' in name for name in built.namelist()), 'Test fixture in production JAR'
shutil.copy2(jar, out / 'PolderCinematics.jar')
with zipfile.ZipFile(out / 'PolderCinematics-Celestial.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar, 'plugins/PolderCinematics.jar')
    for name in ['config.yml', 'effects.yml']:
        archive.write(root / 'polder-cinematics/src/main/resources' / name,
                      'plugins/PolderCinematics/' + name)
    archive.write(root / 'polder-common/src/main/resources/foundation.properties',
                  'plugins/PolderCinematics/foundation.properties')
    archive.write(root / 'polder-cinematics/SETUP.md', 'SETUP.md')
    archive.write(root / 'polder-cinematics/VERIFICATION.md', 'VERIFICATION.md')
(out / 'SHA256SUMS.txt').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
    for path in sorted(out.iterdir()) if path.is_file() and path.name != 'SHA256SUMS.txt'))
print('Packaged standalone PolderCinematics only:', out)
