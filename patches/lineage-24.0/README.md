# LineageOS 24 source patches

These patches are temporary bring-up requirements for the current Android 17
beta source tree. Apply them from the LineageOS source root after `repo sync`.

```sh
git -C system/core am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0001-init-support-an-early-first-stage-console.patch
```

Patch 0001 is needed only while building with
`FLOURITE_FIRST_STAGE_DIAGNOSTICS=true`.
