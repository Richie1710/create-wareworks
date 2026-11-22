package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageDoors;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseInputUnpackingHandler;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.port.DoorFacts;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The "off means unchanged" proof of package mode (issue #40, owner decision 2026-11-22): with
 * {@code [packageMode] enabled = false}, the default, no saved byte and no entrance behaves differently from 0.23.0-beta.
 * <p>
 * The golden files under {@code src/main/resources/dev/wareworks/gametest/golden/package_mode_off/} were written by
 * <b>this very test running on the unchanged code of 0.23.0-beta</b> (commit {@code eacb251}), before a line of package
 * mode existed. The test builds the same warehouse every run (a controller, a chest behind an interface, a brass rack
 * bay, an input holding loose iron, a requesting port, a stock keeper with a rule and a terminal with an open request;
 * the motor stands still, so nothing moves), saves the six block entities and compares what they write with those
 * files. It can fail: a new key, a changed value or a changed tag type in any of the six saves does. It also records
 * what the entrances answer to a loose stack and to a package ({@code behaviour.snbt}), so a gate that leaked into the
 * switched-off path fails here too.
 * <p>
 * <b>What is normalised, and why.</b> A GameTest stands somewhere else in the world on every run, and some values are
 * random or clocked by nature. Before comparing, both sides go through {@link #normalise}: absolute block coordinates
 * become test-relative ones, every UUID (int array of four) becomes {@code "uuid#n"} in order of first appearance, and
 * the game-time stamps listed in {@link #CLOCKED_KEYS} become {@code "tick"}. Nothing else is touched: keys, types and
 * every other value must match exactly. Each tag is also loaded into a fresh block entity and saved again, which must
 * reproduce it byte for byte (the critic's M6 round trip).
 * <p>
 * The second half of {@code behaviour.snbt} (the unpacking, the empty bay, the plain port's door facts, the stock rows
 * and the crane cycle, review fix 2026-11-22) was written the same way: this test run on a copy of {@code eacb251}
 * with every other GameTest holder disabled ({@code stockSnapshot()} in place of {@code screenStockSnapshot()}, which
 * that version did not have); its six saves came out byte for byte as the files already here.
 * <p>
 * <b>Regenerating</b> is never done to make a run pass: a mismatch means the switched-off path changed. The actual
 * tags of every run are written to {@code golden-actual/} in the GameTest directory for a diff.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageModeGoldenGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int TEST_RPM = 128;
    private static final String GOLDEN_DIR = "golden/package_mode_off/";

    private static final RackPosition STORAGE = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition BAY = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition INPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition KEEPER = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition TERMINAL = new RackPosition(4, 0, Side.RIGHT);
    /** An empty plain rack bay placed after the saves are compared, for the probes of the second half. */
    private static final RackPosition EMPTY_BAY = new RackPosition(3, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final int STORED_IRON = 32;
    private static final int BAY_COBBLESTONE = 20;
    private static final int WAITING_IRON = 5;
    private static final int REQUESTED_IRON = 4;

    /**
     * Keys whose values are game-time stamps (or durations measured from the moment a test happens to start): they
     * differ on every run by nature, so only their presence and type are compared. Found by running this test twice
     * at HEAD and diffing; nothing else differed.
     */
    private static final Set<String> CLOCKED_KEYS = Set.of();

    private PackageModeGoldenGameTests() {
    }

    /**
     * The six block entities of a still warehouse save exactly what 0.23.0-beta saved, and the entrances answer a loose
     * stack and a package exactly as they did. Then (review fix, 2026-11-22) the paths package mode changed most are
     * run with the switch off and recorded the same way: Create's unpacking into the input (simulated), a package
     * offered to an empty rack bay, the door facts of a plain requesting port, the terminal's stock rows, and one real
     * crane cycle with the motor running (the loose iron and a box stored, the request served), so the planner's
     * store, hold and exclusion questions run as they do in play.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packagemodeoffsavesandbehavesashead(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        aisle.build(true);
        CompoundTag behaviour = new CompoundTag();
        List<String> failuresSoFar = new ArrayList<>();
        aisle.storage(STORAGE, IRON.toStack(STORED_IRON));
        helper.setBlock(aisle.rackPos(BAY), WareworksBlocks.RACK_BAY_BRASS.getDefaultState()
                .setValue(RackBayBlock.FACING, aisle.sideDirection(BAY)));
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        aisle.stockKeeper(KEEPER);
        aisle.terminal(TERMINAL);

        helper.startSequence()
                .thenExecute(() -> {
                    RackBayBlockEntity bay = bay(helper, aisle);
                    helper.assertTrue(bay.insert(COBBLESTONE.toStack(BAY_COBBLESTONE), false).isEmpty(),
                            "the bay takes the cobblestone");
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MINIMUM, null, 8L);
                    keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, 256L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 4L);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.controller().status(), ControllerStatus.READY, "the aisle is ready");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) STORED_IRON, "iron indexed");
                    helper.assertValueEqual(aisle.controller().countOf(COBBLESTONE), (long) BAY_COBBLESTONE,
                            "cobblestone indexed");
                })
                .thenExecute(() -> {
                    IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT));
                    helper.assertTrue(input.insertItem(0, IRON.toStack(WAITING_IRON), false).isEmpty(),
                            "loose iron waits in the still input");
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL);
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    Vec3 at = Vec3.atCenterOf(helper.absolutePos(aisle.rackPos(TERMINAL)));
                    player.moveTo(at.x, at.y, at.z);
                    helper.assertTrue(terminal.requestFromTerminal(player, IRON, REQUESTED_IRON).request().isPresent(),
                            "the terminal request is taken");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    failuresSoFar.clear();
                    Map<String, BlockEntity> entities = new java.util.LinkedHashMap<>();
                    entities.put("controller", aisle.controller());
                    entities.put("input", aisle.inputAt(INPUT));
                    entities.put("output", aisle.outputAt(OUTPUT));
                    entities.put("rack_bay", bay(helper, aisle));
                    entities.put("stock_keeper", aisle.stockKeeperAt(KEEPER));
                    entities.put("terminal", aisle.terminalAt(TERMINAL));
                    List<String> failures = new ArrayList<>();
                    for (Map.Entry<String, BlockEntity> entry : entities.entrySet()) {
                        CompoundTag saved = entry.getValue().saveWithoutMetadata(registries);
                        assertRoundTrip(helper, entry.getKey(), entry.getValue(), saved, registries);
                        compare(helper, entry.getKey(), normalise(helper, saved), failures);
                    }
                    behaviour.merge(behaviour(helper, aisle));
                    failuresSoFar.addAll(failures);
                    // The second half: an empty plain bay, a box beside the loose iron, and the crane running.
                    helper.setBlock(aisle.rackPos(EMPTY_BAY), WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                            .setValue(RackBayBlock.FACING, aisle.sideDirection(EMPTY_BAY)));
                    IItemHandler emptyBay = aisle.handlerAt(aisle.rackPos(EMPTY_BAY));
                    ItemStack box = PackageDoorFixture.packageOf(new ItemStack(Items.IRON_INGOT, 3));
                    behaviour.putInt("EmptyBayCapabilityPackage", accepted(box, emptyBay.insertItem(0, box.copy(),
                            true)));
                    behaviour.putBoolean("EmptyBayValidPackage", emptyBay.isItemValid(0, box));
                    helper.assertTrue(aisle.handlerAt(aisle.rackPos(INPUT)).insertItem(1, box.copy(), false)
                            .isEmpty(), "the box waits in the input beside the loose iron");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL, IRON), (long) REQUESTED_IRON,
                            "the request is served");
                    helper.assertTrue(aisle.inputAt(INPUT).bufferedItems().totals().isEmpty(), "the input is stored empty");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    behaviour.merge(cycle(helper, aisle));
                    List<String> failures = new ArrayList<>(failuresSoFar);
                    compare(helper, "behaviour", behaviour, failures);
                    if (!failures.isEmpty())
                        helper.fail("the switched-off save or behaviour differs from 0.23.0-beta: "
                                + String.join("; ", failures));
                })
                .thenSucceed();
    }

    /**
     * What the entrances answer with the switch off, every probe a <b>simulation</b>, so the scene is left as it was:
     * how many of a loose stack and of a package the input's capability and its belt side take, whether the input
     * says the stack is valid, and how many cobblestone, dirt and packages the bay's capability takes.
     */
    private static CompoundTag behaviour(GameTestHelper helper, AisleFixture aisle) {
        CompoundTag tag = new CompoundTag();
        ItemStack loose = IRON.toStack(7);
        ItemStack box = PackageDoorFixture.packageOf(new ItemStack(Items.IRON_INGOT, 3));
        IItemHandler input = aisle.handlerAt(aisle.rackPos(INPUT));
        tag.putInt("InputCapabilityLoose", accepted(loose, insertAnySlot(input, loose)));
        tag.putInt("InputCapabilityPackage", accepted(box, insertAnySlot(input, box)));
        tag.putBoolean("InputValidLoose", input.isItemValid(0, loose));
        tag.putBoolean("InputValidPackage", input.isItemValid(0, box));
        WarehouseInputBlockEntity station = aisle.inputAt(INPUT);
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(station, DirectBeltInputBehaviour.TYPE);
        tag.putInt("InputBeltLoose", accepted(loose, belt.handleInsertion(loose.copy(), Direction.UP, true)));
        tag.putInt("InputBeltPackage", accepted(box, belt.handleInsertion(box.copy(), Direction.UP, true)));
        tag.putInt("InputCraneLoose", accepted(loose, station.insert(loose, true)));
        IItemHandler bay = aisle.handlerAt(aisle.rackPos(BAY));
        tag.putInt("BayCapabilityCobblestone", accepted(COBBLESTONE.toStack(10),
                bay.insertItem(0, COBBLESTONE.toStack(10), true)));
        tag.putInt("BayCapabilityDirt", accepted(new ItemStack(Items.DIRT, 10),
                bay.insertItem(0, new ItemStack(Items.DIRT, 10), true)));
        tag.putInt("BayCapabilityPackage", accepted(box, bay.insertItem(0, box.copy(), true)));
        tag.putBoolean("BayValidCobblestone", bay.isItemValid(0, COBBLESTONE.toStack()));
        // Create's unpacking into the input, simulated (review fix): what a Packager behind it would be told.
        List<ItemStack> contents = new ArrayList<>(List.of(new ItemStack(Items.IRON_INGOT, 3),
                new ItemStack(Items.COBBLESTONE, 2)));
        BlockPos inputPos = aisle.absoluteRackPos(INPUT);
        tag.putBoolean("InputUnpackSimulate", WarehouseInputUnpackingHandler.INSTANCE.unpack(helper.getLevel(),
                inputPos, helper.getLevel().getBlockState(inputPos), Direction.EAST, contents, null, true));
        tag.putInt("InputUnpackSimulateLeft", contents.stream().mapToInt(ItemStack::getCount).sum());
        // The door facts of the plain requesting port (no Packager behind it).
        DoorFacts facts = PackageDoors.facts(helper.getLevel(), aisle.controller(), aisle.absoluteRackPos(OUTPUT));
        tag.putString("PlainPortDoorFacts", facts.member() + "," + facts.requestingPort() + "," + facts.packager()
                + "," + facts.linked() + "," + facts.addressed() + "," + facts.bufferFull() + "," + facts.boxWaiting());
        tag.putString("PlainPortDoorState", facts.state().map(Enum::name).orElse("none"));
        tag.putBoolean("PlainPortIsDoor", facts.isDoor());
        // The terminal's stock rows as its screen is sent them.
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL);
        List<TerminalStockEntry> rows = terminal.screenStockSnapshot();
        helper.assertValueEqual(rows, terminal.stockSnapshot(), "with the switch off a screen gets the plain rows");
        ListTag stockRows = new ListTag();
        for (TerminalStockEntry row : rows)
            stockRows.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(row.key().getItem()) + " total="
                    + row.total() + " available=" + row.available() + " producible=" + row.producible() + " rule="
                    + row.rule().map(Enum::name).orElse("-") + " reserved=" + row.ruleReserved() + " maximum="
                    + row.ruleMaximum()));
        tag.put("TerminalStockRows", stockRows);
        return tag;
    }

    /**
     * What one real crane cycle with the switch off left where (review fix): the loose iron and the box out of the
     * input, the terminal's request served. Counted per place and per item, packages by their item id.
     */
    private static CompoundTag cycle(GameTestHelper helper, AisleFixture aisle) {
        CompoundTag tag = new CompoundTag();
        tag.put("CycleChest", countsAt(helper, aisle.inventoryPos(STORAGE)));
        tag.put("CycleBay", countsAt(helper, aisle.rackPos(BAY)));
        tag.put("CycleEmptyBay", countsAt(helper, aisle.rackPos(EMPTY_BAY)));
        tag.put("CycleInput", countsAt(helper, aisle.rackPos(INPUT)));
        tag.put("CycleTerminal", counts(aisle.terminalAt(TERMINAL).menuBuffer()));
        return tag;
    }

    private static CompoundTag countsAt(GameTestHelper helper, BlockPos relative) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(relative), null);
        return handler == null ? new CompoundTag() : counts(handler);
    }

    private static CompoundTag counts(IItemHandler handler) {
        CompoundTag tag = new CompoundTag();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                tag.putInt(id, tag.getInt(id) + stack.getCount());
            }
        }
        return tag;
    }

    private static ItemStack insertAnySlot(IItemHandler handler, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++)
            rest = handler.insertItem(slot, rest, true);
        return rest;
    }

    private static int accepted(ItemStack offered, ItemStack rest) {
        return offered.getCount() - rest.getCount();
    }

    private static RackBayBlockEntity bay(GameTestHelper helper, AisleFixture aisle) {
        RackBayBlockEntity be = WareworksBlockEntityTypes.RACK_BAY.getNullable(helper.getLevel(),
                helper.absolutePos(aisle.rackPos(BAY)));
        if (be == null)
            helper.fail("missing rack bay block entity", aisle.rackPos(BAY));
        return be;
    }

    /** A fresh block entity of the same type loads {@code saved} and writes it back byte for byte. */
    private static void assertRoundTrip(GameTestHelper helper, String name, BlockEntity live, CompoundTag saved,
            HolderLookup.Provider registries) {
        BlockEntity fresh = live.getType().create(live.getBlockPos(), live.getBlockState());
        if (fresh == null) {
            helper.fail("no fresh " + name + " block entity");
            return;
        }
        fresh.setLevel(helper.getLevel());
        fresh.loadWithComponents(saved, registries);
        CompoundTag again = fresh.saveWithoutMetadata(registries);
        helper.assertValueEqual(again, saved, "the " + name + " writes back exactly what it read");
    }

    // --- golden files -------------------------------------------------------------------------------------------

    private static void compare(GameTestHelper helper, String name, CompoundTag actual, List<String> failures) {
        String text = new SnbtPrinterTagVisitor().visit(actual);
        Path actualDir = Path.of("golden-actual");
        try {
            Files.createDirectories(actualDir);
            Files.writeString(actualDir.resolve(name + ".snbt"), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Wareworks.LOGGER.warn("could not write the actual golden tag {}", name, e);
        }
        String golden = readGolden(name);
        if (golden == null) {
            failures.add("no golden file for " + name + " (actual written to " + actualDir.toAbsolutePath() + ")");
            return;
        }
        CompoundTag expected;
        try {
            expected = TagParser.parseTag(golden);
        } catch (CommandSyntaxException e) {
            failures.add("golden " + name + " does not parse: " + e.getMessage());
            return;
        }
        if (!expected.equals(actual))
            failures.add(name + " differs (actual in " + actualDir.resolve(name + ".snbt").toAbsolutePath() + ")");
    }

    private static String readGolden(String name) {
        String resource = GOLDEN_DIR + name + ".snbt";
        try (InputStream in = PackageModeGoldenGameTests.class.getResourceAsStream(resource)) {
            if (in != null)
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Wareworks.LOGGER.warn("could not read the golden resource {}", resource, e);
        }
        // Dev runs: the GameTest directory is run/gametest, two levels below the project.
        Path file = Path.of("..", "..", "src", "main", "resources", "dev", "wareworks", "gametest", "golden",
                "package_mode_off", name + ".snbt");
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // --- normalisation ------------------------------------------------------------------------------------------

    /**
     * {@code tag} with test-relative coordinates, numbered UUIDs and masked clock values; see the class comment.
     * Coordinates are recognised in the two shapes the six saves use: a compound with int {@code X}, {@code Y} and
     * {@code Z} and an int array of three. Only values that lie inside the test's own structure (with a margin) are
     * rewritten, so an aisle length or a slot count is never mistaken for a coordinate.
     */
    static CompoundTag normalise(GameTestHelper helper, CompoundTag tag) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        Map<String, String> uuids = new HashMap<>();
        return (CompoundTag) normaliseTag(tag, origin, uuids, null);
    }

    private static Tag normaliseTag(Tag tag, BlockPos origin, Map<String, String> uuids, String key) {
        if (key != null && CLOCKED_KEYS.contains(key))
            return StringTag.valueOf("tick");
        if (tag instanceof CompoundTag compound) {
            CompoundTag out = new CompoundTag();
            if (isAbsolutePos(compound, origin)) {
                for (String k : compound.getAllKeys())
                    out.put(k, compound.get(k).copy());
                out.putInt("X", compound.getInt("X") - origin.getX());
                out.putInt("Y", compound.getInt("Y") - origin.getY());
                out.putInt("Z", compound.getInt("Z") - origin.getZ());
                out.putBoolean("RelativeToTest", true);
                return out;
            }
            for (String k : new TreeSet<>(compound.getAllKeys()))
                out.put(k, normaliseTag(compound.get(k), origin, uuids, k));
            return out;
        }
        if (tag instanceof ListTag list) {
            ListTag out = new ListTag();
            for (Tag element : list)
                out.add(normaliseTag(element, origin, uuids, null));
            return out;
        }
        if (tag instanceof IntArrayTag array) {
            int[] values = array.getAsIntArray();
            if (values.length == 4) {
                String text = java.util.Arrays.toString(values);
                return StringTag.valueOf(uuids.computeIfAbsent(text, t -> "uuid#" + uuids.size()));
            }
            if (values.length == 3 && near(values[0], values[1], values[2], origin))
                return new IntArrayTag(new int[] { values[0] - origin.getX(), values[1] - origin.getY(),
                        values[2] - origin.getZ(), Integer.MIN_VALUE });
            return array.copy();
        }
        return tag.copy();
    }

    private static boolean isAbsolutePos(CompoundTag compound, BlockPos origin) {
        if (!(compound.get("X") instanceof IntTag x) || !(compound.get("Y") instanceof IntTag y)
                || !(compound.get("Z") instanceof IntTag z))
            return false;
        return near(x.getAsInt(), y.getAsInt(), z.getAsInt(), origin);
    }

    /** Whether the three values lie within 64 blocks of the test's origin on every axis, i.e. are a world position. */
    private static boolean near(int x, int y, int z, BlockPos origin) {
        return Math.abs(x - origin.getX()) <= 64 && Math.abs(y - origin.getY()) <= 64
                && Math.abs(z - origin.getZ()) <= 64
                && (Math.abs(x) > 64 || Math.abs(z) > 64);
    }
}
