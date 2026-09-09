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

endif
