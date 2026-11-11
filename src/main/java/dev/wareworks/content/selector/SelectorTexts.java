package dev.wareworks.content.selector;

import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.AllBlocks;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.SelectorWait;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The sentences the Recipe Selector's waits, checks and machines are shown with (ADR-085, design §12): one place, so
 * the terminal, the station's screen and the goggles of station and selector say the same thing the same way.
 * <p>
 * Pure text building from lang keys and item names; it reads no world.
 */
public final class SelectorTexts {
    /** Prefix of the order wait keys, {@code gui.terminal.order.selector.<wait>}. */
    public static final String WAIT_PREFIX = "gui.terminal.order.selector.";

    private SelectorTexts() {
    }

    /**
     * The relative lang key of {@code wait}; {@code hold_unexpected} for a hold that names an item (what came back
     * through the return input). Null for {@link SelectorWait#NONE}.
     */
    public static String waitKey(SelectorWait wait, boolean withItem) {
        if (wait == null || wait == SelectorWait.NONE)
            return null;
        if (wait == SelectorWait.HOLD && withItem)
            return WAIT_PREFIX + "hold_unexpected";
        return WAIT_PREFIX + wait.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Why an order waits, as its row and its tooltip say it: "Waits for its turn: Deepslate Tile Slab first". The item
     * fills the texts that name one; without it they name nothing rather than a raw placeholder.
     */
    public static Component waitText(SelectorWait wait, Optional<ItemKey> item) {
        String key = waitKey(wait, item.isPresent());
        if (key == null)
            return Component.empty();
        Component name = item.map(found -> found.toStack().getHoverName()).orElse(Component.empty());
        return WareworksLang.translateDirect(key, name);
    }

    /** The machine a selector of {@code kind} serves, by its block's own name: saw, mixer, press or basin. */
    public static Component machineName(SelectorMachine.Kind kind) {
        return switch (kind) {
            case SAW -> AllBlocks.MECHANICAL_SAW.get().getName();
            case MIXER_BASIN -> AllBlocks.MECHANICAL_MIXER.get().getName();
            case PRESS_BASIN -> AllBlocks.MECHANICAL_PRESS.get().getName();
            case BASIN_ALONE -> AllBlocks.BASIN.get().getName();
        };
    }

    /** Why the selector check refused a pattern: "Mechanical Saw: no recipe makes this from these ingredients". */
    public static Component checkText(SelectorRecipeCheck.Verdict verdict, Optional<SelectorMachine.Kind> kind) {
        Component machine = kind.map(SelectorTexts::machineName)
                .orElseGet(() -> WareworksLang.translateDirect(WareworksLang.GOGGLES_RECIPE_SELECTOR));
        return WareworksLang.translateDirect(verdict.langKey(), machine);
    }

    /** A block position as the screens write one: {@code "12, 64, -3"}. */
    public static Component position(BlockPos pos) {
        return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }
}
