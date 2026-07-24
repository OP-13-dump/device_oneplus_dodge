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
    lib_fixups,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'hardware/oplus',
    'hardware/qcom-caf/sm8750',
    'vendor/oneplus/sm8750-common',
    'vendor/qcom/opensource/commonsys-intf/display',
    # libAlgoProcess.so gets a DT_NEEDED on libapsfixup (the APS turbo fix interposer), which is
    # defined in the device/oneplus/dodge namespace -- import it so the blob can resolve it.
    'device/oneplus/dodge',
]

blob_fixups: blob_fixups_user_type = {
    'odm/etc/init/init.camera_process.rc': blob_fixup()
        .regex_replace('    delete_recursion', '    #delete_recursion'),
    'odm/firmware/fastchg/23821/charging_hyper_mode_config.txt': blob_fixup()
        .regex_replace(r"(PROJECT:=)23893", r"\g<1>23821"),
    'odm/lib64/libAlgoProcess.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V5-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        # APS turbo soft/GREEN/crash is now fixed at RUNTIME by libapsfixup.so
        # (device/oneplus/dodge/apsfixup), loaded via this DT_NEEDED. Root cause: the port's
        # gralloc/IMapper reports a wrong plane layout for the 4096x3072 P010 capture-output
        # buffer, so the byte-identical ArcSoft/Algo blobs build a garbage chroma plane. The
        # interposer corrects, at runtime: (1) ARC_Turbo_RAW_Process output struct chroma plane
        # ptr = luma + Ysize (was align_up(luma,0) = 4GB), (2) chroma pitch = Y stride (was 0),
        # (3) p010LSB2MSBNeon length so w4*w5*1.5 == buffer (full Y+UV, no overrun). Turbo runs
        # normally -> sharp + correct color.
        .add_needed('libapsfixup.so'),
    (
        'odm/lib64/libAncHumanSegFigureFusion.so',
        'odm/lib64/libEISLive.so',
        'odm/lib64/libHIS.so',
        'odm/lib64/libOPAlgoCamAiBeautyFaceRetouchCn.so',
        'odm/lib64/libOPAlgoCamAiUnifySkin.so',
        'odm/lib64/libOPAlgoCamFaceBeautyCap.so',
    ): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_acquire')
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),
    # TIME-LAPSE / hyperlapse saves solid green. LibEIS reads a bogus pixel format from the
    # buffer handle (often the frame width, e.g. 4096) and passes it to
    # ShareBuffer_CreateFromNativeHandle. eglCreateImageKHR then fails (EGL_BAD_ATTRIBUTE /
    # 12300) and the deferred hyperlapse path encodes empty green frames. Force
    # HAL_PIXEL_FORMAT_YCbCr_420_888 (0x23) at the four CreateFromNativeHandle call sites:
    # ldr w4,[xN,#0x2c] -> mov w4,#0x23 (52800464).
    'odm/lib64/libEIS.so': blob_fixup()
        .clear_symbol_version('AHardwareBuffer_acquire')
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock')
        .binary_regex_replace(
            b'\x84\x2e\x40\xb9\xe1\x8b\x44\x29\xe3\x2f\x40\xb9\x05\x40\x80\x52',
            b'\x64\x04\x80\x52\xe1\x8b\x44\x29\xe3\x2f\x40\xb9\x05\x40\x80\x52',
        )
        .binary_regex_replace(
            b'\x04\x2f\x40\xb9\x05\x20\x80\x52\x45\x00\xa0\x72\xe0\x03\x18\xaa',
            b'\x64\x04\x80\x52\x05\x20\x80\x52\x45\x00\xa0\x72\xe0\x03\x18\xaa',
        )
        .binary_regex_replace(
            b'\xff\x03\x00\xf9\x28\x10\x40\xf9\x04\x2c\x40\xb9\x01\x89\x40\x29\x86\xc2\xff\x97',
            b'\xff\x03\x00\xf9\x28\x10\x40\xf9\x64\x04\x80\x52\x01\x89\x40\x29\x86\xc2\xff\x97',
        )
        .binary_regex_replace(
            b'\xff\x03\x00\xf9\x28\x10\x40\xf9\x04\x2c\x40\xb9\x01\x89\x40\x29\x39\xc2\xff\x97',
            b'\xff\x03\x00\xf9\x28\x10\x40\xf9\x64\x04\x80\x52\x01\x89\x40\x29\x39\xc2\xff\x97',
        ),
    # Master/Pro-mode photos come out with RED/BLUE swapped. Pro mode captures RAW10 and the
    # OnePlus OCCE tone-mapper (libBasicTonePhoto.so) runs an OpenGL shader whose body contains a
    # U/V (Cb/Cr) reorder `dstYuv = vec4(dstYuv.r, dstYuv.b, dstYuv.g, 1.0)`. On this port the net
    # result is a single uncompensated chroma swap -> R/B swapped JPEG. Undo the swap in the
    # embedded GLSL (length-preserving). Normal/Photo mode does NOT use BasicTone, so this only
    # affects the (otherwise crisp) Master/Pro path..
    'odm/lib64/libBasicTonePhoto.so': blob_fixup()
        .binary_regex_replace(
            b'vec4\\(dstYuv\\.r, dstYuv\\.b, dstYuv\\.g, 1\\.0\\)',
            b'vec4(dstYuv.r, dstYuv.g, dstYuv.b, 1.0)',
        ),
    'odm/lib64/libsensorbridge.so': blob_fixup()
        .replace_needed('android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so'),
    (
        'vendor/lib64/camera/components/com.qti.node.dewarp.so',
        'vendor/lib64/hw/com.qti.chi.override.so',
        'vendor/lib64/libcamximageformatutils.so',
        'vendor/lib64/libchifeature2.so',
    ): blob_fixup()
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so'),
    'vendor/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so': blob_fixup()
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so')
        # convertAndImportBuffer reads the offline-metadata buffer size from the SnapHandle's
        # aligned_width_in_bytes field (handle+0x1c), but on this build that field holds a bogus
        # stride (e.g. 512) for the metadata BLOB while the real byte size is in the next field
        # (aligned_width_in_pixels, handle+0x20). That truncates the metadata copy to 512 bytes
        # and crashes CamX (MetaBuffer::AllocateBuffer). Patch the load to read +0x20 instead of
        # +0x1c:  ldr w27,[x21,#0x1c] (bb1e40b9) -> ldr w27,[x21,#0x20] (bb2240b9).
        # 12-byte anchor = ldr x21,[x12,#0x30]; ldr w27,[x21,#0x1c]; ldr w0,[x21,#0xc].
        .binary_regex_replace(
            b'\x95\x19\x40\xf9\xbb\x1e\x40\xb9\xa0\x0e\x40\xb9',
            b'\x95\x19\x40\xf9\xbb\x22\x40\xb9\xa0\x0e\x40\xb9',
        ),
    (
        'vendor/lib64/libcamxcoreutils.so',
        'vendor/lib64/libcamxods.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    'odm/lib64/libsharebuffer_impl.so': blob_fixup()
        .replace_needed('libutils.so', 'libutils-stock.so')
        .replace_needed('libui.so', 'libui-stock.so'),
    'vendor/lib64/libui-stock.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V5-ndk.so', 'android.hardware.graphics.common-V7-ndk.so'),
    'odm/etc/camera/CameraHWConfiguration.config': blob_fixup()
        .regex_replace('enableSWfdForThirdCamUnit   = FALSE', 'enableSWfdForThirdCamUnit   = TRUE')
        .regex_replace('fdSupport                 = FALSE;', 'fdSupport                 = TRUE;'),
}  # fmt: skip

module = ExtractUtilsModule(
    'dodge',
    'oneplus',
    namespace_imports=namespace_imports,
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device_with_common(
        module, 'sm8750-common', module.vendor
    )
    utils.run()
