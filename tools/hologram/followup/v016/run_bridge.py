"""ROOT-only executor: compile standalone observer, invoke unchanged normal JAR in SRG ABI fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'src/test/java/com/miaokatze/gtmb/client/hologram/HologramV016ReleaseGuiBridgeProbe.java'
MAIN = 'com.miaokatze.gtmb.client.hologram.HologramV016ReleaseGuiBridgeProbe'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--javac', type=Path, required=True)
    parser.add_argument('--asm', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--srg-runtime', type=Path, default=ROOT / 'build/rfg/srg_merged_minecraft.jar')
    parser.add_argument('--forge-runtime', type=Path, default=ROOT / 'build/rfg/recompiled_minecraft-1.7.10.jar')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expect', choices=['pass', 'old-ame'], required=True)
    args = parser.parse_args()
    for path in (args.java, args.javac, args.asm, args.jar, args.srg_runtime, args.forge_runtime):
        if not path.is_file():
            parser.error('Missing required input: ' + str(path))
    if args.jar.name.endswith(('-dev.jar', '-sources.jar')):
        parser.error('The unchanged normal release JAR is required')
    output = args.output.resolve()
    if output.exists():
        parser.error('Preserve prior evidence: use a fresh output directory')
    output.mkdir(parents=True)
    classes = output / 'observer-classes'
    classes.mkdir()
    inputs = {str(path.resolve()): digest(path) for path in (SOURCE, args.asm, args.jar, args.srg_runtime, args.forge_runtime)}
    with zipfile.ZipFile(args.jar) as archive:
        class_hashes = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in (
            'com/miaokatze/gtmb/client/hologram/HologramScreen.class',
            'com/miaokatze/gtmb/client/hologram/HologramUiHost.class')}
    compile_command = [str(args.javac.resolve()), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
                       '-cp', str(args.asm.resolve()), '-d', str(classes), str(SOURCE)]
    launch = [str(args.java.resolve()), '-cp', str(classes) + ';' + str(args.asm.resolve()), MAIN,
              str(args.jar.resolve()), str(args.srg_runtime.resolve()), str(args.forge_runtime.resolve())]
    (output / 'commands.json').write_text(json.dumps({'compile': compile_command, 'launch': launch}, indent=2))
    compiled = subprocess.run(compile_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (output / 'compile.log').write_bytes(compiled.stdout)
    receipt = {'scope': 'SRG JVM ABI fixture, no FML/GL/native GUI claim', 'expected': args.expect,
               'input_hashes': inputs, 'normal_class_hashes': class_hashes,
               'compile_exit_code': compiled.returncode, 'normal_jar_remapped': False}
    if compiled.returncode:
        receipt['status'] = 'FAIL'
    else:
        launched = subprocess.run(launch, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output / 'probe.log').write_bytes(launched.stdout)
        receipt['launch_exit_code'] = launched.returncode
        try:
            decoded = launched.stdout.decode('utf-8', errors='replace')
            result = json.loads(next(line for line in reversed(decoded.splitlines()) if line.startswith('{')))
        except (StopIteration, json.JSONDecodeError):
            result = {'status': 'FAIL', 'error': 'No parseable JVM receipt'}
        receipt['actual'] = result
        if args.expect == 'pass':
            accepted = launched.returncode == 0 and result.get('status') == 'PASS' and result.get('passed') == 9
            receipt['status'] = 'PASS' if accepted else 'FAIL'
        else:
            accepted = (launched.returncode == 1 and result.get('otherErrors') == 0
                        and result.get('abstractMethodErrors', 0) >= 1
                        and any('drawDefaultBackground:java.lang.AbstractMethodError:' in row
                                for row in result.get('results', [])))
            receipt['status'] = 'EXPECTED_OLD_ABSTRACT_METHOD_ERROR' if accepted else 'FAIL'
    receipt['inputs_unchanged'] = all(digest(Path(path)) == value for path, value in inputs.items())
    if not receipt['inputs_unchanged']:
        receipt['status'] = 'FAIL'
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))
    return 1 if receipt['status'] == 'FAIL' else 0


if __name__ == '__main__':
    sys.exit(main())
