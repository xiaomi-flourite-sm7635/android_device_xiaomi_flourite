# LineageOS 24 compatibility patches

Apply from the source root if the corresponding fix is not already present:

```sh
git -C hardware/lineage/compat am \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0002-compat-link-wfd-shim-against-libaudiobase.patch
git -C hardware/nxp/keymint am \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0004-keymint-isolate-source-transport-soname.patch
git -C hardware/nxp/keymint apply \
    ../../../device/xiaomi/flourite/patches/lineage-24.0/0006-keymint-support-sku-manifests.patch
git -C system/sepolicy am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0005-sepolicy-test-only-older-platform-versions.patch
git -C frameworks/av apply \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0007-codec2-reject-invalid-hidl-configurables.patch
git -C vendor/qcom/opensource/vibrator apply \
    ../../../../device/xiaomi/flourite/patches/lineage-24.0/0008-vibrator-support-ics-haptics.patch
```

- 0002 supplies the WFD shim's AudioSystem dependency.
- 0004 separates the source KeyMint transport from the stock Weaver transport.
- 0005 limits Treble compatibility tests to older platform versions.
- 0006 allows the device to select StrongBox declarations by hardware variant.
- 0007 rejects failed or null HIDL codec configurables before constructing a client.
- 0008 recognizes ICS haptics in the vibrator HAL and excludes them from generic input handling.
