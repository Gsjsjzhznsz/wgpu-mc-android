#!/usr/bin/env python3
"""Bump the mod to android.56: the Fabric jar's loader floor drops to 0.19.3.

Same shape as the earlier bumps: gradle.properties carries the version the CI tag assert reads;
updates.json carries the promos and the per-version changelog entries. Every old entry is
verified preserved after the rewrite, so a lost changelog line cannot slip through silently.

Unlike android.55 - where the 1.20.1 line was written fresh at its release version - all three
version modules move together this time, and the 1.20.1 changelog section is appended to, not
replaced: its android.55 entry must survive.

The actual fix this release carries: Neolectrum-fabric/src/main/resources/fabric.mod.json asked
the loader for >=0.19.5 - the version the build happened to compile against - while launchers
install 0.19.3 for 26.1.2, and mod resolution refused the mod before the game ever started.
The floor now names the loader the era actually ships for 26.1.2.
"""

import json
import sys
from collections import OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

OLD_2612 = "2612.0.8-android.55"
NEW_2612 = "2612.0.8-android.56"
OLD_2620 = "2620.0.1-android.55"
NEW_2620 = "2620.0.1-android.56"
OLD_1201 = "1201.0.1-android.55"
NEW_1201 = "1201.0.1-android.56"

NOTES_2612 = (
    "The Fabric jar's loader floor drops from 0.19.5 to 0.19.3 - the version launchers actually "
    "install for 26.1.2 (the iOS launchers among them) - so the mod loads on those profiles "
    "instead of dying at mod resolution with \"requires version 0.19.5 or later of mod 'Fabric "
    "Loader'\". The floor was copied from the build-time loader, but the only loader API the mod "
    "touches is the stable FabricLoader interface, and 0.19.3 is the loader the very profile that "
    "exposed the floor runs 26.1.2 with. The NeoForge jar changes only in its version stamp.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_2620 = (
    "The build number moves with the shared release tag so the 26.1.2 loader-floor fix can ship; "
    "nothing changes on 26.2 this time.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_1201 = (
    "The build number moves with the shared release tag so the 26.1.2 loader-floor fix can ship; "
    "nothing changes on 1.20.1 this time.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)


def bump_gradle(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    assert old in text, f"{path}: {old} not found"
    text = text.replace(old, new)
    path.write_text(text)


def main() -> int:
    bump_gradle(REPO / "Neolectrum" / "gradle.properties", OLD_2612, NEW_2612)
    bump_gradle(REPO / "Neolectrum-26.2" / "gradle.properties", OLD_2620, NEW_2620)
    bump_gradle(REPO / "Neolectrum-1.20.1" / "gradle.properties", OLD_1201, NEW_1201)

    updates_path = REPO / "Neolectrum" / "updates.json"
    updates = json.loads(updates_path.read_text(), object_pairs_hook=OrderedDict)

    promos = updates["promos"]
    promos["26.1.2-latest"] = NEW_2612
    promos["26.1.1-latest"] = NEW_2612
    promos["26.1-latest"] = NEW_2612
    promos["26.2-latest"] = NEW_2620
    promos["1.20.1-latest"] = NEW_1201

    # Every section the promos name gets its changelog entry - the packaging test holds each
    # promo's version to a changelog under that promo's own Minecraft version. The 26.1 line
    # answers for three sections (26.1, 26.1.1, 26.1.2); 1.20.1 is appended to, not replaced.
    updates["26.1.2"][NEW_2612] = NOTES_2612
    updates["26.1.1"][NEW_2612] = NOTES_2612
    updates["26.1"][NEW_2612] = NOTES_2612
    updates["26.2"][NEW_2620] = NOTES_2620
    updates["1.20.1"][NEW_1201] = NOTES_1201

    text = json.dumps(updates, indent=1, ensure_ascii=False)
    updates_path.write_text(text + "\n")

    # Every old entry preserved: a lost changelog line cannot slip through silently.
    before = json.loads(updates_path.read_text())
    assert before["26.1.2"][OLD_2612], "the previous 26.1.2 entry was lost"
    assert before["26.1.1"][OLD_2612], "the previous 26.1.1 entry was lost"
    assert before["26.1"][OLD_2612], "the previous 26.1 entry was lost"
    assert before["26.2"][OLD_2620], "the previous 26.2 entry was lost"
    assert before["1.20.1"][OLD_1201], "the previous 1.20.1 entry was lost"
    print(f"bumped: {NEW_2612}, {NEW_2620}, {NEW_1201}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
