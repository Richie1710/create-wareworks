package dev.wareworks.content.storage;

import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;

import dev.wareworks.content.controller.AisleNaming;
import dev.wareworks.content.crane.head.TransferContexts;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

/**
 * The hand gestures of a rack bay ({@code docs/warehouse-system.md} §3.8, M28, issue #20, ADR-045): <b>a plain
 * right-click moves one item, Shift moves one stack, and that holds in both directions.</b>
 *
 * <table>
 * <caption>What a click does</caption>
 * <tr><th>input</th><th>effect</th></tr>
 * <tr><td>right-click with an item the bay takes</td><td>puts <b>one item</b> in</td></tr>
 * <tr><td><b>Shift</b> + right-click with such an item</td><td>puts <b>one stack</b> in</td></tr>
 * <tr><td>right-click with an <b>empty hand</b></td><td>takes <b>one item</b> out</td></tr>
 * <tr><td><b>Shift</b> + right-click with an empty hand</td><td>takes <b>one stack</b> out</td></tr>
 * <tr><td>a plain click <b>inside</b> the value box's 4 px sphere</td><td>Create's filter slot, untouched</td></tr>
 * <tr><td>holding a click inside that sphere</td><td>the storage priority board, untouched</td></tr>
 * <tr><td>a punch (left-click)</td><td><b>not used</b> — it is the same input as breaking the block</td></tr>
 * </table>
 *
 * There is deliberately <b>no "take everything"</b>: emptying a bay in one go is what breaking it is for, and a brass
 * bay holds 65 536 items against a player inventory's 2 304, so "everything" could only ever have meant "fill my
 * inventory and stop". The terminal's third modifier therefore has no counterpart here, and it could not have been
 * delivered anyway — a block click carries no modifier bit at all over the wire ({@code ServerboundInteractPacket}
 * carries one, {@code usingSecondaryAction}, and only for entities), so the server knows nothing about the player's
 * keyboard beyond the crouch state it already tracks.
 *
 * <h2>Why a punch is not the gesture, although a drawer's is</h2>
 * {@code blockstate.attack(...)} and starting to break a block are <b>one input</b>:
 * {@code ServerPlayerGameMode#handleBlockBreakAction} calls {@code attack} and then begins destroy progress on the
 * following lines, and {@code STOP_DESTROY_BLOCK} breaks the block once that progress passes {@code 0.7F}. A player
 * building a warehouse holds an efficiency pickaxe, so "punch to take a stack" would break the bay in about two ticks
 * — on the block this mod places by the hundred. An empty-hand right-click keeps the drawer feel and cannot destroy
 * anything.
 *
 * <h2>Why this class owns an event listener</h2>
 * Vanilla never hands a <b>sneaking</b> interaction to a block while a hand holds something:
 * {@code ServerPlayerGameMode#useItemOn} builds {@code flag1} from "is sneaking" and "either hand is non-empty" (less
 * the items that answer {@code doesSneakBypassUse}, whose default is {@code false}) and skips {@code useItemOn}
 * <i>and</i> {@code useWithoutItem} for it. So "Shift puts one stack in" is unreachable from the block alone, and so is
 * "Shift takes one stack out" for a player with anything in the offhand — the very case
 * {@link dev.wareworks.content.station.ProductionStationHooks} was written for. The supported way past that guard is
 * the interaction event: {@code setUseBlock(TRUE)} makes the block's own {@code useItemOn} run regardless.
 * <p>
 * It is forced only for a sneaking main-hand click on a rack bay, by a real player, holding none of the items listed
 * in {@link #actsOnTheBlock}; and the listener answers the same on both sides, so the client's prediction matches the
 * server. Forcing it costs one thing, which is worth saying out loud: <b>you cannot sneak-place a block against a
 * bay's face while the bay would accept that block as an item.</b> A bay that refuses it — a filter, a different type
 * already inside, no room left — passes the click on and the block is placed as before, and so does every item below.
 * The one block for which that cost would have been fatal is a <b>rack bay</b>, because the row of bays a player
 * clicks along is how a wall is built at all; it is therefore on that list.
 *
 * <h2>Item conservation</h2>
 * Every gesture simulates first and commits second, and the player's stack is changed by what the <b>real</b> transfer
 * accepted, never by what was asked for. A take-out is simulated against the player's own inventory as well and takes
 * only what fits, so a full inventory means nothing happens and nothing spills; if a commit ever disagreed with its
 * own simulate, what was taken goes back into the bay rather than anywhere else. The simulate runs on both sides (the
 * bay's count, its filter and the capacity config all reach the client), so the client predicts what the server will
 * really do; only the server commits.
 * <p>
 * The prediction is exact for any item whose identity <b>is</b> its registry id, which is every item a bay is built
 * for. It <b>misses for an item carrying data components</b> — a renamed or enchanted one — and that is a property of
 * the sync format rather than of this class: a bay's update tag may only carry an id
 * ({@link RackBayHandler#writeClientPacket}), so the client rebuilds a component-less key and
 * {@code BayContents.roomFor} compares the wrong thing. A bay holding an enchanted book predicts a pass-through where
 * the server inserts, and a bay holding one predicts success for a plain book of the same id where the server refuses.
 * Both are cosmetic and self-correcting — the server's block-change acknowledgement is the next thing the client sees
 * — but anyone chasing a flickering hand should know where to look.
 */
public final class RackBayGestures {
    /**
     * Items a plain right-click moves, in <b>either</b> direction. One, symmetric, and the same thing a plain click
     * means at a warehouse terminal.
     * <p>
     * The alternative the issue itself raised is "a plain insert puts the whole held stack in", because standing at a
     * wall holding 64 cobblestone and clicking 64 times is tedious. It is this constant and nothing else, and the
     * taking direction has {@link #PLAIN_TAKE_ITEMS} of its own, so the two can differ without touching anything here.
     */
    public static final int PLAIN_INSERT_ITEMS = 1;
    /** Items a plain right-click with an empty hand takes out; see {@link #PLAIN_INSERT_ITEMS}. */
    public static final int PLAIN_TAKE_ITEMS = 1;

    private RackBayGestures() {
    }

    /** Registers the sneak hook on the NeoForge game event bus. Called once from {@code Wareworks}. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(RackBayGestures::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (forcesBlockUse(event.getEntity(), event.getLevel(), event.getPos(), event.getHand()))
            event.setUseBlock(TriState.TRUE);
    }

    /**
     * Whether this right-click is a bay's Shift gesture and therefore has to be handed to the block although the
     * player is sneaking (see the class comment). Free of the event and visible to tests, because
     * {@code GameTestHelper#useBlock} calls {@code useItemOn} itself and never runs the game mode's sneak guard, so
     * this is the only part of the mechanism a GameTest can ask about at all.
     */
    public static boolean forcesBlockUse(Player player, Level level, BlockPos pos, InteractionHand hand) {
        if (player == null || level == null || pos == null || hand != InteractionHand.MAIN_HAND)
            return false;
        if (!player.isSecondaryUseActive() || player instanceof FakePlayer)
            return false;
        // A renamed item is excluded here as well, although the gesture answers it rather than passing it on:
        // sneaking is how a player places a renamed block against a warehouse block today, and AisleNaming promises
        // exactly that for the controller and the interface. A bay must not be the one block where it stops working.
        ItemStack held = player.getMainHandItem();
        if (actsOnTheBlock(held) || AisleNaming.isGesture(held))
            return false;
        return level.getBlockState(pos).getBlock() instanceof RackBayBlock;
    }

    /**
     * Items whose own right-click has to win on a bay, so neither the gesture nor the forced sneak click may take it:
     * <ul>
     * <li>a <b>wrench</b> (the {@code c:tools/wrench} tag, the one Create tests too) turns the bay, which decides
     * which face is the aisle face;</li>
     * <li>Create's <b>clipboard</b> copies the store filter and the storage priority, which is how a fifty-bay rack
     * wall is dedicated in one gesture — Create's own handler cancels a plain click for it and returns on a sneaking
     * one, so without this a sneaking clipboard click would be stored in the bay;</li>
     * <li>the <b>Mechanical Arm</b> item aims an arm, as it does on a warehouse interface and an output (M12);</li>
     * <li>a <b>bay</b> of any family and any tier <b>builds the wall</b>, which is the one item on this list whose own
     * click is the block's whole reason for existing: "you extend a wall by placing a bay beside an existing one"
     * (issue #20), and {@link BayColumn#placementFacing} copies the clicked bay's facing for exactly that gesture.
     * Without this entry a freshly placed bay — empty and unfiltered, which accepts anything once — <i>stored</i> the
     * next bay instead of letting the row grow, in both postures: a plain click because {@code ServerPlayerGameMode}
     * returns on a consuming {@code useItemOn} before {@code stack.useOn}, and a sneaking one because
     * {@link #forcesBlockUse} hands it to the block. With it, what a player sees is the column rule's own refusal
     * ({@link WareworksLang#BAY_COLUMN_REFUSED}) or a placed bay. <b>Any</b> family since M30, because joining is
     * across them (ADR-050): a <b>fluid</b> bay clicked against a rack bay's side takes that bay's facing and shares
     * its upright, so a rack bay that swallowed it would make a mixed wall unbuildable by hand.</li>
     * </ul>
     * A <b>renamed</b> item is not in this list, because a plain click with one is <i>answered</i> rather than passed
     * on: the aisle-naming gesture a player learned on an interface collides head-on with "a right-click puts the item
     * in" ({@link WareworksLang#BAY_NO_NAMING}). A <b>sneaking</b> click with one is passed on all the same, in
     * {@link #use} and in {@link #forcesBlockUse}, because that is how a renamed block is placed against a warehouse
     * block today.
     */
    private static boolean actsOnTheBlock(ItemStack stack) {
        return stack.is(Tags.Items.TOOLS_WRENCH) || AllBlocks.CLIPBOARD.isIn(stack)
                || AllBlocks.MECHANICAL_ARM.isIn(stack) || FluidBayGestures.isBay(stack);
    }

    /**
     * A right-click on a rack bay, from {@code RackBayBlock#useItemOn}: runs on both sides, commits on the server.
     * <p>
     * Answers {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} for everything that is not this gesture and for a transfer
     * that would move nothing, so that a click the bay cannot use behaves exactly as it did before the bay existed —
     * a block is placed, a wrench turns, an arm is aimed.
     */
    public static ItemInteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player,
                                            InteractionHand hand) {
        if (player == null || hand != InteractionHand.MAIN_HAND || player.isSpectator())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!(level.getBlockEntity(pos) instanceof RackBayBlockEntity bay))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        // Only a real player's hands reach a bay's contents. A Deployer has the item capability for exactly this
        // purpose, and the aisle face is the one face in-aisle automation can reach, so a fake player that could
        // right-click would be able to empty a whole rack wall item by item (StorageFilterBehaviour#mayInteract
        // refuses them at the value box for the same reason, which is why this click arrives here at all).
        if (player instanceof FakePlayer || actsOnTheBlock(stack))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        boolean wholeStack = player.isSecondaryUseActive();
        if (AisleNaming.isGesture(stack))
            return wholeStack ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION : refuseNaming(level, player);
        return stack.isEmpty() ? take(level, pos, bay, player, wholeStack)
                : insert(level, pos, bay, player, stack, wholeStack);
    }

    /**
     * A bay names no aisle (ADR-045): it is not a block of a warehouse at all until a crane can reach it, and a
     * warehouse's aisles are named where their letter is set. The click is <b>consumed</b> with one action-bar line
     * rather than passed on, because passing it on would put the renamed item into the bay — exactly the surprise the
     * answer exists to avoid.
     * <p>
     * Only a <b>plain</b> click gets here, exactly as in {@code AisleNaming.clicked}: a sneaking click with a renamed
     * item is passed on, and is not even forced to the block, so sneaking still places a renamed block against a bay
     * the way it does against a controller and an interface. The consequence is worth stating: a renamed item cannot
     * be put into a bay <b>by hand</b> at all. A funnel, a hopper and the crane still store one, and it would read as
     * its plain self on the block either way, because that is all the client is sent.
     */
    private static ItemInteractionResult refuseNaming(Level level, Player player) {
        if (!level.isClientSide)
            player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.BAY_NO_NAMING), true);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Puts one item, or one stack, into the bay. The bay decides what it takes — its store filter, the one type it is
     * committed to, and the room it has left — and the held stack shrinks by what the <b>real</b> insert accepted.
     * <p>
     * {@code OVERLOADED} is deliberately not consulted: the column rule takes a bay out of the warehouse's store
     * plans, and a player's own hands are not the crane. A bay in a column a command broke stays usable by hand,
     * exactly as it stays retrievable by the crane.
     */
    private static ItemInteractionResult insert(Level level, BlockPos pos, RackBayBlockEntity bay, Player player,
                                                ItemStack stack, boolean wholeStack) {
        int wanted = wholeStack ? Math.min(stack.getCount(), stack.getMaxStackSize()) : PLAIN_INSERT_ITEMS;
        ItemStack offered = stack.copyWithCount(wanted);
        if (accepted(wanted, bay.insert(offered, true)) <= 0)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        int moved = accepted(wanted, bay.insert(offered, false));
        if (moved <= 0)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION; // the simulate was stale: nothing moved
        stack.shrink(moved);
        AllSoundEvents.DEPOT_PLOP.playOnServer(level, pos);
        return ItemInteractionResult.CONSUME;
    }

    /**
     * Takes one item, or one stack, out of the bay and into the player's inventory — and <b>only what fits there</b>.
     * The insert into the player is simulated first and exactly that much is extracted, so a player with a full
     * inventory sees nothing happen rather than items on the floor.
     */
    private static ItemInteractionResult take(Level level, BlockPos pos, RackBayBlockEntity bay, Player player,
                                              boolean wholeStack) {
        Optional<ItemKey> stored = bay.storedKey();
        if (stored.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        int wanted = wholeStack ? stored.get().getMaxStackSize() : PLAIN_TAKE_ITEMS;
        ItemStack available = bay.extract(wanted, true);
        IItemHandler inventory = new PlayerMainInvWrapper(player.getInventory());
        int fits = accepted(available.getCount(),
                ItemHandlerHelper.insertItemStacked(inventory, available.copy(), true));
        if (fits <= 0)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;
        ItemStack taken = bay.extract(fits, false);
        if (taken.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION; // the simulate was stale: nothing moved
        ItemStack leftOver = ItemHandlerHelper.insertItemStacked(inventory, taken, false);
        // Cannot happen after the simulate above, and handled anyway: items the player's inventory refused after all
        // go back where they came from, and only something that refuses even that reaches the floor. An item is
        // always in exactly one place (docs/warehouse-system.md §8), and a gesture is no exception.
        if (!leftOver.isEmpty()) {
            ItemStack rejected = bay.insert(leftOver, false);
            if (!rejected.isEmpty())
                TransferContexts.spillAt(level, pos, rejected);
        }
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.2f,
                1f + level.getRandom().nextFloat() * 0.2f);
        return ItemInteractionResult.CONSUME;
    }

    /** How much of {@code offered} a transfer really took, given the remainder it answered with. */
    private static int accepted(int offered, ItemStack remainder) {
        return offered - Math.min(offered, Math.max(0, remainder.getCount()));
    }
}
