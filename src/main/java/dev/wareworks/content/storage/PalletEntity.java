package dev.wareworks.content.storage;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.storage.BayContents;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

/**
 * The load of a broken rack bay, lying in the world as <b>one</b> entity ({@code docs/warehouse-system.md} §3.8 and §8,
 * M28, issue #20, ADR-046).
 *
 * <h2>Why it exists at all</h2>
 * A brass bay holds 65 536 items and a single {@code ItemStack} caps at {@code Item.ABSOLUTE_MAX_STACK_SIZE} = 99, so
 * dropping a full bay as item entities is about 3 300 of them ({@link TransferContexts#spillAt} splits to the item's
 * max stack size and {@code Containers.dropItemStack} splits again), and a rack wall would be a server's bad
 * afternoon. A pallet is the same load as a <b>single</b> entity, and it is cheap to be one: a bay holds one item type,
 * so its contents are not 1 024 slots but one {@link ItemKey} and one count — the very shape {@link BayContents}
 * already is, which is why this class reuses it rather than inventing a second one.
 *
 * <h2>What it deliberately is not</h2>
 * A pallet <b>cannot be pocketed and cannot be placed back as a filled bay</b>. There is no item form, no spawn egg,
 * and both pick hooks are left at their defaults ({@code Entity#getPickResult} answers {@code null} and
 * {@code IEntityExtension#getPickedResult} delegates to it), which enforces that for free and with nothing to
 * maintain. That is the exact opposite of Create's {@code PackageEntity}, which hands the box to a player on an
 * empty-hand right-click — and the difference is the whole point: a removal crate worth thirty-eight shulker boxes,
 * obtainable without ever visiting the End, is what the owner refused. Breaking a bay therefore <b>resets</b> it: an
 * empty bay block and a pallet on the floor, refilled by hand a stack at a time with the bay's own gesture.
 *
 * <h2>Why a plain {@link Entity} satisfies every "must survive" requirement</h2>
 * None of it costs an override, because this is not a {@code LivingEntity}:
 * <ul>
 * <li><b>Damage</b>: {@code Entity#hurt} has no health to reduce — its whole body is "mark hurt and answer false" — so
 * no damage source can destroy a pallet. {@link #setInvulnerable(boolean)} is set in the constructor all the same, so
 * that {@code isInvulnerableTo} answers honestly and {@code /damage} reports the failure rather than lying;</li>
 * <li><b>fire and lava</b>: {@code Entity#lavaHurt} has its whole body inside {@code if (!fireImmune())} and every
 * burning path routes through {@code hurt}, so the {@code .fireImmune()} flag on the entity type is the whole of it
 * (and it also stops the permanent flame overlay);</li>
 * <li><b>despawning</b>: a 6 000-tick lifetime is {@code ItemEntity} behaviour and despawn rules are {@code Mob}
 * behaviour. A plain entity has neither — and because a pallet keeps only the <i>lifetime</i> half of that
 * comparison, {@link #tick()} takes the other half, {@code ItemEntity}'s resting guard, which is what a permanent
 * entity needs far more than a temporary one does;</li>
 * <li><b>save, chunk unload and reload</b>: {@code shouldBeSaved()} is already true for a non-passenger and the unload
 * path saves to the chunk, so {@code noSave()} must <b>never</b> be called and no persistence flag is needed.</li>
 * </ul>
 * <b>Water</b> is the other difference from a package, which water destroys: {@code onInsideBlock} is deliberately not
 * implemented, so a pallet that ends up in a river simply sits there with its load.
 *
 * <h2>What can still lose a pallet, said out loud</h2>
 * The <b>void</b>, {@code /kill} and a world edit. The void is conceded by the issue and is the one allowed loss, so
 * {@link #onBelowWorld()} logs the item, the count and the position <b>before</b> it discards, and the loss leaves a
 * record.
 *
 * <h2>It can be shoved, and it falls</h2>
 * {@link #isPushable()} is true and {@code canBeCollidedWith()} is left at its {@code false} default, so a player
 * walks through a pallet and shoves it along as they do — a pallet pushed across a warehouse floor is the picture this
 * feature is built on. {@link #tick()} is therefore a real, minimal physics tick (gravity, move, drag), whose shape
 * {@code PrimedTnt} already has: without it a bay broken high up in a rack wall would leave its load floating out of
 * reach. {@code PrimedTnt} lives at most 80 ticks, though, and a pallet is permanent, so the move is guarded by
 * {@code ItemEntity}'s resting check as well ({@link #resting()}). {@link #isPickable()} must be true or the pallet cannot be clicked at all, and {@link #isAttackable()} is
 * false because there is nothing a punch could ever do to it.
 *
 * <h2>Items in and out</h2>
 * Out only, ever. The way <b>in</b> is the bay, and nothing else:
 * <ul>
 * <li>a player takes with the bay's own gesture — an empty-hand right-click for one item, Shift for one stack
 * ({@link RackBayGestures#PLAIN_TAKE_ITEMS}), simulated against their own inventory first so a full inventory means
 * nothing happens and nothing spills;</li>
 * <li>{@code Capabilities.ItemHandler.ENTITY} and {@code ENTITY_AUTOMATION} expose one slot, <b>extract-only</b>:
 * insertion would turn a pallet into a machine-fillable portable container, which is the thing that was refused. A
 * vanilla hopper under a pallet drains it ({@code VanillaInventoryCodeHooks.getItemHandlerAt} falls back to the
 * automation capability) and so does a Create Deployer, which throttles itself on its own overflow. <b>Create's own
 * belts, chutes, funnels, depots and ejectors do not</b>, because they all gate on {@code ItemHelper.fromItemEntity},
 * whose whole body answers only for a {@code PackageEntity} and an {@code ItemEntity}. That is worth knowing rather
 * than discovering, and it has a pleasant side: no Create logistics block can delete or teleport a pallet either.</li>
 * </ul>
 * <b>An emptied pallet discards itself</b> ({@link #onLoadChanged()}), so draining one never leaves an invisible husk
 * behind for an item census to trip over.
 *
 * <h2>What is saved, and what crosses the wire</h2>
 * {@code Item} as a count-less {@link ItemKey} and {@code Count} as an {@code int}, and <b>never</b>
 * {@code ItemStack.save}, whose codec bounds a count at 99 while the network codec does not — a pallet built on a
 * stack would look perfect for a whole session and empty itself on the next world load (ADR-013, and the same reason
 * {@link RackBayHandler} is hand-written). A load is bounded and never throws: an undecodable key or a count at or
 * below 0 discards the pallet, with vanilla's own answer for an unreadable container entry as the precedent, and a
 * count above {@link #MAX_LOAD} is clamped <b>and logged as the item loss it is</b>.
 * <p>
 * The client is sent the item as a tracked {@code ItemStack} of <b>count 1</b> plus the count as a separate
 * {@code int}, so no oversized stack ever exists where {@code save} could truncate it. This is entity tracker traffic
 * rather than a chunk packet, so it carries the real components and a renamed item reads correctly on a pallet —
 * unlike in a bay, whose update tag may only carry a registry id (§3.1.1).
 */
public class PalletEntity extends Entity {
    /**
     * The most a pallet can carry: exactly what the largest bay any configuration allows can hold
     * ({@link BayTier#MAX_CAPACITY_ITEMS}, 405 504 items).
     * <p>
     * <b>One ceiling, not two.</b> A pallet carries one bay's load and nothing else, so its bound is the bay's bound,
     * its message is the bay's message ({@link RackBayHandler#readFrom}) and its count is an {@code int} for the same
     * reason a bay's is. A second, unrelated number here would be a second thing to keep in step, and a wider one
     * would accept save data that no legitimate path can produce.
     */
    public static final int MAX_LOAD = BayTier.MAX_CAPACITY_ITEMS;

    /** Save key of the carried item type, a count-less {@link ItemKey}. */
    public static final String STORED_TAG = "Item";
    /** Save key of the carried amount, an {@code int}. */
    public static final String COUNT_TAG = "Count";

    /** The pallet has exactly one slot; a count, not a slot list, is what makes it carry a thousand stacks. */
    public static final int SLOTS = 1;
    /** The only slot index. */
    public static final int SLOT = 0;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The carried item as a stack of <b>count 1</b> — the count rides {@link #DATA_COUNT} beside it, so the tracked
     * value is a fixed, small size whatever the pallet holds and no oversized stack is ever written by
     * {@code ItemStack}'s own codecs.
     */
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(PalletEntity.class, EntityDataSerializers.ITEM_STACK);
    /** The carried amount; 0 exactly while {@link #DATA_ITEM} is empty. */
    private static final EntityDataAccessor<Integer> DATA_COUNT =
            SynchedEntityData.defineId(PalletEntity.class, EntityDataSerializers.INT);

    /** Downward acceleration per tick, the same as an item entity's: a pallet falls like a dropped crate. */
    private static final double GRAVITY = 0.04;
    /** Air drag per tick, as {@code PrimedTnt} uses. */
    private static final double AIR_DRAG = 0.98;
    /** Horizontal drag while a pallet rests on the ground: a shove carries it a little and then it stops. */
    private static final double GROUND_DRAG = 0.6;
    /**
     * Below this squared horizontal speed a pallet on the ground counts as lying still ({@link #resting()}), as
     * {@code ItemEntity} uses it.
     */
    private static final double RESTING_MOTION_SQR = 1.0E-5;
    /** How often a resting pallet still runs its collision step, so it falls when the floor is mined away. */
    private static final int RESTING_RECHECK_TICKS = 4;
    /** Volume and base pitch of the one sound a pallet makes, when a hand takes something off it. */
    private static final float PICKUP_VOLUME = 0.2F;
    private static final float PICKUP_PITCH = 1.0F;
    private static final float PICKUP_PITCH_SPREAD = 0.2F;

    /** The whole of a pallet's contents, server-authoritative and mirrored on the client from the tracked values. */
    private final BayContents<ItemKey> contents = new BayContents<>();
    /** Extract-only, one slot; a final field, so NeoForge's automatic capability invalidation is enough. */
    private final Handler handler = new Handler();

    public PalletEntity(EntityType<?> type, Level level) {
        super(type, level);
        // Not a defence against damage — Entity#hurt cannot remove a plain entity — but so that isInvulnerableTo
        // answers honestly and a command that tries reports its failure instead of silently doing nothing.
        setInvulnerable(true);
    }

    // --- spawning ---------------------------------------------------------------------------------------------------

    /**
     * Puts a pallet carrying {@code load} items of {@code key} into the world at {@code pos}, standing on the bottom of
     * that block, and answers whether the level really <b>accepted</b> it.
     * <p>
     * <b>The answer must be checked by every caller.</b> {@code Level#addFreshEntity} returns a boolean because it can
     * refuse: {@code EntityJoinLevelEvent} is cancellable and the UUID set can reject, so one unrelated mod's handler
     * would otherwise turn breaking a bay into total item loss. The caller's fallback is
     * {@link TransferContexts#spillAt}, which is deliberately ugly rather than quiet — see
     * {@link RackBayBlockEntity#destroy()}.
     *
     * @return {@code false} when nothing was spawned and the caller still owns the load
     */
    public static boolean spawn(Level level, BlockPos pos, ItemKey key, int load) {
        if (level == null || level.isClientSide || key == null || load <= 0)
            return false;
        PalletEntity pallet = WareworksEntityTypes.PALLET.create(level);
        if (pallet == null) {
            LOGGER.warn("Could not create a pallet for {} x {} at {}", load, key, pos);
            return false;
        }
        pallet.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        pallet.setLoad(key, load);
        return level.addFreshEntity(pallet);
    }

    // --- contents ---------------------------------------------------------------------------------------------------

    /** The carried item type, empty for a pallet that has just been drained and is on its way out. */
    public Optional<ItemKey> carriedKey() {
        return Optional.ofNullable(contents.stored());
    }

    /** How many items this pallet carries; 0 exactly when {@link #carriedKey()} is empty. */
    public int carriedCount() {
        return contents.count();
    }

    /** The pallet's item handler — extract-only, and the whole of its load ({@link Handler}). */
    public IItemHandler handler() {
        return handler;
    }

    /** Server: takes up to {@code amount} items, at most one stack per call, and answers what was really taken. */
    public ItemStack extract(int amount, boolean simulate) {
        return takeLoad(amount, simulate);
    }

    /**
     * Sets the whole load at once, for a spawn and for a load from save data. A count above {@link #MAX_LOAD} is
     * clamped and logged, because a clamp <b>is</b> item loss and has to read as one.
     */
    private void setLoad(@Nullable ItemKey key, int load) {
        int carried = load;
        if (key != null && carried > MAX_LOAD) {
            LOGGER.warn("A pallet was given {} x {}, more than the {} items any bay can hold; {} items are lost",
                    carried, key, MAX_LOAD, carried - MAX_LOAD);
            carried = MAX_LOAD;
        }
        contents.restore(key, carried);
        publishLoad();
    }

    /**
     * The one place items leave a pallet: {@code perCall} is the item's own max stack size, so one call returns at most
     * one stack exactly as the {@code IItemHandler} contract requires, and a real call that empties the pallet makes it
     * go away.
     */
    private ItemStack takeLoad(int amount, boolean simulate) {
        ItemKey key = contents.stored();
        if (key == null || amount <= 0)
            return ItemStack.EMPTY;
        int taken = contents.extract(amount, key.getMaxStackSize(), simulate);
        if (taken <= 0)
            return ItemStack.EMPTY;
        if (!simulate)
            onLoadChanged();
        return key.toStack(taken);
    }

    /**
     * After a real change on the server: the tracked values follow, and a pallet with nothing left on it
     * <b>discards itself</b>. The caller already holds what was taken, so the discard can never lose an item — and an
     * empty pallet left standing would be an invisible husk in the world and in every item census.
     */
    private void onLoadChanged() {
        if (level().isClientSide)
            return;
        publishLoad();
        if (contents.isEmpty())
            discard();
    }

    private void publishLoad() {
        ItemKey key = contents.stored();
        entityData.set(DATA_ITEM, key == null ? ItemStack.EMPTY : key.toStack());
        entityData.set(DATA_COUNT, key == null ? 0 : contents.count());
    }

    /**
     * Client levels only (the game client and Ponder): shows this pallet carrying {@code load} items of {@code key},
     * for a deterministic picture — the same documented hook {@code StackerCraneBlockEntity#showClientPose} is for the
     * crane, and for the same reason: a Ponder scene runs in a client level, where {@link #spawn} refuses outright and
     * no server packet will ever arrive to say what the pallet holds.
     * <p>
     * It writes the tracked values and lets {@link #onSyncedDataUpdated} mirror them into the contents, so a shown
     * pallet is in exactly the state a real sync would have put it in, down to the component-less key a renamed item
     * arrives as. Nothing else happens: no transfer, no discard, and on a server level this does nothing at all —
     * items only ever leave a pallet through {@code takeLoad}.
     *
     * @return whether the load is shown (false on a server level, or for a load that is not a positive count of a key)
     */
    public boolean showClientLoad(@Nullable ItemKey key, int load) {
        if (!level().isClientSide || key == null || load <= 0)
            return false;
        entityData.set(DATA_ITEM, key.toStack());
        entityData.set(DATA_COUNT, Math.min(load, MAX_LOAD));
        return true;
    }

    // --- a player's hands -------------------------------------------------------------------------------------------

    /**
     * A right-click on a pallet: an <b>empty hand</b> takes one item, Shift takes one stack, and <b>everything else is
     * passed on</b> — nothing is ever put into a pallet, so a click with an item in hand keeps whatever meaning it had.
     * <p>
     * Runs on both sides and commits only on the server, exactly as the bay's gesture does: the client knows the item
     * and the count from the tracked values, so it predicts what the server will really do. A block click carries no
     * modifier bit over the wire, but an <b>entity</b> interaction does ({@code ServerboundInteractPacket} carries
     * {@code usingSecondaryAction}, and the server sets the player's crouch state from it before this runs), so Shift
     * means here exactly what it means on the bay.
     * <p>
     * A {@code FakePlayer} is refused for the reason a bay refuses one: in-aisle automation has the item capability for
     * this, and a fake player that could right-click would empty a pallet item by item.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player == null || hand != InteractionHand.MAIN_HAND || player instanceof FakePlayer)
            return InteractionResult.PASS;
        if (!player.getItemInHand(hand).isEmpty())
            return InteractionResult.PASS;
        return take(player, player.isSecondaryUseActive());
    }

    /**
     * Takes one item, or one stack, into the player's inventory — and <b>only what fits there</b>. The insert into the
     * player is simulated first and exactly that much is extracted, so a player with a full inventory sees nothing
     * happen rather than items on the floor.
     */
    private InteractionResult take(Player player, boolean wholeStack) {
        ItemKey key = contents.stored();
        if (key == null)
            return InteractionResult.PASS;
        int wanted = wholeStack ? key.getMaxStackSize() : RackBayGestures.PLAIN_TAKE_ITEMS;
        ItemStack available = takeLoad(wanted, true);
        IItemHandler inventory = new PlayerMainInvWrapper(player.getInventory());
        int fits = accepted(available.getCount(),
                ItemHandlerHelper.insertItemStacked(inventory, available.copy(), true));
        if (fits <= 0)
            return InteractionResult.PASS;
        if (level().isClientSide)
            return InteractionResult.SUCCESS;
        ItemStack taken = takeLoad(fits, false);
        if (taken.isEmpty())
            return InteractionResult.PASS; // the simulate was stale: nothing moved
        ItemStack leftOver = ItemHandlerHelper.insertItemStacked(inventory, taken, false);
        // Cannot happen after the simulate above, and handled anyway: the pallet may already have discarded itself on
        // this very call, so the remainder goes to the floor where it stood rather than back into a carrier that is on
        // its way out. An item is always in exactly one place (docs/warehouse-system.md §8).
        if (!leftOver.isEmpty())
            TransferContexts.spillAt(level(), blockPosition(), leftOver);
        playSound(SoundEvents.ITEM_PICKUP, PICKUP_VOLUME, PICKUP_PITCH + random.nextFloat() * PICKUP_PITCH_SPREAD);
        return InteractionResult.CONSUME;
    }

    /** How much of {@code offered} a transfer really took, given the remainder it answered with. */
    private static int accepted(int offered, ItemStack remainder) {
        return offered - Math.min(offered, Math.max(0, remainder.getCount()));
    }

    // --- entity behaviour -------------------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
        builder.define(DATA_COUNT, 0);
    }

    /** The client mirrors the server's load from the tracked values, so its own renderer and its prediction agree. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (!level().isClientSide || (!DATA_ITEM.equals(key) && !DATA_COUNT.equals(key)))
            return;
        ItemStack item = entityData.get(DATA_ITEM);
        int count = entityData.get(DATA_COUNT);
        // Bounded on purpose: nothing a server sends may make a client draw or allocate something impossible.
        contents.restore(item.isEmpty() || count <= 0 ? null : ItemKey.of(item), Math.min(count, MAX_LOAD));
    }

    /**
     * Gravity, a move and drag — the least that makes "a pallet lies on the floor and can be shoved across it" true.
     * {@code super.tick()} is the base tick, which is where fire, lava, water and the void check live.
     * <p>
     * <b>A pallet that is simply lying there skips the collision step</b> ({@link #resting()}). That is not a
     * micro-optimisation on this entity: a pallet is <b>permanent</b> by design, it only goes away once it has been
     * emptied — up to 1 024 Shift-clicks for a full brass bay — and it ticks with no player nearby, because
     * {@code AisleChunkTickets} holds non-ticking tickets under which entities run anyway. So one pallet per broken
     * full bay accumulates and none of them ever stops working. {@code move} is the expensive half of this method:
     * the entity-collision query, the block sweep, {@code tryCheckInsideBlocks} and the
     * {@code getBlockStatesIfLoaded(...).noneMatch(...)} stream all sit inside it, and a resting pallet never reaches
     * {@code Entity.collide}'s {@code vec.lengthSqr() == 0} short circuit because gravity puts {@code -0.04} back on
     * the delta every tick.
     */
    @Override
    public void tick() {
        super.tick();
        if (isRemoved())
            return;
        applyGravity();
        if (resting())
            return;
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(AIR_DRAG));
        if (onGround())
            setDeltaMovement(getDeltaMovement().multiply(GROUND_DRAG, 0.0, GROUND_DRAG));
    }

    /**
     * Whether this pallet may skip its collision step this tick: it stands on the ground, nothing is shoving it
     * sideways, and this is not its periodic re-check.
     * <p>
     * This is <b>vanilla's own guard for its one long-lived dropped entity</b>, taken verbatim in shape:
     * {@code ItemEntity.tick} wraps its {@code move} in
     * {@code !onGround() || horizontalDistanceSqr() > 1.0E-5F || (tickCount + getId()) % 4 == 0}, so a resting item
     * pays a quarter of the sweep — and it despawns after 6 000 ticks on top of that, which a pallet deliberately
     * never does. Gravity stays unconditional, exactly as it is there, so the skipped ticks accumulate a downward
     * delta that the next re-check spends: a pallet still falls the moment the floor under it is mined, and the
     * {@code getId()} term staggers a row of pallets over the four ticks instead of bunching them.
     */
    private boolean resting() {
        return onGround() && getDeltaMovement().horizontalDistanceSqr() <= RESTING_MOTION_SQR
                && (tickCount + getId()) % RESTING_RECHECK_TICKS != 0;
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    /** A pallet is a load on the floor, not a boat: a current leaves it where it is. */
    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    /** Or it could not be right-clicked at all. */
    @Override
    public boolean isPickable() {
        return true;
    }

    /** Shoving a pallet across the floor is the picture this feature is built on. */
    @Override
    public boolean isPushable() {
        return true;
    }

    /** There is nothing a punch could do to it, so the server does not even have to try. */
    @Override
    public boolean isAttackable() {
        return false;
    }

    /** A pallet is not a vehicle and does not ride one; a pallet in a minecart is a removal crate by another name. */
    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level oldLevel, Level newLevel) {
        return false;
    }

    /**
     * The one allowed loss, and the only one that leaves a record: the void. Logged <b>before</b> {@code super}
     * discards, with the item, the count and the position, so a player who finds a bay's load gone can be told where it
     * went.
     */
    @Override
    protected void onBelowWorld() {
        ItemKey key = contents.stored();
        if (key != null && !level().isClientSide)
            LOGGER.warn("A pallet carrying {} x {} fell out of the world at {}; those items are lost",
                    contents.count(), key, blockPosition());
        super.onBelowWorld();
    }

    // --- persistence ------------------------------------------------------------------------------------------------

    /**
     * {@value #STORED_TAG} as a count-less {@link ItemKey} and {@value #COUNT_TAG} as an {@code int} — never
     * {@code ItemStack.save}, see the class comment. A key that cannot be encoded writes no count either, so the two
     * keys are never out of step and a half-written pallet reads back as an empty one rather than as a lost item of
     * nothing.
     */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ItemKey key = contents.stored();
        if (key == null || contents.count() <= 0)
            return;
        key.saveTo(tag, STORED_TAG, registryAccess());
        if (!tag.contains(STORED_TAG))
            return;
        tag.putInt(COUNT_TAG, contents.count());
    }

    /**
     * Reads what {@link #addAdditionalSaveData} wrote. Never throws, whatever the tag holds, and a pallet that has no
     * load left to carry <b>discards itself</b> — vanilla's own answer for an item entity whose stack it could not read
     * ({@code ItemEntity#readAdditionalSaveData}), and far better than an unremovable husk nobody can get rid of.
     */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        int saved = tag.getInt(COUNT_TAG);
        Optional<ItemKey> key = saved > 0 ? ItemKey.loadFrom(tag, STORED_TAG, registryAccess()) : Optional.empty();
        if (key.isEmpty()) {
            if (saved > 0)
                LOGGER.warn("A pallet at {} says it carries {} items of an item type that cannot be read; it is removed",
                        blockPosition(), saved);
            setLoad(null, 0);
            discard();
            return;
        }
        setLoad(key.get(), saved);
    }

    @Override
    public String toString() {
        return "PalletEntity" + contents;
    }

    // --- the item handler -------------------------------------------------------------------------------------------

    /**
     * One slot, <b>extract-only</b>. {@link #getStackInSlot} answers the <b>true, oversized</b> count, as a rack bay's
     * handler does and for the same reason: both item censuses read exactly this number, and a clamped answer would
     * make every conservation test report a gain the moment anything took a stack off a pallet.
     */
    private final class Handler implements IItemHandler {
        @Override
        public int getSlots() {
            return SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            ItemKey key = slot == SLOT ? contents.stored() : null;
            return key == null ? ItemStack.EMPTY : key.toStack(contents.count());
        }

        /** Nothing is ever put into a pallet: the way in is the bay, and a fillable pallet would be a portable crate. */
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == SLOT ? takeLoad(amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == SLOT ? MAX_LOAD : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public String toString() {
            return "PalletHandler" + contents;
        }
    }

    /**
     * Mod-bus listener: the load on both entity item capabilities, so a vanilla hopper and a Create Deployer can drain
     * a pallet while Create's own logistics cannot see it at all (class comment). {@code ENTITY} is the plain inventory
     * view and {@code ENTITY_AUTOMATION} is the one vanilla's hopper code falls back to.
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, WareworksEntityTypes.PALLET.get(),
                (pallet, context) -> pallet.handler);
        event.registerEntity(Capabilities.ItemHandler.ENTITY_AUTOMATION, WareworksEntityTypes.PALLET.get(),
                (pallet, side) -> pallet.handler);
    }
}
