#
# Copyright (C) 2021-2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxxhdpi

# Alert slider
# DeviceSettings owns the tri-state KeyHandler (custom usages incl. flashlight
# blink). Do not also package hardware/oplus KeyHandler — PhoneWindowManager
# would load it from lineage-sdk and overwrite DeviceSettings slider actions.
PRODUCT_PACKAGES += \
    DeviceSettings \
    tri-state-key-calibrate

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(LOCAL_PATH)/configs/audio/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml

# Boot animation
TARGET_SCREEN_HEIGHT := 3168
TARGET_SCREEN_WIDTH := 1440

# Display
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/display/displayconfig.xml:$(TARGET_COPY_OUT_VENDOR)/etc/displayconfig/display_id_4630946756802996883.xml

PRODUCT_SYSTEM_PROPERTIES += \
    sys.brightness.disable_gamma_conversion=true

# Fingerprint
TARGET_HAS_UDFPS := true

# LiveDisplay
$(call soong_config_set_bool,OPLUS_LINEAGE_LIVEDISPLAY_HAL,ENABLE_AF,true)

# Maintainer
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.lunaris.maintainer=No_Cache

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(LOCAL_PATH)/overlay-lineage

PRODUCT_PACKAGES += \
    FrameworksResTargetEuicc \
    OPlusFrameworksResTarget \
    OPlusSettingsProviderResTarget \
    OPlusSettingsResTarget \
    OPlusSystemUIResTarget

# NFC
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23821:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23821 \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23893:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23893 \
    $(LOCAL_PATH)/configs/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-nci.conf

# Power
$(call soong_config_set,qtipower,mode_ext_lib,power-ext-oplus)

# PowerShare
PRODUCT_PACKAGES += \
    vendor.lineage.powershare-service.oplus

# Regional properties
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/recovery/root/vendor/odm/etc/23821/build.default.prop:$(TARGET_COPY_OUT_ODM)/etc/23821/build.default.prop \
    $(LOCAL_PATH)/recovery/root/vendor/odm/etc/23893/build.EU.prop:$(TARGET_COPY_OUT_ODM)/etc/23893/build.EU.prop \
    $(LOCAL_PATH)/recovery/root/vendor/odm/etc/23893/build.IN.prop:$(TARGET_COPY_OUT_ODM)/etc/23893/build.IN.prop \
    $(LOCAL_PATH)/recovery/root/vendor/odm/etc/23893/build.NA.prop:$(TARGET_COPY_OUT_ODM)/etc/23893/build.NA.prop \
    $(LOCAL_PATH)/recovery/root/vendor/odm/etc/23893/build.default.prop:$(TARGET_COPY_OUT_ODM)/etc/23893/build.default.prop

# RemovePackages
 PRODUCT_PACKAGES += \
     RemovePackagesDodge

#SurfaceFlinger Refresh Rate
$(call soong_config_set,surfaceflinger,frame_rate_category_high,120)
$(call soong_config_set,surfaceflinger,frame_rate_category_min,60)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Telephony
PRODUCT_PACKAGES += \
    OplusEsimSwitcher \
    OplusEuicc

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.euicc.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/android.hardware.telephony.euicc.xml

# Touch features
$(call soong_config_set_bool,OPLUS_LINEAGE_TOUCH_HAL,ENABLE_GM,true)
$(call soong_config_set_bool,OPLUS_LINEAGE_TOUCH_HAL,ENABLE_HTPR,false)

# Vibrator
# Stock OOS richtap HAL blob stack (vendor/oneplus/dodge) replaces the
# source-built QTI HAL; the blob HAL does its own OOS-correct effect mapping
# so it reads the stock bin layout shipped from the vendor tree.
TARGET_USES_OPLUS_VIBRATOR_BLOBS := true

# AOSP prebaked effects. Stock ships a crisp (def) and a gentle (soft) set,
# switched by an OOS Settings toggle AOSP never calls, so it defaults to the
# hard/rough crisp set. CLICK/DOUBLE_CLICK/TICK/THUD/POP (0-4) use the stock
# soft-style waveforms (smooth, verified on-device); HEAVY_CLICK (5) has no
# soft variant so it stays def amplitude-scaled to 60%. These copies precede
# the vendor-tree inherit so they win; the other ~66 bins ship stock.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/vibrator/def/effect_0.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_0.bin \
    $(LOCAL_PATH)/configs/vibrator/def/effect_1.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_1.bin \
    $(LOCAL_PATH)/configs/vibrator/def/effect_2.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_2.bin \
    $(LOCAL_PATH)/configs/vibrator/def/effect_3.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_3.bin \
    $(LOCAL_PATH)/configs/vibrator/def/effect_4.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_4.bin \
    $(LOCAL_PATH)/configs/vibrator/def/effect_5.bin:$(TARGET_COPY_OUT_ODM)/etc/vibrator/9999/def/effect_5.bin

$(call soong_config_set_bool,OPLUS_LINEAGE_VIBRATOR_HAL,USE_EFFECT_STREAM,true)
$(call soong_config_set,OPLUS_LINEAGE_VIBRATOR_HAL,INCLUDE_DIR,$(LOCAL_PATH)/vibrator/include)

# Inherit from the common OEM chipset makefile.
$(call inherit-product, device/oneplus/sm8750-common/common.mk)

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/oneplus/dodge/dodge-vendor.mk)

# Real-time 1080p120: override stock odm camera feature/config blobs
PRODUCT_COPY_FILES := $(filter-out \
    %/odm/etc/camera/config/oplus_camera_config \
    %/odm/etc/camera/config/camera_unit_feature_config.protobuf \
    ,$(PRODUCT_COPY_FILES))
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/camera/oplus_camera_config:$(TARGET_COPY_OUT_ODM)/etc/camera/config/oplus_camera_config \
    $(LOCAL_PATH)/camera/camera_unit_feature_config.protobuf:$(TARGET_COPY_OUT_ODM)/etc/camera/config/camera_unit_feature_config.protobuf


# Camera
$(call inherit-product-if-exists, vendor/oplus/camera/opluscamera.mk)
