package dev.wareworks.content.controller;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.warehouse.AisleName;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

/**
 * The one gesture that names an aisle (M25, issue #15, ADR-038): a plain right-click with a <b>renamed item</b> on a
 * warehouse controller (which names the aisle at its dock) or on a warehouse interface (which names <i>its</i> aisle).
 * A plain name tag takes the name off again.
 * <p>
 * <b>Why a held item and not a screen.</b> The text arrives inside an {@link ItemStack} the player is holding, which
 * vanilla's anvil has already filtered and capped at 50 characters — so nothing player-typed ever goes client to
 * server, no menu, no edit box and no validation burden is added, and the gesture is one a player already knows. Only
 * {@link Component#getString()} is read from {@link DataComponents#CUSTOM_NAME}, never the component itself: a command
 * can set that component to a translate, hover or click component, and a name is drawn on several surfaces and written
 * into a save.
 * <p>
 * <b>Why it needs no {@code bypassesInput} override.</b> {@code ValueSettingsInputHandler} walks a block entity's
 * behaviours and {@code continue}s on {@code !testHit(ray.getLocation())}, so only a click that really hits the little
 * value-box sphere is swallowed before the block sees it; a click anywhere else on the face already falls through to
 * {@code useItemOn}. Overriding {@code bypassesInput} would instead cost the controller's aisle-letter box — and, on
 * the interface, its filter and priority slot — for every renamed item in hand, which is a far worse trade than
 * "click the face off the middle". The hint line on the controller's goggles is what pays for the discoverability.
 * <p>
 * <b>Why sneaking passes.</b> {@code ServerPlayerGameMode#useItemOn} drops a sneaking interaction before the block
 * whenever a hand holds something, and Create's own {@code canInteract} excludes a sneaking player too — so sneaking
 * is already how a player places a renamed block against a warehouse block, and this must not change that.
 */
public final class AisleNaming {
    private AisleNaming() {
    }

    /**
     * Whether a held item means "name this aisle": any item carrying {@link DataComponents#CUSTOM_NAME} names, and a
     * plain name tag — the one item whose whole purpose is naming, and whose {@code useOn} does nothing on a block —
     * clears. Everything else is no gesture at all and is passed on untouched.
     * <p>
     * <b>A wrench is never a naming item</b>, however it is named. Create's own wrench turns a block from
     * {@code WrenchItem#useOn}, which runs <i>after</i> {@code useItemOn} and therefore only if this gesture passed the
     * click on — so without this exception a player who named their wrench in an anvil would lose rotation on exactly
     * these two blocks. The test is the {@code c:tools/wrench} tag, the same one Create tests, so every mod's wrench is
     * covered; a foreign wrench would in fact keep working anyway, because {@code WrenchEventHandler} cancels the event
     * for those before the block is asked at all, and an inconsistency between the two would be worse than either.
     */
    public static boolean isGesture(ItemStack stack) {
        if (stack.is(Tags.Items.TOOLS_WRENCH))
            return false;
        return stack.has(DataComponents.CUSTOM_NAME) || stack.is(Items.NAME_TAG);
    }

    /**
     * Applies a naming click, as the {@code useItemOn} of a warehouse controller and of a warehouse interface do.
     *
     * @param target the aisle this block names, or {@code null} when the block belongs to no warehouse aisle, in which
     *               case the player is told so rather than left clicking at nothing
     * @return {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} unless this really was a naming click, so that no other
     *         interaction of either block is stolen; the item is never consumed
     */
    public static ItemInteractionResult clicked(ItemStack stack, Level level, Player player,
            @Nullable WarehouseRegistry.MemberAisle target) {
        if (player.isSecondaryUseActive() || !isGesture(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        if (target == null) {
            // A click that does nothing silently is a click nobody finds (the M20 lesson): say which of the two things
            // is missing is not worth four messages, but saying that this block has no aisle yet always is.
            tell(player, WareworksLang.AISLE_NAME_NO_AISLE);
            return ItemInteractionResult.SUCCESS;
        }
        apply(stack, player, target.controller(), target.aisle());
        return ItemInteractionResult.SUCCESS;
    }

    /** {@link #clicked(ItemStack, Level, Player, WarehouseRegistry.MemberAisle)} for an {@link Optional} target. */
    public static ItemInteractionResult clicked(ItemStack stack, Level level, Player player,
            Optional<WarehouseRegistry.MemberAisle> target) {
        return clicked(stack, level, player, target.orElse(null));
    }

    /**
     * Server: stores or clears the name and tells the player exactly what happened — which aisle, and what it is
     * called now.
     * <p>
     * The branch is decided on the <b>sanitised</b> name and before the call, because {@code set} answers only whether
     * the letter was an aisle letter: a renamed item whose name is nothing but spaces clears, like a name tag, and the
     * player is told that it was cleared rather than that it was named "".
     * <p>
     * <b>Exactly one message per click, always</b> (M25 review fix), for two reasons this method got wrong twice.
     * {@link #tell} writes the action bar, and the client's HUD holds one action-bar message at a time
     * ({@code Gui#setOverlayMessage} assigns its field and resets its timer, it does not queue), so two messages in
     * one tick mean the player reads only the second — which is why a cut name is one sentence that still names its
     * aisle ({@link WareworksLang#AISLE_NAMED_CUT}) instead of "Aisle A is now …" followed by "Shortened to …". And a
     * click that found an aisle is always answered, a clearing click on an aisle that had no name included: the aisle
     * really is unnamed afterwards, so the message is true, and a consumed click that says nothing is a click nobody
     * finds (the M20 lesson this class applies 25 lines above).
     */
    private static void apply(ItemStack stack, Player player, WarehouseControllerBlockEntity controller, char aisle) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        String raw = custom == null ? null : custom.getString();
        String name = AisleName.sanitize(raw);
        if (name.isEmpty()) {
            controller.clearAisleName(aisle);
            tell(player, WareworksLang.AISLE_NAME_CLEARED, letter(aisle));
            return;
        }
        controller.setAisleName(aisle, name);
        // Only the length is echoed. A dropped formatting code or a collapsed double space changes text the player
        // could not see, and reporting a change nobody can perceive is noise.
        tell(player, AisleName.wouldCut(raw) ? WareworksLang.AISLE_NAMED_CUT : WareworksLang.AISLE_NAMED,
                letter(aisle), Component.literal(name));
    }

    private static Component letter(char aisle) {
        return Component.literal(String.valueOf(aisle));
    }

    private static void tell(Player player, String relativeKey, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
    }
}
