package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The <b>direction and rank</b> of a warehouse port ({@code docs/warehouse-system.md} §3.2, M17, issue #12): Create's
 * {@link ScrollValueBehaviour} on a wrench-only value box, carrying the one signed number that says both what the port
 * does and where it ranks against storage ({@link PortSettings}).
 * <ul>
 * <li><b>Wrench only</b> ({@link #onlyVisibleWithWrench()}). Create's {@code ValueSettingsInputHandler} skips the box
 * unless the main hand holds a {@code TOOLS_WRENCH}, and {@code ScrollValueRenderer} does not draw it unless that item is
 * {@code AllItems.WRENCH}, so the block looks and behaves exactly as before for every other hand. Precedent: Create's
 * linear chassis range box, which Create gates the same two ways. Together with
 * {@link RequestFilterBehaviour#mayInteract} refusing <b>Create's</b> wrench, exactly one box is eligible while that
 * wrench is held, which is why both may sit on the same faces ({@link PortRankValueBox}).</li>
 * <li><b>The row carries the sign.</b> The board has three rows — request, overflow, diversion — and a magnitude column
 * {@code 0..}{@value PortSettings#MAX_STRENGTH}; {@link PortSettings#rankOf} composes the signed rank from the two,
 * because a board column can never be negative (ADR-028).</li>
 * <li><b>Its own NBT key and its own clipboard key.</b> Create's generic {@code "ScrollValue"} is replaced by
 * {@value #RANK_TAG}, written only while the rank is not 0, so a port nobody configured — and every warehouse output
 * from before M17 — adds nothing to a save or a chunk packet and needs no migration. The clipboard key
 * {@value #CLIPBOARD_KEY} is the port's own, so only ports paste onto ports and a copied port carries its whole policy
 * onto a rack wall of them.</li>
 * <li><b>No fake player</b> ({@link #mayInteract}): exporting items is irreversible, and Create's input handler skips
 * the 4 px hit test entirely for a {@code FakePlayer}, so a deployer holding a wrench anywhere near the block would
 * otherwise flip the port's direction. Refused, exactly as {@code StorageFilterBehaviour} refuses one.</li>
 * </ul>
 * The behaviour keeps Create's {@code ScrollValueBehaviour.TYPE} and its {@code netId()} of 0. That is what tells it
 * apart from the filter slot on the same block entity: {@code FilteringBehaviour#netId()} is 1, and
 * {@code ValueSettingsPacket} picks the behaviour whose id the client sent.
 */
public class PortRankBehaviour extends ScrollValueBehaviour {
    /** Save, client-packet and clipboard key of the signed rank. */
    public static final String RANK_TAG = "PortRank";
    /** Clipboard key: a port pastes only onto another port. */
    public static final String CLIPBOARD_KEY = "WarehousePort";

    public PortRankBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(WareworksLang.translateDirect(WareworksLang.OUTPUT_PORT_RANK), be, slot);
        // Up to the collect sentinel, not to MAX_RANK (M18, issue #13): Create's setValue clamps to this range, so a
        // narrower one would silently turn a collecting port into the strongest diversion. No input path reaches setValue
        // directly — Create's ValueSettingsPacket only ever calls setValueSettings, which composes the rank from the
        // board's row and column, and setRank clamps with PortSettings.clampRank before handing the number over — so the
        // widened range adds no way to reach the sentinel other than meaning it.
        between(PortSettings.MIN_RANK, PortSettings.COLLECT_RANK);
        requiresWrench();
        // The box text is the signed rank, the same number the block renderer paints on the back plate.
        withFormatter(PortSettings::formatRank);
    }

    /**
     * The signed rank, clamped on read so a number saved under a wider range is never silently rewritten. The clamp keeps
     * the collect sentinel and clamps everything else into the accept band ({@link PortSettings#clampRank}).
     */
    public int rank() {
        return PortSettings.clampRank(value);
    }

    /** What the port does. */
    public PortDirection direction() {
        return PortSettings.directionOf(rank());
    }

    /**
     * Sets the signed rank as the hold-to-edit board would: out-of-range values are clamped into the accept band, and only
     * the sentinel itself makes a collecting port ({@link PortSettings#clampRank}).
     * <p>
     * The clamp is applied <b>here</b> and not left to Create's {@code setValue}, whose range ends at the sentinel since
     * M18: a number above the accept band would otherwise land on the sentinel and turn the port around, while the same
     * number read from a save or a clipboard through {@link #read} becomes the strongest diversion. One stored number
     * must not mean two directions depending on which path it took (M18 review); {@code between(...)} now only keeps
     * {@code setValue} from rewriting a legitimate sentinel, which is all it was widened for.
     *
     * @return whether it changed
     */
    public boolean setRank(int rank) {
        int before = rank();
        setValue(PortSettings.clampRank(rank));
        return rank() != before;
    }

    // --- board ---------------------------------------------------------------------------------------------------

    /** Three rows for the sign, a magnitude column with the storage priority's range and milestones. */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        List<Component> rows = new ArrayList<>(PortSettings.ROWS);
        for (int row = 0; row < PortSettings.ROWS; row++)
            rows.add(WareworksLang.translateDirect(PortSettings.rowLangKey(row)));
        return new ValueSettingsBoard(WareworksLang.translateDirect(WareworksLang.OUTPUT_PORT_RANK),
                PortSettings.MAX_STRENGTH, PortSettings.STRENGTH_MILESTONE_INTERVAL, rows,
                new ValueSettingsFormatter(PortRankBehaviour::formatSettings));
    }

    @Override
    public ValueSettings getValueSettings() {
        int rank = rank();
        return new ValueSettings(PortSettings.rowOf(rank), PortSettings.strengthOf(rank));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlDown) {
        if (!setRank(PortSettings.rankOf(valueSetting.row(), valueSetting.value())))
            return;
        playFeedbackSound(this);
    }

    /**
     * A magnitude means nothing in the request row and none in the <b>collect</b> row either (M18, issue #13), so both
     * show a dash instead of a number: ordering against other work is where the arrival stage sits in dispatch, and
     * ordering among several collecting ports is a round robin, because a priority there would starve the weaker ports.
     */
    private static MutableComponent formatSettings(ValueSettings settings) {
        if (settings.row() == PortSettings.REQUEST_ROW || settings.row() == PortSettings.COLLECT_ROW)
            return Component.literal(PortSettings.NO_VALUE);
        return Component.literal(String.valueOf(Mth.clamp(settings.value(), 0, PortSettings.MAX_STRENGTH)));
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    /** Only a real player may turn a warehouse into one that hands its surplus out (see the class comment). */
    @Override
    public boolean mayInteract(Player player) {
        return !(player instanceof FakePlayer) && super.mayInteract(player);
    }

    // --- persistence, sync and clipboard -------------------------------------------------------------------------

    /**
     * Deliberately not {@code super.write}: Create writes its generic {@code "ScrollValue"} key unconditionally, while a
     * port at rank 0 must add nothing at all — that is what makes a pre-M17 world load unchanged and keeps a rack wall
     * of plain outputs out of the chunk packets. {@code BlockEntityBehaviour#write} itself is empty, so nothing is lost.
     * <p>
     * The rank goes into client packets as well (the block renderer paints it) and, through
     * {@code BlockEntityBehaviour#writeSafe} and {@code isSafeNBT() == true}, into schematics, where a number costs no
     * {@code getRequiredItems()}.
     */
    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        if (rank() != PortSettings.REQUEST_RANK)
            nbt.putInt(RANK_TAG, rank());
    }

    /** A missing key reads as rank 0, i.e. a requesting port: a world, schematic or clipboard from before M17. */
    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        value = PortSettings.clampRank(nbt.getInt(RANK_TAG));
    }

    @Override
    public String getClipboardKey() {
        return CLIPBOARD_KEY;
    }

    /**
     * Unconditional, unlike {@link #write}: in a clipboard an omitted key would make "this is a requesting port"
     * unsayable, so a paste from a plain output could never undo an accepting one (the M16 clipboard lesson,
     * {@code StorageFilterBehaviour}).
     */
    @Override
    public boolean writeToClipboard(HolderLookup.Provider registries, CompoundTag tag, Direction side) {
        tag.putInt(RANK_TAG, rank());
        return true;
    }

    @Override
    public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player,
                                     Direction side, boolean simulate) {
        if (!mayInteract(player) || !tag.contains(RANK_TAG))
            return false;
        if (simulate || getWorld().isClientSide)
            return true;
        setRank(tag.getInt(RANK_TAG));
        return true;
    }
}
