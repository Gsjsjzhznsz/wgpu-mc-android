#!/usr/bin/env python3
"""Bump the mod version to 2612.0.8-android.44 and update updates.json.

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

OLD_VERSION = "2612.0.8-android.43"
NEW_VERSION = "2612.0.8-android.44"

NOTES = (
    "The self-contained jar: the NeoForge `-all` build now carries Kotlin for Forge, and the "
    "Fabric build loads at all.\n"
    "\n"
    "- **NeoForge: no separate KFF install any more.** The `-all` jar embedded the Kotlin stdlib "
    "but still demanded Kotlin for Forge as a separate download - a name that promised "
    "dependencies and shipped half of them. The four KFF artifacts (the Kotlin runtime carrier, "
    "the `kotlinforforge` mod itself, its library and the language provider `modLoader=` resolves "
    "to) now ride inside the jar under `META-INF/jarjar/`, so a fresh install needs exactly one "
    "file. **One migration step if an older Kotlin for Forge is still in `mods/`: remove it "
    "once after updating.** The loader refuses two mods with the same id, and both the old KFF "
    "and this jar declare `kotlinforforge`.\n"
    "- **Fabric: the mod loads.** The Fabric entrypoint was a Kotlin `object`, whose constructor "
    "compiles private - and Fabric's language adapter instantiates entrypoints with a plain "
    "reflective `newInstance()` that cannot touch a private constructor, so every launch died "
    "with `IllegalAccessException ... modifiers \"private\"` before one line of the mod ran. The "
    "entrypoint is a class now, and the loader constructs it like any other. Fabric keeps its "
    "one external dependency: fabric-api, as usual.\n"
    "- **Fabric builds hold the release now.** The Fabric job graduated from `continue-on-error`: "
    "a Fabric compile break stops the release instead of shipping a NeoForge-only jar under a "
    "version the update checker calls current, and both jars' content checks are strict.\n"
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
                "The self-contained jar: the NeoForge `-all` build now carries Kotlin for Forge, and the "
                "Fabric build loads at all.",
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
