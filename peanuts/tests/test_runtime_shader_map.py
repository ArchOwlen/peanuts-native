"""Runtime shader detection reads the APK tools archive without loading PackNative/CPython."""
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/build/RuntimeShaderMap.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class RuntimeShaderMap(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='runtime_shader_map_')
        cls.root = Path(cls.tmp.name)
        runner = cls.root / 'RuntimeShaderMapTest.java'
        runner.write_text('''package dev.peanuts.build;
import java.io.*;
public final class RuntimeShaderMapTest {
    public static void main(String[] args) throws Exception {
        try {
            System.out.println(RuntimeShaderMap.compilesAtRunTime(new FileInputStream(args[0]), args[1]));
        } catch (IOException e) {
            System.out.println("error: " + e.getMessage());
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.root), str(SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def detect(self, entries, title):
        archive = self.root / 'tools.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, text in entries.items():
                z.writestr(name, text)
        r = subprocess.run(['java', '-cp', str(self.root), 'dev.peanuts.build.RuntimeShaderMapTest', str(archive), title],
                           check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def test_shipped_profiles(self):
        titles = {'010055D009F78000': 'true', '01008CF01BAAC000': 'false', '0100152000022000': 'false'}
        entries = {f'dm/games/{title}/shader_map.tsv': (REPO / f'dm/games/{title}/shader_map.tsv').read_text()
                   for title in titles}
        for title, expected in titles.items():
            with self.subTest(title=title):
                self.assertEqual(self.detect(entries, title), expected)

    def test_only_target_profile_and_source_column_count(self):
        entries = {'dm/games/other/shader_map.tsv': 'vs_runtime.bin\t0\tvertex\t1234\t/glslc_capture/one.glslc\n',
                   'dm/games/target/shader_map.tsv': '# /glslc_capture/ignored.glslc\nvs_runtime.bin\t0\tvertex\t1234\tmain\n'}
        self.assertEqual(self.detect(entries, 'target'), 'false')

    def test_missing_profile_is_an_error(self):
        self.assertTrue(self.detect({'README': 'no profiles'}, 'target').startswith('error: '))


if __name__ == '__main__':
    unittest.main()
