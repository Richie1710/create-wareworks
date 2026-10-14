package dev.wareworks.content.station;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;

import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

/**
 * The one rule for the three stations that open a <b>screen</b> on a right-click — the warehouse terminal, the
 * production station and the stock keeper ({@code docs/warehouse-system.md} §3.4.2, §3.5, §3.6; issue #26, ADR-060):
 * <b>a right-click opens the screen whatever is in the hand, and a sneaking one uses the item</b>, which is how a
 * vanilla chest behaves.
 * <p>
 * The split is vanilla's own, the one {@code ChestBlock} and {@code LecternBlock} use: {@code useItemOn} only decides
 * whether the held item keeps its own click, and the screen opens from {@code useWithoutItem}. That is why it is
 * right on both sides without a single line about the client:
 * <ul>
 * <li>{@code useWithoutItem} answers {@code sidedSuccess} — {@code SUCCESS} on the client, {@code CONSUME} on the
 * server — so the client's {@code MultiPlayerGameMode#performUseItemOn} returns before {@code stack.useOn}, and
 * {@code Minecraft#startUseItem} before {@code useItem}: no ghost block, no placement sound, no bite of food and no
 * second packet for the server to act on. Like a chest, the server does not echo the arm swing back.</li>
 * <li>vanilla calls {@code useWithoutItem} for the <b>main hand only</b>, so a click that reaches the offhand is that
 * item's own, exactly as at a chest;</li>
 * <li>a <b>sneaking</b> click with something in either hand never reaches the block at all (the sneak guard in
 * {@code ServerPlayerGameMode#useItemOn} and its client twin), so sneak + right-click places the block or uses the
 * item without any code here. The blocks themselves pass a sneaking click that does arrive — both hands empty, or an
 * item that bypasses the sneak such as Create's Extendo Grip — except for the production station's resume gesture
 * ({@link ProductionStationHooks}).</li>
 * </ul>
 * The answer depends only on the held stack, on whether the player is a fake one and on whether the player may build
 * (synced with the abilities) — never on block entity state a client does not have — so the client predicts exactly
 * what the server does.
 */
final class StationScreenClick {
    private StationScreenClick() {
    }

    /**
     * The {@code useItemOn} of all three blocks: {@code SKIP_DEFAULT_BLOCK_INTERACTION} for an item that keeps its own
     * click ({@link #keepsItsOwnClick}), which vanilla then hands straight to {@code stack.useOn}, and
     * {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} for everything else, which leads to the block's
     * {@code useWithoutItem} on the main hand and so to the screen.
     * <p>
     * It must be {@code SKIP} and not {@code PASS_TO_DEFAULT} for those items: the latter would run
     * {@code useWithoutItem} first, and the screen would swallow the wrench, the arm and the clipboard.
     */
    static ItemInteractionResult useItemOn(ItemStack stack, Player player) {
        return keepsItsOwnClick(stack, player) ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Whether a right-click with {@code stack} is the item's own and must not open the screen. An empty hand never is.
     * <p>
     * A <b>fake player</b> holding something — a Deployer — keeps its item's click, so a Deployer still places a block
     * or uses its item against a station as before. NeoForge's {@code FakePlayer#openMenu} opens nothing, so the screen
     * would only swallow the click and the Deployer would silently stop working; an empty-handed one is answered as
     * before (consumed, nothing opens). {@code RackBayGestures} and Create's own toolbox treat fake players the same
     * way.
     */
    static boolean keepsItsOwnClick(ItemStack stack, Player player) {
        if (stack.isEmpty())
            return false;
        return (player != null && player.isFakePlayer()) || actsOnTheBlock(stack, player);
    }

    /**
     * Items whose right-click on a station is about the station, or that issue #26 lists as having to behave exactly as
     * before. Each of them runs <b>after</b> the block in vanilla's dispatch, so the screen would otherwise win:
     * <ul>
     * <li>a <b>wrench</b> — the {@code c:tools/wrench} tag, which Create's own tag file puts its wrench into, and
     * Create's wrench by name in case a pack takes it out of the tag — turns the block in {@code WrenchItem#useOn}: the
     * terminal's screen face, the other two stations' facing, and Shift picks the block up. A foreign wrench is turned
     * by Create's {@code WrenchEventHandler} before the block is asked at all. Both act only where the player
     * {@linkplain Player#mayBuild() may build}, so only there does the wrench keep its click. In adventure mode
     * {@code ItemStack#useOn} answers {@code PASS} before {@code WrenchItem#useOn} runs, and a kept click would go
     * nowhere: the client would go on to the main hand's use and then to the <b>offhand</b>, whose
     * {@code useWithoutItem} vanilla never asks, so offhand food or a pearl would be used in front of the station — the
     * symptom of issue #26 again. There the wrench opens the screen like any other item instead. The abilities are
     * synced, so client and server still agree. {@code AisleNaming} and {@code RackBayGestures} test the same
     * tag;</li>
     * <li>the <b>Mechanical Arm</b> item selects the terminal and the production station as arm points (M12, ADR-025).
     * Create's {@code ArmInteractionPointHandler} cancels that click on the client only; the server still receives it
     * and runs this block with the arm in hand, so without this entry the server would open the screen in the middle of
     * a selection. Against the stock keeper, which is no arm target, the arm is placed as before;</li>
     * <li>Create's <b>clipboard</b> opens its own editor on a plain click and is hung on the face on a sneaking one
     * ({@code ClipboardBlockItem#useOn}). None of the three blocks has a clipboard-copyable setting, so Create's copy
     * handler never takes this click; a filled-in list still goes into the terminal through the slot in its
     * screen;</li>
     * <li><b>Create's goggles</b> are put on by their own right-click ({@code GogglesItem#use}), and issue #26 names
     * them among the items that keep working exactly as before. Worn goggles take no part in a click at all. When the
     * head slot already holds identical goggles, their use fails and the click goes on to the offhand, as it always
     * did; that one case is left as it is.</li>
     * </ul>
     * Items Create handles before any block is asked need no entry: the Display Link (and every
     * {@code ClickToLinkBlockItem}), funnels and super glue set {@code useBlock} to {@code FALSE} in a
     * {@code RightClickBlock} listener, and the Schematic and Quill, factory gauge connections and chain conveyors act
     * on the raw input before a click is sent. Everything else — blocks, tools, food, filters, the Linked Controller,
     * the Wand of Symmetry — opens the screen as it would open a chest, and does its own thing with Shift.
     */
    static boolean actsOnTheBlock(ItemStack stack, Player player) {
        if (stack.is(Tags.Items.TOOLS_WRENCH) || AllItems.WRENCH.isIn(stack))
            return player != null && player.mayBuild();
        return AllBlocks.MECHANICAL_ARM.isIn(stack) || AllBlocks.CLIPBOARD.isIn(stack) || AllItems.GOGGLES.isIn(stack);
    }
}
