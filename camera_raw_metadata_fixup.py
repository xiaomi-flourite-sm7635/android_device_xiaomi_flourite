# SPDX-License-Identifier: Apache-2.0
"""Pinned, index-preserving RAW metadata import patch for the flourite core.

Do NOT use patchelf --rename-dynamic-symbols here: 0.18 reorders .dynsym but
does not remap Android APS2 packed relocations. R7 consequently redirected
1,050 unrelated relocations and crashed inside CameraMode's static ctor.
Only replace the name of an unhashed UND symbol, in its existing string slot.
"""
import hashlib
from pathlib import Path
import struct
from typing import NamedTuple

TARGET = 'vendor/lib64/libmicamera_hal_core.so'
ORIGINAL = '_ZN10MiMetadata6updateEjPKim'
REPLACEMENT = 'flourite_metadata_update_i32'
BROKEN_R7 = 'flourite_camera_metadata_update_i32'
TEXT_VA, TEXT_SIZE = 0x115000, 0x3706D8
TEXT_SHA256 = '324e470222430ef83342ed4d6e83d6de0d36550ac651db2cae1c50a384bd6f2f'
# OS3.0.304.0 stock and verified R6 have identical ordered symbol semantics
# and relocation/hash/version tables. Section ordinals/file offsets may move
# when DT_NEEDED is added; symbol identity/order/value/binding must not.
SYMBOL_LAYOUT_SHA256 = '6c4b49b8f895a121aec4984475405325b619667dd13c9adcb447e452663c8aee'
TABLE_SHA256 = {
    '.gnu.hash': '2b600e07a49a0b9757eeff5e5cb87a3d4d5a1727c38f61dfee998d97ef5fc307',
    '.gnu.version': '4dc12b3a6ad036488082e0920f5be61f0f9f881a2e6bff3d59deb603b34980db',
    '.gnu.version_r': 'c6fc423c7ff0e0146d50a811f2cced01b08b76ca384c302ad742b918c40d7e94',
    '.rela.dyn': '025f6feba1b78cb210815492179679d02a6ed1c91a50dc8d8ec501025c1d98e0',
    '.relr.dyn': 'e7da3d04b121562f452bd6d7691fb66c6cb1b3a8eb3fac1072ce05c86468454b',
    '.rela.plt': '70617b33b369f4cba5740ccb35edf4ee36b5da5380f6a6765c2d161aca5310ed',
}

# Separately audited OS3.0.306.0 core, after the GraphicBuffer fix. Never
# accept a new version merely because the import still has the same name.
FIRMWARE_306 = {
    'text_size': 0x370C08,
    'text_sha256': '281a2d23389500d86b9c7d0a6cb63c3dcfb3ee7d219435f30b3cb0ff24f4be46',
    'symbol_layout_sha256': '90f36303194eee59bf8d786c99c4d907325851e18d6bd9b74685a0e5faf97943',
    'tables': {
        '.gnu.hash': 'cda3be161743e673f5028329d4d033582cf5255e3e6edb87f31a524d4e79231b',
        '.gnu.version': '5736a462ab6a0e53dd41e9bf8fa91eeec5ed137d579dba11b6cdd91e5cc2cf21',
        '.gnu.version_r': '660910e3995248682c56e7d5a87e95a2537385721cf526b76ea565fac75aef17',
        '.rela.dyn': 'f9e82d6308380274bf24df2c64e84578dfbd639d3db21857589ff9376f89377b',
        '.relr.dyn': 'ffa8f9d4cf05f6358740f3a0a11a6b2c0c5d2cdb0e094cd4e89a468f05586798',
        '.rela.plt': 'a381bac9245fed8cde6150a18a15b9b483778daf6add636b5fc509ffb4827cb1',
    },
}


def _cstring(data, offset):
    if not 0 <= offset < len(data):
        raise ValueError('ELF string offset outside table')
    end = data.find(b'\0', offset)
    if end < 0:
        raise ValueError('Unterminated ELF string')
    return data[offset:end].decode('ascii')


class Symbol(NamedTuple):
    index: int
    name: str
    info: int
    other: int
    section: int
    value: int
    size: int
    string_offset: int


class CameraElf:
    """Minimal read-only ELF64 view; all patch inputs are additionally pinned."""
    def __init__(self, data):
        self.data = data
        if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or
                struct.unpack_from('<HH', data, 16) != (3, 183)):
            raise ValueError('RAW metadata shim requires AArch64 ELF64 DSO')
        shoff = struct.unpack_from('<Q', data, 40)[0]
        entsize, count, names_index = struct.unpack_from('<HHH', data, 58)
        if (entsize != 64 or not 0 < names_index < count or
                shoff + count * entsize > len(data)):
            raise ValueError('Invalid ELF section table')
        self.headers = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * entsize)
                        for i in range(count)]
        names = self.contents(self.headers[names_index])
        self.names = [_cstring(names, h[0]) for h in self.headers]
        if len(set(self.names)) != count:
            raise ValueError('Duplicate ELF section names')
        self.sections = dict(zip(self.names, self.headers))
        symtab = self.sections['.dynsym']
        if (symtab[1] != 11 or symtab[9] != 24 or symtab[5] % 24 or
                not 0 <= symtab[6] < count or
                self.names[symtab[6]] != '.dynstr'):
            raise ValueError('Invalid dynamic symbol table')
        self.strings = self.contents(self.sections['.dynstr'])
        self.symbols = []
        for i, fields in enumerate(struct.iter_unpack('<IBBHQQ', self.contents(symtab))):
            offset, info, other, section, value, size = fields
            self.symbols.append(Symbol(i, _cstring(self.strings, offset), info, other,
                                       section, value, size, offset))

    def contents(self, section):
        start, size = section[4:6]
        if section[1] == 8 or start + size > len(self.data):
            raise ValueError('ELF section is not file-backed')
        return self.data[start:start + size]

    def layout_hash(self):
        rows = []
        for s in self.symbols:
            name = ORIGINAL if s.name == REPLACEMENT else s.name
            section = self.names[s.section] if 0 < s.section < len(self.names) else s.section
            rows.append(f'{s.index}:{name}:{s.info}:{s.other}:{section}:{s.value:x}:{s.size:x}\n')
        return hashlib.sha256(''.join(rows).encode()).hexdigest()


def _check_text(elf):
    text = elf.sections['.text']
    version = FIRMWARE_306 if text[5] == FIRMWARE_306['text_size'] else {
        'text_size': TEXT_SIZE, 'text_sha256': TEXT_SHA256,
        'symbol_layout_sha256': SYMBOL_LAYOUT_SHA256, 'tables': TABLE_SHA256,
    }
    if (text[3] != TEXT_VA or text[5] != version['text_size'] or
            hashlib.sha256(elf.contents(text)).hexdigest() != version['text_sha256']):
        raise ValueError('Camera text changed; re-audit RAW metadata call sites')
    return version


def check_text(path):
    _check_text(CameraElf(Path(path).read_bytes()))


def checked_elf(data):
    elf = CameraElf(data)
    version = _check_text(elf)
    if any(s.name == BROKEN_R7 for s in elf.symbols):
        raise ValueError('Unsafe R7 blob: regenerate from stock or verified R6, not in-place')
    if elf.layout_hash() != version['symbol_layout_sha256']:
        raise ValueError('Camera symbol layout changed; packed relocation indices are unsafe')
    for name, expected in version['tables'].items():
        if hashlib.sha256(elf.contents(elf.sections[name])).hexdigest() != expected:
            raise ValueError(f'Camera {name} changed; re-audit relocations/hash/version tables')
    matches = [s for s in elf.symbols if s.name in (ORIGINAL, REPLACEMENT)]
    if (len(matches) != 1 or matches[0].index != 179 or
            (matches[0].info, matches[0].other, matches[0].section) != (0x12, 0, 0)):
        raise ValueError('Expected the original GLOBAL DEFAULT UND FUNC at index 179')
    target = matches[0]
    # GNU hash excludes undefined imports. Renaming an exported/hashed symbol
    # would require a hash rebuild and is explicitly unsupported here.
    first_hashed = struct.unpack_from('<I', elf.contents(elf.sections['.gnu.hash']), 4)[0]
    if target.index >= first_hashed or '.hash' in elf.sections:
        raise ValueError('Cannot rename a hashed symbol in place')
    lo, hi = target.string_offset, target.string_offset + len(ORIGINAL)
    if any(s.index != target.index and lo <= s.string_offset <= hi for s in elf.symbols):
        raise ValueError('Shared symbol-name string cannot be patched safely')
    # Reject a dynamic library name/path sharing the same string slot too.
    for tag, value in struct.iter_unpack('<qQ', elf.contents(elf.sections['.dynamic'])):
        if tag in (1, 14, 15, 29, 0x7ffffffd, 0x7fffffff) and lo <= value <= hi:
            raise ValueError('Dynamic string overlaps the metadata import')
    return elf, target


def patched_metadata_elf(data):
    elf, target = checked_elf(data)
    if target.name == REPLACEMENT:
        return data
    old, new = ORIGINAL.encode(), REPLACEMENT.encode()
    if len(old) != len(new):
        raise ValueError('Metadata replacement must fit exactly in the original name slot')
    offset = elf.sections['.dynstr'][4] + target.string_offset
    result = data[:offset] + new + data[offset + len(old):]
    # All bytes outside this one string are unchanged, including every symbol
    # index and every packed/non-packed relocation. Revalidate before writing.
    checked_elf(result)
    return result


def fixup_camera_raw_metadata(ctx, file, file_path, *args, **kwargs):
    if file.dst != TARGET:
        raise ValueError('RAW metadata shim may only modify the audited camera core')
    path = Path(file_path)
    original = path.read_bytes()
    patched = patched_metadata_elf(original)
    if patched != original:
        path.write_bytes(patched)


def verify_camera_raw_metadata(ctx, file, file_path, *args, **kwargs):
    """Run AFTER all DT_NEEDED rewrites as well, not just after our own edit."""
    if file.dst != TARGET:
        raise ValueError('RAW metadata verification requires the audited camera core')
    elf, target = checked_elf(Path(file_path).read_bytes())
    if target.name != REPLACEMENT:
        raise ValueError('Metadata import was not patched')
    needed = [_cstring(elf.strings, value) for tag, value in
              struct.iter_unpack('<qQ', elf.contents(elf.sections['.dynamic'])) if tag == 1]
    if needed.count('libflourite_camera_metadata.so') != 1:
        raise ValueError('Expected exactly one metadata shim dependency')
