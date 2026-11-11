package dev.wareworks.content.storage;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.Wareworks;
import dev.wareworks.core.storage.BayFamily;
import dev.wareworks.core.storage.BayUpgrade;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;

/**
 * An upgrade kit used on a bay: the bay becomes the next tier of its family <b>where it stands</b>, with everything in
 * it (ADR-083, {@code docs/warehouse-system.md} §3.8.2).
 *
 * <h2>The block changes, the block entity stays</h2>
 * Saving the bay's data, setting the next tier's block and loading the data back was rejected: the {@code setBlock}
 * would run {@code IBE.onRemove}, i.e. {@code destroy()} — a pallet for a rack bay, the fluid lost for a fluid bay, a
 * List or Attribute Filter dropped as an item that the reload would then duplicate — and a fresh block entity would
 * tell the controllers a member changed and lose a crane's target. Instead {@link #swapInPlace} raises the
 * {@link TierChangeable} flag for exactly one {@code setBlock}: the bay's {@code onRemove} then leaves its block entity
 * alone, and {@code LevelChunk#setBlockState} hands that <b>same object</b> the new state, because the block entity type
 * of a family is valid for all its tiers. Goods, filter, priority, address and reservations never move; no item moves
 * at all, so the item conservation invariant is untouched by construction.
 *
 * <h2>The column rule</h2>
 * Only a bay <b>below</b> can refuse an upgrade ({@code BayUpgrade}, {@link BayColumn#carriedByEverythingBelow}), so a
 * column is upgraded from the bottom up. An <b>overloaded</b> bay may be upgraded when the bays below carry the
 * target: that is how a column a command broke is repaired.
 *
 * <h2>Protection</h2>
 * The same checks a placed block passes: the level's {@code mayInteract} (spawn protection, claims that hook it), the
 * player's {@code mayUseItemAt} and adventure mode's {@code blockActionRestricted}, and then NeoForge's block place
 * event, which claim and logging mods listen to. An adventure player's kit without {@code can_place_on} never gets
 * here: NeoForge's {@code ItemStack#onItemUseFirst} passes it first, and the bay's gesture passes a kit on. A cancelled event swaps the bay <b>back</b> the same way it was swapped
 * (never {@code BlockSnapshot#restore}, which would run without the flag, destroy the bay and duplicate its filter),
 * and the kit is kept.
 *
 * <h2>Deployers may use it</h2>
 * An upgrade moves no item — which is the reason bays refuse fake players everywhere else — and a Deployer can already
 * break a bay, which is far worse, so it opens no new grief route; a contraption sweeping a rack wall with kits is the
 * most useful automation this item has. The protection checks run for the fake player too (Create's Deployer acts
 * with its owner's profile), a fake player is never creative so the kit is used up, and refusals are silent.
 */
public final class BayUpgrades {
    private BayUpgrades() {
    }

    /**
     * The kit's click, from {@code BayUpgradeKitItem#onItemUseFirst}: runs on both sides, changes the world on the
     * server only. Anything that is not a bay passes; a bay the kit does not fit, or whose column refuses, fails with
     * an action-bar line.
     */
    public static InteractionResult use(ItemStack kit, UseOnContext context, BayUpgrade step) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        BlockState old = level.getBlockState(pos);
        TieredBay bay = BayColumn.bayAt(old);
        if (bay == null || player == null)
            return InteractionResult.PASS; // the kit does nothing anywhere else
        if (!step.fits(bay.bayFamily(), strengthOf(bay)))
            return refuse(level, player, WareworksLang.BAY_UPGRADE_FITS, sourceBlock(step).getName());
        Block target = targetBlock(step);
        if (!(target instanceof TieredBay targetBay))
            return InteractionResult.FAIL;
        if (!BayColumn.carriedByEverythingBelow(level, pos, targetBay))
            return refuse(level, player, WareworksLang.BAY_UPGRADE_COLUMN);
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.FAIL;
        Direction face = context.getClickedFace();
        if (!mayChange(serverPlayer, serverLevel, pos, face, kit))
            return refuse(level, player, WareworksLang.BAY_UPGRADE_NOT_ALLOWED);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TierChangeable) || be.isRemoved())
            return InteractionResult.FAIL;

        BlockState next = target.withPropertiesOf(old)
                .setValue(TieredBay.OVERLOADED, BayColumn.overloadedAt(level, pos, targetBay));
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        if (!swapInPlace(serverLevel, pos, next))
            return InteractionResult.FAIL;
        if (EventHooks.onBlockPlace(player, snapshot, face)) {
            // A claim or a logger said no: the same swap back, never snapshot.restore (see the class comment).
            if (!swapInPlace(serverLevel, pos, old))
                Wareworks.LOGGER.error("An upgraded bay at {} could not be swapped back after its place event was "
                        + "cancelled; it stays {}", pos, next);
            return refuse(level, player, WareworksLang.BAY_UPGRADE_NOT_ALLOWED);
        }
        if (!player.hasInfiniteMaterials())
            kit.shrink(1);
        BlockState now = level.getBlockState(pos);
        SoundType sound = now.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, now));
        // No ITEM_USED stat here: NeoForge's ItemStack#onItemUseFirst awards it for a SUCCESS already.
        return InteractionResult.SUCCESS;
    }

    /**
     * Server: sets {@code next} at {@code pos} while the bay's block entity is told to stay, and checks that it did.
     * Then lets other mods' capability caches re-ask (same handler object, a new slot limit) and brings the derived
     * block state in line with the new capacity ({@link BayColumn#publishState}: the fill level of a rack bay).
     *
     * @return whether the same block entity now stands under {@code next}; false (and an error in the log) if not,
     *         which must not happen
     */
    static boolean swapInPlace(ServerLevel level, BlockPos pos, BlockState next) {
        if (!(level.getBlockEntity(pos) instanceof TierChangeable bay))
            return false;
        BlockEntity before = (BlockEntity) bay;
        bay.beginTierChange();
        try {
            level.setBlock(pos, next, Block.UPDATE_ALL);
        } finally {
            bay.endTierChange();
        }
        if (level.getBlockEntity(pos) != before || !level.getBlockState(pos).is(next.getBlock())) {
            Wareworks.LOGGER.error("A bay at {} did not keep its block entity while it was changed to {}; another "
                    + "mod may have replaced it", pos, next);
            return false;
        }
        level.invalidateCapabilities(pos);
        BayColumn.publishState(level, pos);
        return true;
    }

    /**
     * Whether {@code player} may change the bay at {@code pos}: the level's own check (spawn protection and the claims
     * that hook it), the player's build check for this item and face, and adventure mode.
     * <p>
     * {@code Player#mayUseItemAt} tests {@code can_place_on} against the block <b>behind</b> the position it is given,
     * as a block item passes it the position it places at. The kit changes the clicked bay itself, so it is handed the
     * position in front of the clicked face, and the predicate lands on the bay — not on whatever stands behind it.
     */
    private static boolean mayChange(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face,
            ItemStack kit) {
        return level.mayInteract(player, pos) && player.mayUseItemAt(pos.relative(face), face, kit)
                && !player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer());
    }

    /** The strength of {@code bay}'s tier on its own family's ladder. */
    static int strengthOf(TieredBay bay) {
        if (bay instanceof RackBayBlock rack)
            return rack.tier().strength();
        if (bay instanceof FluidBayBlock fluid)
            return fluid.tier().strength();
        return -1;
    }

    /** The bay block a kit of {@code step} upgrades. */
    public static Block sourceBlock(BayUpgrade step) {
        Block block = blockOf(step.family(), step.fromStrength());
        if (block == null)
            throw new IllegalStateException("no bay block for the source of " + step);
        return block;
    }

    /** The bay block a kit of {@code step} turns its bay into. */
    public static Block targetBlock(BayUpgrade step) {
        Block block = blockOf(step.family(), step.toStrength());
        if (block == null)
            throw new IllegalStateException("no bay block for the target of " + step);
        return block;
    }

    @Nullable
    private static Block blockOf(BayFamily family, int strength) {
        Block[] blocks = family == BayFamily.ITEM
                ? new Block[] {WareworksBlocks.RACK_BAY_WOOD.get(), WareworksBlocks.RACK_BAY_ANDESITE.get(),
                        WareworksBlocks.RACK_BAY_BRASS.get()}
                : new Block[] {WareworksBlocks.FLUID_BAY_COPPER.get(), WareworksBlocks.FLUID_BAY_BRASS.get()};
        for (Block block : blocks)
            if (block instanceof TieredBay bay && strengthOf(bay) == strength)
                return block;
        return null;
    }

    /**
     * Fails the click and tells a real player why, on the server only (the client predicts the same answer and says
     * nothing, so the action bar holds the server's one line). A Deployer's fake player is refused silently.
     */
    private static InteractionResult refuse(Level level, Player player, String relativeKey, Object... args) {
        if (!level.isClientSide && !(player instanceof FakePlayer))
            player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
        return InteractionResult.FAIL;
    }
}
