#!/usr/bin/env python3
"""Dump methods and fields of classes inside a Java archive, no JDK needed.

Parses the JVM class-file format directly (constant pool + member tables),
so it reads class files newer than the local JDK can load (e.g. Java 25
class version 69 that Minecraft 26.1 ships, against a JRE 21 host).

Usage:
  classdump.py <jar-or-dir> <class.Name> [class.Name ...]
  classdump.py <jar-or-dir> --list <substring>

Prints, per class: the runtime-visible methods and fields as
  <flags> <descriptor> <name>
with descriptors in JVM internal form (e.g. `(Lnet/minecraft/core/SectionPos;...)V`),
which is exactly the spelling mixin `method =`/`target =` strings use, so the
output can be diffed against mixin annotation strings by eye or by script.

Exit code 1 if any requested class is missing from the archive.
"""
import struct
import sys
import zipfile
from pathlib import Path

CONSTANT_Utf8, CONSTANT_Integer, CONSTANT_Float, CONSTANT_Long, CONSTANT_Double = 1, 3, 4, 5, 6
CONSTANT_Class, CONSTANT_String, CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref = 7, 8, 9, 10, 11
CONSTANT_NameAndType, CONSTANT_MethodHandle, CONSTANT_MethodType, CONSTANT_Dynamic = 12, 15, 16, 17
CONSTANT_InvokeDynamic, CONSTANT_Module, CONSTANT_Package = 18, 19, 20

ACCESS = [(0x0001, "public"), (0x0002, "private"), (0x0004, "protected"), (0x0008, "static"),
          (0x0010, "final"), (0x0040, "volatile"), (0x0080, "transient"), (0x0100, "native"),
          (0x0400, "abstract"), (0x0800, "strict")]


def flags_text(bits: int) -> str:
    return " ".join(word for bit, word in ACCESS if bits & bit) or "-"


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u1(self) -> int:
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self) -> int:
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u4(self) -> int:
        v = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def bytes(self, n: int) -> bytes:
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v


def read_class(data: bytes) -> dict:
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError("not a class file")
    r.u2()  # minor
    r.u2()  # major
    count = r.u2()
    pool = [None] * count  # 1-based; long/double leave a phantom slot
    i = 1
    while i < count:
        tag = r.u1()
        if tag == CONSTANT_Utf8:
            pool[i] = ("utf8", r.bytes(r.u2()).decode("utf-8", "replace"))
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            pool[i] = ("num", r.bytes(4))
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            pool[i] = ("num", r.bytes(8))
            i += 1  # two slots
        elif tag in (CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package):
            pool[i] = ("ref1", tag, r.u2())
        elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref, CONSTANT_NameAndType, CONSTANT_InvokeDynamic):
            pool[i] = ("ref2", tag, r.u2(), r.u2())
        elif tag == CONSTANT_MethodHandle:
            pool[i] = ("handle", r.u1(), r.u2())
        elif tag == CONSTANT_Dynamic:
            pool[i] = ("ref2", tag, r.u2(), r.u2())
        else:
            raise ValueError(f"unknown constant tag {tag} at slot {i}")
        i += 1

    def utf8(idx: int) -> str:
        return pool[idx][1]

    def class_name(idx: int) -> str:
        return utf8(pool[idx][2])

    r.u2()  # access
    this_class = class_name(r.u2())
    super_index = r.u2()
    super_class = class_name(super_index).replace("/", ".") if super_index else None
    for _ in range(r.u2()):
        r.u2()  # interfaces
    members = {"fields": [], "methods": []}

    def skip_attributes():
        for _ in range(r.u2()):
            r.u2()  # attribute name index
            r.bytes(r.u4())

    def read_members(kind: str):
        for _ in range(r.u2()):
            access = r.u2()
            name = utf8(r.u2())
            descriptor = utf8(r.u2())
            skip_attributes()
            members[kind].append((access, descriptor, name))

    read_members("fields")
    read_members("methods")
    skip_attributes()  # class attributes
    return {"name": this_class, "super": super_class,
            "methods": members["methods"], "fields": members["fields"]}


def members_from(source: str, wanted: list[str]) -> list[dict]:
    out = []
    if Path(source).is_dir():
        for name in wanted:
            p = Path(source) / (name.replace(".", "/") + ".class")
            out.append((name, read_class(p.read_bytes())))
        return out
    with zipfile.ZipFile(source) as zf:
        names = set(zf.namelist())
        for name in wanted:
            entry = name.replace(".", "/") + ".class"
            if entry not in names:
                out.append((name, None))
                continue
            out.append((name, read_class(zf.read(entry))))
    return out


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    source = sys.argv[1]
    missing = 0
    if sys.argv[2] == "--list":
        needle = sys.argv[3]
        if Path(source).is_dir():
            hits = sorted(p.stem for p in Path(source).rglob("*.class") if needle in p.stem)
        else:
            with zipfile.ZipFile(source) as zf:
                hits = sorted(n[:-6] for n in zf.namelist()
                              if n.endswith(".class") and needle in n)
        for h in hits:
            print(h.replace("/", "."))
        return 0
    for name, parsed in members_from(source, sys.argv[2:]):
        if parsed is None:
            print(f"!! {name}: NOT FOUND in {source}")
            missing = 1
            continue
        print(f"== class {parsed['name'].replace('/', '.')}  (file request: {name})")
        for access, descriptor, mname in parsed["methods"]:
            print(f"  method  {flags_text(access)} {descriptor} {mname}")
        for access, descriptor, fname in parsed["fields"]:
            print(f"  field   {flags_text(access)} {descriptor} {fname}")
    return missing


if __name__ == "__main__":
    sys.exit(main())
