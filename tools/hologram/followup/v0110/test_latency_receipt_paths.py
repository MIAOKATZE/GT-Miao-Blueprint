"""Receipt-read races only: temporary workspace files, no runner.run/JVM/native fixture activity."""
import importlib.util
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("v0110_latency_receipt_runner", Path(__file__).with_name("run_latency.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ReceiptContainment(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="latency-path-unit-", dir=runner.BUILD)
        self.root = Path(self.temporary.name).resolve(strict=True)
        self.root.relative_to(runner.BUILD.resolve(strict=True))  # Validate before eventual recursive cleanup.
        self.outputs = self.root / "outputs"
        self.case = self.outputs / "CASE-01"
        self.case.mkdir(parents=True)
        self.target = self.case / "latency-client.properties"
        self.target.write_bytes(b"status=RUNNING\n")
        self.outputs_patch = patch.object(runner, "OUTPUTS", self.outputs)
        self.outputs_patch.start()
        self.sleep_patch = patch.object(runner.time, "sleep")
        self.sleep = self.sleep_patch.start()

    def tearDown(self):
        self.sleep_patch.stop()
        self.outputs_patch.stop()
        self.root.resolve(strict=True).relative_to(runner.BUILD.resolve(strict=True))
        self.temporary.cleanup()

    def test_current_named_receipt_and_missing_receipt(self):
        self.assertEqual(runner.properties(self.target), {"status": "RUNNING"})
        self.target.unlink()
        self.assertEqual(runner.properties(self.target), {})

    def test_non_whitelisted_leaf_is_rejected(self):
        with self.assertRaises(ValueError):
            runner.shared_bytes(self.case / "unowned.properties")

    def test_parent_outside_owned_output_is_rejected(self):
        outside = self.root / "outside" / self.target.name
        outside.parent.mkdir()
        outside.write_bytes(b"status=PASS\n")
        with self.assertRaises(ValueError):
            runner.properties(outside)

    def test_real_outside_leaf_symlink_is_rejected_without_retry(self):
        outside = self.root / "outside.properties"
        outside.write_bytes(b"status=PASS\n")
        self.target.unlink()
        try:
            self.target.symlink_to(outside)
        except OSError as error:
            if os.name != "nt" or getattr(error, "winerror", None) != 1314:
                raise
            # Windows without SeCreateSymbolicLinkPrivilege still supports real directory
            # junctions. Exercise the same actual outside reparse boundary without mocking.
            outside_case = self.root / "outside-case"
            outside_case.mkdir()
            (outside_case / self.target.name).write_bytes(b"status=PASS\n")
            link = self.outputs / "OUTSIDE-01"
            result = subprocess.run(["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(outside_case)],
                                    capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(link.resolve(strict=True), outside_case.resolve(strict=True))
            with self.assertRaises(ValueError):
                runner.properties(link / self.target.name)
            self.sleep.assert_not_called()
            return
        with self.assertRaises(ValueError):
            runner.properties(self.target)
        self.sleep.assert_not_called()

    def test_resolved_outside_leaf_is_rejected_without_retry(self):
        original = Path.resolve
        outside = self.root / "outside.properties"

        def resolve(path, *args, **kwargs):
            return outside if path == self.target else original(path, *args, **kwargs)

        with patch.object(Path, "resolve", resolve), patch.object(runner, "_read_shared_receipt") as read:
            with self.assertRaises(ValueError):
                runner.properties(self.target)
            read.assert_not_called()
        self.sleep.assert_not_called()


@unittest.skipUnless(os.name == "nt", "NTFS deleted namespaces and final-handle validation")
class WindowsReceiptRaces(ReceiptContainment):
    def deleted(self):
        return Path(self.target.drive + r"\$Extend\$Deleted\00010000005291F13B16C7E1")

    def test_deleted_resolve_reopens_current_name_and_reads_fresh_receipt(self):
        original = Path.resolve
        leaf_resolves = []

        def resolve(path, *args, **kwargs):
            if path == self.target:
                leaf_resolves.append(path)
                if len(leaf_resolves) == 1:
                    return self.deleted()
            return original(path, *args, **kwargs)

        with patch.object(Path, "resolve", resolve):
            self.assertEqual(runner.properties(self.target), {"status": "RUNNING"})
        self.assertEqual(len(leaf_resolves), 2)
        self.sleep.assert_called_once_with(0.01)

    def test_replaced_handle_bytes_are_discarded_and_handle_is_closed(self):
        self.target.write_bytes(b"status=PASS\n")
        original = runner.receipt_target
        checks = []

        def verify(resolved, named):
            checks.append(resolved)
            if len(checks) == 3:  # Name resolution, before handle read, after handle read.
                self.target.write_bytes(b"status=RUNNING\n")
                raise runner._ReceiptReplaced(str(self.deleted()))
            return original(resolved, named)

        with patch.object(runner, "receipt_target", side_effect=verify):
            self.assertEqual(runner.properties(self.target), {"status": "RUNNING"})
        self.assertEqual(len(checks), 6)
        self.sleep.assert_called_once_with(0.01)
        # An unclosed first handle would prevent an exclusive current-name reopen.
        import ctypes
        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.CreateFileW.restype = ctypes.c_void_p
        api.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p,
                                   ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
        api.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = api.CreateFileW(str(self.target), 0x80000000, 0, None, 3, 0x80, None)
        self.assertNotEqual(handle, ctypes.c_void_p(-1).value)
        api.CloseHandle(handle)

    def test_deleted_resolve_retry_is_bounded(self):
        original = Path.resolve

        def resolve(path, *args, **kwargs):
            return self.deleted() if path == self.target else original(path, *args, **kwargs)

        with patch.object(Path, "resolve", resolve), patch.object(runner, "_read_shared_receipt") as read:
            with self.assertRaisesRegex(RuntimeError, "bounded reopen"):
                runner.properties(self.target)
            read.assert_not_called()
        self.assertEqual(self.sleep.call_count, runner.RECEIPT_READ_ATTEMPTS - 1)

    def test_global_within_still_rejects_deleted_resolution(self):
        original = Path.resolve

        def resolve(path, *args, **kwargs):
            return self.deleted() if path == self.target else original(path, *args, **kwargs)

        with patch.object(Path, "resolve", resolve), self.assertRaises(ValueError):
            runner.within(self.target, runner.ROOT)
        self.sleep.assert_not_called()

    def test_deleted_lookalike_on_another_drive_is_rejected(self):
        outside = Path(("Z:" if self.target.drive.casefold() != "z:" else "Y:")
                       + r"\$Extend\$Deleted\00010000005291F13B16C7E1")
        with self.assertRaises(ValueError):
            runner.receipt_target(outside, self.target)


if __name__ == "__main__":
    unittest.main()
