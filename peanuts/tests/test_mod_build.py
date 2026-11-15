"""ModBuild's module-list composition and build-ID refusal (DESIGN-MODS.md "Code injection"/"ExeFS binary patch"): plain-Java, javac/java only, same
pattern as test_mod_store.py and test_runtime_shader_map.py. Does not exercise the actual patch_ips.py/patch_nso.py file work (dm/tools/tests/
test_patch_ips.py covers that layer); this is the pure Java planning logic only."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MOD_STORE = REPO / 'android/app/src/main/java/dev/peanuts/mods/ModStore.java'
MOD_BUILD = REPO / 'android/app/src/main/java/dev/peanuts/build/ModBuild.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_build_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModBuildTest.java'
        runner.write_text('''package dev.peanuts.mods;
import dev.peanuts.build.ModBuild;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public final class ModBuildTest {
    static ModStore.Mod makeMod(File root, String name, String... exefsFiles) throws Exception {
        File dir = new File(root, name);
        dir.mkdirs();
        if (exefsFiles.length > 0) {
            File exefs = new File(dir, "exefs");
            exefs.mkdirs();
            for (String f : exefsFiles) new File(exefs, f).createNewFile();
        }
        ModStore.Mod m = new ModStore.Mod(dir);
        m.name = name; m.version = "1"; m.enabled = true; m.kind = ModStore.KIND_CODE;
        return m;
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        String cmd = args[1];
        switch (cmd) {
            case "compose_add": {
                // compose_add <root> <generic:true|false> <mod1Name:files...> <mod2Name:files...> ...
                List<String> profile = List.of("main", "subsdk0", "sdk");
                boolean generic = Boolean.parseBoolean(args[2]);
                List<ModStore.Mod> mods = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    String[] p = args[i].split(":");
                    String[] files = p.length > 1 ? p[1].split(",") : new String[0];
                    mods.add(makeMod(root, p[0], files));
                }
                ModBuild.Plan plan = ModBuild.compose(profile, mods, generic);
                if (plan.refusal != null) { System.out.println("REFUSED: " + plan.refusal); return; }
                System.out.println("modules=" + String.join(",", plan.modules));
                java.util.List<String> srcKeys = new ArrayList<>(plan.source.keySet());
                java.util.Collections.sort(srcKeys);   // exefs/ directory listing order is not guaranteed; sort for a stable assertion
                StringBuilder src = new StringBuilder();
                for (String k : srcKeys) src.append(k).append('=').append(plan.source.get(k).name).append(';');
                System.out.println("source=" + src);
                break;
            }
            case "patch_sets": {
                // patch_sets <root> <mod1Name:files...> ...: each patch set as <mod folder>/<folder under it>
                List<ModStore.Mod> mods = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    String[] p = args[i].split(":");
                    mods.add(makeMod(root, p[0], p.length > 1 ? p[1].split(",") : new String[0]));
                }
                ModBuild.Plan plan = ModBuild.compose(List.of("main", "subsdk0", "sdk"), mods, true);
                List<String> out = new ArrayList<>();
                for (File f : plan.patchSets) out.add(root.toPath().relativize(f.toPath()).toString());
                System.out.println(String.join(",", out));
                break;
            }
            case "build_id": {
                // build_id <moddedId> <registeredId1,registeredId2,...>
                List<String> reg = List.of(args[3].split(","));
                String r = ModBuild.checkMainBuildId(args[2], reg);
                System.out.println(r == null ? "OK" : "REFUSED: " + r);
                break;
            }
            default: throw new IllegalArgumentException(cmd);
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.classes), str(MOD_STORE), str(MOD_BUILD), str(runner)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.mods_dir = Path(tempfile.mkdtemp(dir=self.root, prefix='mods_'))

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModBuildTest', str(self.mods_dir), *args],
                            capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout.strip()

    def test_a_mod_that_only_replaces_known_modules_does_not_grow_the_list(self):
        out = self.run_cmd('compose_add', 'true', 'Lumi:main')
        self.assertEqual(out, 'modules=main,subsdk0,sdk\nsource=main=Lumi;')

    def test_a_mod_that_adds_a_new_module_puts_it_where_the_console_loads_it(self):
        # the console's order is main, subsdk0..9, sdk: an added subsdk stands before sdk (its symbols' precedence, and the distance its branches expect)
        out = self.run_cmd('compose_add', 'true', 'Lumi:main,subsdk9')
        self.assertEqual(out, 'modules=main,subsdk0,subsdk9,sdk\nsource=main=Lumi;subsdk9=Lumi;')
        out = self.run_cmd('compose_add', 'true', 'Lumi2:subsdk1')   # Luminescent Platinum for Pokemon Brilliant Diamond
        self.assertEqual(out, 'modules=main,subsdk0,subsdk1,sdk\nsource=subsdk1=Lumi2;')
        out = self.run_cmd('compose_add', 'true', 'A:subsdk3', 'B:subsdk1,subsdk2')
        self.assertEqual(out, 'modules=main,subsdk0,subsdk1,subsdk2,subsdk3,sdk\nsource=subsdk1=B;subsdk2=B;subsdk3=A;')

    def test_an_exefs_folder_with_ips_files_is_a_patch_set(self):
        # the emulators' layout: exefs/<build id>.ips beside the module the mod adds (Luminescent Platinum); exefs_patches/<set>/ comes first
        out = self.run_cmd('patch_sets', 'Lumi:subsdk1,94CEAE325C205C4B9D6F7235552F28FD.ips')
        self.assertEqual(out, 'Lumi/exefs')
        out = self.run_cmd('patch_sets', 'Plain:subsdk1,main.npdm', 'Other:a.IPS32')
        self.assertEqual(out, 'Other/exefs')
        out = self.run_cmd('patch_sets', 'Plain2:subsdk1')
        self.assertEqual(out, '')

    def test_adding_a_module_on_a_non_generic_host_is_refused(self):
        out = self.run_cmd('compose_add', 'false', 'Lumi:main,subsdk9')
        self.assertTrue(out.startswith('REFUSED:'), out)
        self.assertIn('subsdk9', out)
        self.assertIn('generic host', out)

    def test_replacing_a_module_on_a_non_generic_host_is_fine(self):
        out = self.run_cmd('compose_add', 'false', 'Lumi:main')
        self.assertEqual(out, 'modules=main,subsdk0,sdk\nsource=main=Lumi;')

    def test_first_enabled_mod_wins_a_module_two_mods_both_claim(self):
        out = self.run_cmd('compose_add', 'true', 'First:main', 'Second:main')
        self.assertEqual(out, 'modules=main,subsdk0,sdk\nsource=main=First;')

    def test_over_the_cap_is_refused(self):
        # profile already has 3 (main, subsdk0, sdk); adding 6 more modules reaches 9, past the 8-slot cap
        out = self.run_cmd('compose_add', 'true', 'Big:subsdk1,subsdk2,subsdk3,subsdk4,subsdk5,subsdk6')
        self.assertTrue(out.startswith('REFUSED:'), out)
        self.assertIn('DM_PROFILE_MODULES_CAP', out)

    def test_an_unknown_exefs_file_name_is_ignored(self):
        out = self.run_cmd('compose_add', 'true', 'Lumi:main,main.npdm')
        self.assertEqual(out, 'modules=main,subsdk0,sdk\nsource=main=Lumi;')

    def test_build_id_listed_is_ok(self):
        self.assertEqual(self.run_cmd('build_id', 'abc', 'abc,def'), 'OK')

    def test_build_id_not_listed_is_refused(self):
        out = self.run_cmd('build_id', 'zzz', 'abc,def')
        self.assertTrue(out.startswith('REFUSED:'), out)
        self.assertIn('zzz', out)


if __name__ == '__main__':
    unittest.main()
