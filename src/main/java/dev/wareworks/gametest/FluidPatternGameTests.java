package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.selector.SelectorMachine;
import dev.wareworks.content.selector.SelectorRecipeCheck;
import dev.wareworks.content.station.CrafterRecipeCheck;
import dev.wareworks.content.station.PatternFluids;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.ProductionGoggleSummary;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.production.CrafterPatternCheck;
import dev.wareworks.core.production.FluidCell;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.core.production.ProductionPattern;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.network.ProductionPatternLayPayload;
import dev.wareworks.network.ProductionPatternPayload;
import dev.wareworks.network.ProductionScreenPayload;
import dev.wareworks.util.WareworksLang;
import io.netty.buffer.Unpooled;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of the <b>fluid cells of a production pattern</b> (issue #55, ADR-089, slice 1: data model, save, payloads,
 * screen state). A pattern may name up to two fluids with an amount per run. What these prove in a real world, on top of
 * the unit tests of {@code FluidCell} and {@code ProductionPatterns}: a click through the real payload path writes,
 * changes and clears a cell, the server resolves the fluid against its own registry (a flowing fluid is stored as its
 * source, an unknown one is ignored), the screen is told, a save keeps cells beside real items and a save without a fluid
 * cell has no fluid tag at all (so it is what it always was), and a pattern with a fluid cell is stored and shown but
 * never offered to the planner until slice 2 delivers fluid. Nothing here moves an item or a fluid.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class FluidPatternGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 600;

    private static final RackPosition MUD_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition PLANK_RACK = new RackPosition(3, 0, Side.RIGHT);

    private static final String WATER = "minecraft:water";
    private static final String LAVA = "minecraft:lava";

    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey MUD = ItemKey.of(Items.MUD);
    private static final ItemKey OAK_LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey OAK_PLANKS = ItemKey.of(Items.OAK_PLANKS);

    private FluidPatternGameTests() {
    }

    private static AisleFixture aisle(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.production(MUD_RACK);
        aisle.production(PLANK_RACK);
        return aisle;
    }

    private static ProductionMenu openMenu(ClickingPlayer player, WarehouseProductionBlockEntity station) {
        ProductionMenu menu = ProductionMenu.create(player.containerMenu.containerId + 1, player.getInventory(),
                station);
        player.containerMenu = menu;
        return menu;
    }

    private static Optional<Boolean> submit(ClickingPlayer player, ProductionPatternPayload payload) {
        return ProductionMenu.submitPattern(player, payload.containerId(), payload.pattern(), payload.entry(),
                payload.key(), payload.count(), payload.fluid());
    }

    /** The click as the network handler receives it: through the payload's own codec. */
    private static ProductionPatternPayload sent(GameTestHelper helper, ProductionPatternPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionPatternPayload.STREAM_CODEC.encode(buffer, payload);
        ProductionPatternPayload read = ProductionPatternPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the click reads exactly what it wrote");
        helper.assertValueEqual(read, payload, "and reads it back unchanged");
        return read;
    }

    private static ProductionScreenState roundTrip(GameTestHelper helper, ProductionScreenState state) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionScreenPayload.STREAM_CODEC.encode(buffer, new ProductionScreenPayload(1, state));
        ProductionScreenState read = ProductionScreenPayload.STREAM_CODEC.decode(buffer).state();
        helper.assertValueEqual(buffer.readableBytes(), 0, "the state reads exactly what it wrote");
        return read;
    }

    /**
     * A player's clicks through the real payload path: a fluid is set with an amount, the wheel changes the amount
     * (clamped to the Basin segment), a second fluid goes into the second cell, the same fluid twice is refused and said
     * over the hotbar, a flowing fluid is stored as its source, an unknown fluid is ignored, and a right click clears a
     * cell, which moves the other one up. The screen's state carries it all, through its codec.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidcelleditthroughpayload(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        // A menu answers eight edits per tick (ProductionMenu#MAX_EDITS_PER_TICK): the clicks come in two ticks.
        WarehouseProductionBlockEntity[] stationBox = new WarehouseProductionBlockEntity[1];
        ClickingPlayer[] playerBox = new ClickingPlayer[1];
        ProductionMenu[] menuBox = new ProductionMenu[1];
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(MUD_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(MUD_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    stationBox[0] = station;
                    playerBox[0] = player;
                    menuBox[0] = menu;
                    int id = menu.containerId;

                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 0, WATER, 250))), Optional.of(true),
                            "water is written");
                    helper.assertValueEqual(station.patterns().fluidAt(0, 0), Optional.of(new FluidCell(WATER, 250)),
                            "into cell 0 with 250 mB");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 0, WATER, 5000))), Optional.of(true),
                            "the wheel moves it");
                    helper.assertValueEqual(station.patterns().fluidAt(0, 0).orElseThrow().millibuckets(), 1000,
                            "and the amount is clamped to a Basin segment");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 0, WATER, 250))), Optional.of(true),
                            "and back");

                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 1, "minecraft:flowing_water", 500))),
                            Optional.of(false), "the same fluid in the other cell, even flowing, is refused");
                    helper.assertValueEqual(player.toldKeys(),
                            List.of(WareworksLang.key(WareworksLang.PRODUCTION_FLUID_SAME)), "and said over the hotbar");
                    helper.assertValueEqual(station.patterns().fluidAt(0, 1), Optional.empty(), "nothing was written");

                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 1, "minecraft:flowing_lava", 750))),
                            Optional.of(true), "a flowing fluid is accepted");
                    helper.assertValueEqual(station.patterns().fluidAt(0, 1), Optional.of(new FluidCell(LAVA, 750)),
                            "and stored as its source");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 1, "nomod:goo", 100))),
                            Optional.of(false), "a fluid this server does not have is ignored");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 1, "minecraft:empty", 100))),
                            Optional.of(false), "and so is the empty fluid");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.setFluid(id, 0, 3, WATER, 100))),
                            Optional.of(false), "a cell past the second changes nothing");
                    helper.assertValueEqual(station.patterns().fluidCells(0),
                            List.of(new FluidCell(WATER, 250), new FluidCell(LAVA, 750)), "two cells, as clicked");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.clearFluid(id, 0, 0))), Optional.empty(),
                            "a ninth edit in one tick is dropped, like every pattern edit");
                    helper.assertValueEqual(station.patterns().fluidCells(0).size(), 2, "and changes nothing");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = stationBox[0];
                    ClickingPlayer player = playerBox[0];
                    ProductionMenu menu = menuBox[0];
                    int id = menu.containerId;
                    ProductionScreenState state = roundTrip(helper, menu.screenState());
                    helper.assertValueEqual(state.fluid(0, 0), Optional.of(new FluidCell(WATER, 250)),
                            "the screen is told cell 0");
                    helper.assertValueEqual(state.fluid(0, 1), Optional.of(new FluidCell(LAVA, 750)), "and cell 1");
                    helper.assertValueEqual(state.fluid(1, 0), Optional.empty(), "and none for another slot");

                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.clearFluid(id, 0, 0))), Optional.of(true),
                            "a right click clears cell 0");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(new FluidCell(LAVA, 750)),
                            "and the other cell moves up");
                    helper.assertValueEqual(submit(player,
                            sent(helper, ProductionPatternPayload.clearFluid(id, 0, 1))), Optional.of(false),
                            "an empty cell has nothing to clear");

                    // An item entry through the very same payload is unchanged by all this.
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.set(id, 0, 0, DIRT, 1))), Optional.of(true), "an item cell");
                    helper.assertValueEqual(station.patterns().keyAt(0, 0), Optional.of(DIRT), "is written as before");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(new FluidCell(LAVA, 750)),
                            "and the fluid cell stays");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.clearPattern(id, 0))), Optional.of(true), "a whole slot clears");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(), "fluid cells too");
                })
                .thenSucceed();
    }

    /**
     * A filled container names its fluid for the cell: a water bucket is water, 1000 mB; an empty bucket and a plain item
     * are none.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidcellfromabucket(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertValueEqual(PatternFluids.contained(Items.WATER_BUCKET.getDefaultInstance()),
                            Optional.of(new PatternFluids.Contained(WATER, 1000)), "a water bucket");
                    helper.assertValueEqual(PatternFluids.contained(Items.LAVA_BUCKET.getDefaultInstance()),
                            Optional.of(new PatternFluids.Contained(LAVA, 1000)), "a lava bucket");
                    helper.assertValueEqual(PatternFluids.contained(Items.BUCKET.getDefaultInstance()), Optional.empty(),
                            "an empty bucket holds none");
                    helper.assertValueEqual(PatternFluids.contained(Items.DIRT.getDefaultInstance()), Optional.empty(),
                            "dirt neither");
                    helper.assertValueEqual(PatternFluids.validId("minecraft:flowing_water"), Optional.of(WATER),
                            "a flowing fluid is its source");
                    helper.assertValueEqual(PatternFluids.validId("minecraft:empty"), Optional.empty(), "empty is none");
                    helper.assertValueEqual(PatternFluids.validId("nomod:goo"), Optional.empty(), "unknown is none");
                    helper.assertValueEqual(PatternFluids.validId("Not A Fluid!"), Optional.empty(), "malformed is none");
                })
                .thenSucceed();
    }

    /**
     * A save with items and fluid cells round-trips; a slot without a fluid cell saves <b>exactly</b> the keys it always
     * did (no fluid tag, nothing new in an entry), so a world that never uses fluid cells is untouched; and a save written
     * before fluid cells existed loads with none.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternsave(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    ProductionPatterns patterns = new ProductionPatterns(4);
                    patterns.setEntry(0, 0, DIRT, 1);
                    patterns.setEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1);
                    patterns.setFluid(0, 0, WATER, 250);
                    patterns.setFluid(0, 1, LAVA, 100);
                    patterns.setEntry(1, 0, OAK_LOG, 1);
                    patterns.setEntry(1, ProductionPatterns.RESULT_ENTRY, OAK_PLANKS, 4);
                    patterns.setFluid(2, 1, WATER, 1000);

                    ListTag saved = patterns.save(registries);
                    helper.assertValueEqual(saved.size(), 3, "three slots hold something");
                    CompoundTag withFluids = saved.getCompound(0);
                    helper.assertValueEqual(withFluids.getList("FluidCells", Tag.TAG_COMPOUND).size(), 2,
                            "slot 0 lists both fluid cells");
                    CompoundTag plain = saved.getCompound(1);
                    helper.assertValueEqual(plain.getAllKeys(), Set.of("Slot", "Entries"),
                            "a slot without a fluid cell has exactly the keys it always had");
                    for (Tag entry : plain.getList("Entries", Tag.TAG_COMPOUND))
                        helper.assertValueEqual(((CompoundTag) entry).getAllKeys(), Set.of("Entry", "Item", "Count"),
                                "and so has each of its entries");
                    CompoundTag fluidOnly = saved.getCompound(2);
                    helper.assertValueEqual(fluidOnly.getInt("Slot"), 2, "a slot with only a fluid is saved too");
                    helper.assertValueEqual(fluidOnly.getList("Entries", Tag.TAG_COMPOUND).size(), 0, "with no entries");

                    ProductionPatterns loaded = new ProductionPatterns(4);
                    loaded.load(saved, registries);
                    helper.assertValueEqual(loaded.fluidCells(0), patterns.fluidCells(0), "fluid cells survive");
                    helper.assertValueEqual(loaded.fluidCells(2), patterns.fluidCells(2), "also without items");
                    helper.assertValueEqual(loaded.keyAt(0, 0), Optional.of(DIRT), "items too");
                    helper.assertValueEqual(loaded.patternAt(0).map(ProductionPattern::fluids),
                            Optional.of(patterns.fluidCells(0)), "and the pattern carries them");
                    helper.assertValueEqual(loaded.save(registries), saved, "and the save is the same again");

                    // A save from before fluid cells: no list anywhere.
                    ListTag old = new ListTag();
                    old.add(plain.copy());
                    ProductionPatterns oldLoaded = new ProductionPatterns(4);
                    oldLoaded.load(old, registries);
                    helper.assertValueEqual(oldLoaded.fluidCells(1), List.of(), "an old slot has no fluid cells");
                    helper.assertValueEqual(oldLoaded.patternAt(1).map(ProductionPattern::hasFluids), Optional.of(false),
                            "its pattern is a plain one");
                    helper.assertValueEqual(oldLoaded.save(registries), old, "and saves back the very same tag");
                })
                .thenSucceed();
    }

    /**
     * A pattern with a fluid cell is stored, shown and, since the planner delivers fluid (issue #55, slice 2), offered to
     * it like any other pattern. The Crafter Feeder refuses a fluid pattern with its own reason, and the goggles count it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternisoffered(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(MUD_RACK);
                    helper.assertTrue(station.setPatternEntry(0, 0, DIRT, 1), "dirt");
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "to mud");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "an item pattern is offered");
                    helper.assertValueEqual(station.editFluidCell(0, 0, WATER, 250),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "and gets a water cell");
                    helper.assertValueEqual(station.patterns().patternCount(), 1, "it is still a complete pattern");
                    helper.assertTrue(station.patterns().patternAt(0).orElseThrow().hasFluids(), "with a fluid");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "and is offered to the planner");
                    helper.assertTrue(station.activePatterns().getFirst().hasFluids(), "with its fluid");
                    helper.assertTrue(station.setPatternEntry(1, 0, OAK_LOG, 1), "another slot");
                    helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, OAK_PLANKS, 4),
                            "makes planks");
                    helper.assertValueEqual(station.activePatterns().size(), 2, "and so is that one");
                    helper.assertValueEqual(station.activePatterns().get(1).result().key(), OAK_PLANKS,
                            "the plain one");

                    helper.assertValueEqual(CrafterRecipeCheck.check(helper.getLevel(), station.patterns(), 0).check(),
                            CrafterPatternCheck.FLUID_CELL, "a Crafter takes no fluid");
                    helper.assertTrue(CrafterPatternCheck.FLUID_CELL.refused(), "which is drawn red");
                    helper.assertTrue(CrafterRecipeCheck.check(helper.getLevel(), station.patterns(), 1).check()
                            != CrafterPatternCheck.FLUID_CELL, "and the plain slot is checked as ever");

                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().fluidPatterns(), 1, "the goggles count it");
                    helper.assertValueEqual(station.productionSummary().fluidContainersHeld(), 0, "nothing stuck yet");
                    station.insertIntoBufferForTest(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.WATER_BUCKET, 2));
                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().fluidContainersHeld(), 2,
                            "two filled buckets left in the buffer are named by the goggles");
                })
                .thenSucceed();
    }

    /**
     * A pattern with ingredients but no result is never ordered and says so (issue #62): the model and the screen's state
     * name the slot, the goggle summary counts it with its tab number (the lines themselves are the fluidpattern visual
     * run's: a goggle tooltip cannot be built on a server), the synced tag keeps it, and a result clears it. A filled water bucket in the grid beside a water cell is marked as paid twice; an empty
     * bucket, a lava bucket and a slot without a fluid cell are not. Nothing is converted: the grid keeps the bucket.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternwithoutresultissaid(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(MUD_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(MUD_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().noResultPatterns(), 0, "an untouched station");

                    // The reporter's pattern: a water bucket and gravel in the grid, 200 mB of water, no result.
                    helper.assertTrue(station.setPatternEntry(1, 0, ItemKey.of(Items.WATER_BUCKET), 1), "a bucket");
                    helper.assertTrue(station.setPatternEntry(1, 1, ItemKey.of(Items.GRAVEL), 1), "gravel");
                    helper.assertValueEqual(station.editFluidCell(1, 0, WATER, 200),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "and a water cell");
                    helper.assertValueEqual(station.activePatterns().size(), 0, "it is not offered");
                    helper.assertValueEqual(station.patterns().slotsWithoutResult(), List.of(1), "and it is named");
                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().noResultPatterns(), 1, "one such pattern");
                    helper.assertValueEqual(station.productionSummary().firstNoResult(), 2, "pattern 2, as its tab");
                    helper.assertValueEqual(station.productionSummary().patterns(), 0, "no complete pattern");
                    CompoundTag synced = new CompoundTag();
                    station.productionSummary().write(synced);
                    helper.assertValueEqual(ProductionGoggleSummary.read(synced), station.productionSummary(),
                            "and the client reads the same");

                    ProductionScreenState state = roundTrip(helper, menu.screenState());
                    helper.assertTrue(state.lacksResult(1), "the screen knows the slot lacks a result");
                    helper.assertFalse(state.lacksResult(0), "an empty slot lacks nothing");
                    helper.assertValueEqual(state.paidTwice(1, 0), Optional.of(WATER), "the bucket is paid twice");
                    helper.assertValueEqual(state.paidTwice(1, 1), Optional.empty(), "gravel is not");
                    helper.assertTrue(state.fluidAlsoInGrid(1, WATER), "and the water cell is told");

                    // A fluid cell alone is an ingredient too.
                    helper.assertValueEqual(station.editFluidCell(3, 0, LAVA, 100),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "a lone fluid cell");
                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().noResultPatterns(), 2, "two are counted");
                    helper.assertTrue(station.setPatternEntry(3, 0, ItemKey.of(Items.WATER_BUCKET), 1), "water");
                    helper.assertValueEqual(menu.screenState().paidTwice(3, 0), Optional.empty(),
                            "another fluid is not paid twice");
                    helper.assertTrue(station.setPatternEntry(3, 0, ItemKey.of(Items.BUCKET), 1), "an empty bucket");
                    helper.assertValueEqual(menu.screenState().paidTwice(3, 0), Optional.empty(), "nor is it");
                    helper.assertTrue(station.setPatternEntry(2, 0, ItemKey.of(Items.WATER_BUCKET), 1), "no cell");
                    helper.assertValueEqual(menu.screenState().paidTwice(2, 0), Optional.empty(),
                            "a bucket without a fluid cell is an ordinary item");

                    helper.assertTrue(station.setPatternEntry(1, ProductionPatterns.RESULT_ENTRY, MUD, 1), "a result");
                    helper.assertValueEqual(station.activePatterns().size(), 1, "now it is offered");
                    helper.assertFalse(menu.screenState().lacksResult(1), "and lacks nothing");
                    helper.assertValueEqual(menu.screenState().paidTwice(1, 0), Optional.of(WATER),
                            "the double payment is still said, and the bucket stays an item");
                    station.onGoggleObserved();
                    helper.assertValueEqual(station.productionSummary().firstNoResult(), 3, "the next one is named");
                })
                .thenSucceed();
    }

    /**
     * A fluid whose mod was removed stays in the cell and is shown (the screen draws it as unknown), it cannot be set
     * again, and it can be cleared: an unknown fluid is never a crash.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternunknownfluidkept(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(MUD_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(MUD_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    ProductionPatterns loaded = station.patterns();
                    helper.assertTrue(loaded.setFluid(0, 0, "removedmod:goo", 400), "loaded from a save");
                    helper.assertValueEqual(PatternFluids.resolve("removedmod:goo"), Optional.empty(), "no such fluid");
                    ProductionScreenState state = roundTrip(helper, menu.screenState());
                    helper.assertValueEqual(state.fluid(0, 0), Optional.of(new FluidCell("removedmod:goo", 400)),
                            "the screen is told it");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.setFluid(menu.containerId, 0, 0, "removedmod:goo", 500))),
                            Optional.of(false), "it cannot be set again");
                    helper.assertValueEqual(submit(player, sent(helper,
                            ProductionPatternPayload.clearFluid(menu.containerId, 0, 0))), Optional.of(true),
                            "but it can be cleared");
                    helper.assertValueEqual(loaded.fluidCells(0), List.of(), "and then it is gone");
                })
                .thenSucceed();
    }

    /**
     * The station's fluid cells as a model, without a click (the test classpath of the unit tests holds no Minecraft
     * classes, so the content layer is proven here): set, clamped, cleared, the same fluid refused in the other cell, the
     * set cells kept at the front, a whole slot cleared with them, a hostile save read without throwing and bounded, and
     * the goggle summary writing its count only when there is one.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternmodel(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    ProductionPatterns patterns = new ProductionPatterns(4);
                    helper.assertTrue(patterns.isEmpty(), "nothing yet");
                    helper.assertTrue(patterns.setFluid(1, 0, WATER, 250), "a fluid is set");
                    helper.assertValueEqual(patterns.fluidAt(1, 0), Optional.of(new FluidCell(WATER, 250)), "as given");
                    helper.assertFalse(patterns.isEmpty(), "a slot with only a fluid cell is not empty");
                    helper.assertFalse(patterns.setFluid(1, 0, WATER, 250), "the same again changes nothing");
                    helper.assertTrue(patterns.setFluid(1, 0, WATER, 99999), "an amount");
                    helper.assertValueEqual(patterns.fluidAt(1, 0).orElseThrow().millibuckets(), 1000, "is clamped");
                    helper.assertTrue(patterns.setFluid(1, 0, WATER, -3), "even a negative one");
                    helper.assertValueEqual(patterns.fluidAt(1, 0).orElseThrow().millibuckets(), 1, "to one");
                    helper.assertTrue(patterns.setFluid(1, 0, null, 1), "cleared");
                    helper.assertTrue(patterns.isEmpty(), "and the station is empty again");
                    helper.assertFalse(patterns.setFluid(1, 0, null, 1), "clearing an empty cell changes nothing");

                    helper.assertFalse(patterns.setFluid(-1, 0, WATER, 250), "a slot below the first");
                    helper.assertFalse(patterns.setFluid(4, 0, WATER, 250), "a slot past the last");
                    helper.assertFalse(patterns.setFluid(0, 2, WATER, 250), "a third cell");
                    helper.assertFalse(patterns.setFluid(0, 0, "", 250), "a blank id");
                    helper.assertFalse(patterns.setFluid(0, 0, "Not A Fluid!", 250), "a malformed id");

                    helper.assertTrue(patterns.setFluid(0, 0, WATER, 250), "water");
                    helper.assertFalse(patterns.setFluid(0, 1, WATER, 500), "not twice");
                    helper.assertTrue(patterns.setFluid(0, 1, LAVA, 500), "lava in the other cell");
                    helper.assertFalse(patterns.setFluid(0, 0, LAVA, 100), "nor the other way round");
                    helper.assertTrue(patterns.setFluid(0, 0, WATER, 750), "a cell may change its own amount");
                    helper.assertTrue(patterns.setFluid(0, 0, null, 1), "clearing cell 0");
                    helper.assertValueEqual(patterns.fluidAt(0, 0), Optional.of(new FluidCell(LAVA, 500)),
                            "moves cell 1 up");
                    helper.assertTrue(patterns.setFluid(0, 1, WATER, 5), "and a cell 1 beside it");
                    helper.assertTrue(patterns.clearPattern(0), "a whole slot clears");
                    helper.assertValueEqual(patterns.fluidCells(0), List.of(), "with its fluid cells");
                    helper.assertTrue(patterns.setFluid(2, 1, LAVA, 40), "a lone cell 1");
                    helper.assertValueEqual(patterns.fluidAt(2, 0), Optional.of(new FluidCell(LAVA, 40)),
                            "is stored as cell 0");

                    ListTag hostile = new ListTag();
                    CompoundTag slot = new CompoundTag();
                    slot.putInt("Slot", 0);
                    ListTag cells = new ListTag();
                    cells.add(new CompoundTag());
                    CompoundTag huge = new CompoundTag();
                    huge.putInt("Cell", 0);
                    huge.putString("Fluid", WATER);
                    huge.putInt("Mb", 500000);
                    cells.add(huge);
                    for (int i = 0; i < 50; i++) {
                        CompoundTag spam = new CompoundTag();
                        spam.putInt("Cell", 1);
                        spam.putString("Fluid", "minecraft:milk" + i);
                        spam.putInt("Mb", 5);
                        cells.add(spam);
                    }
                    CompoundTag longId = new CompoundTag();
                    longId.putInt("Cell", 1);
                    longId.putString("Fluid", "minecraft:" + "x".repeat(FluidCell.MAX_ID_LENGTH));
                    longId.putInt("Mb", 5);
                    cells.add(longId);
                    slot.put("FluidCells", cells);
                    hostile.add(slot);
                    ProductionPatterns read = new ProductionPatterns(1);
                    read.load(hostile, registries);
                    helper.assertTrue(read.fluidCells(0).size() <= FluidCell.MAX_PER_PATTERN, "bounded: "
                            + read.fluidCells(0));
                    for (FluidCell cell : read.fluidCells(0))
                        helper.assertTrue(cell.fluid().length() <= FluidCell.MAX_ID_LENGTH, "an id too long to sync is "
                                + "dropped: " + cell.fluid().length());
                    helper.assertFalse(read.setFluid(0, 1, "minecraft:" + "y".repeat(FluidCell.MAX_ID_LENGTH), 5),
                            "and setFluid refuses one");
                    for (FluidCell cell : read.fluidCells(0))
                        helper.assertTrue(cell.millibuckets() >= 1 && cell.millibuckets() <= 1000, "clamped: " + cell);

                    ProductionGoggleSummary none = ProductionGoggleSummary.NONE;
                    CompoundTag plain = new CompoundTag();
                    none.write(plain);
                    helper.assertFalse(plain.contains("FluidPatterns"), "a station without a fluid pays nothing");
                    ProductionGoggleSummary with = new ProductionGoggleSummary(2, 0, Optional.empty(), 0L, 0L, 0, 0L,
                            false, false, 0, ProductionGoggleSummary.SelectorSummary.NONE, 2);
                    CompoundTag written = new CompoundTag();
                    with.write(written);
                    helper.assertValueEqual(written.getInt("FluidPatterns"), 2, "the count is written");
                    helper.assertValueEqual(ProductionGoggleSummary.read(written), with, "and read back");
                    helper.assertValueEqual(ProductionGoggleSummary.read(plain), none, "an old tag reads as none");
                })
                .thenSucceed();
    }

    // --- JEI's "+" and the Recipe Selector (issue #55, slice 3) ---------------------------------------------------------

    private static ProductionPatternLayPayload sentLay(GameTestHelper helper, ProductionPatternLayPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        ProductionPatternLayPayload.STREAM_CODEC.encode(buffer, payload);
        ProductionPatternLayPayload read = ProductionPatternLayPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the lay reads exactly what it wrote");
        helper.assertValueEqual(read, payload, "with every cell, count, tag and fluid");
        return read;
    }

    private static ProductionPatternLayPayload machineLay(int containerId, int pattern, ItemKey input, ItemKey result,
            List<FluidCell> fluids) {
        List<List<ItemKey>> cells = new ArrayList<>(Collections.nCopies(9, List.of()));
        if (input != null)
            cells.set(0, List.of(input));
        return new ProductionPatternLayPayload(containerId, pattern, false, cells, Collections.nCopies(9, 1),
                Optional.of(new ProductionEntry<>(result, 1)), Collections.nCopies(9, Optional.empty()),
                Optional.empty(), fluids);
    }

    /**
     * What a recipe viewer's "+" sends for a machine recipe with a fluid (ADR-076 D5 amended by ADR-089): the fluid cells
     * travel with the lay through the payload's own codec, the server writes them beside the items and replaces what the
     * slot held; a lay without a fluid leaves the slot without one; an unknown fluid refuses the whole lay and leaves
     * the slot as it was; a flowing fluid is stored as its source; two names of one fluid merge; a crafting recipe has no
     * fluid cell whatever the client says; a payload with three fluids is no payload.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidlayfromrecipeviewer(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(MUD_RACK);
                    ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(MUD_RACK));
                    ProductionMenu menu = openMenu(player, station);
                    int id = menu.containerId;

                    helper.assertValueEqual(menu.lay(player, sentLay(helper, machineLay(id, 0, DIRT, MUD,
                            List.of(new FluidCell(WATER, 250))))), ProductionMenu.LayResult.LAID, "mud is laid");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(new FluidCell(WATER, 250)),
                            "with its water");
                    helper.assertValueEqual(station.patterns().keyAt(0, 0), Optional.of(DIRT), "and its dirt");
                    helper.assertValueEqual(station.patterns().keyAt(0, ProductionPatterns.RESULT_ENTRY),
                            Optional.of(MUD), "and the result");
                    helper.assertTrue(station.patterns().patternAt(0).orElseThrow().hasFluids(), "one pattern");

                    helper.assertValueEqual(menu.lay(player, machineLay(id, 0, DIRT, MUD, List.of())),
                            ProductionMenu.LayResult.LAID, "laid again without a fluid");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(), "the slot keeps none");

                    helper.assertValueEqual(menu.lay(player, machineLay(id, 0, DIRT, MUD,
                            List.of(new FluidCell(WATER, 250)))), ProductionMenu.LayResult.LAID, "water again");
                    helper.assertValueEqual(menu.lay(player, machineLay(id, 0, DIRT, MUD,
                            List.of(new FluidCell("nomod:nothing", 250)))), ProductionMenu.LayResult.UNKNOWN_FLUID,
                            "an unknown fluid refuses the lay");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(new FluidCell(WATER, 250)),
                            "and the slot stays as it was");
                    helper.assertValueEqual(menu.lay(player, machineLay(id, 0, DIRT, MUD,
                            List.of(new FluidCell(WATER, 600), new FluidCell(WATER, 600)))),
                            ProductionMenu.LayResult.NOT_A_RECIPE, "two cells of one fluid past 1000 mB are refused");
                    helper.assertValueEqual(station.patterns().fluidCells(0), List.of(new FluidCell(WATER, 250)),
                            "not clamped into the slot");

                    helper.assertValueEqual(menu.lay(player, machineLay(id, 1, null, MUD,
                            List.of(new FluidCell("minecraft:flowing_lava", 100)))), ProductionMenu.LayResult.LAID,
                            "a recipe of only a fluid");
                    helper.assertValueEqual(station.patterns().fluidCells(1),
                            List.of(new FluidCell(LAVA, 100)), "flowing lava is stored as its source");
                    helper.assertValueEqual(station.patterns().patternCount(), 2, "a fluid-only slot is a pattern");

                    helper.assertValueEqual(menu.lay(player, machineLay(id, 2, DIRT, MUD,
                            List.of(new FluidCell("minecraft:flowing_water", 200), new FluidCell(WATER, 300)))),
                            ProductionMenu.LayResult.LAID, "two names of water");
                    helper.assertValueEqual(station.patterns().fluidCells(2), List.of(new FluidCell(WATER, 500)),
                            "are one cell with both amounts");

                    List<List<ItemKey>> table = new ArrayList<>(Collections.nCopies(9, List.of()));
                    for (int cell : new int[] {0, 1, 3, 4})
                        table.set(cell, List.of(OAK_PLANKS));
                    helper.assertValueEqual(menu.lay(player, new ProductionPatternLayPayload(id, 3, true, table,
                            Collections.nCopies(9, 1), Optional.empty(), Collections.nCopies(9, Optional.empty()),
                            Optional.empty(), List.of(new FluidCell(WATER, 250)))), ProductionMenu.LayResult.LAID,
                            "a crafting table");
                    helper.assertValueEqual(station.patterns().fluidCells(3), List.of(),
                            "a crafting recipe has no fluid cell");

                    ProductionPatternLayPayload three = machineLay(id, 0, DIRT, MUD, List.of(new FluidCell("a:a", 1),
                            new FluidCell("a:b", 1), new FluidCell("a:c", 1)));
                    helper.assertFalse(three.wellFormed(), "three fluids are no lay");
                    helper.assertValueEqual(ProductionMenu.submitLay(player, three), Optional.empty(),
                            "and the server drops it");
                })
                .thenSucceed();
    }

    /**
     * The pattern check at a station of a Recipe Selector (ADR-085 D14 amended by ADR-089): the Basin's mud recipe passes
     * only with its water cell of 250 mB, a changed amount is FLUID_MISMATCH and takes the pattern out of the planner's
     * reach, the check follows the edit (the cache is keyed on the patterns), and a station of an Ender Recipe Selector
     * refuses every fluid cell with FLUID_FAR, packages carrying items only. A pattern without a fluid cell is checked
     * exactly as before.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fluidpatternselectorcheck(GameTestHelper helper) {
        AisleFixture aisle = aisle(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity local = aisle.productionAt(MUD_RACK);
                    helper.assertTrue(local.setPatternEntry(0, 0, DIRT, 1), "dirt");
                    helper.assertTrue(local.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "to mud");
                    helper.assertValueEqual(local.setSelectorMode(SelectorMode.LOCAL,
                            Optional.of(SelectorMachine.Kind.MIXER_BASIN)),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "a local selector");
                    helper.assertValueEqual(local.selectorCheck(0),
                            Optional.of(SelectorRecipeCheck.Verdict.FLUID_MISMATCH),
                            "mud without its water is a mismatch, not NO_RECIPE");
                    helper.assertValueEqual(local.activePatterns().size(), 0, "so it is never offered");
                    helper.assertValueEqual(local.editFluidCell(0, 0, WATER, 250),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "water 250");
                    helper.assertValueEqual(local.selectorCheck(0), Optional.of(SelectorRecipeCheck.Verdict.VALID),
                            "passes, the check followed the edit");
                    helper.assertValueEqual(local.activePatterns().size(), 1, "and is offered");
                    helper.assertValueEqual(local.editFluidCell(0, 0, WATER, 500),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "500 mB");
                    helper.assertValueEqual(local.selectorCheck(0),
                            Optional.of(SelectorRecipeCheck.Verdict.FLUID_MISMATCH), "does not pass");
                    helper.assertValueEqual(local.editFluidCell(0, 0, LAVA, 250),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "lava 250");
                    helper.assertValueEqual(local.selectorCheck(0),
                            Optional.of(SelectorRecipeCheck.Verdict.FLUID_MISMATCH), "nor does another fluid");

                    WarehouseProductionBlockEntity far = aisle.productionAt(PLANK_RACK);
                    helper.assertTrue(far.setPatternEntry(0, 0, DIRT, 1), "dirt");
                    helper.assertTrue(far.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, MUD, 1), "to mud");
                    helper.assertValueEqual(far.editFluidCell(0, 0, WATER, 250),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "water 250");
                    helper.assertValueEqual(far.setSelectorMode(SelectorMode.SLIPS,
                            Optional.of(SelectorMachine.Kind.MIXER_BASIN)),
                            WarehouseProductionBlockEntity.SelectorModeChange.ACCEPTED, "an Ender selector");
                    helper.assertValueEqual(far.selectorCheck(0), Optional.of(SelectorRecipeCheck.Verdict.FLUID_FAR),
                            "the far tier refuses a fluid cell");
                    helper.assertValueEqual(far.activePatterns().size(), 0, "and never offers it");
                    helper.assertValueEqual(far.editFluidCell(0, 0, null, 1),
                            WarehouseProductionBlockEntity.EditOutcome.CHANGED, "cleared");
                    helper.assertValueEqual(far.selectorCheck(0),
                            Optional.of(SelectorRecipeCheck.Verdict.FLUID_MISMATCH),
                            "without the cell the far tier checks mud like the local one");
                })
                .thenSucceed();
    }
}
