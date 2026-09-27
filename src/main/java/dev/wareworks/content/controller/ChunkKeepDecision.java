package dev.wareworks.content.controller;

/**
 * Whether an aisle takes, keeps or releases the chunk tickets of its own footprint, as a pure function of what the
 * controller observed (M19, issue #10, ADR-031). {@code WarehouseControllerBlockEntity} does the observing;
 * {@code AisleChunkTickets} does the taking; this decides — exactly the split {@code CranePauseDecision} follows, and for
 * the same reason: the table below is the part no world test can cover cheaply.
 * <p>
 * <b>The rule.</b> An aisle <i>has work</i> when its crane has a job (including {@code HOLDING} and
 * {@code WAITING_FOR_TARGET}), when a retrieval request is open, when a production order is open (an automatic restock
 * order is one) or — only with the separate opt-in — when a gated-open collecting port has something pending. Everything
 * else is idle, <b>including a warehouse input that holds buffered items</b>: a buffer cannot change while its own chunk
 * does not tick, so it can never <i>appear</i> while the aisle is unloaded, and the moment it is planned it becomes a
 * crane job. As a hold reason it would let one forgotten item hold chunks forever.
 * <ul>
 * <li><b>Take</b> in the very tick the transition is observed, while the footprint fits and a level slot is free.</li>
 * <li><b>Keep</b> while there is work and the hold is younger than {@code maxHoldTicks}.</li>
 * <li><b>Release</b> {@code releaseDelayTicks} after the last work — the linger that keeps a burst of jobs from making
 * the tickets thrash.</li>
 * <li><b>Give up</b> after {@code maxHoldTicks} of uninterrupted holding: release, and refuse to take again until the
 * work really changed (or disappeared). This clause is what makes "never a permanent loader" true rather than
 * aspirational — an open request whose output can never be served, or a crane stuck in {@code HOLDING}, would otherwise
 * hold chunks forever.</li>
 * <li><b>Refuse</b> over any cap: hold nothing at all (never a partial hold) and behave exactly as before M19, which is
 * to say the crane pauses with {@code CHUNK_NOT_LOADED} while its chunks are away. Each of the three caps has a reason
 * of its own, so an operator can see which setting stopped which aisle.</li>
 * </ul>
 * The order of the clauses carries two rules the table would otherwise only imply (both M19 review findings):
 * <ol>
 * <li><b>Idle beats every refusal.</b> An aisle with nothing to do reports nothing, whatever flag it carries.</li>
 * <li><b>The caps are checked for a hold that exists, not only for one being taken.</b> Otherwise a cap lowered while an
 * aisle holds, or an aisle extended while it holds, would leave the hold over the bound with nothing said about it.</li>
 * </ol>
 * Pure (no {@code Level}, no block entity, no config access), so all of it is unit tested.
 */
public final class ChunkKeepDecision {
    /** {@link Decision#recheckAtTick()} when nothing needs to be looked at again on a deadline. */
    public static final long NO_RECHECK = Long.MAX_VALUE;
    /** {@link State#idleSince()} / {@link State#heldSince()} when the aisle was never idle / is not holding. */
    public static final long NOT_SET = Long.MIN_VALUE;

    private ChunkKeepDecision() {
    }

    /** What the controller should do with its tickets this tick. */
    public enum Action {
        /** Start holding the footprint. */
        TAKE,
        /** Go on holding it (and top it up if it is not complete yet). */
        KEEP,
        /** Let every chunk go. */
        RELEASE,
        /** There is work, but a cap forbids holding: hold nothing and report {@code reason}. */
        REFUSE,
        /** Nothing to do at all (the feature is off, or the aisle is idle and holds nothing). */
        NONE
    }

    /**
     * @param action        what to do
     * @param reason        what to report on the goggles
     * @param recheckAtTick the game tick at which the decision must be taken again even without an event, or
     *                      {@link #NO_RECHECK}
     */
    public record Decision(Action action, ChunkKeepReason reason, long recheckAtTick) {
        public Decision {
            if (action == null)
                action = Action.NONE;
            if (reason == null)
                reason = ChunkKeepReason.NONE;
        }

        static Decision of(Action action, ChunkKeepReason reason) {
            return new Decision(action, reason, NO_RECHECK);
        }
    }

    /**
     * What the aisle is doing.
     *
     * @param craneJob       the linked dock has a job
     * @param openRequests   open retrieval requests
     * @param openOrders     open production orders (automatic restock orders included)
     * @param collectPending gated-open collecting ports with something pending; only looked at with the opt-in
     */
    public record Work(boolean craneJob, int openRequests, int openOrders, int collectPending) {
        public static final Work NONE = new Work(false, 0, 0, 0);

        public Work {
            openRequests = Math.max(0, openRequests);
            openOrders = Math.max(0, openOrders);
            collectPending = Math.max(0, collectPending);
        }

        /** Whether anything at all is going on, with collecting counted only when {@code collectCounts}. */
        public boolean any(boolean collectCounts) {
            return hard() || (collectCounts && collectPending > 0);
        }

        /** Work that exists whatever the collecting opt-in says. */
        public boolean hard() {
            return craneJob || openRequests > 0 || openOrders > 0;
        }

        /** The reason this work is reported as, most specific first. */
        public ChunkKeepReason reason(boolean collectCounts) {
            if (craneJob)
                return ChunkKeepReason.CRANE_JOB;
            if (openRequests > 0)
                return ChunkKeepReason.OPEN_REQUESTS;
            if (openOrders > 0)
                return ChunkKeepReason.PRODUCTION_ORDERS;
            return collectCounts && collectPending > 0 ? ChunkKeepReason.COLLECTING : ChunkKeepReason.NONE;
        }
    }

    /**
     * The configured bounds and what the level's ledger allows right now.
     *
     * @param featureEnabled    {@code chunkLoading.maxTicketedAislesPerLevel > 0}
     * @param collectHoldEnabled {@code chunkLoading.maxCollectHoldAislesPerLevel > 0} <b>and</b> the feature itself is
     *                           on: the opt-in alone can hold nothing, so it must not make the caller read the world
     *                           for a collect count either
     * @param levelCapAllows    this aisle may hold without exceeding {@code maxTicketedAislesPerLevel}
     * @param collectCapAllows  this aisle may hold <b>for collecting alone</b> without exceeding
     *                          {@code maxCollectHoldAislesPerLevel}
     * @param footprintChunks   how many chunks this aisle's footprint needs ({@link AisleChunkSpan})
     * @param maxChunks         {@code chunkLoading.maxChunksPerAisle}
     * @param releaseDelayTicks the linger after the last work
     * @param maxHoldTicks      the longest uninterrupted hold; {@code 0} means unlimited
     */
    public record Limits(boolean featureEnabled, boolean collectHoldEnabled, boolean levelCapAllows,
                         boolean collectCapAllows, int footprintChunks, int maxChunks, int releaseDelayTicks,
                         int maxHoldTicks) {
        public Limits {
            footprintChunks = Math.max(0, footprintChunks);
            maxChunks = Math.max(0, maxChunks);
            releaseDelayTicks = Math.max(0, releaseDelayTicks);
            maxHoldTicks = Math.max(0, maxHoldTicks);
        }
    }

    /**
     * Where the aisle stands.
     *
     * @param holding   it holds at least one chunk right now
     * @param heldSince the tick the hold began, or {@link #NOT_SET}
     * @param idleSince the tick the last work disappeared, or {@link #NOT_SET} while there is work
     * @param gaveUp    it gave up on this work and must not take again until the work changes
     */
    public record State(boolean holding, long heldSince, long idleSince, boolean gaveUp) {
        public static final State IDLE = new State(false, NOT_SET, NOT_SET, false);
    }

    /** The decision for this tick. */
    public static Decision decide(Work work, Limits limits, State state, long now) {
        // The feature being off is the first thing checked, so switching it off releases whatever is held and an aisle
        // that never held pays one boolean.
        if (!limits.featureEnabled())
            return state.holding() ? Decision.of(Action.RELEASE, ChunkKeepReason.NONE)
                    : Decision.of(Action.NONE, ChunkKeepReason.NONE);
        // No aisle at all (no dock, or the dock was just broken): there is nothing to hold, and anything still held has
        // to go. Checked before the work, because an aisle without a footprint can still have open requests.
        if (limits.footprintChunks() <= 0)
            return Decision.of(state.holding() ? Action.RELEASE : Action.NONE, ChunkKeepReason.NONE);

        boolean collectCounts = limits.collectHoldEnabled() && limits.collectCapAllows();
        // Nothing to do is checked before every refusal, including the give-up flag: an aisle with no work is idle, and
        // saying "let go, holds again when its work changes" about an aisle that has nothing to do would be a lie the
        // operator's release could produce at any time (M19 review). The caller drops the flag in the same breath, which
        // is what lets finished work re-arm a hold whatever its fingerprint was.
        if (!work.any(collectCounts)) {
            if (!state.holding())
                return collectCapRefused(work, limits)
                        ? Decision.of(Action.REFUSE, ChunkKeepReason.AT_COLLECT_LIMIT)
                        : Decision.of(Action.NONE, ChunkKeepReason.NONE);
            long releaseAt = idleDeadline(state, limits, now);
            if (now >= releaseAt)
                return Decision.of(Action.RELEASE, ChunkKeepReason.NONE);
            return new Decision(Action.KEEP, ChunkKeepReason.RELEASING, releaseAt);
        }
        // Gave up on exactly this work: it stays refused until the caller clears the flag because the work changed.
        if (state.gaveUp())
            return Decision.of(state.holding() ? Action.RELEASE : Action.REFUSE, ChunkKeepReason.GAVE_UP);
        // Both caps are checked for a hold that already exists as well, not only at take time: a cap lowered while the
        // aisle holds, and an aisle extended while it holds, are exactly the two ways a hold could otherwise grow past
        // the bound the operator set (M19 review). A holder counts itself out of the level cap (AisleChunkTickets
        // #levelCapAllows), so a level merely AT its cap never evicts its own holders - only a genuinely lowered one.
        if (limits.footprintChunks() > limits.maxChunks())
            return Decision.of(state.holding() ? Action.RELEASE : Action.REFUSE, ChunkKeepReason.TOO_MANY_CHUNKS);
        if (!limits.levelCapAllows())
            return Decision.of(state.holding() ? Action.RELEASE : Action.REFUSE, ChunkKeepReason.AT_LEVEL_LIMIT);

        ChunkKeepReason reason = work.reason(collectCounts);
        if (state.holding()) {
            long giveUpAt = giveUpDeadline(state, limits);
            if (now >= giveUpAt)
                return Decision.of(Action.RELEASE, ChunkKeepReason.GAVE_UP);
            return new Decision(Action.KEEP, reason, giveUpAt);
        }
        return Decision.of(Action.TAKE, reason);
    }

    /**
     * Whether the <b>only</b> thing keeping this aisle from holding is the collect opt-in's own cap. Its own reason,
     * because an aisle queued behind that cap would otherwise be byte-for-byte an idle one: no goggle line, no log line
     * and no row in {@code /wareworks chunks} (M19 review).
     */
    private static boolean collectCapRefused(Work work, Limits limits) {
        return limits.collectHoldEnabled() && !limits.collectCapAllows() && work.collectPending() > 0 && !work.hard();
    }

    /** The tick an idle hold is released at; an unknown {@code idleSince} releases at once. */
    private static long idleDeadline(State state, Limits limits, long now) {
        if (state.idleSince() == NOT_SET)
            return now;
        return state.idleSince() + limits.releaseDelayTicks();
    }

    /** The tick a hold gives up at, or {@link #NO_RECHECK} while {@code maxHoldTicks} is unlimited. */
    private static long giveUpDeadline(State state, Limits limits) {
        if (limits.maxHoldTicks() == 0 || state.heldSince() == NOT_SET)
            return NO_RECHECK;
        return state.heldSince() + limits.maxHoldTicks();
    }
}
