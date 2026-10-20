#!/usr/bin/env python3
"""Cross-reference mc_api_diff.py output with the identifiers this repo actually spells.

`mc_api_diff` reports every member a newer jar lost; most of them are lambdas,
enum constants and internals nobody here touches. This tool reads that report,
takes the lost member *names*, and greps the source roots for each as a word -
the short list that comes out is the set of names worth a human (or a compile
run) looking at.

Usage:
  python3 scripts/mc_api_diff_filter.py --diff-report /tmp/diff.txt \
      --sources Neolectrum/src/main/java Neolectrum/src/main/kotlin ...
"""
import argparse
import re
from pathlib import Path

CLASS_RE = re.compile(r"^  ([a-zA-Z][\w.$]+) \((\d+) lost\):$")
MEMBER_RE = re.compile(r"^    - (.+)$")
SKIP_NAMES_RE = re.compile(r"^(lambda\$|access\$)")


def sources_text(sources):
    chunks = []
    for root in sources:
        root = Path(root)
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.suffix in (".java", ".kt"):
                try:
                    chunks.append(path.read_text(encoding="utf-8", errors="replace"))
                except OSError:
                    pass
    return "\n".join(chunks)


def member_names(sig):
    """`foo(net.minecraft.core.SectionPos,int)` -> foo; `FIELD` -> FIELD"""
    if "(" in sig:
        return sig.split("(")[0].split()[-1]
    return sig.split()[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--diff-report", required=True)
    ap.add_argument("--sources", nargs="+", required=True)
    args = ap.parse_args()

    current = None
    per_class = {}
    for line in Path(args.diff_report).read_text().splitlines():
        m = CLASS_RE.match(line)
        if m:
            current = m.group(1)
            per_class.setdefault(current, [])
            continue
        m = MEMBER_RE.match(line)
        if m and current:
            per_class[current].append(m.group(1))

    text = sources_text(args.sources)
    print("== lost member names the sources actually spell ==")
    hits = 0
    for cls, members in sorted(per_class.items()):
        used = []
        seen = set()
        for sig in members:
            name = member_names(sig)
            if name in seen or SKIP_NAMES_RE.match(name) or len(name) < 4:
                continue
            seen.add(name)
            # word-boundary grep for the plain name
            if re.search(rf"\b{re.escape(name)}\b", text):
                used.append(name)
        if used:
            hits += 1
            print(f"\n  {cls}:")
            for name in sorted(set(used)):
                print(f"    ? {name}")
    if not hits:
        print("  (none)")
    print(f"\n{hits} class(es) with source-level name overlaps")


if __name__ == "__main__":
    main()
