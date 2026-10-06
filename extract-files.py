#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)
from camera_graphicbuffer_fixup import fixup_camera_graphicbuffer

namespace_imports = [
    'device/xiaomi/flourite',
    'hardware/qcom-caf/common/libqti-perfd-client',
    'hardware/qcom-caf/sm8650',
    'hardware/qcom-caf/wlan',
    'hardware/xiaomi',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/dataservices',
    'vendor/qcom/opensource/display',
]

def lib_fixup_odm_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'odm' else None

def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    # Stock camera blobs intentionally load allocator AIDL V1 alongside V2.
    # Keep their DT_NEEDED entries untouched, but do not expose V1 through
    # Soong's dependency graph, which rejects mixed stable AIDL versions.
    'android.hardware.graphics.allocator-V1-ndk': lib_fixup_remove,
    (
        'libjc_keymint_transport.nxp',
        'sqlite3',
    ): lib_fixup_odm_suffix,
    (
        'vendor.qti.diaghal@1.0',
        'vendor.qti.hardware.dpmservice@1.0',
        'vendor.qti.hardware.qccsyshal@1.0',
        'vendor.qti.hardware.qccsyshal@1.1',
        'vendor.qti.hardware.qccsyshal@1.2',
        'vendor.qti.hardware.wifidisplaysession@1.0',
        'vendor.qti.ImsRtpService-V1-ndk',
        'vendor.qti.imsrtpservice@3.0',
        'vendor.qti.imsrtpservice@3.1',
        'vendor.qti.qccvndhal_aidl-V1-ndk',
    ): lib_fixup_vendor_suffix,
}

blob_fixups: blob_fixups_user_type = {
    # The current LLNDK libsync exports sync_wait without a symbol version.
    # Keep the ABI and dependency intact; only drop the obsolete LIBSYNC tag.
    'vendor/lib64/com.qti.feature2.offlinestatsregeneration.so': blob_fixup()
        .clear_symbol_version('sync_wait'),
    'odm/etc/vintf/manifest/vendor.xiaomi.hardware.vibratorfeature.service.xml': blob_fixup()
        .regex_replace(r'<hal format="aidl" override="true">', '<hal format="aidl">'),
    'odm/lib64/libkeymint_empty-nxp.so': blob_fixup()
        .replace_needed(
            'lib_android_keymaster_keymint_utils.so',
            'lib_android_keymaster_keymint_utils_V3.so',
        ),
    'system_ext/etc/init/qspa_system.rc': blob_fixup()
        .regex_replace(r'\$\{ro\.boot\.vendor\.qspa:-default\}', 'default'),
    'system_ext/etc/vintf/manifest/vendor.qti.qesdsys.service.xml': blob_fixup()
        .regex_replace(r'(?s)^.*?(?=<manifest)', ''),
    'system_ext/bin/wfdservice64': blob_fixup()
        .add_needed('libwfdservice_shim.so'),
    'system_ext/lib64/libwfdmmsrc_system.so': blob_fixup()
        .add_needed('libaudiobase.so')
        .add_needed('libgui_shim.so'),
    'system_ext/lib64/libwfdnative.so': blob_fixup()
        .add_needed('libbinder_shim.so')
        .add_needed('libinput_shim.so')
        .remove_needed('android.hidl.base@1.0.so'),
    'system_ext/lib64/libwfdservice.so': blob_fixup()
        .add_needed('libaudiobase.so')
        .replace_needed(
            'android.media.audio.common.types-V4-cpp.so',
            'android.media.audio.common.types-V5-cpp.so'
        ),
    'system_ext/lib64/vendor.qti.hardware.qccsyshal@1.2-halimpl.so': blob_fixup()
        .replace_needed(
            'libprotobuf-cpp-full.so',
            'libprotobuf-cpp-full-21.7.so'
    ),
    'vendor/bin/slim_daemon': blob_fixup()
        .replace_needed(
            'libsensorndkbridge.so',
            'libsensorndkbridge_sensors_v2.so',
        ),
    'vendor/bin/system_dlkm_modprobe.sh': blob_fixup()
        .regex_replace(
            'SYSTEM_DLKM_DIRS="/system_dlkm/lib/modules"',
            'SYSTEM_DLKM_DIRS="/system_dlkm/lib/modules /system_dlkm/lib"',
        )
        .regex_replace(
            r'(\t\t# Wait until current dir modprobe finished\n\t\twait)\n\tdone\n\n\twait\n\n\texit 0',
            r'\1\n\t\texit 0\n\tdone',
        ),
    'vendor/lib64/hw/android.hardware.audio@7.1-impl.so': blob_fixup()
        .replace_needed(
            'android.hardware.audio@7.1-util.so',
            'android.hardware.audio@7.1-util-v34.so',
        ),
    'vendor/lib64/libsensorndkbridge_sensors_v2.so': blob_fixup()
        .replace_needed(
            'android.frameworks.sensorservice-V1-ndk.so',
            'android.frameworks.sensorservice-V1-ndk_sensors_v2.so',
        ),
    (
        'odm/bin/hw/vendor.xiaomi.sensor.citsensorservice.aidl',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.anchor.so',
        'odm/lib64/com.qti.feature2.anchorsync.so',
        'odm/lib64/hw/displayfeature.default.so',
        # Stock camera code uses the VNDK 34 XMLDocument layout (0x308).
        # Current tinyxml2 writes past that allocation (0x370). Keep every
        # camera importer, including the dlopened anchor feature, on v34.
        'vendor/bin/hw/vendor.qti.camera.provider-service_64',
        'vendor/bin/hw/vendor.qti.hardware.display.composer-service',
        'vendor/lib64/com.qti.feature2.anchorsync.so',
        'vendor/lib64/libaudiocloudctrl.so',
        'vendor/lib64/libdpps.so',
        'vendor/lib64/liblearningmodule.so',
        'vendor/lib64/libmicamera_aidl_provider.so',
        'vendor/lib64/libsimulation.so',
        'vendor/lib64/libsnapdragoncolor-manager.so',
    ): blob_fixup()
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so'
    ),
    (
        'odm/etc/camera/enhance_motiontuning.xml',
        'odm/etc/camera/enhance_motiontuning_gl.xml',
        'odm/etc/camera/motiontuning.xml',
        'odm/etc/camera/motiontuning_gl.xml',
        'odm/etc/camera/night_motiontuning.xml'
    ): blob_fixup()
        .regex_replace('xml=version', 'xml version'),
    (
        'odm/lib64/camera/plugins/com.xiaomi.plugin.gainmap.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so',
    ): blob_fixup()
        .replace_needed(
            'libultrahdr.so',
            'libultrahdr_prebuilt.so'
    ),
    # These flourite camera blobs live in vendor. Unlike the old ODM stack,
    # only libcameraopt imports the legacy, unmangled SetTaskProfiles symbol.
    'vendor/lib64/libcameraopt.so': blob_fixup()
        .add_needed('libprocessgroup_shim.so'),
    # VNDK 34 camera objects reserve 0x100 bytes; current libui needs 0xd30.
    # Patch only audited allocation instructions, with normalized text hashes.
    (
        'odm/lib64/camera/components/com.jigan.node.videobokeh.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so',
        'vendor/lib64/libcom.xiaomi.grallocutils.so',
        'vendor/lib64/libcom.xiaomi.mawutils.so',
        'vendor/lib64/libcom.xiaomi.mawutilsold.so',
    ): blob_fixup()
        .call(fixup_camera_graphicbuffer),
    'vendor/lib64/libmicamera_hal_core.so': blob_fixup()
        .call(fixup_camera_graphicbuffer)
        .add_needed('libui_shim.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    (
        'odm/lib64/anc.hal.so',
        'vendor/bin/qseecom_sample_client',
    ): blob_fixup()
        .add_needed('libion.so'),
    (
        'odm/lib64/libAncHumanPreviewBokeh.so',
        'odm/lib64/libAncHumanVideoBokehV4.so',
        'odm/lib64/libTrueSight.so',
        'odm/lib64/libalLDC.so',
        'odm/lib64/libarcsoft_beautyshot.so',
        'odm/lib64/libwa_widelens_undistort.so',
        'odm/lib64/libmorpho_ubwc.so',
        'vendor/lib64/libMiPhotoFilter.so',
    ): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_isSupported')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),
    'odm/lib64/libsnpe_config.so': blob_fixup()
        .add_needed('liblog.so'),
    (
        'odm/lib64/libaudioroute_ext.so',
        'vendor/lib64/libagm.so',
        'vendor/lib64/libar-pal.so',
        'vendor/lib64/libfmpal.so',
        'vendor/lib64/libhfp_pal.so',
        'vendor/lib64/libkaraokepal.so',
        'vendor/lib64/libmcs.so',
    ): blob_fixup()
        .replace_needed(
            'libaudioroute.so',
            'libaudioroute-v34.so'
    ),
    'vendor/etc/clstc_config_library.xml': blob_fixup()
        .regex_replace(r'<library>\s*<name>libdolbyclstc[\s\S]*?</library>', ''),
    'vendor/etc/init/vendor.qti.camera.provider-service_64.rc': blob_fixup()
        .regex_replace(
            r'\n    interface vendor\.xiaomi\.hardware\.intentaware@1\.0::IIntentAwareService default',
            '',
        ),
    'vendor/etc/seccomp_policy/c2audio.vendor.ext-arm64.policy': blob_fixup()
        .add_line_if_missing('setsockopt: 1'),
    'vendor/etc/vintf/manifest/c2_manifest_vendor.xml': blob_fixup()
        .regex_replace('.+DOLBY.+\n', ''),
    (
        'vendor/bin/poweropt-service',
        'vendor/lib64/libgamepoweroptfeature.so',
        'vendor/lib64/liboffscreenpoweroptfeature.so',
        'vendor/lib64/libpowercore.so',
        'vendor/lib64/libvideooptfeature.so',
    ): blob_fixup()
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2_1.so'
        ),
    (
        'vendor/bin/qcc-vendor',
        'vendor/bin/qms',
        'vendor/bin/xtra-daemon',
        'vendor/lib64/libcne.so',
        'vendor/lib64/libqcc_sdk.so',
        'vendor/lib64/libqms_client.so',
    ): blob_fixup()
        .add_needed('libbinder_shim.so'),
    'vendor/lib64/libqcodec2_core.so': blob_fixup()
        .add_needed('libcodec2_shim.so'),
    'vendor/lib64/libqcrilNrVoiceModule.so': blob_fixup()
        .sig_replace('a1 00 80 52 22', 'a1 00 80 52 02'),
    'vendor/lib64/vendor.libdpmframework.so': blob_fixup()
        .add_needed('libbinder_shim.so')
        .add_needed('libhidlbase_shim.so'),
    'vendor/lib64/libultrahdr_prebuilt.so': blob_fixup()
        .replace_needed(
            'libjpegdecoder.so',
            'libjpegdecoder_prebuilt.so'
        )
        .replace_needed(
            'libjpegencoder.so',
            'libjpegencoder_prebuilt.so'
    ),
    (
        'vendor/lib64/libVoiceSdk.so',
        'vendor/lib64/libcapiv2uvvendor.so',
        'vendor/lib64/liblistensoundmodel2vendor.so',
    ): blob_fixup()
        .replace_needed(
            'libtensorflowlite_c.so',
            'libtensorflowlite_c_vendor.so',
    ),
}  # fmt: skip

module = ExtractUtilsModule(
    'flourite',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
