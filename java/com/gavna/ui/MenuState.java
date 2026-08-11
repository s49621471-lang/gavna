package com.gavna.ui;

import com.gavna.GavnaLog;
import com.gavna.Native;

/** Menu state, mirrored into the native engine on every change. */
public final class MenuState {

    private final boolean[] features = new boolean[Native.FEATURE_COUNT];
    private int coinAmount = 999000000;
    private int coinPreset = 2;
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
        boolean ok = Native.setFeature(feature, on);
        GavnaLog.i("feature " + feature + " -> " + on + (ok ? "" : " (engine not ready)"));
    }

    public int coinAmount() {
        return coinAmount;
    }

    public int coinMillions() {
        return Widgets.clamp(coinAmount / 1000000, 1, 999);
    }

    public void setCoinAmount(int amount) {
        coinAmount = Widgets.clamp(amount, 1, 2000000000);
        Native.setValue(Native.VALUE_COIN_AMOUNT, coinAmount);
    }

    public int coinPreset() {
        return coinPreset;
    }

    public void setCoinPreset(int index) {
        coinPreset = index;
    }

    public int length() {
        return length;
    }

    public void setLength(int value) {
        length = Widgets.clamp(value, 2, 20000);
        Native.setValue(Native.VALUE_LENGTH, length);
    }

    public int lengthPreset() {
        return lengthPreset;
    }

    public void setLengthPreset(int index) {
        lengthPreset = index;
    }

    /** Pushes the whole state down again - used by the apply button. */
    public void reapply() {
        Native.setValue(Native.VALUE_COIN_AMOUNT, coinAmount);
        Native.setValue(Native.VALUE_LENGTH, length);
        for (int i = 0; i < features.length; i++) {
            Native.setFeature(i, features[i]);
        }
        GavnaLog.i("state reapplied");
    }
}
