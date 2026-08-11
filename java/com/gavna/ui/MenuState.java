package com.gavna.ui;

import com.gavna.Native;

/** Menu state, mirrored into the native engine on every change. */
public final class MenuState {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 50000;

    private final boolean[] features = new boolean[Native.FEATURE_COUNT];
    private int length = 500;
    private int lengthPreset = 2;

    public boolean isOn(int feature) {
        if (feature < 0 || feature >= features.length) {
            return false;
        }
        return features[feature];
    }

    public void setFeature(int feature, boolean on) {
        if (feature < 0 || feature >= features.length) {
            return;
        }
        features[feature] = on;
        Native.setFeature(feature, on);
    }

    public int length() {
        return length;
    }

    public void setLength(int value) {
        length = Widgets.clamp(value, MIN_LENGTH, MAX_LENGTH);
        Native.setValue(Native.VALUE_LENGTH, length);
    }

    public int lengthPreset() {
        return lengthPreset;
    }

    public void setLengthPreset(int index) {
        lengthPreset = index;
    }
}
