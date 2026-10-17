"""Read-only Windows containment regression; never calls runner.run or starts a process."""
import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("v0110_runner_paths", Path(__file__).with_name("run_native.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


@unittest.skipUnless(os.name == "nt", "Windows namespaces")
class WindowsContainment(unittest.TestCase):
    def test_resolved_extended_file_inside_normal_parent(self):
        target = runner.ROOT / "build/verification/render-v0110/example/background-client.properties"
        with patch.object(Path, "resolve", side_effect=[Path("\\\\?\\" + str(target)), runner.ROOT]):
            self.assertEqual(runner.within(target, runner.ROOT), target)

    def test_extended_parent_and_normal_target(self):
        target = runner.ROOT / "build/example.nbt"
        with patch.object(Path, "resolve", side_effect=[target, Path("\\\\?\\" + str(runner.ROOT))]):
            self.assertEqual(runner.within(target, runner.ROOT), target)

    def test_unc_namespace_equivalence(self):
        self.assertEqual(runner.standard_namespace(r"\\?\UNC\server\share\case\receipt.nbt"),
                         Path(r"\\server\share\case\receipt.nbt"))

    def test_canonical_target_outside_workspace_is_rejected(self):
        outside = runner.ROOT.parent / (runner.ROOT.name + "-other") / "receipt.nbt"
        with patch.object(Path, "resolve", side_effect=[Path("\\\\?\\" + str(outside)), runner.ROOT]):
            with self.assertRaises(ValueError):
                runner.within(runner.ROOT / "apparent-link", runner.ROOT)

    def test_parent_traversal_is_rejected(self):
        with self.assertRaises(ValueError):
            runner.within(runner.ROOT / "../outside-receipt.nbt", runner.ROOT)

    def test_unsupported_or_semantically_ambiguous_names_are_rejected(self):
        for value in (r"\\?\GLOBALROOT\Device\HarddiskVolume1\file", r"\\.\C:\file",
                      r"\\?\C:\case\name.", r"\\?\C:\case\name ", r"\\?\C:\case\NUL.txt",
                      r"\\?\C:\case\receipt.nbt:stream"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                runner.standard_namespace(value)


if __name__ == "__main__":
    unittest.main()
