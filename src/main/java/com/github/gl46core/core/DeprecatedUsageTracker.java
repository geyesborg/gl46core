package com.github.gl46core.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects information about mods/classes that call deprecated GL functions
 * which are no-ops in core profile. Populated during ASM transformation,
 * queried at mod init to warn the user.
 *
 * Thread-safe: ASM transformation may run on multiple threads during class loading.
 */
public final class DeprecatedUsageTracker {

    private DeprecatedUsageTracker() {}

    /**
     * Maps a human-readable feature name to the set of class names that use it.
     * Example: "Display Lists" → {"com.somemod.render.CustomRenderer", "com.other.ModelHelper"}
     */
    private static final Map<String, Set<String>> usageByFeature = new ConcurrentHashMap<>();

    public static void record(String featureName, String className) {
        usageByFeature
                .computeIfAbsent(featureName, k -> ConcurrentHashMap.newKeySet())
                .add(className);
    }

    public static boolean hasAnyUsage() {
        return !usageByFeature.isEmpty();
    }

    /**
     * Returns an unmodifiable snapshot of all recorded deprecated usage.
     * Keys are feature names, values are the classes that use them.
     */
    public static Map<String, Set<String>> getUsage() {
        Map<String, Set<String>> snapshot = new LinkedHashMap<>();
        for (var entry : usageByFeature.entrySet()) {
            snapshot.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Build a summary suitable for logging or display.
     * Groups by feature, lists affected classes (truncated to avoid flooding).
     */
    public static List<String> buildSummaryLines() {
        List<String> lines = new ArrayList<>();
        for (var entry : usageByFeature.entrySet()) {
            String feature = entry.getKey();
            Set<String> classes = entry.getValue();
            lines.add(feature + " (" + classes.size() + " class" + (classes.size() != 1 ? "es" : "") + ")");
            int shown = 0;
            for (String cls : classes) {
                if (shown >= 5) {
                    lines.add("    ... and " + (classes.size() - shown) + " more");
                    break;
                }
                lines.add("    " + cls);
                shown++;
            }
        }
        return lines;
    }
}
