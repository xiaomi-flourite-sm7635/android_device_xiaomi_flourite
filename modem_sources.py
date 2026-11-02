# SPDX-License-Identifier: Apache-2.0
"""Package pinned regional NON-HLOS images without flashing modem partitions."""

import hashlib
import shutil
import struct
from pathlib import Path

from extract_utils.file import File
from extract_utils.source import DiskSource, Source


def normalize_modem(path, spec):
    """Restore only the omitted free FAT tail of a verified stock image."""
    path = Path(path)
    size = path.stat().st_size
    if size not in (spec['size'], spec['filesystem_size']):
        raise ValueError(f'Unexpected modem image size: {path}: {size}')
    with path.open('rb') as image:
        header = image.read(512)
        sector_size = struct.unpack_from('<H', header, 11)[0]
        sectors = struct.unpack_from('<H', header, 19)[0]
        sectors = sectors or struct.unpack_from('<I', header, 32)[0]
        if header[510:512] != b'\x55\xaa' or sector_size * sectors != spec['filesystem_size']:
            raise ValueError(f'Unexpected modem FAT header: {path}')
        image.seek(0)
        digest = hashlib.sha256()
        remaining = spec['size']
        while remaining:
            block = image.read(min(remaining, 1024 * 1024))
            if not block:
                raise ValueError(f'Truncated modem image: {path}')
            digest.update(block)
            remaining -= len(block)
        if digest.hexdigest() != spec['sha256']:
            raise ValueError(f'Stock modem hash mismatch: {path}')
    # This is a temporary packaging copy, never the source dump or a device.
    with path.open('r+b') as image:
        image.truncate(spec['size'])
        image.truncate(spec['filesystem_size'])


class ModemSource(Source):
    def __init__(self, base, china_dir, overrides, specs, work_dir):
        self.base = base
        self.images = {}
        for variant, spec in specs.items():
            target = f'vendor/etc/modem/{variant}.img'
            cached = Path(work_dir) / f'{variant}.img'
            explicit = overrides.get(variant)
            if explicit:
                shutil.copyfile(explicit, cached)
                copied = True
            else:
                copied = base._copy_file_path(target, str(cached))
                if not copied:
                    source = (DiskSource(str(china_dir))
                              if variant == 'flourite_cn' and china_dir else base)
                    # Never silently take the Global stock modem for CN.
                    copied = (variant != 'flourite_cn' or china_dir is not None) and source._copy_firmware(
                        File('modem.img'), str(cached))
            if not copied:
                raise ValueError(f'Missing {variant} modem: supply its stock modem.img')
            normalize_modem(cached, spec)
            self.images[target] = cached
            version = Path(work_dir) / f'{variant}.version'
            version.write_text(f"{spec['release']}\n{spec['sha256']}\n")
            self.images[f'vendor/etc/modem/{variant}.version'] = version

    def _copy_file_path(self, file_path, target_file_path):
        if file_path in self.images:
            shutil.copyfile(self.images[file_path], target_file_path)
            return True
        return self.base._copy_file_path(file_path, target_file_path)

    def _copy_firmware(self, file, target_file_path):
        return self.base._copy_firmware(file, target_file_path)

    def _list_sub_path_file_rel_paths(self, sub_path):
        paths = set(self.base._list_sub_path_file_rel_paths(sub_path))
        prefix = sub_path.rstrip('/') + '/'
        paths.update(name[len(prefix):] for name in self.images if name.startswith(prefix))
        return sorted(paths)
