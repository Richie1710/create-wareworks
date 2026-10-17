package dev.wareworks.content.storage;

import java.util.List;
import java.util.function.IntConsumer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.util.WareworksLang;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The store settings of a warehouse interface ({@code docs/warehouse-system.md} §3.1.1, ADR-021, ADR-028): Create's
 * {@link FilteringBehaviour} carrying <b>two</b> settings on one value box — the store filter, and the storage priority
 * added in M16 (issue #11).
 * <p>
 * <b>Why the priority shares this box instead of getting its own.</b> The interface has exactly one usable face. The
 * aisle face is a 3 px brass frame around a window whose andesite plate is only 6 px tall (x 3..13, <b>y 3..9</b>), with
 * the crane's arm port directly above it (y 9..13). A value box hit sphere is {@code scale / 2} = 4 px in radius, so two
 * boxes need their centres 8 px apart — more than the plate is tall, and any second position either reaches into the arm
 * path, leaves the block, or overlaps the first box's sphere (the earlier behaviour in
 * {@code SmartBlockEntity#getAllBehaviours} order would then silently swallow the other). Every other face is taken:
 * {@code FACING} touches the attached inventory, the lateral faces carry the row-building placement rule, and top and
 * bottom are the wrench faces and are covered by the neighbours in a rack wall — putting a setting there is exactly the
 * ADR-022 mistake M10 had to undo. So the priority is a <b>board row on the existing box</b>, which is also Create's most
 * common idiom (funnel, saw, deployer, belt tunnel):
 * <ul>
 * <li>a click <b>with an item</b> sets the filter on the press ({@link StorageFilterClicks}, issue #30);</li>
 * <li>a <b>short</b> click with an <b>empty hand</b> clears it;</li>
 * <li><b>hold</b> the click with an empty hand or a wrench for five ticks and Create's {@code ValueSettingsScreen}
 * opens with one row, "Priority", {@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}.</li>
 * </ul>
 * {@link #acceptsValueSettings()} is {@code true} for that board, so Create's {@code ValueSettingsInputHandler} no
 * longer takes its immediate {@code onShortInteract} branch for this slot but goes through the client warmup and
 * applies a short click on <b>release</b>. Between M16 and issue #30 that was every filter click, and it made them
 * unreliable: held a moment too long the board opened instead, and a crosshair that left the box before the release
 * dropped the click. So it answers {@code false} for exactly one thing, the dispatch of a click with an item that
 * {@link StorageFilterClicks} marked as a press, and Create's handler then applies that click at once; the warmup is
 * left with the two inputs that have no filter to set. Otherwise the answer does not depend on what is held, and it is
 * deliberately <b>not</b> Create's {@code isCountVisible()}, whose {@code getMaxStackSize() > 1} term would make the
 * board unreachable as soon as a player used a non-stackable item as a plain filter. The M8 fake-player protection is
 * untouched: {@code ValueSettingsInputHandler} and {@link StorageFilterClicks} both ask {@link #mayInteract}
 * <i>before</i> anything else.
 * <p>
 * <b>Negative priorities were rejected.</b> {@code ValueSettingsScreen#getClosestCoordinate} scans columns from 0, so a
 * negative value cannot be picked on a board at all, and an offset encoding would make the saved number differ from the
 * shown one. "Fill last" is expressed by raising the others, and a 0-based range can be widened later without a
 * migration. The value is clamped <b>on read</b>, like the crane's mast height, so a future lower maximum never rewrites
 * a player's number.
 * <p>
 * <b>An empty filter writes nothing.</b> Create's {@code write} emits {@code Filter}, {@code FilterAmount} and
 * {@code UpTo} unconditionally, which costs roughly 215 bytes of NBT size accounting in <b>every</b> interface's update
 * tag — and that tag is part of every chunk packet, read by clients with a 2 MB quota (§3.1.1). Here only the filter is
 * a setting, so {@link #write} writes the {@code Filter} entry alone and only while a filter is set; Create's
 * {@code read} builds an empty {@code FilterItemStack} from a missing tag, so an absent entry reads back exactly as
 * "accepts everything". A priority is one int under a 13-character key (~66 accounting bytes) and is written <b>only
 * while it is not 0</b>, so an unprioritised, unfiltered location writes nothing at all, and a prioritised but
 * unfiltered one pays for the number alone. The client needs the number because {@code addToGoggleTooltip} and the
 * block renderer both run client-side.
 * <p>
 * <b>Saves and schematics.</b> A save writes the same two settings, so filter and priority persist. {@code isSafeNBT()}
 * is {@code true} and {@code BlockEntityBehaviour#writeSafe} defaults to {@code write(tag, .., false)}, so both travel
 * in a schematic, a number costs no {@code getRequiredItems()}, and a location at its defaults adds nothing to a
 * schematic at all (issue #25, ADR-061). The filter is stored and written as a <b>sample of one</b> ({@link #sampleOf}).
 * A pre-M16 world has no {@value #PRIORITY_TAG} key and reads back 0, and an older save (before issue #25) carries
 * Create's {@code FilterAmount} and {@code UpTo}, which read exactly as before; nothing migrates and loading can never
 * throw.
 * <p>
 * <b>Clipboard.</b> The key stays Create's {@code "Filtering"}, so copying a funnel's filter onto an interface keeps
 * working, but Create's generic {@code Value}/{@code Row} pair must not carry the priority, or a funnel's <i>extracted
 * amount</i> would land in it and an interface's priority would become a funnel's amount. {@link #writeToClipboard}
 * therefore removes that pair and writes {@value #PRIORITY_TAG} instead, and {@link #readFromClipboard} ignores the
 * upstream {@code setValueSettings} ({@link #pastingClipboard}) and applies only {@value #PRIORITY_TAG}. All four
 * directions are then right: interface → interface copies filter <b>and</b> priority (dedicating a whole rack wall with
 * one clipboard is the headline use case), funnel → interface copies only the filter, interface → funnel sets only the
 * filter.
 * <p>
 * <b>A clipboard writes the priority even at {@value #MIN_PRIORITY}</b>, unlike {@link #write}: the byte budget above is
 * about update tags, and in a clipboard an omitted key would make "no priority" unsayable. Create writes its
 * {@code Filter} entry unconditionally and pastes an empty stack back as "no filter", so a paste from a neutral
 * interface <i>does</i> clear the target's filter — if the priority were omitted the same paste would silently leave the
 * target's number in place, and the ranking input the player believes they overwrote would still decide where the crane
 * stores. A funnel's clipboard still carries no {@value #PRIORITY_TAG} at all, so the missing key stays an unambiguous
 * "not copied from an interface" for {@link #readFromClipboard}.
 */
public class StorageFilterBehaviour extends FilteringBehaviour {
    /** Lowest storage priority: the default, and what every location of a warehouse before M16 has. */
    public static final int MIN_PRIORITY = 0;
    /**
     * Highest storage priority. A single digit, so the digit the block renderer draws stays one glyph wide; the range is
     * 0-based so #12 can widen it without a save migration.
     */
    public static final int MAX_PRIORITY = 9;
    /** Priorities between two milestones of the hold-to-edit board (0, 3, 6, 9). */
    public static final int PRIORITY_MILESTONE_INTERVAL = 3;
    /**
     * Save, client-packet and clipboard key of the storage priority. Into a save or a packet it is written only while it
     * is not {@value #MIN_PRIORITY} (the byte budget); into a clipboard it is written always, so that a paste can clear
     * a priority the way it clears a filter.
     */
    public static final String PRIORITY_TAG = "StorePriority";
    /** Create's key of the filter stack, in saves, packets, schematics and clipboard data alike. */
    public static final String FILTER_TAG = "Filter";
    /** The board has one row, so every setting arrives on row 0. */
    private static final int PRIORITY_ROW = 0;
    /** Create's generic value-settings keys in clipboard data; deliberately not used to carry the priority. */
    private static final String CLIPBOARD_VALUE_TAG = "Value";
    private static final String CLIPBOARD_ROW_TAG = "Row";

    private int priority = MIN_PRIORITY;
    private IntConsumer priorityCallback = value -> {
    };
    /**
     * A clipboard paste is running, so the upstream {@code readFromClipboard} must not apply Create's generic
     * {@code Value}/{@code Row} pair to the priority (a funnel's extracted amount would become one).
     */
    private boolean pastingClipboard;

    public StorageFilterBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(be, slot);
    }

    /** Called with the new priority whenever it changes (server: the controllers re-read it). */
    public StorageFilterBehaviour withPriorityCallback(IntConsumer callback) {
        this.priorityCallback = callback;
        return this;
    }

    // --- storage priority ----------------------------------------------------------------------------------------

    /**
     * The storage priority of this location, {@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}; higher fills first when
     * storing. Clamped on read, so a number saved under a wider range is never silently rewritten.
     */
    public int priority() {
        return Mth.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
    }

    /**
     * Sets the storage priority as the hold-to-edit board would (out-of-range values are clamped).
     *
     * @return whether it changed
     */
    public boolean setPriority(int value) {
        int clamped = Mth.clamp(value, MIN_PRIORITY, MAX_PRIORITY);
        if (clamped == priority())
            return false;
        priority = clamped;
        blockEntity.setChanged();
        blockEntity.sendData();
        priorityCallback.accept(clamped);
        return true;
    }

    /**
     * {@code true}, not Create's {@code isCountVisible()}: the board must stay reachable whatever sits in the filter
     * slot (see the class comment). {@code false} only while the right-click being dispatched on this thread is a press
     * with an item that {@link StorageFilterClicks} marked on this slot, so that Create's
     * {@code ValueSettingsInputHandler} applies it at once instead of starting its warmup (issue #30). The renderer, the
     * board's own packet ({@code ValueSettingsPacket#applySettings}) and the clipboard ask outside that dispatch and
     * always see {@code true}.
     */
    @Override
    public boolean acceptsValueSettings() {
        return !StorageFilterClicks.isPressOn(this);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(PRIORITY_ROW, priority());
    }

    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean ctrlDown) {
        // A clipboard paste carries Create's generic Value/Row pair, which here means nothing (see the class comment).
        if (pastingClipboard)
            return;
        if (setPriority(settings.value()))
            playFeedbackSound(this);
    }

    /** One row, "Priority", 0..{@value #MAX_PRIORITY}, with milestones every {@value #PRIORITY_MILESTONE_INTERVAL}. */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_PRIORITY),
                MAX_PRIORITY, PRIORITY_MILESTONE_INTERVAL,
                List.of(WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_PRIORITY_ROW)),
                new ValueSettingsFormatter(this::formatValue));
    }

    @Override
    public MutableComponent formatValue(ValueSettings value) {
        return Component.literal(String.valueOf(Mth.clamp(value.value(), MIN_PRIORITY, MAX_PRIORITY)));
    }

    /**
     * True so Create's {@code FilteringRenderer} adds {@link #getAmountTip()} as the third hover-tip line. Create's own
     * implementation would also demand a stackable filter item, which has nothing to do with a priority.
     */
    @Override
    public boolean isCountVisible() {
        return true;
    }

    /**
     * "Hold with an empty hand to set the priority; higher fills first", the third line of the hover tip Create shows
     * for this slot: with an item in hand the click sets the filter on the press ({@link StorageFilterClicks}), and
     * nothing else in the game says which way the number ranks.
     */
    @Override
    public MutableComponent getAmountTip() {
        return WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_PRIORITY_TIP);
    }

    /**
     * <b>Always empty</b>, so this slot's value box carries no number of its own: the priority is drawn once, by
     * {@code client.render.WarehouseInterfaceRenderer}, on the block itself.
     * <p>
     * Showing the priority here as well — where a funnel shows its extracted amount — looked free and is not. Create's
     * {@code FilteringRenderer#tick} builds the {@code ItemValueBox} with this label and hands it to the {@code Outliner}
     * <b>before</b> its {@code if (!hit) continue;}, and {@code ValueBox#render} guards only the outline <i>icon</i> with
     * {@code if (!isPassive)} and then calls {@code renderContents} unconditionally. So {@code passive(!hit)} suppresses
     * the icon, not the label: the label appears for <b>every</b> interface the crosshair rests on, not only inside the
     * 4 px hit sphere. For an empty filter slot — the default, and the common case for a prioritised location — Create's
     * {@code isEmpty} branch then puts its glyph at roughly x 7.6..8.8, y 4.3..5.8 px, i.e. <b>inside</b> the renderer's
     * own digit at x 6.9..9.1, y 4.0..7.0 px, at 55 % of its size and with a dark outline that shows through the open
     * counters of the big glyph (the text render type discards transparent fragments). The number the player aims at to
     * read it was the one that came out garbled; with a filter item in the slot the two glyphs stopped overlapping but
     * the plate showed the same number twice at two sizes.
     * <p>
     * {@link #isCountVisible()} stays {@code true}: that is what adds {@link #getAmountTip()} as the third hover-tip
     * line, and it is the only reason this override cannot simply be dropped — Create's own implementation would put the
     * <i>filter amount</i> here.
     */
    @Override
    public MutableComponent getCountLabelForValueBox() {
        return Component.empty();
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    /**
     * Only a real player may change a storage location's partitioning (M8 review fix), and a player holding the
     * Mechanical Arm item does not interact with this slot at all (M16, mirroring
     * {@code content.station.RequestFilterBehaviour}).
     * <p>
     * Create's {@code ValueSettingsInputHandler} skips the 4 px hit test of a value box entirely for a
     * {@code FakePlayer} and then interacts immediately, so <b>any</b> right-click a deployer (or another mod's
     * automation) aims at this block's aisle face would set the filter, wherever it hits — and
     * {@code onShortInteract} hands a previously set Create filter item to that fake player's inventory, where it is
     * discarded. The aisle face is the one face in-aisle automation can reach, and re-dedicating a chest by accident
     * is permanent (nothing is ever re-shuffled, ADR-021), so fake players are refused here. This is checked before
     * that shortcut, and it also covers the clipboard path, which asks {@code mayInteract} as well.
     */
    @Override
    public boolean mayInteract(Player player) {
        return !(player instanceof FakePlayer) && !AllBlocks.MECHANICAL_ARM.isIn(player.getMainHandItem())
                && super.mayInteract(player);
    }

    /**
     * A click with the Mechanical Arm item passes through the slot to Create's arm target selection (M12,
     * {@code content.station.RequestFilterBehaviour} does the same for the output). Without this, a click that aims the
     * arm at the aisle face of an interface did nothing at all: {@code ValueSettingsInputHandler} cancelled it with
     * {@code SUCCESS} and Create's {@code canShortInteract} then refuses the arm item as a filter.
     */
    @Override
    public boolean bypassesInput(ItemStack mainhandItem) {
        return AllBlocks.MECHANICAL_ARM.isIn(mainhandItem) || super.bypassesInput(mainhandItem);
    }

    /**
     * Create's own short click — on the press for a click with an item ({@link StorageFilterClicks}), on the release
     * of Create's warmup otherwise — except that a press with a stack that already <b>is</b> the filter changes nothing
     * ({@link #holdsFilterOf}): applying it again would only cost an update packet, a sound and a controller re-read,
     * and swap one Create filter item for an identical one. The release path is left exactly as it was, so a short
     * click with an empty hand still clears the filter.
     */
    @Override
    public void onShortInteract(Player player, InteractionHand hand, Direction side, BlockHitResult hitResult) {
        if (StorageFilterClicks.isPressOn(this) && holdsFilterOf(player.getItemInHand(hand)))
            return;
        super.onShortInteract(player, hand, side, hitResult);
    }

    // --- persistence, sync and clipboard -------------------------------------------------------------------------

    /**
     * Deliberately not {@code super.write}, on every path — save, client packet and schematic ({@code writeSafe} falls
     * back to this with {@code clientPacket == false}): Create writes {@code Filter}, {@code FilterAmount} and
     * {@code UpTo} unconditionally, and here only the filter is a setting. The amount is never read (the board carries
     * the priority, {@link #getCountLabelForValueBox()} is empty) and {@code upTo} never changes, and Create's
     * {@code read} turns both missing keys into its own defaults ({@code count == 0} takes its migration branch). So
     * an unfiltered, unprioritised location writes <b>nothing at all</b>, which keeps it out of the chunk packets and
     * out of a schematic (issue #25, ADR-061). {@code BlockEntityBehaviour#write} itself is empty, so nothing is lost.
     */
    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        writeSample(this, nbt, registries);
        writePriority(nbt);
    }

    /**
     * Writes Create's {@code Filter} entry only while a filter is set, and the filter as a <b>sample of one</b>
     * ({@link #sampleOf}): a missing entry reads back as an empty filter, exactly as Create's own empty
     * {@code ItemStack.saveOptional} does.
     */
    public static void writeSample(FilteringBehaviour behaviour, CompoundTag nbt, HolderLookup.Provider registries) {
        ItemStack filter = behaviour.getFilter();
        if (!filter.isEmpty())
            nbt.put(FILTER_TAG, sampleOf(filter).saveOptional(registries));
    }

    /**
     * A filter as the <b>sample</b> it is: one item, whatever the player held when they set it.
     * <p>
     * Create's {@code onShortInteract} copies the whole held stack into the slot and cuts only a Create filter item to
     * one, so a click with 64 cobblestone used to keep "64 cobblestone" in the slot — in the save, in every chunk packet
     * and in every schematic, where a checker reading the file sees 64 items that do not exist. The count of a ghost
     * means nothing (a filter matches by item and components), and it can never be taken out: {@code destroy()} drops
     * only a filter item. A Create filter item is left exactly as it is, because that one <b>is</b> a real item, paid
     * for at the Schematicannon ({@code FilteringBehaviour#getRequiredItems}) and dropped when the block breaks.
     */
    public static ItemStack sampleOf(ItemStack filter) {
        if (filter.isEmpty() || filter.getCount() == 1 || filter.getItem() instanceof FilterItem)
            return filter;
        return filter.copyWithCount(1);
    }

    /** A click, a clipboard and the block entity API all store a sample of one ({@link #sampleOf}). */
    @Override
    public boolean setFilter(ItemStack stack) {
        return super.setFilter(sampleOf(stack));
    }

    /**
     * Whether a click with {@code held} would store exactly the filter this slot already holds, so that applying it
     * again would change nothing but cost an update packet, a sound and a controller re-read
     * ({@link #onShortInteract}). The count never matters (the slot keeps a sample of one). A copy of the held stack
     * is first trimmed the way Create's {@code FilterItemStack.of} trims what it stores: a configured filter item loses
     * its enchantments and attribute modifiers <b>in place</b>, which leaves removal entries in the stored copy's
     * patch, and the held stack itself is never touched.
     */
    public boolean holdsFilterOf(ItemStack held) {
        if (held.isEmpty())
            return getFilter().isEmpty();
        ItemStack probe = held.copy();
        FilterItemStack.of(probe);
        return ItemStack.isSameItemSameComponents(probe, getFilter());
    }

    /**
     * The priority for a save or a client packet: omitted at {@value #MIN_PRIORITY}, because a missing key reads back as
     * exactly that and an unprioritised interface should cost nothing in a chunk packet. Not for
     * {@link #writeToClipboard}, which has to be able to say "0".
     */
    private void writePriority(CompoundTag nbt) {
        if (priority() != MIN_PRIORITY)
            nbt.putInt(PRIORITY_TAG, priority());
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        // Missing key → 0: a world, schematic or clipboard from before M16 loads as "no priority" and behaves as before.
        priority = Mth.clamp(nbt.getInt(PRIORITY_TAG), MIN_PRIORITY, MAX_PRIORITY);
    }

    @Override
    public boolean writeToClipboard(HolderLookup.Provider registries, CompoundTag tag, Direction side) {
        boolean result = super.writeToClipboard(registries, tag, side);
        // Create's generic pair means "extracted amount" to every other filter block; carrying the priority in it would
        // swap a funnel's amount and this location's priority in both directions.
        tag.remove(CLIPBOARD_VALUE_TAG);
        tag.remove(CLIPBOARD_ROW_TAG);
        // Unconditional, deliberately not writePriority: a clipboard must be able to say "priority 0". Create's own
        // Filter entry is written unconditionally and pastes back as "no filter", so a copy of a neutral interface
        // clears the target's filter — omitting the number here would let that same paste keep the target's priority
        // (see the class comment). A funnel never writes this key, so a missing one still means "not from an interface".
        tag.putInt(PRIORITY_TAG, priority());
        return result;
    }

    @Override
    public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side,
                                     boolean simulate) {
        if (!mayInteract(player))
            return false;
        boolean result;
        pastingClipboard = true;
        try {
            result = super.readFromClipboard(registries, tag, player, side, simulate);
        } finally {
            pastingClipboard = false;
        }
        if (!tag.contains(PRIORITY_TAG))
            return result;
        if (simulate || getWorld().isClientSide)
            return true;
        setPriority(tag.getInt(PRIORITY_TAG));
        return true;
    }
}
