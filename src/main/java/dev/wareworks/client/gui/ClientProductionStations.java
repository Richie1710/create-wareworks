package dev.wareworks.client.gui;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The client's lookup of a warehouse production station block entity, for the menu's client factory
 * ({@code docs/warehouse-system.md} §3.5).
 * <p>
 * The menu itself lives in {@code content.station} and is loaded on a dedicated server, so it must not name
 * {@code Minecraft} or {@code ClientLevel}: those are {@code @OnlyIn(Dist.CLIENT)} and are stripped there. The lookup
 * therefore lives here and is referenced by its fully qualified name from inside
 * {@code ProductionMenu#createOnClient}, which never runs on a server — the same pattern {@code ClientTerminals} uses.
 * <p>
 * Client only.
 */
public final class ClientProductionStations {
    private ClientProductionStations() {
    }

    /**
     * The production station at {@code pos} in the client level, or {@code null} while no level is loaded or the block
     * entity there is missing or of another type. The menu then builds its slots from the counts the server sent.
     */
    @Nullable
    public static WarehouseProductionBlockEntity at(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station)
            return station;
        return null;
    }
}
