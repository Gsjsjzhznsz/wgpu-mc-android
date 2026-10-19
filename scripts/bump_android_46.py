#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.46 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.45"
NEW_VERSION = "2612.0.8-android.46"

NOTES = (
    "Fabric could load the mod, reach a world, and then be thrown out of it: the chunk-compile "
    "mixin pinned NeoForge's five-argument compile signature, which vanilla does not have, and a "
    "mixin whose target cannot be found is a class that refuses to transform.\n"
    "\n"
    "- **What the log showed**: `Mixin apply for mod wgpu_mc failed ... SectionCompilerMixin` "
    "followed by `Mixin transformation of net.minecraft.client.renderer.chunk.SectionCompiler "
    "failed` the moment a world joined - `LevelRenderer.allChanged` loads that class, the load "
    "threw, the login packet was dropped, and the client disconnected and hit the crash screen. "
    "NeoForge was never affected: its patch is what adds the fifth `List` argument the mixin was "
    "pinned to.\n"
    "- **The fix**: every injection in that mixin now names both signatures - the four-argument "
    "one vanilla (and Fabric) runs, and the five-argument one NeoForge patched in - and Mixin "
    "applies whichever the running loader actually has. The two callbacks that used to capture "
    "the compile's arguments now capture none of them (Mixin takes all of a target's arguments or "
    "none, so an argument-capturing handler pinned to either loader could not serve both); the "
    "region and section they need come from a per-thread rebuild context the task hook sets "
    "before the compile runs. Nothing else about the path changed: the geometry skip, the "
    "occlusion mask and the block-entity hand-over behave as before on both loaders.\n"
    "- **What that changes on Fabric**: until now every Fabric section rebuild built Minecraft's "
    "own geometry in full and threw it away, on top of the Rust bake - the mixin that skips that "
    "build could not apply. With it applying, a Fabric rebuild does what a NeoForge rebuild does: "
    "one bake, no second mesher, and the per-block work of a compile is the mask pass.\n"
    "- **The gate this ships behind**: a CI step now resolves every `method =`, `target =`, "
    "`@Accessor` and `@Invoker` string in both mixins.json files against the real vanilla client "
    "jar before any build runs, so a mixin pinned to one loader's patched signature cannot ship "
    "silently again.\n"
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
                "Fabric could load the mod, reach a world, and then be thrown out of it: the chunk-compile "
                "mixin pinned NeoForge's five-argument compile signature, which vanilla does not have, and a "
                "mixin whose target cannot be found is a class that refuses to transform.",
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
