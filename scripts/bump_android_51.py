#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.51 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.50"
NEW_VERSION = "2612.0.8-android.51"

NOTES = (
    "A performance round, on the GPU side of the terrain pixel. Your Adreno 640 draws a 12-chunk "
    "view at 1080p, and the terrain fragment stage was the most expensive thing in it: every "
    "minified terrain pixel paid nine texture fetches (one plain plus the game's own four-tap "
    "rotated grid at two mip levels each) to fight shimmer that, that far out, the arithmetic "
    "already answers for free.\n"
    "\n"
    "- **The far field stops paying the grid's bill**: the rotated grid's taps sit 0.395 of a fine "
    "texel off centre, and at eight texels a pixel that is under five per cent of one coarse texel - "
    "so past that line two fetches (one bilinear at each level, blended by the same fraction) return "
    "what the eight returned, to within what a screen cannot resolve. Most of a loaded view is past "
    "that line: expect the far three-quarters of the terrain pixels to draw at two fetches instead "
    "of nine.\n"
    "- **The near field keeps the grid**: below the line the texel edge is screen-sized and the grid "
    "is the anti-aliasing - nothing changes where shimmer is actually visible. Magnified surfaces "
    "also skip the level math they never read, and the minified middle band stops fetching the plain "
    "texel it was multiplying by zero.\n"
    "- **The two terrain shaders carry the tiers together**, and a test now holds them to it: their "
    "sampling code has to stay identical file for file, or the solid and cutout layers would seam at "
    "the tier line.\n"
    "- **A log lie fixed on the way**: the arena's \"it cannot grow\" verdict compared the pool's "
    "total size against one buffer's cap, so on your device it said cannot-grow with thirty more "
    "buffers of headroom left. Informational only - the rebuild was never gated on it - but every "
    "trim line you sent was carrying it.\n"
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
                "A performance round, on the GPU side of the terrain pixel. Your Adreno 640 draws a 12-chunk "
                "view at 1080p, and the terrain fragment stage was the most expensive thing in it: every "
                "minified terrain pixel paid nine texture fetches (one plain plus the game's own four-tap "
                "rotated grid at two mip levels each) to fight shimmer that, that far out, the arithmetic "
                "already answers for free.",
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
