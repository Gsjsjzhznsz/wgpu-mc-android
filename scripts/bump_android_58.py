#!/usr/bin/env python3
"""Bump the mod to android.58: the GLES backend draws a batch as one call.

Same shape as the earlier bumps: gradle.properties carries the version the CI tag assert reads;
updates.json carries the promos and the per-version changelog entries. Every old entry is
verified preserved after the rewrite, so a lost changelog line cannot slip through silently.

The actual change this release carries is structural, and it is for every device that renders
through a GL context (the iOS launchers' MobileGlues, the Android GLES mode, desktop GL): the
renderer's GLES backend could not batch terrain draws at all, because wgpu-hal's gles backend
has no multi-draw and glow did not even expose glMultiDrawElementsIndirect - so the mod's gate
(can_multi_draw_indirect) stayed closed, and a loaded world drew its sections through the
in-place fallback at roughly one draw per contiguous run, a few hundred per pass, on the
shims where each call is a boundary. Three fork commits change that:

  * Gsjsjzhznsz/glow (one commit on 0.17.0): the Gl wrapper exposes
    multi_draw_elements_indirect_offset, plus the symbol-loaded pair a context answers
    about itself - the generated GlFns had carried the pointer all along.
  * Gsjsjzhznsz/wgpu-hal (one commit): the gles backend draws draw_count > 1 as ONE
    glMultiDrawElementsIndirect (the plainest way to say "this is one call"), reports
    Features::MULTI_DRAW_INDIRECT_COUNT by the symbol the context exported - not by the
    version string, which is what a GL-on-Vulkan shim understates - and accepts a resolved
    glDrawElementsIndirect symbol as indirect execution alongside the version routes.
  * Gsjsjzhznsz/wgpu-core (one commit): the indirect-call validation is best-effort - a
    validation that cannot be built is logged and skipped instead of taking the device down,
    because the GLES path runs that construction for the first time exactly here.

The mod side changes only in its documentation and its lockfile: the gate reads the adapter's
bits and opens on its own, and the device's own index-record probe still decides - a driver
that cannot draw the batched shape keeps the per-record loop and the in-place/compacted
fallbacks. Vulkan devices are untouched by every one of these paths.
"""

import json
import sys
from collections import OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

OLD_2612 = "2612.0.8-android.57"
NEW_2612 = "2612.0.8-android.58"
OLD_2620 = "2620.0.1-android.57"
NEW_2620 = "2620.0.1-android.58"
OLD_1201 = "1201.0.1-android.57"
NEW_1201 = "1201.0.1-android.58"

NOTES_2612 = (
    "GLES and GL-on-Vulkan devices batch their terrain draws for real now. Until this release "
    "the renderer's GLES backend could not batch at all - wgpu's GL layer had no multi-draw and "
    "glow never exposed glMultiDrawElementsIndirect - so a loaded world drew its sections one "
    "contiguous run at a time, a few hundred draws per pass, on the shims where every call is a "
    "boundary. The GLES backend now draws a batch as one glMultiDrawElementsIndirect and reports "
    "the capability by the symbol the context actually exports (not the version string a "
    "GL-on-Vulkan surface reports), so devices that were on the fallback shapes move to one call "
    "per arena run. The device's own probe still decides - a driver that fails it keeps the "
    "fallback shapes, and the `terrain_indirect` switch still turns batching off. "
    "Indirect-call validation is best-effort now on backends that cannot build it. Vulkan "
    "devices are unchanged.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_2620 = (
    "Same GLES batching unlock as the 26.1.2 line ships this release: the renderer's GLES "
    "backend draws a terrain batch as one glMultiDrawElementsIndirect and reports the capability "
    "by the symbol the context exports, so GL-path devices move from a few hundred draws per "
    "pass to one call per arena run, with the device probe still holding the gate. The "
    "NeoForge jar changes only in its version stamp.\n\n"
    "Full notes: https://github.com/Gsjsjzhznsz/wgpu-mc-android/releases"
)

NOTES_1201 = (
    "Same GLES batching unlock as the 26.x lines ship this release: the renderer's GLES backend "
    "draws a terrain batch as one glMultiDrawElementsIndirect and reports the capability by the "
    "symbol the context exports, with the device probe still holding the gate. The Forge jar "
    "changes only in its version stamp.\n\n"
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
