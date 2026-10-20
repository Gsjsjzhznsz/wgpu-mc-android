#!/usr/bin/env python3
"""Bump the mod to android.57: the Fabric jars' mixin annotations are 0.17.3-shaped again.

Same shape as the earlier bumps: gradle.properties carries the version the CI tag assert reads;
updates.json carries the promos and the per-version changelog entries. Every old entry is
verified preserved after the rewrite, so a lost changelog line cannot slip through silently.

The actual fix this release carries: the fabric sponge-mixin fork changed `Redirect.at()`
from a single `At` (0.17.3) to `At[]` (0.17.4), and the Fabric modules compiled against
0.17.4 (via loader 0.19.5/0.20.1 transitives), so every `at = @At(...)` in the shipped jars
was encoded as an array - a legal encoding for that declaration but one the MixinExtras on
older loaders (0.5.4 on loader 0.19.3 and below, 0.4.x on the 1.20.x era) cannot read: the
first mixin with a @Redirect dies at preparation with "ArrayList cannot be cast to
AnnotationNode". Seen on the iOS Prisma build (loader 0.19.3) right after android.56 let the
mod past mod resolution. The three Fabric builds now pin sponge-mixin 0.17.3 on their compile
classpaths - the one shape every loader reads - and a CI audit
(scripts/audit_fabric_mixin_shape.py) fails any future jar whose @Redirect shape regresses.
The NeoForge jars change only in their version stamps.
"""

import json
import sys
from collections import OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

OLD_2612 = "2612.0.8-android.56"
NEW_2612 = "2612.0.8-android.57"
OLD_2620 = "2620.0.1-android.56"
NEW_2620 = "2620.0.1-android.57"
OLD_1201 = "1201.0.1-android.56"
NEW_1201 = "1201.0.1-android.57"

NOTES_2612 = (
    "Fixes the launch crash \"Mixin transformation of net.minecraft.client.renderer.texture."
    "TextureAtlas failed / ClassCastException: ArrayList cannot be cast to AnnotationNode\" on "
    "loaders whose MixinExtras predates 0.5.5 - the iOS launchers running 26.1.2 on Fabric "
    "loader 0.19.3 among them. The jar's mixin annotations had been compiled against the "
    "sponge-mixin that encodes Redirect.at as an array (0.17.4+); every loader reads the "
    "single-annotation shape, so the Fabric build now compiles against 0.17.3 and a CI audit "
    "holds the shape. The NeoForge jar changes only in its version stamp.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_2620 = (
    "Same Fabric-jar annotation-shape fix as the 26.1.2 line ships this release: the jar's "
    "@Redirect annotations are compiled back to the single-annotation shape every loader's "
    "MixinExtras reads, instead of the array shape that kills MixinExtras <=0.5.4. A CI audit "
    "holds the shape from here on. The NeoForge jar changes only in its version stamp.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_1201 = (
    "Same Fabric-jar annotation-shape fix as the 26.x lines ship this release: the jar's "
    "@Redirect annotations are compiled back to the single-annotation shape every loader's "
    "MixinExtras reads - the era's loaders bundle MixinExtras 0.4.x, which could not read the "
    "array shape the previous jar carried. A CI audit holds the shape from here on. The Forge "
    "jar changes only in its version stamp.\n\n"
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
    # promo's version to a changelog under that promo's own Minecraft version.
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
