#!/usr/bin/env python3
"""Opt-in native Android documentation captures. Never installs host dependencies."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PROJECT = HERE / "android-preview"
SCENARIOS = HERE / "scenarios/showcase.json"
LOCAL = ROOT / ".local/doc-media"
PACKAGE = "com.alwayside.docmedia"
APK = PROJECT / "build/outputs/apk/debug/AlwaySideDocPreview-debug.apk"
PROVENANCE = PROJECT / "build/doc-media-provenance.json"
SHARED = ("AssistantPresentation.kt", "Ui.kt", "PrototypeIcons.kt", "AppAppearance.kt",
          "AppearanceMode.kt", "ThemeScheme.kt")


def run(args, *, env=None, binary=False, check=True):
    result = subprocess.run([str(x) for x in args], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=600)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace") +
                           result.stdout.decode("utf-8", "replace"))
    return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    main = ROOT / "apps/android/app/src/main"
    paths = [main / "java/com/alwayside/android" / name for name in SHARED]
    paths += sorted((main / "res/drawable").glob("*.xml"))
    paths += [main / "res/values/styles.xml", main / "res/values-night/styles.xml"]
    paths += sorted((PROJECT / "src").rglob("*"))
    paths += [PROJECT / "settings.gradle.kts", PROJECT / "build.gradle.kts",
              PROJECT / "gradle.properties", SCENARIOS]
    return {p.relative_to(ROOT).as_posix(): digest(p) for p in paths if p.is_file()}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sdk_path(args):
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    return Path(sdk) if sdk else ROOT / ".local/android-sdk"


def adb_path(args):
    path = sdk_path(args) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if not path.is_file():
        raise RuntimeError("adb missing. Set --sdk or ANDROID_HOME to an existing SDK.")
    return path


def doctor(args):
    import importlib.metadata
    print("Project:", PROJECT)
    print("SDK:", sdk_path(args), "(exists:", sdk_path(args).is_dir(), ")")
    print("Java:", args.java_home or os.environ.get("JAVA_HOME") or shutil.which("java"))
    try:
        print("Pillow:", importlib.metadata.version("Pillow"))
    except importlib.metadata.PackageNotFoundError:
        print("Pillow: missing; install requirements.txt only after authorizing dependency changes")
    try:
        print(run([adb_path(args), "devices", "-l"]))
    except RuntimeError as error:
        print(error)
    print("No software downloaded, installed or configured.")


def build(args):
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk_path(args).resolve())
    env.pop("ANDROID_SDK_ROOT", None)
    if args.java_home:
        env["JAVA_HOME"] = str(Path(args.java_home).resolve())
    if args.gradle_user_home:
        env["GRADLE_USER_HOME"] = str(Path(args.gradle_user_home).resolve())
    if args.android_user_home:
        env["ANDROID_USER_HOME"] = str(Path(args.android_user_home).resolve())
    # The wrapper is only a pinned Gradle launcher; -p selects the independent build.
    wrapper = ROOT / "apps/android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    launcher = Path(args.gradle).resolve() if args.gradle else wrapper
    command = [launcher, "-p", PROJECT, "assembleDebug", "--console=plain"]
    if args.offline:
        command.append("--offline")
    if args.keystore:
        command.append("-PdocMediaKeystore=" + str(Path(args.keystore).resolve()))
    before = inputs()
    print(run(command, env=env))
    if inputs() != before:
        raise RuntimeError("Sources changed during build; rebuild before capturing.")
    if not APK.is_file():
        raise RuntimeError("Expected APK missing: " + str(APK))
    write_json(PROVENANCE, {"schema": 1, "apk_sha256": digest(APK), "inputs": before})
    print("Built independent preview:", APK)


def capture(args):
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise RuntimeError("Only an explicitly selected Android emulator is supported; no real devices.")
    if not PROVENANCE.is_file():
        raise RuntimeError("Run doc_media.py build first to record source provenance.")
    provenance = read_json(PROVENANCE)
    if provenance["inputs"] != inputs() or provenance["apk_sha256"] != digest(APK):
        raise RuntimeError("Preview APK is stale. Rebuild so it uses the current App components and fixtures.")
    config = read_json(SCENARIOS)["scenes"]
    adb = [adb_path(args), "-s", args.serial]
    if run(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
        raise RuntimeError("Selected device did not identify itself as an emulator.")
    selected = args.scene or list(config)
    output = Path(args.output).resolve() if args.output else LOCAL / "captures" / time.strftime("%Y%m%d-%H%M%S")
    if output.exists():
        raise RuntimeError("Capture output already exists; choose a new directory: " + str(output))
    print(run(adb + ["install", "-r", APK]))
    output.mkdir(parents=True)
    for name in selected:
        identifier = "doc_" + uuid.uuid4().hex
        remote = "files/captures/" + identifier
        run(adb + ["shell", "am", "force-stop", PACKAGE])
        run(adb + ["shell", "am", "start", "-W", "-n", PACKAGE + "/com.alwayside.android.DocMediaActivity",
                   "--es", "scene", name, "--es", "run", identifier])
        deadline = time.monotonic() + config[name]["duration"] / 1000 + 90
        while True:
            # The native host publishes this completion file atomically.
            manifest = run(adb + ["exec-out", "run-as", PACKAGE, "cat", remote + "/manifest.json"], check=False)
            if manifest.startswith("{"):
                break
            if not run(adb + ["shell", "pidof", PACKAGE], check=False):
                raise RuntimeError(f"Preview Activity stopped: {name}. Inspect emulator logcat for {PACKAGE}.")
            if time.monotonic() > deadline:
                raise RuntimeError(f"Capture timed out: {name}. Inspect emulator logcat for {PACKAGE}.")
            time.sleep(0.5)
        archive = run(adb + ["exec-out", "run-as", PACKAGE, "tar", "-C", remote, "-cf", "-", "."], binary=True)
        destination = output / name
        destination.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            for member in tar:
                filename = member.name.removeprefix("./")
                if member.isfile() and re.fullmatch(r"manifest\.json|frame-\d{4}\.png", filename):
                    with tar.extractfile(member) as source:
                        (destination / filename).write_bytes(source.read())
        write_json(destination / "provenance.json", provenance)
        completion = read_json(destination / "manifest.json")
        if config[name].get("requiresAnswer") and (
                completion.get("finalAnswer") != read_json(SCENARIOS)["reading"]["answer"] or
                not completion.get("finalAnswerVisible")):
            raise RuntimeError("Final follow-up answer is incomplete or clipped. Inspect capture: " + str(destination))
        # Clean only this tool's unique completed run in the preview app's private files.
        run(adb + ["shell", "run-as", PACKAGE, "rm", "-r", remote])
        print(name, "captured:", destination, flush=True)
    run(adb + ["shell", "am", "force-stop", PACKAGE])
    print("Next: python tools/doc_media/doc_media.py render --input", output)


def font_path(requested):
    if requested:
        return requested
    for path in ("C:/Windows/Fonts/msyh.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                 "/System/Library/Fonts/PingFang.ttc"):
        if Path(path).is_file():
            return path
    raise RuntimeError("Pass --font pointing to an installed Chinese font. No fonts are downloaded.")


def render(args):
    from PIL import Image, ImageDraw, ImageFont
    source = Path(args.input).resolve()
    output = Path(args.output).resolve() if args.output else source.parent / (source.name + "-rendered")
    if output.exists():
        raise RuntimeError("Render output already exists; choose a new directory: " + str(output))
    config = read_json(SCENARIOS)["scenes"]
    selected = args.scene or [p.name for p in source.iterdir() if (p / "manifest.json").is_file()]
    if not selected:
        raise RuntimeError("No captured scenes found.")
    font = ImageFont.truetype(font_path(args.font), 15)
    small = ImageFont.truetype(font_path(args.font), 12)
    title_font = ImageFont.truetype(font_path(args.font), 19)
    output.mkdir(parents=True)
    results = []
    for name in selected:
        manifest = read_json(source / name / "manifest.json")
        native_width, native_height = manifest["width"], manifest["height"]
        scale = 432 / native_width
        height = round(native_height * scale)
        rendered, durations = [], []
        records = manifest["frames"]
        if not records:
            raise RuntimeError("Empty capture: " + name)
        for index, frame in enumerate(records):
            with Image.open(source / name / frame["file"]) as native:
                native = native.convert("RGB").resize((432, height), Image.Resampling.LANCZOS)
            canvas = Image.new("RGB", (480, height + 158), "#F5F7FB")
            canvas.paste(native, (24, 58))
            draw = ImageDraw.Draw(canvas)
            draw.text((24, 18), "AlwaySide", font=title_font, fill="#202428")
            draw.text((350, 23), "原生组件演示", font=small, fill="#68717C")
            draw.rounded_rectangle((23, 57, 456, height + 59), radius=2, outline="#DCE0E5", width=1)
            y, line = height + 71, ""
            for char in frame["caption"]:
                if draw.textlength(line + char, font=font) > 432:
                    draw.text((24, y), line, font=font, fill="#202428")
                    line, y = "", y + 23
                line += char
            draw.text((24, y), line, font=font, fill="#202428")
            draw.text((24, height + 135), "预设内容 · 手势标记为后期叠加 · 非真实采集", font=small, fill="#68717C")
            cue = frame.get("cue")
            if cue and cue["target"] in frame["targets"]:
                x1, y1, x2, y2 = frame["targets"][cue["target"]]
                cx, cy = 24 + (x1 + x2) / 2 * scale, 58 + (y1 + y2) / 2 * scale
                kind = cue["kind"]
                # Bounds come from native views, so annotations follow layout changes.
                if kind.startswith("swipe-"):
                    direction = -1 if kind == "swipe-up" else 1
                    draw.line((cx, cy - direction * 35, cx, cy + direction * 35), fill="#2155CD", width=3)
                    end = cy + direction * 35
                    draw.line((cx - 7, end - direction * 9, cx, end, cx + 7, end - direction * 9), fill="#2155CD", width=3)
                else:
                    phase = (frame["at"] % 600) / 600
                    radius = 18 + round(phase * 8)
                    draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), outline="#2155CD", width=3)
                    count = {"double-tap": "2", "triple-tap": "3", "long-press": "按住"}.get(kind, "1")
                    tx = min(422, max(30, cx - 12))
                    draw.rounded_rectangle((tx-4, cy-48, tx+32, cy-27), radius=5, fill="#2155CD")
                    draw.text((tx, cy-47), count, font=small, fill="white")
            rendered.append(canvas)
            following = records[index + 1]["at"] if index + 1 < len(records) else frame["at"] + 100
            durations.append(max(20, round((following - frame["at"]) / 10) * 10))
        still = bool(config[name].get("still"))
        target = output / (name + (".png" if still else ".gif"))
        if still:
            rendered[-1].save(target)
        else:
            # One shared palette prevents colors jumping between frames.
            samples = rendered[::max(1, len(rendered)//8)][:8]
            strip = Image.new("RGB", (480, rendered[0].height * len(samples)))
            for index, sample in enumerate(samples):
                strip.paste(sample, (0, index * sample.height))
            palette = strip.quantize(colors=240)
            paletted = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in rendered]
            paletted[0].save(target, save_all=True, append_images=paletted[1:], duration=durations,
                             loop=0, optimize=False, disposal=1)
            # QA contact sheet is local-only and is never exported to docs by `export`.
            sheet = Image.new("RGB", (480 * 3, rendered[0].height * 2), "white")
            for index, pos in enumerate((0, len(rendered)//4, len(rendered)//2,
                                         len(rendered)*2//3, len(rendered)*5//6, len(rendered)-1)):
                sheet.paste(rendered[pos], ((index % 3)*480, (index // 3)*rendered[0].height))
            sheet.save(output / (name + "-contact.png"))
            rendered[-1].save(output / (name + "-final.png"))
        results.append({"scene": name, "file": target.name, "sha256": digest(target),
                        "duration_ms": 0 if still else sum(durations), "frames": len(records),
                        "provenance": read_json(source / name / "provenance.json")})
        print(target, flush=True)
    write_json(output / "index.json", {"schema": 1, "assets": results})


def export(args):
    source, destination = Path(args.input).resolve(), Path(args.dest).resolve()
    index = read_json(source / "index.json")
    assets = {asset["scene"]: asset for asset in index["assets"]}
    selected = [assets[name] for name in args.scene]
    for asset in selected:
        if Path(asset["file"]).name != asset["file"] or digest(source / asset["file"]) != asset["sha256"]:
            raise RuntimeError("Asset does not match review index; render again.")
        if (destination / asset["file"]).exists() and not args.replace:
            raise RuntimeError("Existing file requires --replace: " + str(destination / asset["file"]))
    destination.mkdir(parents=True, exist_ok=True)
    for asset in selected:
        shutil.copy2(source / asset["file"], destination / asset["file"])
        print("Exported:", destination / asset["file"])
    print("Only selected PNG/GIF copied. Documentation and Git were not changed by this command.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("doctor", "build", "capture"):
        p = sub.add_parser(name)
        p.add_argument("--sdk")
        if name in ("doctor", "build"):
            p.add_argument("--java-home")
        if name == "build":
            p.add_argument("--gradle", help="Existing Gradle executable; defaults to repository wrapper")
            p.add_argument("--gradle-user-home")
            p.add_argument("--android-user-home")
            p.add_argument("--keystore", help="Existing android/android key, alias alwayside-test")
            p.add_argument("--offline", action="store_true")
        if name == "capture":
            p.add_argument("--serial", required=True)
            p.add_argument("--scene", action="append", choices=list(read_json(SCENARIOS)["scenes"]))
            p.add_argument("--output")
    p = sub.add_parser("render")
    p.add_argument("--input", required=True)
    p.add_argument("--output")
    p.add_argument("--scene", action="append", choices=list(read_json(SCENARIOS)["scenes"]))
    p.add_argument("--font", help="Installed Chinese font; only used for the postproduction labels")
    p = sub.add_parser("export")
    p.add_argument("--input", required=True)
    p.add_argument("--dest", required=True)
    p.add_argument("--scene", action="append", required=True, choices=list(read_json(SCENARIOS)["scenes"]))
    p.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    try:
        globals()[args.command](args)
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
