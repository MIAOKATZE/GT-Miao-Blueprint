"""Read-only, type-preserving raw-cut evidence comparison; never changes an oracle or source artifact."""

from __future__ import annotations

import argparse
import collections
from dataclasses import dataclass
import gzip
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import struct
import zlib


NAMES = {0: "end", 1: "byte", 2: "short", 3: "int", 4: "long", 5: "float", 6: "double",
         7: "byte_array", 8: "string", 9: "list", 10: "compound", 11: "int_array", 12: "long_array"}


@dataclass(frozen=True)
class Tag:
    kind: int
    value: object
    element_kind: int | None = None


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, count):
        if count < 0 or self.offset + count > len(self.data):
            raise ValueError("Truncated or negative-length NBT")
        result = self.data[self.offset:self.offset + count]
        self.offset += count
        return result

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        # Java NBT uses modified UTF-8 (including surrogate-pair encoding), not plain UTF-8.
        raw = self.take(self.number("H")).replace(b"\xc0\x80", b"\x00")
        value = raw.decode("utf-8", errors="surrogatepass")
        return value.encode("utf-16", errors="surrogatepass").decode("utf-16", errors="surrogatepass")

    def tag(self, kind):
        if kind in (1, 2, 3, 4):
            return Tag(kind, self.number({1: "b", 2: "h", 3: "i", 4: "q"}[kind]))
        if kind in (5, 6):
            return Tag(kind, self.take(4 if kind == 5 else 8))  # Preserve IEEE bits, including signed zero.
        if kind == 7:
            return Tag(kind, self.take(self.number("i")))
        if kind == 8:
            return Tag(kind, self.string())
        if kind == 9:
            element, count = self.number("B"), self.number("i")
            if count < 0 or (element == 0 and count):
                raise ValueError("Invalid NBT list")
            return Tag(kind, [self.tag(element) for _ in range(count)], element)
        if kind == 10:
            entries = {}
            while True:
                child_kind = self.number("B")
                if child_kind == 0:
                    break
                key = self.string()
                if key in entries:
                    raise ValueError("Duplicate NBT compound key")
                entries[key] = self.tag(child_kind)
            return Tag(kind, entries)
        if kind in (11, 12):
            count = self.number("i")
            if count < 0:
                raise ValueError("Negative NBT array")
            return Tag(kind, tuple(self.number("i" if kind == 11 else "q") for _ in range(count)))
        raise ValueError(f"Unsupported NBT tag ID {kind}")

    def root(self):
        kind = self.number("B")
        self.string()
        result = self.tag(kind)
        if self.offset != len(self.data):
            raise ValueError("Trailing NBT bytes")
        return result


def read_nbt(path):
    data = path.read_bytes()
    return Reader(gzip.decompress(data) if data.startswith(b"\x1f\x8b") else data).root()


def field(tag, name, kind=None):
    if tag.kind != 10 or name not in tag.value:
        raise ValueError(f"Required compound field missing: {name}")
    result = tag.value[name]
    if kind is not None and result.kind != kind:
        raise ValueError(f"Unexpected type of {name}: {result.kind}, expected {kind}")
    return result


def scalar(tag, name, kind=None):
    return field(tag, name, kind).value


def triplet(tag):
    values = tag.value if tag.kind == 11 else [item.value for item in tag.value]
    if len(values) != 3:
        raise ValueError("Expected xyz/size triplet")
    return tuple(values)


def typed(tag):
    if tag is None:
        return {"type": "missing"}
    result = {"tagId": tag.kind, "type": NAMES[tag.kind]}
    if tag.kind == 10:
        result["value"] = {key: typed(value) for key, value in sorted(tag.value.items())}
    elif tag.kind == 9:
        result.update(elementTagId=tag.element_kind, value=[typed(item) for item in tag.value])
    elif tag.kind in (5, 6):
        value = struct.unpack(">f" if tag.kind == 5 else ">d", tag.value)[0]
        result.update(value=value if math.isfinite(value) else repr(value), ieeeHex=tag.value.hex())
    elif tag.kind == 7:
        result.update(value=list(tag.value), length=len(tag.value))
    elif tag.kind in (11, 12):
        result["value"] = list(tag.value)
    else:
        result["value"] = tag.value
    return result


def pointer(key):
    return key.replace("~", "~0").replace("/", "~1")


def children(tag, path):
    if tag.kind == 10:
        return [(path + "/" + pointer(key), value) for key, value in sorted(tag.value.items())]
    if tag.kind == 9:
        return [(f"{path}[{index}]", value) for index, value in enumerate(tag.value)]
    if tag.kind in (7, 11, 12):
        kind = {7: 1, 11: 3, 12: 4}[tag.kind]
        return [(f"{path}[{index}]", Tag(kind, value - 256 if tag.kind == 7 and value > 127 else value))
                for index, value in enumerate(tag.value)]
    return []


def differences(before, after, path):
    if before is None or after is None:
        present = before if before is not None else after
        nested = children(present, path)
        if nested:
            for child_path, child in nested:
                yield from differences(child if before is not None else None,
                                       child if after is not None else None, child_path)
        else:
            yield path, before, after, "missing"
        return
    if before.kind != after.kind:
        yield path, before, after, "type"
    elif before.kind == 10:
        for key in sorted(before.value.keys() | after.value.keys()):
            yield from differences(before.value.get(key), after.value.get(key), path + "/" + pointer(key))
    elif before.kind in (9, 7, 11, 12):
        if before.kind == 9 and before.element_kind != after.element_kind:
            yield path + "/#elementTagId", Tag(1, before.element_kind), Tag(1, after.element_kind), "list_element_type"
        if len(before.value) != len(after.value):
            yield path + "/#length", Tag(3, len(before.value)), Tag(3, len(after.value)), "length"
        first, second = children(before, path), children(after, path)
        for index in range(max(len(first), len(second))):
            child_path = f"{path}[{index}]"
            yield from differences(first[index][1] if index < len(first) else None,
                                   second[index][1] if index < len(second) else None, child_path)
    elif before.value != after.value:
        yield path, before, after, "value"


def categories(path):
    lower = path.lower()
    result = set()
    if any(word in lower for word in ("/items", "/inventory", "/internalinventory", "/inv", "/storage", "/cache", "/waitingtosend")):
        result.add("inventory")
    if any(word in lower for word in ("/config", "/upgrade", "priority", "mode", "setting", "locked")):
        result.add("configuration")
    if "pattern" in lower:
        result.add("patterns")
    if any(word in lower for word in ("owner", "placer", "uuid", "frequency", "privatechannel")) or re.search(r"/(proxy|part)/(p|k)$", lower):
        result.add("ownership_security")
    if re.search(r"/(proxy|part)/g$", lower):
        result.add("ae_grid_storage_identity")
    if any(word in lower for word in ("energy", "storedeu", "storedrf", "storedpower", "capacity", "voltage", "amperage", "fractionaleu", "cacheeu")):
        result.add("energy_capacity")
    if any(word in lower for word in ("fluid", "tank", "steam")):
        result.add("fluid")
    if any(word in lower for word in ("facing", "orientation", "rotation", "pointat", "paintedcolor")):
        result.add("facing_pose")
    if "cover" in lower:
        result.add("covers")
    if path in ("/tile/x", "/tile/y", "/tile/z"):
        result.add("tile_root_coordinates")
    if not result:
        result.add("other")
    return sorted(result)


class RegionWorld:
    def __init__(self, world, dimension, registry):
        self.directory = world if dimension == 0 else world / f"DIM{dimension}"
        self.registry = registry
        self.chunks = {}
        self.paths = set()
        self.section_keys = set()
        self.duplicate_selected_positions = []

    def chunk(self, x, z):
        if (x, z) not in self.chunks:
            path = self.directory / "region" / f"r.{x >> 5}.{z >> 5}.mca"
            self.paths.add(path)
            with path.open("rb") as handle:
                handle.seek(4 * ((x & 31) + 32 * (z & 31)))
                location = int.from_bytes(handle.read(4), "big")
                if not location >> 8:
                    raise ValueError(f"Missing on-disk chunk {x},{z}")
                handle.seek((location >> 8) * 4096)
                count = int.from_bytes(handle.read(4), "big")
                kind = handle.read(1)
                data = handle.read(count - 1)
            if kind == b"\x02":
                data = zlib.decompress(data)
            elif kind == b"\x01":
                data = gzip.decompress(data)
            else:
                raise ValueError(f"Unsupported region compression {kind!r}")
            level = field(Reader(data).root(), "Level", 10)
            if (scalar(level, "xPos", 3), scalar(level, "zPos", 3)) != (x, z):
                raise ValueError("Wrong on-disk chunk coordinates")
            sections = {scalar(section, "Y", 1): section for section in field(level, "Sections", 9).value}
            tiles = collections.defaultdict(list)
            for tile in field(level, "TileEntities", 9).value:
                position = tuple(scalar(tile, key, 3) for key in ("x", "y", "z"))
                tiles[position].append(tile)
            self.chunks[x, z] = sections, tiles
        return self.chunks[x, z]

    @staticmethod
    def nibble(section, key, index):
        tag = section.value.get(key)
        return ((tag.value[index >> 1] >> (4 * (index & 1))) & 15) if tag else 0

    def row(self, position):
        x, y, z = position
        sections, tiles = self.chunk(x >> 4, z >> 4)
        section = sections.get(y >> 4)
        block_id, meta = 0, 0
        if section is not None:
            self.section_keys.update(section.value)
            index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
            block_id = (field(section, "Blocks", 7).value[index]
                        | self.nibble(section, "Add", index) << 8
                        | self.nibble(section, "BlocksB2Hi", index) << 12)
            meta = self.nibble(section, "Data", index) | self.nibble(section, "Data1High", index) << 4
        if block_id not in self.registry:
            raise ValueError(f"Unregistered on-disk block ID {block_id}")
        matches = tiles.get(position, [])
        if len(matches) > 1:
            self.duplicate_selected_positions.append(list(position))
        result = {"block": Tag(8, self.registry[block_id]), "meta": Tag(3, meta)}
        if matches:
            result["tile"] = matches[-1]
        return Tag(10, result)


def row_only(row):
    return Tag(10, {key: row.value[key] for key in ("block", "meta", "tile") if key in row.value})


def relocated(row, position):
    result = dict(row.value)
    if "tile" in result:
        tile = dict(result["tile"].value)
        tile.update({key: Tag(3, value) for key, value in zip(("x", "y", "z"), position)})
        result["tile"] = Tag(10, tile)
    return Tag(10, result)


def scalar_count(rows, key):
    return sum(key in row.value for row in rows)


def coverage(rows):
    counts = collections.Counter()
    positions = collections.defaultdict(set)
    for index, row in enumerate(rows):
        tile = row.value.get("tile")
        if tile is None:
            continue
        pending = [("/tile", tile)]
        while pending:
            path, node = pending.pop()
            nested = children(node, path)
            if nested:
                pending.extend(nested)
            else:
                for category in categories(path):
                    counts[category] += 1
                    positions[category].add(index)
    return {key: {"leafFields": count, "tilePositions": len(positions[key])} for key, count in sorted(counts.items())}


def stage(name, first, second, classes, coordinates, output):
    groups = collections.defaultdict(lambda: {"differences": 0, "cellIndices": set(), "sampleIndices": []})
    stats = collections.Counter()
    domains = collections.Counter()
    changed_cells = set()
    stats["tilePresenceDifferentCells"] = sum(("tile" in a.value) != ("tile" in b.value) for a, b in zip(first, second))
    with output.open("w", encoding="utf-8", newline="\n") as handle:
        for index, (before, after) in enumerate(zip(first, second)):
            for path, old, new, reason in differences(before, after, ""):
                tags = categories(path)
                item = {"index": index, "sourceCoordinate": coordinates[index][0],
                        "targetCoordinate": coordinates[index][1], "sourceObservedNativeClass": classes[index],
                        "path": path, "reason": reason, "categories": tags,
                        "before": typed(old), "after": typed(new)}
                handle.write(json.dumps(item, ensure_ascii=True, separators=(",", ":")) + "\n")
                changed_cells.add(index)
                stats["differences"] += 1
                stats["blockDifferences" if path == "/block" else "metadataDifferences" if path == "/meta" else "tileFieldDifferences"] += 1
                stats["numericOrTagTypeDifferences"] += reason in ("type", "list_element_type")
                domains.update(tags)
                normalized_path = re.sub(r"\[\d+\]", "[*]", path)
                key = (classes[index], normalized_path, reason, None if old is None else old.kind, None if new is None else new.kind)
                group = groups[key]
                group["differences"] += 1
                group["cellIndices"].add(index)
                if len(group["sampleIndices"]) < 4:
                    group["sampleIndices"].append(index)
    return {"stage": name, "allCellsCompared": len(first), "differentCells": len(changed_cells),
            "equalCells": len(first) - len(changed_cells), **dict(stats), "categories": dict(sorted(domains.items())),
            "fullDifferencesJsonl": str(output), "fullDifferencesSha256": sha(output),
            "groupingNote": "All differences are counted. Array/list indices are grouped only here; JSONL retains every exact index and type/value.",
            "groups": [{"sourceObservedNativeClass": key[0], "path": key[1], "reason": key[2],
                        "beforeTagId": key[3], "afterTagId": key[4], "differences": value["differences"],
                        "differentCells": len(value["cellIndices"]), "sampleIndices": value["sampleIndices"]}
                       for key, value in sorted(groups.items(), key=lambda item: (-item[1]["differences"], str(item[0])))]}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    case, world, output = args.case_dir.resolve(), args.world_dir.resolve(), args.output_dir.resolve()
    if output == case or output == world or world in output.parents or case in output.parents:
        raise ValueError("Use a new report directory outside the original case and world")
    output.mkdir(parents=True, exist_ok=True)
    paths = {name: case / name for name in ("composite-manifest.nbt", "composite-source-baseline.nbt",
             "composite-raw-preview-reply.nbt", "composite-raw-preview-transport.nbt")}
    manifest = read_nbt(paths["composite-manifest.nbt"])
    baseline = read_nbt(paths["composite-source-baseline.nbt"])
    reply = read_nbt(paths["composite-raw-preview-reply.nbt"])
    transport = read_nbt(paths["composite-raw-preview-transport.nbt"])
    preview = field(reply, "preview", 10)
    source, target, size = (triplet(field(manifest, key)) for key in ("source", "raw", "size"))
    total = math.prod(size)
    if scalar(reply, "success", 1) != 1 or scalar(reply, "id", 8) != scalar(preview, "id", 8):
        raise ValueError("Preview reply is not an authenticated successful matching clipboard response")
    if any(scalar(preview, key, 3) != 0 for key in ("mirror", "rotation", "turnX", "turnZ")):
        raise ValueError("This comparison requires an identity-pose raw preview")
    if tuple(scalar(preview, key, 3) for key in ("x", "y", "z")) != source:
        raise ValueError("Preview payload source origin mismatch")
    raw_rows = field(baseline, "rows", 9).value
    cells = field(preview, "cells", 9).value
    if len(raw_rows) != total or len(cells) != total or scalar(preview, "selectedCells", 3) != total:
        raise ValueError("Expected complete source volume in both artifacts")
    ordered = [None] * total
    for cell in cells:
        x, y, z = (scalar(cell, key, 3) for key in ("x", "y", "z"))
        if not (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]):
            raise ValueError("Cut cell offset outside manifest")
        index = x + size[0] * z + size[0] * size[2] * y
        if ordered[index] is not None:
            raise ValueError("Duplicate cut cell")
        ordered[index] = row_only(cell)
    if any(row is None for row in ordered):
        raise ValueError("Missing cut cell")
    first = [row_only(row) for row in raw_rows]
    classes = [scalar(row, "tileClass", 8) if "tile" in row.value else "<no source tile>" for row in raw_rows]
    coordinates = []
    for y, z, x in itertools.product(range(size[1]), range(size[2]), range(size[0])):
        offset = (x, y, z)
        coordinates.append(([source[i] + offset[i] for i in range(3)], [target[i] + offset[i] for i in range(3)]))
    level_path = world / "level.dat"
    level = read_nbt(level_path)
    registry = {}
    for entry in field(field(level, "FML", 10), "ItemData", 9).value:
        key = scalar(entry, "K", 8)
        if key.startswith("\x01"):
            registry[scalar(entry, "V", 3)] = key[1:]
    disk = RegionWorld(world, scalar(manifest, "dimension", 3), registry)
    final = [disk.row(tuple(position[1])) for position in coordinates]
    adjusted = [relocated(row, coordinates[index][1]) for index, row in enumerate(ordered)]
    reports = [stage("baseline_to_actual_cut", first, ordered, classes, coordinates, output / "baseline-to-cut.full.jsonl"),
               stage("actual_cut_to_closed_world_unadjusted", ordered, final, classes, coordinates, output / "cut-to-closed.unadjusted.full.jsonl"),
               stage("actual_cut_root_xyz_relocated_to_closed_world", adjusted, final, classes, coordinates, output / "cut-relocated-to-closed.full.jsonl")]
    inputs = list(paths.values()) + [level_path] + sorted(disk.paths)
    inputs += [path for path in (case / "server.log", case / "artifact-hashes.json", case / "runtime.json") if path.exists()]
    field_coverage = {name: coverage(rows) for name, rows in (("baseline", first), ("actualCut", ordered), ("closedTarget", final))}
    class_index = collections.defaultdict(lambda: collections.Counter())
    for row, cls in zip(raw_rows, classes):
        if "tile" in row.value:
            class_index[scalar(field(row, "tile", 10), "id", 8)][cls] += 1
    result = {"scope": "Complete typed raw NBT evidence comparison; no oracle relaxation, PASS classification, field removal, or production mutation",
              "case": case.name, "inputAccess": "All source artifacts and Anvil files are opened read-only; output is restricted to a separate report directory.",
              "temporalBoundaries": {
                  "baseline": {"firstWorldTick": scalar(baseline, "firstWorldTick", 4), "lastWorldTick": scalar(baseline, "lastWorldTick", 4),
                               "scanTicks": scalar(baseline, "scanTicks", 3), "samplingScope": scalar(baseline, "samplingScope", 8)},
                  "actualCut": "The successful matching native preview reply contains the identity-pose full durable cut entry, after cut and before paste. Not a reconstruction from the baseline.",
                  "closedTarget": "Anvil bytes after natural shutdown. Includes native ready, committed physics, later natural ticks and shutdown; cannot attribute individual changes to ready or commit alone."},
              "provenance": {"clipboardId": scalar(reply, "id", 8), "previewRequestId": scalar(reply, "requestId", 8),
                             "transport": typed(transport), "identityPose": True, "sourceOrigin": source, "targetOrigin": target,
                             "volume": total, "size": size, "allGpkInventoryEnergyFieldsCompared": True,
                             "classBasis": "Exact live class recorded by the source baseline; disk contains serialized TE IDs, not an independently observed Java instance class.",
                             "sourceIdToObservedClasses": {key: dict(value) for key, value in sorted(class_index.items())}},
              "counts": {name: {"cells": len(rows), "tiles": scalar_count(rows, "tile"),
                                 "air": sum(scalar(row, "block", 8) == "minecraft:air" for row in rows)}
                         for name, rows in (("baseline", first), ("actualCut", ordered), ("closedTarget", final))},
              "fieldCoverageByCategory": field_coverage,
              "categoryBoundary": "Domain labels may overlap and only organize reporting. Exact full comparisons retain every field, including all unclassified fields under other; no category is excluded.",
              "rootRelocationBoundary": "Unadjusted JSONL preserves all raw coordinate differences. A separate additional comparison relocates only root tile x/y/z on a disposable expected copy, matching production; nested coordinates remain untouched.",
              "disk": {"chunks": len(disk.chunks), "sectionKeys": sorted(disk.section_keys),
                       "decoder": "Anvil Blocks + Add nibble + BlocksB2Hi nibble, Data + Data1High; checked against level.dat FML block registry; block-only, no TE loading",
                       "duplicateSelectedTilePositions": disk.duplicate_selected_positions},
              "stages": reports, "inputs": [{"path": str(path), "bytes": path.stat().st_size, "sha256": sha(path)} for path in inputs],
              "tool": {"path": str(Path(__file__).resolve()), "sha256": sha(Path(__file__))}}
    report = output / "typed-raw-nbt-audit.json"
    report.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    lines = [f"# {case.name} typed raw NBT evidence", "", "This is evidence analysis, not a replacement PASS verdict.", "",
             f"All {total:,} cells were compared; baseline/cut/closed TE counts: "
             + "/".join(str(result["counts"][name]["tiles"]) for name in ("baseline", "actualCut", "closedTarget")) + ".", "",
             "The baseline spans multiple ticks. The cut payload is the actual full preview reply. Closed Anvil is later than commit.", "",
             "| Comparison | Different cells | Block | Metadata | TE fields | All differences |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for item in reports:
        lines.append(f"| {item['stage']} | {item['differentCells']} | {item.get('blockDifferences', 0)} | {item.get('metadataDifferences', 0)} | {item.get('tileFieldDifferences', 0)} | {item.get('differences', 0)} |")
    lines += ["", "Every exact difference and typed before/after value is retained in the three full JSONL files; grouping and sample indices do not truncate totals.", "",
              "Only the additional relocation comparison adjusts root tile xyz. No g/p/k, inventory, owner, config, pattern, energy, facing, or cover field is removed.", "",
              "Source Java classes are those observed in the baseline; disk TE IDs do not independently prove the final runtime class.", "",
              "## Full-field groups after root-only relocation", "",
              "These groups include every remaining difference, without classifying any as expected. The exact cells and typed values remain in JSONL.", "",
              "| Source observed native class | Exact/grouped path | Tag IDs before → after | Differences | Cells |",
              "| --- | --- | --- | ---: | ---: |"]
    for item in reports[-1]["groups"]:
        lines.append(f"| {item['sourceObservedNativeClass']} | `{item['path']}` | {item['beforeTagId']} → {item['afterTagId']} | {item['differences']} | {item['differentCells']} |")
    (output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(report), "counts": result["counts"],
                      "stages": [{key: item.get(key, 0) for key in ("stage", "differentCells", "blockDifferences", "metadataDifferences", "tileFieldDifferences", "differences", "numericOrTagTypeDifferences")} for item in reports]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case-dir", type=Path, required=True)
    parser.add_argument("--world-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    run(parser.parse_args())
