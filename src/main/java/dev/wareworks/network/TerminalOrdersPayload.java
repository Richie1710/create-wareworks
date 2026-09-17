package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.ProductionScreenState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the production orders of a warehouse terminal's aisle, for the one player whose screen is open
 * ({@code docs/warehouse-system.md} §3.4.2, ADR-024).
 * <p>
 * It is a payload of its own rather than a part of {@link TerminalStatusPayload} because an order line carries an
 * {@code ItemKey} with data components, while the status is a fixed-size record of numbers and enum ordinals that is
 * sent whenever a crane moves. Keeping them apart means a warehouse whose orders do not change pays nothing for them,
 * and the status keeps its guaranteed size.
 * <p>
 * The lines are exactly the ones the production station's own screen shows
 * ({@link ProductionScreenState.OrderView}, one wire form for both screens), and both directions are bounded at
 * {@value #MAX_ORDERS} entries whatever a length prefix claims. Sent only when the list changed, under the menu's
 * throttle, so an idle aisle costs no packet at all.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param orders      the aisle's production orders, newest last
 */
public record TerminalOrdersPayload(int containerId, List<ProductionScreenState.OrderView> orders)
        implements CustomPacketPayload {
    /** Most order lines one payload carries; the terminal sends the newest ones (§3.4.2). */
    public static final int MAX_ORDERS = ProductionScreenState.MAX_ORDERS;

    public static final CustomPacketPayload.Type<TerminalOrdersPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_orders"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalOrdersPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalOrdersPayload::write, TerminalOrdersPayload::new);

    public TerminalOrdersPayload {
        Objects.requireNonNull(orders, "orders");
        orders = List.copyOf(orders.size() <= MAX_ORDERS ? orders : orders.subList(0, MAX_ORDERS));
    }

    private TerminalOrdersPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), readOrders(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(orders.size());
        for (ProductionScreenState.OrderView order : orders)
            order.write(buffer);
    }

    private static List<ProductionScreenState.OrderView> readOrders(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), MAX_ORDERS);
        List<ProductionScreenState.OrderView> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            orders.add(ProductionScreenState.OrderView.read(buffer));
        return orders;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
