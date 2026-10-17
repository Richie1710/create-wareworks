package dev.wareworks.gametest;

import static dev.wareworks.gametest.ComponentHashGameTests.TERMINAL_RACK;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItemComponent;
import com.simibubi.create.content.equipment.symmetryWand.mirror.CrossPlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.PlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.TriplePlaneMirror;
import com.simibubi.create.content.equipment.toolbox.ToolboxBlockEntity;
import com.simibubi.create.content.equipment.toolbox.ToolboxInventory;
import com.simibubi.create.foundation.item.ItemSlots;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ComponentMeanings;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.RetrievalRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of <b>component meanings</b> (issue #29, ADR-066): an item key compares, hashes and keeps a private copy of
 * Create's toolbox inventory, of a sand paper's polished item and of a symmetry wand's mirror by what they hold,
 * because their own {@code equals} does not — a toolbox's compares its filter stacks by identity and never its
 * contents, a sand paper's never holds, a mirror's never compares the kind of mirror. The toolboxes are real: placed,
 * filled through Create's own item handler (which sets the filters) and taken the way the toolbox's loot table takes
 * them, with the block entity's live inventory as the component; or decoded by Create's own codec. Each test first
 * checks that the three meanings are registered, so a Create update that renamed one of the toolbox's private members
 * fails with that message.
 * <ul>
 * <li>{@code componentmeaningtoolboxkeys} — equal toolboxes built apart, saved and sent are one key with one hash;
 * different contents, filters or mode are different keys with different hash codes; Create's own equality fails exactly
 * there; a key's text does not depend on the order the components were added; a key is untouched when its source
 * changes, and the stacks it hands out share nothing; a paused sand paper keeps the same contract; every other stack
 * compares as before;</li>
 * <li>{@code componentmeaningsymmetrywand} — a cross-plane and a triple-plane wand at one position, which Create's own
 * equality calls the same, are two keys, each one key with its saved and sent copy; a key is untouched when the wand
 * it was made from is moved, and the wands it hands out have mirrors of their own;</li>
 * <li>{@code componentmeaningtoolboxstoresrequestsandreloads} — filtered and unfiltered toolboxes and the two wands are
 * stored, one stock line per contents, reloaded with the controller and the chests, requested with a key decoded as a
 * client sends it and delivered into the terminal's buffer, each holding exactly what it was stored with; the item
 * census holds on every tick.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ComponentMeaningGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int AISLE_TIMEOUT_TICKS = 3600;

    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STORAGE_C = new RackPosition(3, 0, Side.LEFT);
    private static final List<RackPosition> STORAGE = List.of(STORAGE_A, STORAGE_B, STORAGE_C);

    /** Where the aisle test places its toolboxes for a moment, well past the end of the rails. */
    private static final BlockPos SPARE_A = new BlockPos(12, BASE_Y, 0);
    private static final BlockPos SPARE_B = new BlockPos(14, BASE_Y, 0);

    /** The uuid two copies of one toolbox share, and the uuid of another toolbox. */
    private static final UUID TOOLBOX_UUID = UUID.fromString("6c1f2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");
    private static final UUID OTHER_UUID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110");

    private static final int SLOTS = 8 * ToolboxInventory.STACKS_PER_COMPARTMENT;
    private static final int COMPARTMENTS = 8;
    private static final int DIAMONDS = 8;
    /** How many keys a spread check builds. */
    private static final int SPREAD = 16;

    /** Where the wands' mirrors stand: block coordinates, as {@code SymmetryWandItem#useOn} sets them. */
    private static final Vec3 MIRROR_AT = new Vec3(4, 64, -7);

    private ComponentMeaningGameTests() {
    }

    // --- keys --------------------------------------------------------------------------------------------------------

    /**
     * <b>A toolbox is one key per contents, filters and mode.</b> The three meanings are registered, checked first.
     * Two toolboxes filled the same way through two placed toolboxes' item handlers (Create sets the filter) and taken
     * as the loot table takes them — with the block entity's own inventory as the component — are, together with the
     * key loaded from a save and the key decoded from the network, equal both ways, hash equally and are found in hash
     * maps and sets, and every key matches the live and the decoded stack. Create's own equality tells the live toolbox
     * and its decoded copy apart, and lets an unfiltered toolbox holding eight diamonds equal an empty one: the bug.
     * Another item, count, component or compartment, a filter set or not and the limited mode give other keys with
     * other hash codes; a filter's count does not. A key's text is the same whichever order its components were added
     * in, and after a save. A key is untouched when its source's stacks grow in place, and two stacks it hands out
     * share no inventory. A sand paper paused while polishing keeps the same contract; plain, named and enchanted
     * stacks compare exactly as before.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void componentMeaningToolboxKeys(GameTestHelper helper) {
        assertMeaningsRegistered(helper);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Consumer<IItemHandler> eightDiamonds = handler -> insert(helper, handler, new ItemStack(Items.DIAMOND,
                DIAMONDS));
        ItemStack live = placedToolbox(helper, new BlockPos(1, 1, 1), TOOLBOX_UUID, eightDiamonds);
        ItemStack liveTwin = placedToolbox(helper, new BlockPos(3, 1, 1), TOOLBOX_UUID, eightDiamonds);
        ItemStack decoded = TerminalCraftGameTests.roundTrip(helper, live, ItemStack.STREAM_CODEC);

        // The premise, in Create's own terms.
        helper.assertFalse(ItemStack.isSameItemSameComponents(live, decoded),
                "Create's equality tells a filtered toolbox and its decoded copy apart");
        helper.assertFalse(ItemStack.isSameItemSameComponents(live, liveTwin),
                "and two filtered toolboxes holding the same");
        ItemStack unfilteredDiamonds = codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)),
                noFilters());
        ItemStack unfilteredEmpty = codecToolbox(helper, Map.of(), noFilters());
        helper.assertTrue(ItemStack.isSameItemSameComponents(unfilteredDiamonds, unfilteredEmpty),
                "Create's equality lets an unfiltered toolbox holding diamonds equal an empty one");

        // One key, however the toolbox was made.
        ComponentHashGameTests.assertOneKey(helper, "a filtered toolbox taken from the world", live, liveTwin);
        ComponentHashGameTests.assertOneKey(helper, "a filtered toolbox and its decoded copy", live, decoded);
        ComponentHashGameTests.assertOneKey(helper, "an unfiltered toolbox from Create's codec", unfilteredDiamonds,
                codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)), noFilters()));
        ItemKey key = ItemKey.of(live);
        helper.assertTrue(key.matches(live) && key.matches(liveTwin) && key.matches(decoded),
                "the key matches the live toolbox, its twin and the decoded copy");
        helper.assertTrue(ItemKey.of(decoded).matches(live), "the decoded key matches the live toolbox");
        helper.assertValueEqual(key.toString(), ItemKey.of(liveTwin).toString(), "the key's text is value-based");
        helper.assertFalse(key.toString().contains("@"), "no identity hash in the key's text: " + key);
        assertTextAfterASave(helper, "the toolbox taken from the world", live);

        // The key's text does not depend on the order the components were added: a loot drop sets the uuid first, a
        // stack loaded from a save may come back with its inventory first. One value, set in both orders.
        ToolboxInventory oneInventory = codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)),
                filters(new ItemStack(Items.DIAMOND))).get(AllDataComponents.TOOLBOX_INVENTORY);
        ItemStack uuidFirst = AllBlocks.TOOLBOXES.get(DyeColor.BROWN).asStack();
        uuidFirst.set(AllDataComponents.TOOLBOX_UUID, TOOLBOX_UUID);
        uuidFirst.set(AllDataComponents.TOOLBOX_INVENTORY, oneInventory);
        ItemStack inventoryFirst = AllBlocks.TOOLBOXES.get(DyeColor.BROWN).asStack();
        inventoryFirst.set(AllDataComponents.TOOLBOX_INVENTORY, oneInventory);
        inventoryFirst.set(AllDataComponents.TOOLBOX_UUID, TOOLBOX_UUID);
        helper.assertFalse(uuidFirst.getComponentsPatch().toString().equals(
                inventoryFirst.getComponentsPatch().toString()), "the patch's own text follows the order of adding");
        helper.assertValueEqual(ItemKey.of(uuidFirst).toString(), ItemKey.of(inventoryFirst).toString(),
                "the key's text does not");
        assertTextAfterASave(helper, "the toolbox with its uuid set first", uuidFirst);
        assertTextAfterASave(helper, "the toolbox with its inventory set first", inventoryFirst);
        ItemStack namedFirst = new ItemStack(Items.DIAMOND_SWORD);
        namedFirst.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        namedFirst.set(DataComponents.DAMAGE, 3);
        ItemStack damagedFirst = new ItemStack(Items.DIAMOND_SWORD);
        damagedFirst.set(DataComponents.DAMAGE, 3);
        damagedFirst.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        helper.assertValueEqual(ItemKey.of(namedFirst).toString(), ItemKey.of(damagedFirst).toString(),
                "nor does the text of a key without a meaning");

        // Different contents, filters or mode: different keys.
        ItemStack filtered = codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)),
                filters(new ItemStack(Items.DIAMOND)));
        assertApart(helper, "unfiltered and empty", unfilteredDiamonds, unfilteredEmpty);
        assertApart(helper, "another item", filtered, codecToolbox(helper,
                Map.of(0, new ItemStack(Items.EMERALD, DIAMONDS)), filters(new ItemStack(Items.DIAMOND))));
        assertApart(helper, "another count", filtered, codecToolbox(helper,
                Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS + 1)), filters(new ItemStack(Items.DIAMOND))));
        ItemStack named = new ItemStack(Items.DIAMOND, DIAMONDS);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Sharp"));
        assertApart(helper, "a named diamond", filtered, codecToolbox(helper, Map.of(0, named),
                filters(new ItemStack(Items.DIAMOND))));
        assertApart(helper, "the same stack in the second compartment", filtered, codecToolbox(helper,
                Map.of(ToolboxInventory.STACKS_PER_COMPARTMENT, new ItemStack(Items.DIAMOND, DIAMONDS)),
                filters(new ItemStack(Items.DIAMOND))));
        assertApart(helper, "a filter set or not, nothing inside", unfilteredEmpty,
                codecToolbox(helper, Map.of(), filters(new ItemStack(Items.DIAMOND))));
        ItemKey outside = ItemKey.of(filtered);
        ItemKey[] limited = new ItemKey[1];
        filtered.get(AllDataComponents.TOOLBOX_INVENTORY).inLimitedMode(inventory -> limited[0] = ItemKey.of(filtered));
        helper.assertFalse(outside.equals(limited[0]) || limited[0].equals(outside), "the limited mode, another key");
        helper.assertFalse(outside.hashCode() == limited[0].hashCode(), "the limited mode, another hash code");
        helper.assertTrue(outside.equals(ItemKey.of(filtered)), "and the mode is back to normal afterwards");

        // What a toolbox holds is in its key's hash, not only in its equality: a revert to counting the toolbox
        // inventory by its type alone (ADR-064) puts every toolbox of one colour into one hash bucket and fails here.
        ComponentHashGameTests.assertSpread(helper, "a toolbox of every diamond count", SPREAD,
                i -> codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, i + 1)), noFilters()));
        ComponentHashGameTests.assertSpread(helper, "a toolbox with its diamonds in every compartment", COMPARTMENTS,
                i -> codecToolbox(helper, Map.of(i * ToolboxInventory.STACKS_PER_COMPARTMENT,
                        new ItemStack(Items.DIAMOND, DIAMONDS)), noFilters()));
        ComponentHashGameTests.assertSpread(helper, "a toolbox with a filter in every compartment", COMPARTMENTS,
                i -> {
                    List<ItemStack> oneFilter = new ArrayList<>(noFilters());
                    oneFilter.set(i, new ItemStack(Items.DIAMOND));
                    return codecToolbox(helper, Map.of(), oneFilter);
                });

        // A filter's count is not part of it: Create compares one by item and components only.
        ComponentHashGameTests.assertOneKey(helper, "a filter of two diamonds and one of one",
                codecToolbox(helper, Map.of(), filters(new ItemStack(Items.DIAMOND, 2))),
                codecToolbox(helper, Map.of(), filters(new ItemStack(Items.DIAMOND))));

        // A private copy: the key keeps what it was made from, and hands out stacks of their own.
        ItemStack source = codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)),
                filters(new ItemStack(Items.DIAMOND)));
        ItemKey kept = ItemKey.of(source);
        int hash = kept.hashCode();
        Set<ItemKey> set = new HashSet<>(List.of(kept));
        ToolboxInventory sourceInventory = source.get(AllDataComponents.TOOLBOX_INVENTORY);
        sourceInventory.getStackInSlot(0).grow(1); // what a placed toolbox does to the stacks its item lent it
        helper.assertValueEqual(kept.hashCode(), hash, "the key's hash after its source grew");
        helper.assertTrue(kept.equals(ItemKey.of(filtered)), "the key still holds eight diamonds");
        helper.assertTrue(set.contains(kept) && set.contains(ItemKey.of(filtered)), "and is found where it was put");
        helper.assertFalse(kept.matches(source), "the grown source is another toolbox now");
        ItemStack first = kept.toStack();
        ItemStack second = kept.toStack(1);
        ToolboxInventory firstInventory = first.get(AllDataComponents.TOOLBOX_INVENTORY);
        ToolboxInventory secondInventory = second.get(AllDataComponents.TOOLBOX_INVENTORY);
        helper.assertTrue(firstInventory != secondInventory && firstInventory != sourceInventory
                && secondInventory != sourceInventory, "every stack the key hands out has an inventory of its own");
        helper.assertTrue(kept.matches(first) && kept.matches(second), "both are stacks of the key");
        firstInventory.getStackInSlot(0).grow(1);
        helper.assertTrue(kept.matches(second) && !kept.matches(first), "growing one changes neither the other nor "
                + "the key");
        helper.assertValueEqual(kept.toStack(3).getCount(), 3, "toStack keeps its count");

        // A sand paper paused while polishing: its record never equals another instance.
        helper.assertFalse(ItemStack.isSameItemSameComponents(polishing(new ItemStack(Items.IRON_INGOT)),
                polishing(new ItemStack(Items.IRON_INGOT))), "Create's equality tells two paused sand papers apart");
        ComponentHashGameTests.assertOneKey(helper, "a sand paper polishing an iron ingot",
                polishing(new ItemStack(Items.IRON_INGOT)), polishing(new ItemStack(Items.IRON_INGOT)));
        assertApart(helper, "another polished item", polishing(new ItemStack(Items.IRON_INGOT)),
                polishing(new ItemStack(Items.GOLD_INGOT)));
        assertApart(helper, "two polished items", polishing(new ItemStack(Items.IRON_INGOT)),
                polishing(new ItemStack(Items.IRON_INGOT, 2)));
        ItemStack paper = polishing(new ItemStack(Items.IRON_INGOT));
        ItemKey paperKey = ItemKey.of(paper);
        int paperHash = paperKey.hashCode();
        paper.get(AllDataComponents.SAND_PAPER_POLISHING).item().shrink(1); // what releaseUsing does to the record
        helper.assertValueEqual(paperKey.hashCode(), paperHash, "the sand paper key's hash after its source shrank");
        helper.assertTrue(paperKey.equals(ItemKey.of(polishing(new ItemStack(Items.IRON_INGOT)))),
                "the sand paper key still holds the ingot");
        ComponentHashGameTests.assertSpread(helper, "a sand paper polishing every count of ingots", SPREAD,
                i -> polishing(new ItemStack(Items.IRON_INGOT, i + 1)));

        // Everything else compares exactly as before.
        ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(
                registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3));
        List<ItemStack> plain = List.of(new ItemStack(Items.DIAMOND), named, book, book.copy(),
                TerminalCraftGameTests.roundTrip(helper, book, ItemStack.STREAM_CODEC));
        for (ItemStack one : plain) {
            helper.assertFalse(ComponentMeanings.present(one), "no meaning in " + one);
            for (ItemStack other : plain) {
                boolean vanilla = ItemStack.isSameItemSameComponents(one, other);
                helper.assertValueEqual(ItemKey.of(one).matches(other), vanilla, "matches as before: " + one
                        + " / " + other);
                helper.assertValueEqual(ComponentMeanings.sameItemSameComponents(one, other), vanilla,
                        "compares as before: " + one + " / " + other);
            }
        }
        helper.succeed();
    }

    /**
     * <b>A symmetry wand is one key per kind of mirror, orientation, position and state.</b> The three meanings are
     * registered, checked first. Create's own equality calls an orthogonal cross-plane mirror and a triple-plane mirror
     * at one position the same — its {@code equals} never compares the kind of mirror, and a triple-plane mirror keeps
     * orientation index 0 and answers the cross-plane's {@code Y} — so two such wands were one key: they are two keys
     * with two hash codes now. Each wand is one key with its twin built apart, its save and its network copy; another
     * orientation, position or enable flag is another key, and the position is in the hash. A key is untouched when the
     * mirror of the wand it was made from is moved in place, as using the wand on a block moves it, and every wand a
     * key hands out has a mirror of its own, of the key's kind.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void componentMeaningSymmetryWand(GameTestHelper helper) {
        assertMeaningsRegistered(helper);

        // The premise, in Create's own terms.
        helper.assertTrue(new CrossPlaneMirror(MIRROR_AT).equals(new TriplePlaneMirror(MIRROR_AT)),
                "Create's equality calls a cross-plane and a triple-plane mirror at one position the same");
        helper.assertTrue(ItemStack.isSameItemSameComponents(wand(new CrossPlaneMirror(MIRROR_AT)),
                wand(new TriplePlaneMirror(MIRROR_AT))), "and so two such wands");

        // One key per wand, and the two kinds apart.
        ComponentHashGameTests.assertOneKey(helper, "a cross-plane wand", wand(new CrossPlaneMirror(MIRROR_AT)),
                wand(new CrossPlaneMirror(MIRROR_AT)));
        ComponentHashGameTests.assertOneKey(helper, "a triple-plane wand", wand(new TriplePlaneMirror(MIRROR_AT)),
                wand(new TriplePlaneMirror(MIRROR_AT)));
        ComponentHashGameTests.assertOneKey(helper, "a plane wand facing YZ", wand(plane(PlaneMirror.Align.YZ)),
                wand(plane(PlaneMirror.Align.YZ)));
        assertApart(helper, "a cross-plane and a triple-plane wand", wand(new CrossPlaneMirror(MIRROR_AT)),
                wand(new TriplePlaneMirror(MIRROR_AT)));
        assertApart(helper, "a plane wand in its two orientations", wand(plane(PlaneMirror.Align.XY)),
                wand(plane(PlaneMirror.Align.YZ)));
        assertApart(helper, "a mirror one block further", wand(new CrossPlaneMirror(MIRROR_AT)),
                wand(new CrossPlaneMirror(MIRROR_AT.add(1, 0, 0))));
        CrossPlaneMirror off = new CrossPlaneMirror(MIRROR_AT);
        off.enable = false;
        assertApart(helper, "a mirror switched off", wand(new CrossPlaneMirror(MIRROR_AT)), wand(off));
        ComponentHashGameTests.assertSpread(helper, "a wand at every position along a row", SPREAD,
                i -> wand(new CrossPlaneMirror(MIRROR_AT.add(i, 0, 0))));
        assertTextAfterASave(helper, "a triple-plane wand", wand(new TriplePlaneMirror(MIRROR_AT)));
        helper.assertFalse(ItemKey.of(wand(new TriplePlaneMirror(MIRROR_AT))).toString().contains("@"),
                "no identity hash in a wand key's text");

        // A private copy: the key keeps where its mirror stood, and hands out wands with mirrors of their own.
        ItemStack source = wand(new TriplePlaneMirror(MIRROR_AT));
        SymmetryMirror sourceMirror = source.get(AllDataComponents.SYMMETRY_WAND);
        ItemKey key = ItemKey.of(source);
        int hash = key.hashCode();
        sourceMirror.setPosition(MIRROR_AT.add(0, 5, 0)); // what SymmetryWandItem#useOn does to the wand's own mirror
        helper.assertValueEqual(key.hashCode(), hash, "the key's hash after its source's mirror moved");
        helper.assertTrue(key.equals(ItemKey.of(wand(new TriplePlaneMirror(MIRROR_AT)))),
                "the key's mirror still stands where it stood");
        helper.assertFalse(key.matches(source), "the moved wand is another wand now");
        ItemStack first = key.toStack();
        ItemStack second = key.toStack(1);
        SymmetryMirror firstMirror = first.get(AllDataComponents.SYMMETRY_WAND);
        SymmetryMirror secondMirror = second.get(AllDataComponents.SYMMETRY_WAND);
        helper.assertTrue(firstMirror != secondMirror && firstMirror != sourceMirror && secondMirror != sourceMirror,
                "every wand the key hands out has a mirror of its own");
        helper.assertTrue(firstMirror instanceof TriplePlaneMirror && secondMirror instanceof TriplePlaneMirror,
                "of the key's kind");
        helper.assertTrue(key.matches(first) && key.matches(second), "both are wands of the key");
        firstMirror.setPosition(MIRROR_AT.add(0, 9, 0));
        helper.assertTrue(key.matches(second) && !key.matches(first), "moving one changes neither the other nor "
                + "the key");
        helper.succeed();
    }

    // --- the warehouse -----------------------------------------------------------------------------------------------

    /**
     * <b>Store, reload, request and retrieve toolboxes and wands.</b> The three meanings are registered, checked first.
     * Two filtered toolboxes holding eight diamonds, taken from two placed toolboxes that share one uuid (copies of one
     * toolbox), lie in two storage locations, and a filtered empty toolbox in a third, beside a cross-plane and a
     * triple-plane symmetry wand centred on one block, which Create's own equality calls the same. An unfiltered
     * toolbox holding eight diamonds and an empty unfiltered one, without uuid, are fed through the input once the
     * motor runs — Create's own equality calls those two the same, so a station or the crane that merged them would
     * make the empty one a second copy of the full one. The terminal shows six lines: two of the first kind, one of
     * each other. The chests and the controller are saved and loaded while the two filtered ones lie in two locations,
     * and the loaded index merges their decoded keys. Then every line is requested with a key that went through the
     * request payload's codec and delivered into the terminal's buffer, the unfiltered two from wherever the crane
     * stored them, each holding exactly what it was stored with, and each wand with the mirror it was stored with. The
     * item census holds on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = AISLE_TIMEOUT_TICKS)
    public static void componentMeaningToolboxStoresRequestsAndReloads(GameTestHelper helper) {
        assertMeaningsRegistered(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        Consumer<IItemHandler> eightDiamonds = handler -> insert(helper, handler, new ItemStack(Items.DIAMOND,
                DIAMONDS));
        ItemStack filteredOne = placedToolbox(helper, SPARE_A, TOOLBOX_UUID, eightDiamonds);
        ItemStack filteredTwo = placedToolbox(helper, SPARE_B, TOOLBOX_UUID, eightDiamonds);
        ItemStack filteredEmpty = placedToolbox(helper, SPARE_A, OTHER_UUID, handler -> {
            insert(helper, handler, new ItemStack(Items.DIAMOND, DIAMONDS));
            helper.assertValueEqual(handler.extractItem(0, DIAMONDS, false).getCount(), DIAMONDS,
                    "the diamonds taken out again");
        });
        ItemStack diamonds = codecToolbox(helper, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)), noFilters());
        ItemStack empty = codecToolbox(helper, Map.of(), noFilters());
        ItemKey filtered = ItemKey.of(filteredOne);
        ItemKey filteredNothing = ItemKey.of(filteredEmpty);
        ItemKey unfiltered = ItemKey.of(diamonds);
        ItemKey unfilteredNothing = ItemKey.of(empty);
        helper.assertTrue(filtered.equals(ItemKey.of(filteredTwo)), "the two copies are one key");
        ItemStack crossWand = wand(new CrossPlaneMirror(MIRROR_AT));
        ItemStack tripleWand = wand(new TriplePlaneMirror(MIRROR_AT));
        ItemKey cross = ItemKey.of(crossWand);
        ItemKey triple = ItemKey.of(tripleWand);
        helper.assertFalse(cross.equals(triple), "the two wands are two keys");

        aisle.storage(STORAGE_A, filteredOne);
        aisle.storage(STORAGE_B, filteredTwo);
        aisle.storage(STORAGE_C, filteredEmpty, crossWand, tripleWand);
        aisle.input(INPUT_RACK);
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> expected = ItemCensus.of(filtered, 2L, filteredNothing, 1L, cross, 1L, triple, 1L);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "toolboxes stored, reloaded and requested"));
        Map<ItemKey, Long> lines = new LinkedHashMap<>(Map.of(filtered, 2L, filteredNothing, 1L, cross, 1L, triple,
                1L));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(STORAGE.size(), 1, 1))
                .thenExecute(() -> {
                    assertStockLines(helper, aisle, lines, "before the motor runs");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(input, diamonds.copy());
                    aisle.insertAll(input, empty.copy());
                    ItemCensus.change(expected, unfiltered, 1);
                    ItemCensus.change(expected, unfilteredNothing, 1);
                    lines.put(unfiltered, 1L);
                    lines.put(unfilteredNothing, 1L);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(aisle.inputAt(INPUT_RACK).hasBufferedItems(), "the input was emptied");
                    aisle.assertIdleAndEmpty();
                    helper.assertValueEqual(aisle.controller().pendingSnapshotCount(), 0, "every location read");
                    helper.assertValueEqual(aisle.controller().countOf(unfiltered), 1L, "the diamonds indexed");
                    helper.assertValueEqual(aisle.controller().countOf(unfilteredNothing), 1L, "the empty one indexed");
                })
                .thenExecute(() -> {
                    assertStockLines(helper, aisle, lines, "after storing");
                    helper.assertValueEqual(aisle.controller().stockIndex().distinctKeys(), lines.size(),
                            "one key per line indexed");

                    // A restart: the chests and the controller saved and loaded, with the two copies still in two
                    // locations, as nothing has been requested yet.
                    for (RackPosition rack : STORAGE) {
                        BlockPos chestPos = aisle.inventoryPos(rack);
                        ChestBlockEntity chest = ComponentHashGameTests.reload(helper, helper.getBlockEntity(chestPos),
                                ChestBlockEntity.class);
                        helper.getLevel().setBlockEntity(chest);
                        helper.assertTrue(helper.getBlockEntity(chestPos) == chest,
                                "the chest at " + rack + " was replaced by the loaded one");
                    }
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseControllerBlockEntity copy = ComponentHashGameTests.reload(helper, controller,
                            WarehouseControllerBlockEntity.class);
                    helper.assertTrue(copy.stockIndex().locationsOf(filtered).size() >= 2,
                            "the copies lie in several locations: " + copy.stockIndex().locationsOf(filtered));
                    helper.assertValueEqual(copy.countOf(filtered), 2L, "the keys decoded per location merge");
                    helper.assertValueEqual(copy.stockIndex().distinctKeys(), lines.size(),
                            "the loaded stock: one key per line");
                    helper.getLevel().setBlockEntity(copy);
                    helper.assertTrue(controller.isRemoved() && aisle.controller() == copy,
                            "the controller block entity was replaced by the loaded one");
                })
                .thenWaitUntil(() -> aisle.assertReady(STORAGE.size(), 1, 1))
                .thenExecute(() -> {
                    assertStockLines(helper, aisle, lines, "once the reloaded chests are read");
                    request(helper, aisle, player, filtered, 2);
                })
                .thenWaitUntil(() -> assertDelivered(helper, aisle, filtered, 2))
                .thenExecute(() -> request(helper, aisle, player, unfiltered, 1))
                .thenWaitUntil(() -> assertDelivered(helper, aisle, unfiltered, 1))
                .thenExecute(() -> request(helper, aisle, player, unfilteredNothing, 1))
                .thenWaitUntil(() -> assertDelivered(helper, aisle, unfilteredNothing, 1))
                .thenExecute(() -> request(helper, aisle, player, filteredNothing, 1))
                .thenWaitUntil(() -> assertDelivered(helper, aisle, filteredNothing, 1))
                .thenExecute(() -> request(helper, aisle, player, triple, 1))
                .thenWaitUntil(() -> assertDelivered(helper, aisle, triple, 1))
                .thenExecute(() -> request(helper, aisle, player, cross, 1))
                .thenWaitUntil(() -> assertDelivered(helper, aisle, cross, 1))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.terminalAt(TERMINAL_RACK).stockSnapshot().size(), 0,
                            "every toolbox and wand was delivered");
                    IItemHandler buffer = aisle.terminalAt(TERMINAL_RACK).externalHandler();
                    assertBuffered(helper, buffer, unfiltered, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)), 1);
                    assertBuffered(helper, buffer, unfilteredNothing, Map.of(), 1);
                    assertBuffered(helper, buffer, filtered, Map.of(0, new ItemStack(Items.DIAMOND, DIAMONDS)), 2);
                    assertBuffered(helper, buffer, filteredNothing, Map.of(), 1);
                    assertBufferedWand(helper, buffer, triple, TriplePlaneMirror.class);
                    assertBufferedWand(helper, buffer, cross, CrossPlaneMirror.class);
                })
                .thenSucceed();
    }

    // --- assertions --------------------------------------------------------------------------------------------------

    /**
     * The three meanings are registered, and no other type has one. Checked first in every test here, so a Create
     * update that renamed one of {@code ToolboxInventory}'s private members fails with this message, not as an unequal
     * key further down.
     */
    private static void assertMeaningsRegistered(GameTestHelper helper) {
        helper.assertTrue(ComponentMeanings.of(AllDataComponents.TOOLBOX_INVENTORY) != null,
                "the toolbox meaning is registered: Create's private ToolboxInventory members did not resolve (see "
                        + "the \"Cannot read Create's ToolboxInventory#...\" warning in the log)");
        helper.assertTrue(ComponentMeanings.of(AllDataComponents.SAND_PAPER_POLISHING) != null,
                "the sand paper meaning is registered");
        helper.assertTrue(ComponentMeanings.of(AllDataComponents.SYMMETRY_WAND) != null,
                "the symmetry wand meaning is registered");
        helper.assertTrue(ComponentMeanings.of(DataComponents.CUSTOM_NAME) == null, "and nothing else");
    }

    /**
     * Two stacks are two keys, both ways, neither matching the other's stack, two entries in a set, and two hash codes:
     * what tells them apart is in the hash, not only in the equality (ADR-066).
     */
    private static void assertApart(GameTestHelper helper, String name, ItemStack one, ItemStack other) {
        ItemKey first = ItemKey.of(one);
        ItemKey second = ItemKey.of(other);
        helper.assertFalse(first.equals(second) || second.equals(first), name + ": different keys");
        helper.assertFalse(first.matches(other) || second.matches(one), name + ": neither matches the other");
        helper.assertValueEqual(new HashSet<>(List.of(first, second, ItemKey.of(one), ItemKey.of(other))).size(), 2,
                name + ": two entries in a set");
        helper.assertFalse(first.hashCode() == second.hashCode(), name + ": two hash codes");
    }

    /** The key of {@code stack} has the same text as the key loaded from its save, so {@code ItemKey.ORDER} holds. */
    private static void assertTextAfterASave(GameTestHelper helper, String name, ItemStack stack) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        ItemKey key = ItemKey.of(stack);
        ItemKey loaded = ItemKey.load(registries, key.save(registries))
                .orElseThrow(() -> new GameTestAssertException(name + ": the key did not load again"));
        helper.assertValueEqual(loaded.toString(), key.toString(), name + ": the key's text after a save");
    }

    /** Exactly the stock lines {@code lines}: one per key, with its total. */
    private static void assertStockLines(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> lines,
            String when) {
        List<TerminalStockEntry> stock = aisle.terminalAt(TERMINAL_RACK).stockSnapshot();
        helper.assertValueEqual(stock.size(), lines.size(), "one stock line per key " + when + ": " + stock);
        lines.forEach((key, total) -> helper.assertValueEqual(ComponentHashGameTests.entry(helper, stock, key).total(),
                total, key + " " + when));
    }

    /** A request as a client sends it, accepted for exactly {@code amount} of {@code key}. */
    private static void request(GameTestHelper helper, AisleFixture aisle, Player player, ItemKey key, int amount) {
        RequestResult result = ComponentHashGameTests.requestAsAClient(helper, aisle, player, key, amount);
        helper.assertTrue(result.isAccepted(), "the decoded key is in stock: " + key + ": " + result);
        RetrievalRequest<ItemKey, BlockPos> request = result.request().orElseThrow();
        helper.assertValueEqual(request.key(), key, "requested toolbox");
        helper.assertValueEqual(request.requested(), amount, "granted amount");
    }

    /** {@code amount} of {@code key} in the terminal's buffer, the request finished and the crane idle. */
    private static void assertDelivered(GameTestHelper helper, AisleFixture aisle, ItemKey key, int amount) {
        helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, key), (long) amount,
                "delivered into the terminal buffer: " + key);
        helper.assertTrue(aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                "the request is finished");
        aisle.assertIdleAndEmpty();
    }

    /**
     * The buffer holds {@code count} toolboxes of {@code key}, each a stack of one, and each holding exactly
     * {@code contents} (slot to stack, every other slot empty).
     */
    private static void assertBuffered(GameTestHelper helper, IItemHandler buffer, ItemKey key,
            Map<Integer, ItemStack> contents, int count) {
        int found = 0;
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            if (!key.matches(stack))
                continue;
            helper.assertValueEqual(stack.getCount(), 1, "one toolbox per slot: " + key);
            ToolboxInventory inventory = stack.get(AllDataComponents.TOOLBOX_INVENTORY);
            helper.assertTrue(inventory != null, "a toolbox inventory: " + stack);
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack expected = contents.getOrDefault(i, ItemStack.EMPTY);
                helper.assertTrue(ItemStack.matches(inventory.getStackInSlot(i), expected),
                        "toolbox slot " + i + " holds " + expected + " but has " + inventory.getStackInSlot(i));
            }
            found++;
        }
        helper.assertValueEqual(found, count, "toolboxes of " + key + " in the buffer");
    }

    /** The buffer holds one wand of {@code key}, its mirror of {@code kind} where the wands' mirrors stand. */
    private static void assertBufferedWand(GameTestHelper helper, IItemHandler buffer, ItemKey key,
            Class<? extends SymmetryMirror> kind) {
        int found = 0;
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            if (!stack.is(AllItems.WAND_OF_SYMMETRY.get()))
                continue;
            SymmetryMirror mirror = stack.get(AllDataComponents.SYMMETRY_WAND);
            if (mirror == null || mirror.getClass() != kind)
                continue;
            helper.assertTrue(key.matches(stack), "a wand of the key: " + stack);
            helper.assertValueEqual(mirror.getPosition(), MIRROR_AT, "the mirror stands where it stood");
            found++;
        }
        helper.assertValueEqual(found, 1, kind.getSimpleName() + " wands in the buffer");
    }

    // --- toolboxes ---------------------------------------------------------------------------------------------------

    /**
     * A toolbox as its loot table takes it: a brown toolbox placed at {@code pos} with {@code uuid}, filled through
     * Create's own item handler by {@code fill} (Create sets each filter from the first item that enters a
     * compartment), and its item with the block entity's components — its uuid, then the live inventory itself, in the
     * order the block entity gives them, as a loot drop has them. The block is removed again.
     */
    private static ItemStack placedToolbox(GameTestHelper helper, BlockPos pos, UUID uuid,
            Consumer<IItemHandler> fill) {
        helper.setBlock(pos, AllBlocks.TOOLBOXES.get(DyeColor.BROWN).getDefaultState());
        BlockEntity toolbox = helper.getBlockEntity(pos);
        if (!(toolbox instanceof ToolboxBlockEntity placed))
            throw new GameTestAssertException("a placed toolbox has its block entity: " + toolbox);
        placed.setUniqueId(uuid);
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null)
            throw new GameTestAssertException("a placed toolbox has an item handler");
        fill.accept(handler);
        ItemStack stack = AllBlocks.TOOLBOXES.get(DyeColor.BROWN).asStack();
        stack.applyComponents(toolbox.collectComponents());
        helper.assertTrue(stack.get(AllDataComponents.TOOLBOX_INVENTORY) == handler,
                "the item holds the block entity's live inventory, as its loot table copies it");
        helper.assertValueEqual(stack.get(AllDataComponents.TOOLBOX_UUID), uuid, "and the block entity's uuid");
        helper.setBlock(pos, Blocks.AIR);
        return stack;
    }

    /**
     * A brown toolbox whose inventory Create's own codec decoded: {@code slots} by slot index and one filter per
     * compartment, no block entity and no uuid — as a command or a datapack gives one.
     */
    private static ItemStack codecToolbox(GameTestHelper helper, Map<Integer, ItemStack> slots,
            List<ItemStack> filters) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ItemSlots itemSlots = new ItemSlots();
        itemSlots.setSize(SLOTS);
        slots.forEach((slot, stack) -> itemSlots.set(slot, stack.copy()));
        CompoundTag tag = new CompoundTag();
        tag.put("items", ItemSlots.CODEC.encodeStart(ops, itemSlots).getOrThrow());
        tag.put("filters", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, filters).getOrThrow());
        ItemStack toolbox = AllBlocks.TOOLBOXES.get(DyeColor.BROWN).asStack();
        toolbox.set(AllDataComponents.TOOLBOX_INVENTORY, ToolboxInventory.CODEC.parse(ops, tag).getOrThrow());
        return toolbox;
    }

    /** Eight empty filters. */
    private static List<ItemStack> noFilters() {
        return filters();
    }

    /** {@code first} as the filters of the first compartments, the rest empty, eight in all. */
    private static List<ItemStack> filters(ItemStack... first) {
        List<ItemStack> filters = new ArrayList<>(List.of(first));
        while (filters.size() < COMPARTMENTS)
            filters.add(ItemStack.EMPTY);
        return filters;
    }

    /**
     * A wand of symmetry holding {@code mirror}, switched on, as using it on a block leaves it. Built as loading a
     * saved wand builds it, from a decoded patch: {@code ItemStack#set} is checked by NeoForge in a development run,
     * and on a dedicated server that check cannot resolve Create's mirror classes (their public
     * {@code applyModelTransform} names the client's {@code PoseStack}).
     */
    private static ItemStack wand(SymmetryMirror mirror) {
        CompoundTag components = new CompoundTag();
        components.put("create:symmetry_wand", SymmetryMirror.CODEC.encodeStart(NbtOps.INSTANCE, mirror).getOrThrow());
        components.putBoolean("create:symmetry_wand_enable", true);
        ItemStack wand = AllItems.WAND_OF_SYMMETRY.asStack();
        wand.applyComponents(DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, components).getOrThrow());
        return wand;
    }

    /** A plane mirror at {@link #MIRROR_AT} in the orientation {@code align}. */
    private static PlaneMirror plane(PlaneMirror.Align align) {
        PlaneMirror mirror = new PlaneMirror(MIRROR_AT);
        mirror.setOrientation(align.ordinal());
        return mirror;
    }

    /** A sand paper paused while polishing {@code item}, as switching the hotbar slot mid-use leaves it. */
    private static ItemStack polishing(ItemStack item) {
        ItemStack paper = AllItems.SAND_PAPER.asStack();
        paper.set(AllDataComponents.SAND_PAPER_POLISHING, new SandPaperItemComponent(item));
        return paper;
    }

    private static void insert(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        helper.assertTrue(ItemHandlerHelper.insertItem(handler, stack, false).isEmpty(),
                "the toolbox took " + stack);
    }
}
