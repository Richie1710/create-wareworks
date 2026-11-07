package dev.wareworks.client.gui;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.station.WarehouseOptimizerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The client's lookup of a Warehouse Optimizer block entity, for the menu's client factory (issue #46, ADR-079).
 * <p>
 * The menu itself lives in {@code content.station} and is loaded on a dedicated server, so it must not name
 * {@code Minecraft} or {@code ClientLevel}. The lookup therefore lives here and is referenced by its fully qualified name
 * from inside {@code OptimizerMenu#createOnClient}, which never runs on a server — the pattern of
 * {@link ClientStockKeepers}.
 * <p>
 * Client only.
 */
public final class ClientOptimizers {
    private ClientOptimizers() {
    }

    /**
     * The optimizer at {@code pos} in the client level, or {@code null} while no level is loaded or the block entity
     * there is missing or of another type.
     */
    @Nullable
    public static WarehouseOptimizerBlockEntity at(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof WarehouseOptimizerBlockEntity optimizer)
            return optimizer;
        return null;
    }
}
