#!/usr/bin/env python3
# PEANUTS: new file (code mods: IPS/IPS32 before the rewrite, DESIGN-MODS.md)
"""Applies a code mod's exefs_patches (ips.py: IPS/IPS32, Atmosphère's own format and offset convention: offsets count the NSO's 0x100-byte header) to one
NSO module, *before* dm/tools/patch_nso.py's ARM64 rewrite — the rewrite's own registered CUTS and MATCH_CUTS
assert the module's original bytes one at a time and would otherwise abort on a mod's own edits, or silently
rewrite around them.

Where this sits in a mod build (design/platform/DESIGN-MODS.md "ExeFS binary patch (IPS/IPS32)"): a code mod
(dev.peanuts.mods.ModStore) may ship exefs_patches/<name>/<buildid>.ips(32) files, named by the module's own NSO
build ID (all of it without its trailing zero bytes, or more, in either letter case, as Atmosphère's loader looks
them up; or its first 8 bytes alone: stem_matches), so a mod author's existing Atmosphère-layout package needs no
renaming to be used here. An emulator-layout mod keeps the same files in its exefs/ folder, beside the modules it
adds; that folder is then given as a patches dir too (dev.peanuts.build.ModBuild).

Usage: patch_ips.py <module NSO/NRO> <patches dir> <output NSO> [<patches dir> ...]
  <module>        a stock module (main, subsdk0, ...), read with dm/tools/nso.py exactly as patch_nso.py reads it
  <patches dir>   one or more directories to search for '<build id>.ips'/'.ips32' (case-insensitive stem match);
                   given in priority order (a mod earlier in the list is applied first; see ModBuild.java for how
                   a title's several enabled mods are ordered) — repeat the flag once per enabled mod's own
                   exefs_patches/<ModName>/ folder
  <output NSO>    a new, *uncompressed*, hash-check-cleared NSO0 with the patches applied (nso.py's own comment on
                   a module with its hash-check flags cleared: "not checked, as on the Switch" — the documented
                   escape hatch for a modified module); patch_nso.py reads this file exactly as it would the
                   original, build ID and segment layout unchanged, so every build-ID gate downstream (the game
                   profile's registered builds, patches.py's CUTS) still sees the same module

With no patch file found for the module's build ID in any given directory, the output is a byte-identical copy
of the input (not an error: a mod's exefs/ may ship a module with no accompanying exefs_patches, e.g. a plain
replacement NSO, or only exefs_patches for a *different* module than the one named here)."""
import glob
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ips
import nso


def build_id_hex16(build_id):
    """The module key in messages and in the profile's build rows' short form: the first 8 bytes of the 0x20-byte NSO build ID, as 16 lowercase hex digits"""
    return build_id[:8].hex()


def stem_matches(stem, build_id):
    """Whether a patch file named <stem>.ips is for the module with this build ID (bytes, the NSO header's 0x20). Atmosphère's rule (ldr_patcher.cpp
    MatchesModuleId): the stem is an even number of hex digits, at most 64, and equals the build ID when the bytes it leaves out are zero, in either
    letter case - Luminescent Platinum names its patch by the 32 digits of the game's 16-byte ID, others by all 40 of a 20-byte one or the whole 64.
    A stem of exactly 16 digits also counts when it is the ID's first 8 bytes whatever follows them: the short name this tool took from the start
    (the cheat files' convention), kept so a mod laid out for it still applies."""
    s = stem.lower()
    if len(s) < 16 or len(s) > 64 or len(s) % 2 or any(c not in '0123456789abcdef' for c in s): return False
    full = bytes(build_id).ljust(0x20, b'\0')[:0x20].hex()
    return full.startswith(s) and (len(s) == 16 or not full[len(s):].strip('0'))


def find_patches(patch_dir, build_id):
    """Every .ips/.ips32 file directly under patch_dir whose stem names build_id (stem_matches; build_id as bytes, or the 16-digit short form
    as a string, which a longer file name then cannot match), sorted for a deterministic order when both extensions are present (an '.ips32' sorts
    after '.ips' — a mod should never ship both for the same build, but if it does, the one meant for a build this large wins)"""
    out = []
    bid = bytes.fromhex(build_id) if isinstance(build_id, str) else build_id
    for ext in ('.ips', '.ips32'):
        for path in sorted(glob.glob(os.path.join(glob.escape(patch_dir), '*' + ext))):
            stem = os.path.splitext(os.path.basename(path))[0]
            if stem_matches(stem, bid): out.append(path)
    return out


def patches_for(patch_dirs, build_id):
    """find_patches() over every directory in patch_dirs, in order (a mod's priority order: the earliest
    directory's patches apply first)"""
    out = []
    for d in patch_dirs:
        out += find_patches(d, build_id)
    return out


def apply_patches(image, patch_paths):
    """mod.image (dm/tools/nso.py's Module.image: text+ro+data at their memory offsets, bss excluded) with every
    patch file in patch_paths applied in order. A record's offset counts the NSO file's 0x100-byte header before the
    image (ips.py's header comment), so image offset = record offset - 0x100, and what a record has inside the header
    is left out, as Atmosphère does. Refuses to grow the image (a code mod replacing or editing existing bytes, never
    one that appends new ones past the module's own layout — that would move every segment after it and is not
    representable by an in-place IPS/IPS32 patch here)."""
    out = bytearray(image)
    for path in patch_paths:
        try:
            with open(path, 'rb') as f:
                data = f.read()
            out = ips.apply(out, data, max_size=len(image), header=ips.NSO_HEADER)
        except ips.Error as e:
            raise ips.Error(f'{path}: {e}') from None
        except OSError as e:   # found a moment ago and gone or unreadable now (the mod deleted during its build): the same refusal, not a traceback
            raise ips.Error(f'{path}: cannot be read ({e.strerror or e})') from None
    return out


def write_nso(mod, image, path):
    """A new NSO0 file at path: image's bytes (dm/tools/patch_ips.py's own apply_patches() output, or the
    module's own image unmodified) stored uncompressed at mod's original segment layout, build ID and bss
    unchanged, with every segment's hash-check flag cleared so nso.py's load() (and so patch_nso.py) accepts the
    edited bytes without re-validating them against the original module's SHA-256 — see nso.py's
    test_damaged_bytes_of_the_right_size for the same escape hatch this reuses."""
    if len(image) < mod.text_end:
        raise ips.Error('the patched image is shorter than the text segment: a patch cannot shrink a module')
    hdr = bytearray(0x100)
    hdr[:4] = b'NSO0'
    hdr[0x40:0x60] = mod.build_id
    struct.pack_into('<I', hdr, 0x3c, mod.bss)
    body = b''
    for i, (mo, sz) in enumerate(mod.segments):
        seg = bytes(image[mo:mo + sz])
        if len(seg) != sz:
            raise ips.Error(f'segment {i} ({nso.NAMES[i]}) is {len(seg)} bytes at offset {mo:#x}, the module declares {sz:#x}')
        fo = 0x100 + len(body)
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, fo, mo, sz)
        struct.pack_into('<I', hdr, 0x60 + 4 * i, sz)   # "compressed" size == the stored size: these segments are not compressed
        body += seg
    # flags: every compression bit (0..2) and every hash-check bit (3..5) left 0 — uncompressed, unchecked
    struct.pack_into('<I', hdr, 0xc, 0)
    with open(path, 'wb') as f:
        f.write(hdr)
        f.write(body)


def main(argv):
    if len(argv) < 3:
        sys.exit(f'usage: {argv[0]} <module NSO/NRO> <patches dir>... <output NSO>')
    src, out = argv[1], argv[-1]
    patch_dirs = argv[2:-1]
    mod = nso.cli_read(src)
    build_id16 = build_id_hex16(mod.build_id)
    paths = patches_for(patch_dirs, mod.build_id)
    if not paths:
        print(f'patch_ips: no exefs_patches for build {build_id16} in {", ".join(patch_dirs) or "(no directories given)"}: {out} is an unmodified copy')
        write_nso(mod, mod.image, out)
        return 0
    try:
        image = apply_patches(mod.image, paths)
    except ips.Error as e:
        sys.exit(f'[RED] {e}')
    write_nso(mod, image, out)
    print(f'patch_ips: {len(paths)} patch file(s) applied to build {build_id16}: {", ".join(paths)}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
