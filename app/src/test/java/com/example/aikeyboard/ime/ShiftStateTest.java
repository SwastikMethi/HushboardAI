// Shift key behaviour: one-shot, caps lock by double tap, and automatic sentence capitals.
package com.example.aikeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.ime.ShiftState.Mode;

import org.junit.Test;

public class ShiftStateTest {

    private final ShiftState shift = new ShiftState();

    @Test
    public void startsOff() {
        assertEquals(Mode.OFF, shift.mode());
        assertFalse(shift.isUpperCase());
    }

    @Test
    public void oneTapCapitalisesOnlyTheNextCharacter() {
        shift.onShiftTapped(1_000);
        assertTrue(shift.isUpperCase());
        shift.onCharacterTyped();
        assertEquals(Mode.OFF, shift.mode());
    }

    @Test
    public void aQuickSecondTapLocksCapitals() {
        shift.onShiftTapped(1_000);
        shift.onShiftTapped(1_200);
        assertEquals(Mode.LOCKED, shift.mode());
        shift.onCharacterTyped();
        shift.onCharacterTyped();
        assertEquals(Mode.LOCKED, shift.mode());
    }

    @Test
    public void aSlowSecondTapTurnsShiftOff() {
        shift.onShiftTapped(1_000);
        shift.onShiftTapped(3_000);
        assertEquals(Mode.OFF, shift.mode());
    }

    @Test
    public void tappingWhileLockedUnlocks() {
        shift.onShiftTapped(1_000);
        shift.onShiftTapped(1_200);
        shift.onShiftTapped(5_000);
        assertEquals(Mode.OFF, shift.mode());
    }

    @Test
    public void autoCapsCapitalisesTheStartOfASentence() {
        shift.onAutoCaps(true);
        assertEquals(Mode.ONCE, shift.mode());
        shift.onCharacterTyped();
        shift.onAutoCaps(false);
        assertEquals(Mode.OFF, shift.mode());
    }

    @Test
    public void autoCapsNeverOverridesCapsLock() {
        shift.onShiftTapped(1_000);
        shift.onShiftTapped(1_200);
        shift.onAutoCaps(false);
        assertEquals(Mode.LOCKED, shift.mode());
    }

    @Test
    public void turningShiftOffByHandBeatsAutoCapsUntilSomethingIsTyped() {
        shift.onAutoCaps(true);
        shift.onShiftTapped(1_000);
        assertEquals(Mode.OFF, shift.mode());
        shift.onAutoCaps(true);
        assertEquals("the user's choice stands", Mode.OFF, shift.mode());
        shift.onCharacterTyped();
        shift.onAutoCaps(true);
        assertEquals(Mode.ONCE, shift.mode());
    }
}
