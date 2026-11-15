# PEANUTS: new file (code mods: IPS/IPS32 before the rewrite, DESIGN-MODS.md)
"""IPS and IPS32 patch application, Atmosphère's exefs_patches semantics: a patch's offsets count from the start of the
module's NSO *file* as it would be uncompressed, that is its 0x100-byte header first and then the decompressed memory
image (text at file offset 0x100). Atmosphère's loader subtracts the header's size from every record and leaves out
whatever of a record lies inside the header (ldr_patcher.cpp, ApplyIpsPatch with a protected size of sizeof(NsoHeader));
apply() does the same when it is given header=NSO_HEADER, and patch_ips.py always gives it. Measured on Luminescent
Platinum's patch for Pokemon Brilliant Diamond 1.3.0: with the 0x100 taken off its 106 branches stand on function
entries and existing call sites of main, without it they land in the middle of functions. With header=0 (the default)
an offset is an index into the data as it is, the plain IPS meaning. Atmosphère picks the patches of a module by its
build ID (the patch file's own name, see patch_ips.py); this file only implements the patch format itself, with no
notion of a build ID or a module name.

IPS (the classic format):
  5-byte magic b'PATCH', then records until the 3-byte end marker b'EOF':
    offset (3 bytes, big-endian, 0..0xFFFFFF) size (2 bytes, big-endian)
      size != 0: size literal bytes to place at offset
      size == 0: an RLE record — count (2 bytes, big-endian) value (1 byte), meaning count copies of value at offset
IPS32 (Atmosphère's own extension, for a module whose patched offset would not fit in IPS's 3-byte/16 MiB limit):
  5-byte magic b'IPS32', the same records with a 4-byte offset, ended by the 4-byte marker b'EEOF'.

A record may extend past the current end of the data (Atmosphère's own loader, and this module, both grow the
buffer with zero bytes up to the write — the common case for a mod that is only replacing bytes already in the
image does not do this; growth support exists for the encoding, not because this project encourages shipping a
larger main).
"""
from dataclasses import dataclass


class Error(ValueError):
    pass


@dataclass(frozen=True)
class Record:
    offset: int
    data: bytes   # already expanded: an RLE record becomes `count` copies of `value` here


def _records(buf, off_size, magic, end):
    if buf[:len(magic)] != magic:
        raise Error(f'not an IPS patch (expected {magic!r}, got {bytes(buf[:len(magic)])!r})')
    i = len(magic)
    n = len(buf)
    out = []
    while True:
        if buf[i:i + len(end)] == end and i + len(end) <= n:
            return out
        if i + off_size + 2 > n:
            raise Error(f'truncated record at offset {i:#x} (the file has no {end!r} end marker)')
        offset = int.from_bytes(buf[i:i + off_size], 'big')
        i += off_size
        size = int.from_bytes(buf[i:i + 2], 'big')
        i += 2
        if size == 0:
            if i + 3 > n:
                raise Error(f'truncated RLE record at offset {i:#x}')
            count = int.from_bytes(buf[i:i + 2], 'big')
            value = buf[i + 2]
            i += 3
            if count == 0:
                raise Error(f'RLE record at patch offset {offset:#x} has a zero count')
            out.append(Record(offset, bytes([value]) * count))
        else:
            if i + size > n:
                raise Error(f'record at patch offset {offset:#x} claims {size} bytes, past the end of the file')
            out.append(Record(offset, bytes(buf[i:i + size])))
            i += size


def parse(data):
    """The records of an IPS or IPS32 file (bytes-like), in file order. Error (a ValueError) on anything malformed;
    never partially applied — a caller gets every record or none."""
    buf = bytes(data)
    if buf[:5] == b'IPS32':
        return _records(buf, 4, b'IPS32', b'EEOF')
    if buf[:5] == b'PATCH':
        return _records(buf, 3, b'PATCH', b'EOF')
    raise Error(f"not an IPS or IPS32 patch (starts with {bytes(buf[:5])!r}, expected b'PATCH' or b'IPS32')")


def is_ips(data):
    """Whether data starts with an IPS or IPS32 magic (a quick filter before parse(), which still re-checks)"""
    buf = bytes(data[:5])
    return buf[:5] == b'IPS32' or buf[:5] == b'PATCH'


NSO_HEADER = 0x100   # sizeof(NsoHeader): what an exefs patch's offsets count in before the module's image


def apply(data, patch, *, max_size=None, header=0):
    """data (bytes-like) with every record of patch (an IPS/IPS32 file's bytes, or an already-parsed list of Record)
    written in file order — a later record overwriting an earlier one's bytes, as Atmosphère applies them in file
    order with no coalescing. Returns a new bytearray; data is not modified. header: the bytes a record's offset counts
    before data starts (NSO_HEADER for a module's image); a record that lies in them is left out, one that starts in
    them loses that part, as on the console. The buffer grows (zero-filled) when a record reaches past its current
    end. max_size, when given, is the hard cap this call refuses to grow past (the caller's segment size): a record
    that would grow the buffer beyond it is an Error, not a silent truncation."""
    out = bytearray(data)
    records = patch if isinstance(patch, list) else parse(patch)
    for r in records:
        at, d = r.offset - header, r.data
        if at < 0: at, d = 0, d[-at:]   # the part inside the file header: never written (d is empty when all of the record is there)
        if not d: continue
        end = at + len(d)
        if max_size is not None and end > max_size:
            raise Error(f'record at offset {r.offset:#x} (len {len(r.data)}) reaches {end:#x}, past the {max_size:#x}-byte limit')
        if end > len(out):
            out.extend(bytes(end - len(out)))
        out[at:end] = d
    return out


def build(records, *, ips32=None):
    """The inverse of parse(): an IPS or IPS32 file's bytes for records (a list of Record, or (offset, data) pairs).
    ips32 picks the format; None chooses it from whether any offset needs more than 3 bytes (0xFFFFFF). Used only by
    this module's own tests (there is no production need to author patches, only to apply ones a mod ships) —
    kept small and literal (no RLE emission; an all-identical-byte run is written out verbatim), which is also
    exactly what every real IPS/IPS32 file in the wild does for anything but long runs of one repeated byte."""
    recs = [r if isinstance(r, Record) else Record(r[0], bytes(r[1])) for r in records]
    if ips32 is None:
        ips32 = any(r.offset > 0xFFFFFF or r.offset + len(r.data) > 0xFFFFFF for r in recs)
    out = bytearray(b'IPS32' if ips32 else b'PATCH')
    off_size = 4 if ips32 else 3
    for r in recs:
        if not r.data:
            raise Error('a record with no bytes cannot be encoded (nothing to write)')
        if len(r.data) >= 0x10000:
            raise Error(f'record at offset {r.offset:#x} is {len(r.data)} bytes; IPS/IPS32 records are at most 0xFFFF')
        out += r.offset.to_bytes(off_size, 'big')
        out += len(r.data).to_bytes(2, 'big')
        out += r.data
    out += b'EEOF' if ips32 else b'EOF'
    return bytes(out)
