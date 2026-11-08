package dev.wareworks.core.requester;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One order a Schematicannon Requester placed at its terminal (issue #45, ADR-080, amended by ADR-081): which item,
 * which request it went into, its own share of that request, what of the share production still makes for it, and
 * where it stands. Immutable; {@link RequesterOrders} replaces it as it moves on.
 *
 * @param key        the item ordered
 * @param strict     whether it was ordered for a strict requirement (exact components) rather than a plain one
 * @param requestId  the terminal request the share went into; shared with every amount merged into that request
 * @param share      the amount this order was granted, at least 1
 * @param ahead      what was in the merged request before this share when it was granted ({@link OrderShare})
 * @param aheadDelivered what of the merged request had already been delivered when this share was granted,
 *                   {@code 0..ahead}: delivered items are never taken off a request, so these stay ahead of the share
 *                   whatever is given back later ({@link OrderShare#returnable})
 * @param producing  the made part of the share, {@code 0..share}: what the production plan this order started still
 *                   owes it; 0 for a share served from stock alone and once that plan has ended (ADR-081)
 * @param root       the root production order of the plan this share started; present exactly when
 *                   {@code producing > 0}
 * @param unsure     the made part of the share whose root ended unseen, {@code 0..share - producing}: the controller
 *                   no longer knew that root when the requester next looked ({@code RootState.Kind#GONE}), so these
 *                   items may have been made or have fallen short and been taken off the request. They count towards
 *                   the share but are never given back ({@link RequesterOrders#giveBack}); 0 otherwise
 * @param phase      where the order stands
 * @param liveAge    ticks the order has been running while the terminal and its controller were loaded and no
 *                   production order was making its item
 * @param graceUntil the game time the grace ends ({@link Phase#GRACE} only, 0 otherwise)
 * @param <K>        item key type
 */
public record RequesterOrder<K>(K key, boolean strict, UUID requestId, int share, int ahead, int aheadDelivered,
                                int producing, Optional<UUID> root, int unsure, Phase phase, int liveAge,
                                long graceUntil) {
    /** Where an order stands. */
    public enum Phase {
        /** The request waits for the share. */
        RUNNING,
        /** The share was delivered (or the request is gone) and its item still lies in the terminal's buffer. */
        IN_BUFFER,
        /** The item left the buffer and is on its way to the cannon; the requester waits a moment. */
        GRACE;

        /** The name a phase is saved by, lower case. */
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The phase of a {@link #serializedName()} (case is ignored), or empty for anything else. */
        public static Optional<Phase> byName(String name) {
            if (name == null || name.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    public RequesterOrder {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(phase, "phase");
        if (share < 1)
            throw new IllegalArgumentException("share must be at least 1: " + share);
        if (ahead < 0)
            throw new IllegalArgumentException("ahead must not be negative: " + ahead);
        if (aheadDelivered < 0 || aheadDelivered > ahead)
            throw new IllegalArgumentException("aheadDelivered must be within 0.." + ahead + ": " + aheadDelivered);
        if (producing < 0 || producing > share)
            throw new IllegalArgumentException("producing must be within 0.." + share + ": " + producing);
        if ((producing > 0) != root.isPresent())
            throw new IllegalArgumentException("a made part has a root production order and only a made part has one: "
                    + producing + ", " + root);
        if (unsure < 0 || unsure > share - producing)
            throw new IllegalArgumentException("unsure must be within 0.." + (share - producing) + ": " + unsure);
        if (liveAge < 0)
            throw new IllegalArgumentException("liveAge must not be negative: " + liveAge);
    }

    /** An order served from stock alone: nothing of it is made (the component list of 0.20.0-beta). */
    public RequesterOrder(K key, boolean strict, UUID requestId, int share, int ahead, int aheadDelivered, Phase phase,
            int liveAge, long graceUntil) {
        this(key, strict, requestId, share, ahead, aheadDelivered, 0, Optional.empty(), phase, liveAge, graceUntil);
    }

    /** An order with nothing of unknown fate in its share. */
    public RequesterOrder(K key, boolean strict, UUID requestId, int share, int ahead, int aheadDelivered,
            int producing, Optional<UUID> root, Phase phase, int liveAge, long graceUntil) {
        this(key, strict, requestId, share, ahead, aheadDelivered, producing, root, 0, phase, liveAge, graceUntil);
    }

    /** A fresh order: running, no live age yet, nothing of the request delivered when it was granted, nothing made. */
    public static <K> RequesterOrder<K> placed(K key, boolean strict, UUID requestId, int share, int ahead) {
        return placed(key, strict, requestId, share, ahead, 0);
    }

    /** A fresh order from stock: running, no live age yet; {@code aheadDelivered} is clamped to {@code 0..ahead}. */
    public static <K> RequesterOrder<K> placed(K key, boolean strict, UUID requestId, int share, int ahead,
            int aheadDelivered) {
        return placed(key, strict, requestId, share, ahead, aheadDelivered, 0, Optional.empty());
    }

    /**
     * A fresh order of which {@code producing} items are made by the plan of {@code root} (ADR-081): running, no live
     * age yet; {@code aheadDelivered} is clamped to {@code 0..ahead}.
     */
    public static <K> RequesterOrder<K> placed(K key, boolean strict, UUID requestId, int share, int ahead,
            int aheadDelivered, int producing, Optional<UUID> root) {
        return new RequesterOrder<>(key, strict, requestId, share, ahead,
                Math.max(0, Math.min(aheadDelivered, ahead)), producing, root, Phase.RUNNING, 0, 0L);
    }

    /** Whether the request still waits for this share. */
    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** Whether production still makes part of this share: it runs, and the plan it started has not ended yet. */
    public boolean isMaking() {
        return phase == Phase.RUNNING && producing > 0;
    }

    RequesterOrder<K> withAhead(int newAhead) {
        // Never above what is ahead: a lower floor only ever gives back less (OrderShare#returnable).
        return new RequesterOrder<>(key, strict, requestId, share, newAhead, Math.min(aheadDelivered, newAhead),
                producing, root, unsure, phase, liveAge, graceUntil);
    }

    RequesterOrder<K> withLiveAge(int newLiveAge) {
        return new RequesterOrder<>(key, strict, requestId, share, ahead, aheadDelivered, producing, root, unsure,
                phase, newLiveAge, graceUntil);
    }

    /** This order with its own production plan over: production owes the share nothing any more. */
    RequesterOrder<K> withMadeEnded() {
        return producing == 0 ? this
                : new RequesterOrder<>(key, strict, requestId, share, ahead, aheadDelivered, 0, Optional.empty(),
                        unsure, phase, liveAge, graceUntil);
    }

    /**
     * This order with its own production plan ended unseen ({@code RootState.Kind#GONE}): {@code cut} items fewer in
     * its share, taken off the made part, and the rest of the made part {@link #unsure()}; at least 1 must be left.
     */
    RequesterOrder<K> withMadeGone(int cut) {
        int taken = Math.max(0, Math.min(cut, producing));
        int left = share - taken;
        if (left < 1)
            throw new IllegalArgumentException("a cut of " + cut + " leaves nothing of a share of " + share);
        return new RequesterOrder<>(key, strict, requestId, left, ahead, aheadDelivered, 0, Optional.empty(),
                unsure + producing - taken, phase, liveAge, graceUntil);
    }

    /** This order with {@code cut} items fewer in its share (a production shortfall); at least 1 must be left. */
    RequesterOrder<K> withShareCut(int cut) {
        int left = share - Math.max(0, cut);
        if (left < 1)
            throw new IllegalArgumentException("a cut of " + cut + " leaves nothing of a share of " + share);
        int made = Math.min(producing, left);
        return new RequesterOrder<>(key, strict, requestId, left, ahead, aheadDelivered, made,
                made > 0 ? root : Optional.empty(), Math.min(unsure, left - made), phase, liveAge, graceUntil);
    }

    RequesterOrder<K> inBuffer() {
        return new RequesterOrder<>(key, strict, requestId, share, ahead, aheadDelivered, producing, root, unsure,
                Phase.IN_BUFFER, liveAge, 0L);
    }

    RequesterOrder<K> withGraceUntilCleared() {
        return graceUntil == 0L ? this
                : new RequesterOrder<>(key, strict, requestId, share, ahead, aheadDelivered, producing, root, unsure,
                        phase, liveAge, 0L);
    }

    RequesterOrder<K> inGrace(long until) {
        return new RequesterOrder<>(key, strict, requestId, share, ahead, aheadDelivered, producing, root, unsure,
                Phase.GRACE, liveAge, until);
    }
}
