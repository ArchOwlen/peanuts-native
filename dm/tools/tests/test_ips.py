# PEANUTS: new file (code mods: IPS/IPS32 before the rewrite, DESIGN-MODS.md)
"""dm/tools/ips.py: IPS and IPS32 patch parsing/application (Atmosphère's exefs_patches format and offset
convention), independent of any NSO/module reading — see test_patch_ips.py for the NSO-specific layer."""
from pathlib import Path
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import ips


class Build(unittest.TestCase):
    def test_round_trip_ips(self):
        data = ips.build([(0, b'AB'), (10, b'xyz')])
        self.assertEqual(data[:5], b'PATCH')
        self.assertTrue(data.endswith(b'EOF'))
        recs = ips.parse(data)
        self.assertEqual([(r.offset, r.data) for r in recs], [(0, b'AB'), (10, b'xyz')])

    def test_round_trip_ips32(self):
        data = ips.build([(0x1000000, b'Z')], ips32=True)
        self.assertEqual(data[:5], b'IPS32')
        self.assertTrue(data.endswith(b'EEOF'))
        self.assertEqual(ips.parse(data), [ips.Record(0x1000000, b'Z')])

    def test_ips32_chosen_automatically_past_the_3_byte_limit(self):
        data = ips.build([(0x1000000, b'Z')])   # past 0xFFFFFF: IPS cannot encode this offset
        self.assertEqual(data[:5], b'IPS32')

    def test_record_too_long_is_refused(self):
        with self.assertRaises(ips.Error): ips.build([(0, bytes(0x10000))])

    def test_empty_record_is_refused(self):
        with self.assertRaises(ips.Error): ips.build([(0, b'')])


class Parse(unittest.TestCase):
    def test_wrong_magic(self):
        with self.assertRaises(ips.Error): ips.parse(b'NOPE' + b'EOF')

    def test_truncated_record(self):
        with self.assertRaises(ips.Error): ips.parse(b'PATCH\x00\x00\x00\x00')   # offset only, no size, no EOF

    def test_record_past_end_of_file(self):
        data = bytearray(b'PATCH' + b'\x00\x00\x00' + b'\x00\x05' + b'AB')   # claims 5 bytes, has 2
        data += b'EOF'
        with self.assertRaises(ips.Error): ips.parse(bytes(data))

    def test_rle_record(self):
        data = b'PATCH' + b'\x00\x00\x02' + b'\x00\x00' + b'\x00\x04' + b'\x7f' + b'EOF'   # offset 2, RLE 4x 0x7f
        self.assertEqual(ips.parse(data), [ips.Record(2, b'\x7f\x7f\x7f\x7f')])

    def test_rle_record_zero_count_is_refused(self):
        data = b'PATCH' + b'\x00\x00\x02' + b'\x00\x00' + b'\x00\x00' + b'\x00' + b'EOF'
        with self.assertRaises(ips.Error): ips.parse(data)

    def test_is_ips_quick_filter(self):
        self.assertTrue(ips.is_ips(b'PATCH...'))
        self.assertTrue(ips.is_ips(b'IPS32...'))
        self.assertFalse(ips.is_ips(b'NSO0....'))


class Apply(unittest.TestCase):
    def test_overwrite_in_place(self):
        data = b'\x00' * 16
        patch = ips.build([(4, b'\xaa\xbb')])
        out = ips.apply(data, patch)
        self.assertEqual(bytes(out), b'\x00\x00\x00\x00\xaa\xbb' + b'\x00' * 10)

    def test_later_record_wins_over_an_earlier_overlapping_one(self):
        patch = ips.build([(0, b'\x01\x01'), (1, b'\x02')])
        out = ips.apply(b'\x00\x00\x00', patch)
        self.assertEqual(bytes(out), b'\x01\x02\x00')

    def test_does_not_mutate_the_input(self):
        data = bytearray(b'\x00\x00\x00\x00')
        out = ips.apply(data, ips.build([(0, b'\xff')]))
        self.assertEqual(bytes(data), b'\x00\x00\x00\x00')
        self.assertEqual(bytes(out), b'\xff\x00\x00\x00')

    def test_grows_the_buffer_when_the_record_reaches_past_the_end(self):
        out = ips.apply(b'\x00\x00', ips.build([(4, b'\x01\x02')]))
        self.assertEqual(bytes(out), b'\x00\x00\x00\x00\x01\x02')

    def test_max_size_refuses_growth_past_the_limit(self):
        with self.assertRaises(ips.Error):
            ips.apply(b'\x00\x00', ips.build([(4, b'\x01\x02')]), max_size=4)
        out = ips.apply(b'\x00\x00\x00\x00', ips.build([(2, b'\x01\x02')]), max_size=4)   # fits exactly: allowed
        self.assertEqual(bytes(out), b'\x00\x00\x01\x02')

    def test_header_bytes_are_taken_off_and_never_written(self):
        # an exefs patch counts the NSO's 0x100-byte header (ips.NSO_HEADER): a record at 0x100 is the image's first byte
        self.assertEqual(ips.NSO_HEADER, 0x100)
        out = ips.apply(b'\x00' * 8, ips.build([(0x104, b'\xaa\xbb')]), header=0x100)
        self.assertEqual(bytes(out), b'\x00\x00\x00\x00\xaa\xbb\x00\x00')
        out = ips.apply(b'\x00' * 4, ips.build([(0x40, b'\x01\x02'), (0xFF, b'\x03\x04\x05')]), header=0x100)   # inside the header: left out; across its end: the rest
        self.assertEqual(bytes(out), b'\x04\x05\x00\x00')
        with self.assertRaises(ips.Error): ips.apply(b'\x00' * 4, ips.build([(0x103, b'\x01\x02')]), header=0x100, max_size=4)

    def test_apply_accepts_a_pre_parsed_record_list(self):
        out = ips.apply(b'\x00\x00', [ips.Record(0, b'\x9f')])
        self.assertEqual(bytes(out), b'\x9f\x00')

    def test_rle_record_applies(self):
        patch = b'PATCH' + b'\x00\x00\x00' + b'\x00\x00' + b'\x00\x03' + b'\x42' + b'EOF'
        out = ips.apply(b'\x00' * 3, patch)
        self.assertEqual(bytes(out), b'\x42\x42\x42')


if __name__ == '__main__':
    unittest.main()
