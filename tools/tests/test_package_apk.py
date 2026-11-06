"""Protect signing identity, artifact freshness and delivery integrity."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_apk as package


CERTIFICATE = "1" * 64
SIGNATURE = (f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n"
             "Verified using v3 scheme (APK Signature Scheme v3): true\n")
BADGING = "package: name='com.alwayside.android' versionCode='1004001' versionName='1.4.1'\n"


class PackageDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.paths = package.BuildPaths(
            java=self.root / "jdk/bin/java", sdk=self.root / "sdk", gradle_cache=self.root / "gradle",
            destination=self.root / "share", keystore=self.root / "signing/key.p12",
            lineage=self.root / "signing/rotation.lineage", credentials=None, certificate_sha256=CERTIFICATE,
            aapt2=self.root / "sdk/build-tools/36.0.0/aapt2")
        self.paths.destination.mkdir()

    def signing_env(self):
        return {"ANDROID_KEYSTORE_PATH": "fixture.p12", "ANDROID_KEY_ALIAS": "fixture-alias",
                "ANDROID_KEYSTORE_PASSWORD": "fixture-password", "ANDROID_KEY_PASSWORD": "fixture-password"}

    def test_missing_delivery_configuration_has_no_implicit_target(self):
        args = argparse.Namespace(java_home=None, sdk=str(self.paths.sdk), dest=None)
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(package.shutil, "which", return_value=str(self.paths.java)):
            with self.assertRaisesRegex(RuntimeError, "ALWAYSIDE_APK_SHARE"):
                package.resolve_paths(args)

    def test_paths_and_signing_identity_come_from_environment(self):
        args = argparse.Namespace(java_home=None, sdk=None, dest=None, keystore=None, lineage=None,
                                  credentials=None, certificate_sha256=None, gradle_user_home=None, aapt2=None)
        env = {"ANDROID_HOME": str(self.paths.sdk), "ALWAYSIDE_APK_SHARE": str(self.paths.destination),
               "ANDROID_KEYSTORE_PATH": str(self.paths.keystore),
               "ANDROID_SIGNING_LINEAGE_PATH": str(self.paths.lineage),
               "ALWAYSIDE_RELEASE_CERTIFICATE_SHA256": CERTIFICATE}
        with patch.dict(os.environ, env, clear=True), \
                patch.object(package.shutil, "which", return_value=str(self.paths.java)):
            paths = package.resolve_paths(args)
        self.assertEqual(paths.destination, self.paths.destination)
        self.assertEqual(paths.keystore, self.paths.keystore)
        self.assertEqual(paths.lineage, self.paths.lineage)
        self.assertEqual(paths.certificate_sha256, CERTIFICATE)

    def test_failed_build_never_signs_or_copies_an_old_apk(self):
        with patch.object(package, "preflight"), \
                patch.object(package, "signing_environment", return_value=self.signing_env()), \
                patch.object(package.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gradle")) as run, \
                patch.object(package, "copy_verified") as copy:
            with self.assertRaises(subprocess.CalledProcessError):
                package.package_and_upload("1.4.1", self.paths)
            self.assertEqual(run.call_count, 1)
            copy.assert_not_called()
        self.assertEqual(list(self.paths.destination.iterdir()), [])

    def test_successful_build_signs_verifies_and_delivers_matching_bytes(self):
        def build_or_sign(command, **kwargs):
            if "--out" in command:
                Path(command[command.index("--out") + 1]).write_bytes(b"fresh signed APK fixture")

        with patch.object(package, "ROOT", self.root), patch.object(package, "preflight"), \
                patch.object(package, "signing_environment", return_value=self.signing_env()), \
                patch.object(package, "subprocess") as processes:
            # Isolate APK commands without intercepting platform's own subprocess calls.
            processes.run.side_effect = build_or_sign
            processes.check_output.side_effect = [SIGNATURE, BADGING]
            target = package.package_and_upload("1.4.1", self.paths)
        record_file = next((self.root / ".local/android-build").glob("*/delivery.json"))
        record = json.loads(record_file.read_text())
        self.assertEqual(target.read_bytes(), b"fresh signed APK fixture")
        self.assertEqual(package.sha256(target), record["sha256"])
        self.assertEqual(record["versionCode"], 1004001)
        self.assertNotIn("fixture-password", record_file.read_text())
        self.assertIn("--offline", processes.run.call_args_list[0].args[0])
        self.assertIn("--lineage", processes.run.call_args_list[1].args[0])
        self.assertEqual(processes.check_output.call_count, 2)
        self.assertEqual(list(self.paths.destination.glob("*.uploading-*")), [])

    def test_wrong_certificate_signature_or_version_blocks_delivery(self):
        apk = self.root / "signed.apk"
        apk.write_bytes(b"APK")
        cases = (
            (SIGNATURE.replace(CERTIFICATE, "0" * 64), BADGING),
            (SIGNATURE.replace(": true", ": false"), BADGING),
            (SIGNATURE, BADGING.replace("1004001", "1003009")),
            (SIGNATURE, BADGING.replace("com.alwayside.android", "com.other.app")),
        )
        for signature, badging in cases:
            with self.subTest(signature=signature, badging=badging):
                with self.assertRaises(RuntimeError):
                    package.verify_apk(apk, "1.4.1", 1004001, signature, badging, CERTIFICATE)

    def test_copy_corruption_leaves_no_final_or_partial_delivery(self):
        source, target = self.root / "local.apk", self.paths.destination / "output.apk"
        source.write_bytes(b"APK")
        with patch.object(package, "sha256", side_effect=["expected", "corrupt"]):
            with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                package.copy_verified(source, target)
        self.assertEqual(list(self.paths.destination.iterdir()), [])

    def test_existing_delivery_is_never_overwritten(self):
        source, target = self.root / "local.apk", self.paths.destination / "output.apk"
        source.write_bytes(b"new APK")
        target.write_bytes(b"previous APK")
        with self.assertRaisesRegex(RuntimeError, "拒绝覆盖"):
            package.copy_verified(source, target)
        self.assertEqual(target.read_bytes(), b"previous APK")
        self.assertEqual(list(self.paths.destination.glob("*.uploading-*")), [])

    @unittest.skipIf(os.name == "nt", "ELF check applies to Unix hosts")
    def test_x86_tool_rejected_on_arm64_before_execution(self):
        binary = self.root / "aapt2"
        header = bytearray(20)
        header[:4] = b"\x7fELF"
        header[5] = 1
        struct.pack_into("<H", header, 18, 62)
        binary.write_bytes(header)
        binary.chmod(0o755)
        with patch.object(package.platform, "machine", return_value="aarch64"):
            with self.assertRaisesRegex(RuntimeError, "CPU 架构不匹配"):
                package.require_native_tool(binary)


if __name__ == "__main__":
    unittest.main()
