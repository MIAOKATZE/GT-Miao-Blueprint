"""Read-only source/schema audit; does not load mods or claim runtime compatibility.

python tools/audit_community_declarations.py --root E:/CodeGT/Source_Code_Reference_Collection/非官方mod
"""

import argparse
import hashlib
import json
import re
from pathlib import Path


def method_body(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*\{", source)
    if not match:
        raise ValueError("Missing native method: " + name)
    start = match.end() - 1
    depth = 0
    for position in range(start, len(source)):
        char = source[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start:position + 1]
    raise ValueError("Unterminated native method: " + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1]
                        / "src/test/resources/hologram/community-declarations-v004.json")
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    reports = []
    for module in fixture["modules"]:
        source_root = args.root / module["directory"]
        if not source_root.is_dir():
            raise ValueError("Missing required exact source tree: " + str(source_root))
        for sample in module["samples"]:
            path = source_root / sample["path"]
            raw = path.read_bytes()
            source = raw.decode("utf-8")
            if hashlib.sha256(raw).hexdigest() != sample["sha256"]:
                raise ValueError("Native sample changed; review exact declaration: " + str(path))
            declaration = method_body(source, "getStructureDefinition")
            construct = method_body(source, "construct")
            if "buildPiece(" not in construct:
                raise ValueError("Expected standard native construct path: " + str(path))
            for factory in sample["declarationFactories"]:
                if not re.search(r"\b" + factory + r"\s*\(", declaration):
                    raise ValueError("Missing reviewed declaration factory " + factory + ": " + str(path))
            reports.append({"module": module["tag"], "sample": sample["class"],
                            "schema": sample["schema"], "declarationFactories": sample["declarationFactories"],
                            "nativeConstruct": "buildPiece", "runtimeTested": False})
    print(json.dumps({"sourceSchemaAudit": "PASS", "samples": reports,
                      "boundary": "Source hashes/API schemas only; native mod loading/world construction untested"},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
