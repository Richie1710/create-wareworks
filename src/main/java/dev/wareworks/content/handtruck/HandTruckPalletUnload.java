package dev.wareworks.content.handtruck;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.handtruck.PalletIntoBay;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksEntityTypes;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A hand truck carrying a pallet, right-clicked on a block: the pallet goes whole into an empty rack bay, or is set
 * down as a pallet again (ADR-084).
 * <p>
 * Runs in {@code onItemUseFirst}, so no clicked block's own use ever receives the truck — and a click inside a bay's
 * filter slot reaches it too, because the slot lets a loaded truck through ({@code StorageFilterBehaviour}). The client
 * answers {@code SUCCESS}, the server decides by the clicked block entity:
 * <ul>
 * <li><b>a rack bay</b> (any tier): the whole load goes in, in one handler call, but only into an <b>empty</b> bay with
 * no crane on its way to it, whose filter takes the item and which holds the whole count ({@link PalletIntoBay}).
 * Anything else is refused with its own line and changes nothing. A pallet never tops up a bay: that would make the
 * truck a way to pour any amount into any bay, which is the crane's job and the bay's own gesture's. The column rule
 * and the overload flag do not apply, as for every hand insert (ADR-044);</li>
 * <li><b>a fluid bay</b>: refused, it holds no items;</li>
 * <li><b>anything else</b>: set down as a {@link PalletEntity} where a block would be placed, turned towards the
 * player, if a block item could be placed there and the pallet's box is free of blocks.</li>
 * </ul>
 * Source first, in both commits: the truck's component is removed before the bay or the level gets the load, and put
 * back (reduced, for a bay) only on a refusal that cannot really happen. Setting down posts NeoForge's block place
 * event for the landing position before anything changes, as the chest's set-down does: that position may lie in
 * another claim than the clicked block, and claim mods forbid building through that event, not through the entity's
 * join. A cancel refuses with the chest's line and leaves the truck loaded; a refused {@code EntityJoinLevelEvent}
 * does too.
 * <p>
 * NeoForge counts the item-used stat for a successful {@code onItemUseFirst} ({@code IItemStackExtension}), so it is
 * not counted here; the same method passes an adventure click without a matching {@code can_place_on} before the truck
 * sees it, so the adventure check in {@code mayPlace} only ever decides for a truck that carries one.
 */
final class HandTruckPalletUnload {
    /** Pitch of the set-down sound: the same lowered pitch as the chest's. */
    private static final float PITCH = 0.8F;
    /** Turns a player's yaw around, so the set-down pallet faces them. */
    private static final float FACING_THE_PLAYER = 180.0F;

    private HandTruckPalletUnload() {
    }

    static InteractionResult unload(ItemStack truck, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !HandTruckRules.isRealPlayer(player)
                || !(level instanceof ServerLevel serverLevel))
            return InteractionResult.FAIL;
        HandTruckPallet pallet = HandTruckItem.palletLoad(truck);
        if (pallet == null)
            return InteractionResult.FAIL;
        // The component is client-writable in creative mode: a pallet of loaded trucks would put a load into the world
        // that nothing else keeps track of.
        if (HandTruckItem.isLoaded(pallet.item().toStack()))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        BlockEntity clicked = level.getBlockEntity(context.getClickedPos());
        if (clicked instanceof RackBayBlockEntity bay)
            return intoBay(serverPlayer, serverLevel, context, truck, pallet, bay);
        if (clicked instanceof FluidBayBlockEntity)
            return refuse(player, WareworksLang.HAND_TRUCK_BAY_WRONG_KIND);
        return ontoTheFloor(serverPlayer, serverLevel, context, truck, pallet);
    }

    /** The whole load into an empty rack bay that holds it all, or a refusal that changes nothing. */
    private static InteractionResult intoBay(ServerPlayer player, ServerLevel level, UseOnContext context,
            ItemStack truck, HandTruckPallet pallet, RackBayBlockEntity bay) {
        BlockPos pos = context.getClickedPos();
        if (!HandTruckPlacement.mayPlace(player, level, pos, pos, context.getClickedFace(), truck))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        ItemKey key = pallet.item();
        int count = pallet.count();
        boolean empty = bay.storedKey().isEmpty();
        // One registry scan per click, and only for an empty bay: the reservations of a crane on its way here.
        boolean craneBringing = empty && !WarehouseRegistry.observeStorage(level, pos, bay).reservations()
                .incoming().isEmpty();
        long capacity = bay.capacityFor(key);
        switch (PalletIntoBay.verdict(empty, craneBringing, bay.storeFilterAllows(key.toStack()), capacity, count)) {
            case NOT_EMPTY:
                return refuse(player, WareworksLang.HAND_TRUCK_BAY_NOT_EMPTY);
            case CRANE_BRINGING:
                return refuse(player, WareworksLang.HAND_TRUCK_BAY_INCOMING);
            case FILTERED:
                return refuse(player, WareworksLang.HAND_TRUCK_BAY_FILTERED, pallet.displayName());
            case TOO_SMALL:
                return refuse(player, WareworksLang.HAND_TRUCK_BAY_TOO_SMALL, capacity, count);
            case FITS:
                break;
        }
        if (!bay.insert(key.toStack(count), true).isEmpty())
            return refuse(player, WareworksLang.HAND_TRUCK_BAY_TOO_SMALL, capacity, count);

        truck.remove(WareworksDataComponents.HAND_TRUCK_PALLET.get());
        ItemStack rest = bay.insert(key.toStack(count), false);
        if (!rest.isEmpty()) {
            // Cannot happen after the simulation above; handled anyway, so that nothing is lost.
            Wareworks.LOGGER.warn("The rack bay at {} took only {} of {} x {} off a hand truck; the rest stays on the "
                    + "truck", pos, count - rest.getCount(), count, key);
            truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(), new HandTruckPallet(key, rest.getCount()));
        }

        BlockState state = level.getBlockState(pos);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
        SoundType sound = state.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * PITCH);
        return InteractionResult.SUCCESS;
    }

    /** Sets the pallet down where a block item would be placed, turned towards the player. */
    private static InteractionResult ontoTheFloor(ServerPlayer player, ServerLevel level, UseOnContext context,
            ItemStack truck, HandTruckPallet pallet) {
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        if (!placeContext.canPlace())
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        BlockPos pos = placeContext.getClickedPos();
        Direction face = context.getClickedFace();
        if (!HandTruckPlacement.mayPlace(player, level, pos, context.getClickedPos(), face, truck))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
        AABB box = WareworksEntityTypes.PALLET.get().getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (!level.noCollision(null, box))
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        // The chest's protection step at the landing position, which may lie in another claim than the clicked block:
        // claim mods forbid building through this event. No block is written, so a cancel has nothing to revert.
        if (EventHooks.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), face))
            return refuse(player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);

        truck.remove(WareworksDataComponents.HAND_TRUCK_PALLET.get());
        if (!PalletEntity.spawn(level, pos, pallet.item(), pallet.count(), player.getYRot() + FACING_THE_PLAYER)) {
            truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(), pallet);
            return refuse(player, WareworksLang.HAND_TRUCK_NO_ROOM);
        }

        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
        level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, PITCH);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult refuse(Player player, String relativeKey, Object... args) {
        HandTruckRules.tell(player, relativeKey, args);
        return InteractionResult.FAIL;
    }
}
