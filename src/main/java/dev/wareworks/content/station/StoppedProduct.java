package dev.wareworks.content.station;

import java.util.Objects;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.StockRulePause;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One product of a warehouse production station the <b>safety stop</b> is holding (M20, issue #4, ADR-032): the item,
 * why the warehouse stopped making it, and what that cost.
 * <p>
 * <b>Why a station-side type at all.</b> The pause itself lives in the controller, keyed by item
 * ({@link StockRulePause}), because the controller is the one thing that is always loaded when an order could start.
 * What a <i>player</i> needs is the other direction: standing in front of a machine, which of the things this machine
 * makes has been stopped, and what it cost. This is that answer, and it is the single answer four surfaces are built
 * from — the station's screen ({@link ProductionScreenState#stopped()}), its goggle lines, its block state and the chat
 * line a resume writes — so none of them can disagree with the others about a machine.
 * <p>
 * It is a <b>derived</b> value, never saved: the controller's pause map is the one saved copy (ADR-027). The cause is
 * carried rather than a boolean because the four causes read differently to a player: "the last order timed out" is a
 * broken machine, "an order at a machine was given up" is usually their own click.
 *
 * @param key         the item the warehouse has stopped making
 * @param cause       how the order that armed the stop ended
 * @param unrecovered ingredient items that were already at the machine when it ended and that nothing takes back
 *                    ({@code docs/warehouse-system.md} §3.5.4) — what this loss cost
 */
public record StoppedProduct(ItemKey key, StockRulePause.Cause cause, long unrecovered) {
    /** Longest cause name a payload may claim; the bound is what keeps a hostile one from claiming a megabyte. */
    private static final int MAX_CAUSE_LENGTH = 32;

    public StoppedProduct {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cause, "cause");
        unrecovered = Math.max(0L, unrecovered);
    }

    /** The stopped product {@code key} stands for, as the controller's pause describes it. */
    public static StoppedProduct of(ItemKey key, StockRulePause pause) {
        Objects.requireNonNull(pause, "pause");
        return new StoppedProduct(key, pause.cause(), pause.unrecovered());
    }

    /** Lang key of the sentence that says why the warehouse stopped, shared with the stock keeper's own screen. */
    public String causeKey() {
        return cause.langKey();
    }

    /** Writes one entry; the cause travels by <b>name</b>, so appending a constant can never shift another one. */
    public void write(RegistryFriendlyByteBuf buffer) {
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeUtf(cause.name(), MAX_CAUSE_LENGTH);
        buffer.writeVarLong(unrecovered);
    }

    /** Reads an entry written by {@link #write}; an unknown cause name reads as the oldest one instead of throwing. */
    public static StoppedProduct read(RegistryFriendlyByteBuf buffer) {
        ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
        String cause = buffer.readUtf(MAX_CAUSE_LENGTH);
        return new StoppedProduct(key, StockRulePause.Cause.byName(cause).orElse(StockRulePause.Cause.TIMED_OUT),
                buffer.readVarLong());
    }
}
