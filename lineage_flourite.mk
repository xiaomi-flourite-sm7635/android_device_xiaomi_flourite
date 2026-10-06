#
# Copyright (C) 2024 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Inherit from flourite device
$(call inherit-product, device/xiaomi/flourite/device.mk)

PRODUCT_NAME := lineage_flourite
PRODUCT_DEVICE := flourite
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_BRAND := Redmi
PRODUCT_MODEL := 2510ERA8BG

PRODUCT_SYSTEM_NAME := flourite_global
PRODUCT_SYSTEM_DEVICE := flourite

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="flourite_global-user 16 BP2A.250605.031.A3 OS3.0.304.0.WPRMIXM release-keys" \
    BuildFingerprint=Redmi/flourite/miproduct:16/BP2A.250605.031.A3/OS3.0.304.0.WPRMIXM:user/release-keys \
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME) \
    SystemDevice=$(PRODUCT_SYSTEM_DEVICE) \
    SystemName=$(PRODUCT_SYSTEM_NAME)

PRODUCT_GMS_CLIENTID_BASE := android-xiaomi
