package dev.wareworks.content.handtruck;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.core.handtruck.HandTruckLimits;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * An empty hand truck right-clicked on a pallet lying in the world: the whole pallet goes onto the truck (ADR-084).
 * <p>
 * Reached from {@link HandTruckHooks}' listener on {@code PlayerInteractEvent.EntityInteract} at {@code LOW} priority,
 * so a protection mod that cancels the click at its usual priority has already decided, and a cancelled event never
 * gets here. Runs on both sides; the client answers {@code SUCCESS} for what the server will probably do (the arm
 * swings), the server decides. In this order, and the first step that fails decides ({@code PASS} = not ours, the
 * pallet's own click follows, which passes for a non-empty hand; {@code FAIL} = refused, with a line on the action
 * bar):
 * <ol>
 * <li>a Deployer's fake player, or no player: {@code PASS} (as for a chest);</li>
 * <li>a pallet already on its way out, or one with nothing on it: {@code PASS};</li>
 * <li>the player already holds a loaded truck: {@code FAIL}, one load per player;</li>
 * <li>the player may not build (adventure mode) or not act there (spawn protection, the world border):
 * {@code FAIL};</li>
 * <li>the key with its components is over the configured size budget ({@link HandTruckLimits}): {@code FAIL}. Only a
 * key with huge components ever is; unloading never checks it again.</li>
 * </ol>
 * Then, with nothing in between that could post an event or throw: the pallet gives up its whole load and discards
 * itself ({@link PalletEntity#takeWholeLoad}), and only then does the truck get it. The count is read at that moment,
 * so a hopper that drained the pallet earlier in the same tick is already accounted for.
 */
final class HandTruckPalletPickup {
    /** Pitch of the lift sound: a wooden thud, a little lower, so it reads as heavy. */
    private static final float PITCH = 0.8F;

    private HandTruckPalletPickup() {
    }

    static InteractionResult lift(Player player, ItemStack truck, PalletEntity pallet) {
        if (!HandTruckRules.isRealPlayer(player))
            return InteractionResult.PASS;
        if (pallet.isRemoved() || pallet.carriedKey().isEmpty())
            return InteractionResult.PASS;
        Level level = player.level();
        if (HandTruckItem.holdsLoadedTruck(player)) {
            if (!level.isClientSide)
                HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);
            return InteractionResult.FAIL;
        }
        if (level.isClientSide)
            return player.getAbilities().mayBuild ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        if (!(level instanceof ServerLevel serverLevel))
            return InteractionResult.PASS;
        if (!player.getAbilities().mayBuild || !serverLevel.mayInteract(player, pallet.blockPosition())) {
            HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_NOT_ALLOWED);
            return InteractionResult.FAIL;
        }
        ItemKey key = pallet.carriedKey().get();
        int bytes = new HandTruckPallet(key, pallet.carriedCount()).sizeInBytes(level.registryAccess());
        int maxKiB = WareworksConfig.handTruckMaxLoadKiB();
        if (!HandTruckLimits.fits(bytes, maxKiB)) {
            HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_TOO_HEAVY, HandTruckLimits.kib(bytes), maxKiB);
            return InteractionResult.FAIL;
        }

        ItemStack whole = pallet.takeWholeLoad(); // clears and discards the pallet before anything else can see it
        if (whole.isEmpty())
            return InteractionResult.PASS;
        truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(), new HandTruckPallet(ItemKey.of(whole),
                whole.getCount()));

        level.gameEvent(player, GameEvent.ENTITY_INTERACT, pallet.position());
        level.playSound(null, pallet.getX(), pallet.getY(), pallet.getZ(), SoundEvents.WOOD_PLACE,
                SoundSource.BLOCKS, 1.0F, PITCH);
        player.awardStat(Stats.ITEM_USED.get(truck.getItem()));
        return InteractionResult.SUCCESS;
    }
}
