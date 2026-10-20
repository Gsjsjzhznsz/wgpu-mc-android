#!/usr/bin/env python3
"""Convert loom's three-column tiny mapping into the one the mixin AP can answer, completed for
the classes Mojang's own mapping does not cover.

Two things are wrong with the file loom 1.10 hands the mixin annotation processor, and both
cost a day to find:

1. The file is *incomplete*: Mojang's official obfuscation map only lists the classes the
   vanilla jar obfuscates, and in 1.20.1 that is not all of them - RenderSystem, GlStateManager
   and GLX keep their names at runtime, so they have no row, and every mixin whose target is
   one of them fails with "Unable to locate obfuscation mapping" no matter how the columns are
   spelled. A class the vanilla jar does not rename is also a class whose runtime name is its
   Mojang name, so the answer for those is the identity - for the class and for every member on
   it - which is where the named jar comes in: the build script hands this script the merged
   named jar, and every uncovered `com.mojang` class is read out of the jar and written as
   identity rows, members included.
2. The namespaces must be spelled so the processor finds both of its answers: `getNamespaceId`
   only searches *destination* namespaces, so `named` and `intermediary` are both destinations
   here, with a dummy `official` source column in front.

The output is a tiny **v2** file in the hierarchical layout v2 requires - classes at column
zero, members indented one tab deeper - because the v1 reader mapping-io ships answers zero
classes for every v1 file this side has fed it, while the v2 reader takes the same tree whole:

    tiny 2 0  official  named  intermediary
    c   <official>  <named>  <intermediary>
    	f   <desc in the source namespace>  <official>  <named>  <intermediary>

Mapped rows carry the source file's own descriptor (official spelling) verbatim - v2 has one
descriptor column per row, written in the source namespace, and the reader rewrites it per
namespace through the class table itself. Identity rows carry the named jar's descriptor, which
for an unobfuscated class is already the runtime spelling.

usage: tiny_named_intermediary.py <in.tiny> <out.tiny> [<named-minecraft.jar>]
"""
import struct
import sys
import zipfile


def parse_class_members(data):
    """The declared fields and methods of one class file, as (kind, name, descriptor)."""
    def u2(off):
        return struct.unpack_from(">H", data, off)[0], off + 2

    def u4(off):
        return struct.unpack_from(">I", data, off)[0], off + 4

    off = 8  # magic + minor + major
    cp_count, off = u2(off)
    utf8 = {}
    i = 1
    while i < cp_count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            length, off = u2(off)
            utf8[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (3, 4):  # Integer, Float
            off += 4
        elif tag in (5, 6):  # Long, Double - two constant pool slots
            off += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            off += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # refs, NameAndType, dynamics
            off += 4
        elif tag == 15:  # MethodHandle
            off += 3
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1

    off += 2  # access_flags
    off += 2  # this_class
    off += 2  # super_class
    ifaces, off = u2(off)
    off += 2 * ifaces

    members = []
    for kind in ("f", "m"):
        count, off = u2(off)
        for _ in range(count):
            _, off = u2(off)  # access_flags
            name_idx, off = u2(off)
            desc_idx, off = u2(off)
            attr_count, off = u2(off)
            for _ in range(attr_count):
                _, off = u2(off)  # attribute_name_index
                attr_len, off = u4(off)
                off += attr_len
            members.append((kind, utf8.get(name_idx, ""), utf8.get(desc_idx, "")))
    return members


def main(src, dst, named_jar=None):
    ns = []
    tree = []  # [ [official, inter, named], [(kind, official, inter, named, desc), ...] ] in order
    current = None

    with open(src, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        if header[0] != "tiny" or header[1] != "2":
            raise SystemExit(f"expected a tiny v2 file, got: {' '.join(header[:3])}")
        ns = header[3:]
        try:
            i_official = ns.index("official")
            i_inter = ns.index("intermediary")
            i_named = ns.index("named")
        except ValueError as e:
            raise SystemExit(f"mapping namespaces {ns} lack official/intermediary/named: {e}")

        def pad(names):
            return (names + [""] * len(ns))[: len(ns)]

        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            kind = None
            for i, part in enumerate(parts):
                if part != "":
                    kind = part
                    parts = parts[i:]
                    break
            if kind == "c":
                names = pad(parts[1:])
                current = [[names[i_official], names[i_inter], names[i_named]], []]
                tree.append(current)
            elif kind in ("f", "m") and current is not None:
                desc = parts[1]
                names = pad(parts[2:])
                current[1].append((kind, names[i_official], names[i_inter], names[i_named], desc))
            # parameter/argument rows are skipped on purpose

    covered = {row[0][i_named] for row in tree} | {row[0][i_official] for row in tree if row[0][i_official]}

    n_fields = n_methods = n_identity = 0
    with open(dst, "w", encoding="utf-8") as out:
        out.write("tiny\t2\t0\tofficial\tnamed\tintermediary\n")
        for (names, members) in tree:
            out.write(f"c\t{names[0]}\t{names[2]}\t{names[1]}\n")
            for kind, off, inter, named, desc in members:
                # the descriptor rides in the source (official) namespace, spelled as the source
                # file had it - the reader rewrites it per namespace through the class table
                if kind == "f":
                    out.write(f"\tf\t{desc}\t{off}\t{named}\t{inter}\n")
                    n_fields += 1
                else:
                    out.write(f"\tm\t{desc}\t{off}\t{named}\t{inter}\n")
                    n_methods += 1

        # Identity rows for everything the official mapping does not cover: a class the vanilla
        # jar does not obfuscate runs under its own name, so the mapping the processor reads must
        # answer its members with themselves.
        if named_jar:
            with zipfile.ZipFile(named_jar) as jar:
                for entry in jar.namelist():
                    if not entry.endswith(".class") or not entry.startswith("com/mojang/"):
                        # the unobfuscated half of the game - blaze3d, brigadier, datafixers - is
                        # the half the official mapping does not cover; everything else is either
                        # mapped already or not a class the processor would ask about
                        continue
                    name = entry[: -len(".class")]
                    if name in covered or name.endswith("module-info") or "package-info" in name:
                        continue
                    try:
                        members = parse_class_members(jar.read(entry))
                    except Exception as e:  # noqa: BLE001 - one unreadable class must not stop the map
                        print(f"warn: could not read {name}: {e}", file=sys.stderr)
                        continue
                    out.write(f"c\t{name}\t{name}\t{name}\n")
                    for kind, member_name, desc in members:
                        # identity rows: the class runs under its own name, so the descriptor the
                        # named jar carries IS the source-namespace spelling
                        out.write(f"\t{kind}\t{desc}\t{member_name}\t{member_name}\t{member_name}\n")
                        if kind == "f":
                            n_fields += 1
                        else:
                            n_methods += 1
                    n_identity += 1

    print(
        f"mixin map: {len(tree)} mapped classes + {n_identity} identity classes, "
        f"{n_fields} fields, {n_methods} methods -> {dst}"
    )


if __name__ == "__main__":
    main(*sys.argv[1:4])
