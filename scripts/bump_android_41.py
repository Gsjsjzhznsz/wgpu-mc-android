#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.41 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.40"
NEW_VERSION = "2612.0.8-android.41"

NOTES = (
    "The shredded-world fix: android.40's alignment change made the compacted terrain draw tear "
    "the geometry apart, and this build stops the tear at its source.\n"
    "\n"
    "- **What you saw: a world of stretched, shredded triangles.** android.40 rounded every arena "
    "allocation to a multiple of four words so the terrain shaders could load vertices as whole "
    "vectors - the change that fixed the per-vertex load storm. But six indices a quad only lands "
    "on a four-word boundary for every second section, so the odd sections carried two spare words "
    "at the end of their index range, holding whatever the previous section had left there. One "
    "section drawing alone never completed a triangle out of them. The compacted draw - the path "
    "this renderer's phones actually draw with - packs sections back to back into one index buffer, "
    "and those two words sat *between* two sections' real indices: from the first slack word on, "
    "every triangle of the run was three wrong vertices, and the two spare words themselves were "
    "stale index values pointing into recycled sections. A world of long black spikes is what that "
    "reads as.\n"
    "- **The fix: draws measure indices exactly as baked.** The allocation stays rounded - that is "
    "the vec4 contract, and the shaders lean on it - but the exact baked count now travels beside "
    "it, and every consumer measures draws with it: the compacted draw's copies and destinations, "
    "the one-draw-per-section fallback, the batched indirect records. A section's indices now end "
    "in the buffer exactly where the next section's begin, and every triangle the GPU reads is a "
    "quad the bake wrote.\n"
    "- **Pinned by tests so it cannot come back quietly**: the arena records both counts and the "
    "gap between them, and the copy planner asserts every section's destination lands on a triangle "
    "boundary.\n"
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
                "The shredded-world fix: android.40's alignment change made the compacted terrain draw tear "
                "the geometry apart, and this build stops the tear at its source.",
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
