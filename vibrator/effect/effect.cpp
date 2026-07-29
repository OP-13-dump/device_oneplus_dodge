/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * dodge (OnePlus 13) haptic profile selector — YAAP sm8650-common style.
 * Prebaked tables are keyed with AOSP Effect IDs 0-5, primitive tables with the
 * AOSP CompositePrimitive ids under PRIMITIVE_ID_MASK. Profiles:
 *   richtap | crisp | gentle | op13crisp | op13gentle (default)
 * op13crisp/op13gentle are dodge stock ColorOS waveforms — def/soft effect_0..5
 * for the prebaked effects, and def-derived cuts for the primitives.
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
#include "op13_primitive_profiles.h"

const struct effect_stream* get_effect_stream(uint32_t effect_id) {
    using android::base::GetProperty;

    size_t i;
    std::string profile = GetProperty("persist.sys.haptic_profile", "op13gentle");

    if ((effect_id & 0x8000) != 0) {
        effect_id = effect_id & 0x7fff;
        const struct effect_stream* selected = primitives;
        size_t size = ARRAY_SIZE(primitives);

        /* The op13 profiles get stock ColorOS primitives of their own. They used
         * to borrow the YAAP sets, which peak at 78/127 on gentle — that is why
         * "OnePlus 13 Gentle" felt weak: primitives are about half of all haptic
         * callbacks (keyboard and UI taps compose them; only the six predefined
         * effects go through perform()). */
        if (profile == "op13crisp") {
            selected = primitives_op13crisp;
            size = ARRAY_SIZE(primitives_op13crisp);
        } else if (profile == "op13gentle" || profile == "op13soft") {
            selected = primitives_op13gentle;
            size = ARRAY_SIZE(primitives_op13gentle);
        } else if (profile == "crisp") {
            selected = primitives_crisp;
            size = ARRAY_SIZE(primitives_crisp);
        } else if (profile == "gentle") {
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
