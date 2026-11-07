package dev.wareworks.content.requester;

import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Makes the Schematicannon Requester's <b>sneak-click</b>, which takes the Warehouse Remote out, reach the block
 * whatever the player is carrying in the off hand (issue #45, ADR-080), as {@code ProductionStationHooks} does for the
 * production station's resume.
 * <p>
 * Vanilla only dispatches a <b>sneaking</b> interaction to a block when <i>both</i> hands answer
 * {@code IItemStackExtension#doesSneakBypassUse}: an empty hand does, and NeoForge's default for an item is
 * {@code false}. So with an empty main hand and a torch, shield or map in the off hand, the requester's
 * {@code useItemOn} and {@code useWithoutItem} are never called ({@code ServerPlayerGameMode#useItemOn},
 * {@code MultiPlayerGameMode#performUseItemOn}), the remote stays in, and the off hand's click goes on to place the
 * torch against the block.
 * <p>
 * {@code setUseBlock(TRUE)} makes the block's own {@code useItemOn} run regardless, and with it
 * {@link SchematicannonRequesterBlock#useWithoutItem}, which the empty main hand is passed on to. That answer is
 * consumed on both sides, so the client never sends the off hand's click and the torch is not placed; the main hand's
 * own item use has nothing to do, its stack being empty. It is asked <b>only</b> for this block while it holds a
 * remote, only for the main hand, only while sneaking, only with an empty main hand and never for a fake player —
 * exactly the shape the block takes the remote out on — so a held item is still placed or used as before, and an empty
 * requester leaves the off hand's click to vanilla. The event fires on both sides, and the listener answers the same
 * way on both (the remote in the slot is synced to clients), so the client's prediction matches the server.
 * <p>
 * Registered once from {@code Wareworks} on the game bus; safe on both dists.
 */
public final class SchematicannonRequesterHooks {
    private SchematicannonRequesterHooks() {
    }

    /** Registers the hooks on the NeoForge game event bus. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(SchematicannonRequesterHooks::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (forcesBlockUse(event.getEntity(), event.getLevel(), event.getPos(), event.getHand()))
            event.setUseBlock(TriState.TRUE);
    }

    /**
     * Whether this right-click is the requester's take-out gesture on a requester that holds a remote, and has to be
     * handed to the block although the player is sneaking.
     */
    public static boolean forcesBlockUse(Player player, Level level, BlockPos pos, InteractionHand hand) {
        if (player == null || level == null || pos == null || hand != InteractionHand.MAIN_HAND)
            return false;
        if (player.isFakePlayer() || !player.isSecondaryUseActive() || !player.getMainHandItem().isEmpty())
            return false;
        return level.getBlockState(pos).is(WareworksBlocks.SCHEMATICANNON_REQUESTER.get())
                && level.getBlockEntity(pos) instanceof SchematicannonRequesterBlockEntity requester
                && requester.hasRemote();
    }
}
