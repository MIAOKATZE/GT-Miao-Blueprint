"""Verify and archive one explicitly selected new fixture; never reuse historical PASS files."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

from nbt_reader import read_nbt
from workbench_validation import (at, validate_keyboard, validate_nc_payment, validate_workbench_source,
                                  validate_saved_workbench)

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'gradle.properties').is_file() and (parent / 'src').is_dir())
FIXTURES = ROOT / 'build/hologram-followup'
DECOR = [f'decor-{family}-{mode}' for family in range(4)
         for mode in ('raw-ceil-refund', 'prebuilt', 'creative-empty')] + ['architecture-91-three-quarters-secondary',
            'decor-prefab-reserved-for-later-inventory-asset'] + [f'decor-{family}-factory-id-{kind}'
            for family in range(4) for kind in ('bogus', 'numeric')] + [
            'carpenter-existing-owner-level0-native-install', 'carpenter-existing-owner-level1-service-refund']
LOCAL_ESSENTIA = ['tc-jar-local-source-payment-refund', 'tc-filled-jar-is-not-fuzzy-empty-shell',
                  'tc-creative-empty-source-does-not-copy-contents', 'tc-creative-real-source-content-payment']


def require(value, message):
    if not value:
        raise ValueError(message)


def full_review_frame(frame):
    ui = at(frame, 'actualUi')
    active = at(ui, 'processingFlow')
    require(active in ((1, 0), (1, 1)), 'Processing flow lost its actual typed modal state')
    if active == (1, 0):
        require(at(ui, 'flowFullReview') == (1, 0) and at(ui, 'flowRequiredStages') == (3, -1),
                'Closed processing flow retained a fabricated full-review mode or content mask')
        return None
    mask = at(frame, 'actualSnapshot', 'processing', 'requiredStages')
    require(mask[0] == 3 and 0 <= mask[1] <= 63 and at(ui, 'flowRequiredStages') == mask
            and at(ui, 'flowFullReview') == (1, 1),
            'Actual manual processing frame lacks full review or differs from the authoritative content-derived mask')
    return mask


def full_review_selection(before, after, stage_frame, *, click_counts=None, policies=None, snapshots=None):
    require(before[0] == after[0] == 10 and at(before, 'processingFlow') == at(after, 'processingFlow') == (1, 1)
            and at(before, 'flowFullReview') == (1, 0) and at(after, 'flowFullReview') == (1, 1)
            and at(after, 'flowStage') == (3, 0) and at(after, 'flowChosen') == (1, 0),
            'Native 296 did not change the actual adaptive modal to six fresh manual stages')
    mask = full_review_frame(stage_frame)
    require(mask is not None and at(before, 'flowRequiredStages') == at(after, 'flowRequiredStages') == mask,
            'Native full-review selection rewrote the server content mask or belongs to a different source')
    for key in ('session', 'planRevision'):
        require(at(before, key) == at(after, key) == at(stage_frame, 'actualSnapshot', key),
                'Native full-review toggle changed the authoritative source session/revision')
    require(at(before, 'session')[0] == 8 and at(before, 'planRevision')[0] == 4,
            'Native full-review source identity has the wrong NBT types')
    controls = at(before, 'drawnControls')
    require(controls[0] == 9 and controls[1][0] == 10, 'Native full-review entry lacks actual rendered controls')
    buttons = [control for control in controls[1][1] if at(control, 'id') == (3, 296)]
    require(len(buttons) == 1 and at(buttons[0], 'enabled') == (1, 1),
            'Native full review was not selected through one visible enabled control 296')
    before_generation, after_generation = at(before, 'renderGeneration'), at(after, 'renderGeneration')
    require(before_generation[0] == after_generation[0] == 4
            and at(stage_frame, 'actualRenderGeneration')[0] == 4
            and 0 < before_generation[1] <= after_generation[1] < at(stage_frame, 'actualRenderGeneration')[1]
            and at(stage_frame, 'capturePhase') == (8, 'RenderTick.END'),
            'Native full-review entry is not followed by a fresh actual full-mode processing END')
    if snapshots is None:
        require(at(stage_frame, 'actualUi', 'flowStage') == (3, 0) and at(stage_frame, 'actualUi', 'flowChosen') == (1, 0),
                'Native complete-review selection did not produce its captured untouched first stage')
    if click_counts is not None:
        old, new = click_counts
        require(old[0] == new[0] == 4 and old[1] >= 0 and new[1] == old[1] + 1,
                'Native full-review selection did not consume exactly one real mouse click')
    if policies is not None:
        old, new = policies
        expected = copy.deepcopy(old)
        expected[1]['confirmedStages'] = (9, (0, []))
        require(new == expected, 'Native full review modified stored processing choices beyond clearing stage confirmations')
    if snapshots is not None:
        old, new = snapshots
        require(after_generation[1] > before_generation[1], 'Workbench full-review selection lacks a later actual rendered modal')
        next_buttons = [control for control in at(after, 'drawnControls')[1][1] if at(control, 'id') == (3, 231)]
        require(len(next_buttons) == 1 and at(next_buttons[0], 'enabled') == (1, 0),
                'Workbench full-review first rendered stage exposes Next before its explicit choice')
        for key in ('session', 'planRevision', 'x', 'y', 'z', 'dimension', 'cells', 'cellNbtEntries',
                    'processingPolicy', 'processingRelations', 'virtualPresetId', 'virtualPresetName'):
            require(at(old, key) == at(new, key), 'Native full review changed source, assets, NBT, policy or plan: ' + key)
        require(at(old, 'processing', 'requiredStages') == at(new, 'processing', 'requiredStages') == mask,
                'Native full review changed the authoritative server-required content stages')
    return mask


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_client_game_directory(spec, fixture, receipt):
    """cwd and Minecraft's explicit game directory must identify the same fresh client."""
    expected = (fixture / 'client').resolve()
    require(expected.is_dir() and expected.parent == fixture.resolve(), 'Actual fresh client directory is missing or escaped its fixture')
    arguments = spec.get('arguments')
    require(isinstance(arguments, list) and all(isinstance(value, str) for value in arguments),
            'Actual client launch has no bounded string argument list')
    positions = [index for index, value in enumerate(arguments) if value == '--gameDir' or value.startswith('--gameDir=')]
    require(len(positions) == 1 and arguments[positions[0]] == '--gameDir',
            'Actual client launch must contain one explicit --gameDir argument')
    index = positions[0]
    require(index + 1 < len(arguments) and arguments[index + 1] and not arguments[index + 1].startswith('--'),
            'Actual client --gameDir has no path value')
    game_directory = Path(arguments[index + 1])
    require(game_directory.is_absolute(), 'Actual client --gameDir must name an absolute fresh directory')
    require(game_directory.resolve() == expected
            == Path(receipt['working_directory']).resolve() == Path(spec['workingDirectory']).resolve(),
            'Actual client --gameDir, launch cwd and run receipt must equal this fixture/client')


def actual_run(log, fixture, role):
    receipt_path = Path(str(log) + '.receipt.json')
    receipt = json.loads(receipt_path.read_text(encoding='utf-8-sig'))
    require(receipt['exit_code'] == 0 and receipt['log_sha256'] == sha(log), 'Actual Java exit/log integrity failed')
    launch = fixture / (role + '-launch.json')
    require(receipt['launch_sha256'] == sha(launch), 'Actual launch descriptor changed')
    require(Path(receipt['working_directory']).resolve() == (fixture / role).resolve(), 'Actual run used another fixture')
    spec = json.loads(launch.read_text(encoding='utf-8'))
    if role == 'client':
        verify_client_game_directory(spec, fixture, receipt)
    for name, expected in spec['source_hashes'].items():
        require(sha(ROOT / name) == expected, 'Sources changed since actual launch export: ' + name)
    for name, expected in spec['engine_jar_hashes'].items():
        require(sha(Path(name)) == expected, 'Engine bytes changed since actual launch export: ' + name)
    raw = log.read_bytes()
    text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig', errors='strict')
    require('FINAL FAIL' not in text, 'Actual fixture log has failure')
    return receipt


def recent(path, runs):
    require(path.is_file(), 'Missing new receipt: ' + str(path))
    modified = path.stat().st_mtime
    require(any(datetime.fromisoformat(run['started_utc']).timestamp() - 2 <= modified
        <= datetime.fromisoformat(run['finished_utc']).timestamp() + 2 for run in runs),
        'Receipt predates or follows selected actual run: ' + str(path))


def collect(args):
    fixture = args.fixture.resolve()
    require(fixture.is_relative_to(FIXTURES.resolve()), 'Only new isolated follow-up fixtures are accepted')
    environment = json.loads((fixture / 'environment.json').read_text(encoding='utf-8'))
    require(environment['profile'] == args.profile and environment['release_version'] == '0.1.1', 'Fixture profile/version mismatch')
    require(environment.get('user_worlds_copied') is False and environment.get('shared_running_instances_touched') is False,
            'Environment did not preserve isolated-world/instance boundaries')
    paired = args.profile in ('area', 'workbench')
    require(not paired or args.client_log is not None, 'Actual paired client log is required')
    runs = [actual_run(args.server_log, fixture, 'server')]
    if paired:
        runs.append(actual_run(args.client_log, fixture, 'client'))
    if args.profile == 'workbench':
        client_launch = json.loads((fixture / 'client-launch.json').read_text(encoding='utf-8'))
        require(client_launch['arguments'].count('-Dgtmb.hologram.previewCaptureWitness=true') == 1,
                'Actual workbench native capture witness must be enabled once in the launched client')
    receipts = fixture / 'receipts'
    require(not list(receipts.glob('*fail*')), 'Fixture contains failed evidence; do not downgrade or archive as success')
    selected = []
    scopes = []
    if args.profile == 'area':
        required = ['area-server-ready.nbt', 'area-client-final.nbt', 'area-server-final.nbt',
                    'area-client-pass.txt', 'area-server-pass.txt']
        expected_labels = ['begin-ack', 'right-corner-first-ack', 'other-slot-paused-no-input-consumption',
            'resume-left-completes-original-add-mode', 'alt-zero-current-red-target-feet',
            'alt-current-red-target-equals-server-corner', 'held-projector-escape-ack', 'other-slot-escape-ack']
        observed = {}
        initial_inventory = None
        for sequence in range(1, 9):
            client = read_nbt(receipts / f'area-client-step-{sequence}.nbt')
            server = read_nbt(receipts / f'area-server-step-{sequence}.nbt')
            require(client.get('passed') and server.get('passed') and client['sequence'] == server['sequence'] == sequence,
                    'Missing exact client/server stage proof')
            require(client['capturePhase'] == 'RenderTick.END' and client['actualRenderFrame'] > client['requestedAfterRenderFrame'],
                    'Screenshot was not captured after the requested actual RenderTick.END')
            require(client['session'] == server['session'] and client['points'] == server['points']
                and client['selecting'] == server['selecting'] and client['heldSlot'] == server['heldSlot']
                and client['planRevision'] == server['planRevision']
                and client['label'] == server['label'] == expected_labels[sequence - 1],
                'Actual Netty mirror and independent server observation disagree')
            require(client['heldSlot'] == (1 if sequence in (3, 8) else 0)
                and bool(client['selecting']) == (sequence <= 6), 'Area stage held slot or ACK state differs')
            require(server['sourceUnchanged'], 'Real source was mutated')
            if initial_inventory is None:
                initial_inventory = server['inventory']
            expected_inventory = copy.deepcopy(initial_inventory)
            if sequence >= 7:
                for item in expected_inventory:
                    if item['slot'] == 0:
                        item.get('tag', {}).pop('gtitHologramAreaSelecting', None)
            require(server['inventory'] == expected_inventory,
                    'Actual inventory changed beyond the reviewed selection-flag clear on Esc')
            png = receipts / client['png']
            require(png.parent == receipts and sha(png) == client['pngSha256'], 'Actual framebuffer PNG hash mismatch')
            if client.get('cursorRequired'):
                require(len(client.get('drawnCurrentRedTarget', [])) == 3, 'Actual red current cursor was not drawn')
            if sequence == 3:
                earlier = read_nbt(receipts / 'area-server-step-2.nbt')
                require(server['worldTick'] - earlier['worldTick'] >= 8, 'Held-slot pause did not span eight actual server ticks')
                require(client['points'] == observed[2]['points'] and not client.get('drawnCurrentRedTarget'),
                        'Held-slot pause consumed a corner or drew the projector cursor')
            if sequence == 2:
                require(client['points'] == [51, 80, 44] and client['nettySnapshot']['areaPendingCorner'] == 2
                    and client['nettySnapshot']['areaPointMode'] == 'add', 'Right-first Ctrl corner did not lock add mode')
            if sequence == 4:
                require(client['points'] == [50, 80, 44, 51, 80, 44] and len(server['regions']) == 1
                    and server['regions'][0]['mode'] == 'add', 'Reverse plain-left completion changed first-corner mode')
            if sequence in (5, 6):
                require(60 <= client['actualCameraPitch'] <= 85
                    and abs(client['actualCameraYaw'] - client['requestedCameraYaw']) < 0.05,
                    'Actual Alt cursor frame did not preserve the requested downward camera view')
            if sequence == 6:
                require(client['points'] == client['drawnCurrentRedTarget'] == observed[5]['drawnCurrentRedTarget'],
                        'Actual Alt zero cursor and server corner disagree')
            if sequence >= 7:
                require(client['points'] == [], 'Esc ACK retained area corner coordinates')
            observed[sequence] = client
            required += [f'area-client-step-{sequence}.nbt', f'area-server-step-{sequence}.nbt', client['png']]
        client_final = read_nbt(receipts / 'area-client-final.nbt')
        server_final = read_nbt(receipts / 'area-server-final.nbt')
        require(client_final.get('passed') and client_final.get('actualNettyClient')
            and not client_final.get('syntheticSnapshotsUsed') and client_final.get('observations') == 8
            and server_final.get('passed') and server_final.get('sourceUnchanged')
            and server_final.get('observations') == 8 and server_final['inventory'] == expected_inventory,
            'Actual paired final proof did not complete all eight stages with conserved inventory')
        scopes = ['actual Netty reverse corners and first-corner mode', 'actual C09 held-slot pause/resume',
                  'actual red cursor versus server corner at Alt distance zero', 'both held/suspended Esc ACKs',
                  'eight exact END screenshots with independent source/inventory observations']
    elif args.profile in ('decor', 'essentia'):
        scenarios = DECOR if args.profile == 'decor' else LOCAL_ESSENTIA + ['essentia-ae-native-monitor']
        required = [args.profile + '-world-final.nbt', args.profile + '-world-pass.txt']
        for name in scenarios:
            row = read_nbt(receipts / (name + '.nbt'))
            require(row.get('passed') and row.get('sourceUnchanged'), 'Actual native world scenario did not pass: ' + name)
            require('inventoryBefore' in row and 'inventoryAfter' in row, 'Missing real inventory evidence: ' + name)
            if name.endswith('raw-ceil-refund') or name == 'tc-jar-local-source-payment-refund':
                require(row.get('inventoryAfterCancelled') == row['inventoryBefore'], 'Actual rollback failed to restore exact item tags')
            if args.profile == 'decor' and name.endswith('raw-ceil-refund'):
                events = row.get('placementEvents', [])
                require(row.get('placementEventCount') == 1 and row.get('eventNativeTargetComplete')
                    and len(events) == 1 and events[0].get('complete') and events[0].get('nativeMatch')
                    and events[0].get('ownerMatch') and events[0].get('completePersistenceMatch'),
                    'Cancelled placement did not observe complete native persistence: ' + name)
                require(events[0].get('sourceIdentityUnchanged')
                    and events[0].get('sourceTileAtEvent') == row['sourceTileBefore']
                    and row.get('sourceTileAfterCancelled') == row['sourceTileBefore']
                    and row.get('sourceTileAfter') == row['sourceTileBefore'],
                    'Complete native source changed during cancelled/retried placement: ' + name)
                require(row.get('nativeMaterialCosts') and row.get('reportedPaymentBefore')
                    and 'inventoryDuring' in events[0], 'Missing native costs or actual event inventory: ' + name)
                required += [name + '-operation-before.nbt', name + '-cancel-observation.nbt']
            if '-factory-id-' in name:
                expected_id = 'gtmb:bogus' if name.endswith('-bogus') else 174011
                require(row.get('quoteRejected') and row.get('placementRejected') and row.get('quoteError')
                    and row.get('cellStatusAfter') == 'unsupported' and not row.get('reportedPayment')
                    and row.get('placementEventCount') == 0 and row['invalidCapturedTile']['id'] == expected_id
                    and row['inventoryBefore'] == row['inventoryAfter']
                    and row['sourceTileBefore'] == row['sourceTileAfter']
                    and all(target['block'] == 'minecraft:air' and 'tile' not in target for target in row['targetWorldAfter']),
                    'Invalid native factory carrier reached a world or payment mutation: ' + name)
                required += [name + '-before.nbt', name + '-observation.nbt']
            if name == 'carpenter-existing-owner-level0-native-install':
                require(not row.get('paidPlacementClaimed') and row['existingOwner'] != row['installingOwner']
                    and row['installedTile']['cbOwner'] == row['existingOwner']
                    and row['inventoryBefore'] == row['inventoryAfter']
                    and row['sourceTileBefore'] == row['sourceTileAfter'],
                    'Native level0 install did not preserve the nonempty owner for a different actor')
            if name == 'carpenter-existing-owner-level1-service-refund':
                events = row.get('placementEvents', [])
                require(row.get('paidPlacementClaimed') and row.get('deidentificationLevel') == 1
                    and row['processedTile']['cbOwner'] == '' and row['sourceTileBefore']['cbOwner']
                    and row['nativeProcessedTile']['cbOwner'] == '' and not row['nativeProjectionDifferences']
                    and row.get('placementEventCount') == 1 and row.get('eventNativeTargetComplete')
                    and len(events) == 1 and events[0].get('completePersistenceMatch')
                    and row['placed'][0]['tile']['cbOwner'] == row['expectedTargetTile']['cbOwner']
                    and row['sourceTileBefore'] == row['sourceTileAfterCancelled'] == row['sourceTileAfter']
                    and row['inventoryBefore'] == row['inventoryAfterCancelled'] and not row['inventoryAfter'],
                    'Actual level1 owner projection, material payment or exact rollback differs')
                required += [name + '-projection.nbt', name + '-operation-before.nbt', name + '-cancel-observation.nbt']
            if name == 'tc-jar-local-source-payment-refund':
                events = row.get('placementEvents', [])
                require(row.get('placementEventCount') == 2 and row.get('earlyNativeShellEventCount') == 1
                    and row.get('completeServiceEventCount') == 1 and row.get('cancelledEventCount') == 1
                    and row.get('unexpectedEventCount') == 0 and len(events) == 2
                    and events[0]['phase'] == 'early-native-empty-shell' and not events[0]['cancelled']
                    and events[0]['nativeTile'].get('Amount') == 0 and events[0]['nativeTile'].get('Aspect', '') == ''
                    and events[1]['phase'] == 'complete-service-target' and events[1]['cancelled']
                    and events[1]['complete'] and events[1]['nativeTile']['Amount'] == 32
                    and events[1]['nativeTile']['Aspect'] == 'aer'
                    and all(event['nativeTileClass'] == 'thaumcraft.common.tiles.TileJarFillable'
                        and event['localSourceAspectDuring'] == 32
                        and event['sourceTileAtEvent'] == row['sourceTileBefore'] for event in events)
                    and row['localSourceAspectBefore'] == row['localSourceAspectAfterCancelled'] == 64
                    and row.get('inventoryRefundMatches') and row.get('targetAfterCancelledIsAir')
                    and row.get('targetAfterCancelledTileAbsent') and row.get('cancelledCellStatus') == 'protected'
                    and row['sourceTileBefore'] == row['sourceTileAfterCancelled'] == row['sourceTileAfter'],
                    'Actual TC empty-shell and complete-content event phases or exact refund differ')
                required += [name + '-operation-before.nbt', name + '-cancel-observation.nbt']
            required.append(name + '.nbt')
            if name == 'essentia-ae-native-monitor':
                require(row.get('actualPlaceEventRefund') and row.get('successBefore') == 64
                    and row.get('successAfter') == 32 and row.get('targetAspectAfter') == 32
                    and row.get('refundBefore') == 64 and row.get('refundDuring') == 32
                    and row.get('refundAfter') == 64 and row.get('refundAfterRetry') == 64
                    and row.get('deficitRequired') == 64 and row.get('deficitBefore') == row.get('deficitAfter') == 32,
                    'Actual native AE monitor success, deficit and once-only rollback counts differ')
        scopes = ['actual WorldServer blocks/NBT and Forge placement cancellation',
                  'real inventory payment and exact-tag refunds', 'explicitly constructed world-only Service fixture sessions']
        if args.profile == 'decor':
            scopes += ['AC 1/16 and 3/4 native raw ratios and aggregate ceilings', 'FMP/LT same-material aggregate ceilings',
                       'Carpenter damage2 shell with non-default slope/covers/dye', 'native prebuilt and empty-inventory creative placement',
                       'four native factory carriers reject bogus and numeric IDs before Service payment',
                       'Carpenter level0 native install preserves nonempty owner for another actor; no paid placement claim',
                       'Carpenter level1 actual-block projection, Service payment and exact refund']
        else:
            scopes += ['native TC local jar content payment and filled-shell protection', 'real AE/ThE native monitor success/deficit/refund']
    else:
        contract = json.loads((receipts / 'workbench-contract.json').read_text(encoding='utf-8'))
        require(contract.get('complete') and contract.get('actualNettyClient'), 'Workbench actual-operation contract is incomplete')
        final = read_nbt(receipts / 'workbench-client-final.nbt')
        server = read_nbt(receipts / 'workbench-server-final.nbt')
        require(final.get('passed') and final.get('actualNettyClient') and not final.get('syntheticSnapshotsUsed')
            and not final.get('controllerMemoryWritesUsed') and final.get('freshRenderWitnesses', 0) >= 12,
            'Workbench actual input and render contract is incomplete')
        require(server.get('passed') and server.get('sourceUnchanged')
            and server['sourceCardsBefore'] == server['sourceCardsAfter'], 'Real NC source assets changed')
        require(all(final['verifiedFindings'].get(name) for name in contract['verified_scope']),
                'Workbench scope includes a finding that was not actually verified')
        required = contract['required_receipts'] + ['workbench-contract.json', 'nc-payment-world.nbt']
        require(len(required) == len(set(required)), 'Workbench contract has duplicate receipt paths')
        validate_nc_payment(receipts / 'nc-payment-world.nbt')
        validate_workbench_source(receipts)
        validate_keyboard(receipts, required)
        required_native = ['workbench-initial-import-tab.nbt', 'workbench-native-process-server-ack.nbt']
        require(all(name in required for name in required_native), 'Workbench native source/processed ACK witnesses are not required')
        for path in validate_saved_workbench(receipts, fixture, required):
            recent(path, runs)
            selected.append((path, 'private-blueprints/' + path.name))
        render_count = 0
        render_frames = []
        for name in required:
            if not name.endswith('.nbt'):
                continue
            row = read_nbt(receipts / name)
            if 'capturePhase' not in row:
                continue
            typed_row = read_nbt(receipts / name, typed=True)
            full_review_frame(typed_row)
            for field in ('flowFullReview', 'flowRequiredStages'):
                require(at(typed_row, field) == at(typed_row, 'requestedUi', field) == at(typed_row, 'actualUi', field),
                        'Workbench full-review mode/content mask differs between requested, actual and final frame: ' + name)
            render_frames.append(typed_row)
            require(row.get('passed') and row.get('actualNettyClient') and not row.get('syntheticSnapshotsUsed')
                and row['capturePhase'] == 'RenderTick.END'
                and row['actualRenderGeneration'] > row['requestedAfterGeneration'],
                'Workbench screenshot was not bound to the requested actual END frame: ' + name)
            require(row['session'] == row['actualSnapshot']['session']
                and row['planRevision'] == row['actualSnapshot']['planRevision']
                and row['actualUi']['renderGeneration'] == row['actualRenderGeneration']
                and row['flowStage'] == row['actualUi']['flowStage']
                and row['flowChosen'] == row['actualUi']['flowChosen'],
                'Workbench rendered snapshot and requested state disagree: ' + name)
            png = receipts / row['png']
            require(png.parent == receipts and row['png'] in required and sha(png) == row['pngSha256'],
                    'Workbench actual framebuffer PNG hash differs: ' + name)
            render_count += 1
        require(render_count == final['freshRenderWitnesses'], 'Workbench final screenshot count differs from actual witnesses')
        findings = at(read_nbt(receipts / 'workbench-client-final.nbt', typed=True), 'verifiedFindings')
        choices = at(findings, 'actualFullReviewSelections')
        require(choices[0] == 9 and choices[1][0] == 10 and len(choices[1][1]) > 0
                and at(findings, 'actualFullReviewNativeToggleClicks') == (3, len(choices[1][1]))
                and at(findings, 'actualVisibleFullSixStageReviewSelected') == (1, 1),
                'Workbench lacks actual native 296 selections matching the declared click count')
        identities = set()
        for choice in choices[1][1]:
            before, after = at(choice, 'actualUiBefore'), at(choice, 'actualUiAfter')
            point = at(choice, 'controlPoint')
            require(at(choice, 'controlId') == (3, 296) and point[0] == 11 and len(point[1]) == 2
                    and all(value >= 0 for value in point[1]) and at(choice, 'label')[0] == 8,
                    'Workbench full-review entry lacks actual control 296 identity and native click coordinates')
            identity = (at(before, 'session'), at(before, 'planRevision'), at(before, 'renderGeneration'))
            require(identity not in identities, 'Workbench reused a prior full-review native selection')
            identities.add(identity)
            candidates = [frame for frame in render_frames if at(frame, 'actualUi', 'processingFlow') == (1, 1)
                          and at(frame, 'session') == at(before, 'session') and at(frame, 'planRevision') == at(before, 'planRevision')
                          and at(frame, 'actualRenderGeneration')[1] > at(after, 'renderGeneration')[1]
                          and at(frame, 'label')[1].startswith('workbench-' + at(choice, 'label')[1] + '-')]
            require(candidates, 'Workbench full-review selection has no later original processing END witness in this source/session')
            frame = min(candidates, key=lambda value: at(value, 'actualRenderGeneration')[1])
            full_review_selection(before, after, frame,
                                  snapshots=(at(choice, 'actualSnapshotBefore'), at(choice, 'actualSnapshotAfter')))
        scopes = contract['verified_scope']
    for name in required:
        path = receipts / name
        require(path.parent == receipts, 'Receipt name escaped current fixture')
        recent(path, runs)
        selected.append((path, 'receipts/' + name))
    output = args.output.resolve()
    require(output.is_relative_to((ROOT / 'plan/evidence/v0.1.1').resolve()) and not output.exists(), 'Use a fresh v0.1.1 archive directory')
    selected += [(fixture / 'environment.json', 'environment.json')]
    for role, log in [('server', args.server_log)] + ([('client', args.client_log)] if paired else []):
        selected += [(fixture / (role + '-launch.json'), role + '-launch.json'), (log, role + '.log'),
                     (Path(str(log) + '.receipt.json'), role + '-actual-run.json')]
    selected += [(path, 'tooling/' + path.name) for path in Path(__file__).parent.iterdir()
                 if path.is_file() and path.suffix in ('.py', '.ps1', '.gradle', '.md')]
    output.mkdir(parents=True, exist_ok=False)
    files = {}
    for path, destination in selected:
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        files[destination] = dict(bytes=target.stat().st_size, sha256=sha(target))
    summary = dict(release_version='0.1.1', profile=args.profile, collected_utc=datetime.now(timezone.utc).isoformat(),
        status='PASS_FOR_LISTED_SCOPE', historical_pass_reused=False, fixture=str(fixture),
        verified_scope=scopes, tests_not_run_by_collector=True, files=files)
    (output / 'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=('area', 'decor', 'essentia', 'workbench'), required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--server-log', type=Path, required=True)
    parser.add_argument('--client-log', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = collect(args)
    print(json.dumps({key: result[key] for key in ('status', 'profile', 'verified_scope')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
