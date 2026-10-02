package dev.wareworks.core.terminal;

import java.util.Objects;

/**
 * One portion of a list order: the next ordinary retrieval request the order wants made
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036).
 * <p>
 * <b>A portion is not a new kind of request.</b> It is a key and an amount, handed to precisely the call a click goes
 * through, with the order's consent budget and {@link RequestScope#LIST}. Everything downstream — merging (ADR-020),
 * reserves, maxima, filters, priorities, chains, the safety stop and the full-destination back-off — cannot tell it
 * from a click, which is the whole "no second request system" requirement of issue #19.
 * <p>
 * <b>Why the amount is what it is.</b> It is what the line still needs, bounded by {@code maxTerminalRequestAmount} —
 * not one stack. A request above one stack is already split into successive {@code RETRIEVE} jobs by the planner
 * ({@code §3.4.1}), and a destination that cannot take any more is a planner skip rather than a refusal, so asking for
 * a stack at a time would buy nothing and cost a round of measuring per stack. A whole schematic's worth of material
 * is therefore ordered a portion at a time without the order ever having to know how much fits.
 *
 * @param line   the index of the line in {@link ListOrder#lines()} this portion is for. Lines are never removed from
 *               an order, so the index stays valid for the order's whole life and is what
 *               {@link ListOrder#granted} and {@link ListOrder#refused} are told about
 * @param key    the item to fetch
 * @param amount how many of it to ask for, at least 1
 * @param <K>    item key type
 */
public record ListPortion<K>(int line, K key, int amount) {
    public ListPortion {
        Objects.requireNonNull(key, "key");
        if (line < 0)
            throw new IllegalArgumentException("line must not be negative: " + line);
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
    }
}
