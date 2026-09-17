"""Generates the custom block textures of Create: Wareworks.

Create: Wareworks uses Create's own textures everywhere (docs/architecture.md, ADR-017). This script exists
for the single documented exception recorded in **ADR-023**: Create ships no texture that reads as a request
terminal's screen (`stock_ticker`, `stock_link` and `flap_display_front` were all checked), so the warehouse
terminal's display is drawn here instead of being cropped out of an unrelated Create block.

Writing the texture with a script rather than by hand keeps the one exception reviewable: the palette is a
named table of Create-like colours, the layout is arithmetic instead of pixel art, and `--verify` proves that
the committed PNG is exactly what this file produces.

Output (deterministic, byte-identical on every run):
    src/main/resources/assets/wareworks/textures/block/terminal_screen.png   16 x 16 RGBA

Usage (from the repository root):
    py scripts/gen_textures.py            # (re)generate the textures, then verify them
    py scripts/gen_textures.py --verify   # only verify the committed files

The PNG is written by hand (zlib + struct, like scripts/gen_structures.py writes NBT), so the script needs no
third-party library and the build can run it anywhere Python is available.

Model note: `models/block/warehouse_terminal/shell_display.json` maps this texture with `uv [0, 0, 16, 16]`
onto the 8 x 8 model-pixel screen face, i.e. two texels per model pixel — the same trick Create uses for its
own 32 px sheets. Keep the two in sync: the layout below is designed for that face and for nothing else.
"""

import pathlib
import struct
import sys
import zlib

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src/main/resources/assets/wareworks/textures/block"

SIZE = 16

# --- palette ----------------------------------------------------------------------------------------------
# Dark slate with a teal cast, so the display sits beside Create's brass and railway casing instead of fighting
# them. Accents are muted versions of Create's own material colours (brass, copper, zinc, iron, redstone).
BEZEL = (10, 16, 19)
BACKGROUND = (20, 30, 34)
BAR_HIGHLIGHT = (52, 76, 80)
BAR_BODY = (38, 56, 60)
BAR_SHADOW = (14, 21, 24)
BAR_TEXT = (51, 73, 77)
GLASS = (120, 170, 175)
CELL = (30, 48, 52)
CELL_HIGHLIGHT = (44, 68, 72)
GUTTER = (15, 23, 26)
SCROLL_TRACK = (24, 36, 40)
SCROLL_THUMB = (70, 102, 106)
ACCENTS = {
    (0, 0): (194, 162, 74),   # brass
    (2, 0): (160, 104, 72),   # copper
    (1, 1): (95, 168, 160),   # zinc / teal
    (3, 1): (154, 166, 173),  # iron
    (2, 2): (166, 60, 60),    # redstone
}

# --- layout (texel coordinates, v = 0 is the top row) -----------------------------------------------------
INTERIOR = (1, 14)          # first and last texel of the interior, on both axes
BAR_TOP, BAR_BOTTOM = 2, 5  # the search row
BAR_LEFT, BAR_RIGHT = 2, 13
GLASS_LEFT, GLASS_TOP = 3, 3  # the magnifier of the search row
BAR_TEXT_LEFT, BAR_TEXT_RIGHT = 6, 11
BAR_TEXT_ROW = 4
GRID_TOP = 7                # the item grid: 4 x 3 cells of 2 x 2 texels with 1 texel gutters
GRID_LEFT = 2
CELL_SIZE = 2
CELL_PITCH = CELL_SIZE + 1
GRID_COLUMNS = 4
GRID_ROWS = 3
SCROLL_COLUMN = 13
SCROLL_THUMB_TOP, SCROLL_THUMB_BOTTOM = 7, 10


def terminal_screen():
    """The warehouse terminal's display: a bezel, a search row and a grid of item cells. No text, no glyphs."""
    pixels = [[BEZEL] * SIZE for _ in range(SIZE)]
    first, last = INTERIOR
    for v in range(first, last + 1):
        for u in range(first, last + 1):
            pixels[v][u] = BACKGROUND

    # Search row: a raised bar with a magnifier on the left and a hint of entered text.
    rows = {BAR_TOP: BAR_HIGHLIGHT, BAR_TOP + 1: BAR_BODY, BAR_TOP + 2: BAR_BODY, BAR_BOTTOM: BAR_SHADOW}
    for v, colour in rows.items():
        for u in range(BAR_LEFT, BAR_RIGHT + 1):
            pixels[v][u] = colour
    for u, v in ((GLASS_LEFT, GLASS_TOP), (GLASS_LEFT + 1, GLASS_TOP), (GLASS_LEFT, GLASS_TOP + 1)):
        pixels[v][u] = GLASS
    for u in range(BAR_TEXT_LEFT, BAR_TEXT_RIGHT + 1):
        pixels[BAR_TEXT_ROW][u] = BAR_TEXT

    # Item grid: every cell is a lit top row over a darker body; a few carry a single accent texel, which reads
    # as "this cell holds something" without ever becoming a recognisable item.
    for v in range(GRID_TOP, SIZE - 1):
        for u in range(GRID_LEFT, SCROLL_COLUMN):
            pixels[v][u] = GUTTER
    for row in range(GRID_ROWS):
        for column in range(GRID_COLUMNS):
            left = GRID_LEFT + column * CELL_PITCH
            top = GRID_TOP + row * CELL_PITCH
            for v in range(top, top + CELL_SIZE):
                for u in range(left, left + CELL_SIZE):
                    pixels[v][u] = CELL_HIGHLIGHT if v == top else CELL
            accent = ACCENTS.get((column, row))
            if accent is not None:
                pixels[top + CELL_SIZE - 1][left + CELL_SIZE - 1] = accent

    # Scrollbar: the grid scrolls on the real screen, so the block says so too.
    for v in range(GRID_TOP, SIZE - 1):
        pixels[v][SCROLL_COLUMN] = SCROLL_TRACK
    for v in range(SCROLL_THUMB_TOP, SCROLL_THUMB_BOTTOM + 1):
        pixels[v][SCROLL_COLUMN] = SCROLL_THUMB
    return pixels


TEXTURES = {
    "terminal_screen": terminal_screen,
}


# --- PNG writer -------------------------------------------------------------------------------------------

def _chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def encode_png(pixels):
    """An 8-bit RGBA PNG of the given rows of (r, g, b) triples; fully opaque, filter type 0, deterministic."""
    height = len(pixels)
    width = len(pixels[0])
    raw = bytearray()
    for row in pixels:
        if len(row) != width:
            raise SystemExit("every row must have the same width")
        raw.append(0)  # filter type "None": no pixel of ours predicts another well, and this stays readable
        for red, green, blue in row:
            raw += bytes((red, green, blue, 255))
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    # level 9 with the default strategy is deterministic for a fixed zlib version, and the file is verified by
    # its pixels below rather than byte for byte, so a future zlib may compress it differently without failing.
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header)
            + _chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + _chunk(b"IEND", b""))


def decode_png(data):
    """The rows of (r, g, b) triples of a PNG written by {@link encode_png}; refuses anything else."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("not a PNG file")
    position = 8
    width = height = 0
    compressed = bytearray()
    while position < len(data):
        (length,) = struct.unpack_from(">I", data, position)
        kind = data[position + 4:position + 8]
        payload = data[position + 8:position + 8 + length]
        position += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if (depth, colour, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise SystemExit(f"unexpected PNG header {payload!r}")
        elif kind == b"IDAT":
            compressed += payload
        elif kind == b"IEND":
            break
    raw = zlib.decompress(bytes(compressed))
    stride = width * 4
    rows = []
    for y in range(height):
        start = y * (stride + 1)
        if raw[start] != 0:
            raise SystemExit(f"unexpected PNG filter {raw[start]} in row {y}")
        line = raw[start + 1:start + 1 + stride]
        rows.append([tuple(line[x * 4:x * 4 + 3]) for x in range(width)])
    return rows


def write_texture(path, pixels):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encode_png(pixels))


def check_generated(name, pixels):
    """Fails before anything is written: a builder that produced the wrong resolution is a bug in this file."""
    height = len(pixels)
    width = max((len(row) for row in pixels), default=0)
    if height != SIZE or any(len(row) != SIZE for row in pixels):
        raise SystemExit(f"{name}: builds {width}x{height} pixels, but block textures must be {SIZE}x{SIZE}")


def verify_texture(path, pixels):
    if not path.is_file():
        raise SystemExit(f"{path.name}: missing; run 'py scripts/gen_textures.py'")
    found = decode_png(path.read_bytes())
    # Inspect the committed file's own resolution first, so the message names what it looked at: after the equality
    # check below, every size problem would be reported as the generic "differs from ..." instead.
    height = len(found)
    width = max((len(row) for row in found), default=0)
    if height != SIZE or any(len(row) != SIZE for row in found):
        raise SystemExit(f"{path.name}: is {width}x{height}, but block textures must be {SIZE}x{SIZE}")
    if found != pixels:
        raise SystemExit(f"{path.name}: differs from what gen_textures.py produces; regenerate it")
    print(f"ok  {path.relative_to(REPO_ROOT).as_posix()}  size={SIZE}x{SIZE}")


def main(argv):
    verify_only = "--verify" in argv
    for name, build in TEXTURES.items():
        path = OUT_DIR / f"{name}.png"
        pixels = build()
        check_generated(name, pixels)
        if not verify_only:
            write_texture(path, pixels)
        verify_texture(path, pixels)


if __name__ == "__main__":
    main(sys.argv[1:])
