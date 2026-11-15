#!/usr/bin/env python3
# Builds a synthetic .zip for zip_test.c: one stored member and one deflated member, both named *.nca so nx_zip_open keeps them, plus a non-.nca
# member to check that filter. Content is a deterministic formula (pattern(i, salt)) so the C test can recompute the expected bytes itself instead
# of needing a second fixture file to compare against.
import struct
import sys
import zipfile


def pattern(n, salt):
    return bytes((i * 37 + 11 + salt) & 0xFF for i in range(n))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "fixture.zip"
    stored = pattern(5000, 1)      # small, like a real font archive NCA
    deflated = pattern(20000, 2) + bytes(20000)   # half pattern, half zeros: compresses well, exercises a multi-block inflate (test reads in 64 KiB chunks)
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(zipfile.ZipInfo("notes.txt"), b"not an nca, must be skipped")
        zi = zipfile.ZipInfo("a/stored_member.nca")
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, stored)
        zi2 = zipfile.ZipInfo("deflate_member.nca")
        zi2.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi2, deflated)
    print(f"wrote {out}: stored {len(stored)} bytes, deflated {len(deflated)} bytes (compressed)")


if __name__ == "__main__":
    main()
