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

import psutil


CLIENT_RSS_LIMIT = int(4.4 * 1024 ** 3)


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
    heap_limits = []
    for line in Path(env['args_file']).read_text(encoding='utf-8').splitlines():
        option = line.strip().strip('"')
        if option.startswith('-Xmx'):
            match = re.fullmatch(r'-Xmx([0-9]+)([kKmMgGtT]?)', option)
            if not match:
                parser.error('Client -Xmx must be an explicit integer byte/unit limit')
            heap_limits.append(int(match.group(1)) * 1024 ** ('kmgt'.index(match.group(2).lower()) + 1
                                if match.group(2) else 0))
    if len(heap_limits) != 1 or heap_limits[0] > 5 * 1024 ** 3:
        parser.error('Prepared client arguments require exactly one explicit -Xmx at most 5GiB')
    normal = Path(env['client']) / 'mods' / env['normal_jar']
    if digest(normal) != env['normal_sha256']:
        parser.error('Prepared unchanged normal JAR hash differs')
    command = [env['java'], '@' + env['args_file']]
    started = time.time()
    log_path = fixture / 'native-client.log'
    receipt = {'scope': 'actual FML production main-menu empty import Screen, no world/network/business retest',
               'expected': args.expect, 'command': command, 'environment_sha256': digest(environment),
               'normal_sha256': digest(normal), 'start_unix': started, 'timeout': False,
               'memory_limited': False, 'client_rss_limit_bytes': CLIENT_RSS_LIMIT,
               'client_peak_rss_bytes': 0, 'client_rss_samples': 0}
    with log_path.open('wb') as log:
        process = subprocess.Popen(command, cwd=env['client'], stdout=log, stderr=subprocess.STDOUT)
        receipt['pid'] = process.pid
        client_monitor = psutil.Process(process.pid)
        receipt_file.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
        while process.poll() is None:
            try:
                rss = client_monitor.memory_info().rss
                receipt['client_rss_samples'] += 1
                receipt['client_peak_rss_bytes'] = max(receipt['client_peak_rss_bytes'], rss)
                if rss >= CLIENT_RSS_LIMIT:
                    receipt['memory_limited'] = True
                    receipt['resource_stop_reason'] = f'Owned client RSS {rss} reached {CLIENT_RSS_LIMIT} bytes'
                    break
            except psutil.NoSuchProcess:
                pass
            if time.time() - started >= args.timeout:
                receipt['timeout'] = True
                break
            time.sleep(.05)
        if process.poll() is None:
            # Resource limits terminate this owned client immediately, without a pre-stop wait.
            process.terminate()
            try:
                receipt['exit_code'] = process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                receipt['exit_code'] = process.wait()
        else:
            receipt['exit_code'] = process.returncode
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
    mui2 = observed.get('mui2Version') is not None
    migration_passed = (observed.get('mui2Version') == '2.3.92-1.7.10'
                        and observed.get('mui2Resource', '').startswith('jar:file:')
                        and '-dev.jar' not in observed.get('mui2Resource', '')
                        and int(observed.get('actualNativeButtons', '0')) > 0
                        and int(observed.get('actualNativeTextFields', '0')) > 0
                        and 'real ModularScreen hit-test' in observed.get('operation', '')
                        and observed.get('operationBefore') != observed.get('operationAfter')
                        and observed.get('resourceReload', '').startswith('PASS ')
                        and observed.get('cutActionCodecPackets') == '0'
                        and all(observed.get('texture.' + name, '').endswith('decoded and GL bound ' + dimensions)
                                for name, dimensions in (('PANEL', '64x64'), ('CLOTH', '32x32'),
                                                         ('BUTTON', '88x18'), ('PRESSED', '88x18')))
                        and (fixture / 'receipts/native-hologram-before-reload.png').is_file()
                        and (fixture / 'receipts/native-hologram-screen.png').is_file())
    receipt['actual_mui2_migration_scope'] = migration_passed if mui2 else 'legacy fixture, not MUI2 validation'
    receipt['framebuffer_hashes'] = {path.name: digest(path) for path in (fixture / 'receipts').glob('native-hologram-*.png')}
    if mui2:
        url = observed.get('mui2Resource', '')
        if url.startswith('jar:file:') and '!' in url:
            path = unquote(urlsplit(url[4:].split('!', 1)[0]).path)
            if re.match(r'^/[A-Za-z]:/', path):
                path = path[1:]
            library = Path(path)
            if library.is_file():
                receipt['actual_mui2_runtime'] = {'path': str(library.resolve()), 'sha256': digest(library)}
    log = log_path.read_text(encoding='utf-8', errors='replace')
    crashes = list((fixture / 'client/crash-reports').glob('*.txt'))
    all_errors = log + '\n'.join(path.read_text(encoding='utf-8', errors='replace') for path in crashes)
    if args.expect == 'pass':
        accepted = (observed.get('status') == 'PASS' and observed.get('hostMethodCount') == '9'
                    and observed.get('glError') == '0' and observed.get('fmlDeobfuscatedEnvironment') == 'false'
                    and int(observed.get('nativeFramesObserved', '0')) >= 4 and source_matches
                    and receipt['normal_unchanged'] and not receipt['timeout'] and not receipt['memory_limited']
                    and receipt['exit_code'] == 0
                    and (not mui2 or migration_passed))
        receipt['status'] = 'PASS' if accepted else 'FAIL'
    else:
        accepted = ('AbstractMethodError' in all_errors and 'drawDefaultBackground' in all_errors
                    and receipt['normal_unchanged'] and not receipt['timeout'] and not receipt['memory_limited']
                    and source_matches)
        receipt['status'] = 'EXPECTED_OLD_ABSTRACT_METHOD_ERROR' if accepted else 'FAIL'
    if receipt['memory_limited']:
        receipt['status'] = 'RESOURCE_LIMIT_STOPPED_NOT_PRODUCT_RESULT'
        receipt['product_verdict'] = 'NOT_EVALUATED'
    receipt['log_sha256'] = digest(log_path)
    receipt['crash_report_hashes'] = {str(path.relative_to(fixture)): digest(path) for path in crashes}
    receipt_file.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))
    return 2 if receipt['memory_limited'] else 1 if receipt['status'] == 'FAIL' else 0


if __name__ == '__main__':
    sys.exit(main())
