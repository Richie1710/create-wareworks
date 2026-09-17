package dev.wareworks.client.gui;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The client's lookup of a warehouse terminal block entity, for the menu's client factory
 * ({@code docs/architecture.md} ADR-019).
 * <p>
 * The menu itself lives in {@code content.station} and is loaded on a dedicated server, so it must not name
 * {@code Minecraft} or {@code ClientLevel} — those classes are {@code @OnlyIn(Dist.CLIENT)} and are removed there. The
 * lookup therefore lives here and is referenced by its fully qualified name from inside
 * {@code WarehouseTerminalMenu#createOnClient}, which never runs on a server, the same pattern
 * {@code WareworksNetwork} uses for {@link TerminalScreenUpdates}.
 * <p>
 * Client only.
 */
public final class ClientTerminals {
    private ClientTerminals() {
    }

    /**
     * The terminal at {@code pos} in the client level, or {@code null} while no level is loaded or the block entity at
     * that position is missing or of another type. The menu then builds its slots from the slot count the server sent.
     */
    @Nullable
    public static WarehouseTerminalBlockEntity at(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal)
            return terminal;
        return null;
    }
}
