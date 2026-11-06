"""Build, verify and copy a signed APK using an existing host toolchain."""

import argparse
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = "36.0.0"
SIGNING_VARIABLES = ("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")


def release_version(value):
    if not re.fullmatch(r"(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", value):
        raise argparse.ArgumentTypeError("版本号应为 1.4.1 这样的三段数字，每段为 0–999，无前导零")
    if value == "0.0.0":
        raise argparse.ArgumentTypeError("不能发布 0.0.0")
    return value


@dataclass(frozen=True)
class BuildPaths:
    java: Path
    sdk: Path
    gradle_cache: Path
    destination: Path
    keystore: Path
    lineage: Path
    credentials: Path | None
    certificate_sha256: str
    aapt2: Path

    @property
    def tools(self):
        return self.sdk / "build-tools" / BUILD_TOOLS


def configured_path(value, label):
    if not value or not str(value).strip():
        raise RuntimeError(f"缺少本地配置 {label}，请通过本地调用脚本、环境变量或命令参数指定")
    return Path(value).expanduser().resolve()


def resolve_paths(args):
    java_home = args.java_home or os.environ.get("JAVA_HOME")
    java = Path(java_home).expanduser() / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else shutil.which("java")
    if not java:
        raise RuntimeError("未找到 Java；请指定 --java-home 或配置已有的 JAVA_HOME")
    sdk = configured_path(args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"),
                          "ANDROID_HOME / --sdk")
    destination = configured_path(args.dest or os.environ.get("ALWAYSIDE_APK_SHARE")
                                  or os.environ.get("ALWAYSIDE_APK_DEST"), "ALWAYSIDE_APK_SHARE / --dest")
    keystore = configured_path(args.keystore or os.environ.get("ANDROID_KEYSTORE_PATH"),
                               "ANDROID_KEYSTORE_PATH / --keystore")
    lineage = configured_path(args.lineage or os.environ.get("ANDROID_SIGNING_LINEAGE_PATH"),
                              "ANDROID_SIGNING_LINEAGE_PATH / --lineage")
    credentials = args.credentials or os.environ.get("ALWAYSIDE_SIGNING_CREDENTIALS")
    certificate = args.certificate_sha256 or os.environ.get("ALWAYSIDE_RELEASE_CERTIFICATE_SHA256", "")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", certificate):
        raise RuntimeError("缺少有效的正式证书 SHA-256，请配置 ALWAYSIDE_RELEASE_CERTIFICATE_SHA256 或 --certificate-sha256")
    return BuildPaths(
        java=Path(java).expanduser().resolve(), sdk=sdk,
        gradle_cache=Path(args.gradle_user_home or os.environ.get("GRADLE_USER_HOME")
                          or ROOT / ".local/gradle-cache").expanduser().resolve(),
        destination=destination, keystore=keystore, lineage=lineage,
        credentials=Path(credentials).expanduser().resolve() if credentials else None,
        certificate_sha256=certificate.lower(),
        aapt2=Path(args.aapt2 or sdk / "build-tools" / BUILD_TOOLS /
                   ("aapt2.exe" if os.name == "nt" else "aapt2")).expanduser().resolve(),
    )


def require_native_tool(path):
    if not path.is_file():
        hint = "（此 SDK 仅有 Windows .exe，请指定适合本机的 --sdk）" if path.with_suffix(".exe").is_file() else ""
        raise RuntimeError(f"缺少本机工具：{path}{hint}")
    if os.name == "nt":
        return
    if not os.access(path, os.X_OK):
        raise RuntimeError(f"工具没有执行权限：{path}")
    with path.open("rb") as stream:
        header = stream.read(20)
    if header.startswith(b"MZ"):
        raise RuntimeError(f"不能在本机运行 Windows 工具：{path}")
    if header.startswith(b"\x7fELF"):
        if len(header) < 20 or header[5] not in (1, 2):
            raise RuntimeError(f"工具 ELF 文件头无效：{path}")
        machine = struct.unpack("<H" if header[5] == 1 else ">H", header[18:20])[0]
        expected = {"aarch64": 183, "arm64": 183, "x86_64": 62, "amd64": 62}.get(platform.machine().lower())
        if expected and machine != expected:
            raise RuntimeError(f"工具 CPU 架构不匹配（本机 {platform.machine()}，ELF machine={machine}）：{path}")


def preflight(paths, offline):
    for required in (paths.java, paths.tools / "lib/apksigner.jar", paths.sdk / "platforms/android-36/android.jar",
                     paths.keystore, paths.lineage, *([paths.credentials] if paths.credentials else [])):
        if not required.is_file():
            raise RuntimeError(f"缺少现有构建或签名文件：{required}")
    if not paths.destination.is_dir() or not os.access(paths.destination, os.W_OK):
        raise RuntimeError(f"交付目录不存在或不可写：{paths.destination}")
    require_native_tool(paths.aapt2)
    for tool in ("aidl", "zipalign", "split-select"):
        require_native_tool(paths.tools / (tool + (".exe" if os.name == "nt" else "")))
    if offline and not any((paths.gradle_cache / "wrapper/dists").glob(
            "gradle-9.6.0-bin/*/gradle-9.6.0/lib/gradle-launcher-9.6.0.jar")):
        raise RuntimeError("离线 Gradle 9.6.0 发行包缺失；请先准备 Wrapper 缓存，脚本不会自动安装")
    version = subprocess.check_output([str(paths.java), "-version"], stderr=subprocess.STDOUT, text=True)
    match = re.search(r'version "(\d+)', version)
    if not match or int(match[1]) < 17:
        raise RuntimeError("构建需要 JDK 17 或以上版本")
    subprocess.run([str(paths.aapt2), "version"], check=True)


def signing_environment(paths):
    credentials = json.loads(paths.credentials.read_text(encoding="utf-8-sig")) if paths.credentials else {}
    if not isinstance(credentials, dict):
        raise RuntimeError("正式签名凭据文件必须是 JSON 对象")
    env = os.environ.copy()
    env.update(JAVA_HOME=str(paths.java.parent.parent), ANDROID_HOME=str(paths.sdk),
               ANDROID_USER_HOME=os.environ.get("ANDROID_USER_HOME", str(ROOT / ".local/android-user")),
               GRADLE_USER_HOME=str(paths.gradle_cache),
               ANDROID_KEYSTORE_PATH=str(paths.keystore))
    env.pop("ANDROID_SDK_ROOT", None)
    for key in SIGNING_VARIABLES:
        value = os.environ.get(key) or credentials.get(key)
        if not isinstance(value, str) or not value:
            raise RuntimeError(f"正式签名配置缺少 {key}")
        env[key] = value
    return env


def verify_apk(output, version, code, signature, badging, expected_certificate):
    certificate = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.MULTILINE)
    if not certificate or certificate[1].lower() != expected_certificate or \
            "Verified using v3 scheme (APK Signature Scheme v3): true" not in signature:
        raise RuntimeError("正式证书或 V3 签名不匹配，已停止交付")
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    if any(fields.get(key) != value for key, value in (
            ("name", "com.alwayside.android"), ("versionName", version), ("versionCode", str(code)))):
        raise RuntimeError("APK 包名或版本不匹配，已停止交付")
    if not output.is_file() or output.stat().st_size == 0:
        raise RuntimeError("签名 APK 缺失或为空，已停止交付")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_verified(output, target):
    expected = sha256(output)
    temporary = target.with_name(target.name + ".uploading-" + uuid.uuid4().hex)
    try:
        with output.open("rb") as source, temporary.open("xb") as destination:
            shutil.copyfileobj(source, destination)
            destination.flush()
            os.fsync(destination.fileno())
        if sha256(temporary) != expected:
            raise RuntimeError("交付文件 SHA-256 不匹配，已停止交付")
        if target.exists():
            raise RuntimeError(f"交付文件已存在，拒绝覆盖：{target}")
        if os.name == "nt":
            os.rename(temporary, target)
        else:
            # Publish atomically without replacing a concurrent delivery.
            os.link(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    return expected


def package_and_upload(version, paths, *, offline=True):
    preflight(paths, offline)
    major, minor, patch = map(int, version.split("."))
    code = major * 1000000 + minor * 1000 + patch
    env = signing_environment(paths)
    wrapper = ROOT / "apps/android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    command += ["--no-daemon", "--console=plain", "-Pandroid.builder.sdkDownload=false",
                f"-Pandroid.aapt2FromMavenOverride={paths.aapt2}",
                f"-PreleaseVersionName={version}", f"-PreleaseVersionCode={code}", ":app:assembleRelease"]
    if offline:
        command.append("--offline")
    print(f"打包 Release {version}（versionCode={code}，SDK={paths.sdk}）…", flush=True)
    subprocess.run(command, cwd=ROOT / "apps/android", env=env, check=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    record = ROOT / ".local/android-build" / f"delivery-v{version}-release-{stamp}"
    record.mkdir(parents=True, exist_ok=False)
    name = f"AlwaySide-v{version}-release-{stamp}.apk"
    output = record / name
    signer = [str(paths.java), "-jar", str(paths.tools / "lib/apksigner.jar")]
    print("使用正式证书签名并附带原轮换链…", flush=True)
    subprocess.run([*signer, "sign", "--ks", env["ANDROID_KEYSTORE_PATH"], "--ks-key-alias", env["ANDROID_KEY_ALIAS"],
                    "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                    "--lineage", str(paths.lineage), "--rotation-min-sdk-version", "28",
                    "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                    "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", "--out", str(output),
                    str(ROOT / "apps/android/app/build/outputs/apk/release/app-release.apk")],
                   env=env, check=True)
    signature = subprocess.check_output([*signer, "verify", "--verbose", "--print-certs", str(output)], env=env, text=True)
    badging = subprocess.check_output([str(paths.aapt2), "dump", "badging", str(output)], env=env, text=True)
    verify_apk(output, version, code, signature, badging, paths.certificate_sha256)
    (record / "signature.txt").write_text(signature, encoding="utf-8")
    (record / "badging.txt").write_text(badging, encoding="utf-8")

    target = paths.destination / name
    print(f"交付到 {paths.destination} …", flush=True)
    checksum = copy_verified(output, target)
    result = dict(versionName=version, versionCode=code, buildType="release", apk=str(target),
                  localApk=str(output), sha256=checksum, certificateSha256=paths.certificate_sha256,
                  signingLineage=True, host=f"{platform.system()} {platform.machine()}")
    (record / "delivery.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (record / (name + ".sha256")).write_text(f"{checksum}  {name}\n", encoding="utf-8")
    print(f"完成：{target}\nSHA-256：{checksum}", flush=True)
    return target


def main():
    parser = argparse.ArgumentParser(description="使用已有工具链构建正式签名 APK，并复制到共享交付目录。")
    parser.add_argument("version", type=release_version, help="版本号，例如 1.4.1")
    parser.add_argument("--sdk", help="已有 Android SDK 目录")
    parser.add_argument("--java-home", help="已有 JDK 目录；默认使用 JAVA_HOME 或 PATH 中的 Java")
    parser.add_argument("--gradle-user-home", help="已有 Gradle 发行包与依赖缓存目录")
    parser.add_argument("--keystore", help="已有正式签名密钥文件；也可使用 ANDROID_KEYSTORE_PATH")
    parser.add_argument("--lineage", help="已有签名轮换链；也可使用 ANDROID_SIGNING_LINEAGE_PATH")
    parser.add_argument("--credentials", help="可选的本地签名凭据 JSON；也可直接配置签名环境变量")
    parser.add_argument("--certificate-sha256", help="预期正式证书 SHA-256；必须由本地配置指定")
    parser.add_argument("--aapt2", help="已有的本机原生 AAPT2；默认使用 SDK 内的版本")
    parser.add_argument("--dest", help="交付目录；也可使用 ALWAYSIDE_APK_SHARE；无内置目标")
    parser.add_argument("--online", action="store_true", help="允许 Gradle 联网获取构建依赖；默认离线")
    parser.add_argument("--check", action="store_true", help="仅检查已有工具、签名配置和交付目录，不构建")
    args = parser.parse_args()
    try:
        paths = resolve_paths(args)
        if args.check:
            preflight(paths, not args.online)
            signing_environment(paths)
            print(f"环境检查通过；交付目录：{paths.destination}")
        else:
            package_and_upload(args.version, paths, offline=not args.online)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"打包交付失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
