package dev.wareworks.core.handtruck;

/**
 * What carrying a loaded hand truck does to a player's walk (issue #37, ADR-070): the arithmetic only, so it can be
 * tested without Minecraft.
 * <p>
 * The penalty is applied where vanilla applies its own slow walks, sneaking and using an item: to the movement
 * <b>input</b> the client computes each tick ({@code LocalPlayer#aiStep}). Scaling both impulses keeps the direction a
 * player walks in and slows every direction alike. Sprinting is stopped by the same rule vanilla uses to stop it while
 * an item is in use: a sprint can neither start nor continue with less than {@value #SPRINT_IMPULSE} forward impulse
 * ({@code LocalPlayer#hasEnoughImpulseToStartSprinting}), so the forward impulse is capped just below it.
 * <p>
 * Who is carrying is not found by scanning anything. Vanilla already ticks every stack of a player's inventory each
 * tick, and a loaded truck writes the player's own tick counter into the player when it is ticked (not the game time,
 * which the client resets from the server and which stands still under {@code /tick freeze}); the player is carrying
 * while that
 * stamp is from this tick or the one before ({@link #withinWindow}), because the client reads its input before that
 * tick's inventory tick runs.
 */
public final class HandTruckPenalty {
    /** The forward impulse vanilla needs to start or keep a sprint on land. */
    public static final float SPRINT_IMPULSE = 0.8F;
    /** The forward impulse a carrying player is capped at while sprinting is prevented: just below a sprint. */
    public static final float MAX_FORWARD_WITHOUT_SPRINT = 0.79F;
    /** The stamp of a player who has never carried anything. */
    public static final long NEVER = Long.MIN_VALUE;
    /** The slowest walk the configuration may ask for. */
    public static final double MIN_MULTIPLIER = 0.1;
    /** No slow-down at all. */
    public static final double MAX_MULTIPLIER = 1.0;

    private HandTruckPenalty() {
    }

    /** A movement input: forward/backward and sideways impulse, each in -1..1 as vanilla's {@code Input} holds them. */
    public record Impulse(float forward, float left) {
    }

    /**
     * The input of a carrying player: both impulses times {@code multiplier} (clamped into
     * {@value #MIN_MULTIPLIER}..{@value #MAX_MULTIPLIER}), and with {@code preventSprint} the forward impulse capped at
     * {@value #MAX_FORWARD_WITHOUT_SPRINT}. Walking backwards is never capped: it cannot start a sprint.
     */
    public static Impulse scale(float forward, float left, double multiplier, boolean preventSprint) {
        float factor = (float) clampMultiplier(multiplier);
        float scaledForward = forward * factor;
        float scaledLeft = left * factor;
        if (preventSprint)
            scaledForward = Math.min(scaledForward, MAX_FORWARD_WITHOUT_SPRINT);
        return new Impulse(scaledForward, scaledLeft);
    }

    /** {@code multiplier} clamped into the range the configuration allows; a NaN counts as no slow-down. */
    public static double clampMultiplier(double multiplier) {
        if (Double.isNaN(multiplier))
            return MAX_MULTIPLIER;
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
    }

    /**
     * Whether a carry stamp written at the player's tick {@code stamp} still counts at the player's tick {@code now}:
     * written this tick or the one before. The counter only grows, so a stamp ahead of {@code now} is not a real one
     * and never counts, and neither does {@link #NEVER}.
     */
    public static boolean withinWindow(long now, long stamp) {
        if (stamp == NEVER)
            return false;
        long age = now - stamp;
        return age >= 0 && age <= 1;
    }
}
