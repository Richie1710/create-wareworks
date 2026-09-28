package dev.wareworks.content.station;

import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Makes the production station's <b>sneak-click</b> reach the block whatever the player is carrying in the offhand (M20
 * review fix, issue #4, ADR-032).
 * <p>
 * Lifting the safety stop at the machine is taught in two places — the block's item description ("Sneak-Right-Click this
 * block") and the goggle hint ("Sneak-click the station to make them again") — and it is the one way back that needs no
 * screen. Vanilla, however, only dispatches a <b>sneaking</b> interaction to a block when <i>both</i> hands answer
 * {@code IItemStackExtension#doesSneakBypassUse}: an empty hand does, and NeoForge's default for an item is
 * {@code false}. So with an empty main hand and a shield, torch, map or piece of food in the offhand, a sneaking player
 * clicks the station and {@code useItemOn} is never called at all — neither is {@code useWithoutItem}, which sits inside
 * the same guard ({@code ServerPlayerGameMode#useItemOn}, {@code MultiPlayerGameMode#performUseItemOn}). The gesture
 * silently did nothing, which is worse than not having it: a player who has read the tooltip concludes the stop cannot be
 * lifted.
 * <p>
 * The supported way past that guard is the interaction event itself: {@code setUseBlock(TRUE)} makes the block's own
 * {@code useItemOn} run regardless. It is asked <b>only</b> for this block, only for the main hand, only while sneaking
 * and only with an empty main hand — exactly the shape {@link WarehouseProductionBlock#useItemOn} handles, so nothing
 * else a player does with a held item changes. The event fires on both sides, and the listener answers the same way on
 * both, so the client's own prediction matches what the server does instead of flickering.
 * <p>
 * Registered once from {@code Wareworks} on the game bus; safe on both dists.
 */
public final class ProductionStationHooks {
    private ProductionStationHooks() {
    }

    /** Registers the hooks on the NeoForge game event bus. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(ProductionStationHooks::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (forcesBlockUse(event.getEntity(), event.getLevel(), event.getPos(), event.getHand()))
            event.setUseBlock(TriState.TRUE);
    }

    /**
     * Whether this right-click is the station's resume gesture and has to be handed to the block although the player is
     * sneaking. Package-visible and free of the event so a GameTest can ask it directly: the whole point of the fix is a
     * case {@code GameTestHelper#useBlock} cannot reach, because that helper calls {@code useItemOn} itself and never
     * runs the game mode's sneak guard.
     */
    public static boolean forcesBlockUse(Player player, Level level, BlockPos pos, InteractionHand hand) {
        if (player == null || level == null || pos == null || hand != InteractionHand.MAIN_HAND)
            return false;
        if (!player.isSecondaryUseActive() || !player.getMainHandItem().isEmpty())
            return false;
        // Nothing in the offhand may change the answer, and nothing else is forced: only our own block, only empty-handed.
        return level.getBlockState(pos).is(WareworksBlocks.WAREHOUSE_PRODUCTION.get());
    }
}
