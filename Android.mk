# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0

LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),flourite)

# Stock ships these PCI helpers as self-contained ARM32 executables on an
# otherwise 64-bit-only product. Treat them as target prebuilts so Android 17
# does not reject them as ELF files installed through PRODUCT_COPY_FILES.
include $(CLEAR_VARS)
LOCAL_MODULE := ispv4_utils_lspci
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../../../vendor/xiaomi/flourite/proprietary/odm/bin/ispv4_utils_lspci
LOCAL_MODULE_PATH := $(TARGET_OUT_ODM)/bin
LOCAL_CHECK_ELF_FILES := false
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ispv4_utils_setpci
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../../../vendor/xiaomi/flourite/proprietary/odm/bin/ispv4_utils_setpci
LOCAL_MODULE_PATH := $(TARGET_OUT_ODM)/bin
LOCAL_CHECK_ELF_FILES := false
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

# Recovery needs these modules to boot the ADSP before the battery charger
# service can publish its power-supply nodes. They are kept out of
# PRODUCT_COPY_FILES because kernel modules are ELF prebuilts.
include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_q6_pdr_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/q6_pdr_dlkm.ko
LOCAL_MODULE_STEM := q6_pdr_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_q6_notifier_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/q6_notifier_dlkm.ko
LOCAL_MODULE_STEM := q6_notifier_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_snd_event_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/snd_event_dlkm.ko
LOCAL_MODULE_STEM := snd_event_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_gpr_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/gpr_dlkm.ko
LOCAL_MODULE_STEM := gpr_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_spf_core_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/spf_core_dlkm.ko
LOCAL_MODULE_STEM := spf_core_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := flourite_recovery_adsp_loader_dlkm
LOCAL_MODULE_OWNER := xiaomi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := ../flourite-kernel/vendor_dlkm/adsp_loader_dlkm.ko
LOCAL_MODULE_STEM := adsp_loader_dlkm.ko
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/lib/modules
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

endif
