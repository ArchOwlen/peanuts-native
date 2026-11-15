"""ModdedBuild's pure-Java glue (DESIGN-MODS.md "Code injection"/"ExeFS binary patch"): variant folder layout, the modded rewrite step's extra argv,
pack.tsv's modules-row reader, and the exlaunch-hook warning. Same javac/java pattern as test_mod_build.py; does not start any real build."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MOD_STORE = REPO / 'android/app/src/main/java/dev/peanuts/mods/ModStore.java'
MOD_BUILD = REPO / 'android/app/src/main/java/dev/peanuts/build/ModBuild.java'
MODDED_BUILD = REPO / 'android/app/src/main/java/dev/peanuts/build/ModdedBuild.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModdedBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='modded_build_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModdedBuildTest.java'
        runner.write_text('''package dev.peanuts.mods;
import dev.peanuts.build.ModBuild;
import dev.peanuts.build.ModdedBuild;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
public final class ModdedBuildTest {
    static ModStore.Mod makeMod(File root, String name, String kind, String... exefsFiles) throws Exception {
        File dir = new File(root, name);
        dir.mkdirs();
        if (exefsFiles.length > 0) {
            File exefs = new File(dir, "exefs");
            exefs.mkdirs();
            for (String f : exefsFiles) new File(exefs, f).createNewFile();
        }
        ModStore.Mod m = new ModStore.Mod(dir);
        m.name = name; m.version = "1"; m.enabled = true; m.kind = kind;
        return m;
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        String cmd = args[1];
        switch (cmd) {
            case "workdir": {
                File home = new File(args[2]);
                System.out.println(ModdedBuild.workDir(home, args[3]).getPath());
                break;
            }
            case "shaders_dir": {
                System.out.println(ModdedBuild.normalShadersDir(new File(args[2])).getPath());
                break;
            }
            case "normal_modules": {
                File pack = new File(root, "pack");
                pack.mkdirs();
                Files.write(new File(pack, "pack.tsv").toPath(),
                        ("format\\t1\\ntitle_id\\t0100000011D90000\\nmodules\\t" + args[2] + "\\nromfs\\tbase/romfs\\n").getBytes());
                List<String> got = ModdedBuild.normalModules(pack);
                System.out.println(got == null ? "NULL" : String.join(",", got));
                break;
            }
            case "normal_modules_missing": {
                File pack = new File(root, "pack_missing");
                pack.mkdirs();
                List<String> got = ModdedBuild.normalModules(pack);
                System.out.println(got == null ? "NULL" : String.join(",", got));
                break;
            }
            case "rewrite_argv": {
                // rewrite_argv <root> <generic:true|false> <mod1Name:files...> ...
                List<String> profile = List.of("main", "subsdk0", "sdk");
                boolean generic = Boolean.parseBoolean(args[2]);
                List<ModStore.Mod> mods = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    String[] p = args[i].split(":", -1);
                    String[] files = p.length > 1 && !p[1].isEmpty() ? p[1].split(",") : new String[0];
                    ModStore.Mod m = makeMod(root, p[0], ModStore.KIND_CODE, files);
                    if (p.length > 2) { File set = new File(m.exefsPatches(), p[2]); set.mkdirs(); Files.write(new File(set, "94ceae325c205c4b.ips").toPath(), "PATCHEOF".getBytes()); }
                    mods.add(m);
                }
                ModBuild.Plan plan = ModBuild.compose(profile, mods, generic);
                if (plan.refusal != null) { System.out.println("REFUSED: " + plan.refusal); return; }
                List<String> argv = ModdedBuild.rewriteArgv(plan);
                System.out.println(String.join("|", argv));
                break;
            }
            case "exlaunch_added": {
                // exlaunch_added <root> <modName> <addsSubsdk9:true|false> <exlaunchMarker:true|false> [<module>=<its bytes as text> ...]
                String modName = args[2];
                boolean adds = Boolean.parseBoolean(args[3]);
                boolean marker = Boolean.parseBoolean(args[4]);
                String[] files = new String[args.length - 4];
                files[0] = "subsdk9";
                for (int i = 5; i < args.length; i++) files[i - 4] = args[i].substring(0, args[i].indexOf('='));
                ModStore.Mod mod = makeMod(root, modName, ModStore.KIND_CODE, files);
                if (marker) Files.write(new File(mod.exefs(), "subsdk9").toPath(), "hello exlaunch world".getBytes());
                for (int i = 5; i < args.length; i++) Files.write(new File(mod.exefs(), files[i - 4]).toPath(), args[i].substring(args[i].indexOf('=') + 1).getBytes());
                List<String> profile = adds ? List.of("main", "subsdk0", "sdk") : List.of("main", "subsdk0", "sdk", "subsdk9");
                List<ModStore.Mod> mods = List.of(mod);
                ModBuild.Plan plan = ModBuild.compose(profile, mods, true);
                String warn = ModdedBuild.exlaunchWarning(profile, plan);
                System.out.println(warn == null ? "NONE" : warn);
                break;
            }
            default: throw new IllegalArgumentException(cmd);
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.classes), str(MOD_STORE), str(MOD_BUILD), str(MODDED_BUILD), str(runner)],
                        check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(dir=self.root, prefix='work_'))

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModdedBuildTest', str(self.work), *args],
                            capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout.strip()

    def test_work_dir_is_under_the_game_home_named_after_the_variant(self):
        home = self.work / 'BrilliantDiamond'
        out = self.run_cmd('workdir', str(home), '0100000011D90000-mod-abc123def012')
        self.assertEqual(out, str(home / '0100000011D90000-mod-abc123def012-work'))

    def test_normal_shaders_dir_is_the_shaders_subfolder(self):
        pack = self.work / 'pack'
        out = self.run_cmd('shaders_dir', str(pack))
        self.assertEqual(out, str(pack / 'shaders'))

    def test_normal_modules_reads_the_pack_tsv_row(self):
        out = self.run_cmd('normal_modules', 'main subsdk0 sdk')
        self.assertEqual(out, 'main,subsdk0,sdk')

    def test_normal_modules_is_null_without_a_pack_tsv(self):
        out = self.run_cmd('normal_modules_missing')
        self.assertEqual(out, 'NULL')

    def test_rewrite_argv_for_a_replace_only_mod(self):
        out = self.run_cmd('rewrite_argv', 'true', 'Lumi:main')
        self.assertEqual(out, '--modules|main,subsdk0,sdk|--mod-exefs|main=' + str(self.work / 'Lumi' / 'exefs' / 'main'))

    def test_rewrite_argv_for_an_added_module(self):
        out = self.run_cmd('rewrite_argv', 'true', 'Lumi:main,subsdk9')
        tokens = out.split('|')
        self.assertEqual(tokens[0], '--modules')
        self.assertEqual(tokens[1], 'main,subsdk0,subsdk9,sdk')   # where the console loads it, not after sdk
        self.assertIn('--mod-exefs', tokens)
        self.assertIn('main=' + str(self.work / 'Lumi' / 'exefs' / 'main'), tokens)
        self.assertIn('subsdk9=' + str(self.work / 'Lumi' / 'exefs' / 'subsdk9'), tokens)

    def test_rewrite_argv_for_a_patches_only_mod_offers_its_set_to_every_module(self):
        # Atmosphère layout: exefs_patches/<patch set>/<build id>.ips, the folder named after the set, not a module (found on the Thor, 2026-11-13:
        # a patches-only mod produced no argv at all and the variant build rewrote the stock main)
        out = self.run_cmd('rewrite_argv', 'true', 'Lumi::lumi_fixes')
        s = str(self.work / 'Lumi' / 'exefs_patches' / 'lumi_fixes')
        self.assertEqual(out, '|'.join(['--modules', 'main,subsdk0,sdk', '--mod-patches', 'main=' + s, '--mod-patches', 'subsdk0=' + s,
                                        '--mod-patches', 'sdk=' + s]))

    def test_rewrite_argv_is_empty_without_any_claimed_module(self):
        # a kind=code mod with no exefs/exefs_patches never reaches ModBuild.compose's source map (ModStore.enabledCodeMods filters it out), but
        # defensively check the empty-plan shape too: compose() with no mods at all
        out = self.run_cmd('rewrite_argv', 'true')
        self.assertEqual(out, '')

    def test_exlaunch_warning_fires_for_an_added_subsdk9(self):
        out = self.run_cmd('exlaunch_added', 'Lumi', 'true', 'false')
        self.assertIn('exlaunch-style hook module', out)
        self.assertIn('svcSetProcessMemoryPermission', out)

    def test_exlaunch_warning_fires_for_a_marker_even_when_replacing(self):
        out = self.run_cmd('exlaunch_added', 'Lumi', 'false', 'true')
        self.assertIn('exlaunch-style hook module', out)

    def test_no_exlaunch_warning_for_a_plain_replacement_with_no_marker(self):
        out = self.run_cmd('exlaunch_added', 'Lumi', 'false', 'false')
        self.assertEqual(out, 'NONE')

    def test_exlaunch_warning_names_every_module_with_a_marker_and_never_main(self):
        # a hook module under another name (subsdk1, with exlaunch's namespace in its symbols), and a main that happens to hold the word
        out = self.run_cmd('exlaunch_added', 'Lumi', 'false', 'false', 'subsdk1=_ZN3exl4hook::exl::hook', 'main=exlaunch', 'subsdk2=plain code')
        self.assertIn('subsdk1 (from Lumi) looks like an exlaunch-style hook module', out)
        self.assertNotIn('subsdk9 (from', out)
        self.assertNotIn('subsdk2 (from', out)
        self.assertNotIn('main (from', out)
        out = self.run_cmd('exlaunch_added', 'Lumi', 'true', 'false', 'subsdk1=uses exl::patch')
        self.assertEqual(out.count('looks like an exlaunch-style hook module'), 2)   # the added subsdk9 and the marked subsdk1, one sentence each
        self.assertIn('\n\n', out)

    def test_no_exlaunch_warning_for_an_added_module_that_is_not_subsdk9_and_has_no_marker(self):
        # Luminescent Platinum's subsdk1: its branches into main are in its code, nothing is written at run time
        out = self.run_cmd('exlaunch_added', 'Lumi', 'false', 'false', 'subsdk1=static hooks only')
        self.assertEqual(out, 'NONE')


if __name__ == '__main__':
    unittest.main()
