# SPDX-License-Identifier: Apache-2.0
"""Pinned GraphicBuffer allocation fixes for flourite 304 camera blobs.

Only audited operator-new size arguments are changed. A normalized .text hash
rejects different firmware and unrelated binary edits. Reapplication is safe.
Virtual addresses are translated through ELF segments, never used as file offsets.
"""

import hashlib

OLD_ALLOCATION = bytes.fromhex('00 20 80 52')  # mov w0, #0x100
NEW_ALLOCATION = bytes.fromhex('00 a6 81 52')  # mov w0, #0xd30
GRAPHIC_BUFFER_SIZE = 0xD30

# text VA, text size, allocation instruction VAs, normalized text SHA-256.
# Each site was traced through operator new to a GraphicBuffer constructor.
CAMERA_ALLOCATIONS = {
    'vendor/lib64/libmicamera_hal_core.so': (
        0x115000, 0x3706D8, (0x21ED18, 0x21F4CC),
        '51355d28d21be505593764c4c7ad51cb8a5a975a33ec9b1cde7c32fab4a5116e',
    ),
    'vendor/lib64/libcom.xiaomi.grallocutils.so': (
        0x2000, 0xA08, (0x20FC, 0x221C, 0x2478),
        'ee690966f35306b81ded8b2f8dfe4542c56e1babc9312cd3a667604bbde55048',
    ),
    'vendor/lib64/libcom.xiaomi.mawutils.so': (
        0xC000, 0x23608, (0xC388, 0xFE50),
        '5f384047997a472320ea880d5702b404914f292854281ef8b945407ff2363a2f',
    ),
    'vendor/lib64/libcom.xiaomi.mawutilsold.so': (
        0xB000, 0x15E30, (0xD648, 0x1112C),
        'cadaaa864efadcd487592f7a5706c6d474af8569209247c6cc3031c8ffab70e3',
    ),
    'odm/lib64/camera/components/com.jigan.node.videobokeh.so': (
        0xA000, 0x1D8B4, (0xCBDC, 0xCC40),
        '58097ff28ae94370b13b2bc3b83c163dc74e00cb25b9ba0c29f397632eb5c184',
    ),
    'odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so': (
        0xB000, 0x10770, (0xC470, 0xC4B8),
        '2ecd5d43ff428964f783335c10322d1a9925abe8c01205f61a7ad0cf6550bdd8',
    ),
}


def patched_text(data, spec):
    start, size, addresses, expected_hash = spec
    if len(data) != size or len(set(addresses)) != len(addresses):
        raise ValueError('Invalid GraphicBuffer patch range')
    normalized = bytearray(data)
    for address in addresses:
        offset = address - start
        if offset < 0 or offset + 4 > size or address % 4:
            raise ValueError('GraphicBuffer allocation is outside the audited text')
        if data[offset:offset + 4] not in (OLD_ALLOCATION, NEW_ALLOCATION):
            raise ValueError(f'Unexpected GraphicBuffer instruction at {address:#x}')
        normalized[offset:offset + 4] = OLD_ALLOCATION
    if hashlib.sha256(normalized).hexdigest() != expected_hash:
        raise ValueError('Camera blob text changed; re-audit before applying the allocation fix')
    for address in addresses:
        offset = address - start
        normalized[offset:offset + 4] = NEW_ALLOCATION
    return bytes(normalized)


def fixup_camera_graphicbuffer(ctx, file, file_path, *args, **kwargs):
    from extract_utils.elf_parser import ELFFile, EM

    spec = CAMERA_ALLOCATIONS[file.dst]
    start, size, addresses, _ = spec
    with open(file_path, 'rb') as stream:
        elf = ELFFile(stream)
        if elf.machine != EM.AARCH64 or elf.bits != 64:
            raise ValueError('GraphicBuffer fix requires AArch64 ELF64')
        offset, _ = elf.address_to_offset(start, size)
        if offset is None:
            raise ValueError('Camera blob text is not file-backed')
        stream.seek(offset)
        original = stream.read(size)
    replacement = patched_text(original, spec)
    # Validate every site and the complete text before writing any instruction.
    with open(file_path, 'r+b') as stream:
        for address in addresses:
            relative = address - start
            stream.seek(offset + relative)
            stream.write(replacement[relative:relative + 4])
