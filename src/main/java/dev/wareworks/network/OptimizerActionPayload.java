package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.OptimizerAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: a click in a Warehouse Optimizer's screen (issue #46, ADR-079) — put this item into that row, empty
 * it, add the clipboard on the cursor, optimise every row or one of them, or stop the running order.
 * <p>
 * <b>Nothing in it is trusted, and nothing in it moves an item.</b> A row is a ghost item: the server writes the
 * {@link ItemKey} into the optimizer's row and never takes anything out of an inventory. A clipboard is read from the
 * server's own cursor of the player's open menu, never from anything the payload describes. The server resolves the
 * payload against the menu that player really has open ({@code OptimizerMenu#submit}), checks their reach and bounds
 * how many actions one tick may carry, and the warehouse bounds what an order may cost ({@code optimizer.tripsPerRequest}).
 * <p>
 * The action travels by <b>name</b>: a build that does not know one reads it as none, and the server drops it.
 *
 * @param containerId the menu the player has open
 * @param action      what the player asked for, empty for an action this build does not know
 * @param row         the row it is about (set, clear, optimise one row); ignored otherwise
 * @param key         the item to set, only read for {@link OptimizerAction#SET_ITEM}
 */
public record OptimizerActionPayload(int containerId, Optional<OptimizerAction> action, int row,
                                     Optional<ItemKey> key) implements CustomPacketPayload {
    /** Longest action name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_ACTION_NAME = 24;

    public static final CustomPacketPayload.Type<OptimizerActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("optimizer_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OptimizerActionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(OptimizerActionPayload::write, OptimizerActionPayload::new);

    public OptimizerActionPayload {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(key, "key");
    }

    /** An action about the whole optimizer (Optimise, Cancel, add the clipboard on the cursor). */
    public static OptimizerActionPayload of(int containerId, OptimizerAction action) {
        return new OptimizerActionPayload(containerId, Optional.of(action), 0, Optional.empty());
    }

    /** An action about one row (empty it, optimise it alone). */
    public static OptimizerActionPayload ofRow(int containerId, OptimizerAction action, int row) {
        return new OptimizerActionPayload(containerId, Optional.of(action), row, Optional.empty());
    }

    /** Puts {@code key} into {@code row}. */
    public static OptimizerActionPayload setItem(int containerId, int row, ItemKey key) {
        return new OptimizerActionPayload(containerId, Optional.of(OptimizerAction.SET_ITEM), row,
                Optional.of(Objects.requireNonNull(key, "key")));
    }

    private OptimizerActionPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), OptimizerAction.byName(buffer.readUtf(MAX_ACTION_NAME)), buffer.readVarInt(),
                buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        // By name, not by ordinal: a build that does not know an action reads it as none instead of as a shifted one.
        buffer.writeUtf(action.map(OptimizerAction::name).orElse(""), MAX_ACTION_NAME);
        buffer.writeVarInt(row);
        buffer.writeBoolean(key.isPresent());
        key.ifPresent(value -> ItemKey.STREAM_CODEC.encode(buffer, value));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
