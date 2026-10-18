#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.34 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.33"
NEW_VERSION = "2612.0.8-android.34"

NOTES = (
    "The corruption that survived the last two fixes has its root cause, and it is one line long: the section-record table's batched uploads handed every run after the first the FIRST run's bytes. Fixed, with a contract test that pins it.\n"
    "\n"
    "- **What you saw.** Stretched black shards and misplaced white slabs that moved around between frames - the picture the 32-chunk screenshots are full of, still present on android.33.\n"
    "- **Why.** The terrain shader reads each section's record (its origin and where its vertices live) out of a table, addressed by the section's own index words. The table is uploaded per frame in runs of adjacent slots to keep the write count down. The gathering recorded where the FIRST run's bytes sit and pushed a hardcoded zero for every later run - so run two, three, four wrote the first run's records at their own slots. Every section in those runs then fetched somebody else's vertices at somebody else's position: geometry nobody baked, drawn wherever the foreign record pointed. Which sections corrupted moved with each frame's run layout, which is why it flickered and never matched a single cause.\n"
    "- **The fix.** Each run now records its own byte position when it opens - the arena's own uploads always did, which is what makes this divergence visible in hindsight. A contract test draws three scattered runs and asserts every run's window is its own records.\n"
    "- **Iris/Vulkan research updated (docs/iris-adaptation-research.md section 4.6).** The Iris 1.12.0-snapshot jar circulating in the wild is the official Vulkan rewrite in development: a backend abstraction with OpenGL and Vulkan implementations, riding Minecraft 26.x's own native Vulkan renderer, compiling pack GLSL to SPIR-V with shaderc. The released Iris (1.11.7) has none of this. Separately, Vitrail already loads OptiFine-format packs on Minecraft's native Vulkan renderer, NeoForge included - the first shipping proof that 'Vulkan renderer + OptiFine packs' works. What it means for this mod is written down in the doc.\n"
    "\n"
    "If any corruption remains, send the log - but this one was reproducible in the records themselves, and the test fails on the old code.\n"
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
                "The corruption that survived the last two fixes",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe corruption that survived the last two fixes",
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
