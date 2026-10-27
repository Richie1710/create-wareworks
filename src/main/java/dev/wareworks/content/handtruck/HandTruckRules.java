package dev.wareworks.content.handtruck;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.registry.WareworksTags;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * What a hand truck may carry and who may use one (issue #37, ADR-070).
 * <p>
 * A block is carriable when it is in {@code wareworks:hand_truck_carriable} and not in
 * {@code wareworks:hand_truck_denied} — the client can tell that much, tags are synced — and, on the server, when its
 * block entity is a plain vanilla {@link Container} and the block can be broken at all. The container check is what
 * keeps Create's vaults, toolboxes and depots, and any block that only offers an item handler, off a truck even when
 * a pack tags them: everything the truck does afterwards — spilling the contents when it tips over, counting what it
 * holds — relies on the {@code Container} API.
 */
public final class HandTruckRules {
    private HandTruckRules() {
    }

    /** The tag half of the rule, which client and server both know. */
    public static boolean isCarriableByTag(BlockState state) {
        return state.is(WareworksTags.HAND_TRUCK_CARRIABLE) && !state.is(WareworksTags.HAND_TRUCK_DENIED);
    }

    /** The whole rule, on the server: tags, a plain container as block entity, and a block that can be broken. */
    public static boolean isCarriable(BlockGetter level, BlockPos pos, BlockState state, @Nullable BlockEntity be) {
        return isCarriableByTag(state) && be instanceof Container && state.getDestroySpeed(level, pos) >= 0;
    }

    /**
     * Whether {@code player} is a real player. A Deployer's fake player never uses a truck: what it holds leaves
     * through funnels, and a loaded truck must never leave a player.
     */
    public static boolean isRealPlayer(@Nullable Player player) {
        return player != null && !(player instanceof FakePlayer);
    }

    /** Tells {@code player} why something did not happen, on the action bar. */
    public static void tell(Player player, String relativeKey, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
    }
}
