package dev.wareworks.content.requester;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.core.requester.RequesterLimits;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The <b>grace</b> of a Schematicannon Requester (issue #45, ADR-080 §8.4): how many seconds it waits, after the items
 * of one of its orders left the terminal's buffer, before it may order that item again. Create's
 * {@link ScrollValueBehaviour} on a small box on the block's back face ({@link RequesterBackFace.Grace}), seconds only,
 * {@value RequesterLimits#MIN_GRACE_SECONDS}..{@value RequesterLimits#MAX_GRACE_SECONDS}, shipped at
 * {@value RequesterLimits#DEFAULT_GRACE_SECONDS}.
 * <p>
 * <b>Why on the block</b> and not in the configuration: what decides the right value is the length of the belt between
 * the terminal and the cannon's chest, which is different at every cannon. 0 is for a funnel that inserts straight into
 * the chest. Once the items reach the chest the cannon grabs them within a tick and asks for something else, so the
 * grace only has to cover the belt.
 * <ul>
 * <li><b>Its own NBT key</b>, {@value #GRACE_TAG}, written only while the value is not the default — the port rank's
 * rule ({@code PortRankBehaviour#write}). A requester nobody configured adds nothing to a save, a chunk packet or a
 * schematic, which is what {@code SchematicSafetyGameTests#blocksAtTheirDefaultsHandAPrintNothing} asks of every
 * block. A missing key reads as the default, so a print without it leaves the block at the default as well.</li>
 * <li><b>Its own clipboard key</b>, {@value #CLIPBOARD_KEY}, so a value copied from one requester pastes onto another
 * and onto nothing else.</li>
 * <li><b>The remote passes</b> ({@link #bypassesInput}): a click with a Warehouse Remote is the block's own insert
 * click wherever it lands, the box included. A sneaking click never reaches the box (Create's input handler skips
 * sneaking players), so taking the remote out is never caught here either.</li>
 * <li><b>No fake player</b> ({@link #mayInteract}): Create's input handler skips the hit test for one, so a Deployer
 * anywhere near the block would otherwise set it.</li>
 * </ul>
 * The value travels through Create's own {@code ValueSettingsPacket}; there is no Wareworks payload.
 */
public class GraceBehaviour extends ScrollValueBehaviour {
    /** Save, client-packet, schematic and clipboard key of the seconds; absent at the default. */
    public static final String GRACE_TAG = "Grace";
    /** Clipboard key: a requester's grace pastes only onto another requester. */
    public static final String CLIPBOARD_KEY = "SchematicannonRequester";
    /** Milestones on the board every ten seconds. */
    private static final int MILESTONE_INTERVAL = 10;

    public GraceBehaviour(SmartBlockEntity be) {
        super(WareworksLang.translateDirect(WareworksLang.REQUESTER_GRACE), be, new RequesterBackFace.Grace());
        between(RequesterLimits.MIN_GRACE_SECONDS, RequesterLimits.MAX_GRACE_SECONDS);
        value = RequesterLimits.DEFAULT_GRACE_SECONDS;
        withFormatter(seconds -> WareworksLang.translateDirect(WareworksLang.REQUESTER_GRACE_SECONDS, seconds)
                .getString());
    }

    /** The grace in seconds, clamped on read so a number saved under a wider range is never rewritten. */
    public int seconds() {
        return RequesterLimits.clampGraceSeconds(value);
    }

    /** Sets the grace in seconds, clamped; saved and synced by Create's {@code setValue}. */
    public void setSeconds(int seconds) {
        setValue(RequesterLimits.clampGraceSeconds(seconds));
    }

    // --- board ---------------------------------------------------------------------------------------------------

    /** One row, in Create's own unit word, from 0 to the longest grace. */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(label, RequesterLimits.MAX_GRACE_SECONDS, MILESTONE_INTERVAL,
                List.of(CreateLang.translateDirect("generic.unit.seconds")),
                new ValueSettingsFormatter(settings -> format(settings.value())));
    }

    private static MutableComponent format(int seconds) {
        return WareworksLang.translateDirect(WareworksLang.REQUESTER_GRACE_SECONDS,
                RequesterLimits.clampGraceSeconds(seconds));
    }

    // --- interaction ---------------------------------------------------------------------------------------------

    /** A click with a Warehouse Remote is the block's insert click, wherever it lands (see the class comment). */
    @Override
    public boolean bypassesInput(ItemStack mainhandItem) {
        return mainhandItem.getItem() instanceof WarehouseRemoteItem;
    }

    /** Only a real player sets the grace (see the class comment). */
    @Override
    public boolean mayInteract(Player player) {
        return !(player instanceof FakePlayer) && super.mayInteract(player);
    }

    // --- persistence, sync and clipboard -------------------------------------------------------------------------

    /**
     * Deliberately not {@code super.write}: Create writes its generic {@code "ScrollValue"} key unconditionally, while a
     * requester at its default must add nothing at all (see the class comment). {@code BlockEntityBehaviour#write} is
     * empty, so nothing is lost. The same key goes into client packets and, through {@code isSafeNBT() == true}, into
     * schematics: a number of seconds is configuration, never contents.
     */
    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        if (seconds() != RequesterLimits.DEFAULT_GRACE_SECONDS)
            nbt.putInt(GRACE_TAG, seconds());
    }

    /** A missing key reads as the default: nothing was configured, or a save from before the value was set. */
    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        value = nbt.contains(GRACE_TAG, Tag.TAG_INT) ? RequesterLimits.clampGraceSeconds(nbt.getInt(GRACE_TAG))
                : RequesterLimits.DEFAULT_GRACE_SECONDS;
    }

    @Override
    public String getClipboardKey() {
        return CLIPBOARD_KEY;
    }

    /** Unconditional, unlike {@link #write}: in a clipboard an omitted key could not say "back to the default". */
    @Override
    public boolean writeToClipboard(HolderLookup.Provider registries, CompoundTag tag, Direction side) {
        tag.putInt(GRACE_TAG, seconds());
        return true;
    }

    @Override
    public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side,
            boolean simulate) {
        if (!mayInteract(player) || !tag.contains(GRACE_TAG, Tag.TAG_INT))
            return false;
        if (simulate || getWorld().isClientSide)
            return true;
        setSeconds(tag.getInt(GRACE_TAG));
        return true;
    }
}
