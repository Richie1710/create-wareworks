package dev.wareworks.core.stock;

/**
 * What {@link StockRule#checked} had to correct about the three numbers it was given (M15, issue #3).
 * <p>
 * A rule silently clamps whatever it is constructed with — crafted save data must never throw and a screen must never
 * be able to store a number the rest of the code has to guard against. A player, on the other hand, has to be told
 * when the value they scrolled to is not the value that was stored, which is what this enum is for: the screen shows
 * one line, and {@link StockRule#checked} reports at most one reason.
 * <p>
 * <b>Precedence</b>, most specific first: {@link #MAXIMUM_RAISED_TO_MINIMUM}, {@link #RESERVE_CLAMPED_TO_MAXIMUM},
 * {@link #VALUE_CLAMPED}, {@link #NONE}. A cross-correction is reported over a plain range clamp because it changes a
 * number the player did not touch.
 * <p>
 * {@link #name()} is a stable name; new values are appended, because the content layer puts the ordinal on the wire.
 */
public enum StockRuleAdjustment {
    /** The numbers were stored exactly as given. */
    NONE,
    /**
     * A value was outside {@code -1 … } {@link StockRule#MAX_AMOUNT} and was clamped into it. A negative value reads
     * as "off" ({@link StockRule#UNSET}), a value above the cap as the cap.
     */
    VALUE_CLAMPED,
    /**
     * The minimum was above the maximum, so the maximum was raised to it. "Keep at least 64, store at most 32" cannot
     * be obeyed in either direction, and raising the cap is the reading that keeps the item flowing.
     */
    MAXIMUM_RAISED_TO_MINIMUM,
    /**
     * The reserve was above the maximum, so it was lowered to it. A reserve the warehouse may never reach would hold
     * back every single item of the key from automation for ever.
     */
    RESERVE_CLAMPED_TO_MAXIMUM
}
