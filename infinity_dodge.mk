#
# Copyright (C) 2021-2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from dodge device
$(call inherit-product, device/oneplus/dodge/device.mk)

# Inherit some common Project Infinity-X stuff.
$(call inherit-product, vendor/infinity/config/common_full_phone.mk)

# Project InfinityX Flags
INFINITY_BUILD_TYPE := OFFICIAL
INFINITY_MAINTAINER := Arijit-Saha
TARGET_SUPPORTS_BLUR := true
TARGET_HAS_UDFPS := true
TARGET_BUILD_GOOGLE_TELEPHONY := false
USE_MOTO_CALCULATOR := true

# Gapps
WITH_GAPPS := true
WITH_GMS := true
TARGET_SHIPS_FULL_GAPPS := false

# Bootanimation
TARGET_BOOT_ANIMATION_RES := 1080

PRODUCT_NAME := infinity_dodge
PRODUCT_DEVICE := dodge
PRODUCT_MANUFACTURER := OnePlus
PRODUCT_BRAND := OnePlus
PRODUCT_MODEL := CPH2653

PRODUCT_GMS_CLIENTID_BASE := android-oneplus

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="qssi_64-user 16 BP2A.250605.015 1775048494038 release-keys" \
    BuildFingerprint=OnePlus/CPH2653EEA/OP5D55L1:16/BP2A.250605.015/V.R4T3.535a14b-3024561-302455e:user/release-keys \
    DeviceName=OP5D55L1 \
    DeviceProduct=CPH2653 \
    SystemDevice=OP5D55L1 \
    SystemName=CPH2653
