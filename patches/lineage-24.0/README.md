# LineageOS 24 source patches

These patches are temporary bring-up requirements for the current Android 17
beta source tree. Apply them from the LineageOS source root after `repo sync`.

```sh
git -C system/core am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0001-init-support-an-early-first-stage-console.patch

git -C hardware/lineage/compat am \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0002-compat-link-wfd-shim-against-libaudiobase.patch

git -C system/sepolicy am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0003-sepolicy-keep-flourite-first-stage-diagnostics-alive.patch
```

Patches 0001 and 0003 are needed only while building with
`FLOURITE_FIRST_STAGE_DIAGNOSTICS=true`. Patch 0003 is inert unless that flag
defines its device-specific m4 switch.

Patch 0002 fixes the current LineageOS 24 beta split where `AudioSystem` is
provided by `libaudiobase`, but `libwfdservice_shim` does not link that library
directly. Drop it once the compatibility project carries the dependency
upstream.
