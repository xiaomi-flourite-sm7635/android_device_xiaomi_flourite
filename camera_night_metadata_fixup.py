# SPDX-License-Identifier: Apache-2.0
"""Keep processed Night RAW metadata consistent with the stock ArcSoft output.

Flourite 306 ArcSoft removes the RAW black pedestal and applies lens shading.
The stock plugin's processUpdateLscBls() publishes zero dynamic black level and
unity LSC only for operation mode 0x900b, not the exposed Night mode 0x800a.
Reuse that existing callback for successful 0x800a processing too. Do not change
sensor/static metadata, Bayer pattern, AWB, the algorithm, or fallback frames.

This is a version-pinned AArch64 branch extension, not an ELF symbol rewrite.
The 40-byte leaf predicate occupies verified zero padding following .plt; grow
only the existing RX segment to cover it. No segment moves, new dependencies,
packed relocation rewrites, register saves, stack changes or indirect branches.
"""
import hashlib
from pathlib import Path
import struct

TARGET = 'odm/lib64/camera/plugins/com.xiaomi.plugin.arcsoftsll.so'
STOCK_SHA256 = '99dfb2949d47fe5a72e89188cf7d8717a685e93b6aa3e1c6573d9aa50a1b3db2'
GATE = 0x1D19C
BODY = 0x1D1A0
EXIT = 0x1D2C4
STUB = 0x48F08
RX_HEADER = 0xB0
RX_SIZE = 0x2DF08
ORIGINAL_GATE = bytes.fromhex('41090054')  # b.ne EXIT after cmp w8, w9 (0x900b)


def _branch(pc, target, condition=None):
    delta = target-pc
    if delta % 4:
        raise ValueError('Unaligned Night metadata branch')
    displacement = delta // 4
    bits = 26 if condition is None else 19
    if not -(1 << (bits-1)) <= displacement < (1 << (bits-1)):
        raise ValueError('Night metadata branch out of range')
    value = displacement & ((1 << bits)-1)
    return (0x14000000 | value) if condition is None else (0x54000000 | value << 5 | condition)


def _stub():
    # x0=this, w8=operationMode, Z=(operationMode==0x900b) on entry.
    # x8/x9 are caller-saved scratch registers, dead at both original targets.
    words = (
        _branch(STUB, BODY, 0),                  # b.eq BODY: stock 0x900b unchanged
        0x52900149,                             # mov w9, #0x800a
        0x6B09011F,                             # cmp w8, w9
        _branch(STUB+12, EXIT, 1),              # b.ne EXIT: every other mode unchanged
        0x91403409,                             # add x9, x0, #0xd, lsl #12
        0xB94D4128,                             # ldr w8, [x9, #0xd40]: m_procRet at 0xdd40
        0x35000008 | (((EXIT-(STUB+24))//4 & 0x7FFFF) << 5),  # cbnz w8, EXIT
        0xF9400308,                             # ldr x8, [x24]: output metadata callback
        0xB4000008 | (((EXIT-(STUB+32))//4 & 0x7FFFF) << 5),  # cbz x8, EXIT
        _branch(STUB+36, BODY),                 # success: existing output-only callback
    )
    return struct.pack('<10I', *words)


STUB_CODE = _stub()
PATCHES = (
    (GATE, ORIGINAL_GATE, struct.pack('<I', _branch(GATE, STUB))),
    (STUB, bytes(len(STUB_CODE)), STUB_CODE),
    (RX_HEADER+32, struct.pack('<Q', RX_SIZE), struct.pack('<Q', RX_SIZE+len(STUB_CODE))),
    (RX_HEADER+40, struct.pack('<Q', RX_SIZE), struct.pack('<Q', RX_SIZE+len(STUB_CODE))),
)


def patched_night_metadata(data):
    normalized = bytearray(data)
    for offset, old, new in PATCHES:
        if normalized[offset:offset+len(old)] not in (old, new):
            raise ValueError(f'Unexpected Night metadata patch bytes at {offset:#x}')
        normalized[offset:offset+len(old)] = old
    if hashlib.sha256(normalized).hexdigest() != STOCK_SHA256:
        raise ValueError('Unknown ArcSoft plugin; re-audit Night metadata before patching')

    # Full-file pinning also protects symbol semantics, object field offsets,
    # callback ABI, existing BTI/PAC/unwind data and all ELF tables.
    header = struct.unpack_from('<II6Q', normalized, RX_HEADER)
    if header != (1, 5, 0x1B000, 0x1B000, 0x1B000, RX_SIZE, RX_SIZE, 0x1000):
        raise ValueError('Unexpected ArcSoft RX segment')
    next_load = struct.unpack_from('<II6Q', normalized, RX_HEADER+56)
    if next_load[0] != 1 or STUB+len(STUB_CODE) > next_load[2]:
        raise ValueError('Night metadata stub would overlap the next segment')
    result = bytearray(normalized)
    for offset, old, new in PATCHES:
        result[offset:offset+len(old)] = new
    return bytes(result)


def fixup_camera_night_metadata(ctx, file, file_path, *args, **kwargs):
    if file.dst != TARGET:
        raise ValueError('Night metadata fix is restricted to the audited ArcSoft plugin')
    path = Path(file_path)
    original = path.read_bytes()
    result = patched_night_metadata(original)
    if result != original:
        path.write_bytes(result)
