#!/usr/bin/env python3
"""Generate op13_primitive_profiles.h from dodge's stock ColorOS def waveforms.

Primitive callbacks (compose()) are ~half of all haptics on this device, and the
op13* profiles had no stock primitive data at all -- they fell back to the YAAP
tables, whose gentle set peaks at 78/127 (~62%). That is the "weak" feel.

Each AOSP primitive is cut from a stock def bin chosen for matching character,
at a length matching the base table so framework-cached primitive durations stay
valid when switching profiles. Window edges snap to zero crossings so no DC step
is left mid-carrier. Nothing is resampled: the LRA resonant carrier is preserved
sample-for-sample.
"""
import os
import sys

# usage: gen_op13_primitives.py [<stock def bin dir>] [<output header>]
# The bin dir is a stock OOS dump's /odm/etc/vibrator/9999/def (not shipped in
# this tree -- the waveforms are baked into the generated header instead).
BINS = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
    '~/Pictures/workspace/dodge_imgs/odm/etc/vibrator/9999/def')
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), 'op13_primitive_profiles.h')

# primitive -> (name, target_len, stock def bin id, window anchor, rationale)
#   'head'  : window starts at sample 0            (attack + decay: clicks/ticks)
#   'peak'  : window starts at the peak            (decay only: falls)
#   'topeak': window ENDS at the peak              (crescendo: rises)
MAP = [
    (0, 'NOOP',        9, None, None,     'silence'),
    (1, 'CLICK',     208,  310, 'head',   'sharpest stock attack, peak 126'),
    (2, 'THUD',      392,    6, 'head',   'heavier body, peak lands later (0.42)'),
    (3, 'SPIN',      136,    1, 'head',   'short mid-level texture, peak 74'),
    (4, 'QUICK_RISE',112,  302, 'topeak', 'stock crescendo, fast ramp to peak 124'),
    (5, 'SLOW_RISE', 400,  302, 'topeak', 'same stock crescendo, long ramp'),
    (6, 'QUICK_FALL',176,  111, 'peak',   'stock decay from peak 105'),
    (7, 'TICK',      208,  110, 'head',   'sharp attack, lighter than CLICK (peak 96)'),
    (8, 'LOW_TICK',  112,    0, 'peak',   'stock light tap, decay from peak 58'),
]

# Amplitude factor per profile. op13crisp ships the stock waveforms untouched;
# op13gentle is scaled, but only to 0.80 -- the YAAP gentle set it replaced sat
# at ~0.62 of base, which is what felt weak. Retune here and re-run.
PROFILES = [('op13crisp', 1.00), ('op13gentle', 0.80)]


def load(bid):
    d = open(os.path.join(BINS, f'effect_{bid}.bin'), 'rb').read()
    return [x - 256 if x > 127 else x for x in d]


def snap(a, idx, limit=24):
    """Move idx to the nearest sample whose value is closest to zero."""
    lo, hi = max(0, idx - limit), min(len(a), idx + limit + 1)
    if lo >= hi:
        return max(0, min(idx, len(a)))
    return min(range(lo, hi), key=lambda i: abs(a[i]))


def window(a, target, anchor):
    pk = max(range(len(a)), key=lambda i: abs(a[i]))
    if anchor == 'head':
        s = 0
        e = snap(a, min(target, len(a)))
    elif anchor == 'peak':
        s = snap(a, pk)
        e = snap(a, min(s + target, len(a)))
    else:  # topeak
        e = snap(a, pk)
        s = snap(a, max(0, e - target))
    return a[s:e]


def scale(a, f):
    return [max(-127, min(127, int(round(x * f)))) for x in a]


def fmt(name, data):
    out = [f'static const int8_t {name}[] = {{']
    for i in range(0, len(data), 16):
        out.append('    ' + ', '.join(f'{v:4d}' for v in data[i:i + 16]) + ',')
    out[-1] = out[-1].rstrip(',')
    out.append('};')
    return '\n'.join(out)


hdr = ['''/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * OnePlus 13 stock primitive tables for the op13crisp / op13gentle profiles.
 *
 * compose() primitives are roughly half of all haptic callbacks on this device
 * (keyboard and UI taps go this way; only the six predefined effects use
 * perform()). The op13 profiles previously had no stock primitive data and fell
 * back to the YAAP tables, whose gentle set peaks at 78/127 -- that was the
 * weak feel. Each primitive below is cut from a stock ColorOS def waveform
 * (/odm/etc/vibrator/9999/def) chosen for matching character, at the same
 * length as the base table so the framework's boot-cached primitive durations
 * stay correct across a profile switch. Window edges snap to zero crossings so
 * no DC step is left mid-carrier; nothing is resampled, so the LRA resonant
 * frequency is preserved sample-for-sample.
 *
 * Generated -- do not hand-edit. See the mapping table for provenance.
 */
''']

summary = []
for pname, factor in PROFILES:
    for pid, nm, tlen, bid, anchor, why in MAP:
        if bid is None:
            data = [0] * tlen
        else:
            data = scale(window(load(bid), tlen, anchor), factor)
        hdr.append(f'/* {nm}: ' + (f'stock def effect_{bid} ({anchor}), {why}' if bid is not None else why) + f' */')
        hdr.append(fmt(f'primitive_{pid}_{pname}', data))
        hdr.append('')
        if factor == 1.00:
            pk = max((abs(x) for x in data), default=0)
            summary.append((nm, tlen, len(data), bid, anchor, pk))

for pname, _ in PROFILES:
    hdr.append(f'static const struct effect_stream primitives_{pname}[] = {{')
    for pid, nm, *_ in MAP:
        hdr.append(f'    {{ /* {nm} */ .effect_id = {pid}, '
                   f'.length = ARRAY_SIZE(primitive_{pid}_{pname}), '
                   f'.play_rate_hz = 24000, .data = primitive_{pid}_{pname} }},')
    hdr.append('};')
    hdr.append('')

open(OUT, 'w').write('\n'.join(hdr))
print(f'wrote {OUT}')
print(f"\n{'primitive':12s} {'target':>7} {'actual':>7} {'ms':>4} {'stock bin':>10} {'anchor':>7} {'peak':>5}")
for nm, tlen, alen, bid, anchor, pk in summary:
    print(f'{nm:12s} {tlen:7d} {alen:7d} {alen*1000//24000+1:4d} '
          f'{("def "+str(bid)) if bid is not None else "-":>10} {anchor or "-":>7} {pk:5d}')
