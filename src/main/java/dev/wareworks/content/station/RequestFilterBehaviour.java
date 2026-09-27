package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The request filter of a warehouse port ({@code docs/warehouse-system.md} §3.2.1, §7.2): Create's
 * {@link FilteringBehaviour} (item plus amount, drawn by Create's renderer, clipboard support) with the following
 * changes.
 * <ul>
 * <li><b>Refused items never cost the player anything.</b> Items the {@code requestable} predicate refuses (list,
 * attribute and package filters) cannot define a request. Create's clipboard paste takes a filter item of the pasted
 * type from a survival player's inventory <i>before</i> it asks the predicate, and nothing gives it back when the
 * predicate refuses. {@link #readFromClipboard} therefore refuses such a clipboard up front (also in simulation, so no
 * paste is offered), and nothing is taken.</li>
 * <li><b>Requests are always "up to" the amount.</b> The controller clamps a request to the available stock, so M2
 * dropped Create's "Exactly" row ({@code §3.2.1}); any "Exactly" setting from a clipboard or an older save loads as "up
 * to".</li>
 * <li><b>The free row axis carries the port's redstone behaviour</b> (M17, issue #12). Because "Exactly" is gone, the
 * board's rows were unused, and Create's own idiom for a free row axis is the brass diode: row = unit, column = value.
 * Here row = {@link PortRedstone} (on a pulse / while powered / unless powered), column = the requested amount. Row and
 * column are therefore read separately ({@link #setValueSettings}); {@code upTo} stays {@code true} for ever, so the
 * amount path, the M7 merge cap and {@code maxRequestAmount()} are untouched.</li>
 * <li><b>In the {@link PortDirection#ACCEPT} direction the amount is not read</b>, so the board's cells all show
 * {@value PortSettings#NO_VALUE} and a setting changes the row only — flipping the direction back and forth never eats
 * a player's amount. The rows still choose <i>when</i> the port acts, so no row is ever dead.</li>
 * <li><b>The board is always reachable</b> ({@link #acceptsValueSettings()} is a constant, as on the warehouse
 * interface): Create's own implementation would demand a stackable filter item, which would make the redstone behaviour
 * unreachable as soon as a player requested a non-stackable item.</li>
 * <li>The value box shows the amount as a number, never Create's "*" (which would read as "any amount"), and nothing at
 * all in the accepting direction.</li>
 * <li><b>The slot's label follows the direction too</b> ({@link #getLabel()}): "Requested Item" while the port asks for
 * something, "Accepted Item" while it takes items in.</li>
 * <li>A click with the Mechanical Arm item passes through the slot to Create's arm target selection
 * ({@link #bypassesInput}), and the slot shows no hint while the arm item hovers it ({@link #mayInteract}).</li>
 * <li><b>Create's wrench does not interact with the filter slot at all</b> ({@link #mayInteract}), because that is what
 * makes the port's own wrench-only box unambiguous: exactly one value box is eligible while Create's wrench is held
 * ({@link PortRankValueBox}). Create's {@code FilteringRenderer#tick} asks {@code mayInteract}, so the outline and the
 * hover tip disappear then; {@code renderOnBlockEntity} does not, so the filter <b>item</b> stays painted — which is
 * right, the filter is part of the port's configuration. Another mod's wrench is <b>not</b> refused, because Create's own
 * renderer would not draw the port's box for it either (see {@link #mayInteract}).</li>
 * </ul>
 * <b>The clipboard trap.</b> Create's generic {@code Value}/{@code Row} pair means "amount" and "up to / exactly" on
 * every funnel, and {@code Row} now means the redstone behaviour here, so a funnel → port paste would silently set the
 * port's behaviour and a port → funnel paste would make a funnel "exactly". {@link #writeToClipboard} therefore keeps
 * {@code Value} (so a port still sets a funnel's amount), forces {@code Row} to 0, and writes the mode under its own
 * {@value #CLIPBOARD_REDSTONE_TAG} key — <b>unconditionally</b>, because in a clipboard an omitted key would make "on a
 * pulse" unsayable, and Create pastes an empty {@code Filter} back as "no filter", so a paste of a plain port must
 * equally be able to clear the mode (the M16 clipboard lesson, {@code StorageFilterBehaviour}).
 */
public class RequestFilterBehaviour extends FilteringBehaviour {
    /** Key of the filter stack in Create's clipboard data ({@code FilteringBehaviour#writeToClipboard}). */
    public static final String CLIPBOARD_FILTER_TAG = "Filter";
    /** Save, client-packet and clipboard key of the port's redstone behaviour. */
    public static final String REDSTONE_MODE_TAG = "RedstoneMode";
    /** The same key inside clipboard data, where it is written even for the default mode. */
    public static final String CLIPBOARD_REDSTONE_TAG = REDSTONE_MODE_TAG;
    /** Board row of Create's "Up to" option, the only amount mode this filter has. */
    public static final int UP_TO_ROW = 0;
    /** Amount between two milestones of the hold-to-edit board (Create's filter board uses the same). */
    private static final int BOARD_MILESTONE_INTERVAL = 16;
    private static final String UP_TO_PREFIX = "≤";
    /** Create's generic value-settings key that must never carry the redstone behaviour. */
    private static final String CLIPBOARD_ROW_TAG = "Row";

    private final Predicate<ItemStack> requestable;
    private final Supplier<PortDirection> direction;

    private PortRedstone redstone = PortRedstone.DEFAULT;
    private Runnable redstoneCallback = () -> {
    };
    /**
     * A clipboard paste is running, so Create's generic {@code Row} must not be read as a redstone behaviour (a funnel's
     * "exactly" would become "while powered").
     */
    private boolean pastingClipboard;

    /**
     * @param requestable which filter stacks can define a request (also installed as the filter predicate)
     * @param direction   what the port does, read lazily: it decides whether the amount is a setting at all
     */
    public RequestFilterBehaviour(SmartBlockEntity be, ValueBoxTransform slot, Predicate<ItemStack> requestable,
                                  Supplier<PortDirection> direction) {
        super(be, slot);
        this.requestable = requestable;
        this.direction = direction;
        withPredicate(requestable);
        upTo = true;
    }

    /** Called whenever the redstone behaviour changes (server: the controller re-reads the port's policy). */
    public RequestFilterBehaviour withRedstoneCallback(Runnable callback) {
        this.redstoneCallback = callback;
        return this;
    }

    // --- redstone behaviour --------------------------------------------------------------------------------------

    /** When the port acts. */
    public PortRedstone redstoneMode() {
        return redstone;
    }

    /**
     * Sets the redstone behaviour as the hold-to-edit board would.
     *
     * @return whether it changed
     */
    public boolean setRedstoneMode(PortRedstone mode) {
        PortRedstone next = mode == null ? PortRedstone.DEFAULT : mode;
        if (next == redstone)
            return false;
        redstone = next;
        blockEntity.setChanged();
        blockEntity.sendData();
        redstoneCallback.run();
        return true;
    }

    /** Whether the amount column of the board is a setting: only a requesting port asks for an amount. */
    private boolean amountEditable() {
        return direction.get() == PortDirection.REQUEST;
    }

    // --- board ---------------------------------------------------------------------------------------------------

    /** Three rows for the redstone behaviour, the requested amount on the column axis. */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        List<Component> rows = new ArrayList<>(PortRedstone.values().length);
        for (PortRedstone mode : PortRedstone.values())
            rows.add(WareworksLang.translateDirect(mode.langKey()));
        String title = amountEditable() ? WareworksLang.OUTPUT_REQUEST_AMOUNT : WareworksLang.OUTPUT_PORT_BOARD;
        // getMaxStackSize() rather than the clicked face's: this filter is not sided, so the face cannot change the
        // answer, and asking without one keeps the board buildable without a hit result.
        return new ValueSettingsBoard(WareworksLang.translateDirect(title), getMaxStackSize(),
                BOARD_MILESTONE_INTERVAL, rows, new ValueSettingsFormatter(this::formatValue));
    }

    @Override
    public MutableComponent formatValue(ValueSettings value) {
        if (!amountEditable())
            return Component.literal(PortSettings.NO_VALUE);
        return Component.literal(UP_TO_PREFIX + Math.max(1, value.value()));
    }

    /** The cursor opens on the current setting: the row is the mode, the column the amount. */
    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(redstone.ordinal(), count == 0 ? getMaxStackSize() : count);
    }

    /**
     * Row and column are two independent settings here, so this replaces Create's implementation rather than delegating
     * to it: the row is the redstone behaviour, the column the amount (ignored in the accepting direction), and
     * {@code upTo} stays {@code true} whatever arrives.
     */
    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean ctrlDown) {
        // A clipboard paste carries Create's generic Row, which means "exactly" on a funnel and nothing here.
        PortRedstone mode = pastingClipboard ? redstone : PortRedstone.byRow(settings.row());
        int amount = amountEditable() ? Mth.clamp(settings.value(), 1, getMaxStackSize()) : count;
        if (mode == redstone && amount == count && upTo)
            return;
        redstone = mode;
        count = amount;
        upTo = true;
        blockEntity.setChanged();
        blockEntity.sendData();
        redstoneCallback.run();
        playFeedbackSound(this);
    }

    /**
     * Constant, not Create's {@code isCountVisible()}: the board carries the port's redstone behaviour and must stay
     * reachable whatever sits in the filter slot (the same reason {@code StorageFilterBehaviour} has for this).
     */
    @Override
    public boolean acceptsValueSettings() {
        return true;
    }

    /**
     * Constant too, so Create's {@code FilteringRenderer} always adds {@link #getAmountTip()} as the third hover-tip
     * line: the hint that the board exists must not disappear with a non-stackable filter item, and in the accepting
     * direction it is the only hint there is.
     */
    @Override
    public boolean isCountVisible() {
        return true;
    }

    /**
     * Answered per direction, exactly as {@link #getLabel()} is: this is the only in-world hint about the board, and in
     * the <b>requesting</b> direction the board still sets the <i>amount</i> — the setting a requesting port needs most
     * often, and the only one every pre-M17 world was built on. In the accepting direction the amount is not a setting at
     * all, so there the tip names the rows alone.
     */
    @Override
    public MutableComponent getAmountTip() {
        return WareworksLang.translateDirect(amountEditable()
                ? WareworksLang.OUTPUT_REQUEST_AMOUNT_TIP : WareworksLang.OUTPUT_PORT_REDSTONE_TIP);
    }

    /**
     * The slot's own name, answered per direction instead of being set once (M17, issue #12): a requesting port's filter
     * names the item to <b>fetch</b>, an accepting port's the items it <b>takes at all</b> — which is what its goggles
     * say too ("Accepts: ..."). Create's {@code FilteringRenderer} asks for the label while it draws the hover tip, so
     * answering it live costs nothing and no block entity has to remember to update it.
     */
    @Override
    public MutableComponent getLabel() {
        return WareworksLang.translateDirect(switch (direction.get()) {
            case REQUEST -> WareworksLang.OUTPUT_REQUEST_FILTER;
            case ACCEPT -> WareworksLang.OUTPUT_ACCEPT_FILTER;
            // A collecting port's filter names what it fetches out of the machine behind it (M18, issue #13).
            case COLLECT -> WareworksLang.OUTPUT_COLLECT_FILTER;
        });
    }

    /** The plain number, never Create's "*"; nothing at all where the amount is not a setting. */
    @Override
    public MutableComponent getCountLabelForValueBox() {
        return Component.literal(amountEditable() ? String.valueOf(count) : "");
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    /**
     * A click with the Mechanical Arm item passes through the filter slot (M12, {@code docs/warehouse-system.md}
     * §3.2.2). The slot sits in the centre of the top, back and side faces, exactly where a player clicks to select the
     * output as an arm target. Create's {@code ValueSettingsInputHandler} would take that click first and cancel it, so
     * Create's arm selection handler never saw it, and Create's own {@code FilteringBehaviour#canShortInteract} then
     * refuses the arm item as a filter: the click did nothing at all. Bypassing lets the selection through; with any
     * other item or an empty hand the slot works as before.
     */
    @Override
    public boolean bypassesInput(ItemStack mainhandItem) {
        return AllBlocks.MECHANICAL_ARM.isIn(mainhandItem) || super.bypassesInput(mainhandItem);
    }

    /**
     * A player holding the Mechanical Arm item does not interact with the slot at all, so Create's
     * {@code FilteringRenderer} draws no value box and no "Click with item to set" hint while the arm item hovers the
     * slot (M12, {@code docs/warehouse-system.md} §3.2.2). Bypassing alone left that hint up although the click selects
     * the output as an arm target. {@code ValueSettingsInputHandler} skips the slot either way, and the clipboard path,
     * which also asks this, is taken with a clipboard in hand, never with the arm.
     * <p>
     * A player holding <b>Create's wrench</b> is refused for a different reason (M17): the wrench belongs to the port's
     * own settings box, which sits on the same faces, and refusing here is what makes exactly one of the two eligible at
     * a time ({@link PortRankValueBox}). Create's {@code canShortInteract} already refused a wrench as a filter item, so
     * nothing a wrench could do to this slot is taken away — before M17 such a click was swallowed and did nothing.
     * <p>
     * <b>Create's own item, not the {@code c:tools/wrench} tag</b>, because Create splits the two predicates: its
     * {@code ScrollValueRenderer} draws a {@code needsWrench} box only for {@code AllItems.WRENCH}, while its
     * {@code ValueSettingsInputHandler} accepts the whole tag. Refusing the tag here therefore left a player holding
     * another mod's wrench with <b>no</b> value box drawn at all — neither this slot's outline, label and hover tip nor
     * the port's box — while the port box still took the click. Matching the renderer keeps this slot visible and usable
     * for every wrench but Create's, exactly as before M17, and leaves Create's own wrench to the port box alone.
     */
    @Override
    public boolean mayInteract(Player player) {
        ItemStack held = player.getMainHandItem();
        return !AllBlocks.MECHANICAL_ARM.isIn(held) && !AllItems.WRENCH.isIn(held) && super.mayInteract(player);
    }

    // --- persistence, sync and clipboard -------------------------------------------------------------------------

    /** The mode is written only while it is not the default, so a pre-M17 output's tag stays byte for byte its own. */
    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(nbt, registries, clientPacket);
        if (redstone != PortRedstone.DEFAULT)
            nbt.putString(REDSTONE_MODE_TAG, redstone.name());
    }

    /** Any row (e.g. "Exactly" from an old save) is stored as "up to"; a missing mode reads as "on a pulse". */
    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        upTo = true;
        redstone = nbt.contains(REDSTONE_MODE_TAG, Tag.TAG_STRING)
                ? PortRedstone.byName(nbt.getString(REDSTONE_MODE_TAG)).orElse(PortRedstone.DEFAULT)
                : PortRedstone.DEFAULT;
    }

    @Override
    public boolean writeToClipboard(HolderLookup.Provider registries, CompoundTag tag, Direction side) {
        boolean result = super.writeToClipboard(registries, tag, side);
        // "Row" means "exactly" to every funnel and the redstone behaviour here: it must carry neither across.
        tag.putInt(CLIPBOARD_ROW_TAG, UP_TO_ROW);
        tag.putString(CLIPBOARD_REDSTONE_TAG, redstone.name());
        return result;
    }

    /**
     * Refuses a clipboard whose filter cannot define a request before Create's paste logic takes a filter item from the
     * player; everything else is pasted as usual, with the redstone behaviour taken only from
     * {@value #CLIPBOARD_REDSTONE_TAG} and never from Create's generic {@code Row}.
     */
    @Override
    public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side,
                                     boolean simulate) {
        if (tag.contains(CLIPBOARD_FILTER_TAG, Tag.TAG_COMPOUND)
                && !requestable.test(ItemStack.parseOptional(registries, tag.getCompound(CLIPBOARD_FILTER_TAG))))
            return false;
        boolean result;
        pastingClipboard = true;
        try {
            result = super.readFromClipboard(registries, tag, player, side, simulate);
        } finally {
            pastingClipboard = false;
        }
        if (!tag.contains(CLIPBOARD_REDSTONE_TAG, Tag.TAG_STRING))
            return result;
        if (simulate || getWorld().isClientSide)
            return true;
        setRedstoneMode(PortRedstone.byName(tag.getString(CLIPBOARD_REDSTONE_TAG)).orElse(PortRedstone.DEFAULT));
        return true;
    }
}
