#!/usr/bin/env python3
"""Move libbox's gomobile runtime from package go to gx.

libv2ray.aar and libbox.aar both ship go.Seq. The classes are not interchangeable
(libbox's Seq has destroyRef), and both cannot be on the same classpath.
Native libs do not clash (libgojni.so vs libbox.so); only the Java package does.
JNI descriptors inside libbox.so are the same length, so a byte replace is safe.
"""
import io
import sys
import zipfile

REPLACEMENTS = (
    (b"go/Seq", b"gx/Seq"),
    (b"go/Universe", b"gx/Universe"),
    (b"go/error", b"gx/error"),
    (b"Java_go_", b"Java_gx_"),
)


def patch(data: bytes) -> bytes:
    for old, new in REPLACEMENTS:
        if len(old) != len(new):
            raise SystemExit(f"length mismatch: {old!r}")
        data = data.replace(old, new)
    return data


def rewrite_classes(jar: bytes) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(jar)) as src, zipfile.ZipFile(out, "w") as dst:
        for entry in src.infolist():
            data = src.read(entry.filename)
            name = entry.filename
            if name.endswith(".class"):
                data = patch(data)
            if name.startswith("go/"):
                name = "gx/" + name[3:]
            dst.writestr(name, data)
    return out.getvalue()


def main() -> None:
    path = sys.argv[1]
    tmp = path + ".tmp"
    with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w") as dst:
        for entry in src.infolist():
            data = src.read(entry.filename)
            if entry.filename == "classes.jar":
                data = rewrite_classes(data)
            elif entry.filename.endswith(".so"):
                data = patch(data)
            dst.writestr(entry.filename, data)
    import os
    os.replace(tmp, path)
    print(f"rewrote {path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: rewrite_libbox.py <libbox.aar>")
    main()
