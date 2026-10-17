#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.33 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.32"
NEW_VERSION = "2612.0.8-android.33"

NOTES = (
    "The stretched-terrain corruption - long thin triangles shooting into the sky while a world loads - is closed on the renderer's own paths, and both fixes count and log themselves so a field log can name any remaining culprit.\n"
    "\n"
    "- **Where the streaks came from.** A baked index is `(record_slot << 17) | local_vertex`, and the shader reads the section's record out of the high bits. Two ways to draw wrong geometry: a bake whose local vertex id ran past the 17 bits the format holds (the guard for this was a `debug_assert`, which a release build compiles away - the spill silently shifted the record slot and fetched another section's vertices at this one's transform), and a rebuild the pool refused whose replaced record slots were freed anyway - the kept section then read whoever took the slot next. Both are exactly the picture the 32-chunk screenshot showed: triangles stretched across the world, chunks in the wrong place, black fragments.\n"
    "- **The fixes.** Every bake's indices are now checked while they are still plain local ids - one pass over memory the packing loop walks anyway - and an overflow refuses the bake, keeps the section's game mesh, counts itself, and says so on the log with the position and the layer. The replaced records are no longer deferred on a refused replacement: the deferral belongs to the replacement, not to the attempt. Two contract tests pin both behaviours.\n"
    "- **What 32 chunks really costs, measured.** The session's numbers: the 32-chunk view asks the arena for 1.09 GB, the record table's ceiling holds it at 512 MB, and until the pool fills the game meshes the rest - which at 97% device memory is the freeze. Until the distance-capped fallback lands, 16-20 chunks is the setting this device can hold.\n"
    "- **Iris research updated with market evidence.** VulkanMod's own shader answer is Beryl - which ships an integrated shader pack and loads no Iris packs at all. The doc now records it: the Vulkan-native pipeline route has a working precedent, and pack compatibility remains the one unsolved hard problem (docs/iris-adaptation-research.md, section 4.5).\n"
    "\n"
    "If streaks ever come back, send the log: a line starting `a bake at (x, y, z)` names the section, the layer and how far past the cliff the mesher reached.\n"
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
                "The stretched-terrain corruption",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe stretched-terrain corruption",
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
