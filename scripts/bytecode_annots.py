#!/usr/bin/env python3
"""Read runtime annotations out of .class files - no JDK needed.

The mixin-conflict audit has to read *other mods'* mixin classes, which ship
compiled - and Minecraft 26.1's ecosystem compiles to Java 25 class files
(major 69), which the local JDK 21 `javap` refuses to load. This module parses
the class-file format directly, the way `classdump.py` does for member tables,
and adds what that one skips: the annotation attributes, on the class and on
each method, with nested annotations and arrays decoded to plain Python.

Only what mixin annotations actually use is implemented: string/class/enum
element values and one level of nesting (an `@At(...)` inside a
`@Redirect(...)`), plus arrays of any of those. That is JVMS 4.7.16's
`element_value` grammar in full.

Usage (as a module):
    from bytecode_annots import class_annotations
    info = class_annotations(data_bytes)
    info["name"]                # internal name, `net/.../SectionCompilerMixin`
    info["class_annotations"]   # list of annotation dicts
    info["methods"]             # {(name, descriptor): [annotation dicts]}

An annotation dict is `{"type": "Lorg/spongepowered/.../Redirect;",
"values": {"method": [...], "at": {"type": "L.../At;", "values": {...}}}}` -
`type` keeps the leading `L...;` spelling the constant pool carries.
"""
import struct

CONSTANT_Utf8 = 1
CONSTANT_Integer, CONSTANT_Float = 3, 4
CONSTANT_Long, CONSTANT_Double = 5, 6
CONSTANT_Class, CONSTANT_String = 7, 8
CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref = 9, 10, 11
CONSTANT_NameAndType = 12
CONSTANT_MethodHandle = 15
CONSTANT_MethodType = 16
CONSTANT_Dynamic = 17
CONSTANT_InvokeDynamic = 18
CONSTANT_Module, CONSTANT_Package = 19, 20

RUNTIME_KINDS = ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations")


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


def _read_constant_pool(r: Reader) -> list:
    count = r.u2()
    pool = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == CONSTANT_Utf8:
            pool[i] = ("utf8", r.bytes(r.u2()).decode("utf-8", "replace"))
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            pool[i] = ("num", r.bytes(4))
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            pool[i] = ("num", r.bytes(8))
            i += 1
        elif tag in (CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType,
                     CONSTANT_Module, CONSTANT_Package):
            pool[i] = ("ref1", tag, r.u2())
        elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
                     CONSTANT_NameAndType, CONSTANT_InvokeDynamic, CONSTANT_Dynamic):
            pool[i] = ("ref2", tag, r.u2(), r.u2())
        elif tag == CONSTANT_MethodHandle:
            pool[i] = ("handle", r.u1(), r.u2())
        else:
            raise ValueError(f"unknown constant tag {tag} at slot {i}")
        i += 1
    return pool


def _read_annotation(r: Reader, pool: list) -> dict:
    type_index = r.u2()
    out = {"type": pool[type_index][1], "values": {}}
    values = out["values"]
    for _ in range(r.u2()):
        name = pool[r.u2()][1]
        values[name] = _read_element_value(r, pool)
    return out


def _read_element_value(r: Reader, pool: list):
    tag = chr(r.u1())
    if tag in "BCDFSIZ":
        const = pool[r.u2()]
        return const[1] if const[0] == "utf8" else const[1]
    if tag == "s":
        return pool[r.u2()][1]
    if tag == "e":
        type_name = pool[r.u2()][1]
        const_name = pool[r.u2()][1]
        return {"enum_type": type_name, "enum_value": const_name}
    if tag == "c":
        return {"class": pool[r.u2()][1]}
    if tag == "@":
        return _read_annotation(r, pool)
    if tag == "[":
        return [_read_element_value(r, pool) for _ in range(r.u2())]
    raise ValueError(f"unknown element_value tag {tag!r}")


def _read_annotation_attribute(r: Reader, pool: list) -> list:
    return [_read_annotation(r, pool) for _ in range(r.u2())]


def class_annotations(data: bytes) -> dict:
    """Parse one class file; annotation dicts as documented in the module doc."""
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError("not a class file")
    r.u2()  # minor
    r.u2()  # major
    pool = _read_constant_pool(r)

    def utf8(idx: int) -> str:
        return pool[idx][1]

    r.u2()  # access
    this_class = utf8(pool[r.u2()][2])
    super_index = r.u2()
    super_class = utf8(pool[super_index][2]) if super_index else None
    for _ in range(r.u2()):
        r.u2()  # interfaces

    def attributes() -> list[tuple[str, Reader, int]]:
        out = []
        for _ in range(r.u2()):
            name = utf8(r.u2())
            length = r.u4()
            out.append((name, Reader(r.bytes(length)), length))
        return out

    class_annots: list[dict] = []

    methods: dict[tuple[str, str], list[dict]] = {}
    for _ in range(r.u2()):  # fields - their annotations do not carry targets
        r.u2(), utf8(r.u2()), utf8(r.u2())
        attributes()
    for _ in range(r.u2()):
        r.u2()  # access
        mname = utf8(r.u2())
        mdesc = utf8(r.u2())
        found: list[dict] = []
        for name, body, _ in attributes():
            if name in RUNTIME_KINDS:
                found.extend(_read_annotation_attribute(body, pool))
        methods[(mname, mdesc)] = found
    # class attributes come after the method table in the class-file layout
    for name, body, _ in attributes():
        if name in RUNTIME_KINDS:
            class_annots.extend(_read_annotation_attribute(body, pool))
    return {"name": this_class, "super": super_class,
            "class_annotations": class_annots, "methods": methods}
