"""Verify and copy artifacts built from an exact local tag and clean Git checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--modid', default='gtmb')
    parser.add_argument('--version', required=True)
    parser.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    def git(*arguments):
        return subprocess.check_output(['git', *arguments], cwd=root, text=True).strip()
    commit = git('rev-parse', 'HEAD')
    assert git('rev-parse', args.version + '^{}') == commit, 'Tag must match HEAD'
    assert not git('status', '--porcelain'), 'Checkout must be clean'
    version_property = (root / 'gradle.properties').read_text(encoding='utf-8')
    assert any(line.replace(' ', '') == 'RELEASE_VERSION=' + args.version.removeprefix('v')
               for line in version_property.splitlines()), 'RELEASE_VERSION must match Tag'
    args.destination.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for suffix in ('', '-dev', '-sources'):
        source = root / 'build/libs' / (args.modid + '-' + args.version + suffix + '.jar')
        assert source.is_file(), source
        with zipfile.ZipFile(source) as jar:
            assert jar.testzip() is None, 'ZIP CRC failure'
            names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
            if suffix != '-sources':
                metadata = json.loads(jar.read('mcmod.info'))
                entries = metadata.get('modList', []) if isinstance(metadata, dict) else metadata
                entries = [entry for entry in entries if entry.get('modid') == args.modid]
                assert len(entries) == 1 and entries[0]['version'] == args.version
                tags = jar.read('com/miaokatze/' + args.modid + '/Tags.class')
                assert args.version.encode() in tags and b'dirty' not in tags and b'master+' not in tags
            if args.modid == 'gtmb':
                assert not any('com/miaokatze/gtit/' in name for name in names), 'GTIT classes leaked'
                assert any(name.endswith('HologramBlueprintNbt.java' if suffix == '-sources'
                                          else 'HologramBlueprintNbt.class') for name in names)
            else:
                removed = ('com/miaokatze/gtit/hologram/', 'com/miaokatze/gtit/client/hologram/',
                           'com/miaokatze/gtit/mixin/hologram/', 'com/miaokatze/gtit/common/items/hologram/',
                           'mixins.gtit.hologram.json')
                assert not any(name.startswith(removed) for name in names), 'Legacy engine remains'
        target = args.destination / source.name
        shutil.copy2(source, target)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
        artifacts.append({'file': target.name, 'bytes': target.stat().st_size, 'sha256': digest})
    receipt = {'version': args.version, 'modid': args.modid, 'commit': commit,
               'clean_worktree': True, 'checks': ['Tag=HEAD', 'RELEASE_VERSION', 'ZIP CRC',
               'mcmod.info', 'Tags.VERSION', 'no dirty/master+', 'engine boundary'], 'artifacts': artifacts}
    (args.destination / 'artifact.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
