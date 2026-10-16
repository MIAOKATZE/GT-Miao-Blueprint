"""ROOT-only executor for a fresh prepare_packaged.py --profile v016-gui fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import unquote, urlsplit


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_properties(path):
    result = {}
    if path.is_file():
        for row in path.read_text(encoding='latin-1').splitlines():
            if row and not row.startswith(('#', '!')) and '=' in row:
                key, value = row.split('=', 1)
                result[key] = re.sub(r'\\([:= \\])', r'\1', value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--expect', choices=['pass', 'old-ame'], required=True)
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    fixture = args.fixture.resolve()
    environment = fixture / 'environment.json'
    receipt_file = fixture / 'native-run.json'
    if receipt_file.exists() or (fixture / 'receipts/native-gui.properties').exists():
        parser.error('Preserve earlier attempts: use a fresh prepared fixture')
    env = json.loads(environment.read_text(encoding='utf-8'))
    if env['profile'] != 'v016-gui':
        parser.error('Only the isolated v016 GUI production profile may run here')
    normal = Path(env['client']) / 'mods' / env['normal_jar']
    if digest(normal) != env['normal_sha256']:
        parser.error('Prepared unchanged normal JAR hash differs')
    command = [env['java'], '@' + env['args_file']]
    started = time.time()
    log_path = fixture / 'native-client.log'
    receipt = {'scope': 'actual FML production main-menu empty import Screen, no world/network/business retest',
               'expected': args.expect, 'command': command, 'environment_sha256': digest(environment),
               'normal_sha256': digest(normal), 'start_unix': started, 'timeout': False}
    with log_path.open('wb') as log:
        process = subprocess.Popen(command, cwd=env['client'], stdout=log, stderr=subprocess.STDOUT)
        receipt['pid'] = process.pid
        receipt_file.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
        try:
            receipt['exit_code'] = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            receipt['timeout'] = True
            process.terminate()
            try:
                receipt['exit_code'] = process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                receipt['exit_code'] = process.wait()
    receipt['closed_unix'] = time.time()
    observed = read_properties(fixture / 'receipts/native-gui.properties')
    receipt['actual'] = observed
    receipt['normal_unchanged'] = digest(normal) == env['normal_sha256']
    source_matches = True
    for key in ('screenResource', 'hostResource'):
        url = observed.get(key, '')
        if not url.startswith('jar:file:') or '!' not in url:
            source_matches = False
        else:
            path = unquote(urlsplit(url[4:].split('!', 1)[0]).path)
            if re.match(r'^/[A-Za-z]:/', path):
                path = path[1:]
            source_matches = source_matches and Path(path).resolve() == normal.resolve()
    receipt['actual_class_origins_match_selected_normal_jar'] = source_matches
    log = log_path.read_text(encoding='utf-8', errors='replace')
    crashes = list((fixture / 'client/crash-reports').glob('*.txt'))
    all_errors = log + '\n'.join(path.read_text(encoding='utf-8', errors='replace') for path in crashes)
    if args.expect == 'pass':
        accepted = (observed.get('status') == 'PASS' and observed.get('hostMethodCount') == '9'
                    and observed.get('glError') == '0' and observed.get('fmlDeobfuscatedEnvironment') == 'false'
                    and int(observed.get('nativeFramesObserved', '0')) >= 4 and source_matches
                    and receipt['normal_unchanged'] and not receipt['timeout'] and receipt['exit_code'] == 0)
        receipt['status'] = 'PASS' if accepted else 'FAIL'
    else:
        accepted = ('AbstractMethodError' in all_errors and 'drawDefaultBackground' in all_errors
                    and receipt['normal_unchanged'] and not receipt['timeout'] and source_matches)
        receipt['status'] = 'EXPECTED_OLD_ABSTRACT_METHOD_ERROR' if accepted else 'FAIL'
    receipt['log_sha256'] = digest(log_path)
    receipt['crash_report_hashes'] = {str(path.relative_to(fixture)): digest(path) for path in crashes}
    receipt_file.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))
    return 1 if receipt['status'] == 'FAIL' else 0


if __name__ == '__main__':
    sys.exit(main())
