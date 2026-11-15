"""ModStore's enable/order/remove contract (DESIGN-MODS.md "Mods"): plain-Java, javac/java only, same pattern as test_runtime_shader_map.py."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/mods/ModStore.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModStoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_store_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModStoreTest.java'
        runner.write_text('''package dev.peanuts.mods;
import java.io.File;
import java.util.List;
public final class ModStoreTest {
    public static void main(String[] args) throws Exception {
        File modsRoot = new File(args[0]);
        String titleId = args[1];
        String cmd = args[2];
        switch (cmd) {
            case "make": {
                // make <modsRoot> <titleId> <name> <kind> <enabled>
                File dir = new File(ModStore.gameDir(modsRoot, titleId), args[3]);
                dir.mkdirs();
                File romfs = new File(dir, "romfs");
                romfs.mkdirs();
                new File(romfs, "placeholder.bin").createNewFile();
                if ("code".equals(args[4])) {
                    new File(dir, "exefs").mkdirs();
                    new File(new File(dir, "exefs"), "main").createNewFile();
                }
                ModStore.Mod m = new ModStore.Mod(dir);
                m.name = args[3]; m.version = "1"; m.enabled = Boolean.parseBoolean(args[5]);
                m.order = ModStore.list(modsRoot, titleId).size();
                m.kind = args[4];
                ModStore.save(m);
                break;
            }
            case "list": {
                for (ModStore.Mod m : ModStore.list(modsRoot, titleId))
                    System.out.println(m.dir.getName() + " " + m.order + " " + m.enabled + " " + m.kind);
                break;
            }
            case "setEnabled": ModStore.setEnabled(modsRoot, titleId, args[3], Boolean.parseBoolean(args[4])); break;
            case "remove": ModStore.remove(modsRoot, titleId, args[3]); break;
            case "needsBuild": System.out.println(ModStore.needsBuild(ModStore.list(modsRoot, titleId))); break;
            case "overlay": {
                List<File> dirs = ModStore.enabledRomfsDirs(ModStore.list(modsRoot, titleId));
                StringBuilder b = new StringBuilder();
                for (File f : dirs) { if (b.length() > 0) b.append(':'); b.append(f.getParentFile().getName()); }
                System.out.println(b);
                break;
            }
            case "freeName": System.out.println(ModStore.freeName(modsRoot, titleId, args[3])); break;
            default: throw new IllegalArgumentException(cmd);
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.classes), str(SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.mods_root = Path(tempfile.mkdtemp(dir=self.root, prefix='mods_'))
        self.title = '0100000000000000'

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModStoreTest',
                             str(self.mods_root), self.title, *args], check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def test_order_and_priority_after_import(self):
        self.run_cmd('make', 'Alpha', 'files', 'true')
        self.run_cmd('make', 'Beta', 'files', 'true')
        self.run_cmd('make', 'Gamma', 'files', 'false')
        lines = self.run_cmd('list').splitlines()
        self.assertEqual(lines, ['Alpha 0 true files', 'Beta 1 true files', 'Gamma 2 false files'])
        # First-enabled-wins order: Alpha then Beta (Gamma disabled, not in the overlay list)
        self.assertEqual(self.run_cmd('overlay'), 'Alpha:Beta')

    def test_disable_removes_from_overlay_but_keeps_order_slot(self):
        self.run_cmd('make', 'Alpha', 'files', 'true')
        self.run_cmd('make', 'Beta', 'files', 'true')
        self.run_cmd('setEnabled', 'Alpha', 'false')
        self.assertEqual(self.run_cmd('overlay'), 'Beta')
        self.assertEqual(self.run_cmd('list').splitlines(), ['Alpha 0 false files', 'Beta 1 true files'])

    def test_remove_deletes_the_folder_and_renumbers_the_rest(self):
        self.run_cmd('make', 'Alpha', 'files', 'true')
        self.run_cmd('make', 'Beta', 'files', 'true')
        self.run_cmd('make', 'Gamma', 'files', 'true')
        self.run_cmd('remove', 'Beta')
        self.assertFalse((self.mods_root / self.title / 'Beta').exists())
        self.assertEqual(self.run_cmd('list').splitlines(), ['Alpha 0 true files', 'Gamma 1 true files'])

    def test_code_mod_needs_build_and_lists_its_romfs(self):
        self.run_cmd('make', 'CodeMod', 'code', 'true')
        self.assertEqual(self.run_cmd('needsBuild'), 'true')
        self.assertEqual(self.run_cmd('overlay'), 'CodeMod')

    def test_disabled_code_mod_does_not_need_build(self):
        self.run_cmd('make', 'CodeMod', 'code', 'false')
        self.assertEqual(self.run_cmd('needsBuild'), 'false')

    def test_free_name_avoids_collision(self):
        self.run_cmd('make', 'Retexture', 'files', 'true')
        self.assertEqual(self.run_cmd('freeName', 'Retexture'), 'Retexture (2)')
        self.assertEqual(self.run_cmd('freeName', 'Something else'), 'Something else')


if __name__ == '__main__':
    unittest.main()
