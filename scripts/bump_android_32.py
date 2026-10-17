#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.32 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.31"
NEW_VERSION = "2612.0.8-android.32"

NOTES = (
    "The distant-terrain corruption - the flat black band over the ocean - is fixed: Minecraft's own mesh now stays until the Rust bake has actually landed.\n"
    "\n"
    "- **What the black band was, in the log's own numbers.** Minecraft's mesh for a section was dropped the moment the Rust baker accepted its payload - but accepting is queueing, and the bake queue drains at about thirty sections a second. A 32-chunk world needs tens of thousands of them, so every section sat with no game mesh and no arena geometry for minutes: a band of terrain drawn by nobody. Over an ocean that is a flat void-coloured plane where the sea should be - the corruption reported at 12 chunks in flight and at 32 chunks standing still, on every build since batching shipped.\n"
    "- **The gate moved from the offer to the publish.** A section keeps Minecraft's own mesh until its bake has landed in the arena - the world is drawn by the game's own path from the first seconds, and each section hands over to the Rust terrain exactly once, when the arena can actually draw it. The publish reaches the JVM through a new per-tick drain (`publishedSections`, the mirror of the refusal drain); the first publish of a section queues one rebuild that retires the game's mesh. Refused, trimmed and parked sections all keep or regain the game's mesh - a picture drawn by the slower renderer, never a picture missing.\n"
    "- **No re-bake loop.** A rebuild that retires a mesh carries no changed blocks, and a published section whose payload has nothing to say no longer calls the baker at all - so the publish drain converges instead of cycling rebuild-and-rebake forever. The per-second terrain line now separates the two states: `N of the claims published` (the arena draws them) against the claims still waiting for their bake.\n"
    "- **Loading looks different on purpose.** At extreme view distances the far field stays on Minecraft's own meshes until the arena reaches it - correct and slower, and the handover is progressive rather than a band of holes. The Iris adaptation research this session produced is checked in at docs/iris-adaptation-research.md.\n"
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
                "The distant-terrain corruption",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe distant-terrain corruption",
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
