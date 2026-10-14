"""Writes the advance of every glyph in Minecraft 1.21.1's default font to a test resource.

The JUnit source set has no Minecraft on its runtime classpath, so a test that wants to know how wide a text is
cannot ask ``Font#width``. This script reproduces what the game itself computes, once, from the real font files in
the client jar, and stores the result as a plain table the test can read.

The rule is vanilla's ``BitmapProvider``: a glyph's width is the rightmost column of its cell that has any opaque
pixel, plus one; its advance is ``(int)(0.5 + width * scale) + 1`` with ``scale = height / cellHeight``. The
``space`` provider gives its characters a fixed advance. The first provider that defines a character wins, in the
order ``default.json`` lists them.

Usage:  py scripts/gen_glyph_advances.py            (writes the table)
        py scripts/gen_glyph_advances.py --verify   (fails if the committed table differs)
"""
import json
import pathlib
import struct
import sys
import zipfile
import zlib

JAR = pathlib.Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/test/resources/font/default_advances.json"


def read_png(data):
    """Decodes a non-interlaced PNG into rows of alpha values.

    Supports what the vanilla font textures use (1-bit palette images with a tRNS chunk) and 8-bit RGBA or
    grey+alpha, which is enough for any resource pack that replaces them.
    """
    pos = 8
    width = height = color_type = bit_depth = None
    idat = b""
    palette_alpha = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", body[:10])
        elif kind == b"tRNS":
            palette_alpha = body
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    channels = {6: 4, 4: 2, 2: 3, 0: 1, 3: 1}[color_type]
    if bit_depth < 8 and color_type not in (0, 3):
        raise ValueError("unsupported PNG: bit depth %d with colour type %d" % (bit_depth, color_type))
    raw = zlib.decompress(idat)
    stride = (width * channels * bit_depth + 7) // 8
    bpp = max(1, channels * bit_depth // 8)
    rows, prev = [], bytearray(stride)
    i = 0
    for _ in range(height):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        prev = line
        if bit_depth < 8:
            per_byte = 8 // bit_depth
            mask = (1 << bit_depth) - 1
            samples = [(line[x // per_byte] >> (8 - bit_depth * (x % per_byte + 1))) & mask for x in range(width)]
            if color_type == 3:
                rows.append([palette_alpha[s] if palette_alpha and s < len(palette_alpha) else 255
                             for s in samples])
            else:
                rows.append([255 if s else 0 for s in samples])
            continue
        if color_type == 6:
            rows.append([line[x * 4 + 3] for x in range(width)])
        elif color_type == 4:
            rows.append([line[x * 2 + 1] for x in range(width)])
        elif color_type == 3:
            rows.append([palette_alpha[line[x]] if palette_alpha and line[x] < len(palette_alpha) else 255
                         for x in range(width)])
        else:
            rows.append([255] * width)
    return width, height, rows


def advances():
    table = {}
    with zipfile.ZipFile(JAR) as jar:
        # default.json lists references (space first, then the bitmaps, then unifont); follow them in that order,
        # the way the game does, so a character takes the advance of the first provider that defines it. Unifont is
        # skipped: it is a hex fallback for scripts this mod never prints, and an unknown character fails the test.
        providers = []
        for ref in json.loads(jar.read("assets/minecraft/font/default.json"))["providers"]:
            name = ref["id"].split(":", 1)[-1]
            if ref["type"] == "reference" and name != "include/unifont":
                providers += json.loads(jar.read("assets/minecraft/font/%s.json" % name))["providers"]
        for provider in providers:
            kind = provider["type"]
            if kind == "space":
                for ch, adv in provider["advances"].items():
                    table.setdefault(ch, int(adv))
            elif kind == "bitmap":
                path = provider["file"].split(":", 1)[-1]
                width, height, alpha = read_png(jar.read("assets/minecraft/textures/" + path))
                chars = provider["chars"]
                cell_w = width // len(chars[0])
                cell_h = height // len(chars)
                scale = provider.get("height", 8) / cell_h
                for row, line in enumerate(chars):
                    for col, ch in enumerate(line):
                        if ch == "\u0000" or ch in table:
                            continue
                        x0, y0 = col * cell_w, row * cell_h
                        right = -1
                        for x in range(cell_w - 1, -1, -1):
                            if any(alpha[y0 + y][x0 + x] for y in range(cell_h)):
                                right = x
                                break
                        glyph_width = right + 1
                        table[ch] = int(0.5 + glyph_width * scale) + 1
    return {str(ord(ch)): adv for ch, adv in sorted(table.items(), key=lambda kv: ord(kv[0]))}


def main():
    table = advances()
    text = json.dumps(table, indent=0, sort_keys=False) + "\n"
    if "--verify" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("default_advances.json is out of date: run py scripts/gen_glyph_advances.py")
            sys.exit(1)
        print("ok: %d glyphs" % len(table))
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print("wrote %d glyphs to %s" % (len(table), OUT))


if __name__ == "__main__":
    main()
