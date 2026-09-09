#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0

"""Build the initial flourite proprietary-files list from an extracted OTA.

The common Qualcomm list is seeded from existing SM7635/volcano devices and
from a recent Xiaomi Qualcomm device.  Only files that are actually present in
the flourite stock image are retained.  Flourite's ODM payload is then added in
full (apart from packages, overlays, generated policy and kernel modules), as
that partition contains the device-specific camera, sensor, audio and biometric
stack.  Finally, DT_NEEDED dependencies are closed over the extracted images.

This is intentionally a bring-up tool, not a substitute for pruning the list
after successful hardware testing.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
from collections import Counter, defaultdict


PARTITIONS = ("odm", "vendor", "system_ext", "product")
OUTPUT_PARTITIONS = (*PARTITIONS, "system")

DEFAULT_REFERENCES = (
    Path("/tmp/flourite-refs/asteroids/proprietary-files.txt"),
    Path("/tmp/flourite-refs/FP6/proprietary-files.txt"),
    Path("/tmp/flourite-refs/peridot/proprietary-files.txt"),
)

# These files are already installed from device/xiaomi/flourite.  Keeping a
# proprietary copy too would create duplicate modules or copy rules.
SOURCE_OWNED_FILES = {
    "odm/bin/test-nusensors",
    "odm/etc/ueventd.rc",
    "product/lib64/libbase.so",
    "product/lib64/libc++.so",
    "product/lib64/libcutils.so",
    "product/lib64/libhidlbase.so",
    "product/lib64/libjsoncpp.so",
    "product/lib64/libutils.so",
    "system_ext/lib64/android.hidl.base@1.0.so",
    "system_ext/lib64/libqti_vndfwk_detect.so",
    "system_ext/lib64/vendor.display.config@1.0.so",
    "system_ext/lib64/vendor.display.config@2.0.so",
    "system_ext/lib64/vendor.qti.hardware.capabilityconfigstore@1.0.so",
    "system_ext/lib64/vendor.qti.hardware.display.config-V11-ndk.so",
    "system_ext/lib64/vendor.qti.hardware.display.config-V2-ndk.so",
    "system_ext/lib64/vendor.qti.hardware.display.config-V5-ndk.so",
    "system_ext/lib64/vendor.qti.hardware.display.config-V7-ndk.so",
    "system_ext/lib64/vendor.qti.hardware.systemhelper@1.0.so",
    "system_ext/lib64/vendor.xiaomi.hardware.displayfeature@1.0.so",
    "vendor/etc/init/vendor.qti.audio-adsprpc-service.rc",
    "vendor/etc/vintf/manifest/android.hardware.graphics.mapper-impl-qti-display.xml",
    "vendor/etc/vintf/manifest/manifest_non_qmaa.xml",
    "vendor/lib64/android.frameworks.sensorservice@1.0.so",
    "vendor/lib64/android.hardware.authsecret-V1-ndk.so",
    "vendor/lib64/android.hardware.audio.common-V1-ndk.so",
    "vendor/lib64/android.hardware.audio.common@7.0-enums.so",
    "vendor/lib64/android.hardware.biometrics.common-V3-ndk.so",
    "vendor/lib64/android.hardware.biometrics.common.thread.so",
    "vendor/lib64/android.hardware.biometrics.face-V3-ndk.so",
    "vendor/lib64/android.hardware.biometrics.fingerprint-V3-ndk.so",
    "vendor/lib64/android.hardware.bluetooth.audio-V3-ndk.so",
    "vendor/lib64/android.hardware.camera.common-V1-ndk.so",
    "vendor/lib64/android.hardware.camera.device-V2-ndk.so",
    "vendor/lib64/android.hardware.camera.metadata-V2-ndk.so",
    "vendor/lib64/android.hardware.camera.provider-V2-ndk.so",
    "vendor/lib64/android.hardware.contexthub-V2-ndk.so",
    "vendor/lib64/android.hardware.drm-V1-ndk.so",
    "vendor/lib64/android.hardware.gatekeeper-V1-ndk.so",
    "vendor/lib64/android.hardware.gnss-V3-ndk.so",
    "vendor/lib64/android.hardware.graphics.allocator-V1-ndk.so",
    "vendor/lib64/android.hardware.graphics.composer3-V2-ndk.so",
    "vendor/lib64/android.hardware.health-V1-ndk.so",
    "vendor/lib64/android.hardware.ir-V1-ndk.so",
    "vendor/lib64/android.hardware.light-V2-ndk.so",
    "vendor/lib64/android.hardware.nfc-V1-ndk.so",
    "vendor/lib64/android.hardware.radio-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.config-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.data-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.messaging-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.modem-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.network-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.sap-V1-ndk.so",
    "vendor/lib64/android.hardware.radio.sim-V2-ndk.so",
    "vendor/lib64/android.hardware.radio.voice-V2-ndk.so",
    "vendor/lib64/android.hardware.secure_element-V1-ndk.so",
    "vendor/lib64/android.hardware.security.rkp-V3-ndk.so",
    "vendor/lib64/android.hardware.security.keymint-V3-ndk.so",
    "vendor/lib64/android.hardware.security.sharedsecret-V1-ndk.so",
    "vendor/lib64/android.hardware.security.secureclock-V1-ndk.so",
    "vendor/lib64/android.hardware.sensors-V2-ndk.so",
    "vendor/lib64/android.hardware.sensors@2.0-ScopedWakelock.so",
    "vendor/lib64/android.hardware.thermal-V1-ndk.so",
    "vendor/lib64/android.hardware.weaver-V2-ndk.so",
    "vendor/lib64/android.hardware.wifi.hostapd-V1-ndk.so",
    "vendor/lib64/android.hardware.wifi.supplicant-V1-ndk.so",
    "vendor/lib64/android.se.omapi-V1-ndk.so",
    "vendor/lib64/android.system.net.netd-V1-ndk.so",
    "vendor/lib64/com.dsi.ant@1.0.so",
    "vendor/lib64/lib_android_keymaster_keymint_utils.so",
    "vendor/lib64/libandroid_runtime_lazy.so",
    "vendor/lib64/libavservices_minijail.so",
    "vendor/lib64/libcodec2_hidl@1.0.so",
    "vendor/lib64/libcodec2_hidl@1.1.so",
    "vendor/lib64/libcodec2_hidl@1.2.so",
    "vendor/lib64/libcodec2_vndk.so",
    "vendor/lib64/libcppbor_external.so",
    "vendor/lib64/libcppcose_rkp.so",
    "vendor/lib64/libdrm.so",
    "vendor/lib64/libflatbuffers-cpp.so",
    "vendor/lib64/libhidltransport.so",
    "vendor/lib64/libhwbinder.so",
    "vendor/lib64/libimage_io.so",
    "vendor/lib64/libkeymaster_messages.so",
    "vendor/lib64/libkeymaster_portable.so",
    "vendor/lib64/libmemunreachable.so",
    "vendor/lib64/libpsi.so",
    "vendor/lib64/libqti_vndfwk_detect.so",
    "vendor/lib64/libruy.so",
    "vendor/lib64/libsoft_attestation_cert.so",
    "vendor/lib64/libstagefright_aidl_bufferpool2.so",
    "vendor/lib64/libstagefright_bufferpool@2.0.1.so",
    "vendor/lib64/libtextclassifier_hash.so",
    "vendor/lib64/libtflite.so",
    "vendor/lib64/vendor.nxp.nxpnfc_aidl-V1-ndk.so",
    "vendor/lib64/vendor.display.config@1.0.so",
    "vendor/lib64/vendor.display.config@2.0.so",
    "vendor/lib64/vendor.qti.hardware.bluetooth.audio-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.bluetooth_audio@2.0.so",
    "vendor/lib64/vendor.qti.hardware.bluetooth_audio@2.1.so",
    "vendor/lib64/vendor.qti.hardware.btconfigstore@1.0.so",
    "vendor/lib64/vendor.qti.hardware.btconfigstore@2.0.so",
    "vendor/lib64/vendor.qti.hardware.camera.offlinecamera-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.camera.postproc@1.0.so",
    "vendor/lib64/vendor.qti.hardware.capabilityconfigstore@1.0.so",
    "vendor/lib64/vendor.qti.hardware.display.allocator@3.0.so",
    "vendor/lib64/vendor.qti.hardware.display.allocator@4.0.so",
    "vendor/lib64/vendor.qti.hardware.display.composer3-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.config-V11-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.config-V2-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.config-V5-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.config-V7-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.demura-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.mapper@2.0.so",
    "vendor/lib64/vendor.qti.hardware.display.mapper@3.0.so",
    "vendor/lib64/vendor.qti.hardware.display.mapper@4.0.so",
    "vendor/lib64/vendor.qti.hardware.display.mapperextensions@1.0.so",
    "vendor/lib64/vendor.qti.hardware.display.mapperextensions@1.1.so",
    "vendor/lib64/vendor.qti.hardware.display.mapperextensions@1.2.so",
    "vendor/lib64/vendor.qti.hardware.display.mapperextensions@1.3.so",
    "vendor/lib64/vendor.qti.hardware.display.postproc-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.display.color-V1-ndk.so",
    "vendor/lib64/vendor.qti.hardware.servicetracker@1.0.so",
    "vendor/lib64/vendor.qti.hardware.servicetracker@1.1.so",
    "vendor/lib64/vendor.qti.hardware.systemhelper@1.0.so",
    "vendor/lib64/vendor.xiaomi.hardware.displayfeature@1.0.so",
    "vendor/bin/init.class_main.sh",
    "vendor/bin/init.kernel.post_boot-memory.sh",
    "vendor/bin/init.kernel.post_boot-volcano_2_2_1.sh",
    "vendor/bin/init.kernel.post_boot-volcano_3_2_1.sh",
    "vendor/bin/init.kernel.post_boot-volcano_default_4_3_1.sh",
    "vendor/bin/init.kernel.post_boot-volcano.sh",
    "vendor/bin/init.kernel.post_boot.sh",
    "vendor/bin/init.qcom.early_boot.sh",
    "vendor/bin/init.qcom.post_boot.sh",
    "vendor/bin/init.qcom.sh",
    "vendor/bin/init.qti.display_boot.sh",
    "vendor/bin/init.qti.media.sh",
    "vendor/bin/hw/vendor.qti.hardware.display.allocator-service",
    "vendor/bin/hw/vendor.qti.hardware.display.composer-service",
    "vendor/bin/hw/vendor.qti.hardware.display.demura-service",
    "vendor/etc/audio_policy_volumes.xml",
    "vendor/etc/bluetooth_qti_hearing_aid_audio_policy_configuration.xml",
    "vendor/etc/card-defs.xml",
    "vendor/etc/charger_fw_fstab.qti",
    "vendor/etc/default_volume_tables.xml",
    "vendor/etc/fstab.qcom",
    "vendor/etc/hal_uuid_map_config.xml",
    "vendor/etc/hal_uuid_map_flourite.xml",
    "vendor/etc/hal_uuid_map_flouritein.xml",
    "vendor/etc/hal_uuid_map_flouritep.xml",
    "vendor/etc/init/hw/init.qcom.factory.rc",
    "vendor/etc/init/hw/init.qcom.rc",
    "vendor/etc/init/hw/init.target.rc",
    "vendor/etc/init/init.flourite.rc",
    "vendor/etc/init/init.qti.display_boot.rc",
    "vendor/etc/init/init.qti.media.rc",
    "vendor/etc/init/vendor.qti.hardware.display.allocator-service.rc",
    "vendor/etc/init/vendor.qti.hardware.display.composer-service.rc",
    "vendor/etc/init/vendor.qti.hardware.display.demura-service.rc",
    "vendor/etc/media_codecs_performance_volcano_v0.xml",
    "vendor/etc/media_codecs_performance_volcano_v1.xml",
    "vendor/etc/media_codecs_volcano_v0.xml",
    "vendor/etc/media_codecs_volcano_v1.xml",
    "vendor/etc/media_profiles_volcano_flourite_v0.xml",
    "vendor/etc/media_profiles_volcano_flourite_v1.xml",
    "vendor/etc/media_volcano_v0/video_system_specs.json",
    "vendor/etc/media_volcano_v1/video_system_specs.json",
    "vendor/etc/microphone_characteristics.xml",
    "vendor/etc/powerhint.xml",
    "vendor/etc/sensors/hals.conf",
    "vendor/etc/ueventd.rc",
    "vendor/etc/usecaseKvManager.xml",
    "vendor/etc/vintf/manifest/vendor.qti.hardware.display.allocator-service.xml",
    "vendor/etc/vintf/manifest/vendor.qti.hardware.display.composer-service.xml",
    "vendor/etc/vintf/manifest/vendor.qti.hardware.display.demura-service.xml",
    "vendor/etc/wifi/p2p_supplicant_overlay.conf",
    "vendor/etc/wifi/qca6750/WCNSS_qcom_cfg.ini",
    "vendor/etc/wifi/wpa_supplicant_overlay.conf",
}

# Stable platform interface libraries are generated from the Android/CAF
# sources in Lineage.  Match by basename so dependency closure cannot pull a
# second stock copy from another partition.
SOURCE_OWNED_BASENAMES = {
    "android.hardware.audio.common@5.0.so",
    "android.hardware.audio.common@7.0.so",
    "android.hardware.audio.effect@7.0.so",
    "android.hardware.audio@7.0.so",
    "android.hardware.bluetooth.audio@2.0.so",
    "android.hardware.bluetooth.audio@2.1.so",
    "android.hardware.bluetooth@1.0.so",
    "android.hardware.bluetooth@1.1.so",
    "android.hardware.gnss.measurement_corrections@1.0.so",
    "android.hardware.gnss.measurement_corrections@1.1.so",
    "android.hardware.gnss.visibility_control@1.0.so",
    "android.hardware.gnss@1.0.so",
    "android.hardware.gnss@1.1.so",
    "android.hardware.gnss@2.0.so",
    "android.hardware.gnss@2.1.so",
    "android.hardware.health@1.0.so",
    "android.hardware.health@2.0.so",
    "android.hardware.health@2.1.so",
    "android.hardware.keymaster@3.0.so",
    "android.hardware.keymaster@4.0.so",
    "android.hardware.keymaster@4.1.so",
    "android.hardware.media.c2@1.0.so",
    "android.hardware.media.c2@1.1.so",
    "android.hardware.media.c2@1.2.so",
    "android.hardware.nfc@1.0.so",
    "android.hardware.nfc@1.1.so",
    "android.hardware.nfc@1.2.so",
    "android.hardware.power@1.0.so",
    "android.hardware.power@1.1.so",
    "android.hardware.power@1.2.so",
    "android.hardware.radio@1.0.so",
    "android.hardware.radio@1.1.so",
    "android.hardware.radio@1.2.so",
    "android.hardware.radio@1.3.so",
    "android.hardware.radio@1.4.so",
    "android.hardware.radio@1.5.so",
    "android.hardware.radio@1.6.so",
    "android.hardware.secure_element@1.0.so",
    "android.hardware.secure_element@1.1.so",
    "android.hardware.secure_element@1.2.so",
    "android.hardware.sensors@1.0.so",
    "android.hardware.sensors@2.0.so",
    "android.hardware.sensors@2.1.so",
    "android.hardware.thermal@1.0.so",
    "android.hardware.thermal@2.0.so",
    "android.hidl.allocator@1.0.so",
    "android.hidl.base@1.0.so",
    "android.hardware.graphics.mapper@4.0-impl-qti-display.so",
    "libcamera2ndk_vendor.so",
    "libcld80211.so",
    "libdisplayconfig.qti.so",
    "libdisplayconfig.system.qti.so",
    "libdisplaydebug.so",
    "libdrmutils.so",
    "libfilefinder.so",
    "libgpu_tonemapper.so",
    "libgralloc.qti.so",
    "libgralloccore.so",
    "libgrallocutils.so",
    "libhistogram.so",
    "libjson.so",
    "libprotobuf-cpp-full-21.7.so",
    "libprotobuf-cpp-lite-21.7.so",
    "libqti_vndfwk_detect_vendor.so",
    "libqdMetaData.so",
    "libqdutils.so",
    "libqservice.so",
    "librmnetctl.so",
    "libsdedrm.so",
    "libsdmcore.so",
    "libsdmdal.so",
    "libsdmutils.so",
    "libvmmem.so",
    "libwfdaac_vendor.so",
    "libwpa_client.so",
    "sound_trigger.primary.volcano.so",
    "vendor.nxp.nxpese@1.0.so",
    "vendor.qti.hardware.AGMIPC@1.0.so",
    "vendor.qti.hardware.pal@1.0.so",
}

SOURCE_OWNED_PREFIXES = (
    "vendor/etc/audio/sku_volcano/",
    "vendor/etc/displayconfig/",
)

# Files copied mechanically from all of ODM should not include these stock
# build products.  Useful apps selected by a curated reference list are kept.
AUTO_SKIP_PREFIXES = (
    "odm/app/",
    "odm/overlay/",
    "odm/priv-app/",
)

GLOBAL_SKIP_PARTS = (
    "/etc/selinux/",
    "/lib/modules/",
)

GLOBAL_SKIP_SUFFIXES = (
    ".art",
    ".ko",
    ".odex",
    ".vdex",
)

# Exact stock data that is both hardware-specific and cheap to keep even when
# no reference device happens to name it.
TARGETED_VENDOR_PREFIXES = (
    "vendor/etc/acdbdata/",
    "vendor/etc/audio/sku_volcano_qssi/",
    "vendor/etc/camera/",
    "vendor/etc/permissions/sku_volcano/",
    "vendor/etc/sensors/config/",
    "vendor/firmware/",
)

TARGETED_VENDOR_TOKENS = ("flourite", "volcano", "qca6750")

FORCED_REFERENCE_LINES = {
    # The stock binary overrides Qualcomm's source-built audioadsprpcd, so it
    # must retain the source module's dependency on the matching init script.
    # The Qualcomm script is byte-identical to the one shipped by flourite.
    "vendor/bin/audioadsprpcd":
        "vendor/bin/audioadsprpcd;REQUIRED=vendor.qti.audio-adsprpc-service.rc",
    # These ISPv4 PCI helpers are self-contained ARM32 static executables.
    # A 64-bit-only product has no ARM32 Soong variant, so Android.mk installs
    # them as unchecked target prebuilts while extract-utils only extracts them.
    "odm/bin/ispv4_utils_lspci":
        "odm/bin/ispv4_utils_lspci;EXTRACT_ONLY",
    "odm/bin/ispv4_utils_setpci":
        "odm/bin/ispv4_utils_setpci;EXTRACT_ONLY",
    # Keep Qualcomm's GNSS V3 manifest as a normal VINTF prebuilt, but give
    # the installed fragment a device-specific name.  The stock basename is
    # also used by Android 17's non-installable example V7 manifest.
    "odm/etc/vintf/manifest/gnss-default.xml":
        "odm/etc/vintf/manifest/gnss-default.xml:"
        "odm/etc/vintf/manifest/flourite-gnss-default.xml;TRYSRCFIRST",
    # Keep the Android 16 sensor stack beside Android 17's V3 source stack.
    # Both the module name and the runtime SONAME must be unique.
    "vendor/lib64/libsensorndkbridge.so":
        "vendor/lib64/libsensorndkbridge.so:"
        "vendor/lib64/libsensorndkbridge_sensors_v2.so;TRYSRCFIRST;FIX_SONAME",
    # The Android 16 sensorservice V1 library references sensors AIDL V2,
    # while Android 17's source module with the same name references V3.
    "vendor/lib64/android.frameworks.sensorservice-V1-ndk.so":
        "vendor/lib64/android.frameworks.sensorservice-V1-ndk.so:"
        "vendor/lib64/android.frameworks.sensorservice-V1-ndk_sensors_v2.so;"
        "TRYSRCFIRST;FIX_SONAME",
    # Android 17's unversioned utility uses biometrics common V4.  Keep the
    # stock V3 utility under a unique module name and SONAME for mfp-daemon.
    "vendor/lib64/android.hardware.biometrics.common.util.so":
        "vendor/lib64/android.hardware.biometrics.common.util.so:"
        "vendor/lib64/android.hardware.biometrics.common.util_v3.so;"
        "TRYSRCFIRST;FIX_SONAME",
    # The same HIDL interface is shipped in vendor and system_ext.  Keep both
    # runtime files, but give the vendor-side Soong module a unique name.
    "vendor/lib64/vendor.qti.hardware.dpmservice@1.0.so":
        "vendor/lib64/vendor.qti.hardware.dpmservice@1.0.so;MODULE_SUFFIX=_vendor",
}

# Xiaomi installs these camera libraries as named variants while keeping the
# SONAME of their common implementation.  Preserve the stock ELF metadata and
# only disable Soong's filename-versus-SONAME check for these intentional
# aliases.
STOCK_SONAME_ALIASES = {
    "odm/lib64/libarcsoft_hdrgl_detection.so",
    "odm/lib64/libarcsoft_hdrgl_raw.so",
    "odm/lib64/libarcsoft_high_dynamic_range_gl.so",
    "odm/lib64/libarcsoft_low_light_hdr_gl.so",
    "odm/lib64/libarcsoft_portrait_hdrgl_raw.so",
    "odm/lib64/libarcsoft_raw_glsr.so",
    "odm/lib64/libarcsoft_super_night_raw_gl.so",
    "odm/lib64/libarcsoft_super_night_raw_poco.so",
    "odm/lib64/libarcsoft_turbo_hdrgl_detection.so",
    "odm/lib64/libremosaiclib_hp3.so",
    "odm/lib64/libremosaiclib_isz.so",
}

# Proprietary files not discoverable from reference lists or ELF dependency
# closure. Camera extmodels are loaded from ODM JSON configuration, and the
# intent-aware AIDL Java API is consumed dynamically by Xiaomi components.
EXTRA_STOCK_FILES = (
    "system/lib64/libheif.so",
    "system/lib64/vendor.qti.diaghal-V1-ndk.so",
    "system_ext/etc/permissions/vendor.xiaomi.hardware.aidl.intentaware-V1-java-permission.xml",
    "system_ext/framework/vendor.xiaomi.hardware.aidl.intentaware-V1-java.jar",
    "vendor/etc/vintf/manifest/vendor.xiaomi.hardware.aidl.intentaware-service.xml",
    "vendor/lib64/com.xiaomi.camhal.extmodel.catch_log_sys.so",
    "vendor/lib64/com.xiaomi.camhal.extmodel.ec_diag_sys.so",
    "vendor/lib64/com.xiaomi.camhal.extmodel.ec_executor.so",
    "vendor/lib64/com.xiaomi.camhal.extmodel.intent_aware_sys.so",
    "vendor/lib64/vendor.xiaomi.hardware.aidl.intentaware-V1-impl.so",
    "vendor/lib64/vendor.xiaomi.hardware.aidl.intentaware-V1-ndk_platform.so",
)

DONOR_MARKERS = (
    "asteroids",
    "fairphone",
    "nothing",
    "pacman",
    "peridot",
    "fp6",
)

NEEDED_RE = re.compile(r"Shared library: \[(.+?)\]")
ALLOCATOR_V1 = "android.hardware.graphics.allocator-V1-ndk.so"


def parse_source(line: str) -> str | None:
    line = line.strip()
    if not line or line.startswith("#"):
        return None

    # A leading '-' asks extract-utils to force package generation.  It is not
    # part of the source path.
    body = line[1:] if line.startswith("-") else line
    end = len(body)
    for separator in (":", ";", "|"):
        position = body.find(separator)
        if position >= 0:
            end = min(end, position)
    source = body[:end]
    if not source.startswith(tuple(f"{partition}/" for partition in PARTITIONS)):
        return None
    return source


def without_hashes(line: str) -> str:
    return line.split("|", 1)[0].strip()


def is_present(stock_root: Path, path: str) -> bool:
    return os.path.lexists(stock_root / path)


def should_skip(path: str, *, automatic: bool = False) -> bool:
    if path in SOURCE_OWNED_FILES:
        return True
    if Path(path).name in SOURCE_OWNED_BASENAMES:
        return True
    if path.startswith(SOURCE_OWNED_PREFIXES):
        return True
    if automatic and path.startswith(AUTO_SKIP_PREFIXES):
        return True
    if any(part in f"/{path}" for part in GLOBAL_SKIP_PARTS):
        return True
    if path.endswith(GLOBAL_SKIP_SUFFIXES):
        return True

    basename = path.rsplit("/", 1)[-1]
    if basename in {
        "aconfig_flags.pb",
        "build.prop",
        "compatibility_matrix.xml",
        "fs_config_dirs",
        "fs_config_files",
        "group",
        "linker.config.pb",
        "manifest.xml",
        "passwd",
    }:
        return True
    if basename.startswith("NOTICE"):
        return True
    return False


def sanitize_reference_line(line: str, source: str) -> str:
    line = without_hashes(line)
    extras = line[len(source) :]
    if line.startswith("-"):
        extras = line[len(source) + 1 :]

    # Destination/module names carrying another device codename are unsafe on
    # flourite.  The stock source itself is still useful, so retain it plainly.
    if any(marker in extras.lower() for marker in DONOR_MARKERS):
        return source
    return line


def android_symlink_target(link_path: str, target: str) -> str | None:
    if target.startswith("/"):
        normalized = target.lstrip("/")
    else:
        normalized = os.path.normpath(
            os.path.join(os.path.dirname(link_path), target)
        )

    if not normalized.startswith(tuple(f"{partition}/" for partition in PARTITIONS)):
        return None
    return normalized


def add_path(
    stock_root: Path,
    entries: dict[str, str],
    symlinks: dict[str, set[str]],
    path: str,
    line: str | None = None,
    *,
    automatic: bool = False,
) -> bool:
    if should_skip(path, automatic=automatic) or not is_present(stock_root, path):
        return False

    stock_path = stock_root / path
    if stock_path.is_symlink():
        target = android_symlink_target(path, os.readlink(stock_path))
        if target is None or should_skip(target, automatic=automatic):
            return False
        if not is_present(stock_root, target):
            return False
        symlinks[target].add(path)
        entries.setdefault(target, target)
        return True

    entries.setdefault(path, line or path)
    return True


def load_references(
    stock_root: Path,
    references: list[Path],
    entries: dict[str, str],
    symlinks: dict[str, set[str]],
) -> dict[str, str]:
    catalog: dict[str, str] = {}
    for reference in references:
        if not reference.is_file():
            raise FileNotFoundError(f"Missing reference list: {reference}")
        for raw_line in reference.read_text(encoding="utf-8").splitlines():
            source = parse_source(raw_line)
            if source is None:
                continue
            line = sanitize_reference_line(raw_line, source)
            catalog.setdefault(source, line)
            add_path(stock_root, entries, symlinks, source, line)
    return catalog


def add_tree(
    stock_root: Path,
    relative_root: str,
    entries: dict[str, str],
    symlinks: dict[str, set[str]],
    *,
    automatic: bool,
) -> None:
    root = stock_root / relative_root
    if not root.is_dir():
        return
    for stock_path in sorted(root.rglob("*")):
        if not (stock_path.is_file() or stock_path.is_symlink()):
            continue
        relative = stock_path.relative_to(stock_root).as_posix()
        add_path(
            stock_root,
            entries,
            symlinks,
            relative,
            automatic=automatic,
        )


def add_targeted_vendor_files(
    stock_root: Path,
    entries: dict[str, str],
    symlinks: dict[str, set[str]],
) -> None:
    for prefix in TARGETED_VENDOR_PREFIXES:
        add_tree(stock_root, prefix.rstrip("/"), entries, symlinks, automatic=True)

    vendor_root = stock_root / "vendor"
    for stock_path in sorted(vendor_root.rglob("*")):
        if not (stock_path.is_file() or stock_path.is_symlink()):
            continue
        relative = stock_path.relative_to(stock_root).as_posix()
        if any(token in relative.lower() for token in TARGETED_VENDOR_TOKENS):
            add_path(stock_root, entries, symlinks, relative, automatic=True)


def elf_class(path: Path) -> int | None:
    if path.is_symlink():
        return None
    try:
        header = path.open("rb").read(5)
    except OSError:
        return None
    if header[:4] != b"\x7fELF" or header[4] not in (1, 2):
        return None
    return header[4]


def library_index(stock_root: Path) -> dict[tuple[str, int], list[str]]:
    index: dict[tuple[str, int], list[str]] = defaultdict(list)
    for partition in PARTITIONS:
        for lib_dir in ("lib", "lib64"):
            root = stock_root / partition / lib_dir
            if not root.is_dir():
                continue
            for stock_path in root.rglob("*"):
                if not stock_path.is_file() or stock_path.is_symlink():
                    continue
                klass = elf_class(stock_path)
                if klass is None:
                    continue
                relative = stock_path.relative_to(stock_root).as_posix()
                index[(stock_path.name, klass)].append(relative)
    return index


def dependency_candidates(current: str, candidates: list[str]) -> list[str]:
    current_partition = current.split("/", 1)[0]
    preference = {
        current_partition: 0,
        "vendor": 1,
        "odm": 2,
        "system_ext": 3,
        "product": 4,
    }
    return sorted(
        candidates,
        key=lambda path: (
            preference.get(path.split("/", 1)[0], 9),
            path.count("/"),
            path,
        ),
    )


def needed_libraries(path: Path) -> set[str]:
    result = subprocess.run(
        ["readelf", "-d", path],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode != 0:
        return set()
    return set(NEEDED_RE.findall(result.stdout))


def close_elf_dependencies(
    stock_root: Path,
    entries: dict[str, str],
    symlinks: dict[str, set[str]],
    catalog: dict[str, str],
) -> int:
    index = library_index(stock_root)
    inspected: set[str] = set()
    added = 0

    while True:
        pending = sorted(set(entries) - inspected)
        if not pending:
            break
        for current in pending:
            inspected.add(current)
            stock_path = stock_root / current
            klass = elf_class(stock_path)
            if klass is None:
                continue
            for needed in sorted(needed_libraries(stock_path)):
                candidates = dependency_candidates(
                    current, index.get((needed, klass), [])
                )
                for candidate in candidates:
                    if should_skip(candidate):
                        continue
                    before = len(entries)
                    add_path(
                        stock_root,
                        entries,
                        symlinks,
                        candidate,
                        catalog.get(candidate, candidate),
                    )
                    if len(entries) > before:
                        added += 1
                    break
    return added


def append_symlink_args(
    entries: dict[str, str], symlinks: dict[str, set[str]]
) -> None:
    for target, links in symlinks.items():
        if target not in entries:
            continue
        existing = entries[target]
        existing_links: set[str] = set()
        for match in re.finditer(r";SYMLINK=([^;|]+)", existing):
            existing_links.update(match.group(1).split(","))
        missing = sorted(links - existing_links)
        if missing:
            entries[target] = f'{existing};SYMLINK={",".join(missing)}'


def write_list(output: Path, entries: dict[str, str], stock_root: Path) -> None:
    lines = [
        "## Proprietary files for flourite.",
        "##",
        "## Generated from Xiaomi OS3.0.304.0.WPRMIXM (Android 16) with",
        "## SM7635/volcano references, then closed over stock ELF dependencies.",
        "## Regenerate with tools/generate-proprietary-files.py and prune only",
        "## after a successful build plus on-device hardware validation.",
    ]

    for partition in OUTPUT_PARTITIONS:
        partition_entries = sorted(
            (path, line)
            for path, line in entries.items()
            if path.startswith(f"{partition}/")
        )
        if not partition_entries:
            continue
        lines.extend(("", f"# {partition.replace('_', ' ').title()}"))
        lines.extend(line for _, line in partition_entries)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    counts = Counter(path.split("/", 1)[0] for path in entries)
    print(f"Wrote {len(entries)} entries to {output}")
    print(
        "Partitions: "
        + ", ".join(f"{p}={counts[p]}" for p in OUTPUT_PARTITIONS)
    )
    print(f"Stock source: {stock_root}")


def write_allocator_v1_list(
    output: Path, entries: dict[str, str], stock_root: Path
) -> None:
    paths: list[str] = []
    marker = ALLOCATOR_V1.encode()
    for path in sorted(entries):
        stock_path = stock_root / path
        if elf_class(stock_path) is None:
            continue
        try:
            if marker not in stock_path.read_bytes():
                continue
        except OSError:
            continue
        if ALLOCATOR_V1 in needed_libraries(stock_path):
            paths.append(path)

    allocator_output = output.with_name("allocator-v1-files.txt")
    lines = [
        "# Generated by tools/generate-proprietary-files.py.",
        "# Android 16 blobs whose allocator V1 dependency must be changed to V2.",
        *paths,
    ]
    allocator_output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Allocator V1 compatibility fixups: {len(paths)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_root", type=Path)
    parser.add_argument(
        "--reference",
        action="append",
        type=Path,
        dest="references",
        help="proprietary-files.txt reference (repeatable)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "proprietary-files.txt",
    )
    args = parser.parse_args()

    stock_root = args.stock_root.resolve()
    if not (stock_root / "vendor").is_dir() or not (stock_root / "odm").is_dir():
        parser.error(f"{stock_root} is not an extracted flourite stock tree")

    references = args.references or list(DEFAULT_REFERENCES)
    entries: dict[str, str] = {}
    symlinks: dict[str, set[str]] = defaultdict(set)

    catalog = load_references(stock_root, references, entries, symlinks)
    add_tree(stock_root, "odm", entries, symlinks, automatic=True)
    add_targeted_vendor_files(stock_root, entries, symlinks)
    closure_added = close_elf_dependencies(
        stock_root, entries, symlinks, catalog
    )
    for path in EXTRA_STOCK_FILES:
        add_path(stock_root, entries, symlinks, path)
    for path, line in FORCED_REFERENCE_LINES.items():
        if path in entries:
            entries[path] = line
    for path in STOCK_SONAME_ALIASES:
        if path in entries and "DISABLE_CHECKELF" not in entries[path]:
            entries[path] = f"{entries[path]};DISABLE_CHECKELF"
    append_symlink_args(entries, symlinks)
    output = args.output.resolve()
    write_list(output, entries, stock_root)
    write_allocator_v1_list(output, entries, stock_root)
    print(f"ELF dependency closure added {closure_added} entries")


if __name__ == "__main__":
    main()
