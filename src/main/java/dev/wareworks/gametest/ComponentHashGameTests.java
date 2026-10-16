package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import it.unimi.dsi.fastutil.ints.IntList;

import com.mojang.authlib.properties.PropertyMap;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crafting.IngredientPicker;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.network.TerminalRequestPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DebugStickState;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the <b>contract-safe key hash</b> (issue #27, ADR-064): an item or fluid key hashes only what its
 * equality is known to compare, so equal keys hash equally whatever mod defined their components. The trap is
 * reproduced without Quark by the dev-only component {@link IdentityHashedStack} — Quark 4.1's seed pouch contents:
 * equal by value, hashed by the identity of an inner stack — and by the cases vanilla and NeoForge break themselves
 * ({@code FoodProperties}, a name's colour and hover item, the stack containers).
 * <ul>
 * <li>{@code componenthashequalkeys} — for a stack of every case, the key, the key of an equal stack built apart, the
 * key after an NBT round trip and the key after a network round trip are equal both ways, hash equally and are found in
 * hash maps and sets; different pouch contents are different keys; the types counted alone really are;</li>
 * <li>{@code componenthashvanillaspreads} — every potion and every enchanted book hashes apart, so do player heads that
 * differ only in their texture, Create's clipboards that differ only in their text and its sequenced assembly steps,
 * and the picker keeps every potion of a cell;</li>
 * <li>{@code componenthashfluidkeys} — the same contract for fluid keys, and for an item holding a modded tank's
 * {@link SimpleFluidContent};</li>
 * <li>{@code componenthashstoresrequestsandreloads} — pouches built apart are stored, one of them through an input by
 * the crane, and show one stock line per content; the storage chests and the controller are saved and loaded while
 * one content lies in several locations, and the loaded index merges the keys it decoded per location into one total
 * and one line; the pouches are then requested with a key that went through the request payload's codec and
 * delivered into the terminal's buffer; the item census holds on every tick.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ComponentHashGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int AISLE_TIMEOUT_TICKS = 3600;

    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition STORAGE_A = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_B = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition STORAGE_C = new RackPosition(3, 0, Side.LEFT);
    private static final int STORAGE_LOCATIONS = 3;

    /** Seeds in one pouch, as a filled Quark pouch holds them. */
    private static final int POUCH_SEEDS = 16;
    /** Wheat-seed pouches in the aisle test: two seeded into storage, one fed through the input. */
    private static final int WHEAT_POUCHES = 3;
    private static final int REQUESTED_WHEAT_POUCHES = 2;

    private ComponentHashGameTests() {
    }

    // --- the contract ----------------------------------------------------------------------------------------------

    /** One case: a stack, and an equal stack built apart (by default the same recipe run again). */
    private record Sample(String name, Supplier<ItemStack> build, Supplier<ItemStack> twin) {
        Sample(String name, Supplier<ItemStack> build) {
            this(name, build, build);
        }
    }

    /**
     * <b>Equal keys hash equally, whatever made them.</b> For a stack of every case — the test component on its own,
     * inside a shulker box, a bundle, a crossbow and a name's hover item; a patched {@code food} with
     * {@code using_converts_to}; a name in a named colour against the same colour as a value ({@code red} and
     * {@code #FF5555}); custom data; enchantments, stored enchantments, potions, stews, both books, rockets and stars,
     * banners, trims, horns, maps, lodestones, a profile by name and one by its texture alone, lore, bees, a removed
     * prototype component, the four types that count by their type alone, and Create's written clipboard (one entry
     * showing a pouch as its icon), sequenced assembly step and package order data — the key, the key of an equal
     * stack built apart, the key loaded from its save and the key decoded from its network form are equal both ways,
     * hash equally and are found in a {@code HashMap} and a {@code HashSet}. Different pouch contents are different
     * keys, and so are different values of the types counted alone, which share one hash code by design.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void componentHashEqualKeys(GameTestHelper helper) {
        for (Sample sample : samples(helper))
            assertOneKey(helper, sample.name(), sample.build().get(), sample.twin().get());

        ItemKey wheat = ItemKey.of(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS));
        ItemKey pumpkin = ItemKey.of(pouch(Items.PUMPKIN_SEEDS, POUCH_SEEDS));
        ItemKey fewer = ItemKey.of(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS / 2));
        helper.assertFalse(wheat.equals(pumpkin), "other seeds in the pouch, another key");
        helper.assertFalse(wheat.equals(fewer), "fewer seeds in the pouch, another key");
        helper.assertValueEqual(wheat.hashCode(), pumpkin.hashCode(),
                "a mod's value counts by its type alone, so pouches share a hash code and equals tells them apart");
        Set<ItemKey> pouches = new HashSet<>(List.of(wheat, pumpkin, fewer, ItemKey.of(pouch(Items.WHEAT_SEEDS,
                POUCH_SEEDS))));
        helper.assertValueEqual(pouches.size(), 3, "three contents, three keys in a set");

        // The four vanilla types counted by their type alone: different values, unequal keys, one hash code.
        assertTypeOnly(helper, "tool", tool(4.0f), tool(8.0f));
        assertTypeOnly(helper, "can_break", canBreak(true), canBreak(false));
        assertTypeOnly(helper, "can_place_on", canPlaceOn(true), canPlaceOn(false));
        assertTypeOnly(helper, "debug_stick_state", debugStick(BlockStateProperties.AXIS),
                debugStick(BlockStateProperties.FACING));
        helper.succeed();
    }

    /**
     * <b>Vanilla and Create variety still spreads.</b> Every potion of the registry is its own hash code, so is an
     * enchanted book of every enchantment, a player head of every texture without a name, a Create clipboard of every
     * text and every step of a sequenced assembly; and the picker keeps a cell that lists every potion whole: whichever
     * potion the buffer holds is picked.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void componentHashVanillaSpreads(GameTestHelper helper) {
        List<ItemKey> potions = BuiltInRegistries.POTION.holders()
                .map(potion -> ItemKey.of(PotionContents.createItemStack(Items.POTION, potion))).toList();
        helper.assertTrue(potions.size() > IngredientPicker.MAX_SAME_HASH, "more potions than the picker's bound");
        helper.assertTrue(potions.size() <= IngredientPicker.MAX_CANDIDATES, "and no more than one cell considers");
        helper.assertValueEqual(potions.stream().mapToInt(ItemKey::hashCode).distinct().count(), (long) potions.size(),
                "every potion hashes apart");
        helper.assertValueEqual(Set.copyOf(potions).size(), potions.size(), "and is a key of its own");

        List<ItemKey> books = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
                .map(enchantment -> ItemKey.of(EnchantedBookItem.createForEnchantment(
                        new EnchantmentInstance(enchantment, 1))))
                .toList();
        helper.assertTrue(books.size() > IngredientPicker.MAX_SAME_HASH, "more enchantments than the picker's bound");
        helper.assertValueEqual(books.stream().mapToInt(ItemKey::hashCode).distinct().count(), (long) books.size(),
                "an enchanted book of every enchantment hashes apart");

        assertSpread(helper, "a head of every texture, without a name", 32, i -> head("texture-" + i));
        assertSpread(helper, "a clipboard of every text", 64, i -> clipboard("line " + i));
        assertSpread(helper, "every step of a sequenced assembly", 16, ComponentHashGameTests::assemblyStep);

        for (ItemKey held : potions) {
            List<List<ItemKey>> cells = new ArrayList<>(List.of(List.of(ItemKey.of(Items.ARROW))));
            cells.add(potions);
            CraftGrid<ItemKey> picked = IngredientPicker.pick(cells,
                    IngredientPicker.Supply.of(Map.of(held, 1L), Map.of(), Set.of()));
            helper.assertValueEqual(picked.cell(1), Optional.of(held), "the picker keeps every potion of a cell");
        }
        helper.succeed();
    }

    /**
     * <b>Fluid keys keep the same contract.</b> A fluid carrying the test component and one carrying potion contents,
     * each built apart and round-tripped through the fluid key's codecs, give equal keys with equal hash codes; and an
     * item holding a fluid the way a modded tank's item does — NeoForge's {@link SimpleFluidContent}, which hashes its
     * stack with {@code FluidStack#hashFluidAndComponents} — keeps it as well.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void componentHashFluidKeys(GameTestHelper helper) {
        assertOneFluidKey(helper, "fluid with the test component", pouchWater(), pouchWater());
        assertOneFluidKey(helper, "fluid with potion contents", potionWater(), potionWater());
        helper.assertFalse(FluidKey.of(pouchWater()).equals(FluidKey.of(new FluidStack(Fluids.WATER, 1))),
                "the component is part of the fluid key");

        assertOneKey(helper, "a tank item holding such a fluid", tank(pouchWater()), tank(pouchWater()));
        assertOneKey(helper, "a tank item holding potion water", tank(potionWater()), tank(potionWater()));
        helper.assertFalse(ItemKey.of(tank(pouchWater())).equals(ItemKey.of(tank(potionWater()))),
                "another fluid in the tank, another key");
        helper.succeed();
    }

    // --- the warehouse ---------------------------------------------------------------------------------------------

    /**
     * <b>Store, reload, request and retrieve.</b> Two pouches of wheat seeds built apart are seeded into two storage
     * locations, and a third one and a pouch of pumpkin seeds are fed into the input, from where the crane stores them.
     * The terminal shows <b>one</b> line per content — three wheat pouches, one pumpkin pouch — where the old hash made
     * every pouch a line of its own. Then the storage chests and the controller are saved and loaded as after a
     * restart, while the wheat pouches lie in at least two locations: the loaded index holds a key decoded from the
     * save per location, and they must merge into one total of three and one stock line, also once the reloaded chests
     * are read again. Two wheat pouches are requested through the terminal menu's request entry with the key a client
     * sends, i.e. after the request payload's codec, and the crane delivers them into the terminal's buffer; the
     * pumpkin pouch follows the same way. The item census holds on every tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = AISLE_TIMEOUT_TICKS)
    public static void componentHashStoresRequestsAndReloads(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        ItemKey wheat = ItemKey.of(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS));
        ItemKey pumpkin = ItemKey.of(pouch(Items.PUMPKIN_SEEDS, POUCH_SEEDS));
        aisle.storage(STORAGE_A, pouch(Items.WHEAT_SEEDS, POUCH_SEEDS));
        aisle.storage(STORAGE_B, pouch(Items.WHEAT_SEEDS, POUCH_SEEDS));
        aisle.storage(STORAGE_C);
        aisle.input(INPUT_RACK);
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> expected = ItemCensus.of(wheat, (long) (WHEAT_POUCHES - 1));
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "pouches stored, requested and reloaded"));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(STORAGE_LOCATIONS, 1, 1))
                .thenExecute(() -> {
                    List<TerminalStockEntry> stock = aisle.terminalAt(TERMINAL_RACK).stockSnapshot();
                    helper.assertValueEqual(stock.size(), 1, "two pouches built apart in two locations: one line");
                    helper.assertValueEqual(entry(helper, stock, wheat).total(), (long) (WHEAT_POUCHES - 1),
                            "holding both");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT_RACK));
                    aisle.insertAll(input, pouch(Items.WHEAT_SEEDS, POUCH_SEEDS));
                    aisle.insertAll(input, pouch(Items.PUMPKIN_SEEDS, POUCH_SEEDS));
                    ItemCensus.change(expected, wheat, 1);
                    ItemCensus.change(expected, pumpkin, 1);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(aisle.inputAt(INPUT_RACK).hasBufferedItems(), "the input was emptied");
                    aisle.assertIdleAndEmpty();
                    helper.assertValueEqual(aisle.controller().pendingSnapshotCount(), 0, "every location read");
                    helper.assertValueEqual(aisle.controller().countOf(wheat), (long) WHEAT_POUCHES, "wheat indexed");
                    helper.assertValueEqual(aisle.controller().countOf(pumpkin), 1L, "pumpkin indexed");
                })
                .thenExecute(() -> {
                    List<TerminalStockEntry> stock = aisle.terminalAt(TERMINAL_RACK).stockSnapshot();
                    helper.assertValueEqual(stock.size(), 2, "one stock line per content: " + stock);
                    helper.assertValueEqual(entry(helper, stock, wheat).total(), (long) WHEAT_POUCHES,
                            "every wheat pouch on one line");
                    helper.assertValueEqual(entry(helper, stock, pumpkin).total(), 1L, "the pumpkin pouch on its own");
                    helper.assertValueEqual(aisle.controller().stockIndex().distinctKeys(), 2, "two keys indexed");

                    // A restart: the chests and the controller saved and loaded. Nothing has left the racks yet, so
                    // the two seeded wheat pouches still lie in two locations, whatever the crane did with the third.
                    for (RackPosition rack : List.of(STORAGE_A, STORAGE_B, STORAGE_C)) {
                        BlockPos chestPos = aisle.inventoryPos(rack);
                        ChestBlockEntity chest = reload(helper, helper.getBlockEntity(chestPos),
                                ChestBlockEntity.class);
                        helper.getLevel().setBlockEntity(chest);
                        helper.assertTrue(helper.getBlockEntity(chestPos) == chest,
                                "the chest at " + rack + " was replaced by the loaded one");
                    }
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    WarehouseControllerBlockEntity copy = reload(helper, controller,
                            WarehouseControllerBlockEntity.class);
                    helper.assertTrue(copy.stockIndex().locationsOf(wheat).size() >= 2,
                            "the saved wheat pouches lie in several locations: "
                                    + copy.stockIndex().locationsOf(wheat));
                    helper.assertValueEqual(copy.countOf(wheat), (long) WHEAT_POUCHES,
                            "the keys decoded per location merge into one total");
                    helper.assertValueEqual(copy.countOf(pumpkin), 1L, "and the pumpkin pouch");
                    helper.assertValueEqual(copy.stockIndex().distinctKeys(), 2, "the loaded stock: two keys");
                    helper.getLevel().setBlockEntity(copy);
                    helper.assertTrue(controller.isRemoved() && aisle.controller() == copy,
                            "the controller block entity was replaced by the loaded one");
                })
                .thenWaitUntil(() -> aisle.assertReady(STORAGE_LOCATIONS, 1, 1))
                .thenExecute(() -> {
                    List<TerminalStockEntry> stock = aisle.terminalAt(TERMINAL_RACK).stockSnapshot();
                    helper.assertValueEqual(stock.size(), 2,
                            "still one stock line per content once the reloaded chests are read: " + stock);
                    helper.assertValueEqual(entry(helper, stock, wheat).total(), (long) WHEAT_POUCHES,
                            "every wheat pouch on one line after the load");
                    helper.assertValueEqual(aisle.controller().countOf(pumpkin), 1L,
                            "the pumpkin pouch after the load");
                    RequestResult result = requestAsAClient(helper, aisle, player, wheat, REQUESTED_WHEAT_POUCHES);
                    helper.assertTrue(result.isAccepted(), "the decoded wheat key is in the loaded stock: " + result);
                    RetrievalRequest<ItemKey, BlockPos> request = result.request().orElseThrow();
                    helper.assertValueEqual(request.key(), wheat, "requested item");
                    helper.assertValueEqual(request.requested(), REQUESTED_WHEAT_POUCHES, "granted amount");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, wheat), (long) REQUESTED_WHEAT_POUCHES,
                            "delivered into the terminal buffer");
                    helper.assertTrue(aisle.controller().requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                            "the request is finished");
                    aisle.assertIdleAndEmpty();
                    helper.assertValueEqual(aisle.controller().pendingSnapshotCount(), 0, "every location read again");
                })
                .thenExecute(() -> {
                    List<TerminalStockEntry> stock = aisle.terminalAt(TERMINAL_RACK).stockSnapshot();
                    helper.assertValueEqual(stock.size(), 2, "one line per content after the request: " + stock);
                    helper.assertValueEqual(entry(helper, stock, wheat).total(),
                            (long) (WHEAT_POUCHES - REQUESTED_WHEAT_POUCHES), "the wheat pouch left");
                    RequestResult result = requestAsAClient(helper, aisle, player, pumpkin, 1);
                    helper.assertTrue(result.isAccepted(), "the decoded pumpkin key is in the loaded stock: " + result);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, pumpkin), 1L,
                            "the pumpkin pouch delivered after the load");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * What a restart makes of {@code saved}: a block entity of the same type loaded from its full save. Not yet in the
     * level; the caller puts it there.
     */
    private static <T extends BlockEntity> T reload(GameTestHelper helper, BlockEntity saved, Class<T> type) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = saved.saveWithFullMetadata(registries);
        BlockEntity loaded = BlockEntity.loadStatic(saved.getBlockPos(), saved.getBlockState(), tag, registries);
        if (!type.isInstance(loaded))
            throw new GameTestAssertException("a saved " + type.getSimpleName() + " must load again as one: " + loaded);
        return type.cast(loaded);
    }

    // --- assertions ------------------------------------------------------------------------------------------------

    /**
     * Fails unless the key of {@code built}, the key of {@code builtApart}, the key loaded from the first one's save
     * and the key decoded from its network form are equal both ways, hash equally and are found in a hash map and a
     * hash set, and unless the patch is part of the key at all.
     */
    private static void assertOneKey(GameTestHelper helper, String name, ItemStack built, ItemStack builtApart) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        ItemKey key = ItemKey.of(built);
        ItemKey apart = ItemKey.of(builtApart);
        ItemKey saved = ItemKey.load(registries, key.save(registries))
                .orElseThrow(() -> new GameTestAssertException(name + ": the key did not load again"));
        ItemKey savedApart = ItemKey.load(registries, apart.save(registries))
                .orElseThrow(() -> new GameTestAssertException(name + ": the twin did not load again"));
        ItemKey sent = TerminalCraftGameTests.roundTrip(helper, key, ItemKey.STREAM_CODEC);
        assertAllEqual(helper, name, List.of(key, apart, saved, savedApart, sent));
        helper.assertFalse(key.equals(ItemKey.of(built.getItem())), name + ": the patch is part of the key");
    }

    /** {@link #assertOneKey} for fluid keys, through {@link FluidKey}'s codecs. */
    private static void assertOneFluidKey(GameTestHelper helper, String name, FluidStack built, FluidStack builtApart) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        FluidKey key = FluidKey.of(built);
        FluidKey apart = FluidKey.of(builtApart);
        FluidKey saved = FluidKey.load(registries, key.save(registries))
                .orElseThrow(() -> new GameTestAssertException(name + ": the key did not load again"));
        FluidKey sent = TerminalCraftGameTests.roundTrip(helper, key, FluidKey.STREAM_CODEC);
        assertAllEqual(helper, name, List.of(key, apart, saved, sent));
    }

    /** Every pair equal both ways with one hash code, and each one found where the first was put. */
    private static <K> void assertAllEqual(GameTestHelper helper, String name, List<K> keys) {
        Map<K, String> map = new HashMap<>();
        map.put(keys.getFirst(), name);
        Set<K> set = new HashSet<>();
        set.add(keys.getFirst());
        for (int i = 0; i < keys.size(); i++) {
            K one = keys.get(i);
            for (int j = 0; j < keys.size(); j++) {
                K other = keys.get(j);
                if (!one.equals(other))
                    helper.fail(name + ": key " + i + " does not equal key " + j + ": " + one + " / " + other);
                if (one.hashCode() != other.hashCode())
                    helper.fail(name + ": keys " + i + " and " + j + " are equal but hash apart: " + one);
            }
            helper.assertTrue(map.containsKey(one), name + ": key " + i + " is found in a hash map");
            helper.assertTrue(set.contains(one), name + ": key " + i + " is found in a hash set");
        }
    }

    /** {@code count} different stacks, each a key with a hash code of its own. */
    private static void assertSpread(GameTestHelper helper, String name, int count, IntFunction<ItemStack> stack) {
        List<ItemKey> keys = IntStream.range(0, count).mapToObj(i -> ItemKey.of(stack.apply(i))).toList();
        helper.assertValueEqual(Set.copyOf(keys).size(), count, name + ": different keys");
        helper.assertValueEqual(keys.stream().mapToInt(ItemKey::hashCode).distinct().count(), (long) count,
                name + ": each with a hash code of its own");
    }

    /** Two different values of a type counted alone: unequal keys with one hash code, each found in a set. */
    private static void assertTypeOnly(GameTestHelper helper, String name, ItemStack one, ItemStack other) {
        ItemKey first = ItemKey.of(one);
        ItemKey second = ItemKey.of(other);
        helper.assertFalse(first.equals(second), name + ": different values, different keys");
        helper.assertValueEqual(first.hashCode(), second.hashCode(), name + ": counted by its type alone");
        Set<ItemKey> set = new HashSet<>(List.of(first, second));
        helper.assertTrue(set.size() == 2 && set.contains(ItemKey.of(one)) && set.contains(ItemKey.of(other)),
                name + ": equals tells them apart in a set");
    }

    /** The stock line of {@code key}; fails the test when there is none. */
    private static TerminalStockEntry entry(GameTestHelper helper, List<TerminalStockEntry> stock, ItemKey key) {
        return stock.stream().filter(line -> line.key().equals(key)).findFirst()
                .orElseThrow(() -> new GameTestAssertException("no stock line for " + key + " in " + stock));
    }

    /**
     * A request the way a client's click arrives: the request payload through its network codec, so the key is a new
     * instance decoded from bytes, then the menu's payload entry with a terminal menu open at the terminal.
     */
    private static RequestResult requestAsAClient(GameTestHelper helper, AisleFixture aisle, Player player,
            ItemKey key, int amount) {
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
        Vec3 center = Vec3.atCenterOf(aisle.absoluteRackPos(TERMINAL_RACK));
        player.moveTo(center.x, center.y, center.z);
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        TerminalRequestPayload payload = TerminalCraftGameTests.roundTrip(helper,
                new TerminalRequestPayload(menu.containerId, key, amount), TerminalRequestPayload.STREAM_CODEC);
        helper.assertFalse(payload.key() == key, "the request carries a decoded key, not the stored instance");
        Optional<TerminalRequestOutcome> outcome = WarehouseTerminalMenu.submitRequest(player, payload.containerId(),
                payload.key(), payload.amount(), payload.acknowledged());
        return outcome.flatMap(TerminalRequestOutcome::result)
                .orElseThrow(() -> new GameTestAssertException("the request was dropped or asked a question"));
    }

    // --- samples ---------------------------------------------------------------------------------------------------

    /**
     * A filled pouch shaped like Quark's: one unstackable paper carrying {@link IdentityHashedStack} around
     * {@code count} of {@code contents}. Every call builds a new inner stack, i.e. a pouch built apart.
     */
    static ItemStack pouch(ItemLike contents, int count) {
        ItemStack pouch = new ItemStack(Items.PAPER);
        pouch.set(DataComponents.MAX_STACK_SIZE, 1);
        pouch.set(GameTestComponents.IDENTITY_HASHED_STACK.get(),
                new IdentityHashedStack(new ItemStack(contents, count)));
        return pouch;
    }

    private static List<Sample> samples(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Holder<Enchantment> sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        Holder<Enchantment> unbreaking = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
        List<Sample> samples = new ArrayList<>();
        samples.add(new Sample("the test component", () -> pouch(Items.WHEAT_SEEDS, POUCH_SEEDS)));
        samples.add(new Sample("a pouch inside a shulker box", () -> with(Items.SHULKER_BOX, DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS), ItemStack.EMPTY,
                        new ItemStack(Items.DIAMOND, 3))))));
        samples.add(new Sample("a pouch inside a bundle", () -> with(Items.BUNDLE, DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS))))));
        samples.add(new Sample("an arrow with the test component in a crossbow", () -> {
            ItemStack arrow = new ItemStack(Items.ARROW);
            arrow.set(GameTestComponents.IDENTITY_HASHED_STACK.get(), new IdentityHashedStack(
                    new ItemStack(Items.WHEAT_SEEDS, POUCH_SEEDS)));
            return with(Items.CROSSBOW, DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(arrow)));
        }));
        samples.add(new Sample("a pouch as a name's hover item", () -> with(Items.PAPER, DataComponents.CUSTOM_NAME,
                Component.literal("Pouch").withStyle(style -> style.withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(
                                pouch(Items.WHEAT_SEEDS, POUCH_SEEDS))))))));
        samples.add(new Sample("food with using_converts_to", () -> with(Items.BREAD, DataComponents.FOOD,
                new FoodProperties(5, 6.0f, false, 1.6f, Optional.of(new ItemStack(Items.BOWL)), List.of()))));
        samples.add(new Sample("a name in red and in #FF5555",
                () -> with(Items.PAPER, DataComponents.CUSTOM_NAME, Component.literal("Pouch")
                        .withStyle(ChatFormatting.RED)),
                () -> with(Items.PAPER, DataComponents.CUSTOM_NAME, Component.literal("Pouch").withColor(0xFF5555))));
        samples.add(new Sample("custom data", () -> customData(1.5f, 2.5)));
        samples.add(new Sample("damage", () -> with(Items.IRON_SWORD, DataComponents.DAMAGE, 5)));
        samples.add(new Sample("enchantments", () -> {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(sharpness, 3);
            sword.enchant(unbreaking, 2);
            return sword;
        }, () -> {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(unbreaking, 2);
            sword.enchant(sharpness, 3);
            return sword;
        }));
        samples.add(new Sample("stored enchantments", () -> EnchantedBookItem.createForEnchantment(
                new EnchantmentInstance(sharpness, 3))));
        samples.add(new Sample("potion contents", () -> with(Items.POTION, DataComponents.POTION_CONTENTS,
                new PotionContents(Optional.of(Potions.STRONG_HEALING), Optional.of(0xFF00FF),
                        List.of(new MobEffectInstance(MobEffects.NIGHT_VISION, 200, 1))))));
        samples.add(new Sample("suspicious stew", () -> with(Items.SUSPICIOUS_STEW,
                DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(
                        new SuspiciousStewEffects.Entry(MobEffects.NIGHT_VISION, 100))))));
        samples.add(new Sample("written book", () -> with(Items.WRITTEN_BOOK, DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("Seeds"), "Quark", 0, List.of(
                        Filterable.<Component>passThrough(Component.literal("One pouch, ")
                                .append(Component.literal("16 seeds")))),
                        true))));
        samples.add(new Sample("writable book", () -> with(Items.WRITABLE_BOOK, DataComponents.WRITABLE_BOOK_CONTENT,
                new WritableBookContent(List.of(Filterable.passThrough("page one"))))));
        samples.add(new Sample("firework star", () -> with(Items.FIREWORK_STAR, DataComponents.FIREWORK_EXPLOSION,
                explosion())));
        samples.add(new Sample("firework rocket", () -> with(Items.FIREWORK_ROCKET, DataComponents.FIREWORKS,
                new Fireworks(2, List.of(explosion())))));
        samples.add(new Sample("banner patterns", () -> with(Items.WHITE_BANNER, DataComponents.BANNER_PATTERNS,
                new BannerPatternLayers.Builder().add(registries.lookupOrThrow(Registries.BANNER_PATTERN)
                        .getOrThrow(BannerPatterns.STRIPE_TOP), DyeColor.RED).build())));
        samples.add(new Sample("armour trim", () -> with(Items.IRON_CHESTPLATE, DataComponents.TRIM, new ArmorTrim(
                registries.lookupOrThrow(Registries.TRIM_MATERIAL).getOrThrow(TrimMaterials.GOLD),
                registries.lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(TrimPatterns.COAST)))));
        samples.add(new Sample("goat horn instrument", () -> with(Items.GOAT_HORN, DataComponents.INSTRUMENT,
                BuiltInRegistries.INSTRUMENT.getHolderOrThrow(Instruments.PONDER_GOAT_HORN))));
        samples.add(new Sample("map id", () -> with(Items.FILLED_MAP, DataComponents.MAP_ID, new MapId(42))));
        samples.add(new Sample("lodestone", () -> with(Items.COMPASS, DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, -3))), true))));
        samples.add(new Sample("profile by name", () -> with(Items.PLAYER_HEAD, DataComponents.PROFILE,
                new ResolvableProfile(Optional.of("Steve"), Optional.empty(), new PropertyMap()))));
        samples.add(new Sample("profile by its texture alone", () -> head("pouch-texture")));
        samples.add(new Sample("lore", () -> with(Items.PAPER, DataComponents.LORE, new ItemLore(List.of(
                Component.literal("16 wheat seeds"), Component.translatable("item.minecraft.wheat_seeds"))))));
        samples.add(new Sample("bees", () -> bees(90.0f)));
        samples.add(new Sample("a removed prototype component", () -> {
            ItemStack bread = new ItemStack(Items.BREAD);
            bread.remove(DataComponents.FOOD);
            return bread;
        }));
        samples.add(new Sample("tool", () -> tool(4.0f)));
        samples.add(new Sample("can_break", () -> canBreak(true)));
        samples.add(new Sample("can_place_on", () -> canPlaceOn(true)));
        samples.add(new Sample("debug_stick_state", () -> debugStick(BlockStateProperties.AXIS)));
        samples.add(new Sample("a written Create clipboard, one entry showing a pouch", () -> clipboard("16 seeds")));
        samples.add(new Sample("a sequenced assembly step", () -> assemblyStep(2)));
        samples.add(new Sample("a package's order data", () -> with(Items.PAPER, AllDataComponents.PACKAGE_ORDER_DATA,
                new PackageItem.PackageOrderData(7, 1, false, 3, true, Optional.empty()))));
        return samples;
    }

    private static <T> ItemStack with(ItemLike item, DataComponentType<T> type, T value) {
        ItemStack stack = new ItemStack(item);
        stack.set(type, value);
        return stack;
    }

    /** Custom data holding a float, a double and a string. */
    private static ItemStack customData(float f, double d) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("f", f);
        tag.putDouble("d", d);
        tag.putString("s", "pouch");
        return with(Items.PAPER, DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static ItemStack bees(float rotation) {
        CompoundTag bee = new CompoundTag();
        bee.putString("id", "minecraft:bee");
        bee.putFloat("Rotation", rotation);
        return with(Items.BEE_NEST, DataComponents.BEES, List.of(new BeehiveBlockEntity.Occupant(CustomData.of(bee),
                100, 600)));
    }

    private static FireworkExplosion explosion() {
        return new FireworkExplosion(FireworkExplosion.Shape.STAR, IntList.of(0xFF0000), IntList.of(0x00FF00), true,
                false);
    }

    private static ItemStack tool(float speed) {
        return with(Items.STICK, DataComponents.TOOL, new Tool(List.of(Tool.Rule.minesAndDrops(List.of(Blocks.STONE),
                speed)), 1.0f, 1));
    }

    private static ItemStack canBreak(boolean showInTooltip) {
        return with(Items.DIAMOND_PICKAXE, DataComponents.CAN_BREAK, new AdventureModePredicate(List.of(
                BlockPredicate.Builder.block().of(Blocks.STONE).build()), showInTooltip));
    }

    private static ItemStack canPlaceOn(boolean showInTooltip) {
        return with(Items.STONE, DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(
                BlockPredicate.Builder.block().of(Blocks.STONE).build()), showInTooltip));
    }

    /** A player head with no name and no id, only a texture, as head commands and datapacks make them. */
    private static ItemStack head(String texture) {
        PropertyMap properties = new PropertyMap();
        properties.put("textures", new com.mojang.authlib.properties.Property("textures", texture));
        return with(Items.PLAYER_HEAD, DataComponents.PROFILE, new ResolvableProfile(Optional.empty(),
                Optional.empty(), properties));
    }

    /**
     * A written Create clipboard: two pages, an entry whose text is {@code text}, a ticked entry in a named colour that
     * shows a pouch as its icon (whose own hash is the identity of its contents), and copied values.
     */
    private static ItemStack clipboard(String text) {
        ClipboardEntry plain = new ClipboardEntry(false, Component.literal(text));
        ClipboardEntry withIcon = new ClipboardEntry(true, Component.literal("Pouch").withStyle(ChatFormatting.RED))
                .displayItem(pouch(Items.WHEAT_SEEDS, POUCH_SEEDS), 3);
        CompoundTag copied = new CompoundTag();
        copied.putString("Filter", "wheat seeds");
        ClipboardContent content = new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(plain, withIcon),
                List.of(new ClipboardEntry(false, Component.literal("page two")))), false).setCopiedValues(copied);
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT, content);
        return stack;
    }

    /** An incomplete precision mechanism at {@code step} of its sequenced assembly. */
    private static ItemStack assemblyStep(int step) {
        return with(AllItems.INCOMPLETE_PRECISION_MECHANISM.get(), AllDataComponents.SEQUENCED_ASSEMBLY,
                new SequencedAssemblyRecipe.SequencedAssembly(Create.asResource("precision_mechanism"), step,
                        step / 5.0f));
    }

    @SuppressWarnings("deprecation") // builtInRegistryHolder: the holder a block's codec gives back
    private static ItemStack debugStick(Property<?> property) {
        return with(Items.DEBUG_STICK, DataComponents.DEBUG_STICK_STATE, new DebugStickState(Map.of(
                Blocks.OAK_LOG.builtInRegistryHolder(), property)));
    }

    /** A bucket of water whose fluid carries the test component. */
    private static FluidStack pouchWater() {
        FluidStack water = new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME);
        water.set(GameTestComponents.IDENTITY_HASHED_STACK.get(), new IdentityHashedStack(
                new ItemStack(Items.WHEAT_SEEDS, POUCH_SEEDS)));
        return water;
    }

    /** A bucket of water carrying potion contents, the way Create's potion fluid is told apart. */
    private static FluidStack potionWater() {
        FluidStack water = new FluidStack(Fluids.WATER, FluidType.BUCKET_VOLUME);
        water.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.LONG_SWIFTNESS));
        return water;
    }

    /** An item holding {@code fluid} the way a modded tank's item does. */
    private static ItemStack tank(FluidStack fluid) {
        return with(Items.BUCKET, GameTestComponents.FLUID_CONTENT.get(), SimpleFluidContent.copyOf(fluid));
    }
}
