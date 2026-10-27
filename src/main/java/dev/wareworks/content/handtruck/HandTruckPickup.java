package dev.wareworks.content.handtruck;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.core.handtruck.HandTruckLimits;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.CommonHooks;

/**
 * An empty hand truck right-clicked on a container: the container goes onto the truck (issue #37, ADR-070).
 * <p>
 * The client answers {@code SUCCESS} for a block the tags allow and {@code PASS} for anything else, so a carriable
 * chest never opens and every other block keeps its own click. The server decides, in this order, and the first step
 * that fails decides ({@code PASS} = not ours, the block's own interaction follows; {@code FAIL} = refused, with a line
 * on the action bar):
 * <ol>
 * <li>a Deployer's fake player, or no player: {@code PASS};</li>
 * <li>not carriable ({@link HandTruckRules#isCarriable}): {@code PASS};</li>
 * <li>the player already holds a loaded truck: {@code FAIL}, one container per player;</li>
 * <li>the player may not break the block here — spawn protection, the world border, adventure mode, or a protection
 * mod cancelling the break event NeoForge fires for it ({@code CommonHooks#fireBlockBreak}): {@code FAIL};</li>
 * <li>the save data is over the configured size budget ({@link HandTruckLimits}): {@code FAIL}.</li>
 * </ol>
 * Then, with nothing in between that could post an event or throw: the container is emptied in place (items and an
 * unrolled loot table, so no {@code onRemove} can drop or roll anything), its block entity is removed and the block is
 * replaced by whatever fluid it held, every open menu that showed it is closed, and only then does the truck get its
 * load. The other half of a double chest becomes a single chest with its own 27 slots, as when one half is broken.
 */
final class HandTruckPickup {
    /** Pitch of the pick-up sound: the container's own break sound, a little lower, so it reads as heavy. */
    private static final float PITCH = 0.8F;

    private HandTruckPickup() {
    }

    static InteractionResult load(ItemStack truck, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!HandTruckRules.isRealPlayer(player) || !HandTruckRules.isCarriableByTag(state))
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel))
            return InteractionResult.PASS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!HandTruckRules.isCarriable(level, pos, state, be))
            return InteractionResult.PASS;
        if (HandTruckItem.holdsLoadedTruck(player)) {
            HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);
            return InteractionResult.FAIL;
        }
        if (!mayTake(serverPlayer, serverLevel, pos, state)) {
            HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_NOT_ALLOWED);
            return InteractionResult.FAIL;
        }
        CompoundTag data = be.saveWithoutMetadata(level.registryAccess());
        int maxKiB = WareworksConfig.handTruckMaxLoadKiB();
        int bytes = data.sizeInBytes();
        if (!HandTruckLimits.fits(bytes, maxKiB)) {
            HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_TOO_HEAVY, HandTruckLimits.kib(bytes), maxKiB);
            return InteractionResult.FAIL;
        }
        HandTruckLoad load = new HandTruckLoad(state, BlockEntityType.getKey(be.getType()), data);

        removeWithoutDrops(serverLevel, pos, state, be);
        closeMenusOn(serverLevel);
        truck.set(WareworksDataComponents.HAND_TRUCK_LOAD.get(), load);

        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, state));
        SoundType sound = state.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * PITCH);
        player.awardStat(Stats.ITEM_USED.get(truck.getItem()));
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether {@code player} may break the block at {@code pos}: spawn protection and the world border
     * ({@code ServerLevel#mayInteract}), adventure and spectator mode ({@code Player#blockActionRestricted}), and the
     * break event protection mods listen to. A cancelled break event re-sends the block to the client itself.
     */
    private static boolean mayTake(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.mayInteract(player, pos))
            return false;
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer()))
            return false;
        return !CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, state)
                .isCanceled();
    }

    /**
     * Takes the block away so that nothing drops. Its save data has already been taken, so the block entity is emptied
     * first — items and an unrolled loot table, because a randomizable container rolls its loot the moment anything
     * reads a slot — then removed, and the block is replaced by the fluid it held (a waterlogged chest leaves its
     * water). A {@code onRemove} that looks the block entity up finds an empty one, and drops nothing.
     */
    private static void removeWithoutDrops(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be) {
        if (be instanceof RandomizableContainer randomizable)
            randomizable.setLootTable(null);
        if (be instanceof Container container)
            container.clearContent();
        level.removeBlockEntity(pos);
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
    }

    /**
     * Closes every menu that showed the container just taken. Vanilla would close them on the players' next tick
     * anyway ({@code stillValid}); doing it now means no click can reach a menu over a container that is gone.
     */
    private static void closeMenusOn(ServerLevel level) {
        for (ServerPlayer other : level.players())
            if (other.containerMenu != other.inventoryMenu && !other.containerMenu.stillValid(other))
                other.closeContainer();
    }
}
