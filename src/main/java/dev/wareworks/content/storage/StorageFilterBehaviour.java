package dev.wareworks.content.storage;

import java.util.List;
import java.util.function.IntConsumer;

import com.simibubi.create.AllBlocks;
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
 * <li><b>short click</b> with an item sets or clears the filter, unchanged;</li>
 * <li><b>hold</b> the click for five ticks and Create's {@code ValueSettingsScreen} opens with one row, "Priority",
 * {@value #MIN_PRIORITY}..{@value #MAX_PRIORITY}.</li>
 * </ul>
 * <b>The one behaviour change:</b> {@link #acceptsValueSettings()} is now {@code true}, so a filter click no longer takes
 * {@code ValueSettingsInputHandler}'s immediate server-side {@code onShortInteract} branch but goes through Create's
 * client warmup and fires on release. It is a constant and deliberately <b>not</b> Create's {@code isCountVisible()},
 * whose {@code getMaxStackSize() > 1} term would make the board unreachable as soon as a player used a non-stackable
 * item as a plain filter. The M8 fake-player protection is untouched: {@code ValueSettingsInputHandler} asks
 * {@link #mayInteract} <i>before</i> its fake-player branch.
 * <p>
 * <b>Negative priorities were rejected.</b> {@code ValueSettingsScreen#getClosestCoordinate} scans columns from 0, so a
 * negative value cannot be picked on a board at all, and an offset encoding would make the saved number differ from the
 * shown one. "Fill last" is expressed by raising the others, and a 0-based range can be widened later without a
 * migration. The value is clamped <b>on read</b>, like the crane's mast height, so a future lower maximum never rewrites
 * a player's number.
 * <p>
 * <b>An empty filter writes nothing into client packets.</b> Create's {@code write} emits {@code Filter},
 * {@code FilterAmount} and {@code UpTo} unconditionally, which costs roughly 215 bytes of NBT size accounting in
 * <b>every</b> interface's update tag — and that tag is part of every chunk packet, read by clients with a 2 MB quota
 * (§3.1.1). Almost every interface of a warehouse carries no filter at all, so for those Create's whole block is skipped;
 * Create's {@code read} builds an empty {@code FilterItemStack} from a missing tag, so an absent entry reads back
 * exactly as "accepts everything". A priority is one int under a 13-character key (~66 accounting bytes) and is written
 * <b>only while it is not 0</b>, so an unprioritised, unfiltered interface still syncs nothing at all, and a prioritised
 * but unfiltered one pays for the number alone. The client needs the number because {@code addToGoggleTooltip} and the
 * block renderer both run client-side.
 * <p>
 * <b>Saves and schematics.</b> Saves are untouched ({@code clientPacket == false}), so filter and priority persist;
 * {@code isSafeNBT()} is {@code true} and {@code BlockEntityBehaviour#writeSafe} defaults to {@code write(tag, .., false)},
 * so both travel in a schematic, and a number costs no {@code getRequiredItems()}. A pre-M16 world has no
 * {@value #PRIORITY_TAG} key and reads back 0, so nothing migrates and loading can never throw.
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
     * Constant, not Create's {@code isCountVisible()}: the board must stay reachable whatever sits in the filter slot
     * (see the class comment).
     */
    @Override
    public boolean acceptsValueSettings() {
        return true;
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

    /** "Hold to set the priority", the third line of the hover tip Create shows for this slot. */
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

    // --- persistence, sync and clipboard -------------------------------------------------------------------------

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        // A missing tag reads back as an empty filter and priority 0, so an unfiltered, unprioritised interface needs
        // to sync nothing at all — and a prioritised one still skips Create's ~215-byte filter block.
        if (clientPacket && getFilter().isEmpty()) {
            writePriority(nbt);
            return;
        }
        super.write(nbt, registries, clientPacket);
        writePriority(nbt);
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
