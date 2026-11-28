"""GTMB owns tutorial prerequisites, menu selection and canvas observations."""
from pathlib import Path
CONFIG = ('config/gtmb-testlab.cfg', 'testlab', 'enabled', 1048578)
CLIENT_RUNNER = None
CLIENT_WORKSPACE = 'miaotest-client-qa'
CLIENT_METADATA = ('client-launch.json', 'host-helper-compile.json')
CLIENT_TOPOLOGY = 'native-menu-MUI'
CLIENT_SOURCES = ('tools/testlab/client_qa/GtmbClientObservations.java',)
CLIENT_JVM = ('-Dmiaotest.host.observer=miaotest.host.GtmbClientObservations',)


def client_mode(args, config, preparing, blockers):
    if args.case not in ('all', 'client-ui'):
        blockers.append('Native client adapter profile is client-ui; other cases use their declared lane')
    return 'client-ui'


def extra_changes(root, client, physical):
    if not client:
        return []
    path = Path(root) / 'config/gtmb-editor-guide.properties'
    return [{'path': str(path), 'kind': 'gtmb-guide-seen', 'fixture': 'gtmb/client-ui',
        'properties': {'tutorial.v2.blueprint': 'skipped'},
        'reason': 'Temporary normally skipped tutorial prerequisite before preference singleton loads; original progress bytes restored'},
        {'path': str(path) + '.pending', 'kind': 'gtmb-guide-pending', 'fixture': 'gtmb/client-ui',
        'reason': 'Back up original pending middleware before temporarily removing it; restore its exact original existence and bytes'}]


def prepared_bytes(entry, original):
    if entry['kind'] == 'gtmb-guide-pending':
        return None
    # Preserve Java Properties duplicate-key and trailing-continuation semantics.
    return (original or b'') + b'\n\ntutorial.v2.blueprint=skipped\n'
