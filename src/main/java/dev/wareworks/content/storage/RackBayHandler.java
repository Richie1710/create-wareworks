package dev.wareworks.content.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.inventory.CapacityMath;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.inventory.SlotView;
import dev.wareworks.core.storage.BayContents;
import dev.wareworks.core.storage.BayTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The item handler of a rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20): <b>one slot, one item type
 * and a count</b>, over a {@link BayContents}.
 *
 * <h2>Why this is hand-written and not an {@link ItemStackHandler}</h2>
 * It is not a preference, it is forced twice over.
 * <ul>
 * <li><b>A bay on an {@code ItemStackHandler} would lose its whole load on the first save, silently.</b>
 * {@code ItemStackHandler.serializeNBT} saves each slot with {@code ItemStack.save}, and {@code ItemStack.CODEC} bounds
 * the count at {@code Item.ABSOLUTE_MAX_STACK_SIZE} = 99. A brass bay holds 65 536. Nothing would throw — the
 * <i>network</i> codec is unbounded, so the bay would look right until the world was reloaded. ADR-013 already forbids
 * saving contents as {@code ItemStack}s for this reason, which is why {@link #writeTo} saves a count-less
 * {@link ItemKey} plus an {@code int}, exactly as the station buffer and the handling head do.</li>
 * <li><b>A raised slot limit alone would not even work.</b> {@code ItemStackHandler.insertItem} caps an insert at
 * {@code min(getSlotLimit, stack.getMaxStackSize())}, so a bay would accept one stack and then refuse everything.</li>
 * </ul>
 *
 * <h2>What it tells the world, and why honestly</h2>
 * <ul>
 * <li>{@link #getStackInSlot} returns the <b>true, oversized</b> count — {@code cobblestone x 65536}, not a stack of
 * 64. The {@code IItemHandler} contract allows it ("the result's stack size may be greater than the itemstack's max
 * size"), every drawer mod does it, and the item censuses of this mod's tests read exactly this number
 * ({@code gametest.ItemCensus}): a clamped answer would make every conservation test report a gain of 64 the moment
 * the crane took a stack out of a bay, and would hide real losses behind the noise.</li>
 * <li>{@link #getSlotLimit} answers the bay's capacity for what it holds, and for an <b>empty</b> bay the largest
 * capacity it could have for any item ({@code stacks x 99}, the largest stack Minecraft allows) — an empty bay does not
 * know its key, so no exact number exists.</li>
 * <li>{@link #extractItem} returns at most one stack per call, as the contract requires and as the crane's grabber and
 * a vanilla hopper both expect; call it again for more.</li>
 * <li>{@link #isItemValid} answers the store filter <b>and</b> the one stored type. The contract asks for a rule that
 * ignores the inventory's state, and the stored type is state — but it is the one state that cannot change except by
 * emptying the bay, and answering it is what makes a filtered funnel back up instead of hammering a bay that will never
 * take its item. Fullness is deliberately <i>not</i> considered, which is what the contract is really about.</li>
 * </ul>
 * <b>No aliasing.</b> A caller's {@code ItemStack} instance is never kept: what is stored is a count-less
 * {@link ItemKey}, so the no-aliasing property the station buffer had to implement by hand is free here.
 * <p>
 * Server thread only. Every real change runs {@link Rules#onContentsChanged()}.
 */
public class RackBayHandler implements IItemHandler {
    /** The bay has exactly one slot; a count, not a slot list, is what makes it hold a thousand stacks. */
    public static final int SLOTS = 1;
    /** The only slot index. */
    public static final int SLOT = 0;
    /** Save key of the stored item type, a count-less {@link ItemKey}; absent while the bay is empty. */
    public static final String STORED_TAG = "Stored";
    /** Save key of the stored amount; absent while the bay is empty. Also the client packet's count. */
    public static final String COUNT_TAG = "Count";
    /**
     * <b>Client packet</b> key of the stored item type, as a registry id string — never an {@link ItemKey}; absent
     * while the bay is empty. See {@link #writeClientPacket}.
     */
    public static final String STORED_ITEM_TAG = "StoredItem";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * What the handler has to ask its bay, because none of it is a property of the contents: the capacity depends on the
     * tier and on a server config that may change under a standing bay, and the filter is a Create behaviour.
     */
    public interface Rules {
        /** How many items of {@code key} this bay holds in total ({@code BayTier.capacity}). */
        long capacity(ItemKey key);

        /**
         * The slot limit to report while the bay is empty: the most it could hold of <b>any</b> item, which is its
         * configured stack count times the largest stack Minecraft allows.
         */
        int emptySlotLimit();

        /** Whether the store filter lets {@code stack} in at all; true for a bay nobody filtered. */
        boolean passesFilter(ItemStack stack);

        /** Called after every real change through this handler (never on a load). */
        void onContentsChanged();

        /** Where this bay stands, for the one warning a bounded load may log. */
        BlockPos position();
    }

    private final BayContents<ItemKey> contents = new BayContents<>();
    private final Rules rules;

    public RackBayHandler(Rules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    // --- contents ------------------------------------------------------------------------------------------------

    /** The stored item type, empty while the bay is empty — which is also how it forgets a learned type. */
    public Optional<ItemKey> stored() {
        return Optional.ofNullable(contents.stored());
    }

    /** {@link #stored()} without the wrapper, for the render thread ({@code RackBayBlockEntity#storedKeyOrNull}). */
    @Nullable
    public ItemKey storedOrNull() {
        return contents.stored();
    }

    /** How many items are stored; 0 exactly when {@link #stored()} is empty. */
    public int count() {
        return contents.count();
    }

    /**
     * The bay as an inventory snapshot for the controller's stock index and its capacity estimates: <b>one</b> slot,
     * whose limit is the bay's capacity in items. Built directly rather than through
     * {@code ItemHandlerSnapshots.capture}, which would call {@link #getStackInSlot} and build an oversized
     * {@code ItemStack} for nothing.
     */
    public InventorySnapshot<ItemKey> snapshot() {
        ItemKey key = contents.stored();
        if (key == null)
            return InventorySnapshot.of(List.of(SlotView.empty(rules.emptySlotLimit())));
        int limit = CapacityMath.toIntClamped(rules.capacity(key));
        return InventorySnapshot
                .of(List.of(SlotView.of(key, contents.count(), limit, key.getMaxStackSize())));
    }

    /** {@code Clearable}: empties the bay without dropping anything ({@code /setblock}, structure placement). */
    public void clear() {
        contents.clear();
    }

    /**
     * Takes everything out at once, for the one caller that must not go through an item handler: the block being
     * destroyed, which has to hand the whole load on in a single move. The bay is empty afterwards, so a second pass
     * finds nothing.
     *
     * @return the whole load as one oversized stack, or empty
     */
    public ItemStack takeAll() {
        ItemKey key = contents.stored();
        if (key == null)
            return ItemStack.EMPTY;
        int load = contents.count();
        contents.clear();
        return key.toStack(load);
    }

    // --- item handler --------------------------------------------------------------------------------------------

    @Override
    public int getSlots() {
        return SLOTS;
    }

    /**
     * The whole load as one stack, whose count may be far above the item's own maximum — see the class comment. A fresh
     * stack every call, because the contract forbids a caller to modify what it gets and a bay has no stack of its own
     * to hand out.
     */
    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemKey key = slot == SLOT ? contents.stored() : null;
        return key == null ? ItemStack.EMPTY : key.toStack(contents.count());
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot != SLOT || stack.isEmpty() || !rules.passesFilter(stack))
            return stack;
        ItemKey key = ItemKey.of(stack); // a count-less copy: the caller's instance is never stored
        int accepted = contents.insert(key, stack.getCount(), rules.capacity(key), simulate);
        if (accepted <= 0)
            return stack;
        if (!simulate)
            rules.onContentsChanged();
        return accepted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemKey key = slot == SLOT ? contents.stored() : null;
        if (key == null || amount <= 0)
            return ItemStack.EMPTY;
        int taken = contents.extract(amount, key.getMaxStackSize(), simulate);
        if (taken <= 0)
            return ItemStack.EMPTY;
        if (!simulate)
            rules.onContentsChanged();
        return key.toStack(taken);
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot != SLOT)
            return 0;
        ItemKey key = contents.stored();
        return key == null ? rules.emptySlotLimit() : CapacityMath.toIntClamped(rules.capacity(key));
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot != SLOT || stack.isEmpty() || !rules.passesFilter(stack))
            return false;
        ItemKey key = contents.stored();
        return key == null || key.matches(stack);
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * Writes the contents into {@code tag}: {@value #STORED_TAG} as a count-less {@link ItemKey} and
     * {@value #COUNT_TAG} as an {@code int}. An empty bay writes <b>neither</b> key, so an empty rack wall costs no
     * save bytes and a bay placed before this method existed reads back exactly as empty.
     * <p>
     * Never throws and never truncates: {@code ItemStack.save} is not called anywhere, which is the whole reason for
     * this shape. A key that cannot be encoded at all writes no count either, so the two keys are never out of step.
     */
    public void writeTo(CompoundTag tag, HolderLookup.Provider registries) {
        ItemKey key = contents.stored();
        if (key == null || contents.count() <= 0)
            return;
        key.saveTo(tag, STORED_TAG, registries);
        if (!tag.contains(STORED_TAG))
            return; // the key could not be encoded; a count without it would read back as a lost item of nothing
        tag.putInt(COUNT_TAG, contents.count());
    }

    /**
     * Reads what {@link #writeTo} wrote. Never throws, whatever the tag holds — save data is untrusted, because
     * {@code /data merge}, uploaded schematics and crafted block entity data all reach it:
     * <ul>
     * <li><b>neither</b> {@value #COUNT_TAG} <b>nor</b> {@value #STORED_TAG}: the contents are left exactly as they
     * are, because such a tag says nothing about them at all — see below;</li>
     * <li>a {@value #COUNT_TAG} at or below 0, or a {@value #STORED_TAG} that cannot be decoded (the item's mod was
     * removed): the bay is empty, which is vanilla's own answer for an unreadable container entry;</li>
     * <li>a count above {@link BayTier#MAX_CAPACITY_ITEMS}, which <b>no</b> configuration can produce: clamped, and
     * logged as the item loss it is;</li>
     * <li>a count above this bay's current capacity, which a lowered config legitimately produces: <b>kept in full</b>
     * and logged once. The bay accepts nothing until it drains, so lowering a capacity never destroys an item
     * (D11).</li>
     * </ul>
     * There is no slot list to size, so a crafted count allocates nothing at all.
     *
     * <h2>Why a tag carrying neither key leaves a <b>standing</b> bay alone</h2>
     * A world load always reads into a freshly built, already empty handler, so "leave it alone" and "empty it" are
     * the same answer there — and an empty bay writes neither key, which is what a world from before M28 and every
     * bay of an idle rack wall look like. The difference is the one caller that reads a tag into a bay that is
     * <b>already standing with goods in it</b>: {@code BlockHelper.placeSchematicBlock} writes the new block state and
     * then calls {@code loadWithComponents} on whatever block entity is there. The bay's own block entity survives
     * that write ({@code IBE.onRemove} returns before {@code destroy()} when the block stays the same), so a Create
     * schematic print — the creative instant print, or a schematicannon with <i>Replace Block Entities</i> on — used to
     * reach this method with a tag that carries only the filter and the priority ({@code isSafeNBT}) and empty a full
     * brass bay <b>in place</b>: no {@code destroy()}, so no pallet and no drop, and {@link BayContents#clear()}
     * rather than {@link #clear()}, so not even a log line, a {@code FILL} update or a client sync. A vanilla chest in
     * the same spot keeps its 27 stacks, which made the bay strictly worse than the container it replaces. The guard
     * below is the same one the station buffer has always had ({@code WarehouseStationBlockEntity#read}), and it
     * closes {@code /data remove} of both keys with it. {@link #clear()} stays the one deliberate way to empty a bay,
     * which is what {@code /setblock}, {@code /fill}, {@code /clone} and structure placement reach through
     * {@code Clearable.tryClear}.
     */
    public void readFrom(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(COUNT_TAG) && !tag.contains(STORED_TAG))
            return; // says nothing about the contents: a schematic print over a standing bay must not empty it
        int saved = tag.getInt(COUNT_TAG);
        Optional<ItemKey> key = saved > 0 ? ItemKey.loadFrom(tag, STORED_TAG, registries) : Optional.empty();
        if (key.isEmpty()) {
            contents.clear();
            return;
        }
        int count = saved;
        if (count > BayTier.MAX_CAPACITY_ITEMS) {
            LOGGER.warn("Rack bay at {} says it holds {} x {}, which no configuration allows; {} items are lost",
                    rules.position(), count, key.get(), count - BayTier.MAX_CAPACITY_ITEMS);
            count = BayTier.MAX_CAPACITY_ITEMS;
        }
        contents.restore(key.get(), count);
        long capacity = rules.capacity(key.get());
        if (count > capacity)
            LOGGER.warn("Rack bay at {} holds {} x {}, more than the {} items it is configured for; it keeps all of "
                    + "them and accepts nothing until it has drained", rules.position(), count, key.get(), capacity);
    }

    // --- client packet -------------------------------------------------------------------------------------------

    /**
     * Writes the contents for a <b>client packet</b>: the item's registry <b>id</b> as a string under
     * {@value #STORED_ITEM_TAG} and the count under {@value #COUNT_TAG}, and nothing at all while the bay is empty.
     * <p>
     * <b>An {@link ItemKey} must never cross this path</b> ({@code docs/warehouse-system.md} §3.1.1): a block entity's
     * update tag is part of <b>every</b> chunk packet, which a client reads with a 2 MB NBT quota, and the first
     * version of the warehouse interface synced full item keys — one shulker box of written books in a rack wall could
     * disconnect everyone loading the chunk. An id and an int are a fixed, small size whatever is in the bay. The
     * visible consequence is worth knowing rather than discovering: a <b>renamed or enchanted</b> item in a bay shows
     * its plain name on the client.
     * <p>
     * The key is deliberately not {@value #STORED_TAG}: that one holds an {@link ItemKey} compound in a <b>save</b>,
     * and one name for two NBT types is how a reader ends up looking at the wrong tag.
     */
    public void writeClientPacket(CompoundTag tag) {
        ItemKey key = contents.stored();
        if (key == null || contents.count() <= 0)
            return;
        tag.putString(STORED_ITEM_TAG, ItemTypeSummaries.itemId(key.getItem()));
        tag.putInt(COUNT_TAG, contents.count());
    }

    /**
     * Reads what {@link #writeClientPacket} wrote. Never throws and is bounded: an unknown item (a server with a mod
     * this client does not have), an absent or non-positive count and a count beyond
     * {@link BayTier#MAX_CAPACITY_ITEMS} all read as the empty bay or the bound, so nothing a server sends can make a
     * client draw or allocate something impossible.
     * <p>
     * The key the client rebuilds is a <b>component-less</b> key for the id, which is the whole of what crossed the
     * wire. That is exactly what the client needs it for — drawing the contents and simulating a player's own
     * gesture before the server answers — and it is why a renamed item reads as its plain self here.
     */
    public void readClientPacket(CompoundTag tag) {
        int count = tag.getInt(COUNT_TAG);
        Optional<Item> item = count > 0 ? ItemTypeSummaries.itemById(tag.getString(STORED_ITEM_TAG))
                : Optional.empty();
        if (item.isEmpty()) {
            contents.clear();
            return;
        }
        contents.restore(ItemKey.of(item.get()), Math.min(count, BayTier.MAX_CAPACITY_ITEMS));
    }

    @Override
    public String toString() {
        return "RackBayHandler" + contents;
    }
}
