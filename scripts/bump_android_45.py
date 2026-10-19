#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.45 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.44"
NEW_VERSION = "2612.0.8-android.45"

NOTES = (
    "The batching the driver was refused: wgpu's indirect-draw validation was zeroing its own "
    "rewritten arguments on the Adreno drivers, and the probes read their clear colour and turned "
    "the batched terrain path off for a fault the driver does not have.\n"
    "\n"
    "- **What the logs showed**: both probes read `[999, 999, 999, 999]` - the clear colour, not "
    "the records they drew - so the terrain pass fell back to drawing sections one contiguous run "
    "at a time: ~18,000 sections a frame in a few hundred runs, and the moment the run count "
    "drifted over the 256 rail, thousands of blits instead. Every counter agreed the world was "
    "being drawn; the frame cost was what disagreed.\n"
    "- **The fix**: wgpu's indirect validation - the compute pass it injects ahead of every "
    "indirect draw to bounds-check the arguments - is off by default on Android now. The draws "
    "read the arguments this renderer wrote and bounded itself, and the defaults migration moves "
    "the carried `on` aside the way vsync and the batching switch moved before; turning it back "
    "on holds as a diagnosis choice. Desktops keep the check.\n"
    "- **What that re-opens**: if the probes now read their four sentinels back in order, the "
    "batched path draws a whole terrain pass in one multi-draw command - a few commands a frame "
    "where the fallback paid hundreds of draws or thousands of copies. The boot log will say so: "
    "`the index-record probe ... read its sentinels back in order`.\n"
    "- **Also**: the in-place run rail moved 256 to 1024, so a loaded world (which plans "
    "258-324 runs) stops straddling it and paying the compacted draw's blit traffic on alternate "
    "frames.\n"
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
                "The batching the driver was refused: wgpu's indirect-draw validation was zeroing its own "
                "rewritten arguments on the Adreno drivers, and the probes read their clear colour and turned "
                "the batched terrain path off for a fault the driver does not have.",
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
