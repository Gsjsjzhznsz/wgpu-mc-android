#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.47 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.46"
NEW_VERSION = "2612.0.8-android.47"

NOTES = (
    "Fabric still left the world at the login packet - one layer deeper than android.46's fix. "
    "The class now transforms, and the crash moved to the next claimant of the same instruction.\n"
    "\n"
    "- **What the log showed**: `@Redirect conflict. Skipping fabric-renderer-api-v1...` right "
    "before `InjectionError: Critical injection failure: Redirector tesselateBlockProxy ... "
    "(0/1) succeeded. Scanned 0 target(s)` inside `Mixin transformation of "
    "net.minecraft.client.renderer.chunk.SectionCompiler failed` - disconnect, then the crash "
    "screen. NeoForge was never affected: Indigo is a Fabric-side renderer.\n"
    "- **The cause**: fabric-renderer-api-v1 (Indigo, in fabric-api on every Fabric install) "
    "redirects the same `tesselateBlock` call inside the same `SectionCompiler.compile` this mod "
    "redirected - that call site is the seam its own renderer hangs on, by design. Two "
    "`@Redirect`s cannot share an instruction: Mixin handed the instruction to one of them (ours "
    "won), and the loser's `require` check then threw, which is a class that refuses to "
    "transform. The android.46 signature fix is what let the class get as far as this collision - "
    "the two bugs stacked in front of each other.\n"
    "- **The fix**: the `tesselateBlock` redirect is gone, and it was redundant, not load-"
    "bearing: the `getRenderShape` predicate redirect already decides the same answer in the same "
    "block iteration (both read the same per-thread bake answer), so while Rust owns the section "
    "the whole `== MODEL` body - the call site included - never executes, and when Rust does not "
    "own it, Indigo's proxy runs exactly as it would without this mod. No branch of anything "
    "changed; the claim that bought the crash did.\n"
    "- **The gate this ships behind**: a second CI audit now reads fabric-api's *compiled* mixin "
    "classes from the pinned release players run (0.155.3+26.1.2), cross-checks every instruction "
    "both mods claim, and fails the build on any overlap that is not two plain `@Inject`s - the "
    "android.46 audit resolved our targets against the game's jar; this one resolves them against "
    "the other mods', which is the half it could not see.\n"
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
                "Fabric still left the world at the login packet - one layer deeper than android.46's fix. "
                "The class now transforms, and the crash moved to the next claimant of the same instruction.",
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
