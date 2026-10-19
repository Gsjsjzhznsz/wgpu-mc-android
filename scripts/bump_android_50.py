#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.50 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.49"
NEW_VERSION = "2612.0.8-android.50"

NOTES = (
    "Your android.49 log caught the bug that has been eating worlds whole - the one reported as "
    "\"some places are transparent / not rendered\". The renderer obeyed the game's occlusion list "
    "even when that list was empty or named nothing the arena holds, and your log shows thirty-five "
    "straight seconds of a 526-section world drawing nothing at all before quit.\n"
    "\n"
    "- **The blank world, explained**: the game's visibility snapshot is only refilled when the "
    "camera turns two degrees or its occlusion graph moves. When one refill came back empty - and "
    "the camera then held still - the snapshot never refilled, and the terrain pass culled every "
    "section it held with an answer about none of them. The arena had the world; the pass refused "
    "to draw it. Both renderers went blank together, which is why nothing else in the log looked "
    "wrong.\n"
    "- **The fix**: a list that is empty, or that names no section the arena can actually draw, is "
    "now read as \"no list\" for that frame - the frustum decides instead, exactly as it does before "
    "the first list of a session arrives. Over-draw replaces the hole, and the occlusion culling "
    "itself is untouched on every frame the list genuinely answers about the arena (the trust check "
    "exits on the first named section, so healthy frames pay nothing for it).\n"
    "- **Visible in the log**: the once-a-second frustum line now ends with \"N frame(s) that drew "
    "from the frustum because the game's list was empty or named nothing the arena holds\" - a "
    "wedged graph is now a climbing number instead of an invisible world. If you see the world go "
    "blank again, that number and the log are the report.\n"
    "- **Fabric side audited**: the per-tick hooks (block cache, camera pin, debug reload) all "
    "early-return on cached settings and empty queues, and both loaders share one render path - "
    "there is no Fabric-specific frame cost to remove. The batching android.49 delivered stands.\n"
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
                "Your android.49 log caught the bug that has been eating worlds whole - the one reported as "
                "\"some places are transparent / not rendered\". The renderer obeyed the game's occlusion list "
                "even when that list was empty or named nothing the arena holds, and your log shows thirty-five "
                "straight seconds of a 526-section world drawing nothing at all before quit.",
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
