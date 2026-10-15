"""Validate four current real sulfur runs, then archive their exact pairs, receipts and worlds.

No Java is started. A suite JSON has exactly source-self, target-self, source-share,
target-share; each row names fixture, server_log and client_log. --check-only never
writes an archive. Historical 0.1.0 results cannot satisfy this contract.
"""
import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import struct

from collect_current import ROOT, FIXTURES, actual_run, recent, require, sha
from nbt_reader import read_nbt
from sulfur_evidence import (safe_file, digest, typed_compounds, java_gzip_hash, tag_at,
    native_completion, offline_uuid, world_files, saved_tile, saved_block, saved_chunk,
    native_author_saves, archive_world, nbt_bytes)
from workbench_validation import verify_blueprint_pair

STAGES = ['preset', 'identity', 'quantum', 'coordinates', 'nbt', 'hints']
STOCK = ['sulfurRemaining', 'oxygenRemaining', 'waterRemaining', 'sulfurDioxide', 'sulfurTrioxide', 'acid']
LABELS = ['source-self', 'target-self', 'source-share', 'target-share']


def json_file(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def frame_names(phase, intent):
    names = ['sulfur-quantum-native-bind-frame']
    if phase == 'source':
        names += ['sulfur-source-running-frame', 'sulfur-source-quantum-stopped-frame',
                  'sulfur-source-quantum-restored-frame', 'sulfur-source-capture-frame']
        for stage in range(6):
            names += [f'sulfur-processing-stage-{stage}-unconfirmed', f'sulfur-processing-stage-{stage}-choice']
            if stage == 2:
                names += ['sulfur-quantum-groups-default-frame']
                if intent == 'share':
                    names += ['sulfur-quantum-groups-multiselect-frame', 'sulfur-quantum-groups-unassigned-frame',
                              'sulfur-quantum-groups-two-named-frame']
                names += ['sulfur-quantum-groups-save-ack-frame']
        names += ['sulfur-processing-ack-frame', 'sulfur-processing-preset-review-frame',
                  'sulfur-source-save-new-frame', 'sulfur-source-overwrite-review-frame', 'sulfur-source-save-frame']
    else:
        names += ['sulfur-recipient-load-frame']
        if intent == 'self':
            names += ['sulfur-recipient-preserved-anchor-blocked-frame']
        else:
            for group in range(2):
                names += [f'sulfur-recipient-quantum-picker-group-{group}-frame',
                          f'sulfur-recipient-quantum-binding-group-{group}-ack-frame']
            names += ['sulfur-recipient-construct-review-frame', 'sulfur-target-running-frame',
                      'sulfur-target-quantum-stopped-frame', 'sulfur-target-quantum-restored-frame',
                      'sulfur-target-nc-decor-frame', 'sulfur-target-decor-frame']
    return names


def receipt(run, name, *, passed=True):
    path = safe_file(run['receipts'] / name, run['receipts'])
    recent(path, run['runs'])
    value = read_nbt(path)
    if passed:
        require(value.get('passed') and value.get('actor') == run['actor'], 'Wrong actual receipt actor/status: ' + name)
    return value


def validate_frames(run, client):
    names = frame_names(run['phase'], run['intent'])
    require(client['actualFrameReceipts'] == [name + '.nbt' for name in names], 'Missing, extra or reordered actual END frames')
    result = {}
    for name in names:
        frame = receipt(run, name + '.nbt')
        require(frame.get('actualFrame') and frame.get('actualNettyClient') and not frame.get('syntheticSnapshotsUsed')
                and frame.get('capturePhase') == 'RenderTick.END' and frame['world'] == run['environment']['world']
                and frame['dimension'] == run['dimension'] and frame['role'] == client['role'], 'Frame runtime/identity mismatch: ' + name)
        witness_name = frame['serverReceipt']
        require(re.fullmatch(r'sulfur-[a-z0-9-]+\.nbt', witness_name), 'Unbounded native witness name')
        witness_path = safe_file(run['receipts'] / witness_name, run['receipts'])
        recent(witness_path, run['runs'])
        witness = read_nbt(witness_path)
        require(frame['serverWitness'] == witness and witness.get('passed') and witness['actor'] == run['actor']
                and frame['serverReceiptSha256'] == sha(witness_path) and frame['runtimePhase'] == witness['phase'],
                'END framebuffer detached from its exact immutable real server receipt')
        png = safe_file(run['receipts'] / (name + '.png'), run['receipts'])
        recent(png, run['runs'])
        raw = png.read_bytes()
        require(frame['png'] == png.name and frame['pngSha256'] == sha(png) and raw[:8] == b'\x89PNG\r\n\x1a\n'
                and raw[12:16] == b'IHDR' and min(struct.unpack('>II', raw[16:24])) > 0, 'END PNG integrity failed')
        typed = typed_compounds(run['receipts'] / (name + '.nbt'))
        require(java_gzip_hash(typed['actualUi']) == frame['actualUiSha256'], 'Native typed UI hash failed')
        if 'actualSnapshot' in frame:
            require(java_gzip_hash(typed['actualSnapshot']) == frame['snapshotSha256']
                    and frame['actualSnapshot']['session'] == frame['session'], 'Actual Netty snapshot hash/session failed')
        if 'requestedUi' in frame:
            require(frame['actualRenderGeneration'] > frame['requestedAfterGeneration']
                    and frame['actualUi']['renderGeneration'] == frame['actualRenderGeneration'], 'Stale rendered GUI frame')
            if frame.get('quantumGroupingScreen'):
                for key in ['groups', 'selection', 'saveEnabled', 'unassigned']:
                    require(frame['requestedUi'][key] == frame['actualUi'][key], 'Grouping changed before actual END capture')
            else:
                for key in ['processingFlow', 'flowStage', 'flowChosen', 'networkDrawer', 'confirmation']:
                    require(frame['requestedUi'].get(key) == frame['actualUi'].get(key), 'Native modal changed before END')
        else:
            require(frame['actualUi'].get('worldFocused'), 'Actual world screenshot was obscured by a modal')
        result[name] = frame
    actual = {path.stem for path in run['receipts'].glob('*.png')}
    require(actual == set(names), 'Unreviewed PNG or omitted expected screenshot remains in this actual run')
    return result


def validate_topology(line, *, running):
    topology = line['topology']
    require(topology.get('nativeGraphObserved') and not topology['ordinaryBypassRoute']
            and not topology['localStorageOrAePowerPlaced'] and not topology['directFixtureHatchEuWrites'],
            'Actual native graph permits an ordinary bypass or local source')
    require(bool(topology['allInputsOnRemoteGrid']) == running, 'Actual input/storage grid identity contradicts phase')
    if running:
        require(topology['productionGatewayCount'] == len(topology['nativeGateways']) == 3
                and topology['localGridIdentity'] == topology['remoteGridIdentity'], 'Three real production gateways must reach remote grid')
        controller = [line.get('dimension', 0)] + topology['remoteController']
        for gateway in topology['nativeGateways']:
            anchor = [gateway[key] for key in ['anchorDim', 'anchorX', 'anchorY', 'anchorZ']]
            require(anchor[1:] == controller[1:], 'Actual gateway inherited a source/foreign controller')
            matching = [edge for edge in topology['actualGatewayEdges'] if edge['from']['position'][1:]
                        == [gateway['x'], gateway['y'], gateway['z']] and not edge['directional']]
            require(len(matching) == 1 and matching[0]['to']['position'] == anchor
                    and matching[0]['to']['hostClass'] == 'appeng.tile.networking.TileController'
                    and matching[0]['from']['playerId'] == topology['remoteOwnerPlayerId'],
                    'Gateway has no actual UNKNOWN bridge edge to its own native controller/owner')
    else:
        require(topology['productionGatewayCount'] == 0 and topology['localGridIdentity'] != topology['remoteGridIdentity'],
                'Disconnected stop still has a real production gateway or remote grid route')


def validate_runtime(run, name):
    proof = receipt(run, name)
    line = proof['nativeLine']
    typed_line = tag_at(read_nbt(run['receipts'] / name, typed=True), 'nativeLine')
    require(line['operational'] and line['outputsAreNative'] and line['acid'] > 0
            and all(line[key] > 0 for key in ['sulfurConsumed', 'oxygenConsumed', 'waterConsumed'])
            and line['acid'] <= min(line['sulfurConsumed'] * 1000, line['waterConsumed']), 'Native acid has no finite stoichiometric debit')
    initial = line['initialStock']
    require([initial[key] for key in ['sulfur', 'oxygen', 'water', 'acidSupplied']] == [64, 192000, 64000, 0]
            and initial['batteryEuPerReactor'] == 100000, 'Unreviewed or infinite process supply')
    require(len(line['machines']) == 3, 'Three real LCR stages required')
    for i, machine in enumerate(line['machines']):
        require(machine['stage'] == i and machine['automaticMaintenance'] and machine['repairStatus'] == 6
                and 0 < machine['batteryDebitedEU'] <= machine['batteryInitialEU'] <= 100000
                and machine['fixtureDirectHatchEuInjected'] == 0, 'Real finite battery/maintenance evidence failed')
        native_completion(machine, tag_at(typed_line, 'machines')[1][1][i], line, typed_line)
    validate_topology(line, running=True)
    return line


def stable_state(line):
    return [line[key] for key in STOCK] + [[row[key] for key in ['nativeRecipesDone', 'progress', 'maxProgress',
        'batteryRemainingEU', 'outputCache']] for row in line['machines']]


def validate_cycle(run):
    label = run['phase']
    cut = receipt(run, f'sulfur-{label}-quantum-cut.nbt')
    stop = receipt(run, f'sulfur-{label}-quantum-stopped.nbt')
    restored = receipt(run, f'sulfur-{label}-quantum-restored.nbt')
    require(len(cut['removedNativeTiles']) == 3 and len({(node['x'], node['y'], node['z'])
            for node in cut['removedNativeTiles']}) == 3, 'Fault injection omitted a production gateway')
    require(stop['gatewayPhysicallyAbsent'] and stop['consecutiveStableTicks'] >= 200
            and stop['stableStartTick'] - stop['cutTick'] >= 200
            and stop['worldTick'] - stop['stableStartTick'] >= 200
            and stable_state(stop['stableStart']) == stable_state(stop['stableEnd']),
            'Disconnected finite buffers/remote stock/recipes never reached a measured natural stable stop')
    validate_topology(stop['stableStart'], running=False)
    validate_topology(stop['stableEnd'], running=False)
    before, after = restored['restoredBaseline'], restored['nativeLine']
    require(not restored['additionalFixtureStockOrPowerInjected'] and after['worldTick'] > stop['worldTick']
            and after['acid'] > before['acid'] and after['sulfurRemaining'] < before['sulfurRemaining']
            and after['waterRemaining'] < before['waterRemaining'], 'Restoration did not natively consume remote ingredients and produce new acid')
    for old, new in zip(before['machines'], after['machines']):
        boundaries = new['nativeCompletionBoundaries']
        require(new['nativeRecipesDone'] > old['nativeRecipesDone'] or len(boundaries) > len(old['nativeCompletionBoundaries']),
                'Restoration only flushed ME caches; each actual reactor must complete another native recipe')
        if new['nativeRecipesDone'] <= old['nativeRecipesDone']:
            require(boundaries[-1]['after']['worldTick'] > before['worldTick'], 'Recovery boundary predates physical restoration')
    validate_topology(after, running=True)
    return dict(cut_tick=cut['worldTick'], stable_ticks=stop['consecutiveStableTicks'],
                restored_tick=restored['worldTick'], acid_before=before['acid'], acid_after=after['acid'])


def validate_author(run, client, frames):
    require(client['intent'] == run['intent'] and all(client[key] for key in ['processingComplete', 'newSave',
        'overwriteSave', 'processingPresetSave']), 'Actual tendency/new/overwrite/preset GUI coverage incomplete')
    transfer = receipt(run, 'sulfur-author-transfer.nbt')
    data, png = bytes(transfer['exportedJson']), bytes(transfer['exportedPng'])
    pair = verify_blueprint_pair(data, png)
    require(pair['jsonSha256'] == transfer['exportedSha256'] == client['exportedJsonSha256']
            and pair['pngSha256'] == transfer['exportedPngSha256'] == client['exportedPngSha256'], 'Exact native source pair changed')
    policy = transfer['serverProcessingPolicy']
    require(policy['confirmedStages'] == STAGES and policy['tendency'] == run['intent']
            and policy['quantum'] == ('preserve' if run['intent'] == 'self' else 'rebind')
            and policy['ignoredNbtKeys'] == ['mStoredEnergy'], 'Actual six-stage tendency policy differs from its reviewed defaults')
    for stage in range(6):
        before, chosen = (frames[f'sulfur-processing-stage-{stage}-{kind}'] for kind in ['unconfirmed', 'choice'])
        require(before['actualUi']['flowStage'] == chosen['actualUi']['flowStage'] == stage
                and before['actualLocalPolicy']['confirmedStages'] == chosen['actualLocalPolicy']['confirmedStages'] == STAGES[:stage]
                and chosen['nativeMouseEvents'] > before['nativeMouseEvents'], 'A real stage choice/Next was bypassed or reordered')
    groups = frames['sulfur-quantum-groups-save-ack-frame']['actualSnapshot']['processing']['quantumGroups']
    require(sum(len(group['cells']) for group in groups) == 3 and len(groups) == (1 if run['intent'] == 'self' else 2),
            'Server classification omitted or duplicated a physical gateway')
    if run['intent'] == 'share':
        require({group['name'] for group in groups} == {'前段供料', '后段供料'}, 'Actual Chinese group names were not retained')
        actions = {item['action']: item for item in client['quantumGroupingInteractions']}
        for action in ['native-T-selects-all-three', 'native-CtrlT-complement-clears-all',
            'native-preview-Ctrl-node-multiselect', 'native-unassigned-gates-save',
            'native-CtrlZ-restores-complete-grouping', 'native-CtrlY-restores-incomplete-gate']:
            require(action in actions, 'Missing real classification keyboard/selection evidence: ' + action)
        require(not frames['sulfur-quantum-groups-unassigned-frame']['actualUi']['saveEnabled'], 'Incomplete grouping was saveable')
    saved = native_author_saves(run, frames, client, data, png)
    local = run['fixture'] / 'client/MiaoBlueprints' / (client['localId'] + '.json')
    require(safe_file(local, run['fixture']).read_bytes() == data and local.with_suffix('.png').read_bytes() == png,
            'Actual client disk pair differs from the native owner-ACK export')
    require(run['final']['sourceMeBeforeCapture'] == transfer['sourceMeBeforeCapture']
            and run['final']['sourceMeAfterAuthor'] == transfer['sourceMeAfterAuthor'], 'Source ME observation was substituted')
    before = receipt(run, 'sulfur-server-ready.nbt')['nativeLine']
    require(before['quiescent'] and all(before[key] == transfer['sourceNativeLine'][key] for key in STOCK),
            'Capture/processing/save debited stopped source ME stock')
    model = pair['model']
    nodes = []
    for cell in model['cells']:
        if cell.get('tileNbt'):
            tag = nbt_bytes(base64.b64decode(cell['tileNbt'], validate=True))
            if tag.get('id') == 'gtswn.network_quantum_node':
                nodes.append(tag)
            require('mStoredEnergy' not in tag, 'Portable blueprint granted source GT stored EU')
    require(len(nodes) == 3, 'Actual native pair lacks the three captured quantum gateways')
    return dict(pair=pair, transfer=transfer, save_transactions=saved)


def validate_target(run, client, frames):
    transfer_path = safe_file(run['receipts'] / 'sulfur-author-transfer.nbt', run['receipts'])
    require(sha(transfer_path) == run['environment']['source_transfer_sha256'], 'Target imported a substituted source transfer')
    transfer = read_nbt(transfer_path)
    pair = verify_blueprint_pair(bytes(transfer['exportedJson']), bytes(transfer['exportedPng']))
    local = safe_file(run['fixture'] / 'client/MiaoBlueprints' / (client['localId'] + '.json'), run['fixture'])
    require(local.read_bytes() == bytes(transfer['exportedJson']) and local.with_suffix('.png').read_bytes() == bytes(transfer['exportedPng']),
            'Target did not import the exact actual author JSON/PNG pair')
    before, after = run['final']['constructionMeStockBefore'], run['final']['constructionMeStockAfter']
    require(before['ready'] and before['supplied'] and before['actor'] == run['actor'] and not before['nativeCellInfinite'],
            'Actual construction stock did not come from the finite authenticated native bank')
    if run['intent'] == 'self':
        require(client['preservedAnchorRejected'] and run['final']['preservedAnchorRejected']
                and run['final']['allTargetFootprintAir'] and not run['final']['targetRuntimeExpected']
                and before['currentStock'] == after['currentStock'], 'Private original-world rejection consumed stock or placed free machinery')
        snapshot = frames['sulfur-recipient-preserved-anchor-blocked-frame']['actualSnapshot']
        groups = snapshot['networkBindings']['groups']
        require(groups and all(not group['bound'] and not group['terminalBinding'] and '其他存档' in group['reason'] for group in groups),
                'Private original anchor was silently rebound or lacked explicit foreign-world feedback')
    else:
        require(after['paid'] and not run['final']['targetStockFromBlueprint']
                and all(run['final']['targetBeforeFixtureSupply'][key] == 0 for key in STOCK), 'Target received unpaid/source process inventory')
        require(all(row['storedEU'] == row['batteryInitialEU'] == row['fixtureDirectHatchEuInjected'] == 0
                    for row in run['final']['targetBeforeFixtureSupply']['machines']), 'Blueprint granted stored EU before the independent finite battery fixture')
        for kind in ['itemLedger', 'fluidLedger']:
            require(len(before[kind]) == len(after[kind]), 'Construction bill changed during payment')
            for a, b in zip(before[kind], after[kind]):
                require(a['supplied'] > 0 and a['remaining'] == a['supplied'] and a['nativeDebited'] == 0
                        and b['remaining'] == 0 and b['nativeDebited'] == a['supplied'], 'Construction was not actually paid once')
        first = frames['sulfur-recipient-quantum-binding-group-0-ack-frame']['actualSnapshot']['networkBindings']['groups']
        second = frames['sulfur-recipient-quantum-binding-group-1-ack-frame']['actualSnapshot']['networkBindings']['groups']
        require(len(first) == len(second) == 2 and first[0]['bound'] and not first[1]['bound']
                and all(group['bound'] and group['terminalBinding'] for group in second), 'Placement did not bind each named group independently')
        validate_runtime(run, 'sulfur-target-running.nbt')
    return dict(pair=pair, transfer=transfer)


def validate_run(label, entry):
    phase, intent = label.split('-')
    fixture = Path(entry['fixture']).resolve()
    require(fixture.is_relative_to(FIXTURES.resolve()) and fixture.name.startswith('v011-'), 'Only explicit fresh v011 fixtures are accepted')
    environment = json_file(safe_file(fixture / 'environment.json', fixture))
    require(environment['release_version'] == '0.1.1' and environment['profile'] == 'sulfur'
            and environment['sulfur_phase'] == phase and environment['sulfur_intent'] == intent, 'Wrong current phase/tendency environment')
    require(all(environment[key] is False for key in ['user_worlds_copied', 'user_configs_copied', 'accounts_read',
        'shared_running_instances_touched']), 'Actual fixture isolation contract failed')
    require(set(environment['roles']) == {'server', 'client'}, 'Each actual world needs its own paired server/client run')
    runs, launches, logs = [], {}, {}
    for role in ['server', 'client']:
        log = Path(entry[role + '_log']).resolve()
        safe_file(log, ROOT)
        actual = actual_run(log, fixture, role)
        launch = json_file(fixture / (role + '-launch.json'))
        require(launch['release_version'] == '0.1.1' and actual['profile'] == 'sulfur' and actual['role'] == role,
                'Actual Java run uses another fixture profile/version')
        for flag in [f'-Dgtmb.hologram.sulfurLinePhase={phase}', f'-Dgtmb.hologram.sulfurLineIntent={intent}',
                     f'-Dgtmb.hologram.sulfurLineDimension={environment["sulfur_dimension"]}']:
            require(launch['arguments'].count(flag) == 1, 'Actual launch did not freeze a unique current phase/tendency/dimension')
        cp = launch['arguments'][launch['arguments'].index('-cp') + 1]
        require('gtswn-v1.8.66-dev.jar' in cp and 'AE2FluidCraft-Rework-1.5.113-gtnh-dev.jar' in cp,
                'Actual runtime lacks reviewed GTSWN 1.8.66/AE2FC 1.5.113')
        dependencies = launch.get('reviewed_runtime_hashes', {})
        require(len(dependencies) == 2 and {Path(path).name for path in dependencies}
                == {'gtswn-v1.8.66-dev.jar', 'AE2FluidCraft-Rework-1.5.113-gtnh-dev.jar'}, 'Reviewed native runtime SHA256 set is incomplete')
        for path, checksum in dependencies.items():
            require(sha(Path(path)) == checksum, 'Actual reviewed native runtime jar changed')
        descriptor = environment['roles'][role]
        require(descriptor['launch_sha256'] == sha(fixture / (role + '-launch.json')), 'Environment launch digest differs')
        for jar, checksum in descriptor['support_jars'].items():
            require(sha(safe_file(fixture / role / 'mods' / jar, fixture / role)) == checksum, 'Actual support mod jar changed')
        require(datetime.fromisoformat(actual['started_utc']) >= datetime.fromisoformat(environment['created_utc']),
                'Actual Java predates this fresh fixture')
        runs.append(actual)
        launches[role], logs[role] = launch, log
    require(datetime.fromisoformat(runs[0]['started_utc']) < datetime.fromisoformat(runs[1]['finished_utc'])
            and datetime.fromisoformat(runs[1]['started_utc']) < datetime.fromisoformat(runs[0]['finished_utc']),
            'Actual paired Netty server/client runs did not overlap')
    receipts = fixture / 'receipts'
    require(not any('fail' in path.name.lower() for path in receipts.iterdir()), 'Failed evidence remains in selected candidate')
    final = read_nbt(safe_file(receipts / 'sulfur-server-final.nbt', receipts))
    expected = 'TARGET_PRIVATE_REJECTION_PASS' if label == 'target-self' else phase.upper() + '_PASS'
    require(final['passed'] and final['actualServerSessions'] and final['result'] == expected
            and final['actor'] == offline_uuid(environment['offline_fixture_username'])
            and final['world'] == environment['world'] and final['dimension'] == environment['sulfur_dimension'],
            'Independent actual server final/actor/dimension failed')
    run = dict(label=label, fixture=fixture, environment=environment, receipts=receipts, runs=runs, launches=launches,
               logs=logs, final=final, actor=final['actor'], phase=phase, intent=intent, dimension=final['dimension'])
    for name in ['sulfur-server-pass.txt', 'sulfur-' + ('author' if phase == 'source' else 'placer') + '-pass.txt']:
        path = safe_file(receipts / name, receipts)
        recent(path, runs)
        require('PASS' in path.read_text(encoding='utf-8'), 'Actual success marker missing')
    client = receipt(run, 'sulfur-' + ('author' if phase == 'source' else 'placer') + '-final.nbt')
    require(client['actualNettyClient'] and not client['syntheticSnapshotsUsed'] and not client['controllerMemoryWritesUsed'],
            'Actual client claimed synthetic/controller-memory actions')
    frames = validate_frames(run, client)
    run['client'], run['frames'] = client, frames
    run['artifact'] = validate_author(run, client, frames) if phase == 'source' else validate_target(run, client, frames)
    if phase == 'source' or intent == 'share':
        validate_runtime(run, f'sulfur-{phase}-running.nbt')
        run['quantum_cycle'] = validate_cycle(run)
    world, files = world_files(run)
    run['world'], run['world_files'] = world, files
    dim_world = world if run['dimension'] == 0 else world / ('DIM' + str(run['dimension']))
    bootstrap = receipt(run, 'sulfur-quantum-bootstrap-ready.nbt')
    cx, cy, cz = bootstrap['controller']
    storage = saved_tile(run, dim_world, cx + 3, cy, cz + 3)
    require(storage.get('id') == 'gtswn.network_quantum_node' and [storage[key] for key in ['anchorDim', 'anchorX', 'anchorY', 'anchorZ']]
            == [run['dimension'], cx, cy, cz], 'Saved external drive has no real own-controller quantum gateway')
    drive = saved_tile(run, dim_world, cx + 4, cy, cz + 3)
    require(drive.get('id') == 'BlockDrive', 'External native drive is missing in saved actual world')
    owner = (run['final']['constructionMeStockBefore']['nativeControllerOwner'] if phase == 'target' else
             receipt(run, 'sulfur-source-running.nbt')['nativeLine']['topology']['remoteOwnerPlayerId'])
    require(storage.get('proxy', {}).get('p') == drive.get('proxy', {}).get('p') == owner,
            'Saved external storage node/drive AE owner differs from this actual native player')
    settings = safe_file(world / 'AE2/settings.cfg', world)
    recent(settings, [run['runs'][0]])
    require(re.search(r'^\s*I:"?' + re.escape(run['actor']) + r'"?\s*=\s*' + str(owner) + r'\s*$',
                      settings.read_text(encoding='utf-8-sig'), re.MULTILINE), 'Saved AE player ID does not resolve to the real connected recipient/author UUID')
    for dx, dy, dz in [(1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]:
        require(saved_block(run, dim_world, cx + 4 + dx, cy + dy, cz + 3 + dz)['block_id'] == 0,
                'External drive has an unreviewed ordinary cable/power bypass neighbor')
    bounds = receipt(run, 'sulfur-server-ready.nbt' if phase == 'source' else 'sulfur-target-ready.nbt')['bounds']
    if label == 'target-self':
        chunks = {}
        for x in range(bounds[0], bounds[3] + 1):
            for z in range(bounds[2], bounds[5] + 1):
                key = (x >> 4, z >> 4)
                if key not in chunks:
                    chunks[key] = saved_chunk(run, dim_world, x, z)
                for y in range(bounds[1], bounds[4] + 1):
                    sections = [section for section in chunks[key].get('Sections', []) if section['Y'] == y >> 4]
                    require(len(sections) <= 1, 'Duplicate private-target native section')
                    if sections:
                        section = sections[0]
                        index = (y & 15) << 8 | (z & 15) << 4 | (x & 15)
                        high = (section.get('Add', [0] * 2048)[index >> 1] >> (4 * (index & 1))) & 15
                        require(section['Blocks'][index] == high == 0, 'Actual saved private-target footprint contains a placed block')
    else:
        origin = [64, 80, 64] if phase == 'source' else receipt(run, 'sulfur-target-ready.nbt')['targetOrigin']
        x, y, z = origin
        for px, py, pz in [(x + 13, y + 1, z + 3), (x + 3, y + 1, z + 4), (x + 8, y + 1, z + 4)]:
            node = saved_tile(run, dim_world, px, py, pz)
            require(node.get('id') == 'gtswn.network_quantum_node' and node.get('proxy', {}).get('p') == owner
                    and [node[key] for key in ['anchorDim', 'anchorX', 'anchorY', 'anchorZ']] == [run['dimension'], cx, cy, cz],
                    'Saved production gateway inherited a foreign anchor or AE owner')
    return run


def collect(args):
    suite = json_file(args.suite)
    require(set(suite) == set(LABELS), 'Suite must list exactly the four fresh self/share source/target runs')
    runs = {label: validate_run(label, suite[label]) for label in LABELS}
    require(len({run['fixture'] for run in runs.values()}) == 4 and len({run['environment']['world'] for run in runs.values()}) == 4,
            'Four runs reused a fixture or actual world')
    require(runs['source-self']['actor'] == runs['source-share']['actor']
            and len({runs['source-self']['actor'], runs['target-self']['actor'], runs['target-share']['actor']}) == 3,
            'Author identity must match both tendencies; two recipient players must each differ')
    require(runs['source-self']['dimension'] == runs['source-share']['dimension'] == 0
            and runs['target-self']['dimension'] not in (0, runs['target-share']['dimension'])
            and runs['target-share']['dimension'] != 0, 'Source and both target dimensions must remain visibly distinct')
    require(runs['source-self']['artifact']['pair']['jsonSha256'] != runs['source-share']['artifact']['pair']['jsonSha256'],
            'Both tendencies reused one native pair')
    hashes = [run['launches'][role]['source_hashes'] for run in runs.values() for role in ['server', 'client']]
    require(all(value == hashes[0] for value in hashes), 'Four actual candidates did not freeze identical source bytes')
    for field in ['engine_jar_hashes', 'reviewed_runtime_hashes']:
        values = [run['launches'][role][field] for run in runs.values() for role in ['server', 'client']]
        require(all(value == values[0] for value in values), 'Four actual candidates did not freeze identical engine/native runtime bytes: ' + field)
    for intent in ['self', 'share']:
        source, target = runs['source-' + intent], runs['target-' + intent]
        require(Path(target['environment']['source_fixture']).resolve() == source['fixture']
                and source['artifact']['pair']['jsonSha256'] == target['artifact']['pair']['jsonSha256']
                and source['artifact']['pair']['pngSha256'] == target['artifact']['pair']['pngSha256'],
                'A recipient used the other tendency or historical author pair')
    summary = dict(release_version='0.1.1', profile='sulfur-four-fresh-worlds-real-quantum', status='PASS_FOR_LISTED_SCOPE',
        historical_pass_reused=False, games_started_by_collector=False,
        collected_utc=datetime.now(timezone.utc).isoformat(),
        runs={label: dict(fixture=str(run['fixture']), actor=run['actor'], world=run['environment']['world'],
              dimension=run['dimension'], actual_java=run['runs'], native_pair=run['artifact']['pair']['metadata'],
              quantum_cycle=run.get('quantum_cycle'), source_json_sha256=run['artifact']['pair']['jsonSha256'],
              source_png_sha256=run['artifact']['pair']['pngSha256']) for label, run in runs.items()})
    if args.check_only:
        return summary
    require(args.output is not None, 'Archive requires an explicit fresh --output under plan/evidence/v0.1.1')
    output = args.output.resolve()
    require(output.is_relative_to((ROOT / 'plan/evidence/v0.1.1').resolve()) and not output.exists(), 'Never overwrite evidence or archive another version')
    output.mkdir(parents=True)
    manifest = {}
    for label, run in runs.items():
        target = output / label
        target.mkdir()
        selected = [(run['fixture'] / 'environment.json', 'environment.json')]
        selected += [(path, 'receipts/' + path.name) for path in sorted(run['receipts'].iterdir()) if path.is_file()]
        for role in ['server', 'client']:
            selected += [(run['fixture'] / (role + '-launch.json'), role + '-launch.json'),
                         (run['logs'][role], role + '.log'), (Path(str(run['logs'][role]) + '.receipt.json'), role + '-actual-run.json')]
        for path in (run['fixture'] / 'client/MiaoBlueprints').glob('*'):
            if path.suffix in ('.json', '.png'):
                selected.append((path, 'client-library/' + path.name))
        for source, relative in selected:
            safe_file(source, ROOT)
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            manifest[label + '/' + relative] = dict(bytes=destination.stat().st_size, sha256=sha(destination))
        world = archive_world(run['world'], run['world_files'], target / 'world.zip')
        summary['runs'][label]['world_archive'] = world
    tooling = output / 'tooling'
    tooling.mkdir()
    for path in Path(__file__).parent.iterdir():
        if path.is_file() and path.suffix in ('.py', '.ps1', '.gradle', '.md', '.json'):
            shutil.copy2(path, tooling / path.name)
    summary['files'] = manifest
    (output / 'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path)
    parser.add_argument('--label', choices=LABELS)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--server-log', type=Path)
    parser.add_argument('--client-log', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    if not args.suite:
        require(args.label and args.fixture and args.server_log and args.client_log,
                'Single current run validation needs --label --fixture --server-log --client-log')
        require(args.output is None, 'Single-run validation cannot create an end-to-end suite archive')
        run = validate_run(args.label, dict(fixture=str(args.fixture), server_log=str(args.server_log), client_log=str(args.client_log)))
        print(json.dumps(dict(status='PASS_FOR_SINGLE_RUN_LISTED_SCOPE', label=run['label'], actor=run['actor'],
            dimension=run['dimension'], historical_pass_reused=False, full_suite_validated=False), ensure_ascii=False))
        return
    result = collect(args)
    print(json.dumps({key: result[key] for key in ['status', 'profile', 'historical_pass_reused']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
