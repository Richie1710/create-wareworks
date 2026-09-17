"""Generates the GameTest structure templates of Create: Wareworks.

Writes gzip-compressed NBT structure templates (Minecraft 1.21.1, DataVersion 3955) into
src/main/resources/data/wareworks/structure/<name>.nbt. The templates contain only a floor layer:
template y = 0 is the floor (test-relative y = 1), everything above is air and is built by the tests
with GameTestHelper#setBlock.

Usage (from the repository root):
    py scripts/gen_structures.py            # (re)generate all templates in TEMPLATES, then verify them
    py scripts/gen_structures.py --verify   # only verify the existing files

Output is deterministic (gzip mtime 0), so regenerating unchanged templates produces identical files.
Every axis must be 1..48 (StructureBlockEntity.MAX_SIZE_PER_AXIS).
"""

import gzip
import io
import pathlib
import struct
import sys

DATA_VERSION = 3955  # SharedConstants.WORLD_VERSION of Minecraft 1.21.1
MAX_SIZE_PER_AXIS = 48
FLOOR_BLOCK = "minecraft:smooth_stone"
REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src/main/resources/data/wareworks/structure"

# name -> (size x, size y, size z). Keep in sync with the constants in dev.wareworks.gametest.WareworksGameTests.
TEMPLATES = {
    # Small plate for single-block tests.
    "empty_7x5x7": (7, 5, 7),
    # Aisle along +X at z = 3: controller x = 0, dock x = 1, up to 14 rails x = 2..15; rack planes at z = 2 and z = 4
    # with inventories at z = 1 and z = 5; dock at y = 2 with mast levels up to y = 7 and headroom up to y = 10.
    "aisle_16x10x7": (16, 10, 7),
    # Long rail line along +X at z = 1: dock x = 0, up to 47 rails x = 1..47 (aisle length cap tests).
    "rail_line_48x5x3": (48, 5, 3),
    # Two parallel aisles along +X for side-by-side comparisons (throughput scenarios): the layout of aisle_16x10x7 at
    # z = 3 (racks z = 2, 4; inventories z = 1, 5) and again at z = 9 (racks z = 8, 10; inventories z = 7, 11).
    "aisle_pair_16x10x13": (16, 10, 13),
}

TAG_END, TAG_INT, TAG_DOUBLE, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 6, 8, 9, 10


# --- writer ---------------------------------------------------------------------------------------------------------

def _string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _named(tag_type, name, payload):
    return bytes([tag_type]) + _string(name) + payload


def _int(value):
    return struct.pack(">i", value)


def _int_list(values):
    return bytes([TAG_INT]) + _int(len(values)) + b"".join(_int(v) for v in values)


def _compound_list(compounds):
    # Each element is a compound payload terminated by TAG_End.
    return bytes([TAG_COMPOUND]) + _int(len(compounds)) + b"".join(c + bytes([TAG_END]) for c in compounds)


def build_template(size, floor_block):
    sx, sy, sz = size
    palette = [_named(TAG_STRING, "Name", _string(floor_block))]
    blocks = [
        _named(TAG_LIST, "pos", _int_list([x, 0, z])) + _named(TAG_INT, "state", _int(0))
        for x in range(sx)
        for z in range(sz)
    ]
    body = (
        _named(TAG_INT, "DataVersion", _int(DATA_VERSION))
        + _named(TAG_LIST, "size", _int_list([sx, sy, sz]))
        + _named(TAG_LIST, "palette", _compound_list(palette))
        + _named(TAG_LIST, "blocks", _compound_list(blocks))
        + _named(TAG_LIST, "entities", _compound_list([]))
    )
    return _named(TAG_COMPOUND, "", body + bytes([TAG_END]))


def write_template(path, size):
    for axis in size:
        if not 1 <= axis <= MAX_SIZE_PER_AXIS:
            raise SystemExit(f"{path.name}: every axis must be 1..{MAX_SIZE_PER_AXIS}, got {size}")
    raw = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
        gz.write(build_template(size, FLOOR_BLOCK))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw.getvalue())


# --- reader (verification) ------------------------------------------------------------------------------------------

class _Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, fmt):
        size = struct.calcsize(fmt)
        (value,) = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += size
        return value

    def string(self):
        length = self.take(">H")
        value = self.data[self.pos:self.pos + length].decode("utf-8")
        self.pos += length
        return value

    def payload(self, tag_type):
        simple = {1: ">b", 2: ">h", TAG_INT: ">i", 4: ">q", 5: ">f", TAG_DOUBLE: ">d"}
        if tag_type in simple:
            return self.take(simple[tag_type])
        if tag_type == TAG_STRING:
            return self.string()
        if tag_type == TAG_LIST:
            element = self.take(">b")
            return [self.payload(element) for _ in range(self.take(">i"))]
        if tag_type == TAG_COMPOUND:
            result = {}
            while True:
                child = self.take(">b")
                if child == TAG_END:
                    return result
                name = self.string()  # read the name first: in "d[k] = v" Python evaluates v before k
                result[name] = self.payload(child)
        if tag_type in (7, 11, 12):
            fmt = {7: ">b", 11: ">i", 12: ">q"}[tag_type]
            return [self.take(fmt) for _ in range(self.take(">i"))]
        raise ValueError(f"unsupported tag type {tag_type}")


def verify_template(path, size):
    root_reader = _Reader(gzip.decompress(path.read_bytes()))
    if root_reader.take(">b") != TAG_COMPOUND or root_reader.string() != "":
        raise SystemExit(f"{path.name}: root must be an unnamed compound")
    root = root_reader.payload(TAG_COMPOUND)
    problems = []
    if root.get("DataVersion") != DATA_VERSION:
        problems.append(f"DataVersion {root.get('DataVersion')} != {DATA_VERSION}")
    if root.get("size") != list(size):
        problems.append(f"size {root.get('size')} != {list(size)}")
    if root.get("palette") != [{"Name": FLOOR_BLOCK}]:
        problems.append(f"unexpected palette {root.get('palette')}")
    expected_positions = {(x, 0, z) for x in range(size[0]) for z in range(size[2])}
    positions = {tuple(block["pos"]) for block in root.get("blocks", [])}
    if positions != expected_positions or any(block["state"] != 0 for block in root.get("blocks", [])):
        problems.append("floor blocks do not cover exactly the layer y = 0")
    if root.get("entities") != []:
        problems.append("entities must be empty")
    if problems:
        raise SystemExit(f"{path.name}: " + "; ".join(problems))
    print(f"ok  {path.relative_to(REPO_ROOT).as_posix()}  size={size}")


def main(argv):
    verify_only = "--verify" in argv
    for name, size in TEMPLATES.items():
        path = OUT_DIR / f"{name}.nbt"
        if not verify_only:
            write_template(path, size)
        verify_template(path, size)


if __name__ == "__main__":
    main(sys.argv[1:])
