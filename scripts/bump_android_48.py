#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.48 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.47"
NEW_VERSION = "2612.0.8-android.48"

NOTES = (
    "The batched terrain path was closed by its own safety probe - and the probe was asking the "
    "wrong question. This build re-asks it on the target your game actually draws onto.\n"
    "\n"
    "- **What the log showed**: every release since the probes went in read the same answer on "
    "Adreno 640 - `[999, 999, 999, 999]`, the clear colour, off every pixel of every probe. The "
    "verdict was 'this driver cannot batch', and the terrain fell back to hundreds of draw calls "
    "a frame.\n"
    "- **What was wrong with that**: the probes rendered onto `R32Uint`, an integer target the "
    "game itself never asks for - the whole game is drawn onto the swapchain's `Rgba8Unorm`. The "
    "same batched call had demonstrably rasterised on this very device back when the game drew "
    "with it, so the one thing the probes and the batched path did not share - the target format - "
    "was the likeliest place for the verdict to come from. A mobile tile-based driver quietly "
    "not rasterising onto an integer target is a known class of fault, and it looks exactly like "
    "'the batched call is broken' when the probe is the only thing using that target.\n"
    "- **The change**: the probes now ask on `Rgba8Unorm`. A **floor probe** (four plain "
    "`draw_indexed` calls of a trivial pipeline) proves the methodology first, and its verdict "
    "gates the channel probes; the `R32Uint` question runs as a forensic beside it, so the next "
    "log names which one was lying. If the floor draws and the batched shape draws on Rgba8Unorm, "
    "the terrain batches - a handful of draw calls a frame instead of hundreds to thousands.\n"
    "- **Also**: the per-second claim report now carries the bake-waiter count, so a gap between "
    "'sections claimed' and 'sections drawn' can be read as bakes backing up rather than guessed "
    "at.\n"
    "\n"
    "If terrain still draws one section at a time, the log now says exactly which probe failed and "
    "what it read back - send the log.\n"
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
                "The batched terrain path was closed by its own safety probe - and the probe was asking the "
                "wrong question. This build re-asks it on the target your game actually draws onto.",
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
