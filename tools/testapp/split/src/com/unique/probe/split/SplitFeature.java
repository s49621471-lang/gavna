package com.unique.probe.split;

/**
 * A class that exists only in the feature split.
 *
 * Loading it from the base APK's code proves the split's dex actually reached the class
 * loader, which it does not unless ApplicationInfo.splitSourceDirs was populated.
 */
public final class SplitFeature {
    private SplitFeature() {}

    public static String greeting() {
        return "hello-from-split";
    }
}
