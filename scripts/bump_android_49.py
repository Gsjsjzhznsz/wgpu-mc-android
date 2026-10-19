#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.49 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.48"
NEW_VERSION = "2612.0.8-android.49"

NOTES = (
    "android.48's safety probe turned out to be the very thing standing between your phone and "
    "the fast path. This build takes the gate off its back and hardens the probe under it.\n"
    "\n"
    "- **What your log showed**: the floor probe read clear colour back "
    "(`[200, 200, 200, 200]`) and closed the batched terrain before the real probes ever ran - "
    "while the same driver rasterised ~15,000 terrain draws a frame for the game itself. The "
    "batched path was proven-safe by a probe that could not prove anything, and your device drew "
    "one section at a time at full draw-call cost.\n"
    "- **The gate is gone**: the channel probes (first-instance for the game path, index-record "
    "for the terrain's exact batched shape) now **always run**, and each one that reads its own "
    "sentinels back has proven the methodology for itself - no floor beneath them required. The "
    "floor stays as a diagnostic, and a failed channel still closes its own path: the probe "
    "system's protection is intact, only the false negative is gone.\n"
    "- **The probe methodology itself was rebuilt**, because an all-clear readback on a device "
    "whose game rasterises fine is the probe lying, not the driver: the render pass and the "
    "readback copy are now **separate submissions** (a tiler can no longer race the copy past the "
    "draws), the target is 64 pixels wide instead of the 4 pixels the quads minimally cover, the "
    "probe's data is written through both upload paths, and on a failure the vertex buffer itself "
    "is read back so the log says whether the draws never rasterised or their data never landed.\n"
    "- **If the index-record probe passes** (the log says 'the index-record probe drew four "
    "records through one multi-draw'), the terrain batches: one multi-draw per run of sections "
    "instead of hundreds of draw calls - the big one on Adreno.\n"
    "- **If it still fails**, the log now carries three readbacks (target, vertex buffer, probe "
    "channel) that pin the exact stage - send the log and the next build fixes the named stage.\n"
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
                "android.48's safety probe turned out to be the very thing standing between your phone and "
                "the fast path. This build takes the gate off its back and hardens the probe under it.",
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
