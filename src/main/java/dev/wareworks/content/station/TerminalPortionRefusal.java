package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.PlanRefusal;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Why the last portion of a terminal's list order was refused: the clipboard order's or the craft order's
 * ({@code docs/warehouse-system.md} §3.4.4, §3.4.5, issue #24, ADR-062).
 * <p>
 * An order steps over a line the warehouse refuses and tries it again in a later pass, so a refusal never ends it
 * ({@code ListOrder#refused}). Without this the order would simply wait, and the player would see the progress stand
 * still without a reason. With it, the status row names the item in the way — "Oak Log is missing" — through the very
 * sentence a refused click gets: the plan refusal behind the request when a production chain could not be planned,
 * and otherwise the request's own rejection.
 * <p>
 * <b>Transient.</b> It is measured against the warehouse as it is, so it is never saved: a reloaded order makes its
 * next portion and finds out again. It is bounded — one enum, at most one more, one item — and compares by value, so a
 * pass that is refused for the same reason again costs the screen no packet.
 *
 * @param rejection why the request was refused
 * @param refusal   why the production chain behind it could not be planned, if that is what refused it
 * @param about     the item the sentence names: the item of the plan refusal when there is one (regularly not the
 *                  portion's own item), otherwise the portion's item
 */
public record TerminalPortionRefusal(RequestRejection rejection, Optional<PlanRefusal> refusal, ItemKey about) {
    /** Longest enum name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_NAME = 32;

    public TerminalPortionRefusal {
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(about, "about");
    }

    /**
     * The refusal a refused portion of {@code portionKey} reports, or empty for a result that is no refusal at all.
     */
    public static Optional<TerminalPortionRefusal> of(RequestResult result, ItemKey portionKey) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(portionKey, "portionKey");
        if (result.rejection().isEmpty())
            return Optional.empty();
        return Optional.of(new TerminalPortionRefusal(result.rejection().get(), result.refusal(),
                result.about().orElse(portionKey)));
    }

    /** Writes this refusal into a payload; every enum by name, so a later build may add constants. */
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(rejection.name(), MAX_NAME);
        buffer.writeUtf(refusal.map(PlanRefusal::name).orElse(""), MAX_NAME);
        ItemKey.STREAM_CODEC.encode(buffer, about);
    }

    /** Writes {@code refusal}, or the one byte that says there is none. */
    public static void write(RegistryFriendlyByteBuf buffer, Optional<TerminalPortionRefusal> refusal) {
        buffer.writeBoolean(refusal.isPresent());
        refusal.ifPresent(value -> value.write(buffer));
    }

    /**
     * Reads what {@link #write(RegistryFriendlyByteBuf, Optional)} wrote. A rejection this build does not know reads as
     * no refusal at all and a plan refusal it does not know as none — and either way the item is still read, so the
     * rest of the payload stays in step.
     */
    public static Optional<TerminalPortionRefusal> read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return Optional.empty();
        Optional<RequestRejection> rejection = RequestRejection.byName(buffer.readUtf(MAX_NAME));
        Optional<PlanRefusal> refusal = planRefusal(buffer.readUtf(MAX_NAME));
        ItemKey about = ItemKey.STREAM_CODEC.decode(buffer);
        return rejection.map(reason -> new TerminalPortionRefusal(reason, refusal, about));
    }

    private static Optional<PlanRefusal> planRefusal(String name) {
        if (name == null || name.isEmpty())
            return Optional.empty();
        for (PlanRefusal refusal : PlanRefusal.values()) {
            if (refusal.name().equals(name))
                return Optional.of(refusal);
        }
        return Optional.empty();
    }
}
