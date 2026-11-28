"""GTMB owns tutorial prerequisites, menu selection and canvas observations."""
from pathlib import Path
CONFIG = ('config/gtmb-testlab.cfg', 'testlab', 'enabled', 1048578)
CLIENT_RUNNER = None
CLIENT_WORKSPACE = 'miaotest-client-qa'
CLIENT_METADATA = ('client-launch.json', 'host-helper-compile.json')
CLIENT_TOPOLOGY = 'native-menu-MUI'
CLIENT_CASES = ('client-ui', 'world-projection-events', 'ebf-actions')
CLIENT_SOURCES = ('tools/testlab/client_qa/GtmbClientObservations.java',
                  'tools/testlab/client_qa/GtmbWorldProjectionController.java',
                  'tools/testlab/client_qa/GtmbWorldProjectionProbe.java',
                  'tools/testlab/client_qa/GtmbEbfActionsController.java',
                  'tools/testlab/client_qa/GtmbEbfActionsProbe.java')
CLIENT_JVM = ('-Dmiaotest.host.observer=miaotest.host.GtmbClientObservations',)


def client_mode(args, config, preparing, blockers):
    if args.case not in ('all', *CLIENT_CASES):
        blockers.append('Native client profiles: ' + ', '.join(CLIENT_CASES))
    return args.case if args.case in CLIENT_CASES else 'client-ui'


def client_details(mode):
    if mode == 'ebf-actions':
        return {'topology': 'native-integrated-world-GT-three-actions', 'ownedIntegrated': True,
                'businessRecoveryRequired': True,
                'controller': 'miaotest.host.GtmbEbfActionsController'}
    if mode == 'world-projection-events':
        return {'topology': 'native-integrated-world-GT-events', 'ownedIntegrated': True,
                'controller': 'miaotest.host.GtmbWorldProjectionController'}
    return {}


def extra_changes(root, client, physical):
    if not client:
        return []
    path = Path(root) / 'config/gtmb-editor-guide.properties'
    return [{'path': str(path), 'kind': 'gtmb-guide-seen', 'fixture': 'gtmb/client-ui',
        'properties': {'tutorial.v2.blueprint': 'skipped'},
        'reason': 'Temporary normally skipped tutorial prerequisite before preference singleton loads; original progress bytes restored'},
        {'path': str(path) + '.pending', 'kind': 'gtmb-guide-pending', 'fixture': 'gtmb/client-ui',
        'reason': 'Back up original pending middleware before temporarily removing it; restore its exact original existence and bytes'},
        *[{'path': str(Path(root) / 'config' / name), 'kind': 'gtmb-runtime-protected', 'fixture': 'gtmb/ebf-actions',
           'reason': 'Back up original UI preferences and native presets; restore original bytes and existence after the client exits'}
          for name in ('gtmb-hologram-ui.properties', 'gtmb-hologram-presets.nbt',
                       'gtsr/gtsr.cfg', 'gtsr.cfg')]]


def prepared_bytes(entry, original):
    if entry['kind'] == 'gtmb-runtime-protected':
        return original
    if entry['kind'] == 'gtmb-guide-pending':
        return None
    # Preserve Java Properties duplicate-key and trailing-continuation semantics.
    return (original or b'') + b'\n\ntutorial.v2.blueprint=skipped\n'
