/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * dodge (OnePlus 13) haptic profile selector — YAAP sm8650-common style.
 * Tables are keyed with AOSP Effect IDs 0-5. Profiles:
 *   richtap | crisp | gentle | op13crisp | op13gentle (default)
 * op13crisp/op13gentle are dodge stock def/soft waveforms (effect_0..5).
 */

#include "effect.h"

#include <android-base/properties.h>
#include <string>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof(*(a)))

#include "standard_effect.h"
#include "yaap_haptic_profiles.h"
#include "op13_stock_effects.h"
#include "primitive_effect.h"
#include "generated_primitive_profiles.h"

const struct effect_stream* get_effect_stream(uint32_t effect_id) {
    using android::base::GetProperty;

    size_t i;
    std::string profile = GetProperty("persist.sys.haptic_profile", "op13gentle");

    if ((effect_id & 0x8000) != 0) {
        effect_id = effect_id & 0x7fff;
        const struct effect_stream* selected = primitives;
        size_t size = ARRAY_SIZE(primitives);

        /* YAAP: gentle + op13gentle share the softened primitive set. */
        if (profile == "crisp" || profile == "op13crisp") {
            selected = primitives_crisp;
            size = ARRAY_SIZE(primitives_crisp);
        } else if (profile == "gentle" || profile == "op13gentle" ||
                   profile == "op13soft") {
            selected = primitives_gentle;
            size = ARRAY_SIZE(primitives_gentle);
        }

        for (i = 0; i < size; i++)
            if (effect_id == selected[i].effect_id) return &selected[i];
        return nullptr;
    }

    const struct effect_stream* selected = effects;
    size_t size = ARRAY_SIZE(effects);

    if (profile == "crisp") {
        selected = effects_crisp;
        size = ARRAY_SIZE(effects_crisp);
    } else if (profile == "gentle") {
        selected = effects_gentle;
        size = ARRAY_SIZE(effects_gentle);
    } else if (profile == "op13crisp" || profile == "op13def") {
        selected = effects_op13def;
        size = ARRAY_SIZE(effects_op13def);
    } else if (profile == "op13gentle" || profile == "op13soft") {
        selected = effects_op13soft;
        size = ARRAY_SIZE(effects_op13soft);
    }

    for (i = 0; i < size; i++)
        if (effect_id == selected[i].effect_id) return &selected[i];
    return nullptr;
}
