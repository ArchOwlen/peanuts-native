# PEANUTS: new file (code mods: IPS/IPS32 before the rewrite, DESIGN-MODS.md)
"""dm/tools/patch_ips.py: applying a code mod's exefs_patches (IPS/IPS32, by build ID) to a module before
dm/tools/patch_nso.py's ARM64 rewrite. Builds a synthetic NSO0 the same way test_nso.py's make_nso() does, so the
layout matches dm/tools/nso.py exactly without needing a real game module."""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

import lz4.block

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import ips
import nso
import patch_ips

TEXT, RO, DATA = bytes(range(256)) * 20, b'read-only ' * 300, b'\x01\x02' * 0x900


def page(x): return (x + 0xfff) & ~0xfff


def make_nso(segs=(TEXT, RO, DATA), compress=(1, 0, 1), bss=0x5000, build_id=bytes(range(0x20))):
    """Same layout as dm/tools/tests/test_nso.py's make_nso(): three segments back to back on pages from 0x100,
    each with its hash and, when its bit is set, LZ4-compressed."""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'
    hdr[0x40:0x60] = build_id
    for i, s in enumerate(segs):
        stored = lz4.block.compress(s, store_size=False) if compress[i] else s
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s))
        struct.pack_into('<I', hdr, 0x60 + 4 * i, len(stored))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += stored
        mo = page(mo + len(s))
    struct.pack_into('<I', hdr, 0xc, 0x38 | sum(c << i for i, c in enumerate(compress)))
    struct.pack_into('<I', hdr, 0x3c, bss)
    return bytes(hdr) + body


BUILD_ID = bytes.fromhex('0123456789abcdef' + '00' * 24)
BUILD_ID16 = '0123456789abcdef'


class BuildIdHex16(unittest.TestCase):
    def test_first_8_bytes_as_lowercase_hex(self):
        self.assertEqual(patch_ips.build_id_hex16(BUILD_ID), BUILD_ID16)


class FindPatches(unittest.TestCase):
    def test_matches_by_stem_case_insensitively(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / (BUILD_ID16.upper() + '.ips')).write_bytes(b'x')
            (Path(d) / 'deadbeefdeadbeef.ips').write_bytes(b'y')   # a different build: not matched
            got = patch_ips.find_patches(d, BUILD_ID16)
            self.assertEqual([os.path.basename(p) for p in got], [BUILD_ID16.upper() + '.ips'])

    def test_a_name_of_the_whole_build_id_matches(self):
        # Luminescent Platinum's patch is named by the 32 digits of a 16-byte ID (the bytes after them are zero); Atmosphère also takes 40 and 64
        bid = bytes.fromhex('94ceae325c205c4b9d6f7235552f28fd') + bytes(16)
        for stem in ('94CEAE325C205C4B9D6F7235552F28FD', '94ceae325c205c4b9d6f7235552f28fd' + '00' * 4, bid.hex(), '94ceae325c205c4b'):
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / (stem + '.ips')).write_bytes(b'x')
                self.assertEqual([os.path.basename(p) for p in patch_ips.find_patches(d, bid)], [stem + '.ips'], stem)

    def test_a_name_that_is_not_the_build_id_does_not_match(self):
        bid = bytes.fromhex('94ceae325c205c4b9d6f7235552f28fd') + bytes(16)
        for stem in ('94ceae325c205c4b9d6f7235552f28', '94ceae325c205c4b9d6f7235552f28fe', '94ceae325c205c4b9d6f', '94ceae325c205c4', '94ceae325c205c4b9d6f7235552f28fd' + '01',
                     '94ceae325c205c4bzz', bid.hex() + '00'):
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / (stem + '.ips')).write_bytes(b'x')
                self.assertEqual(patch_ips.find_patches(d, bid), [], stem)
        # the short name is the ID's first 8 bytes whatever follows them (20-byte IDs)
        self.assertTrue(patch_ips.stem_matches('0123456789abcdef', bytes.fromhex('0123456789abcdef') + b'\x55' * 12 + bytes(12)))
        self.assertFalse(patch_ips.stem_matches('0123456789abcdef55', bytes.fromhex('0123456789abcdef') + b'\x55' * 12 + bytes(12)))

    def test_no_match_is_empty(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(patch_ips.find_patches(d, BUILD_ID16), [])

    def test_patches_for_several_directories_in_priority_order(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / 'a', Path(d) / 'b'
            a.mkdir(); b.mkdir()
            (a / (BUILD_ID16 + '.ips')).write_bytes(b'x')
            (b / (BUILD_ID16 + '.ips')).write_bytes(b'y')
            got = patch_ips.patches_for([str(a), str(b)], BUILD_ID16)
            self.assertEqual(got, [str(a / (BUILD_ID16 + '.ips')), str(b / (BUILD_ID16 + '.ips'))])


class ApplyPatches(unittest.TestCase):
    def test_applies_in_order_and_stays_in_bounds(self):
        mod = nso.load(make_nso())
        p1 = ips.build([(0x104, b'\xAA\xBB')])   # a patch's offsets count the NSO's 0x100-byte header: image offset 4
        p2 = ips.build([(0x104, b'\xCC')])   # overlaps p1's edit; applied second, wins at offset 4
        with tempfile.TemporaryDirectory() as d:
            f1, f2 = Path(d) / 'a.ips', Path(d) / 'b.ips'
            f1.write_bytes(p1); f2.write_bytes(p2)
            out = patch_ips.apply_patches(mod.image, [str(f1), str(f2)])
        self.assertEqual(out[4], 0xCC)
        self.assertEqual(out[5], 0xBB)
        self.assertEqual(len(out), len(mod.image))

    def test_patch_cannot_grow_past_the_original_image(self):
        mod = nso.load(make_nso())
        past = ips.build([(0x100 + len(mod.image) + 4, b'\x01')])
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'a.ips'
            f.write_bytes(past)
            with self.assertRaises(ips.Error):
                patch_ips.apply_patches(mod.image, [str(f)])

    def test_offsets_count_the_nso_header(self):
        # Atmosphère takes sizeof(NsoHeader) off every record: 0x100 is the image's first byte, and what a record has inside the header is left out
        mod = nso.load(make_nso())
        p = ips.build([(0x100, b'\x11'), (0x10, b'\x22\x22'), (0xFE, b'\x33\x34\x35\x36'), (0x100 + mod.text_end - 1, b'\x44')])
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'a.ips'; f.write_bytes(p)
            out = patch_ips.apply_patches(mod.image, [str(f)])
        self.assertEqual(bytes(out[:2]), b'\x35\x36')   # the record across the header's end: its last two bytes, over the first record's
        self.assertEqual(out[mod.text_end - 1], 0x44)
        self.assertEqual(bytes(out[2:mod.text_end - 1]), bytes(mod.image[2:mod.text_end - 1]))   # the record inside the header wrote nothing
        self.assertEqual(len(out), len(mod.image))

    def test_bad_ips_file_name_in_error(self):
        mod = nso.load(make_nso())
        bad = b'PATCH' + b'garbage, no end marker here at all'
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'a.ips'
            f.write_bytes(bad)
            with self.assertRaises(ips.Error) as c:
                patch_ips.apply_patches(mod.image, [str(f)])
            self.assertIn(str(f), str(c.exception))

    def test_a_patch_file_that_is_gone_is_the_same_refusal(self):   # the mod deleted while it is built: a sentence with the file, not a traceback
        mod = nso.load(make_nso())
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'gone.ips'
            with self.assertRaises(ips.Error) as c:
                patch_ips.apply_patches(mod.image, [str(f)])
            self.assertIn(str(f), str(c.exception))


class WriteNso(unittest.TestCase):
    def test_round_trip_unpatched(self):
        mod = nso.load(make_nso(build_id=BUILD_ID))
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'out.nso'
            patch_ips.write_nso(mod, mod.image, str(out))
            m2 = nso.read(out)
        self.assertEqual(bytes(m2.image), bytes(mod.image))
        self.assertEqual(m2.segments, mod.segments)
        self.assertEqual(m2.bss, mod.bss)
        self.assertEqual(m2.build_id, BUILD_ID)

    def test_hash_check_flags_are_cleared_so_edited_bytes_load(self):
        mod = nso.load(make_nso())
        edited = bytearray(mod.image)
        edited[4] ^= 0xFF   # inside .text: a byte the original header's hash would no longer match
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'out.nso'
            patch_ips.write_nso(mod, edited, str(out))
            m2 = nso.read(out)   # would raise nso.Error "does not match its SHA-256" if the flag were still set
        self.assertEqual(m2.image[4], mod.image[4] ^ 0xFF)

    def test_output_is_uncompressed(self):
        mod = nso.load(make_nso(compress=(1, 0, 1)))   # text and data compressed in the input
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'out.nso'
            patch_ips.write_nso(mod, mod.image, str(out))
            flags = struct.unpack_from('<I', out.read_bytes(), 0xc)[0]
        self.assertEqual(flags, 0)

    def test_shrinking_the_image_is_refused(self):
        mod = nso.load(make_nso())
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'out.nso'
            with self.assertRaises(ips.Error):
                patch_ips.write_nso(mod, mod.image[:mod.text_end - 1], str(out))


class MainCli(unittest.TestCase):
    """patch_ips.py as a subprocess, the way a mod build would actually invoke it (ModBuild.java, by design)"""

    def test_no_matching_patch_copies_the_module_unmodified(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            patches = d / 'patches'; patches.mkdir()
            out = d / 'out.nso'
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_ips.py'), str(src), str(patches), str(out)],
                                capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('unmodified copy', r.stdout)
            m1, m2 = nso.read(src), nso.read(out)
            self.assertEqual(bytes(m1.image), bytes(m2.image))

    def test_applies_a_matching_patch_from_one_of_several_directories(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            empty = d / 'empty'; empty.mkdir()
            patches = d / 'LumiPatches'; patches.mkdir()
            (patches / (BUILD_ID16 + '.ips')).write_bytes(ips.build([(0x100, b'\x7f')]))
            out = d / 'out.nso'
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_ips.py'), str(src), str(empty), str(patches), str(out)],
                                capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('1 patch file(s) applied', r.stdout)
            m = nso.read(out)
            self.assertEqual(m.image[0], 0x7f)
            self.assertEqual(m.build_id, BUILD_ID)   # the patch edits a byte, not the module's declared build ID

    def test_malformed_patch_fails_loudly(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            patches = d / 'patches'; patches.mkdir()
            (patches / (BUILD_ID16 + '.ips')).write_bytes(b'not an ips file at all')
            out = d / 'out.nso'
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_ips.py'), str(src), str(patches), str(out)],
                                capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('[RED]', r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
