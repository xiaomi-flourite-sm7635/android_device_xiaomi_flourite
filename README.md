# Xiaomi flourite

LineageOS 24 device tree for POCO M8 Pro 5G / Redmi Note 15 Pro+ 5G
(SM7635, volcano). Stock base: **OS3.0.306.0.WPRMIXM**.

## Build

Place this tree at `device/xiaomi/flourite`. Check out the matching source
repositories at `kernel/xiaomi/sm7635`, `kernel/xiaomi/sm7635-devicetrees`,
`kernel/xiaomi/flourite-source-modules` and `kernel/xiaomi/flourite-build`.
The [build recipe](https://github.com/xiaomi-flourite-sm7635/android_kernel_xiaomi_flourite-build/tree/lineage-24.0)
provides a `manifest.xml` with the pinned source revisions. Its README lists
the toolchain paths and host dependencies.
Extract proprietary files into `vendor/xiaomi/flourite` using `extract-files.py`.
Camera blob corrections are applied during extraction.

Use extracted stock **OS3.0.306.0.WPRMIXM** as the base and
**OS3.0.318.0.WPRCNXM** for the CN camera/NFC additions and radio firmware:

```sh
PYTHONPATH=tools/extract-utils python3 device/xiaomi/flourite/extract-files.py /path/to/global-dump \
    --china-source /path/to/china-dump \
    --global-modem /path/to/global/modem.img --china-modem /path/to/china/modem.img
```

`firmware-sources.json` pins the CN additions by SHA-256. Only those paths
come from CN; shared camera libraries and other bundled firmware stay on the
Global base. Keep both original dumps separate. A Global-only dump is insufficient
for a clean unified extraction. Apply compatibility patch 0006 before building
so StrongBox is declared only for the NXP variants.

Both stock modem images are verified against `firmware-sources.json` and packaged
in vendor. Extraction restores their omitted free FAT tail in temporary copies.
The explicit modem arguments can be omitted when `modem.img` is present in each
dump, or both packaged images are available in a unified ROM dump.

Android mounts the image selected by the hardware-derived vendor SKU read-only
at `/vendor/firmware_mnt`, before starting the radio. MBN profiles come from that
same image, not a previous region's MBN OTA cache. Firmware follows the active
vendor slot and is updated or rolled back with the ROM; modem partitions are
not flashed. Recovery retains its existing partition-based firmware mount.
When updating the stock base, refresh both image pins and rebuild vendor.

```sh
source build/envsetup.sh
breakfast flourite
m bacon
```

The kernel, all packaged modules, DTB and DTBO are built from source during
the normal ROM build. No `device/xiaomi/flourite-kernel` checkout is required.
See `patches/lineage-24.0` for the required platform compatibility patches.

For Xiaomi Camera, include the matching `device/xiaomi/flourite-miuicamera`
and `vendor/xiaomi/flourite-miuicamera` repositories. Without that companion,
the product uses Aperture.
