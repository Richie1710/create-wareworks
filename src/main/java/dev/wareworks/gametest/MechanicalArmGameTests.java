package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsInputHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.DeliveryStationArmPoint;
import dev.wareworks.content.station.StationArmPointType;
import dev.wareworks.content.station.WarehouseDeliveryStationBlockEntity;
import dev.wareworks.content.station.WarehouseInputArmPoint;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.registry.WareworksArmInteractionPoints;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * GameTests of Create mechanical arms at warehouse stations ({@code docs/warehouse-system.md} §3.2, M12): the four
 * interaction point types and their registration, the point semantics (the input is deposit only; output, terminal and
 * production station are take only, also against a forged saved mode), the output's request filter slot letting a
 * click with the arm item through, and real, powered arms moving items between depots and stations with the item
 * census holding. The production order side is
 * {@code ProductionGameTests#productionIngredientsTakenByMechanicalArm}.
 * <p>
 * All tests use the {@code empty_7x5x7} floor. The type and semantics tests put the input, output and terminal in the
 * back row (z = 1), the production station, an unpowered arm and the warehouse interface in the middle row (z = 3), and
 * the controller, crane dock, rail and a composter in the front row (z = 5). The arm test drives three arms from one
 * cogwheel in the centre ({@link MechanicalArmFixture}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class MechanicalArmGameTests {
    // --- station layout (types and semantics) ---
    private static final BlockPos INPUT = new BlockPos(1, BASE_Y, 1);
    private static final BlockPos OUTPUT = new BlockPos(3, BASE_Y, 1);
    private static final BlockPos TERMINAL = new BlockPos(5, BASE_Y, 1);
    private static final BlockPos PRODUCTION = new BlockPos(1, BASE_Y, 3);
    /** An arm without rotation and without targets: only the owner the point API asks for, it never moves. */
    private static final BlockPos IDLE_ARM = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos INTERFACE = new BlockPos(5, BASE_Y, 3);
    private static final BlockPos STOCK_KEEPER = new BlockPos(6, BASE_Y, 3);
    private static final BlockPos CONTROLLER = new BlockPos(0, BASE_Y, 5);
    private static final BlockPos DOCK = new BlockPos(2, BASE_Y, 5);
    private static final BlockPos RAIL = new BlockPos(4, BASE_Y, 5);
    private static final BlockPos COMPOSTER = new BlockPos(6, BASE_Y, 5);

    // --- arm layout (mechanicalArmsFeedAndEmptyStations) ---
    private static final BlockPos ARM_COG = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos FEEDER_ARM = ARM_COG.west();
    private static final BlockPos FEED_DEPOT = new BlockPos(0, BASE_Y, 4);
    private static final BlockPos FED_INPUT = new BlockPos(0, BASE_Y, 2);
    private static final BlockPos OUTPUT_ARM = ARM_COG.east();
    private static final BlockPos EMPTIED_OUTPUT = new BlockPos(6, BASE_Y, 2);
    private static final BlockPos OUTPUT_DEPOT = new BlockPos(6, BASE_Y, 4);
    private static final BlockPos TERMINAL_ARM = ARM_COG.south();
    private static final BlockPos EMPTIED_TERMINAL = new BlockPos(2, BASE_Y, 6);
    private static final BlockPos TERMINAL_DEPOT = new BlockPos(4, BASE_Y, 6);

    private static final int STACK = 64;
    private static final int ARM_IRON = 32;
    private static final int BUFFERED_GOLD = 20;
    private static final int TAKEN_GOLD = 7;
    private static final int OFFERED_IRON = 8;
    private static final int FED_IRON = 32;
    private static final int OUTPUT_GOLD = 20;
    private static final int TERMINAL_DIAMONDS = 12;
    /** Several full arm cycles at {@value MechanicalArmFixture#ARM_RPM} RPM: long enough for anything to move back. */
    private static final int SETTLE_TICKS = 60;
    private static final int ARM_TIMEOUT_TICKS = 400;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    private MechanicalArmGameTests() {
    }

    // --- types -----------------------------------------------------------------------------------------------------

    /**
     * Each station resolves to its own {@code wareworks} type in every block state, the types are in the sorted list
     * Create builds when its registry freezes (so the deferred registration really reached Create's registry), the arm
     * item selects a station instead of being placed against it, and no type accepts another station. The warehouse
     * interface, controller, crane dock and rail are no arm targets.
     * <p>
     * A composter is checked as well: it must still resolve to Create's own type, so a passing test can never just mean
     * that the lookup itself is broken.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationArmPointTypes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(COMPOSTER, Blocks.COMPOSTER);
        helper.assertValueEqual(typeId(primaryType(helper, COMPOSTER)), Create.asResource("composter"),
                "a composter is still Create's arm target: the lookup works in this test");

        List<StationCase> stations = stations();
        for (StationCase station : stations) {
            helper.setBlock(station.pos(), station.block().getDefaultState());
            StationArmPointType type = station.type().get();
            helper.assertValueEqual(typeId(type), Wareworks.asResource(station.id()), "registered id");
            helper.assertValueEqual(station.type().getId(), Wareworks.asResource(station.id()), "holder id");
            helper.assertTrue(ArmInteractionPointType.SORTED_TYPES_VIEW.contains(type),
                    station.id() + " is in Create's sorted type list");

            BlockPos absolute = helper.absolutePos(station.pos());
            for (BlockState state : station.block().get().getStateDefinition().getPossibleStates())
                helper.assertTrue(ArmInteractionPointType.getPrimaryType(level, absolute, state) == type,
                        "an arm resolves " + state + " to " + station.id());
            helper.assertTrue(ArmInteractionPoint.isInteractable(level, absolute, level.getBlockState(absolute)),
                    "the arm item selects the " + station.id() + " instead of being placed against it");
            for (StationCase other : stations) {
                if (other != station)
                    helper.assertFalse(type.canCreatePoint(level, absolute, other.block().getDefaultState()),
                            "the " + station.id() + " type does not accept the " + other.id());
            }
        }

        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState());
        helper.setBlock(CONTROLLER, WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState());
        helper.setBlock(DOCK, WareworksBlocks.STACKER_CRANE.getDefaultState());
        helper.setBlock(RAIL, WareworksBlocks.WAREHOUSE_RAIL.getDefaultState());
        // The stock keeper is an aisle member like the stations, but it holds no items at all, so it must stay a
        // non-target: an arm placed against it, never selecting it (M15).
        helper.setBlock(STOCK_KEEPER, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState());
        for (BlockPos pos : List.of(INTERFACE, CONTROLLER, DOCK, RAIL, STOCK_KEEPER))
            helper.assertTrue(primaryType(helper, pos) == null, "no mechanical arm can target "
                    + level.getBlockState(helper.absolutePos(pos)));
        helper.succeed();
    }

    // --- point semantics -------------------------------------------------------------------------------------------

    /**
     * The points without a running arm, through the API an arm calls. The input: one right-click selects it for
     * depositing and no click changes that, items put in land in the buffer (simulation changes nothing), and nothing can
     * be taken out. Output, terminal and production station: one right-click selects them for taking and no click
     * changes that, an insert returns the offered stack itself and leaves the buffer alone, and an extraction really
     * takes buffered items. Every point survives a save and load with its type id and mode, a tag whose mode was
     * rewritten still loads with the forced mode, and a tag naming another station's type does not load at all.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationArmPointSemantics(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(IDLE_ARM, AllBlocks.MECHANICAL_ARM.getDefaultState());
        ArmBlockEntity arm = MechanicalArmFixture.armAt(helper, IDLE_ARM);
        BlockPos anchor = helper.absolutePos(IDLE_ARM);
        for (StationCase station : stations())
            helper.setBlock(station.pos(), station.block().getDefaultState());

        // The input: deposit only.
        WarehouseInputBlockEntity input = inputAt(helper, INPUT);
        ArmInteractionPoint inputPoint = MechanicalArmFixture.select(helper, INPUT, 1);
        helper.assertTrue(inputPoint instanceof WarehouseInputArmPoint, "the input's point: " + inputPoint);
        helper.assertValueEqual(inputPoint.getMode(), Mode.DEPOSIT, "one click selects the input for depositing");
        inputPoint.cycleMode();
        helper.assertValueEqual(inputPoint.getMode(), Mode.DEPOSIT, "another click keeps it");
        ItemStack offered = IRON.toStack(ARM_IRON);
        helper.assertTrue(inputPoint.insert(arm, offered, true).isEmpty(), "a simulated insert fits");
        helper.assertFalse(input.hasBufferedItems(), "the simulation changes nothing");
        helper.assertTrue(inputPoint.insert(arm, offered, false).isEmpty(), "the insert fits");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) ARM_IRON, "the items landed in the buffer");
        helper.assertValueEqual(inputPoint.getSlotCount(arm), 0, "no slot to take from");
        helper.assertTrue(inputPoint.extract(arm, 0, STACK, false).isEmpty(), "nothing can be taken out");
        helper.assertValueEqual(input.bufferedItems().count(IRON), (long) ARM_IRON, "the buffer is unchanged");
        assertRoundTrip(helper, stations().getFirst(), inputPoint, anchor, Mode.TAKE, Mode.DEPOSIT);

        // Output, terminal and production station: take only.
        for (StationCase station : stations().subList(1, stations().size())) {
            WarehouseDeliveryStationBlockEntity delivery = deliveryAt(helper, station.pos());
            helper.assertTrue(delivery.insert(GOLD.toStack(BUFFERED_GOLD), false).isEmpty(), "the crane delivered gold");
            ArmInteractionPoint point = MechanicalArmFixture.select(helper, station.pos(), 1);
            helper.assertTrue(point instanceof DeliveryStationArmPoint, "the " + station.id() + "'s point: " + point);
            helper.assertValueEqual(point.getMode(), Mode.TAKE, "one click selects the " + station.id() + " for taking");
            point.cycleMode();
            helper.assertValueEqual(point.getMode(), Mode.TAKE, "another click keeps it");
            helper.assertValueEqual(MechanicalArmFixture.select(helper, station.pos(),
                    MechanicalArmFixture.DEPOSIT_CLICKS).getMode(), Mode.TAKE,
                    "the clicks that make a depot a destination do not make the " + station.id() + " one");

            ItemStack refused = IRON.toStack(OFFERED_IRON);
            helper.assertTrue(point.insert(arm, refused, true) == refused, "a simulated insert returns the stack itself");
            helper.assertTrue(point.insert(arm, refused, false) == refused, "an insert returns the stack itself");
            helper.assertValueEqual(refused.getCount(), OFFERED_IRON, "the offered stack is untouched");
            helper.assertValueEqual(delivery.bufferedItems().count(IRON), 0L, "nothing was put into the " + station.id());
            helper.assertValueEqual(delivery.bufferedItems().count(GOLD), (long) BUFFERED_GOLD, "its buffer is unchanged");

            helper.assertValueEqual(point.getSlotCount(arm), delivery.bufferSlots(), "every buffer slot is offered");
            ItemStack simulated = point.extract(arm, 0, TAKEN_GOLD, true);
            helper.assertTrue(GOLD.matches(simulated) && simulated.getCount() == TAKEN_GOLD, "simulated " + simulated);
            helper.assertValueEqual(delivery.bufferedItems().count(GOLD), (long) BUFFERED_GOLD, "the simulation changes nothing");
            ItemStack taken = point.extract(arm, 0, TAKEN_GOLD, false);
            helper.assertTrue(GOLD.matches(taken) && taken.getCount() == TAKEN_GOLD, "taken " + taken);
            helper.assertValueEqual(delivery.bufferedItems().count(GOLD), (long) (BUFFERED_GOLD - TAKEN_GOLD),
                    "the arm took the gold out of the " + station.id());
            assertRoundTrip(helper, station, point, anchor, Mode.DEPOSIT, Mode.TAKE);
        }

        // A saved output point whose type id names the input does not load on the output.
        CompoundTag mislabelled = MechanicalArmFixture.select(helper, OUTPUT, 1).serialize(anchor);
        mislabelled.putString(MechanicalArmFixture.POINT_TYPE_TAG,
                WareworksArmInteractionPoints.WAREHOUSE_INPUT.getId().toString());
        helper.assertTrue(ArmInteractionPoint.deserialize(mislabelled, level, anchor) == null,
                "a point whose type names another station does not load");
        helper.succeed();
    }

    // --- selecting the output through its filter slot ---------------------------------------------------------------

    /**
     * A right-click with the Mechanical Arm item on the warehouse output's request filter slot reaches Create's arm
     * target selection. The slot is a Create value box in the centre of the top, back and side faces, so it is where a
     * player naturally clicks; Create's {@code ValueSettingsInputHandler} used to cancel that click, and the arm item
     * is no filter either, so the click did nothing (found by the {@code arm} visual scenario). The handler is called
     * the way the game posts the event, on the server side with a creative player: with an iron ingot the slot takes
     * the click (so the test really hits the slot), with the arm item the event stays uncancelled and the filter is
     * left alone.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void outputFilterSlotLetsArmSelectionThrough(GameTestHelper helper) {
        helper.setBlock(OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        BlockPos absolute = helper.absolutePos(OUTPUT);
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                absolute);
        if (output == null) {
            helper.fail("missing warehouse output block entity", OUTPUT);
            return;
        }
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        helper.assertTrue(filter != null, "the output has its request filter");
        BlockHitResult slotCentre = new BlockHitResult(Vec3.atLowerCornerOf(absolute).add(0.5, 1.0, 0.5), Direction.UP,
                absolute, false);
        if (filter.getSlotPositioning() instanceof ValueBoxTransform.Sided sided)
            sided.fromSide(Direction.UP);
        helper.assertTrue(filter.testHit(slotCentre.getLocation()), "the click lies on the filter slot of the top face");
        Player player = helper.makeMockPlayer(GameType.CREATIVE);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
        PlayerInteractEvent.RightClickBlock withIngot = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, absolute, slotCentre);
        ValueSettingsInputHandler.onBlockActivated(withIngot);
        helper.assertTrue(withIngot.isCanceled(), "an ordinary item is taken by the filter slot");
        ItemStack filterBefore = filter.getFilter().copy();

        player.setItemInHand(InteractionHand.MAIN_HAND, AllBlocks.MECHANICAL_ARM.asStack());
        PlayerInteractEvent.RightClickBlock withArm = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, absolute, slotCentre);
        ValueSettingsInputHandler.onBlockActivated(withArm);
        helper.assertFalse(withArm.isCanceled(),
                "a click with the Mechanical Arm item passes the filter slot on to the arm's target selection");
        helper.assertTrue(ItemStack.matches(filter.getFilter(), filterBefore), "the arm item leaves the filter alone");
        helper.succeed();
    }

    /**
     * The output's request filter slot offers no interaction to a player holding the Mechanical Arm item, so Create's
     * {@code FilteringRenderer} draws neither the value box nor its "Click with item to set" hint while the arm item
     * hovers the slot (it skips every behaviour whose {@code mayInteract} is false). Bypassing the input alone had left
     * that hint up although the click selects the output as an arm target (found by the {@code arm} visual scenario,
     * which also checks the drawn outline and hint). With an ordinary item or an empty hand the slot stays interactive.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void outputFilterSlotOffersNoInteractionToTheArm(GameTestHelper helper) {
        helper.setBlock(OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                helper.absolutePos(OUTPUT));
        if (output == null) {
            helper.fail("missing warehouse output block entity", OUTPUT);
            return;
        }
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        helper.assertTrue(filter != null, "the output has its request filter");
        Player player = helper.makeMockPlayer(GameType.CREATIVE);

        player.setItemInHand(InteractionHand.MAIN_HAND, AllBlocks.MECHANICAL_ARM.asStack());
        helper.assertFalse(filter.mayInteract(player),
                "a player holding the Mechanical Arm item gets no value box and no hint on the request filter slot");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(filter.mayInteract(player), "with an ordinary item the request filter slot is interactive");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(filter.mayInteract(player), "with an empty hand the request filter slot is interactive");
        helper.succeed();
    }

    // --- real arms -------------------------------------------------------------------------------------------------

    /**
     * Three real, powered mechanical arms on one cogwheel: the feeder arm takes iron from a depot and puts it into a
     * warehouse input, one arm takes gold out of a warehouse output onto a depot, and one takes diamonds out of a
     * warehouse terminal onto a depot. The terminal arm's saved point list names the terminal with the mode
     * <b>deposit</b> (an edited or foreign save); it still takes from it, and saves the point as "take" once resolved.
     * The item census, which counts the arms' claws and the depots, holds on every tick, and every arm is seen holding
     * items on at least one of those ticks, so the census really counted a loaded claw. Nothing moves once the stations
     * are served.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = ARM_TIMEOUT_TICKS)
    public static void mechanicalArmsFeedAndEmptyStations(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        helper.setBlock(FEED_DEPOT, AllBlocks.DEPOT.getDefaultState());
        helper.setBlock(OUTPUT_DEPOT, AllBlocks.DEPOT.getDefaultState());
        helper.setBlock(TERMINAL_DEPOT, AllBlocks.DEPOT.getDefaultState());
        helper.setBlock(FED_INPUT, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        helper.setBlock(EMPTIED_OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        helper.setBlock(EMPTIED_TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState());

        helper.assertTrue(ItemHandlerHelper.insertItem(handlerAt(helper, FEED_DEPOT), IRON.toStack(FED_IRON), false)
                .isEmpty(), "iron lies on the feed depot");
        helper.assertTrue(deliveryAt(helper, EMPTIED_OUTPUT).insert(GOLD.toStack(OUTPUT_GOLD), false).isEmpty(),
                "the crane delivered gold to the output");
        helper.assertTrue(deliveryAt(helper, EMPTIED_TERMINAL).insert(DIAMOND.toStack(TERMINAL_DIAMONDS), false)
                .isEmpty(), "the crane delivered diamonds to the terminal");
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, FED_IRON, GOLD, OUTPUT_GOLD, DIAMOND, TERMINAL_DIAMONDS);

        MechanicalArmFixture.place(helper, FEEDER_ARM, List.of(
                MechanicalArmFixture.select(helper, FEED_DEPOT, MechanicalArmFixture.TAKE_CLICKS),
                MechanicalArmFixture.select(helper, FED_INPUT, 1)));
        MechanicalArmFixture.place(helper, OUTPUT_ARM, List.of(
                MechanicalArmFixture.select(helper, EMPTIED_OUTPUT, 1),
                MechanicalArmFixture.select(helper, OUTPUT_DEPOT, MechanicalArmFixture.DEPOSIT_CLICKS)));
        ListTag terminalPoints = MechanicalArmFixture.serialize(helper, TERMINAL_ARM, List.of(
                MechanicalArmFixture.select(helper, EMPTIED_TERMINAL, 1),
                MechanicalArmFixture.select(helper, TERMINAL_DEPOT, MechanicalArmFixture.DEPOSIT_CLICKS)));
        terminalPoints.getCompound(0).putString(MechanicalArmFixture.POINT_MODE_TAG, Mode.DEPOSIT.name());
        MechanicalArmFixture.place(helper, TERMINAL_ARM, terminalPoints);
        ItemCensus.assertEquals(helper, conserved, "before the arms run");
        List<BlockPos> arms = List.of(FEEDER_ARM, OUTPUT_ARM, TERMINAL_ARM);
        Set<BlockPos> loadedClaws = new HashSet<>();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while the arms run");
            for (BlockPos pos : arms) {
                if (!MechanicalArmFixture.heldItem(MechanicalArmFixture.armAt(helper, pos), registries).isEmpty())
                    loadedClaws.add(pos);
            }
        });
        MechanicalArmFixture.power(helper, ARM_COG);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(inputAt(helper, FED_INPUT).bufferedItems().count(IRON), (long) FED_IRON,
                            "the feeder arm filled the input");
                    helper.assertValueEqual(depotCount(helper, OUTPUT_DEPOT, GOLD), (long) OUTPUT_GOLD,
                            "the arm emptied the output onto its depot");
                    helper.assertValueEqual(depotCount(helper, TERMINAL_DEPOT, DIAMOND), (long) TERMINAL_DIAMONDS,
                            "the arm emptied the terminal onto its depot");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(depotCount(helper, FEED_DEPOT, IRON), 0L, "the feed depot is empty");
                    helper.assertFalse(deliveryAt(helper, EMPTIED_OUTPUT).hasBufferedItems(), "the output is empty");
                    helper.assertFalse(deliveryAt(helper, EMPTIED_TERMINAL).hasBufferedItems(), "the terminal is empty");
                    for (BlockPos pos : arms) {
                        helper.assertTrue(loadedClaws.contains(pos), "the census counted the claw of the arm at " + pos
                                + " while it held items");
                        helper.assertTrue(MechanicalArmFixture.heldItem(MechanicalArmFixture.armAt(helper, pos),
                                registries).isEmpty(), "the claw of the arm at " + pos + " is empty");
                    }

                    ListTag saved = MechanicalArmFixture.savedPoints(MechanicalArmFixture.armAt(helper, TERMINAL_ARM),
                            registries);
                    CompoundTag terminal = savedPointOf(helper, saved, WareworksArmInteractionPoints.WAREHOUSE_TERMINAL);
                    helper.assertValueEqual(terminal.getString(MechanicalArmFixture.POINT_MODE_TAG), Mode.TAKE.name(),
                            "the resolved terminal point is saved as take");
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved, "after the arms settled");
                    helper.assertValueEqual(inputAt(helper, FED_INPUT).bufferedItems().count(IRON), (long) FED_IRON,
                            "nothing left the input");
                    helper.assertValueEqual(depotCount(helper, OUTPUT_DEPOT, GOLD), (long) OUTPUT_GOLD,
                            "nothing went back into the output");
                    helper.assertValueEqual(depotCount(helper, TERMINAL_DEPOT, DIAMOND), (long) TERMINAL_DIAMONDS,
                            "nothing went back into the terminal");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A station block, its position in the type and semantics tests, its arm point type and that type's id path. */
    private record StationCase(BlockPos pos, BlockEntry<? extends Block> block,
                               DeferredHolder<ArmInteractionPointType, StationArmPointType> type, String id) {
    }

    /** The input first, then the three take-only stations. */
    private static List<StationCase> stations() {
        return List.of(
                new StationCase(INPUT, WareworksBlocks.WAREHOUSE_INPUT, WareworksArmInteractionPoints.WAREHOUSE_INPUT,
                        "warehouse_input"),
                new StationCase(OUTPUT, WareworksBlocks.WAREHOUSE_OUTPUT, WareworksArmInteractionPoints.WAREHOUSE_OUTPUT,
                        "warehouse_output"),
                new StationCase(TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL,
                        WareworksArmInteractionPoints.WAREHOUSE_TERMINAL, "warehouse_terminal"),
                new StationCase(PRODUCTION, WareworksBlocks.WAREHOUSE_PRODUCTION,
                        WareworksArmInteractionPoints.WAREHOUSE_PRODUCTION, "warehouse_production"));
    }

    /**
     * Saves {@code point} as an arm does and loads it back: same type, position and {@code mode}; then loads the tag with
     * its mode rewritten to {@code forged}, which must still come back as {@code mode}.
     */
    private static void assertRoundTrip(GameTestHelper helper, StationCase station, ArmInteractionPoint point,
                                        BlockPos anchor, Mode forged, Mode mode) {
        ServerLevel level = helper.getLevel();
        CompoundTag saved = point.serialize(anchor);
        helper.assertValueEqual(saved.getString(MechanicalArmFixture.POINT_TYPE_TAG),
                Wareworks.asResource(station.id()).toString(), "saved type id of the " + station.id());
        ArmInteractionPoint loaded = ArmInteractionPoint.deserialize(saved, level, anchor);
        if (loaded == null) {
            helper.fail("the saved " + station.id() + " point does not load", station.pos());
            return;
        }
        helper.assertTrue(loaded.getType() == station.type().get(), "loaded type of the " + station.id());
        helper.assertValueEqual(loaded.getPos(), helper.absolutePos(station.pos()), "loaded position");
        helper.assertValueEqual(loaded.getMode(), mode, "loaded mode of the " + station.id());

        CompoundTag tampered = saved.copy();
        tampered.putString(MechanicalArmFixture.POINT_MODE_TAG, forged.name());
        ArmInteractionPoint forcedBack = ArmInteractionPoint.deserialize(tampered, level, anchor);
        if (forcedBack == null) {
            helper.fail("the tampered " + station.id() + " point does not load", station.pos());
            return;
        }
        helper.assertValueEqual(forcedBack.getMode(), mode, "a saved " + forged + " mode still loads the " + station.id()
                + " as " + mode);
    }

    /** The first saved point of {@code type}; fails the test when the arm saved none. */
    private static CompoundTag savedPointOf(GameTestHelper helper, ListTag saved,
                                            DeferredHolder<ArmInteractionPointType, StationArmPointType> type) {
        String id = type.getId().toString();
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag point = saved.getCompound(i);
            if (id.equals(point.getString(MechanicalArmFixture.POINT_TYPE_TAG)))
                return point;
        }
        helper.fail("the arm saved no " + id + " point: " + saved);
        throw new IllegalStateException("unreachable");
    }

    /** Create's arm interaction point type for the block at a test-relative position, or null if no arm can target it. */
    private static ArmInteractionPointType primaryType(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return ArmInteractionPointType.getPrimaryType(helper.getLevel(), absolute, helper.getLevel().getBlockState(absolute));
    }

    private static ResourceLocation typeId(ArmInteractionPointType type) {
        return type == null ? null : CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE.getKey(type);
    }

    private static WarehouseInputBlockEntity inputAt(GameTestHelper helper, BlockPos pos) {
        WarehouseInputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing warehouse input block entity", pos);
        return be;
    }

    private static WarehouseDeliveryStationBlockEntity deliveryAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof WarehouseDeliveryStationBlockEntity delivery)) {
            helper.fail("missing warehouse delivery station block entity", pos);
            return null;
        }
        return delivery;
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    /** Items of {@code key} on the depot at a test-relative position, through its item capability. */
    private static long depotCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        IItemHandler handler = handlerAt(helper, pos);
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }
}
