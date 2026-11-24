/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef QTI_VIBRATOR_EFFECT_STREAM_H
#define QTI_VIBRATOR_EFFECT_STREAM_H
#include <sys/types.h>

struct effect_stream {
    uint32_t effect_id;
    uint32_t length;
    uint32_t play_rate_hz;
    const int8_t* data;
};

const struct effect_stream* get_effect_stream(uint32_t effect_id);

/*
 * Optional hooks the sm8750-common QTI HAL calls into (see
 * sm8750-common/vibrator/effect/effect.h for the pass-through defaults).
 */
enum effect_play_kind {
    EFFECT_PLAY_PERFORM = 0,    /* IVibrator::perform() predefined effect */
    EFFECT_PLAY_PRIMITIVE = 1,  /* IVibrator::compose() primitive */
    EFFECT_PLAY_ON = 2,         /* IVibrator::on() constant vibration */
    EFFECT_PLAY_ON_SHORT = 3,   /* IVibrator::on() replaced by a waveform */
};

/* Bit N set: AOSP Effect N can be streamed by perform(). */
uint64_t get_supported_effects_mask(void);

/* Waveform to play instead of a constant on(duration_ms), or NULL. */
const struct effect_stream* get_short_vibration_stream(uint32_t duration_ms);

/*
 * Final FF magnitude (1..0x7fff) for a request. @magnitude is the QTI HAL
 * magnitude it would otherwise use; @amplitude is the requested 0..1
 * amplitude/scale, or a negative value when not applicable.
 */
int16_t scale_effect_magnitude(uint32_t kind, int16_t magnitude, float amplitude);

#endif
