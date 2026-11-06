"""Build a signed Android release and upload it to the configured local NAS."""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
RELEASE_CERTIFICATE = "42a96aa67b649d9ee3cc523e57ea9cb5e0741fb4419ba0160ebae47691b536fd"


def release_version(value):
    if not re.fullmatch(r"(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", value):
        raise argparse.ArgumentTypeError("版本号应为 1.1.2 这样的三段数字，每段为 0–999，无前导零")
    if value == "0.0.0":
        raise argparse.ArgumentTypeError("不能发布 0.0.0")
    return value


def package_and_upload(version):
    settings = {}
    for key in ("JAVA_HOME", "ALWAYSIDE_APK_SHARE"):
        value = os.environ.get(key, "").strip()
        if not value:
            raise RuntimeError(f"缺少本地配置 {key}，请通过本地调用脚本或环境变量指定")
        settings[key] = Path(value)
    java_home = settings["JAVA_HOME"]
    share = settings["ALWAYSIDE_APK_SHARE"]
    major, minor, patch = map(int, version.split("."))
    code = major * 1000000 + minor * 1000 + patch
    signing = ROOT / ".local/release-signing"
    credentials = json.loads((signing / "production-credentials.json").read_text(encoding="utf-8-sig"))
    keystore = signing / "alwayside-production.p12"
    lineage = signing / "alwayside.lineage"
    sdk = ROOT / ".local/android-sdk"
    java = java_home / "bin/java.exe"
    signer_jar = sdk / "build-tools/36.0.0/lib/apksigner.jar"
    for required in (keystore, lineage, java, signer_jar, sdk / "platforms/android-36/android.jar"):
        if not required.is_file():
            raise RuntimeError(f"缺少现有构建或签名文件：{required}")
    if not share.is_dir():
        raise RuntimeError(f"网盘目录不可访问：{share}")

    # Credentials and tool paths apply only to the child processes.
    env = os.environ.copy()
    env.update(JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk),
               ANDROID_USER_HOME=str(ROOT / ".local/android-user"),
               GRADLE_USER_HOME=str(ROOT / ".local/gradle-cache"),
               ANDROID_KEYSTORE_PATH=str(keystore))
    for key in ("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
        value = credentials.get(key)
        if not isinstance(value, str) or not value:
            raise RuntimeError(f"正式签名配置缺少 {key}")
        env[key] = value

    print(f"打包 Release {version}（versionCode={code}）…", flush=True)
    subprocess.run([str(ROOT / "apps/android/gradlew.bat"), "--offline", "--no-daemon", "--console=plain",
                    f"-PreleaseVersionName={version}", f"-PreleaseVersionCode={code}", ":app:assembleRelease"],
                   cwd=ROOT / "apps/android", env=env, check=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    record = ROOT / ".local/android-build" / f"delivery-v{version}-release-{stamp}"
    record.mkdir(parents=True, exist_ok=False)
    name = f"AlwaySide-v{version}-release-{stamp}.apk"
    output = record / name
    signer = [str(java), "-jar", str(signer_jar)]
    print("使用正式证书签名并附带原轮换链…", flush=True)
    subprocess.run([*signer, "sign", "--ks", str(keystore), "--ks-key-alias", env["ANDROID_KEY_ALIAS"],
                    "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                    "--lineage", str(lineage), "--rotation-min-sdk-version", "28",
                    "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                    "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                    "--out", str(output),
                    str(ROOT / "apps/android/app/build/outputs/apk/release/app-release.apk")],
                   env=env, check=True)
    signature = subprocess.check_output([*signer, "verify", "--verbose", "--print-certs", str(output)],
                                        env=env, text=True)
    if RELEASE_CERTIFICATE not in signature or "Verified using v3 scheme (APK Signature Scheme v3): true" not in signature:
        raise RuntimeError("正式证书或 V3 签名不匹配，已停止上传")
    (record / "signature.txt").write_text(signature, encoding="utf-8")

    target = share / name
    temporary = target.with_name(target.name + ".uploading-" + uuid.uuid4().hex)
    print(f"上传到 {share} …", flush=True)
    try:
        with output.open("rb") as source, temporary.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        os.rename(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    result = dict(versionName=version, versionCode=code, buildType="release", apk=str(target),
                  localApk=str(output), certificateSha256=RELEASE_CERTIFICATE, signingLineage=True)
    (record / "delivery.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"完成：{target}", flush=True)
    return target


def main():
    parser = argparse.ArgumentParser(description="使用现有正式签名构建 APK，完成后自动上传网盘。")
    parser.add_argument("version", type=release_version, help="版本号，例如 1.1.2")
    args = parser.parse_args()
    try:
        package_and_upload(args.version)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"打包上传失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
