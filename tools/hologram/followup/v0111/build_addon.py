"""Build the test-only v0111 native witness; does not launch or modify the fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[4]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javac', type=Path, required=True)
    parser.add_argument('--fml-compile-jar', type=Path,
                        default=ROOT / 'build/rfg/recompiled_minecraft-1.7.10.jar')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source', type=Path, default=Path(__file__).with_name('V0111CompositeNativeProbe.java'))
    parser.add_argument('--jar-name', default='gtmb-v0111-composite-native-probe.jar')
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    source.relative_to(ROOT)
    if Path(args.jar_name).name != args.jar_name or not args.jar_name.endswith('.jar'):
        parser.error('Addon JAR name must be a filename ending in .jar')
    if args.output.exists():
        parser.error('Use a fresh addon output directory')
    args.output.mkdir(parents=True)
    classes = args.output / 'classes'
    classes.mkdir()
    command = [str(args.javac.resolve()), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
               '-cp', str(args.fml_compile_jar.resolve()), '-d', str(classes.resolve()), str(source), str(source.with_name("CompositeWorldWitness.java"))]
    (args.output / 'compile-command.json').write_text(json.dumps(command, indent=2), encoding='utf-8')
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (args.output / 'compile.log').write_bytes(result.stdout)
    if result.returncode:
        raise SystemExit(result.stdout.decode('utf-8', errors='replace'))
    addon = args.output / args.jar_name
    with zipfile.ZipFile(addon, 'w') as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
    receipt = dict(scope='test-only addon build; no game executed', addon=str(addon.resolve()),
                   addon_sha256=digest(addon), source_sha256=digest(source), witness_sha256=digest(source.with_name("CompositeWorldWitness.java")),
                   fml_compile_jar_sha256=digest(args.fml_compile_jar),
                   gtmb_classes_replaced=False, minecraft_method_links='SRG reflection')
    (args.output / 'build-receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
