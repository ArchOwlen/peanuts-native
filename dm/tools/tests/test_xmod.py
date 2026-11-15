# PEANUTS: new file (code mods: branches straight from one module into another, dm/tools/xmod.py and patch_nso.py --xmod)
"""dm/tools/xmod.py (the console's module layout and the branches that leave a module) and the rewriter's side of it (patch_nso.py --xmod, <module>.xmod.tsv), on
two small hand-made modules that branch into each other the way a console mod's do. No game files."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import xmod

NOP, RET = 0xD503201F, 0xD65F03C0


def branch(at, to, link=False): return (0x94000000 if link else 0x14000000) | ((to - at) >> 2) & 0x3FFFFFF


def module(code, bss=0x3000, build=b'\x11' * 16, rel=()):
    """An uncompressed NSO: a text page (the module header's offset in its second word, MOD0 with no unwind table and an empty dynamic section, then code from
    0x100: {offset: word}), a ro page and a data page. rel: relative relocations as (slot, addend), their table in the ro page"""
    text = bytearray(struct.pack('<I', NOP) * 0x400)
    struct.pack_into('<I', text, 4, 0x10)
    text[0x10:0x14] = b'MOD0'
    struct.pack_into('<iiiii', text, 0x14, 0x1000 - 0x10, 0x3000 - 0x10, 0x3000 + bss - 0x10, 0x30, 0x30)   # dynamic (in ro, all zero), bss start and end, no eh_frame_hdr
    for o, w in code.items(): struct.pack_into('<I', text, o, w)
    hdr = bytearray(0x100); hdr[:4] = b'NSO0'; hdr[0x40:0x40 + len(build)] = build
    ro = bytearray(0x1000)
    if rel:
        struct.pack_into('<qQqQ', ro, 0, 7, 0x1100, 8, 24 * len(rel))   # DT_RELA, DT_RELASZ
        for i, (slot, addend) in enumerate(rel): struct.pack_into('<QQq', ro, 0x100 + 24 * i, slot, 1027, addend)
    body = bytes(text) + bytes(ro) + bytes(0x1000)
    for i in range(3):
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + 0x1000 * i, 0x1000 * i, 0x1000)
        struct.pack_into('<I', hdr, 0x60 + 4 * i, 0x1000)
    struct.pack_into('<I', hdr, 0x3c, bss)
    return bytes(hdr) + body


class Layout(unittest.TestCase):
    def test_console_order_insert(self):
        self.assertEqual(xmod.insert(['main', 'subsdk0', 'sdk'], 'subsdk1'), ['main', 'subsdk0', 'subsdk1', 'sdk'])
        self.assertEqual(xmod.insert(['main', 'sdk'], 'subsdk9'), ['main', 'subsdk9', 'sdk'])
        self.assertEqual(xmod.insert(['main', 'subsdk0'], 'sdk'), ['main', 'subsdk0', 'sdk'])
        self.assertEqual(xmod.insert(['main', 'subsdk2', 'sdk'], 'subsdk1'), ['main', 'subsdk1', 'subsdk2', 'sdk'])
        self.assertEqual(xmod.insert(['main', 'sdk'], 'main'), ['main', 'sdk'])   # there already: as it is
        self.assertEqual(xmod.insert(['sdk', 'main'], 'subsdk0'), ['subsdk0', 'sdk', 'main'])   # a profile in another order keeps it: before the first later one

    def test_size_is_segments_and_bss_on_a_page(self):
        text, size = xmod.header(module({}, bss=0x2345)[:0x100])
        self.assertEqual((text, size), (0x1000, 0x6000))   # data ends at 0x3000, bss 0x2345 after it, rounded up to 4 KiB
        with self.assertRaises(ValueError): xmod.header(b'NRO0' + bytes(0x100))

    def test_modules_stand_one_after_the_other_from_main(self):
        # Pokemon Brilliant Diamond 1.3.0 with Luminescent Platinum's subsdk1: main 0x5062000 and subsdk0 0x6a6000 put it at main+0x5708000
        lay = xmod.layout({'main': (0x2cf6a30, 0x5062000), 'subsdk0': (0x349170, 0x6a6000), 'sdk': (0x58d930, 0xd34000), 'subsdk1': (0x332e0, 0x689000)})
        self.assertEqual(list(lay), ['main', 'subsdk0', 'subsdk1', 'sdk'])
        self.assertEqual(lay['subsdk1'][0], 0x5708000)
        self.assertEqual(lay['sdk'][0], 0x5708000 + 0x689000)
        lay = xmod.layout({'rtld': (0x1000, 0x3000), 'main': (0x1000, 0x5000)})
        self.assertEqual((lay['rtld'][0], lay['main'][0]), (-0x3000, 0))

    def test_far_branches_and_where_they_land(self):
        a = bytearray(struct.pack('<I', NOP) * 0x400)
        struct.pack_into('<I', a, 0x100, branch(0x100, 0x6000 + 0x40, True))    # into the next module's text
        struct.pack_into('<I', a, 0x104, branch(0x104, 0x200))                 # inside this one
        struct.pack_into('<I', a, 0x108, branch(0x108, 0x6000 + 0x1800))       # the next module's data
        struct.pack_into('<I', a, 0x10c, branch(0x10c, 0x5ffc))                # this module's own bss
        lay = xmod.layout({'main': (0x1000, 0x6000), 'subsdk0': (0x1000, 0x4000)})
        far = xmod.far_branches(a, 0x1000, 0x6000)
        self.assertEqual(far, [(0x100, 'bl', 0x6040), (0x108, 'b', 0x7800)])
        self.assertEqual(xmod.far_branches(a, 0x1000, 0x6000, [0x108, 0x104, 0x2000]), [(0x108, 'b', 0x7800)])
        self.assertEqual(xmod.resolve(lay, 'main', 0x6040), ('subsdk0', 0x40))
        self.assertIsNone(xmod.resolve(lay, 'main', 0x7800))
        self.assertEqual(xmod.resolve(lay, 'subsdk0', -0x6000 + 0x10), ('main', 0x10))

    def test_far_pointers_are_the_relocations_that_leave_the_module(self):
        img = module({}, rel=[(0x2000, 0x2800), (0x2008, -0x6000 + 0x100), (0x2010, 0x6000), (0x2018, 0x6000 + 0x2ff8), (0x2020, 0)])[0x100:]
        # inside, into the module before, the module's own end (a linker script's end symbol: stays its own), into the module after, its own start
        self.assertEqual(xmod.far_pointers(img, 0x6000), [(0x2008, -0x5f00), (0x2018, 0x8ff8)])
        self.assertEqual(xmod.far_pointers(module({})[0x100:], 0x6000), [])
        lay = xmod.layout({'subsdk5': (0x1000, 0x6000), 'subsdk6': (0x1000, 0x6000), 'subsdk7': (0x1000, 0x6000)})
        self.assertEqual(xmod.resolve(lay, 'subsdk6', 0x8ff8, data=True), ('subsdk7', 0x2ff8))   # a data word of the next module: a pointer may go there
        self.assertIsNone(xmod.resolve(lay, 'subsdk6', 0x8ff8))                                    # a branch may not
        self.assertIsNone(xmod.resolve(lay, 'subsdk6', 0x6000 * 2 + 8, data=True))                 # past the last module
        files = xmod.plan(lay, {}, {'subsdk6': [(0x2008, -0x5f00), (0x2018, 0x8ff8)]})
        self.assertEqual(sorted(files), ['subsdk5', 'subsdk6', 'subsdk7'])
        self.assertEqual(files['subsdk5'].splitlines()[-1], 'inptr\t0x100\tsubsdk6')
        self.assertEqual(files['subsdk7'].splitlines()[-1], 'inptr\t0x2ff8\tsubsdk6')

    def test_changed_words(self):
        a = bytes(0x3000); b = bytearray(a); b[0x1004] = 1; b[0x2ffe] = 2; b[0x10] = 3
        self.assertEqual(xmod.changed_words(a, b, 0x3000), [0x10, 0x1004, 0x2ffc])
        self.assertEqual(xmod.changed_words(a, b, 0x2000), [0x10, 0x1004])

    def test_plan_names_only_the_modules_that_take_part(self):
        lay = xmod.layout({'main': (0x1000, 0x6000), 'subsdk0': (0x1000, 0x4000), 'sdk': (0x1000, 0x4000)})
        files = xmod.plan(lay, {'main': ('sites', [0x100, 0x104], [(0x100, 'bl', 0x6040)])})
        self.assertEqual(sorted(files), ['main', 'subsdk0'])
        self.assertEqual(files['main'].splitlines(), ['module\tmain\t0x0\t0x1000\t0x6000', 'module\tsubsdk0\t0x6000\t0x1000\t0x4000', 'module\tsdk\t0xa000\t0x1000\t0x4000',
                                                      'scan\tsites', 'site\t0x100', 'site\t0x104'])
        self.assertEqual(files['subsdk0'].splitlines()[3:], ['scan\tsites', 'in\t0x40\tmain'])
        self.assertEqual(xmod.plan(lay, {'main': ('sites', [0x100], [])}), {})


try:
    import capstone   # noqa: F401
    HAVE_REWRITER = capstone.cs_version()[0] == 5
except ImportError: HAVE_REWRITER = False


@unittest.skipUnless(HAVE_REWRITER, 'the rewriter needs Capstone 5')
class Rewriter(unittest.TestCase):
    """patch_nso.py --xmod on a patched module and the module a mod adds for it: subsdk5 (0x6000 on the console) and subsdk6 right after it"""

    def rewrite(self, d, name, nso_bytes, layout):
        src = d / f'{name}.nso'; src.write_bytes(nso_bytes)
        lf = d / f'{name}.xmod-layout'; lf.write_text(layout, encoding='utf-8')
        return subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_nso.py'), str(src), str(d / 'out'), name, '--target', 'android', '--xmod', str(lf)],
                              capture_output=True, text=True, env=dict(os.environ, DM_REWRITE_J='1', DM_GAME=''))

    def setUp(self):
        self.a = {0x100: branch(0x100, 0x6000 + 0x200, True), 0x104: branch(0x104, 0x6000 + 0x204), 0x108: RET}        # bl and b into subsdk6
        self.b = {0x200: branch(0x200, -0x6000 + 0x108, True), 0x204: RET, 0x300: branch(0x300, -0x6000 + 0x100)}      # and back
        self.heads = {'subsdk5': xmod.header(module(self.a)[:0x100]), 'subsdk6': xmod.header(module(self.b)[:0x100])}
        self.lay = xmod.layout(self.heads)

    def files(self, far_a, scan_a='sites'):
        ia, ib = bytes(module(self.a))[0x100:], bytes(module(self.b))[0x100:]
        return xmod.plan(self.lay, {'subsdk5': (scan_a, [0x100, 0x104, 0x108] if scan_a == 'sites' else [], far_a(ia)),
                                    'subsdk6': ('all', [], xmod.far_branches(ib, 0x1000, self.lay['subsdk6'][2]))})

    def test_tables_of_both_modules(self):
        files = self.files(lambda ia: xmod.far_branches(ia, 0x1000, self.lay['subsdk5'][2], [0x100, 0x104, 0x108]))
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            for name, code in (('subsdk5', self.a), ('subsdk6', self.b)):
                r = self.rewrite(d, name, module(code), files[name])
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(xmod.read_table(d / 'out/subsdk5.xmod.tsv'), [(0x100, 'bl', 'subsdk6', 0x200), (0x104, 'b', 'subsdk6', 0x204)])
            self.assertEqual(xmod.read_table(d / 'out/subsdk6.xmod.tsv'), [(0x200, 'bl', 'subsdk5', 0x108), (0x300, 'b', 'subsdk5', 0x100)])
            self.assertIn('subsdk5: xmod: 2 branches out, 2 into subsdk6; 2 come in', r.stdout + self.rewrite(d, 'subsdk5', module(self.a), files['subsdk5']).stdout)
            # the instructions stay where the file has them: the loader patches them there
            img = (d / 'out/subsdk5.patched.bin').read_bytes()
            self.assertEqual(struct.unpack_from('<II', img, 0x100), (self.a[0x100], self.a[0x104]))

    def test_pointer_rows(self):
        # subsdk6 keeps the address of a function of subsdk5 and of a word of its data, as relative relocations at the console's distance
        rel = [(0x2040, -0x6000 + 0x108), (0x2048, -0x6000 + 0x2010), (0x2050, 0x2100)]
        ib = module(self.b, rel=rel)[0x100:]
        ptrs = xmod.far_pointers(ib, self.lay['subsdk6'][2])
        self.assertEqual(ptrs, [(0x2040, -0x5ef8), (0x2048, -0x3ff0)])
        files = xmod.plan(self.lay, {'subsdk6': ('all', [], xmod.far_branches(ib, 0x1000, self.lay['subsdk6'][2]))}, {'subsdk6': ptrs})
        self.assertIn('inptr\t0x108\tsubsdk6', files['subsdk5'].splitlines())
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = self.rewrite(d, 'subsdk6', module(self.b, rel=rel), files['subsdk6'])
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(xmod.read_table(d / 'out/subsdk6.xmod.tsv'), [(0x200, 'bl', 'subsdk5', 0x108), (0x300, 'b', 'subsdk5', 0x100),
                                                                          (0x2040, 'ptr', 'subsdk5', 0x108), (0x2048, 'ptr', 'subsdk5', 0x2010)])
            self.assertIn('2 pointers out, 2 into subsdk5 (1 of them into its code)', r.stdout)
            r = self.rewrite(d, 'subsdk5', module(self.a), files['subsdk5'])
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('2 pointers come in', r.stdout)

    def test_a_pointer_into_no_module_refuses_the_build(self):
        rel = [(0x2040, -0x6000 - 0x10)]   # before the first module
        ib = module(self.b, rel=rel)[0x100:]
        files = xmod.plan(self.lay, {'subsdk6': ('all', [], xmod.far_branches(ib, 0x1000, self.lay['subsdk6'][2]))}, {'subsdk6': xmod.far_pointers(ib, self.lay['subsdk6'][2])})
        with tempfile.TemporaryDirectory() as d:
            r = self.rewrite(Path(d), 'subsdk6', module(self.b, rel=rel), files['subsdk6'])
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('[RED] cross-module branch: subsdk6+0x2040 holds the address subsdk6-0x6010', r.stdout + r.stderr)
            self.assertIn('in no module', r.stdout + r.stderr)

    def test_a_branch_into_no_modules_code_refuses_the_build(self):
        self.a[0x104] = branch(0x104, 0x6000 + 0x1800)   # subsdk6's read-only data
        files = self.files(lambda ia: xmod.far_branches(ia, 0x1000, self.lay['subsdk5'][2], [0x100, 0x104, 0x108]))
        with tempfile.TemporaryDirectory() as d:
            r = self.rewrite(Path(d), 'subsdk5', module(self.a), files['subsdk5'])
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('[RED] cross-module branch: subsdk5+0x104 b to subsdk5+0x7800', r.stdout + r.stderr)
            self.assertIn('in no module\'s code', r.stdout + r.stderr)
            self.assertFalse((Path(d) / 'out/subsdk5.xmod.tsv').exists())

    def test_without_the_layout_nothing_is_written(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); src = d / 'subsdk5.nso'; src.write_bytes(module(self.a))
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_nso.py'), str(src), str(d / 'out'), 'subsdk5', '--target', 'android'],
                               capture_output=True, text=True, env=dict(os.environ, DM_REWRITE_J='1', DM_GAME=''))
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertFalse((d / 'out/subsdk5.xmod.tsv').exists())
            self.assertNotIn('xmod', r.stdout)


if __name__ == '__main__':
    unittest.main()
