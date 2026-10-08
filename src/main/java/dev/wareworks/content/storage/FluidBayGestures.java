package dev.wareworks.content.storage;

import com.simibubi.create.AllBlocks;

import dev.wareworks.content.controller.AisleNaming;
import dev.wareworks.content.fluid.FluidContainers;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * The hand gestures of a fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): <b>a right-click with a
 * container empties it into the bay, and a right-click with an empty one fills it from the bay.</b>
 *
 * <table>
 * <caption>What a click does</caption>
 * <tr><th>input</th><th>effect</th></tr>
 * <tr><td>right-click with a <b>filled</b> container the bay takes</td><td>empties it into the bay</td></tr>
 * <tr><td>right-click with an <b>empty</b> container</td><td>fills it from the bay</td></tr>
 * <tr><td>right-click with any other container</td><td>nothing moves, and the click is still <b>consumed</b></td></tr>
 * <tr><td>any of those with the container in the <b>off hand</b></td><td>the same, in either hand</td></tr>
 * <tr><td><b>Shift</b> + right-click</td><td><b>not used</b> — passed on, so a block can be placed against the
 * face</td></tr>
 * <tr><td>right-click with an <b>empty hand</b></td><td><b>nothing</b> — a hand cannot carry fluid, so unlike a rack
 * bay's there is nothing to take out</td></tr>
 * <tr><td>right-click with anything else that is not a container</td><td>passed on: it keeps its own meaning</td></tr>
 * <tr><td>a plain click <b>inside</b> the value box's 4 px sphere</td><td>Create's filter slot, untouched</td></tr>
 * <tr><td>a punch (left-click)</td><td>the warning that breaking it loses the fluid ({@link FluidBayBlock})</td></tr>
 * </table>
 *
 * <h2>Why this is one call and not the rack bay's arithmetic</h2>
 * {@code FluidUtil.interactWithFluidHandler} is the vanilla routine every tank in the ecosystem uses: it tries to
 * <b>fill</b> the held container from the handler, then to <b>empty</b> it into the handler, puts the resulting
 * container back in the player's hand, stows the extra one when the hand held more than one, and makes the bucket
 * sound. All four of the traps a hand-written version would hit are already handled in it — a container operation
 * needs a stack of exactly one, the result is {@code getContainer()} and may be a different item, a simulated drain
 * lies about that result, and a creative player's stack must not change — so writing those out again would be a second
 * copy of them, not a better one.
 * <p>
 * It is given the bay's <b>ungated</b> handler ({@link FluidBayBlockEntity#handler()}) and never its
 * {@link FluidBayBlockEntity#pipeView()}: {@code storage.fluidBayPipeExtraction} is a rule about <b>pipes</b>, and a
 * player's own bucket is never refused by it. That is Create's own {@code forceFill} pattern.
 *
 * <h2>Shift has no fluid meaning, so this class needs no event listener</h2>
 * A rack bay's Shift gesture moves a whole <b>stack</b>, which is unreachable from the block alone — vanilla drops a
 * sneaking interaction before the block whenever a hand holds something — and that is why
 * {@link RackBayGestures} carries an interaction listener. <b>A container is one container</b>: there is no larger
 * amount for Shift to mean, because a bucket is emptied whole or refused (D5) and a stack of sixteen empty buckets
 * fills exactly one of them per click, which is {@code FluidUtil}'s own rule. So Shift keeps its item meaning here,
 * a sneaking click is passed straight on, and sneak-placing a block against a tank's face goes on working.
 *
 * <h2>Why a container's click is consumed even when nothing moves</h2>
 * This is the one rule that is not about convenience. A click this class passes on reaches the item's own
 * {@code useOn}, and a <b>bucket of lava</b>'s own use places a lava source against the face it was aimed at. A bay
 * that is full, that holds another fluid, or whose filter names another one would therefore set a wooden rack wall on
 * fire the moment a player tried to pour into it, which reads as this mod destroying a warehouse rather than as a
 * refused transfer. So every <b>container</b> is answered ({@link FluidContainers#isContainer}), whether or not it
 * moved anything, and everything that is not a container is passed on untouched. Create's own Fluid Tank answers the
 * same way and for the same reason.
 * <p>
 * <b>In either hand</b>, and that is the same rule rather than a convenience (M30 review fix). Vanilla offers a block
 * the off hand too — {@code Minecraft#startUseItem} walks both hands, and only the <i>empty-handed</i> interaction is
 * main hand only ({@code ServerPlayerGameMode#useItemOn}) — so a guard on the main hand would hand a bucket of lava
 * carried in the off hand straight to {@code BucketItem#use}, which pours it against the aisle face: exactly the harm
 * the paragraph above exists to prevent, reached by nothing more exotic than keeping the bucket in the other hand. A
 * container in the off hand therefore fills and empties a bay normally, and when it cannot, its click is consumed
 * there too. {@code FluidUtil.interactWithFluidHandler} takes the hand, so there is nothing else to do.
 * <p>
 * The one way left to pour lava at a bay is a <b>sneaking</b> click, which this class never sees: vanilla skips
 * {@code useItemOn} entirely for a sneaking player holding an item, and that is the price of leaving Shift its own
 * meaning so a block can still be placed against a tank's face.
 *
 * <h2>What this gesture must not take</h2>
 * {@link #actsOnTheBlock} is {@link RackBayGestures}' list, and the reasons are that class's: a <b>wrench</b> turns
 * the bay and so decides which face is the aisle face; Create's <b>clipboard</b> copies the filter and the priority
 * onto a whole tank wall; the <b>Mechanical Arm</b> item places an arm. The fourth entry is wider here than there,
 * because joining is across families (ADR-050): <b>any</b> bay, rack or fluid, builds the wall by being placed against
 * the one that was clicked, so a bay item is never consumed by a bay. A {@code FakePlayer} is refused outright — a
 * Deployer in the aisle would otherwise empty a tank wall bucket by bucket, which is exactly what
 * {@code StorageFilterBehaviour#mayInteract} refuses it at the value box for.
 * <p>
 * A <b>renamed</b> item is answered rather than passed on, as it is at a rack bay: the aisle-naming gesture a player
 * learned at a warehouse interface collides head-on with this one, and a renamed bucket of lava would otherwise be
 * emptied into the bay with no sign that the name was never read ({@link WareworksLang#BAY_NO_NAMING}).
 */
public final class FluidBayGestures {
    private FluidBayGestures() {
    }

    /**
     * A right-click on a fluid bay, from {@code FluidBayBlock#useItemOn}. The transfer runs on the <b>server</b>; the
     * client answers the same result so that its prediction matches.
     * <p>
     * Answers {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} for everything that is not a container, so a click a bay
     * cannot use behaves exactly as it did before this block existed — a block is placed, a wrench turns, an arm is
     * aimed.
     */
    public static ItemInteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player,
                                            InteractionHand hand) {
        if (player == null || player.isSpectator())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!(level.getBlockEntity(pos) instanceof FluidBayBlockEntity bay))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player instanceof FakePlayer || actsOnTheBlock(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        // Shift is not this block's gesture: a container is one container, so there is no larger amount for it to
        // mean, and passing it on is what keeps sneak-placing a block against a tank's face working.
        if (player.isSecondaryUseActive())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (AisleNaming.isGesture(stack))
            return refuseNaming(level, player);
        if (!FluidContainers.isContainer(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        // The ungated handler, never the pipe view: a player's bucket is not a pipe (D4). The answer is deliberately
        // ignored - a container's click is consumed either way, because passing it on would pour its fluid against
        // the block's face.
        FluidUtil.interactWithFluidHandler(player, hand, bay.handler());
        return ItemInteractionResult.CONSUME;
    }

    /**
     * A bay names no aisle (ADR-045): it is not a block of a warehouse at all until a crane can reach it. The click is
     * <b>consumed</b> with one action-bar line rather than passed on, because passing it on would empty the renamed
     * container into the bay — exactly the surprise the answer exists to avoid.
     * <p>
     * Only a <b>plain</b> click gets here; a sneaking one is passed on above, so a renamed block is still placed
     * against a tank the way it is against a controller or an interface.
     */
    private static ItemInteractionResult refuseNaming(Level level, Player player) {
        if (!level.isClientSide)
            player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.BAY_NO_NAMING), true);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Items whose own right-click has to win on a fluid bay — {@link RackBayGestures}' list, with the bay entry
     * widened to <b>any</b> bay because joining is across families: a fluid bay placed against a rack bay's side takes
     * that bay's facing and shares its upright ({@link BayColumn#placementFacing}, ADR-050), which is how a mixed wall
     * is built at all.
     */
    private static boolean actsOnTheBlock(ItemStack stack) {
        return stack.is(Tags.Items.TOOLS_WRENCH) || AllBlocks.CLIPBOARD.isIn(stack)
                || AllBlocks.MECHANICAL_ARM.isIn(stack) || isBay(stack);
    }

    /** Whether {@code stack} places a bay of any family and any tier — the item that builds a wall. */
    static boolean isBay(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof TieredBay;
    }
}
