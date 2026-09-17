# Flourite first-stage diagnostics

These files are temporary bring-up instrumentation. They are not intended for
release builds.

The V11 logger must start before the normal `first_stage_console` call, because
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
m bootimage initbootimage recoveryimage vendorbootimage
```

The flag adds both `androidboot.first_stage_console=1` and
`androidboot.first_stage_console_early=1`. The latter has no effect without the
platform patch. V11 keeps the console supervisor's `SIGCHLD` handling isolated
from first-stage init, so PID 1 can still wait for module-loading and other
helper processes. Its watchdog snapshots include process and per-thread kernel
wait channels, stacks and syscalls for the early init, vold and storage path.
V11 keeps capturing after `adbd` or `system_server` first appear. It polls
`/dev/kmsg` every 10 ms, commits the record at least every 50 ms, and commits
immediately after kernel error/fatal records. This is required because PID 1's
fatal parent may reboot as soon as unwinding completes; only its safety child
waits five seconds. V11 writes detailed snapshots directly to `oops` so
`/dev/kmsg` cannot rate-limit them. Normal builds leave the logger and both
bootconfig parameters out.

When packaging only boot-chain images over an existing OTA, do not use the
freshly rebuilt `vbmeta.img`: its `odm`, `vendor`, and `vendor_dlkm` descriptors
describe the local build rather than the installed OTA. Generate a
base-compatible vbmeta from the exact installed OTA images, or ship the matching
dynamic partitions as well.

During one normal diagnostic boot, the watchdog replaces one 2 MiB record in
Xiaomi's 16 MiB `oops` ring after validating its GPT name and size. It preserves
handles for the early `/proc`, `/dev`, and sysfs mounts; once GPT exposes
`PARTNAME=oops`, it opens the partition through a private temporary block node.
This works before init creates `/dev/block` and keeps the log destination valid
across switch-root. Recovery mode never opens `oops`. Remove the platform patch
and keep
`FLOURITE_FIRST_STAGE_DIAGNOSTICS` unset after the pre-ADB bring-up failure has
been diagnosed.
