"""ModStore's build-side additions (enabledCodeMods/codeModsHash/variantId), kept in a test of their own rather than appended to test_mod_store.py so a
concurrent edit to that file's own driver never conflicts with this one. Same plain-Java/javac/java pattern."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MOD_STORE = REPO / 'android/app/src/main/java/dev/peanuts/mods/ModStore.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModStoreVariantTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_variant_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModStoreVariantTest.java'
        runner.write_text('''package dev.peanuts.mods;
import java.io.File;
import java.util.List;
public final class ModStoreVariantTest {
    public static void main(String[] args) throws Exception {
        File modsRoot = new File(args[0]);
        String titleId = args[1];
        String cmd = args[2];
        switch (cmd) {
            case "make": {
                // make <modsRoot> <titleId> make <name> <kind> <enabled> <exefs:true|false>
                File dir = new File(ModStore.gameDir(modsRoot, titleId), args[3]);
                dir.mkdirs();
                if ("true".equals(args[6])) {
                    File exefs = new File(dir, "exefs");
                    exefs.mkdirs();
                    new File(exefs, "main").createNewFile();
                }
                ModStore.Mod m = new ModStore.Mod(dir);
                m.name = args[3]; m.version = "1"; m.enabled = Boolean.parseBoolean(args[5]); m.kind = args[4];
                m.order = ModStore.list(modsRoot, titleId).size();
                ModStore.save(m);
                break;
            }
            case "codeHash": {
                List<ModStore.Mod> codeMods = ModStore.enabledCodeMods(ModStore.list(modsRoot, titleId));
                System.out.println(ModStore.codeModsHash(codeMods));
                break;
            }
            case "variantId": {
                List<ModStore.Mod> codeMods = ModStore.enabledCodeMods(ModStore.list(modsRoot, titleId));
                System.out.println(ModStore.variantId(titleId, codeMods));
                break;
            }
            case "enabledCode": {
                StringBuilder b = new StringBuilder();
                for (ModStore.Mod m : ModStore.enabledCodeMods(ModStore.list(modsRoot, titleId))) { if (b.length() > 0) b.append(','); b.append(m.name); }
                System.out.println(b);
                break;
            }
            default: throw new IllegalArgumentException(cmd);
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.classes), str(MOD_STORE), str(runner)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.mods_root = Path(tempfile.mkdtemp(dir=self.root, prefix='mods_'))
        self.title = '0100000011D90000'

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModStoreVariantTest',
                             str(self.mods_root), self.title, *args], check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def test_no_code_mods_means_no_variant(self):
        self.run_cmd('make', 'FilesOnly', 'files', 'true', 'false')
        self.assertEqual(self.run_cmd('variantId'), 'null')
        self.assertEqual(self.run_cmd('enabledCode'), '')

    def test_a_disabled_code_mod_is_excluded(self):
        self.run_cmd('make', 'Lumi', 'code', 'false', 'true')
        self.assertEqual(self.run_cmd('enabledCode'), '')
        self.assertEqual(self.run_cmd('variantId'), 'null')

    def test_a_code_mod_with_no_exefs_or_patches_is_excluded(self):
        self.run_cmd('make', 'RomfsOnlyButKindCode', 'code', 'true', 'false')
        self.assertEqual(self.run_cmd('enabledCode'), '')

    def test_an_enabled_code_mod_gets_a_variant_id(self):
        self.run_cmd('make', 'Lumi', 'code', 'true', 'true')
        self.assertEqual(self.run_cmd('enabledCode'), 'Lumi')
        v = self.run_cmd('variantId')
        self.assertTrue(v.startswith(self.title + '-mod-'), v)
        self.assertRegex(v, r'^' + self.title + r'-mod-[0-9a-f]{12}$')

    def test_the_hash_is_stable_for_the_same_mod_set_and_order(self):
        self.run_cmd('make', 'Lumi', 'code', 'true', 'true')
        v1 = self.run_cmd('variantId')
        v2 = self.run_cmd('variantId')
        self.assertEqual(v1, v2)

    def test_the_hash_changes_when_a_second_code_mod_is_enabled(self):
        self.run_cmd('make', 'Lumi', 'code', 'true', 'true')
        v1 = self.run_cmd('variantId')
        self.run_cmd('make', 'Second', 'code', 'true', 'true')
        v2 = self.run_cmd('variantId')
        self.assertNotEqual(v1, v2)


if __name__ == '__main__':
    unittest.main()
