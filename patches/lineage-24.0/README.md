# LineageOS 24 compatibility patches

Apply from the source root if the corresponding fix is not already present:

```sh
git -C hardware/lineage/compat am \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0002-compat-link-wfd-shim-against-libaudiobase.patch
git -C hardware/nxp/keymint am \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0004-keymint-isolate-source-transport-soname.patch
git -C system/sepolicy am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0005-sepolicy-test-only-older-platform-versions.patch
```

- 0002 supplies the WFD shim's AudioSystem dependency.
- 0004 separates the source KeyMint transport from the stock Weaver transport.
- 0005 limits Treble compatibility tests to older platform versions.
