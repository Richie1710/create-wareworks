package dev.wareworks.content.handtruck;

import dev.wareworks.Wareworks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A loaded hand truck right-clicked on a surface: the container is set down again (issue #37, ADR-070).
 * <p>
 * The truck's click always wins over the clicked block — it runs in {@code onItemUseFirst} — so no block's own use
 * ever receives a loaded truck; the client answers {@code SUCCESS} and the server decides. Placement follows a block
 * item's rules: the clicked position or the one in front of it, a replaceable block (grass) is replaced, the block's
 * own {@code getStateForPlacement} decides the facing (towards the player) and whether a chest joins its neighbour,
 * and it must survive there and not stand in an entity. A refusal leaves the truck loaded.
 * <p>
 * <b>Why not {@code stack.useOn} and NeoForge's {@code onPlaceItemIntoWorld}.</b> Its cancel path restores the old
 * block over the new one with {@code setBlock}, which runs the new chest's {@code onRemove} — and that would drop the
 * contents of a chest already filled from the truck while the truck still held them. So the order here is: set down
 * an <b>empty</b> block, post the place event (a cancel removes the still empty block entity and restores the old
 * block, so nothing can drop), check the block entity type, and only then load the contents and clear the truck.
 * {@code Block#setPlacedBy} is not called: it would read the truck's own components as the block item's.
 * <p>
 * <b>The load is checked again here.</b> A creative client can send any stack with any components, so a forged load
 * could name a command block or a spawner with any data. The carried state must still be carriable by tag, the new
 * block entity a plain {@link Container}, and a block entity whose data only an operator may set
 * ({@code BlockEntity#onlyOpCanSetNbt}, the test {@code BlockItem#updateCustomBlockEntityTag} makes) is refused to
 * anyone else. The size budget is not checked again: a truck loaded under a larger budget must still be unloadable.
 */
final class HandTruckPlacement {
    private HandTruckPlacement() {
    }

    static InteractionResult unload(ItemStack truck, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !HandTruckRules.isRealPlayer(player)
                || !(level instanceof ServerLevel serverLevel))
            return InteractionResult.FAIL;
        HandTruckLoad load = HandTruckItem.load(truck);
        if (load == null)
            return InteractionResult.FAIL;
        // The load is client-writable in creative mode: never trust what it names, check it like a pickup would.
        if (!HandTruckRules.isCarriableByTag(load.state()))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);

        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        if (!placeContext.canPlace())
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        BlockPos pos = placeContext.getClickedPos();
        Direction face = context.getClickedFace();
        if (!mayPlace(serverPlayer, serverLevel, pos, context.getClickedPos(), face, truck))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        BlockState placed = load.state().getBlock().getStateForPlacement(placeContext);
        if (placed == null || !placed.canSurvive(level, pos)
                || !level.isUnobstructed(placed, pos, CollisionContext.of(player)))
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);

        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        if (!level.setBlock(pos, placed, Block.UPDATE_ALL_IMMEDIATE))
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        if (EventHooks.onBlockPlace(player, snapshot, face)) {
            revert(level, pos, snapshot);
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !BlockEntityType.getKey(be.getType()).equals(load.blockEntityType())) {
            Wareworks.LOGGER.warn("A hand truck could not set down {} at {}: its block entity is {} now, not {}",
                    load.state(), pos, be == null ? null : BlockEntityType.getKey(be.getType()),
                    load.blockEntityType());
            revert(level, pos, snapshot);
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        }
        if (!(be instanceof Container) || (be.onlyOpCanSetNbt() && !player.canUseGameMasterBlocks())) {
            Wareworks.LOGGER.warn("A hand truck refused to set down {} at {} for {}: not a plain container, or data "
                    + "only an operator may set", load.state(), pos, player.getGameProfile().getName());
            revert(level, pos, snapshot);
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        }
        BlockState now = level.getBlockState(pos);
        be.loadWithComponents(load.dataCopy(), level.registryAccess());
        be.setChanged();
        level.sendBlockUpdated(pos, now, now, Block.UPDATE_ALL);
        truck.remove(WareworksDataComponents.HAND_TRUCK_LOAD.get());

        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(player, now));
        SoundType sound = now.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
        player.awardStat(Stats.ITEM_USED.get(truck.getItem()));
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether {@code player} may build at {@code pos}: adventure mode only with a matching {@code can_place_on} on the
     * truck (the test {@code CommonHooks#onPlaceItemIntoWorld} makes), spawn protection and the world border, and the
     * player's own build check.
     */
    private static boolean mayPlace(ServerPlayer player, ServerLevel level, BlockPos pos, BlockPos clicked,
            Direction face, ItemStack truck) {
        if (!player.getAbilities().mayBuild) {
            AdventureModePredicate canPlaceOn = truck.get(DataComponents.CAN_PLACE_ON);
            if (canPlaceOn == null || !canPlaceOn.test(new BlockInWorld(level, clicked, false)))
                return false;
        }
        return level.mayInteract(player, pos) && player.mayUseItemAt(pos, face, truck);
    }

    /**
     * Takes back a block set down this click. Its block entity is still empty, and it is removed before the old block
     * is restored, so no {@code onRemove} can drop anything.
     */
    private static void revert(Level level, BlockPos pos, BlockSnapshot snapshot) {
        level.removeBlockEntity(pos);
        snapshot.restore(Block.UPDATE_ALL);
    }

    private static InteractionResult refuse(Player player, String relativeKey) {
        HandTruckRules.tell(player, relativeKey);
        return InteractionResult.FAIL;
    }
}
