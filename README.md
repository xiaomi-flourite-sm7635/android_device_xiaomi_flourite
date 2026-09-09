# Xiaomi flourite

Android device configuration for the POCO M8 Pro 5G and Redmi Note 15 Pro+ 5G
(`flourite`) on Qualcomm's `volcano` platform (Snapdragon 7s Gen 4 / SM7635).

The current bring-up target is LineageOS 24.0 / Android 17:

```text
lunch lineage_flourite-cp2a-userdebug
```

The default configuration uses the kernel, DTB, DTBO and kernel modules from
Xiaomi OS3.0.304.0.WPRMIXM.  The published Xiaomi kernel source is present for
UAPI header generation and development, but is not yet ABI-compatible with the
complete stock module set.  Set `FLOURITE_BUILD_KERNEL_FROM_SOURCE=true` only
for source-kernel compile testing.

Initial build validation covers `init_boot`, `recovery`, `vendor`, `odm`,
`vendor_dlkm` and `system_dlkm` images.  On-device boot and hardware validation
are still required before this tree can be considered release-ready.
