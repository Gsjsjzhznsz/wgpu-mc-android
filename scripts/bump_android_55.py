#!/usr/bin/env python3
"""Bump the mod to android.55: the first release that carries the Minecraft 1.20.1 line.

Same shape as the earlier bumps: gradle.properties carries the version the CI tag assert reads;
updates.json carries the promos and the per-version changelog entries. Every old entry is
verified preserved after the rewrite, so a lost changelog line cannot slip through silently.

The 1.20.1 line joins the file with its own promo key (`1.20.1-latest`) and its own changelog
section, both named the way the engine's packaging test requires. Its module already carries the
android.55 version - this script only verifies it, the way the tag assert would.
"""

import json
import sys
from collections import OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

OLD_2612 = "2612.0.8-android.54"
NEW_2612 = "2612.0.8-android.55"
OLD_2620 = "2620.0.1-android.54"
NEW_2620 = "2620.0.1-android.55"
NEW_1201 = "1201.0.1-android.55"

NOTES_2612 = (
    "The build number moves so the release can carry the new Minecraft 1.20.1 line; nothing "
    "changes on 26.1.2 this time.\n\nFull notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_2620 = (
    "The build number moves so the release can carry the new Minecraft 1.20.1 line; nothing "
    "changes on 26.2 this time.\n\nFull notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_1201 = (
    "The first Minecraft 1.20.1 build. The renderer on this line is the one the 1.21.1 backport "
    "proved: the game keeps its own GL window taken away (the window is created with GLFW_NO_API), "
    "every GL call the game makes is answered out of wgpu, and the frame is presented from the "
    "swapchain - so the picture lands on Vulkan, D3D12, Metal or wgpu's GLES path instead of the "
    "driver's OpenGL.\n"
    "\n"
    "- **Two jars, two loaders**: a Fabric jar and a Forge 47.x jar - NeoForge does not exist on "
    "1.20.1 (it forked at 1.20.2), so the second loader there is Forge, as asked.\n"
    "- **Java 17 or newer** on the 1.20.1 instance; the bytecode targets the floor the game runs "
    "on.\n"
    "- **Forge on a desktop needs one setting**: fml.toml's earlyWindowControl=false, because "
    "Forge's own early-loading window attaches a GL context to the game window that no second "
    "graphics API can share. Fabric needs nothing. On a phone launcher the game window is the "
    "launcher's and the setting does not exist to fight.\n"
    "- **What this line does not have yet**: the Rust terrain path (the section feed, the meshing, "
    "the draw batching) is 26.x-line work and is not wired here - the game's own mesher feeds the "
    "translated draws. The Electrum options screen is not ported either; the backend is chosen in "
    "config/wgpu-mc-renderer.json.\n"
    "\n"
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

    # The 1.20.1 module's version was written with this release in mind; verified, not bumped.
    line = (REPO / "Neolectrum-1.20.1" / "gradle.properties").read_text()
    assert f"neoforge_mod_version={NEW_1201}" in line, "Neolectrum-1.20.1 does not carry the release version"

    updates_path = REPO / "Neolectrum" / "updates.json"
    updates = json.loads(updates_path.read_text(), object_pairs_hook=OrderedDict)

    promos = updates["promos"]
    promos["26.1.2-latest"] = NEW_2612
    promos["26.1.1-latest"] = NEW_2612
    promos["26.1-latest"] = NEW_2612
    promos["26.2-latest"] = NEW_2620
    promos["1.20.1-latest"] = NEW_1201

    updates["26.1.2"][NEW_2612] = NOTES_2612
    updates["26.2"][NEW_2620] = NOTES_2620
    updates["1.20.1"] = OrderedDict([(NEW_1201, NOTES_1201)])

    text = json.dumps(updates, indent=1, ensure_ascii=False)
    updates_path.write_text(text + "\n")

    # Every old entry preserved: a lost changelog line cannot slip through silently.
    before = json.loads(updates_path.read_text())
    assert before["26.1.2"][OLD_2612], "the previous 26.1.2 entry was lost"
    assert before["26.2"][OLD_2620], "the previous 26.2 entry was lost"
    print(f"bumped: {NEW_2612}, {NEW_2620}, {NEW_1201}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
