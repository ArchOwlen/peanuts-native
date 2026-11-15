"""The mods pipeline's error paths and leftovers (plain Java, javac/java, as test_mod_store.py): a variant id that stays the same for the same code mods, the
builds with mods a deleted mod leaves, a variant pack whose rebuild did not finish, what an interrupted install or delete leaves, .ips32 and patches-only mods at
the install, the 16-digit patch name, and a zip's unsafe entry names."""
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src/main/java/dev/peanuts'
SRCS = [JAVA / 'mods/ModStore.java', JAVA / 'mods/ModImporter.java', JAVA / 'build/ModBuild.java', JAVA / 'build/ModdedBuild.java']
TITLE = '0100000011D90000'
BUILD = '3C70CD1E3CE14A8E8EDBA8DDFE16B5C5E8C5D7A1'   # a 20-byte build ID, as pack.tsv's build rows have it

RUNNER = '''package dev.peanuts.mods;
import dev.peanuts.build.ModdedBuild;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
public final class ModHardeningTest {
    public static void main(String[] a) throws Exception {
        File root = new File(a[1]);
        switch (a[0]) {
            case "make": {   // make <modsRoot> <name> <kind> <enabled> <order>: a mod with exefs/main when its kind is code
                File dir = new File(root, a[2]);
                dir.mkdirs();
                if (a[3].equals("code")) { new File(dir, "exefs").mkdirs(); new File(dir, "exefs/main").createNewFile(); }
                else { new File(dir, "romfs").mkdirs(); new File(dir, "romfs/a.bin").createNewFile(); }
                ModStore.Mod m = new ModStore.Mod(dir);
                m.name = a[2]; m.version = ""; m.kind = a[3]; m.enabled = Boolean.parseBoolean(a[4]); m.order = Integer.parseInt(a[5]);
                ModStore.save(m);
                break;
            }
            case "variant": System.out.println(ModStore.variantId(a[2], ModStore.enabledCodeMods(ModStore.list(root, a[2])))); break;
            case "reachable": {
                java.util.Set<String> ids = ModStore.reachableVariantIds(a[2], ModStore.list(root, a[2]));
                System.out.println(ids == null ? "null" : String.join(" ", new TreeSet<>(ids)));
                break;
            }
            case "list": {
                StringBuilder b = new StringBuilder();
                for (ModStore.Mod m : ModStore.list(root, a[2])) b.append(m.dir.getName()).append(';');
                System.out.println(b);
                break;
            }
            case "sweepDeleted": ModStore.sweepDeleted(root, a[2]); break;
            case "remove": ModStore.remove(root, a[2], a[3]); break;
            case "freeName": System.out.println(ModStore.freeName(root, a[2], a[3])); break;
            case "sweepVariants": {   // sweepVariants <modsRoot> <title> <appPacks> <buildGame> <home>
                java.util.Set<String> keep = ModStore.reachableVariantIds(a[2], ModStore.list(root, a[2]));
                System.out.println(ModdedBuild.sweepVariants(a[2], keep, new File(a[3]), new File(a[4]), new File(a[5])));
                break;
            }
            case "sweepUnknown": System.out.println(ModdedBuild.sweepVariants(a[2], null, new File(a[3]), new File(a[4]), new File(a[5]))); break;
            case "variantProblem": System.out.println(ModdedBuild.variantProblem(root)); break;
            case "contributing": {   // contributing <modsRoot> <title> <the pack being started>
                List<ModStore.Mod> mods = ModStore.list(root, a[2]);
                boolean variant = ModStore.playsVariant(a[2], ModStore.enabledCodeMods(mods), new File(a[3]));
                StringBuilder b = new StringBuilder(variant ? "variant:" : "normal:");
                for (ModStore.Mod m : ModStore.contributing(mods, variant)) b.append(m.dir.getName()).append(';');
                System.out.println(b);
                break;
            }
            case "isVariant": System.out.println(ModdedBuild.isVariantPack(root) + " " + ModdedBuild.normalPackBeside(root)); break;
            case "mark": ModdedBuild.markVariant(root, a[2]); break;
            case "auto": {   // auto <unused> <buildable> <ready> <failed> <building> <busy>
                boolean[] b = new boolean[5];
                for (int i = 0; i < 5; i++) b[i] = a[2 + i].equals("1");
                System.out.println(ModdedBuild.autoBuild(b[0], b[1], b[2], b[3], b[4]));
                break;
            }
            case "import": {   // import <staging> <modsRoot> <title> <name> [main's build ID]
                Map<String, String> builds = new HashMap<>();
                if (a.length > 5) builds.put("main", a[5]);
                ModImporter.Result r = ModImporter.importFrom(root, new File(a[2]), a[3], a[4], builds, null);
                System.out.println(r.ok ? "ok: " + r.modName + " " + r.kind : "error: " + r.error);
                break;
            }
            case "unzip": {   // unzip <zip> <staging>
                try (FileInputStream in = new FileInputStream(root)) { ModImporter.extractZip(in, new File(a[2]), root.length(), null); System.out.println("ok"); }
                catch (java.io.IOException e) { System.out.println("error: " + e.getMessage()); }
                break;
            }
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModHardeningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_hardening_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModHardeningTest.java'
        runner.write_text(RUNNER)
        subprocess.run(['javac', '-d', str(cls.classes)] + [str(s) for s in SRCS] + [str(runner)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(dir=self.root, prefix='t_'))
        self.mods = self.dir / 'mods'
        self.mods.mkdir()

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModHardeningTest', *map(str, args)], check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def make(self, name, kind, enabled=True, order=0):
        self.run_cmd('make', self.mods, name, kind, str(enabled).lower(), order)

    # ---- the variant id ----
    def test_the_variant_id_does_not_follow_the_files_mods_around_a_code_mod(self):
        self.make('Lumi', 'code', order=0)
        alone = self.run_cmd('variant', self.mods, TITLE)
        shutil.rmtree(self.mods / 'Lumi')
        self.make('Textures', 'files', order=0)
        self.make('Lumi', 'code', order=1)   # the same code mod after a files mod: Mod.order 1, and the first code mod as before
        self.assertEqual(self.run_cmd('variant', self.mods, TITLE), alone)
        self.run_cmd('remove', self.mods, TITLE, 'Textures')
        self.assertEqual(self.run_cmd('variant', self.mods, TITLE), alone)

    def test_the_variant_id_follows_the_code_mods_and_their_order(self):
        self.make('A', 'code', order=0)
        self.make('B', 'code', order=1)
        ab = self.run_cmd('variant', self.mods, TITLE)
        shutil.rmtree(self.mods / 'A'); shutil.rmtree(self.mods / 'B')
        self.make('B', 'code', order=0)
        self.make('A', 'code', order=1)
        self.assertNotEqual(self.run_cmd('variant', self.mods, TITLE), ab)

    def test_reachable_ids_are_every_set_of_the_code_mods_on_or_off(self):
        self.make('A', 'code', enabled=True, order=0)
        self.make('T', 'files', order=1)
        self.make('B', 'code', enabled=False, order=2)
        ids = self.run_cmd('reachable', self.mods, TITLE).split()
        self.assertEqual(len(ids), 3)   # A, B, A and B
        self.assertIn(self.run_cmd('variant', self.mods, TITLE), ids)

    # ---- the builds a deleted mod leaves ----
    def variant_folders(self, vid):
        packs, build, home = self.dir / 'packs', self.dir / 'build' / TITLE, self.dir / 'home'
        h = vid.split('-mod-')[1]
        made = [packs / vid, build / f'mod-{h}' / 'pack', home / f'{vid}-work']
        for d in made:
            d.mkdir(parents=True, exist_ok=True); (d / 'pack.tsv').write_text('x')
        return packs, build, home, made

    def test_a_deleted_mods_builds_go_and_the_others_stay(self):
        self.make('A', 'code', order=0)
        keep_id = self.run_cmd('variant', self.mods, TITLE)
        gone_id = f'{TITLE}-mod-0123456789ab'
        packs, build, home, kept = self.variant_folders(keep_id)
        _, _, _, gone = self.variant_folders(gone_id)
        others = [packs / TITLE, packs / '0100000000010000-mod-0123456789ab', build / 'pack', build / 'mods', home / f'{TITLE}-work', build / 'mod-notes']
        for d in others: d.mkdir(parents=True, exist_ok=True); (d / 'f').write_text('x')
        self.assertEqual(self.run_cmd('sweepVariants', self.mods, TITLE, packs, build, home), '1')
        for d in kept + others: self.assertTrue(d.is_dir(), d)
        for d in gone: self.assertFalse(d.exists(), d)
        self.assertFalse((build / 'mod-0123456789ab').exists())

    def test_nothing_goes_when_the_mods_are_not_known(self):
        packs, build, home, made = self.variant_folders(f'{TITLE}-mod-0123456789ab')
        self.assertEqual(self.run_cmd('sweepUnknown', self.mods, TITLE, packs, build, home), '0')
        for d in made: self.assertTrue(d.is_dir(), d)

    # ---- a variant's pack that cannot be started ----
    def pack(self, modules='main subsdk1 sdk'):
        p = self.dir / 'pack'
        p.mkdir()
        (p / 'pack.tsv').write_text(f'format\t1\nmodules\t{modules}\nbuild\tmain={BUILD}\nmod_build\tsubsdk1=00\n')
        for m in modules.split():
            (p / f'{m}.patched.bin').write_bytes(b'x'); (p / f'{m}.android.key').write_text('k\n')
        return p

    def test_a_finished_variant_pack_can_be_played(self):
        self.assertEqual(self.run_cmd('variantProblem', self.pack()), 'null')

    def test_no_pack_is_not_built(self):
        self.assertEqual(self.run_cmd('variantProblem', self.dir / 'none'), 'not built')

    def test_a_rebuild_stopped_part_way_is_not_played(self):
        p = self.pack()
        (p / 'subsdk1.android.key').unlink()   # pack_android.py rewrite: the key goes before the module is rewritten
        self.assertIn('subsdk1', self.run_cmd('variantProblem', p))

    # ---- the build that starts by itself ----
    def auto(self, buildable=1, ready=0, failed=0, building=0, busy=0):
        return self.run_cmd('auto', self.dir, buildable, ready, failed, building, busy)

    def test_a_build_that_is_due_starts_by_itself_or_waits(self):
        self.assertEqual(self.auto(), 'start')
        self.assertEqual(self.auto(busy=1), 'wait')   # another build, an install or a delete goes on

    def test_no_build_by_itself_without_need_or_after_a_failure(self):
        self.assertEqual(self.auto(buildable=0), 'none')   # no code mod on, a refusal, nothing to build, the game not built
        self.assertEqual(self.auto(buildable=0, busy=1), 'none')
        self.assertEqual(self.auto(ready=1), 'none')
        self.assertEqual(self.auto(failed=1), 'none')      # no loop: a failed build waits for a tap or another set of mods
        self.assertEqual(self.auto(failed=1, busy=1), 'none')
        self.assertEqual(self.auto(building=1, busy=1), 'none')

    def test_a_mod_is_on_when_it_is_installed(self):
        staging = self.dir / 'staging'
        (staging / 'exefs').mkdir(parents=True); (staging / 'exefs' / 'main').write_bytes(b'x')
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'Code Mod'), 'ok: Code Mod code')
        self.assertIn('"enabled": true', (self.mods / 'Code Mod' / 'mod.json').read_text())
        self.assertRegex(self.run_cmd('variant', self.mods, TITLE), TITLE + '-mod-[0-9a-f]{12}')   # on: its variant is the one a start of the game asks for

    # ---- a code mod's files and cheats only with its build ----
    def test_a_code_mod_gives_a_start_of_the_games_own_pack_nothing(self):
        self.make('Textures', 'files', order=0)
        self.make('Lumi', 'code', order=1)
        self.make('Off', 'files', enabled=False, order=2)
        vid = self.run_cmd('variant', self.mods, TITLE)
        h = vid.split('-mod-')[1]
        build = self.dir / 'build' / TITLE
        for normal in (build / 'pack', self.dir / 'packs' / TITLE, build / 'mod-0123456789ab' / 'pack', self.dir / 'packs' / f'{TITLE}-mod-0123456789ab'):
            normal.mkdir(parents=True)   # the game's own pack, and the variant of another set of mods: neither runs this mod's code
            self.assertEqual(self.run_cmd('contributing', self.mods, TITLE, normal), 'normal:Textures;', normal)
        for variant in (build / f'mod-{h}' / 'pack', self.dir / 'packs' / vid):
            variant.mkdir(parents=True)
            self.assertEqual(self.run_cmd('contributing', self.mods, TITLE, variant), 'variant:Textures;Lumi;', variant)
        moved = self.dir / 'elsewhere'
        moved.mkdir()
        self.assertEqual(self.run_cmd('contributing', self.mods, TITLE, moved), 'normal:Textures;')
        self.run_cmd('mark', moved, vid)   # known by its mark wherever it lies
        self.assertEqual(self.run_cmd('contributing', self.mods, TITLE, moved), 'variant:Textures;Lumi;')

    def test_no_code_mod_on_is_never_a_variant_start(self):
        self.make('Textures', 'files', order=0)
        self.make('Lumi', 'code', enabled=False, order=1)
        p = self.dir / 'packs' / f'{TITLE}-mod-0123456789ab'
        p.mkdir(parents=True)
        self.assertEqual(self.run_cmd('contributing', self.mods, TITLE, p), 'normal:Textures;')

    # ---- a variant's pack is never the game's own ----
    def test_a_variant_pack_is_known_by_its_place_or_its_mark_and_the_games_pack_is_beside_it(self):
        build, packs = self.dir / 'build' / TITLE, self.dir / 'packs'
        for d in (build / 'pack', packs / TITLE, build / 'mod-notes' / 'pack', self.dir / 'pack'):
            d.mkdir(parents=True)
            self.assertEqual(self.run_cmd('isVariant', d).split()[0], 'false', d)
        v1, v2 = build / 'mod-2f47dc620ed9' / 'pack', packs / f'{TITLE}-mod-2f47dc620ed9'
        v1.mkdir(parents=True); v2.mkdir(parents=True)
        self.assertEqual(self.run_cmd('isVariant', v1), f'true {build / "pack"}')
        self.assertEqual(self.run_cmd('isVariant', v2), f'true {packs / TITLE}')
        moved = self.dir / 'pack'
        self.run_cmd('mark', moved, f'{TITLE}-mod-2f47dc620ed9')
        self.assertEqual(self.run_cmd('isVariant', moved), 'true null')

    # ---- what an interrupted install or delete leaves ----
    def test_a_half_installed_mod_and_a_half_deleted_one_are_swept_and_a_mod_is_not(self):
        self.make('Good', 'files')
        half = self.mods / 'Half'
        (half / 'romfs').mkdir(parents=True); (half / '.installing').write_text('')
        gone = self.mods / '.deleting-Old-1'
        (gone / 'romfs').mkdir(parents=True); (gone / 'mod.json').write_text('{"name": "Old", "enabled": true}')
        self.assertEqual(self.run_cmd('list', self.mods, TITLE), 'Good;')
        self.run_cmd('sweepDeleted', self.mods, TITLE)
        self.assertEqual(sorted(p.name for p in self.mods.iterdir()), ['Good'])

    def test_an_installed_mod_has_no_mark_left(self):
        staging = self.dir / 'staging'
        (staging / 'romfs').mkdir(parents=True); (staging / 'romfs' / 'a.bin').write_bytes(b'x')
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'My Mod'), 'ok: My Mod files')
        self.assertEqual(sorted(p.name for p in (self.mods / 'My Mod').iterdir()), ['mod.json', 'romfs'])

    def test_a_mods_folder_never_gets_a_dot_name(self):
        self.assertEqual(self.run_cmd('freeName', self.mods, TITLE, '.deleting-x'), 'deleting-x')

    # ---- the install: patches ----
    def patches(self, rel, stem=BUILD, ext='.ips'):
        staging = self.dir / 'staging'
        f = staging / rel / f'{stem}{ext}'
        f.parent.mkdir(parents=True); f.write_bytes(b'PATCHEOF')
        return staging

    def test_an_atmosphere_folder_of_patches_alone_is_a_code_mod(self):
        staging = self.patches('atmosphere/exefs_patches/60fps')
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'Sixty', BUILD), 'ok: Sixty code')
        self.assertTrue((self.mods / 'Sixty' / 'exefs_patches' / '60fps' / f'{BUILD}.ips').is_file())

    def test_ips32_patches_are_installed_and_checked(self):
        staging = self.patches('exefs_patches/big', ext='.ips32')
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'Big', BUILD), 'ok: Big code')
        self.assertTrue((self.mods / 'Big' / 'exefs_patches' / 'big' / f'{BUILD}.ips32').is_file())
        other = self.dir / 'other'
        shutil.copytree(staging, other)
        (other / 'exefs_patches/big' / f'{BUILD}.ips32').rename(other / 'exefs_patches/big' / ('AB' * 20 + '.ips32'))
        self.assertTrue(self.run_cmd('import', other, self.mods, TITLE, 'Other', BUILD).startswith('error: This mod is for another game'))
        self.assertFalse((self.mods / 'Other').exists())

    def test_a_patch_named_by_the_first_16_digits_is_this_games(self):
        staging = self.patches('exefs_patches/short', stem=BUILD[:16])
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'Short', BUILD), 'ok: Short code')

    def test_a_patch_named_by_32_digits_of_a_longer_id_is_this_games(self):
        staging = self.patches('exefs', stem=BUILD[:32].lower())   # the emulators' layout: the patch in exefs/
        self.assertEqual(self.run_cmd('import', staging, self.mods, TITLE, 'Emu', BUILD), 'ok: Emu code')

    # ---- a zip's entry names ----
    def unzip(self, names):
        z = self.dir / 'mod.zip'
        with zipfile.ZipFile(z, 'w') as f:
            for n in names: f.writestr(zipfile.ZipInfo(n), b'data')
        out = self.dir / 'out'
        out.mkdir()
        return self.run_cmd('unzip', z, out), out

    def test_a_zip_entry_that_leaves_the_folder_is_refused(self):
        for names in (['romfs/a.bin', '../evil.bin'], ['romfs/../../evil.bin'], ['romfs\\..\\..\\evil.bin']):
            with self.subTest(names=names):
                shutil.rmtree(self.dir / 'out', ignore_errors=True)
                res, out = self.unzip(names)
                self.assertTrue(res.startswith('error: the zip has an unsafe entry name'), res)
                self.assertFalse((self.dir / 'evil.bin').exists())
                self.assertFalse((self.dir.parent / 'evil.bin').exists())

    def test_an_absolute_entry_lands_inside_and_two_dots_in_a_name_are_a_name(self):
        res, out = self.unzip(['/romfs/abs.bin', 'romfs/notes..txt', 'romfs\\Data\\win.bin'])
        self.assertEqual(res, 'ok')
        for rel in ('romfs/abs.bin', 'romfs/notes..txt', 'romfs/Data/win.bin'): self.assertTrue((out / rel).is_file(), rel)


if __name__ == '__main__':
    unittest.main()
