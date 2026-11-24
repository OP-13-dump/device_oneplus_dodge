/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * dodge (OnePlus 13) O-Haptics effect library.
 *
 * Ports the OxygenOS "O-Haptics" behaviour onto the sm8750-common QTI HAL:
 *
 *  - Touch styles. persist.sys.haptic_profile selects
 *      crisp   : stock def_style waveforms (OOS "Crisp", the stock default)
 *      gentle  : stock soft_style waveforms, def_style where soft has none
 *                (OOS "Gentle")
 *      richtap : the built-in RichTap tables (standard/primitive_effect.h)
 *    The stock waveforms are loaded at runtime from the blobs stock ships in
 *    /odm/etc/vibrator (vibrator_effect.json + 9999/{def,soft}/effect_N.bin),
 *    so every OOS waveform is available, not only a baked subset.
 *
 *  - Patterns. AOSP perform() ids are used as slots for the stock waveforms
 *    OOS plays for each scene. Stock maps HapticFeedbackConstants to its own
 *    effect ids (OplusHapticFeedbackVibrationProvider) and those to waveform
 *    bins (effect2waveform.xml); configs/vibrator/haptic_feedback_customization.xml
 *    routes every constant to the slot holding the same waveform. AOSP's
 *    predefined effects keep the bins stock's HAL plays for them.
 *
 *  - Short one-shots. Like stock's VibrationEffectConvertor, on() pulses of up
 *    to 102 ms are played as crisp prebaked waveforms instead of a constant
 *    drive (crisp/gentle only).
 *
 *  - Intensity. Stock "stepless" strengths are millivolt-like units where
 *    2400 is full scale. persist.sys.haptic_touch_strength (800..2400,
 *    default 2000) scales touch haptics (perform/compose/short pulses) and
 *    persist.sys.haptic_vib_strength (700..2400, default 2400) scales
 *    on() vibrations: ringtones, notifications, alarms.
 */

#define LOG_TAG "vendor.qti.vibrator.dodge"

#include "effect.h"

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <json/json.h>

#include <algorithm>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof(*(a)))

#include "primitive_effect.h"
#include "standard_effect.h"

namespace {

using android::base::GetIntProperty;
using android::base::GetProperty;

constexpr const char* kStockConfig = "/odm/etc/vibrator/vibrator_effect.json";
constexpr const char* kDefStyle = "def_style";
constexpr const char* kSoftStyle = "soft_style";

constexpr const char* kPropProfile = "persist.sys.haptic_profile";
constexpr const char* kPropTouchStrength = "persist.sys.haptic_touch_strength";
constexpr const char* kPropVibStrength = "persist.sys.haptic_vib_strength";

/* Stock stepless ranges (StepLessVibrationUtils, LuXun motor variant). */
constexpr int kStrengthFull = 2400;
constexpr int kTouchStrengthMin = 800;
constexpr int kTouchStrengthDefault = 2000;
constexpr int kVibStrengthMin = 700;
constexpr int kVibStrengthDefault = 2400;

constexpr int kMagnitudeMax = 0x7fff;
constexpr int kMagnitudeMedium = 0x5fff; /* EffectStrength::MEDIUM in the HAL */

constexpr uint32_t kPrimitiveMask = 0x8000;
constexpr uint32_t kNoWaveform = UINT32_MAX;

enum class Profile { CRISP, GENTLE, RICHTAP };

Profile currentProfile() {
    const std::string p = GetProperty(kPropProfile, "crisp");
    if (p == "richtap") return Profile::RICHTAP;
    /* "op13gentle"/"op13soft" are the pre-O-Haptics names of the same style. */
    if (p == "gentle" || p == "soft" || p == "op13gentle" || p == "op13soft")
        return Profile::GENTLE;
    return Profile::CRISP;
}

/*
 * AOSP perform() id -> stock waveform bin, with the RichTap table id used for
 * the same slot when the richtap profile is active.
 */
struct Slot {
    uint32_t aosp_id;
    uint32_t waveform;
    uint32_t richtap_id;
};

const Slot kEffectSlots[] = {
    /* Predefined effects: the bins stock's HAL plays for these ids. CLICK is
     * stock's system click (waveform 2, "weak short vibrate"), and
     * DOUBLE_CLICK its reject pattern (46, "moderate short vibrate twice"). */
    {0 /* CLICK */, 2, 0},
    {1 /* DOUBLE_CLICK */, 46, 1},
    {2 /* TICK */, 2, 2},
    {3 /* THUD */, 3, 3},
    {4 /* POP */, 4, 4},
    {5 /* HEAVY_CLICK */, 5, 5},
    /* Slots for stock waveforms AOSP has no effect for (see
     * haptic_feedback_customization.xml for the constants using them). */
    {6 /* RINGTONE_1 */, 7, 2},     /* weak granular: EDGE_SQUEEZE */
    {7 /* RINGTONE_2 */, 56, 5},    /* long vibrate (8 kHz): SAFE_MODE_ENABLED */
    {8 /* RINGTONE_3 */, 310, 0},   /* virtual key feedback: VIRTUAL_KEY */
    {21 /* TEXTURE_TICK */, 1, 2},  /* weakest short: CLOCK_TICK, TEXT_HANDLE_MOVE */
};

/* Compose primitives have no stock counterpart (stock's HAL has no compose());
 * keep the per-bin character map this device has used since the op13 tables. */
const Slot kPrimitiveSlots[] = {
    {0 /* NOOP */, kNoWaveform, 0},
    {1 /* CLICK */, 2, 1},
    {2 /* THUD */, 1, 2},
    {3 /* SPIN */, 3, 3},
    {4 /* QUICK_RISE */, 6, 4},
    {5 /* SLOW_RISE */, 6, 5},
    {6 /* QUICK_FALL */, 7, 6},
    {7 /* LIGHT_TICK */, 7, 7},
    {8 /* LOW_TICK */, 305, 8},
};

/* Stock VibrationEffectConvertor.getEffectIdFromTiming() -> waveform. */
struct ShortPulse {
    uint32_t max_ms;
    uint32_t waveform;
};

const ShortPulse kShortPulses[] = {
    {12, 7},   /* effect 68: weak granular */
    {52, 2},   /* effect 1: weak short vibrate */
    {102, 6},  /* effect 69: strong granular */
};

const Slot* findSlot(const Slot* slots, size_t count, uint32_t id) {
    for (size_t i = 0; i < count; i++)
        if (slots[i].aosp_id == id) return &slots[i];
    return nullptr;
}

const effect_stream* findTable(const effect_stream* table, size_t count, uint32_t id) {
    for (size_t i = 0; i < count; i++)
        if (table[i].effect_id == id) return &table[i];
    return nullptr;
}

const int8_t kSilence[10] = {};
const effect_stream kNoopStream = {0, ARRAY_SIZE(kSilence), 24000, kSilence};

/* Stock def_style/soft_style waveforms from /odm/etc/vibrator. */
class StockWaveforms {
  public:
    static const StockWaveforms& get() {
        static const StockWaveforms instance;
        return instance;
    }

    bool loaded() const { return !def_.empty(); }

    const effect_stream* find(uint32_t waveform, bool soft) const {
        if (soft) {
            auto it = soft_.find(waveform);
            if (it != soft_.end()) return &it->second;
        }
        auto it = def_.find(waveform);
        return it != def_.end() ? &it->second : nullptr;
    }

  private:
    using Map = std::unordered_map<uint32_t, effect_stream>;

    StockWaveforms() {
        std::ifstream config(kStockConfig);
        if (!config) {
            LOG(ERROR) << "Cannot open " << kStockConfig << ", using RichTap tables";
            return;
        }

        Json::Value root;
        Json::CharReaderBuilder builder;
        std::string errs;
        if (!Json::parseFromStream(builder, config, &root, &errs) || !root.isObject()) {
            LOG(ERROR) << "Cannot parse " << kStockConfig << ": " << errs;
            return;
        }

        /* {"9999": {"def_style": [...], "soft_style": [...]}, "vibrator_arch": ...} */
        for (const auto& node : root) {
            if (!node.isObject() || !node[kDefStyle].isArray()) continue;
            load(node[kDefStyle], def_);
            load(node[kSoftStyle], soft_);
            break;
        }

        LOG(INFO) << "Loaded " << def_.size() << " crisp and " << soft_.size()
                  << " gentle stock waveforms";
    }

    void load(const Json::Value& list, Map& out) {
        if (!list.isArray()) return;

        for (const auto& node : list) {
            if (!node["effect_id"].isUInt() || !node["play_rate_hz"].isUInt() ||
                !node["effect_file"].isString())
                continue;

            const uint32_t id = node["effect_id"].asUInt();
            const uint32_t rate = node["play_rate_hz"].asUInt();
            const std::string path = node["effect_file"].asString();

            std::ifstream file(path, std::ios::binary);
            std::vector<char> raw((std::istreambuf_iterator<char>(file)),
                                  std::istreambuf_iterator<char>());
            if (raw.empty() || rate == 0) {
                LOG(WARNING) << "Skipping unreadable waveform " << path;
                continue;
            }

            auto data = std::make_unique<int8_t[]>(raw.size());
            std::copy(raw.begin(), raw.end(), data.get());
            out[id] = effect_stream{id, static_cast<uint32_t>(raw.size()), rate, data.get()};
            buffers_.push_back(std::move(data));
        }
    }

    Map def_;
    Map soft_;
    std::vector<std::unique_ptr<int8_t[]>> buffers_;
};

const effect_stream* richtapEffect(uint32_t id) {
    const Slot* slot = findSlot(kEffectSlots, ARRAY_SIZE(kEffectSlots), id);
    return findTable(effects, ARRAY_SIZE(effects), slot ? slot->richtap_id : id);
}

const effect_stream* richtapPrimitive(uint32_t id) {
    return findTable(primitives, ARRAY_SIZE(primitives), id);
}

float strengthRatio(const char* prop, int min, int def) {
    const int strength = GetIntProperty(prop, def, min, kStrengthFull);
    return static_cast<float>(strength) / kStrengthFull;
}

int16_t toMagnitude(float ratio) {
    const int m = static_cast<int>(ratio * kMagnitudeMax + 0.5f);
    return static_cast<int16_t>(std::clamp(m, 1, kMagnitudeMax));
}

}  // anonymous namespace

const struct effect_stream* get_effect_stream(uint32_t effect_id) {
    const Profile profile = currentProfile();
    const StockWaveforms& stock = StockWaveforms::get();
    const bool useStock = profile != Profile::RICHTAP && stock.loaded();
    const bool soft = profile == Profile::GENTLE;

    if ((effect_id & kPrimitiveMask) != 0) {
        const uint32_t id = effect_id & ~kPrimitiveMask;
        if (!useStock) return richtapPrimitive(id);

        const Slot* slot = findSlot(kPrimitiveSlots, ARRAY_SIZE(kPrimitiveSlots), id);
        if (!slot) return nullptr;
        if (slot->waveform == kNoWaveform) return &kNoopStream;

        const effect_stream* s = stock.find(slot->waveform, soft);
        return s ? s : richtapPrimitive(id);
    }

    if (!useStock) return richtapEffect(effect_id);

    const Slot* slot = findSlot(kEffectSlots, ARRAY_SIZE(kEffectSlots), effect_id);
    if (!slot) return nullptr;

    const effect_stream* s = stock.find(slot->waveform, soft);
    return s ? s : richtapEffect(effect_id);
}

uint64_t get_supported_effects_mask(void) {
    /* Reported once at boot (cached in VibratorInfo), so it has to cover every
     * profile; richtap plays its nearest table entry for the extra slots. */
    uint64_t mask = 0;
    for (const auto& slot : kEffectSlots) mask |= 1ULL << slot.aosp_id;
    return mask;
}

const struct effect_stream* get_short_vibration_stream(uint32_t duration_ms) {
    if (duration_ms == 0) return nullptr;

    const Profile profile = currentProfile();
    const StockWaveforms& stock = StockWaveforms::get();
    if (profile == Profile::RICHTAP || !stock.loaded()) return nullptr;

    for (const auto& pulse : kShortPulses) {
        if (duration_ms <= pulse.max_ms)
            return stock.find(pulse.waveform, profile == Profile::GENTLE);
    }
    return nullptr;
}

int16_t scale_effect_magnitude(uint32_t kind, int16_t magnitude, float amplitude) {
    switch (kind) {
        case EFFECT_PLAY_PERFORM: {
            /* MEDIUM, the framework's default touch strength, plays at the
             * touch strength; LIGHT/STRONG (Vibration & haptics settings) stay
             * relative to it. */
            const float touch = strengthRatio(kPropTouchStrength, kTouchStrengthMin,
                                              kTouchStrengthDefault);
            return toMagnitude(touch * magnitude / kMagnitudeMedium);
        }
        case EFFECT_PLAY_PRIMITIVE: {
            const float touch = strengthRatio(kPropTouchStrength, kTouchStrengthMin,
                                              kTouchStrengthDefault);
            return toMagnitude(touch * magnitude / kMagnitudeMax);
        }
        case EFFECT_PLAY_ON_SHORT: {
            /* Stock converts the step amplitude to strength * 2400, floored at
             * 800 (VibrationEffectConvertor.convertEffectStrength). */
            const float a = amplitude < 0.0f ? 1.0f : std::clamp(amplitude, 1.0f / 3, 1.0f);
            const float touch = strengthRatio(kPropTouchStrength, kTouchStrengthMin,
                                              kTouchStrengthDefault);
            return toMagnitude(a * touch);
        }
        case EFFECT_PLAY_ON: {
            const float vib = strengthRatio(kPropVibStrength, kVibStrengthMin,
                                            kVibStrengthDefault);
            return toMagnitude(vib * magnitude / kMagnitudeMax);
        }
        default:
            return magnitude;
    }
}
