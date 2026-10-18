#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.40 and update updates.json.

Same shape as the earlier bumps: gradle.properties carries the version the CI
tag assert reads; updates.json carries the three promos and the per-version
changelog entries. Every old entry is verified preserved after the rewrite, so
a lost changelog line cannot slip through silently.
"""

import json
import sys
from collections import OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GRADLE = REPO / "Neolectrum" / "gradle.properties"
UPDATES = REPO / "Neolectrum" / "updates.json"

OLD_VERSION = "2612.0.8-android.39"
NEW_VERSION = "2612.0.8-android.40"

NOTES = (
    "The deep rendering pass: the terrain vertex stage does a quarter of the memory work it did, "
    "and every vertex carries less to every fragment.\n"
    "\n"
    "- **The terrain vertex stage loads 4 words a vertex where it loaded 9.** The stage fetched all "
    "of its geometry out of the section arena with storage-buffer loads - four scalars for the "
    "vertex itself, four more for the quad's four ambient-occlusion corners, and the section record "
    "- and the corner loads were the same four words for every vertex of a quad. The arena's vertex "
    "data is now loaded one whole vector per vertex, and the bake packs the four corner counts into "
    "one word per quad that all four vertices share; the section record moved to two whole vectors "
    "for the same reason. This is the one stage that runs per vertex, and load/store pressure there "
    "is what tile-based mobile GPUs (Mali in particular) are shortest of - this is the change aimed "
    "at the 'performance does not match the hardware' gap, not a tweak.\n"
    "- **Every fragment reads five varyings where it read eleven.** The four ambient-occlusion "
    "corners are one packed u32 now (the fragment unpacks them with three shifts), and the four "
    "face flags - which atlas, animated, mipped cutout, level-of-detail floor - are one more. The "
    "picture is unchanged: same numbers, same blend, one quarter of the interpolation traffic.\n"
    "- **The arena keeps the vec4 contract.** Every range the arena hands out is now a multiple of "
    "four words, so the shaders' vector addressing is exact by construction; a section costs one "
    "slot per quad more than before (the packed corner word), and the render-distance sizing "
    "constant accounts for it.\n"
    "\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)


def main() -> int:
    gradle = GRADLE.read_text()
    if f"neoforge_mod_version={OLD_VERSION}" not in gradle:
        print(f"gradle.properties does not carry {OLD_VERSION}; nothing to bump")
        return 1
    GRADLE.write_text(
        gradle.replace(
            f"neoforge_mod_version={OLD_VERSION}",
            f"neoforge_mod_version={NEW_VERSION}",
        )
    )
    print(f"gradle.properties -> {NEW_VERSION}")

    raw = UPDATES.read_text()
    doc = json.loads(raw, object_pairs_hook=OrderedDict)

    for promo in doc["promos"]:
        if doc["promos"][promo] == OLD_VERSION:
            doc["promos"][promo] = NEW_VERSION

    for section in ("26.1.2", "26.1.1", "26.1"):
        entries = doc[section]
        if NEW_VERSION in entries:
            continue
        notes = NOTES
        if section != "26.1.2":
            notes = notes.replace(
                "The deep rendering pass: the terrain vertex stage does a quarter of the memory work it did, "
                "and every vertex carries less to every fragment.",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.",
                1,
            )
        # Keep the file's own ordering shape: the new entry goes in ahead of
        # the first android.* entry, which is the newest one there.
        new = OrderedDict()
        inserted = False
        for key, value in entries.items():
            if not inserted and key.startswith("2612.0.8-android."):
                new[NEW_VERSION] = notes
                inserted = True
            new[key] = value
        if not inserted:
            new[NEW_VERSION] = notes
        doc[section] = new

    UPDATES.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    print("updates.json rewritten")

    # Verify: nothing lost, promos moved, every section carries the new entry.
    old_doc = json.loads(raw, object_pairs_hook=OrderedDict)
    for section, entries in old_doc.items():
        if section == "promos" or not isinstance(entries, dict):
            continue
        for key, value in entries.items():
            if key == OLD_VERSION:
                continue
            assert doc[section].get(key) == value, f"lost {section}/{key}"
    for promo, version in doc["promos"].items():
        assert version == NEW_VERSION, f"{promo} still at {version}"
    for section in ("26.1.2", "26.1.1", "26.1"):
        assert NEW_VERSION in doc[section], f"{section} has no {NEW_VERSION} entry"
    print("old entries verified preserved; promos ->", NEW_VERSION)
    return 0


if __name__ == "__main__":
    sys.exit(main())
