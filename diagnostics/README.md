# Flourite first-stage diagnostics

These files are temporary bring-up instrumentation. They are not intended for
release builds.

The V4 logger must start before the normal `first_stage_console` call, because
that call is reached only after kernel-module loading and early block-device
creation. Apply the matching LineageOS 24 platform patch from the source root:

```sh
git -C system/core am \
    ../../device/xiaomi/flourite/patches/lineage-24.0/0001-init-support-an-early-first-stage-console.patch
```

Then build the diagnostic images explicitly:

```sh
export FLOURITE_FIRST_STAGE_DIAGNOSTICS=true
source build/envsetup.sh
lunch lineage_flourite-cp2a-userdebug
m initbootimage recoveryimage vendorbootimage vbmetaimage
```

The flag adds both `androidboot.first_stage_console=1` and
`androidboot.first_stage_console_early=1`. The latter has no effect without the
platform patch. Normal builds leave the logger and both bootconfig parameters
out.

During one normal diagnostic boot, the watchdog replaces one 2 MiB record in
Xiaomi's 16 MiB `oops` ring after validating its GPT name and size. Recovery
mode never opens `oops`. Remove the platform patch and keep
`FLOURITE_FIRST_STAGE_DIAGNOSTICS` unset after the pre-ADB bring-up failure has
been diagnosed.
