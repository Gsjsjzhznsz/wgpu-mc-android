#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.30 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.29"
NEW_VERSION = "2612.0.8-android.30"

NOTES = (
    "The hollow-world report gets a driver interrogation of its own, and the occlusion list gets a guard against the camera moving.\n"
    "\n"
    "- **The terrain's batched draw is now proven on the device, not assumed from the adapter.** android.29 carries each section's record in its own indices and asks nothing per draw of the driver - which is why it was taken to be beyond the reach of the first-instance probe that correctly rules the game path's batching on this one. The device then drew hollow anyway, and a probe of the old channel says nothing about the new one: a driver can break a batched call in more than one way. A second probe now runs at device creation, drawn exactly as the terrain draws - four records whose indices encode their own record, one storage table, one multi-draw - and a driver that does not draw one record per record loses the batching and keeps one draw per section, correct and slow, with both probes' answers in the log.\n"
    "- **The occlusion list expires when the camera has moved out of it.** The game refills its visible-section list on a two-degree turn or an occlusion-graph update - never on movement - and a straight flight over terrain the graph has already judged leaves the list describing where the camera was. Sections the movement has since exposed are unnamed by that list, and while Minecraft's own mesh for them stays suppressed, a section neither renderer draws is a 16x16x16 hole - the other shape the hollow-world report can take. A frame whose camera has crossed out of the section the list was built in now asks the frustum about everything until the next refill; the count of such frames is in the per-second terrain line.\n"
    "- **The occlusion switch moves to the Optimization section** of the renderer's own settings page, renamed in spirit: it is the switch to flip first when terrain shows holes, and its description now says so, in English and in Chinese.\n"
    "- **The video settings page carries the field of view**, the one row of the game's own Display group it had not had; it lives on the General page between brightness and GUI scale, with the game's own words for 70 and 110.\n"
    "- The stale experiment label and description on the Rust terrain switch are gone: it draws all three terrain layers now, and both language files say what it actually does.\n"
    "\n"
    "If the world still draws hollow on this build, the log now says which of the three causes it was - the batched draw (\"index-record probe\"), the occlusion snapshot (\"moved out of the section the list was built in\"), or neither - and the occlusion switch is one tap away.\n"
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
                "The hollow-world report gets",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe hollow-world report gets",
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
