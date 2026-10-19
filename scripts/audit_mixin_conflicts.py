#!/usr/bin/env python3
"""Audit this mod's mixin injections against another mod's, from its real jar.

The target audit (`audit_mixin_targets.py`) proves our injection strings
describe members the game actually has. It cannot see the other failure mode:
another mod injecting into the *same instruction*. Two `@Redirect`s on one
call cannot both apply - Mixin picks a winner, and the loser's
`InjectionInfo.postInject` still runs its require check and throws
`InjectionError`, which is a class that fails to *transform*. On Fabric that
was "joining a world crashes the client" again (android.46 fixed the
descriptor drift that hid this; the crash the user then hit was our
`tesselateBlock` redirect in `SectionCompiler.compile` colliding with
fabric-renderer-api-v1's own redirect of the same call - Indigo wraps that
call site by design, so the collision was guaranteed, not a drift).

What this script checks, given the real fabric-api jar (any loader mod jar
with nested module jars and `*.mixins.json` files works):

  - every mixin class of that mod is read from its compiled form (Java 25
    class files, no JDK needed - `bytecode_annots.py`);
  - every instruction-claiming injection (`@Redirect`, `@ModifyArg`,
    `@ModifyArgs`, `@ModifyVariable`, `@ModifyConstant`, and the MixinExtras
    wrappers `@WrapOperation`/`@WrapMethod`/`@ModifyExpressionValue`/
    `@ModifyReturnValue`) is collected as a claim:
    (target class, method spec, @At value, @At target, ordinal);
  - the same is collected from this repo's mixin *sources* (the same
    string-parsing the target audit does);
  - any instruction claimed by both mods where at least one claim is not a
    plain `@Inject` is a conflict - two callbacks can coexist, anything that
    rewrites or re-resolves the instruction cannot (and even inject-vs-rewrite
    is apply-order dependent, so it is flagged too).

Claims are matched on (method name, descriptor, @At value, @At target,
ordinal); a descriptor-less method string matches every overload of the name,
which is the conservative reading.

Usage (from the repo root):
  python3 scripts/audit_mixin_conflicts.py \
      --jar .mc-cache/client-26.1.2.jar \
      --other-jar .mc-cache/fabric-api.jar \
      --other-name fabric-api \
      Neolectrum/src/main/resources/wgpu_mc.mixins.json \
      Neolectrum-fabric/src/main/resources/wgpu_mc.mixins.json \
      [--allow scripts/mixin_conflict_allowlist.txt]

Exit code 1 on any conflict; failing lines carry `::error::` so GitHub
annotations land on the run summary.
"""
import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bytecode_annots import class_annotations
from audit_mixin_targets import (STRING_RE, annotation_bodies, at_target,
                                 method_specs, mixin_target_class, strip_comments)


def imports_of(text: str) -> dict:
    """simple name -> FQCN, exactly the map the target audit builds."""
    return {imp.group(1).rsplit(".", 1)[-1]: imp.group(1)
            for imp in re.finditer(r"import\s+(?:static\s+)?([\w.$]+);", text)}

# Annotations that claim or rewrite the instruction itself. A plain @Inject
# only adds a callback next to it, so two @Injects coexist - that is the one
# shape the table below treats as compatible.
CLAIMING_ANNOTATIONS = [
    "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant",
    "WrapOperation", "WrapMethod", "ModifyExpressionValue", "ModifyReturnValue",
]
KNOWN_ANNOTATIONS = ["Inject"] + CLAIMING_ANNOTATIONS

OUR_PACKAGE = "dev.birb.wgpu.mixin."


def fail(message: str) -> None:
    FAILURES.append(message)


FAILURES: list[str] = []


# --------------------------------------------------------------------------
# normal forms
# --------------------------------------------------------------------------

def method_key(spec: str) -> tuple[str, str | None]:
    """`name(desc)` or `owner...name(desc)` or bare `name` -> (name, desc|None)."""
    text = spec.strip().replace(" ", "")
    if "(" not in text:
        head, desc = text, None
    else:
        head, desc = text[:text.index("(")], text[text.index("("):]
    head = re.split(r"[.;/$]", head)[-1] or head
    return head, desc



def canonical_target(target: str | None) -> str:
    """One spelling for the two ways @At targets are written.

    Mixin accepts `Lowner;name(desc)` (the JVM internal form, the source-side
    habit) and `owner/with/slashes.name(desc)` (what a compiled annotation
    carries). Both canonicalize to `owner.with.dots.name` + the descriptor
    *untouched* - the descriptor's own `L...;` spellings stay internal on both
    sides, so the comparison never has to decide what a dotted descriptor means.
    """
    if not target:
        return ""
    text = target.strip()
    if text.startswith("L") and ";" in text:
        owner, rest = text[1:].split(";", 1)
        return owner.replace("/", ".") + "." + rest
    cut = len(text)
    for ch in "(:":
        i = text.find(ch)
        if i != -1:
            cut = min(cut, i)
    head, trailer = text[:cut], text[cut:]
    owner, _, member = head.rpartition(".")
    if not owner:
        return text.replace("/", ".")
    return owner.replace("/", ".") + "." + member + trailer


def at_key(value: str, target: str | None, ordinal) -> tuple:
    return (value or "", canonical_target(target), ordinal)


def annotation_values_from_source(body: str) -> dict:
    """`@At` body -> {value, target, ordinal}; ordinals default to None."""
    vs = re.search(r'(?:^|[^\w])value\s*=\s*"([^"]+)"', body)
    value = vs.group(1) if vs else None
    if value is None:
        # the positional shorthand: `@At("HEAD")` - the first string is the value
        first = STRING_RE.search(body)
        if first:
            value = first.group(0)[1:-1]
    ordinal = re.search(r"\bordinal\s*=\s*(\d+)", body)
    return {"value": value or "",
            "target": at_target(body),
            "ordinal": int(ordinal.group(1)) if ordinal else None}


def source_specs(body: str) -> list[str]:
    out = []
    for spec in method_specs(body):
        # mixin also accepts a comma-separated list inside one string
        out.extend(part for part in spec.split(",") if part.strip())
    return out


# --------------------------------------------------------------------------
# our side: the sources the two mixins.json files name
# --------------------------------------------------------------------------

def our_claims(json_paths: list[Path]) -> dict:
    """target class -> list of (mixin, kind, method_key, at_key, where)."""
    claims: dict[str, list] = {}
    seen: set[str] = set()
    for json_path in json_paths:
        config = json.loads(json_path.read_text())
        names = list(config.get("mixins", [])) + list(config.get("client", [])) \
            + list(config.get("server", []))
        resources_root = json_path.parent
        src_roots = [resources_root.parent / "java"]
        if resources_root.parent.name == "resources":
            src_roots.append(resources_root.parent.parent.parent
                             / "Neolectrum" / "src" / "main" / "java")
        for name in names:
            fqn = f"{config['package']}.{name}"
            if fqn in seen:
                continue
            seen.add(fqn)
            source_path = next((root / (fqn.replace(".", "/") + ".java")
                                for root in src_roots if (root / (fqn.replace(".", "/") + ".java")).exists()),
                               None)
            if source_path is None:
                continue  # accessors and the like are checked by the target audit
            text = strip_comments(source_path.read_text())
            imports = imports_of(text)
            target = mixin_target_class(text, imports, str(source_path))
            if target is None:
                continue
            # the bytecode side spells mixin targets in internal (slash) form;
            # the source side resolves imports to dotted FQCNs - one form for both
            target = target.replace(".", "/")
            for annotation in KNOWN_ANNOTATIONS:
                for start, body in annotation_bodies(text, annotation):
                    kind = annotation
                    ats = [annotation_values_from_source(nested)
                           for _, nested in annotation_bodies(body, "At")]
                    if not ats:
                        ats = [{"value": "HEAD", "target": None, "ordinal": None}]
                    for spec in source_specs(body):
                        for at in ats:
                            claims.setdefault(target, []).append(
                                (fqn, kind, method_key(spec), at_key(at["value"], at["target"], at["ordinal"]),
                                 f"{source_path.name}:{text[:start].count(chr(10)) + 1}"))
    return claims


# --------------------------------------------------------------------------
# their side: the compiled mixin classes inside a mod jar
# --------------------------------------------------------------------------

def string_values(value) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        out = []
        for item in value:
            out.extend(string_values(item))
        return out
    if isinstance(value, str):
        return [value]
    return []


def bytecode_at(annotation: dict) -> dict:
    values = annotation.get("values", {})
    at = values.get("at")
    if not isinstance(at, dict):
        return {"value": "HEAD", "target": None, "ordinal": None}
    inner = at.get("values", {})
    target = inner.get("target")
    if isinstance(target, list):
        target = "".join(target) if all(isinstance(t, str) for t in target) else None
    ordinal = inner.get("ordinal")
    return {"value": inner.get("value") or "",
            "target": target if isinstance(target, str) else None,
            "ordinal": ordinal if isinstance(ordinal, int) else None}


def bytecode_claims(module_name: str, jar_path: Path, vanilla: set[str]) -> dict:
    """target class -> list of (mixin, kind, method_key, at_key, where).

    Reads the mod jar itself *and* every nested `META-INF/jars/*.jar` module,
    because fabric-api ships its mixins inside the module jars, not the fat one.
    """
    claims: dict[str, list] = {}
    zips: list[tuple[str, zipfile.ZipFile]] = []
    import io
    with zipfile.ZipFile(jar_path) as top:
        nested = [(n, top.read(n)) for n in top.namelist()
                  if n.startswith("META-INF/jars/") and n.endswith(".jar")]
    if nested:
        for entry, data in nested:
            zips.append((entry, zipfile.ZipFile(io.BytesIO(data))))
    else:
        zips.append((jar_path.name, zipfile.ZipFile(jar_path)))
    try:
        for where_prefix, zf in zips:
            _claims_from_one_jar(f"{module_name}:{where_prefix}", zf, vanilla, claims)
    finally:
        for _, zf in zips:
            zf.close()
    return claims


def _claims_from_one_jar(module_name: str, zf: zipfile.ZipFile,
                         vanilla: set[str], claims: dict[str, list]) -> None:
    entries = set(zf.namelist())
    configs = [n for n in entries if n.endswith(".mixins.json")]
    for config_name in configs:
        config = json.loads(zf.read(config_name))
        package = config.get("package", "")
        names = list(config.get("mixins", [])) + list(config.get("client", [])) \
            + list(config.get("server", []))
        for name in names:
            entry = (package.replace(".", "/") + "/" + name.replace(".", "/") + ".class").lstrip("/")
            if entry not in entries:
                continue
            info = class_annotations(zf.read(entry))
            target = None
            for annotation in info["class_annotations"]:
                if not annotation["type"].endswith("/mixin/Mixin;"):
                    continue
                values = annotation.get("values", {})
                for item in string_values(values.get("value")):
                    target = item[1:-1] if item.startswith("L") else item
                # @Mixin(X.class) carries `c` element values, not strings
                raw_value = values.get("value")
                if isinstance(raw_value, list):
                    for item in raw_value:
                        if isinstance(item, dict) and "class" in item:
                            descriptor = item["class"]
                            target = descriptor[1:-1] if descriptor.startswith("L") else descriptor
                for spelled in string_values(values.get("targets")):
                    target = spelled.lstrip("/").replace(".", "/")
            if target is None:
                continue
            if target.replace(".", "/") not in vanilla:
                print(f"   ~ {module_name} {name}: targets {target}, not in the vanilla jar - not cross-checked")
                continue
            for (_, descriptor), annotations in info["methods"].items():
                for annotation in annotations:
                    kind = annotation["type"].split("/")[-1][:-1]
                    if kind not in KNOWN_ANNOTATIONS:
                        continue
                    values = annotation.get("values", {})
                    at = bytecode_at(annotation)
                    specs = []
                    for spelled in string_values(values.get("method")):
                        specs.extend(part for part in spelled.split(",") if part.strip())
                    if not specs:
                        specs = [""]
                    for spec in specs:
                        claims.setdefault(target, []).append(
                            (f"{module_name}:{name}", kind, method_key(spec) if spec else ("<any>", None),
                             at_key(at["value"], at["target"], at["ordinal"]),
                             f"{config_name} {name} method {descriptor}"))


# --------------------------------------------------------------------------
# the table
# --------------------------------------------------------------------------


def compare(ours: dict, theirs: dict, allow: set[str]) -> None:
    for target in sorted(set(ours) & set(theirs)):
        by_name_at: dict[tuple, list] = {}
        for claim in ours[target] + theirs[target]:
            # a descriptor-less method string matches every overload of the
            # name - group on (name, at) and let the descriptor check decide
            by_name_at.setdefault((claim[2][0], claim[3]), []).append(claim)
        for (mname, ak), claims in sorted(by_name_at.items(),
                                          key=lambda kv: (repr(kv[0][0]), repr(kv[0][1]))):
            our_entries = [c for c in claims if c[0].startswith(OUR_PACKAGE)]
            their_entries = [c for c in claims if c not in our_entries]
            if not our_entries or not their_entries:
                continue
            pairs = [(a, b) for a in our_entries for b in their_entries
                     if a[2][1] is None or b[2][1] is None or a[2][1] == b[2][1]]
            if not pairs:
                continue
            kinds = {c[1] for c in claims}
            if all(kind == "Inject" for kind in kinds):
                print(f"   ~ {target.split('/')[-1]} {mname} @ {ak[0]} {ak[1] or '-'}: "
                      f"@Inject only on both sides - callbacks coexist")
                continue
            group = ", ".join(f"{c[0]} {c[1]} ({c[4]})" for c in claims)
            message = (f"both mods claim the same instruction of {target}: {mname} "
                       f"@{ak[0]} {ak[1] or '-'} -> {group}")
            if any(c[0] in allow for c in claims):
                print(f"   ~ allowed: {message}")
                continue
            fail(message)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--other-jar", required=True,
                        help="the other mod's jar (fabric-api and the like)")
    parser.add_argument("--other-name", default="other-mod")
    parser.add_argument("--vanilla-jar", required=True,
                        help="the client jar, for the set of classes a mixin target must name")
    parser.add_argument("--allow", default=None)
    parser.add_argument("configs", nargs="+", help="our mixins.json files")
    args = parser.parse_args()

    with zipfile.ZipFile(args.vanilla_jar) as zf:
        vanilla = {n[:-6] for n in zf.namelist() if n.endswith(".class")}

    allow = set()
    if args.allow:
        for line in Path(args.allow).read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                allow.add(line)

    ours = our_claims([Path(p) for p in args.configs])
    theirs = bytecode_claims(args.other_name, Path(args.other_jar), vanilla)

    print(f"   ~ {sum(len(v) for v in ours.values())} claim(s) from this repo, "
          f"{sum(len(v) for v in theirs.values())} from {args.other_name}, "
          f"across {len(set(ours) & set(theirs))} shared target class(es)")
    compare(ours, theirs, allow)

    if FAILURES:
        for message in dict.fromkeys(FAILURES):
            print(f"::error::{message}")
        return 1
    print("mixin conflict audit: no instruction is claimed by both mods")
    return 0


if __name__ == "__main__":
    sys.exit(main())
