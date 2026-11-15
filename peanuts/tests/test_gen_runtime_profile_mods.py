"""dm/peanuts/profile/gen_runtime_profile.py's profile-modules addition (DESIGN-MODS.md "Code injection"): a modded variant build's own module list
baked into profile.bin instead of game.tsv's, same fixed-size schema. Uses a real profile (Pokemon Brilliant Diamond, 0100000011D90000 - the task's
own target title) so this exercises the actual encoder, not a mock."""
from pathlib import Path
import sys
import unittest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm/games'))
sys.path.insert(0, str(REPO / 'dm/peanuts/profile'))
import gen_runtime_profile as g   # noqa: E402
import game as gamepy   # noqa: E402

TITLE_ID = '0100000011D90000'


@unittest.skipUnless((REPO / 'dm/games' / TITLE_ID / 'game.tsv').is_file(), 'no profile for ' + TITLE_ID)
class ProfileModules(unittest.TestCase):
    def setUp(self):
        self.g = gamepy.load(TITLE_ID)

    def test_same_size_as_the_normal_profile(self):
        normal = g.build_profile_bytes(self.g)
        modded = g.build_profile_bytes(self.g, modules_override=['main', 'subsdk0', 'sdk', 'subsdk9'])
        self.assertEqual(len(normal), len(modded))

    def test_differs_from_the_normal_profile_when_a_module_is_added(self):
        normal = g.build_profile_bytes(self.g)
        modded = g.build_profile_bytes(self.g, modules_override=['main', 'subsdk0', 'sdk', 'subsdk9'])
        self.assertNotEqual(normal, modded)

    def test_added_module_name_appears_in_the_bytes(self):
        # not a full profile_rt.c decode (that is the host's own job, dm/peanuts/profile/test/); just confirms the new name is actually in the
        # modules table's bytes, nul-terminated the way profile_rt.h's char[32] slots are
        modded = g.build_profile_bytes(self.g, modules_override=['main', 'subsdk0', 'sdk', 'subsdk9'])
        self.assertIn(b'subsdk9\x00', modded)

    def test_cli_profile_modules_matches_the_library_call(self):
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'out.bin'
            r = subprocess.run([sys.executable, str(REPO / 'dm/peanuts/profile/gen_runtime_profile.py'), 'profile-modules', TITLE_ID, str(out),
                                 'main,subsdk0,sdk,subsdk9'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            via_cli = out.read_bytes()
        via_lib = g.build_profile_bytes(self.g, modules_override=['main', 'subsdk0', 'sdk', 'subsdk9'])
        self.assertEqual(via_cli, via_lib)

    def test_empty_override_list_is_zero_modules(self):
        modded = g.build_profile_bytes(self.g, modules_override=[])
        normal = g.build_profile_bytes(self.g)
        self.assertEqual(len(modded), len(normal))
        self.assertNotEqual(modded, normal)


if __name__ == '__main__':
    unittest.main()
