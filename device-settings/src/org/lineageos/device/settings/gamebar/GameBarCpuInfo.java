/*
 * Copyright (C) 2025 kenway214
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.device.settings.gamebar;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class GameBarCpuInfo {

    private static final String TAG = "GameBarCpuInfo";

    private static long sPrevIdle = -1;
    private static long sPrevTotal = -1;

    private static final String CPU_TEMP_PATH = "/sys/class/thermal/thermal_zone0/temp";
    private static final String CPU_SYSFS = "/sys/devices/system/cpu/";

    /**
     * Return CPU usage % (integer string). Returns "N/A" if not available (initial sample).
     */
    public static String getCpuUsage() {
        String line = readLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) return "N/A";
        String[] parts = line.split("\\s+");
        if (parts.length < 8) return "N/A";

        try {
            long user    = Long.parseLong(parts[1]);
            long nice    = Long.parseLong(parts[2]);
            long system  = Long.parseLong(parts[3]);
            long idle    = Long.parseLong(parts[4]);
            long iowait  = Long.parseLong(parts[5]);
            long irq     = Long.parseLong(parts[6]);
            long softirq = Long.parseLong(parts[7]);
            long steal   = parts.length > 8 ? Long.parseLong(parts[8]) : 0;

            long total = user + nice + system + idle + iowait + irq + softirq + steal;

            if (sPrevTotal != -1 && total != sPrevTotal) {
                long diffTotal = total - sPrevTotal;
                long diffIdle  = idle - sPrevIdle;
                int usage = (int) (100 * (diffTotal - diffIdle) / (double) diffTotal);
                sPrevTotal = total;
                sPrevIdle  = idle;
                return String.valueOf(usage);
            } else {
                sPrevTotal = total;
                sPrevIdle  = idle;
                return "N/A";
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed parsing /proc/stat", e);
            return "N/A";
        }
    }

    /**
     * Return a list of strings describing each CPU frequency, e.g. "cpu0: 1113 MHz" or
     * "cpu2: offline".
     * This method tries a couple of sysfs paths to be robust across kernels/boards.
     */
    public static List<String> getCpuFrequencies() {
        List<String> result = new ArrayList<>();
        File cpuDir = new File(CPU_SYSFS);
        File[] files = cpuDir.listFiles((dir, name) -> name.matches("cpu\\d+"));
        if (files == null || files.length == 0) {
            Log.w(TAG, "No cpuN folders found under " + CPU_SYSFS);
            return result;
        }

        List<File> cpuFolders = new ArrayList<>();
        Collections.addAll(cpuFolders, files);
        cpuFolders.sort(Comparator.comparingInt(GameBarCpuInfo::extractCpuNumber));

        for (File cpu : cpuFolders) {
            String cpuName = cpu.getName();
            String freq = readCpuFreqForCpu(cpu);
            if (freq == null) {
                result.add(cpuName + ": offline or freq unavailable");
            } else {
                result.add(cpuName + ": " + freq + " MHz");
            }
        }

        Log.d(TAG, "getCpuFrequencies -> " + result);
        return result;
    }

    /**
     * Return the maximum CPU MHz across cores (representative value for side-by-side).
     * Returns -1 if none found.
     */
    public static int getMaxCpuMhz() {
        List<String> freqs = getCpuFrequencies();
        int best = -1;
        for (String s : freqs) {
            // Expect format "cpuX: N MHz" or similar
            int colon = s.indexOf(':');
            String tail = colon >= 0 ? s.substring(colon + 1).trim() : s.trim();
            String digits = "";
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (Character.isDigit(c)) digits += c;
                else if (!digits.isEmpty()) break;
            }
            if (!digits.isEmpty()) {
                try {
                    int mhz = Integer.parseInt(digits);
                    if (mhz > best) best = mhz;
                } catch (NumberFormatException ignored) { }
            }
        }
        Log.d(TAG, "getMaxCpuMhz -> " + best);
        return best;
    }

    /**
     * Read CPU temperature. Returns a formatted string (e.g. "41.3") or "N/A"
     */
    public static String getCpuTemp() {
        String line = readLine(CPU_TEMP_PATH);
        if (line == null) return "N/A";
        line = line.trim();
        try {
            float raw = Float.parseFloat(line);
            // many devices use millidegrees (i.e. /1000) and some use deci-degrees (/10). Try heuristics:
            float c;
            if (raw > 1000) {
                // millidegrees
                c = raw / 1000f;
            } else if (raw > 100) {
                // deci-degrees (e.g., value 410 -> 41.0)
                c = raw / 10f;
            } else {
                // already degrees
                c = raw;
            }
            return String.format(Locale.getDefault(), "%.1f", c);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed parsing CPU temp from " + CPU_TEMP_PATH, e);
            return "N/A";
        }
    }

    // --- internal helpers ---

    private static String readCpuFreqForCpu(File cpuFolder) {
        // prefer scaling_cur_freq, fallback to cpuinfo_cur_freq, then try scaling_max or scaling_min if needed
        String[] candidates = new String[] {
            cpuFolder.getAbsolutePath() + "/cpufreq/scaling_cur_freq",
            cpuFolder.getAbsolutePath() + "/cpufreq/cpuinfo_cur_freq",
            cpuFolder.getAbsolutePath() + "/cpufreq/scaling_max_freq"
        };

        for (String p : candidates) {
            String v = readLine(p);
            if (v == null) continue;
            v = v.trim();
            if (v.isEmpty()) continue;
            // parse kHz -> MHz if numeric
            String digits = "";
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (Character.isDigit(c)) digits += c;
                else if (!digits.isEmpty()) break;
            }
            if (digits.isEmpty()) continue;
            try {
                int khz = Integer.parseInt(digits);
                int mhz = khz >= 1000 ? khz / 1000 : khz; // if value already in MHz, this keeps it reasonable
                return String.valueOf(mhz);
            } catch (NumberFormatException e) {
                // fallback to returning the raw trimmed string (non-numeric)
                return v;
            }
        }
        return null;
    }

    private static int extractCpuNumber(File cpuFolder) {
        String name = cpuFolder.getName().replace("cpu", "");
        try { return Integer.parseInt(name); }
        catch (NumberFormatException e) { return -1; }
    }

    private static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            // silent fail; caller will handle null
            return null;
        }
    }
}
