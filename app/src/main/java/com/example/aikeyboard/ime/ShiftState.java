// Shift key state machine: off, one-shot, or locked. Pure logic, tested on the JVM.
package com.example.aikeyboard.ime;

public final class ShiftState {

    public enum Mode { OFF, ONCE, LOCKED }

    private static final long DOUBLE_TAP_MS = 350;

    private Mode mode = Mode.OFF;
    private long lastTapAt;
    // True while the current mode is the user's own choice, so auto-caps must leave it alone.
    private boolean manual;

    public Mode mode() {
        return mode;
    }

    public boolean isUpperCase() {
        return mode != Mode.OFF;
    }

    /** The shift key was tapped. A second tap soon after the first locks capitals. */
    public void onShiftTapped(long nowMs) {
        if (mode == Mode.ONCE && manual && nowMs - lastTapAt <= DOUBLE_TAP_MS) {
            mode = Mode.LOCKED;
        } else if (mode == Mode.OFF) {
            mode = Mode.ONCE;
        } else {
            mode = Mode.OFF;
        }
        manual = true;
        lastTapAt = nowMs;
    }

    /** A character was committed: one-shot shift is spent, and auto-caps may act again. */
    public void onCharacterTyped() {
        if (mode == Mode.ONCE) mode = Mode.OFF;
        manual = false;
    }

    /** The editor reports whether the next character starts a sentence. */
    public void onAutoCaps(boolean capitalise) {
        if (mode == Mode.LOCKED || manual) return;
        mode = capitalise ? Mode.ONCE : Mode.OFF;
    }
}
