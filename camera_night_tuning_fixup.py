# SPDX-License-Identifier: Apache-2.0
"""Route OVX8000 Night post-processing to its existing stock Chromatix profile.

Global 306 selects sensor5/snapshot/Feature1=0/Feature2=106, but both OVX8000
tables only provide that post-fusion profile under Feature1=37. Add one v5
mode-link entry, just like the existing HDR/Auto-Night links. No coefficients,
module headers, symbol references or existing mode entries change. In
particular, pre-fusion65, sensor FPS policy and S5KHPE tables stay untouched.

Only the two complete, version-pinned 306 files are accepted. The insertion
extends the mode section by 24 bytes; adjust the data-section offset, total
size and trailing CRC32. Already-patched input must normalize to exact stock.
"""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import struct
import zlib


@dataclass(frozen=True)
class Profile:
    sha256: str
    size: int
    modes: int
    parent: int
    destination: int


PROFILES = {
    'odm/lib64/camera/com.qti.tuned.flourite_sunny_ovx8000_wide_gl_ii.bin': Profile(
        '7071a4c145f9afc1a6a06ccd6d5a3c93f0ab5bdd54b23e9d1b019a5ebb62e095',
        20938944, 15105, 7429, 7733),
    'odm/lib64/camera/com.qti.tuned.flourite_ofilm_ovx8000_wide_gl_i.bin': Profile(
        '418de816436d4612dbcd8dfc74b3464cd37f941a610f1d7b15ca42918ec9aeed',
        12521321, 9538, 5301, 5434),
}
MODE_RECORD = struct.Struct('<IHHIIII')


def _layout(data):
    if (len(data) < 216 or data[:20] != b'QTI Chromatix Header'
            or struct.unpack_from('<I', data, 0x20)[0] != 5
            or struct.unpack_from('<I', data, 0x1c)[0] != len(data) - 4
            or struct.unpack_from('<II', data, 0xa0) != (0xa8, 4)):
        raise ValueError('Unexpected Chromatix v5 layout')
    if zlib.crc32(data[:-4]) != struct.unpack_from('<I', data, len(data)-4)[0]:
        raise ValueError('Invalid Chromatix CRC32')
    sections = {}
    end = 216
    for index, expected in enumerate((0, 3, 2, 1)):
        entry = 0xa8 + index * 12
        kind, offset, size = struct.unpack_from('<III', data, entry)
        if kind != expected or offset != end or offset + size > len(data)-4:
            raise ValueError('Unexpected Chromatix section layout')
        sections[kind] = (entry, offset, size)
        end = offset + size
    if end != len(data)-4:
        raise ValueError('Unexpected Chromatix trailing data')
    _, offset, size = sections[2]
    if size < 32 or (size-32) % MODE_RECORD.size or data[offset:offset+32] != b'Default' + bytes(25):
        raise ValueError('Unexpected Chromatix mode table')
    return sections


def _alias(profile):
    # id, mode=Feature2, submode=NightPost, group, parent, link, flags
    return MODE_RECORD.pack(profile.modes, 4, 106, 0, profile.parent, profile.destination, 0)


def _resize(data, profile, *, remove=False):
    sections = _layout(data)
    mode_entry, mode_offset, mode_size = sections[2]
    data_entry, data_offset, _ = sections[1]
    expected_count = profile.modes + int(remove)
    if (mode_size-32) // MODE_RECORD.size != expected_count:
        raise ValueError('Unexpected Chromatix mode count')
    alias = _alias(profile)
    if remove:
        if data[data_offset-24:data_offset] != alias:
            raise ValueError('Unexpected Night mode alias')
        result = bytearray(data[:data_offset-24] + data[data_offset:-4])
        delta = -24
    else:
        result = bytearray(data[:data_offset] + alias + data[data_offset:-4])
        delta = 24
    struct.pack_into('<I', result, 0x1c, len(result))
    struct.pack_into('<I', result, mode_entry+8, mode_size+delta)
    struct.pack_into('<I', result, data_entry+4, data_offset+delta)
    result.extend(struct.pack('<I', zlib.crc32(result)))
    _layout(result)
    return bytes(result)


def patched_night_tuning(data, target):
    if target not in PROFILES:
        raise ValueError('Night tuning fix is restricted to the two audited OVX8000 tables')
    profile = PROFILES[target]
    if len(data) == profile.size + 24:
        stock = _resize(data, profile, remove=True)
    elif len(data) == profile.size:
        stock = data
    else:
        raise ValueError('Unknown tuning size; re-audit before patching')
    if hashlib.sha256(stock).hexdigest() != profile.sha256:
        raise ValueError('Unknown OVX8000 tuning; re-audit before patching')
    return _resize(stock, profile)


def fixup_camera_night_tuning(ctx, file, file_path, *args, **kwargs):
    if file.dst not in PROFILES:
        raise ValueError('Wrong Night tuning extraction target')
    path = Path(file_path)
    original = path.read_bytes()
    result = patched_night_tuning(original, file.dst)
    if result != original:
        path.write_bytes(result)
