"""Offline runtime contracts: temporary argument fixtures and mocked host probes only."""
import ast
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


composite = load('run_composite', HERE / 'followup/v0111/run_composite.py')
loaded = load('gtmb_loaded_runtime', HERE / 'followup/v0110/run_loaded_latency.py')
contracts = load('gtmb_native_contracts_runtime', HERE / 'followup/v0111/run_native_contracts.py')


class RuntimeBudget(unittest.TestCase):
    def test_two_jvms_share_one_total_budget_check(self):
        for runner in (loaded, composite):
            with self.subTest(runner=runner.__name__), \
                    patch.object(runner.host, 'require_java26', return_value=Path('C:/test-java/bin/java.exe')) as java, \
                    patch.object(runner.host, 'require_memory', return_value=9 << 30) as memory:
                self.assertEqual(runner.validate_runtime('selected-java'), Path('C:/test-java/bin/java.exe'))
                java.assert_called_once_with('selected-java')
                memory.assert_called_once_with(jvms=2)

    def test_single_jvm_uses_five_gib_budget(self):
        with patch.object(composite.host, 'require_java26', return_value=Path('C:/test-java/bin/java.exe')), \
                patch.object(composite.host, 'require_memory', return_value=5 << 30) as memory:
            composite.validate_runtime('selected-java', jvms=1)
            memory.assert_called_once_with(jvms=1)

    def test_rejected_java_stops_before_memory_probe(self):
        for runner in (loaded, composite):
            with self.subTest(runner=runner.__name__), \
                    patch.object(runner.host, 'require_java26', side_effect=RuntimeError('Java 26 required')), \
                    patch.object(runner.host, 'require_memory') as memory:
                with self.assertRaisesRegex(RuntimeError, 'Java 26'):
                    runner.validate_runtime('selected-java')
                memory.assert_not_called()

    def test_insufficient_memory_propagates_without_launch(self):
        for runner in (loaded, composite):
            with self.subTest(runner=runner.__name__), \
                    patch.object(runner.host, 'require_java26', return_value=Path('C:/test-java/bin/java.exe')), \
                    patch.object(runner.host, 'require_memory', side_effect=RuntimeError('insufficient memory')), \
                    patch.object(runner.subprocess, 'Popen') as launch:
                with self.assertRaisesRegex(RuntimeError, 'insufficient memory'):
                    runner.validate_runtime('selected-java')
                launch.assert_not_called()

    def test_entrypoints_check_once_before_first_mutation(self):
        for runner in (loaded, composite, contracts):
            with self.subTest(runner=runner.__name__):
                tree = ast.parse(Path(runner.__file__).read_text(encoding='utf-8'))
                run = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'run')
                calls = [node for node in ast.walk(run) if isinstance(node, ast.Call)]
                gates = [node for node in calls if (isinstance(node.func, ast.Name) and node.func.id == 'validate_runtime')
                         or (isinstance(node.func, ast.Attribute) and node.func.attr == 'validate_runtime')]
                self.assertEqual(len(gates), 1)
                mutations = [node for node in calls if isinstance(node.func, ast.Attribute)
                             and node.func.attr in ('mkdir', 'write_text', 'write_bytes', 'Popen', 'rename')]
                self.assertLess(gates[0].lineno, min(node.lineno for node in mutations))
                if runner is contracts:
                    self.assertEqual([(kw.arg, kw.value.value) for kw in gates[0].keywords], [('jvms', 1)])


class InstanceOccupancy(unittest.TestCase):
    def process(self, runner, cwd, command=()):
        process = Mock()
        process.pid = 123
        process.info = {"name": "java.exe"}
        process.cwd.return_value = str(cwd)
        process.cmdline.return_value = list(command)
        return process

    def test_other_repositories_and_project_gradle_are_allowed(self):
        for runner in (loaded, composite):
            processes = [self.process(runner, runner.ROOT.parent / "Other-Mod", ["java", "GradleDaemon"]),
                         self.process(runner, runner.ROOT, ["java", "-cp", str(runner.ROOT / "build/classes")]),
                         self.process(runner, runner.FIXTURE / "client-other", ["java"])]
            with self.subTest(runner=runner.__name__), patch.object(runner.psutil, 'process_iter', return_value=processes):
                runner.assert_idle()

    def test_each_target_and_its_descendants_stay_exclusive(self):
        for runner in (loaded, composite):
            for role in ('client', 'server'):
                process = self.process(runner, runner.FIXTURE / role / 'child')
                process.cmdline.side_effect = runner.psutil.AccessDenied(123)
                with self.subTest(runner=runner.__name__, role=role), \
                        patch.object(runner.psutil, 'process_iter', return_value=[process]):
                    with self.assertRaisesRegex(RuntimeError, 'Target instance Java PID 123'):
                        runner.assert_idle()
                    process.cmdline.assert_not_called()

    def test_target_command_is_detected_when_cwd_is_unavailable(self):
        for runner in (loaded, composite):
            process = self.process(runner, runner.ROOT, ['java', '--gameDir=' + str(runner.FIXTURE / 'client')])
            process.cwd.side_effect = runner.psutil.AccessDenied(123)
            with self.subTest(runner=runner.__name__), patch.object(runner.psutil, 'process_iter', return_value=[process]):
                with self.assertRaisesRegex(RuntimeError, 'Target instance Java PID 123'):
                    runner.assert_idle()

    def test_unrelated_and_unknown_access_denied_processes_are_allowed(self):
        for runner in (loaded, composite):
            unrelated = self.process(runner, runner.ROOT.parent / 'Other-Mod')
            unrelated.cmdline.side_effect = runner.psutil.AccessDenied(123)
            unknown = self.process(runner, runner.ROOT)
            unknown.cwd.side_effect = runner.psutil.AccessDenied(123)
            unknown.cmdline.side_effect = runner.psutil.AccessDenied(123)
            with self.subTest(runner=runner.__name__), \
                    patch.object(runner.psutil, 'process_iter', return_value=[unrelated, unknown]):
                runner.assert_idle()

    def test_server_only_target_allows_an_independent_client(self):
        client = self.process(composite, composite.FIXTURE / 'client')
        server = self.process(composite, composite.FIXTURE / 'server')
        with patch.object(composite.psutil, 'process_iter', return_value=[client]):
            composite.assert_idle(targets=(composite.FIXTURE / 'server',))
        with patch.object(composite.psutil, 'process_iter', return_value=[server]):
            with self.assertRaisesRegex(RuntimeError, 'Target instance Java PID 123'):
                composite.assert_idle(targets=(composite.FIXTURE / 'server',))

    def test_native_contracts_checks_only_its_server(self):
        tree = ast.parse(Path(contracts.__file__).read_text(encoding='utf-8'))
        checks = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                  and isinstance(node.func, ast.Attribute) and node.func.attr == 'assert_idle']
        self.assertEqual(len(checks), 4)
        for check in checks:
            self.assertEqual(len(check.keywords), 1)
            self.assertEqual(check.keywords[0].arg, 'targets')
            self.assertEqual(ast.unparse(check.keywords[0].value), '(server,)')


class ArgumentFixtures(unittest.TestCase):
    def test_each_generated_jvm_has_exactly_four_gib(self):
        for runner in (loaded, composite, contracts):
            with self.subTest(runner=runner.__name__), tempfile.TemporaryDirectory(prefix='gtmb-runtime-') as name:
                root = Path(name).resolve(strict=True)
                fixture = root / 'fixture'
                server = fixture / 'server'
                native = fixture / 'client/natives'
                server.mkdir(parents=True)
                native.mkdir(parents=True)
                (server / 'java9args.txt').write_text('-Xms2G\n-Xmx6G\n--add-opens=java.base/java.lang=ALL-UNNAMED\n', encoding='utf-8')
                (server / 'lwjgl3ify-forgePatches.jar').write_bytes(b'fixture')
                (fixture / 'java-args.txt').write_text('\n'.join([
                    '-Xmx4096M', '-Djava.library.path=' + str(native), '-cp', 'fixture.jar', 'FixtureMain',
                    '--username', 'Fixture', '--uuid', '0' * 32, '--gameDir', str(fixture / 'client')]), encoding='utf-8')
                common = composite if runner is contracts else runner
                with patch.object(common, 'ROOT', root), patch.object(common, 'FIXTURE', fixture), \
                        patch.object(runner, 'FIXTURE', fixture):
                    if runner is contracts:
                        args = runner.arguments(root / 'out', 'owned-world', 'a' * 64, 'b' * 64)
                    else:
                        args = runner.server_arguments(root / 'out', False, 0)
                        client = runner.client_arguments(root / 'out', False, 0)
                        self.assertEqual([item for item in client if item.startswith('-Xmx')], ['-Xmx4096M'])
                    self.assertEqual([item for item in args if item.startswith('-Xmx')], ['-Xmx4096M'])
                    self.assertIn('--add-opens=java.base/java.lang=ALL-UNNAMED', args)
                    self.assertEqual(args[-1], 'nogui')


if __name__ == '__main__':
    unittest.main()
