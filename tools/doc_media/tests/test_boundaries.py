"""Guard device selection, source freshness and deliberate asset replacement."""
import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("doc_media", Path(__file__).parents[1] / "doc_media.py")
media = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(media)


class CaptureBoundaries(unittest.TestCase):
    def test_physical_device_rejected_before_adb_or_install(self):
        with patch.object(media, "run") as command:
            with self.assertRaisesRegex(RuntimeError, "no real devices"):
                media.capture(argparse.Namespace(serial="physical-phone"))
            command.assert_not_called()

    def test_source_changes_reject_old_apk_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            provenance = Path(directory) / "provenance.json"
            media.write_json(provenance, {"inputs": {"theme.xml": "old"}, "apk_sha256": "apk"})
            with patch.object(media, "PROVENANCE", provenance), \
                    patch.object(media, "inputs", return_value={"theme.xml": "new"}), \
                    patch.object(media, "run") as command:
                with self.assertRaisesRegex(RuntimeError, "stale"):
                    media.capture(argparse.Namespace(serial="emulator-5580"))
                command.assert_not_called()


class ExportBoundaries(unittest.TestCase):
    def test_existing_asset_blocks_entire_batch_without_replace(self):
        with tempfile.TemporaryDirectory() as directory:
            source, dest = Path(directory) / "source", Path(directory) / "dest"
            source.mkdir(); dest.mkdir()
            assets = []
            for name in ("A01", "S06"):
                image = source / (name + ".png")
                image.write_bytes(b"reviewed image")
                assets.append({"scene": name, "file": image.name, "sha256": media.digest(image)})
            media.write_json(source / "index.json", {"assets": assets})
            (dest / "S06.png").write_bytes(b"existing approved image")
            args = argparse.Namespace(input=source, dest=dest, scene=["A01", "S06"], replace=False)
            with self.assertRaisesRegex(RuntimeError, "--replace"):
                media.export(args)
            self.assertFalse((dest / "A01.png").exists())
            self.assertEqual((dest / "S06.png").read_bytes(), b"existing approved image")

    def test_modified_preview_cannot_replace_approved_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            source, dest = Path(directory) / "source", Path(directory) / "dest"
            source.mkdir(); dest.mkdir()
            image = source / "S06.png"
            image.write_bytes(b"reviewed")
            media.write_json(source / "index.json", {"assets": [
                {"scene": "S06", "file": image.name, "sha256": media.digest(image)}]})
            image.write_bytes(b"changed after review")
            approved = dest / "S06.png"
            approved.write_bytes(b"approved")
            with self.assertRaisesRegex(RuntimeError, "review index"):
                media.export(argparse.Namespace(input=source, dest=dest, scene=["S06"], replace=True))
            self.assertEqual(approved.read_bytes(), b"approved")


if __name__ == "__main__":
    unittest.main()
