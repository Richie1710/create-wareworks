package dev.wareworks.content.controller;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The controller's "Aisle" value box: a scroll value {@value #FIRST_INDEX}..{@value #LAST_INDEX} shown as the letters
 * {@code A..Z}, in the value box and on the hold-to-edit board ({@code docs/warehouse-system.md} §2).
 * <p>
 * The clipboard key is its own ({@value #CLIPBOARD_KEY}), so only aisle letters are pasted onto aisle letters.
 */
public class AisleLetterBehaviour extends ScrollValueBehaviour {
    public static final int FIRST_INDEX = 0;
    public static final int LAST_INDEX = StorageAddress.AISLE_COUNT - 1;
    public static final String CLIPBOARD_KEY = "AisleLetter";
    /** Letters between two milestones of the hold-to-edit board (A, F, K, P, U, Z). */
    private static final int BOARD_MILESTONE_INTERVAL = 5;

    public AisleLetterBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
        super(label, be, slot);
        between(FIRST_INDEX, LAST_INDEX);
        withFormatter(AisleLetterBehaviour::format);
    }

    /** The letter of a scroll value, clamped to {@code A..Z}. */
    public static char letterOf(int index) {
        return StorageAddress.aisleLetter(Mth.clamp(index, FIRST_INDEX, LAST_INDEX));
    }

    /** The value box text of a scroll value. */
    public static String format(int index) {
        return String.valueOf(letterOf(index));
    }

    /** The current letter. */
    public char letter() {
        return letterOf(getValue());
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(label, LAST_INDEX, BOARD_MILESTONE_INTERVAL,
                List.of(WareworksLang.translateDirect(WareworksLang.CONTROLLER_AISLE_LETTER_ROW)),
                new ValueSettingsFormatter(settings -> Component.literal(format(settings.value()))));
    }

    @Override
    public String getClipboardKey() {
        return CLIPBOARD_KEY;
    }
}
