#!/usr/bin/env python3
"""Add a classesN.dex to an APK without touching resources or signatures (sign afterwards).

Usage: add_dex.py <in.apk> <out.apk> <dex-file> [entry-name]
"""
import sys
import zipfile

def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst, dex, name = sys.argv[1], sys.argv[2], sys.argv[3], (sys.argv[4] if len(sys.argv) > 4 else "classes3.dex")
    data = open(dex, "rb").read()
    zin = zipfile.ZipFile(src)
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, data)
    print(f"added {name} ({len(data)} bytes) -> {dst}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
