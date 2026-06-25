#!/usr/bin/env python3
import argparse
import struct
import sys
import zipfile

parser = argparse.ArgumentParser(description="Verify Java class-file major versions inside a jar.")
parser.add_argument("jar")
parser.add_argument("--max-major", type=int, default=61, help="Maximum allowed class major version. 61 = Java 17.")
args = parser.parse_args()

bad = []
checked = 0
with zipfile.ZipFile(args.jar, "r") as zf:
    for name in zf.namelist():
        if not name.endswith(".class"):
            continue
        data = zf.read(name)[:8]
        if len(data) < 8:
            bad.append((name, "truncated"))
            continue
        magic, minor, major = struct.unpack(">IHH", data)
        if magic != 0xCAFEBABE:
            bad.append((name, "not a class"))
            continue
        checked += 1
        if major > args.max_major:
            bad.append((name, major))

if bad:
    print("Class-file version check failed:", file=sys.stderr)
    for name, major in bad[:50]:
        print(f"  {name}: {major}", file=sys.stderr)
    if len(bad) > 50:
        print(f"  ... {len(bad) - 50} more", file=sys.stderr)
    sys.exit(1)

print(f"OK: {checked} class file(s) are <= major {args.max_major}.")
