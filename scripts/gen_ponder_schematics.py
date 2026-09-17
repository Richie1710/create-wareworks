"""Generates the Ponder scene schematics of Create: Wareworks.

Writes gzip-compressed NBT structure templates (Minecraft 1.21.1, DataVersion 3955) into
src/main/resources/assets/wareworks/ponder/<path>.nbt. Ponder loads them with
StructureTemplate.load(...) without a DataFixer, so block ids must already be 1.21.1 names.

Each file is only a stage:
  * layer y = 0 is a checkerboard base plate (white_concrete / snow_block), like Create's scenes;
  * every position above it carries an explicit "minecraft:air" entry.

The air entries are the point of this script. A PonderLevel's bounds are the bounding box of the
blocks the template actually places, NOT the "size" tag, and
ReplaceBlocksInstruction silently skips every position outside those bounds. Without air entries a
scene could not setBlock anything above the plate. Because the whole volume is in bounds, the scenes
build themselves with scene.world().setBlock(...) and no Wareworks block state ever ends up in a
.nbt file, so block state or block entity changes never require regenerating these templates.

Usage (from the repository root):
    py scripts/gen_ponder_schematics.py            # (re)generate all templates, then verify them
    py scripts/gen_ponder_schematics.py --verify   # only verify the existing files

Output is deterministic (gzip mtime 0), so regenerating unchanged templates produces identical files.
"""

import gzip
import io
import pathlib
import struct
import sys

DATA_VERSION = 3955  # SharedConstants.WORLD_VERSION of Minecraft 1.21.1
AIR = "minecraft:air"
# Create's ponder base plate, see any file under build/api-src/create/assets/create/ponder/.
PLATE_LIGHT = "minecraft:white_concrete"
PLATE_DARK = "minecraft:snow_block"

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src/main/resources/assets/wareworks/ponder"

# Scene path -> (size x, size y, size z). The path is what the scene passes to addStoryBoard, so it
# resolves to assets/wareworks/ponder/<path>.nbt. Keep in sync with dev.wareworks.client.ponder.
#
# The aisle scenes share one 9 x 7 x 9 stage (a square 9 base plate, configureBasePlate(0, 0, 9)):
#   aisle along +X at z = 4, controller x = 0, dock x = 1, six rails x = 2..7,
#   rack plane LEFT  z = 3 with its inventories at z = 2,
#   rack plane RIGHT z = 5 with its inventories at z = 6.
# The interface close-up uses a smaller 7 x 6 x 7 stage (square 7 base plate) with the aisle at z = 3.
TEMPLATES = {
    "stacker_crane/overview": (9, 7, 9),
    "warehouse/interface": (7, 6, 7),
    "warehouse/storing": (9, 7, 9),
    "warehouse/retrieving": (9, 7, 9),
}

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


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


def plate_and_air(size):
    """(palette, blocks): checkerboard plate at y = 0, explicit air everywhere above it.

    Returns the palette as a list of block ids and the blocks as a list of ((x, y, z), palette index).
    """
    sx, sy, sz = size
    palette = [PLATE_LIGHT, PLATE_DARK, AIR]
    blocks = []
    for x in range(sx):
        for z in range(sz):
            blocks.append(((x, 0, z), 0 if (x + z) % 2 == 0 else 1))
            for y in range(1, sy):
                blocks.append(((x, y, z), 2))
    return palette, blocks


def build_template(size):
    palette, blocks = plate_and_air(size)
    palette_tags = [_named(TAG_STRING, "Name", _string(name)) for name in palette]
    block_tags = [
        _named(TAG_LIST, "pos", _int_list(list(pos))) + _named(TAG_INT, "state", _int(state))
        for pos, state in blocks
    ]
    body = (
        _named(TAG_INT, "DataVersion", _int(DATA_VERSION))
        + _named(TAG_LIST, "size", _int_list(list(size)))
        + _named(TAG_LIST, "palette", _compound_list(palette_tags))
        + _named(TAG_LIST, "blocks", _compound_list(block_tags))
        + _named(TAG_LIST, "entities", _compound_list([]))
    )
    return _named(TAG_COMPOUND, "", body + bytes([TAG_END]))


def write_template(path, size):
    for axis in size:
        if axis < 1:
            raise SystemExit(f"{path.name}: every axis must be at least 1, got {size}")
    raw = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
        gz.write(build_template(size))
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
        simple = {1: ">b", 2: ">h", TAG_INT: ">i", 4: ">q", 5: ">f", 6: ">d"}
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
    if not path.is_file():
        raise SystemExit(f"{path.name}: missing, run py scripts/gen_ponder_schematics.py")
    root_reader = _Reader(gzip.decompress(path.read_bytes()))
    if root_reader.take(">b") != TAG_COMPOUND or root_reader.string() != "":
        raise SystemExit(f"{path.name}: root must be an unnamed compound")
    root = root_reader.payload(TAG_COMPOUND)
    problems = []
    if root.get("DataVersion") != DATA_VERSION:
        problems.append(f"DataVersion {root.get('DataVersion')} != {DATA_VERSION}")
    if root.get("size") != list(size):
        problems.append(f"size {root.get('size')} != {list(size)}")
    palette = [entry.get("Name") for entry in root.get("palette", [])]
    if palette != [PLATE_LIGHT, PLATE_DARK, AIR]:
        problems.append(f"unexpected palette {palette}")

    sx, sy, sz = size
    seen = {}
    for block in root.get("blocks", []):
        seen[tuple(block["pos"])] = block["state"]
    expected = {}
    for x in range(sx):
        for z in range(sz):
            expected[(x, 0, z)] = 0 if (x + z) % 2 == 0 else 1
            for y in range(1, sy):
                expected[(x, y, z)] = 2
    if seen != expected:
        missing = sorted(set(expected) - set(seen))
        wrong = sorted(p for p in set(expected) & set(seen) if expected[p] != seen[p])
        # The air entries are what puts the whole volume into the PonderLevel bounds.
        problems.append(f"blocks do not cover the volume exactly ({len(missing)} missing, {len(wrong)} wrong state)")
    if root.get("entities") != []:
        problems.append("entities must be empty (Ponder drops schematic entities)")
    if problems:
        raise SystemExit(f"{path.name}: " + "; ".join(problems))
    print(f"ok  {path.relative_to(REPO_ROOT).as_posix()}  size={size}  blocks={len(expected)}")


def main(argv):
    verify_only = "--verify" in argv
    for name, size in TEMPLATES.items():
        path = OUT_DIR / f"{name}.nbt"
        if not verify_only:
            write_template(path, size)
        verify_template(path, size)


if __name__ == "__main__":
    main(sys.argv[1:])
