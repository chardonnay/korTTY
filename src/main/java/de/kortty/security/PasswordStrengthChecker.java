package de.kortty.security;

import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;

/**
 * Checks master password length and strength using zxcvbn (offline, no network).
 * Used to warn users about weak passwords while still allowing them to proceed if they choose.
 */
public final class PasswordStrengthChecker {

    /** Minimum allowed length for master password. */
    public static final int MIN_LENGTH = 6;

    /** zxcvbn score 0 = Weak, 1 = Fair, 2 = Good, 3 = Strong, 4 = Very strong. We warn when score <= 1. */
    private static final int WEAK_SCORE_THRESHOLD = 1;

    private static final Zxcvbn ZXCVBN = new Zxcvbn();

    private PasswordStrengthChecker() {}

    /**
     * Returns true if the password meets the minimum length requirement.
     */
    public static boolean hasMinimumLength(String password) {
        return password != null && password.length() >= MIN_LENGTH;
    }

    /**
     * Returns true if the password is considered weak (zxcvbn score 0 or 1, or too short).
     * The user may still be allowed to use it after a warning.
     */
    public static boolean isWeak(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return true;
        }
        Strength strength = ZXCVBN.measure(password);
        return strength.getScore() <= WEAK_SCORE_THRESHOLD;
    }

    /**
     * Returns a short reason why the password is weak (for use in warning message).
     * Returns null if the password is not weak.
     */
    public static String getWeakReason(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "too_short";
        }
        Strength strength = ZXCVBN.measure(password);
        if (strength.getScore() > WEAK_SCORE_THRESHOLD) {
            return null;
        }
        if (strength.getFeedback() != null && strength.getFeedback().getWarning() != null
                && !strength.getFeedback().getWarning().isEmpty()) {
            return strength.getFeedback().getWarning();
        }
        return "weak";
    }
}
