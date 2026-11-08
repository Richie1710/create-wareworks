package dev.wareworks.core.requester;

/**
 * The bounds of a Schematicannon Requester (issue #45, ADR-080): how often it looks at its cannon, when its dead-man
 * switch gives an order up, how often it may ask the cannon for a fresh material list, how long it waits after its items
 * left the terminal's buffer, and how much it keeps. The arithmetic only, so it can be tested without Minecraft.
 * <p>
 * The three configurable numbers ({@code requester.pollIntervalTicks}, {@code requester.orderTimeoutTicks},
 * {@code requester.checklistRefreshTicks}) are clamped here as well, rather than trusting a file a player edits. The
 * grace is a value on the block, clamped here too. The order and counter caps are constants because they bound what a
 * save may hold.
 */
public final class RequesterLimits {
    /** The shortest poll interval the configuration may set, in ticks: twice a second. */
    public static final int MIN_POLL_TICKS = 10;
    /** The longest poll interval the configuration may set, in ticks: ten seconds. */
    public static final int MAX_POLL_TICKS = 200;
    /** The shipped poll interval, in ticks: once a second, which is also the back-off after a refusal. */
    public static final int DEFAULT_POLL_TICKS = 20;

    /** The shortest dead-man switch the configuration may set, in live ticks: thirty seconds. */
    public static final int MIN_TIMEOUT_TICKS = 600;
    /** The longest dead-man switch the configuration may set, in live ticks: an hour. */
    public static final int MAX_TIMEOUT_TICKS = 72_000;
    /** The shipped dead-man switch, in live ticks: ten minutes of a warehouse that could have served the order. */
    public static final int DEFAULT_TIMEOUT_TICKS = 12_000;

    /** The shortest pause between two material list refreshes of one requester, in ticks: ten seconds. */
    public static final int MIN_REFRESH_TICKS = 200;
    /** The longest pause between two material list refreshes of one requester, in ticks: an hour. */
    public static final int MAX_REFRESH_TICKS = 72_000;
    /** The shipped pause between two material list refreshes of one requester, in ticks: a minute. */
    public static final int DEFAULT_REFRESH_TICKS = 1_200;

    /** The shortest grace after the items left the buffer, in seconds: none, for a funnel straight into the chest. */
    public static final int MIN_GRACE_SECONDS = 0;
    /** The longest grace after the items left the buffer, in seconds: a long belt. */
    public static final int MAX_GRACE_SECONDS = 60;
    /** The shipped grace after the items left the buffer, in seconds: a belt of a few dozen blocks. */
    public static final int DEFAULT_GRACE_SECONDS = 10;

    /**
     * How many orders one requester keeps at a time, in any phase. While the cannon waits it misses one item, so two or
     * three are live at most; a fourth is the margin.
     */
    public static final int MAX_ORDERS = 4;
    /**
     * How many distinct items the "already ordered" counter of one print holds. An item past it counts as unknown to the
     * material list, which means one stack ({@link ChecklistNeed}).
     */
    public static final int MAX_COUNTED_ITEMS = 512;
    /** How long the line about an order the dead-man switch gave up stays in the goggles, in ticks: a minute. */
    public static final int GAVE_UP_SHOWN_TICKS = 1_200;
    /**
     * How many polls in a row the cannon must be without a print (or gone) before the requester gives its running
     * orders back: one poll could be a cannon whose chunk is reloading.
     */
    public static final int IDLE_POLLS_BEFORE_GIVE_BACK = 2;
    /**
     * How many polls a requester waits before it asks the warehouse to make something again after an attempt that was
     * refused or came back short (ADR-081): walking the aisle's patterns once a poll for an item nobody can make would
     * cost more than it could ever bring.
     */
    public static final int MAKE_RETRY_POLLS = 5;

    private static final int TICKS_PER_SECOND = 20;

    private RequesterLimits() {
    }

    /** {@code ticks} clamped into {@value #MIN_POLL_TICKS}..{@value #MAX_POLL_TICKS}. */
    public static int clampPoll(int ticks) {
        return Math.max(MIN_POLL_TICKS, Math.min(MAX_POLL_TICKS, ticks));
    }

    /** The wait before the next attempt to have something made: {@value #MAKE_RETRY_POLLS} polls of {@code pollTicks}. */
    public static int makeRetryTicks(int pollTicks) {
        return MAKE_RETRY_POLLS * clampPoll(pollTicks);
    }

    /** {@code ticks} clamped into {@value #MIN_TIMEOUT_TICKS}..{@value #MAX_TIMEOUT_TICKS}. */
    public static int clampTimeout(int ticks) {
        return Math.max(MIN_TIMEOUT_TICKS, Math.min(MAX_TIMEOUT_TICKS, ticks));
    }

    /** {@code ticks} clamped into {@value #MIN_REFRESH_TICKS}..{@value #MAX_REFRESH_TICKS}. */
    public static int clampRefresh(int ticks) {
        return Math.max(MIN_REFRESH_TICKS, Math.min(MAX_REFRESH_TICKS, ticks));
    }

    /** {@code seconds} clamped into {@value #MIN_GRACE_SECONDS}..{@value #MAX_GRACE_SECONDS}. */
    public static int clampGraceSeconds(int seconds) {
        return Math.max(MIN_GRACE_SECONDS, Math.min(MAX_GRACE_SECONDS, seconds));
    }

    /** The grace of {@code seconds} (clamped) in ticks. */
    public static int graceTicks(int seconds) {
        return clampGraceSeconds(seconds) * TICKS_PER_SECOND;
    }

    /**
     * How many live ticks a poll at {@code now} adds to a running order's age when the previous poll that reached the
     * terminal was at {@code lastPoll}: the time in between, but never more than two poll intervals (clamped). A
     * requester, terminal or controller that was not loaded in between, or a stopped server, could not have made
     * progress, so that time does not count towards the dead-man switch. A first poll ({@code lastPoll < 0}) and a clock
     * that went backwards add nothing.
     */
    public static int liveStep(long now, long lastPoll, int pollTicks) {
        if (lastPoll < 0 || now <= lastPoll)
            return 0;
        return (int) Math.min(now - lastPoll, 2L * clampPoll(pollTicks));
    }
}
