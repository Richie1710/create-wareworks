package dev.wareworks.content.station;

import java.util.function.Predicate;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import dev.wareworks.util.WareworksLang;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The request filter of a warehouse output ({@code docs/warehouse-system.md} §3.2.1, §7.2): Create's
 * {@link FilteringBehaviour} (item plus amount, drawn by Create's renderer, clipboard support) with four changes.
 * <ul>
 * <li><b>Refused items never cost the player anything.</b> Items the {@code requestable} predicate refuses (list,
 * attribute and package filters) cannot define a request. Create's clipboard paste takes a filter item of the pasted
 * type from a survival player's inventory <i>before</i> it asks the predicate, and nothing gives it back when the
 * predicate refuses. {@link #readFromClipboard} therefore refuses such a clipboard up front (also in simulation, so no
 * paste is offered), and nothing is taken.</li>
 * <li><b>Requests are always "up to" the amount.</b> The controller clamps a request to the available stock, so the
 * hold-to-edit board offers only Create's "Up to" row under the title "Requested Amount" ({@link #createBoard}); any
 * "Exactly" setting from a clipboard or an older save loads as "up to".</li>
 * <li>The value box shows the amount as a number, never Create's "*" (which would read as "any amount").</li>
 * <li>A click with the Mechanical Arm item passes through the slot to Create's arm target selection
 * ({@link #bypassesInput}), and the slot shows no hint while the arm item hovers it ({@link #mayInteract}).</li>
 * </ul>
 */
public class RequestFilterBehaviour extends FilteringBehaviour {
    /** Key of the filter stack in Create's clipboard data ({@code FilteringBehaviour#writeToClipboard}). */
    public static final String CLIPBOARD_FILTER_TAG = "Filter";
    /** Board row of Create's "Up to" option. */
    public static final int UP_TO_ROW = 0;
    /** Amount between two milestones of the hold-to-edit board (Create's filter board uses the same). */
    private static final int BOARD_MILESTONE_INTERVAL = 16;
    /** Create's lang prefix and option key of the "Up to" row. */
    private static final String CREATE_FILTER_OPTIONS = "logistics.filter";
    private static final String CREATE_UP_TO_OPTION = "up_to";
    private static final String UP_TO_PREFIX = "≤";

    private final Predicate<ItemStack> requestable;

    /**
     * @param requestable which filter stacks can define a request (also installed as the filter predicate)
     */
    public RequestFilterBehaviour(SmartBlockEntity be, ValueBoxTransform slot, Predicate<ItemStack> requestable) {
        super(be, slot);
        this.requestable = requestable;
        withPredicate(requestable);
        upTo = true;
    }

    /**
     * Refuses a clipboard whose filter cannot define a request before Create's paste logic takes a filter item from the
     * player; everything else is pasted as usual.
     */
    @Override
    public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side,
                                     boolean simulate) {
        if (tag.contains(CLIPBOARD_FILTER_TAG, Tag.TAG_COMPOUND)
                && !requestable.test(ItemStack.parseOptional(registries, tag.getCompound(CLIPBOARD_FILTER_TAG))))
            return false;
        return super.readFromClipboard(registries, tag, player, side, simulate);
    }

    /** One "Up to" row titled "Requested Amount". */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(WareworksLang.translateDirect(WareworksLang.OUTPUT_REQUEST_AMOUNT),
                getMaxStackSize(), BOARD_MILESTONE_INTERVAL,
                CreateLang.translatedOptions(CREATE_FILTER_OPTIONS, CREATE_UP_TO_OPTION),
                new ValueSettingsFormatter(this::formatValue));
    }

    @Override
    public MutableComponent formatValue(ValueSettings value) {
        return Component.literal(UP_TO_PREFIX + Math.max(1, value.value()));
    }

    /** Any row (e.g. "Exactly" from a clipboard) is stored as "up to". */
    @Override
    public void setValueSettings(Player player, ValueSettings settings, boolean ctrlDown) {
        super.setValueSettings(player, new ValueSettings(UP_TO_ROW, settings.value()), ctrlDown);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        upTo = true;
    }

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
     */
    @Override
    public boolean mayInteract(Player player) {
        return !AllBlocks.MECHANICAL_ARM.isIn(player.getMainHandItem()) && super.mayInteract(player);
    }

    @Override
    public MutableComponent getCountLabelForValueBox() {
        return Component.literal(isCountVisible() ? String.valueOf(count) : "");
    }
}
