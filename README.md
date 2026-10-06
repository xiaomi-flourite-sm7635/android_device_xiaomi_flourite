# Xiaomi flourite

LineageOS 24 device tree for POCO M8 Pro 5G / Redmi Note 15 Pro+ 5G
(SM7635, volcano). Stock base: **OS3.0.306.0.WPRMIXM**.

## Build

Place this tree at `device/xiaomi/flourite`, the matching kernel prebuilts at
`device/xiaomi/flourite-kernel`, and kernel headers at `kernel/xiaomi/sm7635`.
Extract proprietary files into `vendor/xiaomi/flourite` using `extract-files.py`.
Camera blob corrections are applied during extraction.

```sh
source build/envsetup.sh
breakfast flourite
m bacon
```

The default kernel is prebuilt. Source-kernel selection remains experimental.
See `patches/lineage-24.0` for the required platform compatibility patches.

For Xiaomi Camera, include the matching `device/xiaomi/flourite-miuicamera`
and `vendor/xiaomi/flourite-miuicamera` repositories. Without that companion,
the product uses Aperture.
