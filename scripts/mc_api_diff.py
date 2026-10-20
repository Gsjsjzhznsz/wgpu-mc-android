#!/usr/bin/env python3
"""Diff the Minecraft API surface a version pair actually exposes, for the classes this mod uses.

The mod compiles against the unobfuscated 26.x jars, so a renamed method, a moved
class or a changed member signature between two MC versions is a compile error (or
a silent mixin miss) the day the tree is pointed at the next version. This tool
answers "what changed" before the first Gradle run:

  - reads every `import net.minecraft...` / `import com.mojang...` from the given
    source roots (Neolectrum's shared tree, the fabric module's own sources);
  - resolves each imported class in both jars (falling back to a by-simple-name
    search that catches the package renames - the renderpearl move of 26.3 - and
    reporting the old and new package when found elsewhere);
  - batch-javap's each jar (classes arrive many per invocation, supers discovered
    in rounds) and diffs the *effective* member set - the class plus every
    superclass and superinterface - so a member that merely moved up the chain is
    not reported lost;
  - prints, per class, the members the newer jar lost (a lost member is a
    potential break; an added member never is).

Usage (from the repo root):
  python3 scripts/mc_api_diff.py --old .mc-cache/client-26.1.2.jar \
      --new .mc-cache/client-26.3.jar \
      --sources Neolectrum/src/main/java Neolectrum/src/main/kotlin \
                Neolectrum-fabric/src/main/java Neolectrum-fabric/src/main/kotlin

`--javap` must point at a javap that reads the jars' class file version (a JDK 25
javap reads the 26.x jars; a JDK 21 one does not). Exit code 1 only on tool
errors, never on "differences were found".
"""
import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([a-zA-Z_][\w.]*);", re.M)
CHUNK = 120


def collect_imports(source_roots):
    imports = set()
    for root in source_roots:
        root = Path(root)
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.suffix not in (".java", ".kt"):
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for match in IMPORT_RE.finditer(text):
                name = match.group(1)
                parts = name.split(".")
                # a static import names a member: keep its owner instead
                if len(parts) > 2 and parts[-1][0].islower():
                    name = ".".join(parts[:-1])
                if name.startswith(("net.minecraft", "com.mojang")):
                    imports.add(name)
    return sorted(imports)


def jar_classes(jar):
    with zipfile.ZipFile(jar) as zf:
        return {
            entry[:-6].replace("/", ".")
            for entry in zf.namelist()
            if entry.endswith(".class")
        }


def resolve(jar, imports, all_classes):
    """Map each import to its FQN in this jar, noting package moves."""
    found, missing = {}, []
    for name in imports:
        if name in all_classes:
            found[name] = name
            continue
        simple = name.split(".")[-1]
        candidates = sorted(c for c in all_classes if c.split(".")[-1] == simple)
        if candidates:
            found[name] = candidates[0]
            if len(candidates) == 1:
                print(f"  moved: {name} -> {candidates[0]}")
            else:
                print(f"  moved (ambiguous, taking first): {name} -> {candidates[:3]}")
        else:
            missing.append(name)
    return found, missing


class JarIndex:
    """Effective member sets for a set of roots, supers discovered in batch rounds."""

    def __init__(self, jar, javap):
        self.jar = jar
        self.javap = javap
        self.headers = {}   # fqn -> header line
        self.members = {}   # fqn -> [member lines]

    def _query(self, classes):
        out = []
        for i in range(0, len(classes), CHUNK):
            batch = classes[i : i + CHUNK]
            proc = subprocess.run(
                [self.javap, "-p", "-cp", self.jar, *batch],
                capture_output=True, text=True, timeout=300,
            )
            out.append(proc.stdout)
            if proc.returncode != 0 and not proc.stdout:
                print(f"  javap batch failed: {proc.stderr[:200]}")
        return "\n".join(out)

    def build(self, roots):
        unknown = [r for r in roots if not r.startswith(("java.", "javax."))]
        seen = set()
        while unknown:
            text = self._query(sorted(set(unknown)))
            seen.update(unknown)
            nxt, current, buf = [], None, []

            def flush():
                if current:
                    self.headers[current] = buf[0] if buf else ""
                    self.members[current] = buf[1:]

            for line in text.splitlines():
                s = line.strip()
                if s.startswith("Compiled from") or not s:
                    continue
                if " class " in s or " interface " in s or " enum " in s or " record " in s:
                    flush()
                    current, buf = s.split()[2] if " class " in s or " interface " in s else s.split()[2], []
                    # the declaration line: `public final class com.x.Y extends ...`
                    tokens = s.replace("{", "").split()
                    # find the token after `class`/`interface`/`enum`/`record`
                    for idx, tok in enumerate(tokens):
                        if tok in ("class", "interface", "enum", "record"):
                            current = tokens[idx + 1]
                            break
                    buf = [s]
                    continue
                if s in ("}", "{"):
                    continue
                buf.append(s)
            flush()

            unknown = []
            for fqn, header in self.headers.items():
                if fqn in seen - set(unknown):
                    pass
                rest = header.replace("{", "")
                sups = []
                if " extends " in rest:
                    sups += rest.split(" extends ", 1)[1].split(" implements ")[0].split(",")
                if " implements " in rest:
                    sups += rest.split(" implements ", 1)[1].split(",")
                for sup in sups:
                    sup = sup.strip().split("<")[0]
                    if sup and "." in sup and not sup.startswith(("java.", "javax.")) and sup not in seen:
                        seen.add(sup)
                        unknown.append(sup)
            nxt = unknown

    def effective(self, fqn):
        """Member names+descriptors visible on the class incl. its supertypes."""
        cache, stack, sigs = set(), [fqn], set()
        while stack:
            cls = stack.pop()
            if cls in cache:
                continue
            cache.add(cls)
            sigs.update(self.members.get(cls, []))
            header = self.headers.get(cls, "")
            rest = header.replace("{", "")
            sups = []
            if " extends " in rest:
                sups += rest.split(" extends ", 1)[1].split(" implements ")[0].split(",")
            if " implements " in rest:
                sups += rest.split(" implements ", 1)[1].split(",")
            for sup in sups:
                sup = sup.strip().split("<")[0]
                if sup and "." in sup and not sup.startswith(("java.", "javax.")):
                    stack.append(sup)
        return sigs


def member_name(line):
    """`public void foo(net.x.Y, int)` -> foo;  `private final int bar` -> bar"""
    s = line.strip().rstrip(";")
    if "(" in s:
        head = s.split("(")[0]
        parts = head.split()
        return parts[-1] if parts else "?"
    parts = s.split()
    return parts[-1] if parts else "?"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--sources", nargs="+", required=True)
    ap.add_argument("--javap", default="javap")
    args = ap.parse_args()

    imports = collect_imports(args.sources)
    print(f"imported MC/Mojang classes: {len(imports)}")

    old_all, new_all = jar_classes(args.old), jar_classes(args.new)
    print(f"resolving against {args.old} ...")
    old_found, old_missing = resolve(args.old, imports, old_all)
    print(f"resolving against {args.new} ...")
    new_found, new_missing = resolve(args.new, imports, new_all)

    if old_missing or new_missing:
        print("\n== classes missing from a jar ==")
        for name in old_missing:
            print(f"  absent in OLD: {name}")
        for name in new_missing:
            print(f"  absent in NEW: {name}")

    print("\nbuilding the jar member indexes (batch javap) ...")
    old_idx = JarIndex(args.old, args.javap)
    old_idx.build(sorted(set(old_found.values())))
    new_idx = JarIndex(args.new, args.javap)
    new_idx.build(sorted(set(new_found.values())))

    print("\n== member diffs (members NEW lost; additions are not breaks) ==")
    broken = 0
    for name in imports:
        old_fqn, new_fqn = old_found.get(name), new_found.get(name)
        if not old_fqn or not new_fqn:
            continue
        old_eff, new_eff = old_idx.effective(old_fqn), new_idx.effective(new_fqn)
        lost = sorted(old_eff - new_eff)
        if lost:
            broken += 1
            print(f"\n  {name} ({len(lost)} lost):")
            for sig in lost:
                print(f"    - {sig}")
    if not broken:
        print("  (none - every resolvable class kept all its members)")
    else:
        print(f"\n{broken} class(es) lost members; cross-check usages above")


if __name__ == "__main__":
    sys.exit(main())
