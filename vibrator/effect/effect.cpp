/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * dodge (OnePlus 13) haptic profile selector. Every vibration the framework
 * requests -- prebaked effects (fingerprint, keyboard, long-press, toggles,
 * gestures, ...) and composed primitives -- resolves through get_effect_stream,
 * so swapping the effect/primitive tables here reprofiles the whole ROM.
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
    std::string profile = GetProperty("persist.sys.haptic_profile", "op13soft");

    if ((effect_id & 0x8000) != 0) {
        effect_id = effect_id & 0x7fff;
        const struct effect_stream* selected = primitives;
        size_t size = ARRAY_SIZE(primitives);

        if (profile == "crisp") {
            selected = primitives_crisp;
            size = ARRAY_SIZE(primitives_crisp);
        } else if (profile == "gentle") {
            selected = primitives_gentle;
            size = ARRAY_SIZE(primitives_gentle);
        }
        /* op13def/op13soft reuse the base (richtap) primitive table. */

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
    } else if (profile == "op13def") {
        selected = effects_op13def;
        size = ARRAY_SIZE(effects_op13def);
    } else if (profile == "op13soft") {
        selected = effects_op13soft;
        size = ARRAY_SIZE(effects_op13soft);
    }

    for (i = 0; i < size; i++)
        if (effect_id == selected[i].effect_id) return &selected[i];
    return nullptr;
}
