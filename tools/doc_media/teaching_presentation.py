"""Approved teaching layout around captures of current native components.

Coordinates follow the original 432 x 960 artwork at 2x resolution. Only the
teaching shell is drawn here; messages and controls always come from the APK.
"""
from functools import lru_cache
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


BLUE, INK, MUTED = "#2155CD", "#202428", "#535B64"


def render_scene(source, output, scene, regular_font):
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    records = manifest["frames"]
    if not records:
        raise RuntimeError("Empty capture: " + source.name)
    still = scene.get("still", False)
    if still:
        records = records[-1:]
    else:
        expected = list(range(0, scene["duration"], 50))
        if manifest.get("captureMode") != "fixed-step" or [r["at"] for r in records] != expected:
            raise RuntimeError("Motion capture must contain every 50ms step; rebuild and recapture " + source.name)
    regular = Path(regular_font)
    bold = next((p for p in (regular.with_name("msyhbd.ttc"),
                            regular.with_name(regular.name.replace("Regular", "Bold")))
                 if p != regular and p.is_file()), regular)
    latin = regular.with_name("segoeui.ttf")
    latin = latin if latin.is_file() else regular

    @lru_cache(maxsize=24)
    def font(size, weight=False, english=False):
        return ImageFont.truetype(str(latin if english else bold if weight else regular), round(size * 2))

    rendered, durations = [], []
    events = scene["events"]
    for index, record in enumerate(records):
        # Match the captured caption so annotations follow actual UI dispatch,
        # including a late frame on a slow emulator.
        event = next((e for e in reversed(events)
                      if e["at"] <= record["at"] and e["caption"] == record["caption"]), None)
        if event is None:
            raise RuntimeError("Teaching events changed; recapture " + source.name)
        teaching = event["teaching"]
        canvas = Image.new("RGB", (864, 1920), "#F6F8FC")
        with Image.open(source / record["file"]) as native:
            native = native.convert("RGB").resize((800, 1600), Image.Resampling.LANCZOS)
        mask = Image.new("L", (800, 1600))
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 799, 1599), radius=36, fill=255)
        canvas.paste(native, (32, 130), mask)
        draw = ImageDraw.Draw(canvas)

        def rect(box, fill, radius=0, outline=None, width=1):
            box = tuple(round(n * 2) for n in box)
            draw.rounded_rectangle(box, radius=round(radius * 2), fill=fill,
                                   outline=outline, width=max(1, round(width * 2)))

        def line(points, color=INK, width=1.5):
            draw.line([(round(x * 2), round(y * 2)) for x, y in points],
                      fill=color, width=max(1, round(width * 2)), joint="curve")

        def text(x, y, value, size=14, color=INK, weight=False, english=False):
            draw.text((round(x * 2), round(y * 2)), value, font=font(size, weight, english),
                      fill=color, anchor="lt")

        def circle(x, y, radius, fill=None, outline=None, width=1):
            draw.ellipse(tuple(round(v * 2) for v in (x-radius, y-radius, x+radius, y+radius)),
                         fill=fill, outline=outline, width=max(1, round(width * 2)))

        # Brand, status strip and step marker retain the original hierarchy.
        rect((17, 18, 41, 42), BLUE, 7)
        sparkle = [(12, 3), (14.5, 9.5), (21, 12), (14.5, 14.5),
                   (12, 21), (9.5, 14.5), (3, 12), (9.5, 9.5), (12, 3)]
        line([(21+x*2/3, 22+y*2/3) for x, y in sparkle], "white", 1.13)
        line([(35, 23.33), (35, 26)], "white", 1.13)
        line([(33.67, 24.67), (36.33, 24.67)], "white", 1.13)
        text(49, 20, "AlwaySide", 17, english=True)
        rect((329, 20, 415, 42), "#E8EDF7", 11)
        text(342, 25, "界面示意", 11, "#52617C")
        rect((16, 65, 416, 865), None, 18, "#DCE0E5")
        text(36, 77, "9:41", 11, english=True)
        for n in range(4):
            rect((342+n*4, 86-n*2, 344+n*4, 88), INK, 1)
        line([(367, 84), (372, 79), (377, 84)], width=1.2)
        rect((388, 79, 404, 88), None, 2, INK)
        rect((390, 81, 400, 86), INK, 1)
        if not still:
            sx, sy = (247, 21) if scene["kind"] == "replay" else (38, 168)
            rect((sx, sy, sx+67, sy+21), "#EAF0FF", 10)
            text(sx+11, sy+6, f"{teaching['step']} / {scene['steps']}", 10, BLUE)

        elapsed = max(0, record["at"] - event["at"])
        cue = record.get("cue")
        if teaching.get("callout"):
            x1, y1, x2, y2 = record["targets"]["bubble"]
            bx = 16 + (x1+x2) / 2 * 400 / manifest["width"]
            by = 65 + (y1+y2) / 2 * 800 / manifest["height"]
            x, y = bx-222, by-98
            rect((x, y, x+207, y+74), "white", 12, "#B7C9F5")
            text(x+13, y+12, teaching["callout"][0], 15, BLUE, True)
            text(x+13, y+45, teaching["callout"][1], 12, MUTED)
            line([(x+185, y+74), (bx-10, by-22)], "#8EACEF")
            if cue and cue["kind"] in ("double-tap", "triple-tap"):
                taps = 2 if cue["kind"] == "double-tap" else 3
                count = min(taps, int(elapsed/200)+1)
                # Numbered dots retain normal tap spacing; the hint lingers.
                rect((x+8, y+40, x+199, y+65), "white")
                for n in range(taps):
                    circle(x+22+n*30, y+50, 10, BLUE if n<count else "#DCE5F8")
                    text(x+19+n*30, y+44, str(n+1), 10, "white" if n<count else MUTED)
                text(x+20+taps*30, y+45, "快速连点", 10, MUTED)
                if elapsed < taps*200 and elapsed % 200 < 100:
                    circle(bx, by, 25, outline=BLUE, width=2)
                    circle(bx, by, 7, "white", BLUE, 1.8)
            elif teaching.get("pressed"):
                circle(bx, by, 24, outline="#C7D7F8", width=2.5)
                draw.arc(tuple(round(v*2) for v in (bx-24, by-24, bx+24, by+24)),
                         -90, -90+359*min(1, elapsed/700), fill=BLUE, width=5)
                circle(bx, by, 25, outline=BLUE, width=2)
                circle(bx, by, 7, "white", BLUE, 1.8)
        elif teaching.get("processing"):
            rect((112, 495, 342, 543), "white", 12, "#DCE0E5")
            draw.arc((256, 1022, 288, 1054), elapsed*.36, elapsed*.36+270, fill=BLUE, width=3)
            text(157, 512, "根据画面生成回答…", 13)
        elif cue and not cue["kind"].startswith("swipe-") and cue["target"] in record["targets"]:
            x1, y1, x2, y2 = record["targets"][cue["target"]]
            cx = 16 + (x1+x2)/2 * 400/manifest["width"]
            cy = 65 + (y1+y2)/2 * 800/manifest["height"]
            if cue["kind"] == "tap":
                phase = min(1, elapsed/450)
                overlay = Image.new("RGBA", canvas.size)
                od = ImageDraw.Draw(overlay)
                radius = 7 + phase*18
                od.ellipse(tuple(round(v*2) for v in (cx-radius, cy-radius, cx+radius, cy+radius)),
                           outline=(33, 85, 205, round(190*(1-phase))), width=4)
                od.ellipse(tuple(round(v*2) for v in (cx-4, cy-4, cx+4, cy+4)),
                           fill=(33, 85, 205, round(160*(1-phase))))
                canvas = Image.alpha_composite(canvas.convert("RGBA"), overlay).convert("RGB")
                draw = ImageDraw.Draw(canvas)

        if teaching.get("recording"):
            rect((42, 500, 390, 574), "#FFF5F7", 12, "#F0CAD4", .8)
            circle(59, 521, 4, "#CC5C74")
            text(72, 513, "录音中 · 状态示意", 13, "#9F344F", True)
            text(57, 544, "双击：结束并处理    三击：取消并丢弃", 11, "#9F344F")
        if teaching.get("cancelled"):
            rect((44, 502, 388, 594), "#F2F6FC", 14, "#DCE0E5")
            text(64, 522, "录音已取消并丢弃", 18, weight=True)
            text(64, 559, "不发送转写，不生成回答。", 12, MUTED)
        if still:
            text(32, 884, teaching["title"], 17, weight=True)
            text(32, 914, teaching["detail"], 10.5, "#68717C")
        else:
            text(24, 881, teaching["title"], 16, weight=True)
            text(24, 910, teaching["detail"], 11, MUTED)
            text(24, 930, "手势示意 · 预设内容 · 默认动作可自定义", 9.5, "#68717C")
            progress = (record["at"]-records[0]["at"]) / max(1, records[-1]["at"]-records[0]["at"])
            rect((24, 950, 408, 952), "#DFE5EF", 1)
            rect((24, 950, 24+384*progress, 952), BLUE, 1)
        rendered.append(canvas if still else canvas.resize((540, 1200), Image.Resampling.LANCZOS))
        following = records[index+1]["at"] if index+1 < len(records) else record["at"]+manifest["frameIntervalMs"]
        durations.append(max(20, round((following-record["at"])/10)*10))

    target = output / (source.name + (".png" if still else ".gif"))
    if still:
        rendered[0].save(target)
        print(target, flush=True)
        return asset_record(source, target, 0, 1)
    # Shared palette keeps white surfaces and text colors steady across frames.
    strip = Image.new("RGB", (540, 1200*8))
    for n in range(8):
        strip.paste(rendered[round(n*(len(rendered)-1)/7)], (0, n*1200))
    palette = strip.quantize(colors=240)
    frames = [frame.quantize(palette=palette, dither=Image.Dither.NONE).convert("RGB") for frame in rendered]
    del rendered
    frames[0].save(target, save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, optimize=True, disposal=1)
    with Image.open(target) as encoded:
        if encoded.n_frames != len(records):
            raise RuntimeError("GIF dropped teaching frames: " + source.name)
        for index in range(encoded.n_frames):
            encoded.seek(index)
            if encoded.info.get("duration") != 50:
                raise RuntimeError("GIF frame duration must be 50ms: " + source.name)
    contact = Image.new("RGB", (1620, 2400))
    for n, at in enumerate(scene.get("reviewAt", [n*records[-1]["at"]/5 for n in range(6)])):
        frame = min(range(len(records)), key=lambda k: abs(records[k]["at"]-at))
        contact.paste(frames[frame].convert("RGB"), ((n%3)*540, (n//3)*1200))
    contact.save(output / (source.name + "-contact.png"))
    frames[-1].convert("RGB").save(output / (source.name + "-final.png"))
    print(target, flush=True)
    return asset_record(source, target, sum(durations), len(records))


def asset_record(source, target, duration, frames):
    provenance = json.loads((source / "provenance.json").read_text(encoding="utf-8"))
    provenance["presentation_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    return {"scene": source.name, "file": target.name,
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "duration_ms": duration, "frames": frames, "provenance": provenance}
