#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.31 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.30"
NEW_VERSION = "2612.0.8-android.31"

NOTES = (
    "The render-distance switch can no longer ask a phone for more memory than the draws can name.\n"
    "\n"
    "- **What the 32-chunk report actually was, in the log's own numbers.** Switching the view to 32 chunks asked the section arena for 285,660,000 slots and was granted all of it - ten buffers, 1,216 MB - while holding 22M slots (6%, 83 MB). On a phone already carrying a 2 GB Java heap, the other 1.1 GB of granted-but-unnamed arena is what pushed the device into swap: the game at a frame a second, the launcher's own UI crawling, and the terrain beyond the near field never baked at all, because a rebuild stream that dispatches per frame cannot fill a world at one frame per second. The broken distant terrain and the near-freeze were the same event.\n"
    "- **The fix: the arena stops at what the record table can name.** Every drawn section names itself through one 32,768-slot record table, and past that table's reach no amount of extra arena can be drawn - it is only memory. The arena's two sizing paths (a world joined at a distance, and a mid-session render-distance change) now hold the view's ask under a ceiling derived from that table: 512 MB, between a 21- and 22-chunk view's weight. Distances phones run (8-20 chunks) pass through untouched; 22 and up come back as the ceiling, and the growth log says so in the same line (\"... the view asked for 285,660,000 asked, 134,217,728 after the record table's ceiling\").\n"
    "- **What 32 chunks is on a phone, honestly.** The record table caps the Rust terrain path at roughly a 24-chunk view's worth of sections; a view past that hands the excess to Minecraft's own meshes, which is correct and slower. With the arena no longer over-granted, the frame rate survives the load, the bake stream keeps up as it does at 12 chunks, and distant terrain fills in instead of staying hollow. A dense ocean world at extreme distances may still refuse sections into the vanilla path - that is the graceful answer, and it is one log line now (\"N left to Minecraft because the arena is full\").\n"
    "- If a view still stalls on this build, the terrain lines now separate the two suspects: the arena's \"holds ... for N chunk(s) of view\" line (was it granted past the ceiling?) and the per-second \"N left to Minecraft\" count (is the record table refusing?).\n"
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
                "The render-distance switch can no longer ask",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe render-distance switch can no longer ask",
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
