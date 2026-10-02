package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: what a click would cost across the boundaries a stock keeper set, i.e. the question the terminal
 * puts up before it makes the request (M15 part 2, {@code docs/warehouse-system.md} §3.6.6).
 * <p>
 * It is the answer to a {@link TerminalRequestPayload} whose acknowledgement did not cover the cost, and it means
 * <b>nothing was requested</b>: no item is promised, no production order was started, and the station's last refusal is
 * untouched, because a question is not a refusal. The screen shows the numbers and, if the player confirms, sends the
 * same request again with {@link RequestConfirmation#acknowledgement()} — which the server measures once more before it
 * acts on it.
 * <p>
 * The numbers are the <b>server's</b>: a screen knows neither the aisle's production patterns nor what their
 * ingredients are promised to, so it could never work out that four planks cost a log a rule protects. Reading is
 * bounded at {@value ProductionPattern#MAX_INGREDIENTS} ingredients, whatever the length prefix claims.
 *
 * <b>The same question is raised by one portion of a clipboard order</b> (M23, issue #19): a list order is the same
 * stream of ordinary requests with nobody watching, so it stops on exactly this panel. {@link #scope()} is what tells
 * the screen where the answer belongs — back to the click as a {@link TerminalRequestPayload}, or to the order as a
 * {@link TerminalListActionPayload}. Nothing else about the question differs.
 *
 * @param containerId the menu the question belongs to; the screen ignores one for another menu
 * @param question    what the request would cross
 * @param scope       who the request was for, i.e. where the answer goes back to
 */
public record TerminalConfirmPayload(int containerId, RequestConfirmation<ItemKey> question, RequestScope scope)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalConfirmPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_confirm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalConfirmPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalConfirmPayload::write, TerminalConfirmPayload::new);

    public TerminalConfirmPayload {
        Objects.requireNonNull(question, "question");
        if (scope == null)
            scope = RequestScope.CLICK;
    }

    /** The question a player's own click raised, which is every question before M23. */
    public TerminalConfirmPayload(int containerId, RequestConfirmation<ItemKey> question) {
        this(containerId, question, RequestScope.CLICK);
    }

    private TerminalConfirmPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), readQuestion(buffer), readScope(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, question.key());
        buffer.writeVarLong(question.amount());
        buffer.writeVarLong(question.fromReserve());
        buffer.writeVarLong(question.reserved());
        buffer.writeVarLong(question.pastMaximum());
        // What the order would make in total, which is the "of these n" the panel names beside the part of it that
        // cannot be stored (M15 review fix).
        buffer.writeVarLong(question.made());
        // The cap as "cap + 1", so "no cap" is one zero byte instead of the ten a negative varlong takes.
        buffer.writeVarLong(question.maximum() + 1L);
        List<RequestConfirmation.ReservedIngredient<ItemKey>> ingredients = question.ingredients();
        buffer.writeVarInt(ingredients.size());
        for (RequestConfirmation.ReservedIngredient<ItemKey> ingredient : ingredients) {
            ItemKey.STREAM_CODEC.encode(buffer, ingredient.key());
            buffer.writeVarLong(ingredient.fromReserve());
            buffer.writeVarLong(ingredient.reserved());
        }
        // Last, and by name: a scope a build does not know reads as a plain click, which is the answer that can only
        // ever be refused by the server rather than acted on in the wrong place (M23).
        buffer.writeUtf(scope.name());
    }

    private static RequestScope readScope(RegistryFriendlyByteBuf buffer) {
        String name = buffer.readUtf();
        for (RequestScope scope : RequestScope.values()) {
            if (scope.name().equals(name))
                return scope;
        }
        return RequestScope.CLICK;
    }

    private static RequestConfirmation<ItemKey> readQuestion(RegistryFriendlyByteBuf buffer) {
        ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
        long amount = buffer.readVarLong();
        long fromReserve = buffer.readVarLong();
        long reserved = buffer.readVarLong();
        long pastMaximum = buffer.readVarLong();
        long made = buffer.readVarLong();
        long maximum = buffer.readVarLong() - 1L;
        int count = Math.min(Math.max(0, buffer.readVarInt()), ProductionPattern.MAX_INGREDIENTS);
        List<RequestConfirmation.ReservedIngredient<ItemKey>> ingredients = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            ingredients.add(new RequestConfirmation.ReservedIngredient<>(ItemKey.STREAM_CODEC.decode(buffer),
                    buffer.readVarLong(), buffer.readVarLong()));
        return new RequestConfirmation<>(key, amount, fromReserve, reserved, pastMaximum, made,
                maximum < 0L ? StockRule.UNSET : maximum, ingredients);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
