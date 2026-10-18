#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.39 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.38"
NEW_VERSION = "2612.0.8-android.39"

NOTES = (
    "The iOS crash from the last report, fixed; two renderer savings on Android.\n"
    "\n"
    "- **iOS: \"can only access UIView on the main thread\" - the renderer could not be created.** The android.38 fix got the mod loading on iOS (the dylib is found, the launcher's game view reaches the Rust side), but the first device creation then panicked inside the Metal window glue: wgpu asks the game view for its render layer through code that refuses to touch a UIView off the main thread, and Prisma runs the game on a background thread. The one call that touches the view is dispatched onto the process main queue now and waited for - once per renderer, at a moment the main thread is idle in the launcher's run loop - and everything after it works on the CAMetalLayer, which is documented thread-safe. First real-device run past device creation: report what the log says.\n"
    "- **Android: the compacted terrain draw no longer re-copies its index data every frame.** The compacted draw blits the frame's index ranges out of the arena every frame before drawing them as one call per run - several megabytes of blits a frame even when nothing changed. Each terrain pass now owns its own compacted buffer, and a frame whose copy plan is byte-for-byte what the pass blitted last - over an arena whose mutation counter has not moved - skips the blits and draws the buffer as it stands. Standing still or turning in place in a settled world, the copies drop to zero; a bake, a trim, or a pool change moves the counter and the copies come back for that frame. The counter lives on the arena storage behind the lock the mutations already hold, so a cached plan cannot outlive the ranges it names.\n"
    "- **Android: the refuse-and-resend accounting warns no more.** Cutting the render distance handed sections back through the same channel a refusal uses, but never counted them as refused - the JVM checked its sum and read `-324 unaccounted for`. A trim drop is counted where the hand-over is fed from now, and regression tests hold the sequence.\n"
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
                "The iOS crash from the last report, fixed; two renderer savings on Android.",
                "Same jar as the 26.1.2 release of this build; the notes describe it once.\n\nThe iOS crash from the last report, fixed; two renderer savings on Android.",
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
