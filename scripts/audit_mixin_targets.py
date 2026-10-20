#!/usr/bin/env python3
"""Audit every mixin target string in this repo against a real Minecraft jar.

The mixin annotations carry their targets as plain strings, and nothing at
compile time checks them - a signature that does not exist at runtime is a
class that fails to *transform*, which on Fabric means "joining a world
crashes the client" (android.45: `SectionCompilerMixin` pinned NeoForge's
five-argument `compile(...List)`, which vanilla does not have). This script
is the gate that catches that class of drift before a release, on CI.

What it checks, per mixin class listed in the given `*.mixins.json` files:

  - the `@Mixin` target class exists in the jar (top-level or `$`-inner);
  - every `method = "..."` / `method = {..}` entry of `@Inject` / `@Redirect`
    resolves to at least one real method (name + optional exact descriptor);
  - every `@At(target = "Lowner;name(desc)ret")` / `...;name:Ltype;` resolves
    to a real method / field on the named owner;
  - every `@Accessor("field")` names a real field, and every
    `@Invoker("method")` names a real method;
  - **handler arity**: a `@Inject` callback must capture either *none* of the
    target's arguments (a callback-info-only handler - the only shape that
    can serve two loaders whose target signatures differ, as ours do) or
    *all* of them - Mixin supports no prefix capture (CallbackInjector's
    checkDescriptor: exact match, or the "simple" descriptor, nothing between);
    a `@Redirect` handler must take exactly the call's receiver (for instance
    targets) plus arguments.

What it cannot check here: the NeoForge *patched* surface. The vanilla jar is
the ground truth for everything both loaders share, which is every mixin in
the Fabric config; the NeoForge-only exceptions live in `--allow` with a
written reason and are validated by the NeoForge device instead.

Known blind spot: reflection targets. `ContainerData` reaches the same
members through `getDeclaredField`/`findVirtual` name strings
(`RenderSectionRegion.sections`, `PalettedContainer.data`,
`PalettedContainer$Data.palette()/storage()`), which this script does not
parse - those four were verified against the 26.1.2 jar by hand when the
audit was introduced. A future rename of any of them would need either this
script taught to read reflection constants or that class moved onto mixin
accessors, which the audit does see.

Usage (from the repo root):
  python3 scripts/audit_mixin_targets.py --jar /path/client.jar \
      Neolectrum/src/main/resources/wgpu_mc.mixins.json \
      Neolectrum-fabric/src/main/resources/wgpu_mc.mixins.json \
      [--allow scripts/mixin_audit_allowlist.txt]

Exit code 1 if any target fails; failing lines carry a `::error::` prefix so
GitHub annotations land on the run summary.
"""
import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from classdump import members_from  # the same jar reader the dumps use

FAILURES: list[str] = []


def fail(message: str) -> None:
    # appended only - printed once, deduplicated, at the end, so callers can
    # retract a failure that `require = 0` turns out to allow
    FAILURES.append(message)


# --------------------------------------------------------------------------
# Java source reading
# --------------------------------------------------------------------------

def strip_comments(text: str) -> str:
    """Blank out comments; keep string literals intact (they carry targets)."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


STRING_RE = re.compile(r'"(?:\\.|[^"\\\n])*"')


def annotation_bodies(source: str, annotation: str) -> list[tuple[int, str]]:
    """`(start_of_body, body)` of every `@Annotation(...)`, paren-balanced."""
    bodies = []
    for m in re.finditer(r"@" + annotation + r"\s*\(", source):
        depth = 0
        start = m.end() - 1
        i = start
        while i < len(source):
            if source[i] == "(":
                depth += 1
            elif source[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        bodies.append((start + 1, source[start + 1:i]))
    return bodies


def joined_strings(body: str) -> list[str]:
    """String literals in the body; adjacent `+`-joined ones become one."""
    literals = [(m.group(0)[1:-1], m.end()) for m in STRING_RE.finditer(body)]
    joined: list[str] = []
    buf: str | None = None
    for lit, end in literals:
        cont = re.match(r"\s*\+", body[end:])
        buf = lit if buf is None else buf + lit
        if not cont:
            joined.append(buf)
            buf = None
    if buf is not None:
        joined.append(buf)
    return joined


def method_specs(body: str) -> list[str]:
    """The `method = ...` entries of an annotation body.

    The value is either `{...}` or one string expression; the single-value form
    ends at the next top-level comma (an `@At(...)` follows on the same body in
    `@Inject(method = "x", at = @At("HEAD"))`, and the `"HEAD"` literal is not
    a method spec).
    """
    m = re.search(r"method\s*=\s*", body)
    if not m:
        return []
    rest = body[m.end():]
    if rest.lstrip().startswith("{"):
        inner_end = rest.index("}")
        return joined_strings(rest[:inner_end])
    end, in_string = 0, False
    while end < len(rest):
        c = rest[end]
        if c == '"':
            in_string = not in_string
        elif c == "," and not in_string:
            break
        end += 1
    return joined_strings(rest[:end])


def at_target(body: str) -> str | None:
    """The `target = ...` string of an `@At(...)` body, `+`-chains joined."""
    m = re.search(r"target\s*=\s*", body)
    if not m:
        return None
    rest = body[m.end():]
    end, in_string = 0, False
    while end < len(rest):
        c = rest[end]
        if c == '"':
            in_string = not in_string
        elif c in ",)" and not in_string:
            break
        end += 1
    strings = joined_strings(rest[:end])
    return strings[0] if strings else None


# --------------------------------------------------------------------------
# Jar member index
# --------------------------------------------------------------------------

class JarIndex:
    def __init__(self, jar: str):
        self.jar = jar
        self._cache: dict[str, dict | None] = {}
        with zipfile.ZipFile(jar) as zf:
            self.entries = set(zf.namelist())

    def has_class(self, fqn: str) -> bool:
        return fqn.replace(".", "/") + ".class" in self.entries

    def _members(self, fqn: str) -> dict:
        if fqn not in self._cache:
            found = members_from(self.jar, [fqn])
            self._cache[fqn] = found[0][1] or {}
        return self._cache[fqn]

    def methods(self, fqn: str) -> list[tuple[int, str, str]]:
        return self._members(fqn).get("methods", [])

    def fields(self, fqn: str) -> list[tuple[int, str, str]]:
        return self._members(fqn).get("fields", [])

    def super_of(self, fqn: str) -> str | None:
        return self._members(fqn).get("super")

    def method_on_type_or_super(self, fqn: str, name: str) -> list[tuple[int, str, str]]:
        """Methods named `name` on `fqn` or a superclass the jar knows.

        Mixin resolves an @At target against the hierarchy - a redirect on
        `BlockState.getRenderShape` targets a method `BlockBehaviour` declares -
        so the audit walks up until it finds the member or runs out of jar.
        """
        current: str | None = fqn
        for _ in range(10):
            if current is None or not self.has_class(current):
                return []
            found = [(flags, d, n) for flags, d, n in self.methods(current) if n == name]
            if found:
                return found
            current = self.super_of(current)
        return []


def is_static(flags: int) -> bool:
    return bool(flags & 0x0008)


def desc_arg_count(descriptor: str) -> int:
    """Top-level parameter count of a JVM method descriptor."""
    assert descriptor.startswith("("), f"not a descriptor: {descriptor!r}"
    count = 0
    i = 1
    while i < len(descriptor) and descriptor[i] != ")":
        c = descriptor[i]
        if c in "BCFISZJD":  # J and D are one argument each (wide, but single slot pair)
            count += 1
            i += 1
        elif c == "L":
            i = descriptor.index(";", i) + 1
            count += 1
        elif c == "[":
            while descriptor[i] == "[":
                i += 1
            if descriptor[i] == "L":
                i = descriptor.index(";", i) + 1
            else:
                i += 1
            count += 1
        else:
            raise ValueError(f"bad descriptor {descriptor!r} at {i}")
    return count


# --------------------------------------------------------------------------
# One mixin class
# --------------------------------------------------------------------------

OWNER: list[str] = [""]


def mixin_target_class(source: str, imports: dict[str, str], path: str) -> str | None:
    for _, body in annotation_bodies(source, "Mixin"):
        ts = re.search(r'targets\s*=\s*"([^"]+)"', body)
        if ts:
            return ts.group(1)
        flat = body.replace(" ", "")
        cv = re.search(r"([A-Za-z_$][\w.$]*?)\.class\b", flat)
        if not cv:
            continue
        simple = cv.group(1)
        parts = simple.split(".")
        head = parts[0]
        if simple in imports:
            return imports[simple]
        # a nested spelling like Foo.Bar.class resolves through Foo's import;
        # the JVM binary name of the nesting uses `$`, so try the dotted name
        # first and the `$`-joined one after it
        if head in imports:
            # the JVM binary name of a nested class joins its nest with `$`,
            # and only the nesting - the package separators stay dots
            return imports[head] + "".join("$" + p for p in parts[1:])
        fail(f"{path}: @Mixin target '{simple}' has no import to resolve it")
    return None


def check_method_string(jar: JarIndex, spec: str, path: str, mixin: str,
                        where: str) -> int | None:
    """One `method =` entry; returns the first matching overload's arg count."""
    m = re.match(r"^([A-Za-z_$<][\w$>]*|<init>)\s*(\((.*)\)([\w/[$;]*))?$", spec.strip())
    if not m:
        fail(f"{path}: {mixin} {where}: cannot parse method spec '{spec}'")
        return None
    name, desc = m.group(1), m.group(3)
    owner = OWNER[0]
    named = jar.method_on_type_or_super(owner, name)
    if not named:
        fail(f"{path}: {mixin} {where}: method '{name}' does not exist on {owner} "
             f"nor its superclasses ({len(jar.methods(owner))} declared methods in the jar)")
        return None
    if desc is None:
        return desc_arg_count(named[0][1])
    # the whole descriptor, parameters AND return type - a JVM descriptor is
    # (params)return and that is the string the jar carries
    full = f"({desc}){m.group(4) or ''}"
    for flags, d, _ in named:
        if d == full:
            return desc_arg_count(d)
    fail(f"{path}: {mixin} {where}: '{spec.strip()}' matches no overload of '{name}' on {owner} "
         f"(or its superclasses); the jar has: {[d for _, d, _ in named]}")
    return None


def check_at_target(jar: JarIndex, target: str, path: str, mixin: str, where: str) -> None:
    m = re.match(r"^L([^;]+);([A-Za-z_$][\w$<>]*)\s*(\(.*?\)[\w/[$;]*|:.*)?$", target)
    if not m:
        fail(f"{path}: {mixin} {where}: cannot parse @At target '{target}'")
        return
    owner, name, rest = m.group(1), m.group(2), m.group(3) or ""
    if not jar.has_class(owner):
        # a class the client jar does not carry at all is an external library
        # (guava, ...), which this audit has no ground truth for - only fail
        # when the jar clearly knows the package and still lacks the class
        package_prefix = owner.rsplit("$", 1)[0].rsplit(".", 1)[0].replace(".", "/")
        if any(e.startswith(package_prefix + "/") for e in jar.entries):
            fail(f"{path}: {mixin} {where}: @At target class {owner} is not in the jar")
        return
    if rest.startswith("("):
        if not any(n == name and d == rest
                   for _, d, n in jar.method_on_type_or_super(owner, name)):
            overloads = [d for _, d, n in jar.method_on_type_or_super(owner, name)]
            fail(f"{path}: {mixin} {where}: @At target '{target}' matches no overload on {owner} "
                 f"(or its superclasses); the jar has: {overloads}")
        return
    if rest.startswith(":"):
        fields = [(d) for _, d, n in jar.fields(owner) if n == name]
        if not fields:
            fail(f"{path}: {mixin} {where}: @At field target '{name}' does not exist on {owner}")
        elif all(d != rest[1:] for d in fields):
            fail(f"{path}: {mixin} {where}: @At field target '{name}' exists on {owner} as "
                 f"{fields}, not {rest[1:]}")
        return
    if any(n == name for _, _, n in jar.methods(owner)) or \
            any(n == name for _, _, n in jar.fields(owner)):
        return
    fail(f"{path}: {mixin} {where}: @At target member '{name}' does not exist on {owner}")


def handler_params(text: str, anno_end: int) -> list[str] | None:
    """Parameter texts of the method declaration following an annotation."""
    m = re.search(r"[\w$]\s*\(", text[anno_end:])
    if m is None:
        return None
    open_paren = anno_end + m.end() - 1
    depth, i = 0, open_paren
    while i < len(text):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    params = text[open_paren + 1:i]
    parts, buf = [], ""
    depth = generic = 0
    for ch in params:
        if ch == "<":
            generic += 1
        elif ch == ">":
            generic -= 1
        if ch == "," and depth == 0 and generic == 0:
            parts.append(buf)
            buf = ""
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        buf += ch
    if buf.strip():
        parts.append(buf)
    return [p for p in (p.strip() for p in parts) if p]


def requires_zero(body: str) -> bool:
    """`require = 0` on an annotation: unmatched targets are by design."""
    return re.search(r"\brequire\s*=\s*0\b", body) is not None


def check_inject(jar: JarIndex, body: str, after: str, path: str, mixin: str) -> None:
    for nested in annotation_bodies(body, "At"):
        ts = at_target(nested[1])
        if ts:
            check_at_target(jar, ts, path, mixin, "@Inject")
    specs = method_specs(body)
    if not specs:
        return
    soft = requires_zero(body)
    before = len(FAILURES)
    counts: list[int] = []
    absent: list[str] = []
    for spec in specs:
        c = check_method_string(jar, spec, path, mixin, "@Inject")
        if c is not None:
            counts.append(c)
        else:
            absent.append(spec)
    if counts and absent:
        # a multi-entry method list that partially resolves is the two-loader
        # pattern working as designed: Mixin fires on whichever target exists on
        # the runtime jar, and require >= 1 asks for one - not for all
        del FAILURES[before:]
        for spec in absent:
            print(f"   ~ {mixin}: @Inject target '{spec[:96]}' is absent here (another listed "
                  f"target resolves - the two-loader pattern)")
    elif not counts and soft:
        del FAILURES[before:]
        for spec in absent:
            print(f"   ~ {mixin}: @Inject target '{spec[:96]}' is absent (allowed by require = 0)")
    if not counts:
        return
    params = handler_params(after, 0)
    if params is None:
        fail(f"{path}: {mixin}: @Inject has no handler method after it")
        return
    captured = len(params) - (1 if any("CallbackInfo" in p for p in params) else 0)
    if captured == 0:
        return  # callback-info-only: the one shape that serves any target signature
    if len(set(counts)) == 1 and captured == counts[0]:
        return
    fail(f"{path}: {mixin}: @Inject handler captures {captured} target arg(s) but its target(s) "
         f"have arg count(s) {sorted(set(counts))} - Mixin takes all of the target's arguments or "
         f"none (CallbackInjector.checkDescriptor has no prefix capture); a handler that serves two "
         f"loaders' differing signatures must capture none and read what it needs another way")


def check_redirect(jar: JarIndex, body: str, after: str, path: str, mixin: str) -> None:
    at = None
    for nested in annotation_bodies(body, "At"):
        ts = at_target(nested[1])
        if ts:
            at = ts
            check_at_target(jar, ts, path, mixin, "@Redirect")
    specs = method_specs(body)
    if specs:
        before = len(FAILURES)
        resolved = False
        absent: list[str] = []
        for spec in specs:
            if check_method_string(jar, spec, path, mixin, "@Redirect") is not None:
                resolved = True
            else:
                absent.append(spec)
        if resolved and absent:
            del FAILURES[before:]
            for spec in absent:
                print(f"   ~ {mixin}: @Redirect target '{spec[:96]}' is absent here (another "
                      f"listed target resolves - the two-loader pattern)")
    m = re.match(r"^L([^;]+);([\w$<>]+)\s*(\(.*?\))\S*$", at) if at else None
    if not m:
        return  # field-target redirects have no receiver contract to check
    owner, name, desc = m.group(1), m.group(2), m.group(3)
    found = [flags for flags, d, n in jar.methods(owner) if n == name and d == desc]
    if not found:
        return  # already reported by check_at_target
    params = handler_params(after, 0)
    if params is None:
        fail(f"{path}: {mixin}: @Redirect has no handler method after it")
        return
    expected = desc_arg_count(desc) + (0 if is_static(found[0]) or name == "<init>" else 1)
    if len(params) != expected:
        kind = "static" if is_static(found[0]) else "instance (receiver+args)"
        fail(f"{path}: {mixin}: @Redirect handler takes {len(params)} arg(s) but the call "
             f"'{name}' has {expected} ({kind})")


def check_accessor(jar: JarIndex, body: str, after: str, path: str, mixin: str) -> None:
    names = joined_strings(body)
    for name in names:
        if not any(n == name for _, _, n in jar.fields(OWNER[0])):
            fail(f"{path}: {mixin}: @Accessor('{name}') - no field '{name}' on {OWNER[0]} "
                 f"(jar has: {[n for _, _, n in jar.fields(OWNER[0])]})")


def check_invoker(jar: JarIndex, body: str, after: str, path: str, mixin: str) -> None:
    for name in joined_strings(body):
        members = [d for _, d, n in jar.methods(OWNER[0]) if n == name]
        if not members:
            fail(f"{path}: {mixin}: @Invoker('{name}') - no method '{name}' on {OWNER[0]}")
            continue
        params = handler_params(after, 0)
        if params is None:
            continue
        if all(desc_arg_count(d) != len(params) for d in members):
            fail(f"{path}: {mixin}: @Invoker('{name}') handler takes {len(params)} arg(s); "
                 f"overloads on {OWNER[0]} take {[desc_arg_count(d) for d in members]}")


CHECKERS = (("Inject", check_inject), ("Redirect", check_redirect),
            ("Accessor", check_accessor), ("Invoker", check_invoker))


def audit_config(config: Path, jar: JarIndex, source_roots: list[Path], allowed: set[str]) -> None:
    data = json.loads(config.read_text())
    package = data["package"]
    mixins = list(data.get("client", [])) + list(data.get("server", []))
    print(f"== {config}: {len(mixins)} mixin(s), package {package}")
    for entry in mixins:
        fqn = f"{package}.{entry}"
        # The first root that carries the class answers - the version overlay is named before the
        # shared tree, so a mixin both trees hold is audited from its version-specific source.
        paths = [root / (fqn.replace(".", "/") + ".java") for root in source_roots]
        path = next((p for p in paths if p.exists()), paths[-1])
        if not path.exists():
            fail(f"{config}: mixin class {fqn} has no source at {paths[0].parent}*/{path.name}")
            continue
        if fqn in allowed:
            print(f"   ~ {entry}: allowed (documented loader-specific surface)")
            continue
        raw = path.read_text()
        imports = {imp.group(1).rsplit(".", 1)[-1]: imp.group(1)
                   for imp in re.finditer(r"import\s+(?:static\s+)?([\w.$]+);", raw)}
        source = strip_comments(raw)
        target_fqn = mixin_target_class(source, imports, str(path))
        if target_fqn is None:
            fail(f"{config}: {entry}: no @Mixin target found")
            continue
        if not jar.has_class(target_fqn):
            package_prefix = target_fqn.rsplit(".", 1)[0].replace(".", "/")
            if any(e.startswith(package_prefix + "/") for e in jar.entries):
                fail(f"{config}: {entry}: @Mixin target {target_fqn} is not in the jar")
            else:
                print(f"   ~ {entry}: @Mixin target {target_fqn} is external to the jar")
            continue
        OWNER[0] = target_fqn
        for anno, checker in CHECKERS:
            for start, body in annotation_bodies(source, anno):
                checker(jar, body, source[start + len(body): start + len(body) + 2000],
                        str(path), entry)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--source-root", action="append", default=None,
                        help="a Java source root; repeat for version overlays (first hit wins)")
    parser.add_argument("--allow", default=None)
    parser.add_argument("configs", nargs="+")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent.parent
    source_roots = [Path(root) for root in args.source_root] if args.source_root \
        else [here / "Neolectrum/src/main/java"]
    allowed = set()
    if args.allow and Path(args.allow).exists():
        for line in Path(args.allow).read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                allowed.add(line)
    jar = JarIndex(args.jar)
    print(f"jar: {args.jar} ({len(jar.entries)} entries)")
    for config in args.configs:
        audit_config(Path(config), jar, source_roots, allowed)
    for message in dict.fromkeys(FAILURES):
        print(f"::error::mixin audit: {message}")
    if FAILURES:
        print(f"\nmixin audit: {len(dict.fromkeys(FAILURES))} failure(s)")
        return 1
    print("\nmixin audit: every target resolves against the jar")
    return 0


if __name__ == "__main__":
    sys.exit(main())
