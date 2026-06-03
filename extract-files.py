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
]

blob_fixups: blob_fixups_user_type = {
    'odm/etc/init/init.camera_process.rc': blob_fixup()
        .regex_replace('    delete_recursion', '    #delete_recursion'),
    'odm/firmware/fastchg/23821/charging_hyper_mode_config.txt': blob_fixup()
        .regex_replace(r"(PROJECT:=)23893", r"\g<1>23821"),
    'odm/lib64/libAlgoProcess.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V5-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        # APS capture crash + soft photo (IPE p010 stage), root-caused via native Frida
        # (aps_p010_args_probe.js) + static RE of the converter:
        # android::hwIPEDoProcess does an IN-PLACE 10-bit LSB->MSB conversion via
        # APSFormatConverterNeon::p010LSB2MSBNeon(dst, src=dst, a2, a3, a4, a5). The converter
        # loop is CONTIGUOUS (`ldr q0,[x11],#0x10`; no per-row stride), so a4/a5 are NOT strides
        # -- they only set the LENGTH converted: bytes = a4*a5*1.5 (mul w22,w21; *3; >>5 -> NEON
        # iters of 16B). a2/a3 are non-zero guards only, IGNORED. The buffer is a 4096x3072 NV12
        # p010 image: Y plane = w*h*2 = 0x1800000 (25.16 MB) FIRST, then UV plane (12.58 MB),
        # total 0x2400000 (36 MiB, = the live rw mapping). Only the Y plane is LSB-packed and
        # needs the <<6; the UV plane is already MSB-aligned and must NOT be shifted (shifting it
        # overflows the chroma -> sharp-but-GREEN photo). The caller loads a4=stride[0x2f4c]=8192
        # and a5 from [x28,#0x2f50] which is UNINITIALIZED on this port (0xfc8.. garbage) -> a5
        # huge -> SIGSEGV (or APS bails -> soft QuickJpeg). Fix: make the converted length equal
        # the Y plane exactly: a4*a5*1.5 == 0x1800000 => a4*a5 == 0x1000000 == 4096*4096 == w*w.
        # Load WIDTH [0x2f2c] (=4096) into BOTH a4 and a5:
        #   ldr w4,[x28,#0x2f4c] (b96f4f84) -> ldr w4,[x28,#0x2f2c] (b96f2f84)  (4f->2f)
        #   ldr w5,[x28,#0x2f50] (b96f5385) -> ldr w5,[x28,#0x2f2c] (b96f2f85)  (53->2f, +5350->2f)
        # History: 53->4f ("srcStride==dstStride") crashed (8192*8192 overrun); 53->33 (a5=height)
        # converted the WHOLE buffer incl UV -> green; w*w converts the Y plane only -> correct.
        # Same struct-field/contract-mismatch family as the offlinecamera +0x1c->+0x20 fix.
        # 12-byte anchor = ldr w3,[x28,#0x2f30]; ldr w4,[x28,#0x2f4c]; ldr w5,[x28,#0x2f50].
        .binary_regex_replace(
            b'\x83\x33\x6f\xb9\x84\x4f\x6f\xb9\x85\x53\x6f\xb9',
            b'\x83\x33\x6f\xb9\x84\x2f\x6f\xb9\x85\x2f\x6f\xb9',
        )
        # SKIP the turbo-RAW-HDR stage in APSCaptureModeManager::workRoutine. Traced (HW
        # bisect + aps_blr2/alloc probes): the turbo-HDR OUTPUT buffer VA (AlgoProcessData+0x540)
        # is a 4GB-aligned, CPU-unmapped (PROT_NONE) reservation -- align_up_4GB(src). ArcSoft
        # (ARC_Turbo_RAW_SetParam cmd 0x2b -> ARC_Turbo_RAW/HDR_Process) writes to it and faults
        # (intermittent SIGSEGV) or bails, leaving the saved photo soft+GREEN (zero chroma).
        # ALL libs in the path (libarcsoft_turbo_raw/hdr, libarc.ion, libmpbase) are byte-identical
        # to stock and every CPU allocator returns valid buffers -> NOT a blob field bug; it's a
        # buffer-provisioning/mapping (QNN-DSP rpcmem / gralloc HW-buffer) env gap that stock maps
        # and the port doesn't. No clean byte-patch exists for that. Pragmatic workaround: NOP the
        # turboHdrProcessV2 call so Normal/Auto saves the pre-turbo APS result -> CORRECT COLOR,
        # no crash (but soft, since turbo-HDR's multi-frame detail is skipped). The next insn
        # reloads x0 ([x20,#0x208]) so the skipped call's return value is unused -> safe to NOP.
        #   workRoutine: ldr x8,[sp,#0x3a0]; mov x9,x0; mov x0,x8; blr x9; ldr x0,[x20,#0x208]
        #   blr x9 (d63f0120) -> nop (d503201f).  Remove this if turbo-HDR is fixed properly.
        .binary_regex_replace(
            b'\xe8\xd3\x41\xf9\xe9\x03\x00\xaa\xe0\x03\x08\xaa\x20\x01\x3f\xd6',
            b'\xe8\xd3\x41\xf9\xe9\x03\x00\xaa\xe0\x03\x08\xaa\x1f\x20\x03\xd5',
        ),
    (
        'odm/lib64/libAncHumanSegFigureFusion.so',
        'odm/lib64/libEIS.so',
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
