#!/usr/bin/env python3
"""comp_check.py <out dir>: decompresses every block comp_test dumped with the reference LZ4 (pip: lz4) and compares with the importer's bytes."""
import sys, lz4.block
d = sys.argv[1]; bad = 0; n = 0
for line in open(f"{d}/entries.txt"):
    k, idx, virt, vs, psize = line.split()
    raw = open(f"{d}/e{k}.lz4", "rb").read(); mine = open(f"{d}/e{k}.dec", "rb").read()
    assert len(raw) == int(psize) and len(mine) == int(vs)
    ref = lz4.block.decompress(raw, uncompressed_size=int(vs))
    ok = ref == mine; n += 1; bad += not ok
    print(f"e{k}: entry {idx} {psize} -> {vs} bytes: {'same as lz4.block' if ok else 'DIFFERENT'}")
print(f"{n} blocks, {bad} differ"); sys.exit(1 if bad else 0)
