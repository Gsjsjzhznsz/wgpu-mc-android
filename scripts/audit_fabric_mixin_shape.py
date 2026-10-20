#!/usr/bin/env python3
"""Audit the @Redirect annotation shape of a built mod jar.

The fabric sponge-mixin fork changed `Redirect.at()` from a single `At` to `At[]` in
0.17.4 (the other injectors changed earlier and their array shape is what every runtime
has parsed for years). javac encodes whatever the compile classpath declares, and a jar
whose `Redirect.at` values are encoded as arrays kills every loader whose bundled
MixinExtras predates 0.5.5 - the factory-redirect scan casts the `at` value straight to
one AnnotationNode and the launch dies on
"ArrayList cannot be cast to AnnotationNode" (first seen on the iOS Prisma build,
loader 0.19.3 / MixinExtras 0.5.4, TextureAtlasMixin).

The single `Redirect.at` shape is readable by every loader from 0.15 to the latest, so
the build pins sponge-mixin 0.17.3 on the compile classpaths and this script is the
guard: every `at` value under every @Redirect must be a single nested annotation
(tag '@', 0x40), never an array (tag '[', 0x5B). Array-encoded `at` under the other
injectors is the ecosystem norm and only noted.

Usage: audit_fabric_mixin_shape.py <jar> [<jar>...]
Exits non-zero on the first violation; prints one line per scanned class on -v.
"""
import struct
import sys

TAG_NESTED = 0x40  # '@'
TAG_ARRAY = 0x5B   # '['


class Reader:
    def __init__(self, data):
        self.d = data
        self.o = 0

    def u1(self):
        v = self.d[self.o]
        self.o += 1
        return v

    def u2(self):
        v = struct.unpack_from('>H', self.d, self.o)[0]
        self.o += 2
        return v

    def u4(self):
        v = struct.unpack_from('>I', self.d, self.o)[0]
        self.o += 4
        return v

    def skip(self, n):
        self.o += n


def parse_constant_pool(r):
    count = r.u2()
    pool = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:  # Utf8
            ln = r.u2()
            pool[i] = r.d[r.o:r.o + ln].decode('utf-8', 'replace')
            r.skip(ln)
        elif tag in (3, 4):
            r.skip(4)
        elif tag in (5, 6):
            r.skip(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            r.skip(2)
        elif tag == 15:  # MethodHandle: u1 kind + u2 index
            r.skip(3)
        else:  # field/method/interface refs, name-and-type, dynamic
            r.skip(4)
        i += 1
    return pool


def walk_element(r, pool, path, violations, notes):
    tag = r.u1()
    if tag in (0x42, 0x43, 0x44, 0x46, 0x49, 0x4A, 0x53, 0x5A, 0x73):
        r.skip(2)  # primitive or string const
    elif tag == 0x65:  # enum
        r.skip(4)
    elif tag == 0x63:  # class
        r.skip(2)
    elif tag == TAG_NESTED:
        walk_annotation(r, pool, path, violations, notes)
    elif tag == TAG_ARRAY:
        for _ in range(r.u2()):
            walk_element(r, pool, path, violations, notes)
    else:
        raise ValueError(f'unexpected element tag {tag:#x} at {r.o}')


def walk_annotation(r, pool, path, violations, notes):
    type_name = pool[r.u2()]
    is_redirect = type_name.endswith('mixin/injection/Redirect;')
    short = type_name.split('/')[-1].rstrip(';')
    for _ in range(r.u2()):
        name = pool[r.u2()]
        here = f'{path}.{short}:{name}'
        start = r.o
        tag = r.d[r.o]
        if name == 'at' and tag == TAG_ARRAY:
            (violations if is_redirect else notes).append(here)
        walk_element(r, pool, here, violations, notes)
        if r.o == start:  # paranoia: never spin on a malformed attribute
            raise ValueError(f'element did not advance at {here}')


def audit_class(data, name, violations, notes):
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError(f'{name}: not a class file')
    r.u2()
    r.u2()
    pool = parse_constant_pool(r)
    r.u2()
    r.skip(2)
    r.skip(2)
    r.skip(2 * r.u2())  # interfaces
    for _ in range(2):  # fields, methods
        for _ in range(r.u2()):
            r.u2()
            r.skip(2)
            r.skip(2)
            for _ in range(r.u2()):
                aname = pool[r.u2()]
                alen = r.u4()
                end = r.o + alen
                if aname == 'RuntimeVisibleAnnotations':
                    for _ in range(r.u2()):
                        walk_annotation(r, pool, name, violations, notes)
                r.o = end


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    import zipfile
    failed = False
    for jar in argv[1:]:
        with zipfile.ZipFile(jar) as zf:
            violations = []
            notes = []
            classes = [n for n in zf.namelist() if n.endswith('.class') and not n.endswith('module-info.class')]
            for entry in sorted(classes):
                try:
                    audit_class(zf.read(entry), entry, violations, notes)
                except Exception as exc:  # a class we cannot parse is a real risk, not a skip
                    print(f'FAIL {jar}: could not audit {entry}: {exc}')
                    failed = True
            if violations:
                failed = True
                for v in violations:
                    print(f'FAIL {jar}: array-encoded @Redirect "at" in {v} '
                          f'- the jar was compiled against sponge-mixin 0.17.4+ and kills '
                          f'MixinExtras <=0.5.4 loaders; pin 0.17.3 on the compile classpath')
            for v in notes:
                print(f'note {jar}: array-encoded "at" under a non-Redirect injector in {v} '
                      f'(the ecosystem norm; every runtime parses it)')
            if not violations and not notes:
                print(f'OK {jar}: {len(classes)} classes, every "at" is a single annotation')
            elif not violations:
                print(f'OK {jar}: {len(classes)} classes, every @Redirect "at" is a single annotation '
                      f'({len(notes)} non-Redirect array notes)')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
