package dev.wareworks.util;

/**
 * Pending flag and minimum interval for syncing derived goggle data to clients, shared by every block entity that
 * syncs a goggle summary on observation ({@link GoggleObservers}).
 * <p>
 * A change marks a sync pending ({@link #markPending()}); the owner asks {@link #tryConsume(long)} on the next
 * observation, which allows at most one sync per interval. A throttled sync stays pending and is sent on a later
 * observation. Server thread only.
 */
public final class SyncThrottle {
    private static final long NEVER = Long.MIN_VALUE;

    private final int minIntervalTicks;
    private boolean pending;
    private long lastSyncTick = NEVER;

    /** @param minIntervalTicks minimum game ticks between two syncs (at least 0) */
    public SyncThrottle(int minIntervalTicks) {
        if (minIntervalTicks < 0)
            throw new IllegalArgumentException("minIntervalTicks must not be negative: " + minIntervalTicks);
        this.minIntervalTicks = minIntervalTicks;
    }

    /** The synced data changed; a sync is due. */
    public void markPending() {
        pending = true;
    }

    public boolean isPending() {
        return pending;
    }

    /**
     * Whether a pending sync may be sent at game time {@code now}. If so, it counts as sent: the pending flag is cleared
     * and the time recorded, so the caller must send it.
     */
    public boolean tryConsume(long now) {
        if (!pending)
            return false;
        if (lastSyncTick != NEVER && now - lastSyncTick < minIntervalTicks)
            return false;
        pending = false;
        lastSyncTick = now;
        return true;
    }
}
