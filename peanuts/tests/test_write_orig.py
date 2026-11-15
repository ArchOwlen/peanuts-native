"""scripts/pack_android.py write_orig: main.orig.bin made for a pack that has none, from the module's file and the pack's main.changed.tsv (the bytes the file has at
the table's runs, in its order), and refused when the table is of another build or names bytes past the code"""
import sys, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'scripts'), str(REPO / 'dm/tools'), str(REPO / 'dm/tools/tests')]
import pack_android
import test_nso


class WriteOrig(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp()); self.out = self.d / 'pack'; self.out.mkdir()
        self.src = self.d / 'main'; self.src.write_bytes(test_nso.make_nso(compress=(0, 0, 0)))
        self.bid = bytes(range(0x10)).hex()

    def table(self, rows, bid=None):
        (self.out / 'main.changed.tsv').write_text(f'# BUILD_ID\t{bid or self.bid}\n' + ''.join(f'{o:#x}\t{n:#x}\n' for o, n in rows))

    def test_runs_in_order(self):
        self.table([(0x10, 4), (0x40, 8), (0x100, 4)])
        n = pack_android.write_orig(self.src, self.out, 'main')
        t = test_nso.TEXT
        self.assertEqual(n, 16)
        self.assertEqual((self.out / 'main.orig.bin').read_bytes(), t[0x10:0x14] + t[0x40:0x48] + t[0x100:0x104])

    def test_other_build_refused(self):
        self.table([(0x10, 4)], bid='ff' * 16)
        with self.assertRaises(pack_android.Error): pack_android.write_orig(self.src, self.out, 'main')
        self.assertFalse((self.out / 'main.orig.bin').exists())

    def test_past_the_code_refused(self):
        self.table([(len(test_nso.TEXT), 4)])
        with self.assertRaises(pack_android.Error): pack_android.write_orig(self.src, self.out, 'main')
        self.assertFalse((self.out / 'main.orig.bin').exists())


if __name__ == '__main__':
    unittest.main()
