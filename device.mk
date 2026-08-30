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
$(call soong_config_set,power_libperfmgr,mode_extension_lib,//hardware/oplus:power-ext-oplus)

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

# Vibrator (YAAP sm8650-common style profiles)
# sm8750-common QTI HAL + dodge effect lib. Profiles via persist.sys.haptic_profile:
#   richtap | crisp | gentle | op13crisp | op13gentle (default)
# op13crisp/op13gentle = dodge stock def/soft effect_0..5 (AOSP IDs 0-5).
$(call soong_config_set,qti_vibrator,effect_lib,libqtivibratoreffect.oplus.dodge)

PRODUCT_PACKAGES += \
    libqtivibratoreffect.oplus.dodge

PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.haptic_profile=op13gentle

# ProXDR / HDR photo pipeline.
#
# Read off stock CPH2653 16.0.9.402 with getprop; all four are absent here, which
# is why Gallery's ultra_hdr / local_hdr / hdr_vision_brighten / edr_listener
# feature checks stay false and photos render SDR even though the panel reports
# ro.surface_flinger.has_HDR_display=true.
#
# Note the panel advertises supportedHdrTypes=INVALID even on stock, so ProXDR
# does not ride the standard Android HDR types -- it is Oplus's own localhdr/uhdr
# path, gated purely on these props.
#
# Dolby Vision is deliberately NOT enabled here (stock also sets
# persist.sys.feature.dolby_vision{,_app} and ro.vendor.oplus.dolby_vision_dpu{,.dvs}).
# Dolby on this device is a separate, flash-verified port that lives on its own
# branch; turning the UI on without that backend just offers a broken toggle.
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.feature.hdr_vision_app=1 \
    persist.sys.feature.localhdr_version=2 \
    persist.sys.feature.uhdr.support=true \
    persist.sys.feature.support.edrlistener=true

# Inherit from the common OEM chipset makefile.
$(call inherit-product, device/oneplus/sm8750-common/common.mk)

# Dalvik: 16GB. Must follow the common inherit; ?= props are last-wins.
$(call inherit-product, frameworks/native/build/phone-xhdpi-16384-dalvik-heap.mk)

# Real-time 1080p120: override the stock odm camera feature blob.
# This has to be declared *before* inheriting dodge-vendor.mk. inherit-product
# only appends an @inherit: marker that gets expanded after this file is parsed,
# so a filter-out here never sees (let alone removes) the vendor entries.
# Duplicate PRODUCT_COPY_FILES destinations are resolved first-one-wins, so
# listing ours ahead of the inherit is what actually makes the override stick.
#
# oplus_camera_config is deliberately NOT copied here any more. extract-files.py
# now decrypts it and sets the 120fps vendor tags directly on the vendor blob,
# and because this list wins first-one-wins, a static copy here would silently
# override those values with the older, narrower ones (main.only.support=1,
# zoom.range 1,3.5, max.zoom.list 1,2.9,10) and undo the patch.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/camera/camera_unit_feature_config.protobuf:$(TARGET_COPY_OUT_ODM)/etc/camera/config/camera_unit_feature_config.protobuf

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/oneplus/dodge/dodge-vendor.mk)

# Camera
$(call inherit-product-if-exists, vendor/oplus/camera/opluscamera.mk)

# Fusion light sensor (content-immune ALS) — added by apply-fusion-port.sh
$(call inherit-product-if-exists, vendor/oplus/fusionlight/fusionlight.mk)
