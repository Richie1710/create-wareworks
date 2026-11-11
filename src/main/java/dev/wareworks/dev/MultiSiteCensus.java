package dev.wareworks.dev;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * The item census of a scenario spread over several sites (dev tooling, ADR-014; shared by {@code robobee} and
 * {@code selector-remote}): {@link SceneItemCensus} of every site box, the player once, and every box and bee in the
 * air ({@link RoboBeeLedger#census}), which no inventory, entity or block reports.
 */
final class MultiSiteCensus {
    private MultiSiteCensus() {
    }

    /** Fails unless every box is fully loaded, then counts them all, the player and what flies. */
    static Map<ItemKey, Long> take(ServerLevel level, Player player, @Nullable RoboBeeLedger ledger, List<AABB> sites) {
        for (AABB box : sites)
            if (!SceneItemCensus.isFullyLoaded(level, box))
                throw new VisualTestException("a census box is not fully loaded: " + box);
        Map<ItemKey, Long> counts = SceneItemCensus.take(level, sites.getFirst(), player);
        for (AABB box : sites.subList(1, sites.size()))
            counts = merge(counts, SceneItemCensus.take(level, box));
        if (ledger != null)
            counts = merge(counts, ledger.census());
        return counts;
    }

    static Map<ItemKey, Long> merge(Map<ItemKey, Long> counts, Map<ItemKey, Long> more) {
        Map<ItemKey, Long> result = counts;
        for (Map.Entry<ItemKey, Long> entry : more.entrySet())
            result = SceneItemCensus.plus(result, entry.getKey(), entry.getValue());
        return result;
    }
}
