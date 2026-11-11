package com.nightlight.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsernameValidationTest {

    @Test
    public void acceptsValidUsernames() {
        assertTrue(AccountPrefs.isValidUsername("ashu"));
        assertTrue(AccountPrefs.isValidUsername("sagar264"));
        assertTrue(AccountPrefs.isValidUsername("nightlight_user"));
        assertTrue(AccountPrefs.isValidUsername("Ed-Sheeran.99"));
        assertTrue(AccountPrefs.isValidUsername("  sagar264  "));
    }

    @Test
    public void rejectsSpaces() {
        assertFalse(AccountPrefs.isValidUsername("ashu pathak"));
        assertFalse(AccountPrefs.isValidUsername("hello world"));
        assertFalse(AccountPrefs.isValidUsername("a b"));
    }

    @Test
    public void rejectsBadLengths() {
        assertFalse(AccountPrefs.isValidUsername("ab"));
        assertFalse(AccountPrefs.isValidUsername(""));
        assertFalse(AccountPrefs.isValidUsername("   "));
        assertFalse(AccountPrefs.isValidUsername("abcdefghijklmnopqrstu"));
        assertFalse(AccountPrefs.isValidUsername(null));
    }

    @Test
    public void rejectsBadCharacters() {
        assertFalse(AccountPrefs.isValidUsername("ashu@pathak"));
        assertFalse(AccountPrefs.isValidUsername("sagar!"));
        assertFalse(AccountPrefs.isValidUsername("a/b"));
    }
}
