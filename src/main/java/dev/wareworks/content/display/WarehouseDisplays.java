package dev.wareworks.content.display;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseRegistry;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Shared plumbing of the four Display Link sources ({@code docs/warehouse-system.md} §10, ADR-026).
 * <p>
 * Common code only: a display link gathers its data on the server and sends the finished text to its target, so nothing
 * here may touch a client class.
 */
final class WarehouseDisplays {
    private WarehouseDisplays() {
    }

    /**
     * The controller whose aisle the link's source block belongs to.
     * <p>
     * A controller answers for itself; every other source block (terminal, output, interface) is resolved through
     * {@link WarehouseRegistry#findController}, which is a containment test per registered controller of the level plus
     * one block entity lookup — no world search and no inventory scan. Empty where the block is part of no aisle, is
     * misaligned, or its controller's chunk is not loaded; the sources then show their "no aisle" text rather than a
     * stale number.
     */
    static Optional<WarehouseControllerBlockEntity> controller(DisplayLinkContext context) {
        BlockEntity source = context.getSourceBlockEntity();
        if (source instanceof WarehouseControllerBlockEntity controller)
            return controller.isRemoved() ? Optional.empty() : Optional.of(controller);
        return WarehouseRegistry.findController(context.level(), context.getSourcePos());
    }

    /**
     * The first {@code stats.maxRows()} lines (at least one), unmodifiable.
     * <p>
     * Lines are not clipped to {@code stats.maxColumns()}: that would need {@code Component#getString()} on the server,
     * which resolves the line against the <b>server's</b> language — English on a dedicated server, where NeoForge
     * loads every mod's {@code en_us.json} into the default table ({@code LanguageHook#loadModLanguages}) — so every
     * player would be shown that one language and no client could translate the line any more. Targets that cannot
     * show a whole line cut it themselves — a nixie tube row shows two characters per tube, a sign flattens to its own
     * width.
     */
    static List<MutableComponent> limit(List<MutableComponent> lines, DisplayTargetStats stats) {
        int rows = Math.max(1, stats.maxRows());
        return List.copyOf(lines.size() <= rows ? lines : lines.subList(0, rows));
    }
}
