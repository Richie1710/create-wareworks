package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageHandover;
import dev.wareworks.content.station.PackageUnpackSummary;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStationBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.port.PackagerSignAddress;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of a <b>package door</b> (M26, issue #18, {@code docs/warehouse-system.md} §3.2.5): a Create Packager whose
 * back touches a warehouse port packs what the warehouse hands over and writes the address off a plain vanilla sign, and
 * one whose back touches a warehouse input takes arriving packages apart into it.
 * <p>
 * <b>Why these tests are the milestone's product.</b> Both directions work with no Wareworks code in the item path at
 * all — they are an accident of two mods fitting together, and nothing pinned them. One changed line on either side
 * would break a shipped feature silently: a port that answered an insert-capable view would start swallowing packages,
 * a Create change to {@code getStateForPlacement} would face new Packagers the wrong way, a tidier re-reading of the
 * sign rule would send boxes to a different address than Create does. Each of those is one assertion below.
 * <p>
 * <b>The direction lock is the load-bearing pair.</b> {@link #packagerBehindAPortCanNeverUnpack} and
 * {@link #packagerBehindAnInputCanNeverPack} pin that the direction of a door is decided by the <b>geometry</b> and
 * cannot be set wrong: the port's capability is extract-only on every side, so Create's simulate pass finds leftovers
 * and refuses the box whole, and the input's is insert-only, so there is nothing for a Packager to pack. Not one line of
 * Wareworks code checks a direction — which is exactly why it has to be asserted from the outside.
 * <p>
 * <b>Conservation.</b> Every test that moves an item asserts {@link ItemCensus} across the handover, and since M26 that
 * census counts a package as its contents and the box as nothing, so iron disappearing into a box is a failure and not a
 * silent pass ({@link ItemCensusGameTests}). Where a box is dropped the census is taken explicitly rather than every
 * tick: NeoForge swaps the dropped {@code ItemEntity} for a {@code PackageEntity} one server task later, so the items
 * legitimately belong to nothing for the width of one tick boundary.
 * <p>
 * <b>What the two neighbouring holders cover instead.</b> {@code PackageUnpackingGameTests} owns the warehouse input's
 * own surfaces — its unpacking handler and the two numbers of a refusal; {@code ItemCensusGameTests} owns the census
 * rules themselves. Nothing here repeats them: the tests below are about the <b>door</b>, with the crane, the
 * controller, the sign and the world attached.
 * <p>
 * Layout of the aisle tests: the {@link AisleFixture} aisle on {@code aisle_16x10x7} ({@value #RAILS} rails), the
 * station on the right rack plane and the Packager one block behind it, where a storage location's inventory would
 * stand. The small tests use {@code empty_7x5x7} with a bare station and no warehouse at all, because a Packager asks
 * the station for a capability and nothing else.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageHandoverGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    /** For the refusal test, whose crane has a full buffer plus a whole package to clear away. */
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Long enough for several dispatch intervals and a Packager cycle: proof that nothing happens any more. */
    private static final int SETTLE_TICKS = 60;
    /** More than Create's lazy tick rate of 10, so {@code recheckIfLinksPresent} has certainly run. */
    private static final int LAZY_TICKS = 25;
    /** Ticks given to freshly placed blocks so Create's behaviours have found their neighbours. */
    private static final int SETTLE_SHORT = 5;

    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PORT_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(3, 0, Side.RIGHT);

    /** The bare station of the {@code empty_7x5x7} tests, and the Packager whose back touches it. */
    private static final BlockPos STATION = new BlockPos(3, BASE_Y, 3);
    private static final Direction PACKAGER_SIDE = Direction.EAST;
    private static final BlockPos PACKAGER = STATION.relative(PACKAGER_SIDE);
    /**
     * A standing sign east of the Packager, the <b>last</b> of its neighbours in {@code Direction.values()} order, and a
     * second one north of it, which comes earlier. Both stand on the template floor, so neither can pop off.
     */
    private static final BlockPos SIGN = PACKAGER.relative(Direction.EAST);
    private static final BlockPos EARLIER_SIGN = PACKAGER.relative(Direction.NORTH);
    /** The redstone block that drives the Packager: adjacent to it and, diagonally, never to the station. */
    private static final BlockPos POWER = PACKAGER.relative(Direction.SOUTH);
    /**
     * A Create Stock Link standing on top of the Packager, which is what takes it off redstone. On top, and not on a
     * side, because every horizontal face of this Packager is already a sign or the redstone block.
     */
    private static final BlockPos STOCK_LINK = PACKAGER.relative(Direction.UP);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    /** The address a player writes on the sign at the door. */
    private static final String ADDRESS = "Base North";
    private static final String OTHER_ADDRESS = "Base South";

    private static final int IRON_IN_STOCK = 40;
    private static final int PER_TRIP = 8;
    private static final int IRON_IN_BOX = 12;
    private static final int GOLD_IN_BOX = 5;
    private static final int DIAMONDS_IN_BOX = 2;
    private static final int STACK = 64;
    /** More than a package entity's 5 HP ({@code PackageEntity#createPackageAttributes}). */
    private static final float KILLING_DAMAGE = 10f;

    /** Distinct, storable items for filling buffer slots, none of them in any test package. */
    private static final List<Item> FILLERS = List.of(Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL,
            Items.OAK_LOG, Items.STONE, Items.GLASS, Items.BRICK, Items.CLAY_BALL, Items.FLINT, Items.COAL,
            Items.STICK, Items.BONE, Items.LEATHER, Items.PAPER, Items.WHEAT, Items.CARROT, Items.POTATO,
            Items.APPLE, Items.EGG, Items.FEATHER, Items.STRING, Items.SUGAR_CANE, Items.KELP, Items.CACTUS,
            Items.PUMPKIN, Items.MELON_SLICE);

    private PackageHandoverGameTests() {
    }

    // --- the out door ------------------------------------------------------------------------------------------------

    /**
     * The whole out door, end to end: a request takes iron out of a rack, the crane carries it to the port, and the
     * Packager behind the port boxes it and writes the sign's address on the box.
     * <p>
     * The three assertions that make it a feature rather than a coincidence: the box exists at all, its address is the
     * string a player wrote on a vanilla sign, and the iron is <b>inside</b> it — which only means anything because the
     * census counts a package as its contents, so the per-tick conservation assertion follows the ingots from the chest
     * through the crane's head and the port buffer into the box without ever losing sight of them.
     * <p>
     * The Packager is powered only after the crane has filled the port, so that packing is unmistakably the redstone's
     * doing. A sustained signal is used rather than a single pulse because that is the build this feature teaches (a
     * Create Smart Observer at the port sustains exactly such a signal while something is extractable); the code path is
     * identical either way, since both reach {@code attemptToSend(null)} through {@code redstoneModeActive()}.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageHandoverFromAPort(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.output(PORT_RACK);
        BlockPos packager = aisle.inventoryPos(PORT_RACK);
        Direction facing = aisle.sideDirection(PORT_RACK);
        BlockPos sign = packager.relative(AisleFixture.AISLE);
        BlockPos power = packager.relative(facing);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a door hands goods over in a box"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    placePackager(helper, packager, facing);
                    writeSign(helper, sign, true, ADDRESS);
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.requestAt(PORT_RACK, IRON.toStack(), PER_TRIP, aisle.rackPos(PORT_RACK).above());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) PER_TRIP,
                        "the crane filled the door"))
                .thenExecute(() -> {
                    helper.assertTrue(packagerAt(helper, packager).heldBox.isEmpty(),
                            "nothing is packed before the door is told to");
                    helper.setBlock(power, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, packager).heldBox.isEmpty(),
                        "the redstone signal made the Packager box what the door held"))
                .thenExecute(() -> {
                    ItemStack box = packagerAt(helper, packager).heldBox;
                    helper.assertTrue(PackageItem.isPackage(box), "what the Packager holds is a Create package");
                    helper.assertValueEqual(PackageItem.getAddress(box), ADDRESS,
                            "the sign at the door is the address on the box");
                    helper.assertValueEqual(contentsOf(box), ItemCensus.of(IRON, PER_TRIP),
                            "and the iron the crane delivered is inside it");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), 0L,
                            "the port handed over everything it held");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) (IRON_IN_STOCK - PER_TRIP),
                            "exactly the requested amount left the rack");
                    // What the goggle tooltip of the port names is the Packager this test built and the address it
                    // reads, and both are the ones the box really got.
                    helper.assertValueEqual(PackageHandover.packagerFor(helper.getLevel(), aisle.absoluteRackPos(
                            PORT_RACK)), Optional.of(helper.absolutePos(packager)), "the port's Packager");
                    helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(), helper.absolutePos(packager)),
                            PackageItem.getAddress(box), "the address the port names is the one on the box");
                })
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * <b>The direction lock, out half.</b> A Packager at a port can only pack: handed a package, it gives it straight
     * back, and not one item of it reaches the port.
     * <p>
     * Nothing in Wareworks checks a direction here. The port answers {@code Capabilities.ItemHandler.BLOCK} with an
     * extract-only view on every side, so {@code DefaultUnpackingHandler}'s simulate pass cannot place a single stack,
     * returns false, and {@code PackagerItemHandler#insertItem} returns the box unchanged — which is also precisely why
     * a player cannot wire a door the wrong way round. If the port ever answered an insert-capable view, packages would
     * start vanishing into a station buffer and <i>nothing else in the suite would notice</i>.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packagerBehindAPortCanNeverUnpack(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        ItemStack box = packageOf(IRON.toStack(IRON_IN_BOX), GOLD.toStack(GOLD_IN_BOX));

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = portAt(helper);
                    // A few items of the port's own, so "nothing entered" is a statement about a buffer in use.
                    helper.assertTrue(port.insert(DIAMOND.toStack(DIAMONDS_IN_BOX), false).isEmpty(),
                            "the port took the crane's delivery");
                    Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS_IN_BOX);
                    ItemCensus.assertEquals(helper, conserved, "before a package is offered to the door");

                    // The structural reason, asserted directly: the view the Packager was handed cannot take anything.
                    IItemHandler view = port.externalHandler();
                    helper.assertFalse(view.isItemValid(0, box), "a port's view accepts no item at all");
                    helper.assertValueEqual(view.insertItem(0, box.copy(), false).getCount(), 1,
                            "and returns whatever is pushed at it");

                    ItemStack back = packagerInventory(helper, PACKAGER).insertItem(0, box.copy(), false);
                    helper.assertValueEqual(back.getCount(), 1, "the package came straight back");
                    helper.assertValueEqual(contentsOf(back), contentsOf(box),
                            "and it still holds everything it arrived with");
                    helper.assertTrue(packagerAt(helper, PACKAGER).heldBox.isEmpty(), "the Packager took nothing in");
                    ItemCensus.assertEquals(helper, conserved, "a port never unpacks, so nothing moved");
                })
                .thenSucceed();
    }

    /**
     * <b>The direction lock, in half.</b> A Packager at a warehouse input can only unpack: a redstone edge and a sign
     * produce no box whatsoever, however much the input holds.
     * <p>
     * Again no Wareworks code decides this. The input answers with an insert-only view whose {@code extractItem} is
     * always empty, so {@code attemptToSend} finds no item present and returns without touching anything. A door built
     * at an input is therefore an in door and can be nothing else.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packagerBehindAnInputCanNeverPack(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_BOX);

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    WarehouseInputBlockEntity input = inputAt(helper);
                    helper.assertTrue(input.insert(IRON.toStack(IRON_IN_BOX), false).isEmpty(), "the input took iron");
                    helper.assertTrue(input.externalHandler().extractItem(0, STACK, true).isEmpty(),
                            "nothing can be taken out of an input through its automation view");
                    writeSign(helper, SIGN, true, ADDRESS);
                    helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    PackagerBlockEntity packager = packagerAt(helper, PACKAGER);
                    helper.assertTrue(packager.heldBox.isEmpty(), "a Packager at an input packs nothing, ever");
                    helper.assertTrue(packager.queuedExitingPackages.isEmpty(), "and queues nothing either");
                    // The sign was read all the same, which is what makes the failure a quiet one worth pinning.
                    helper.assertValueEqual(packager.signBasedAddress, ADDRESS, "the sign was read");
                    helper.assertValueEqual(inputAt(helper).bufferedItems().count(IRON), (long) IRON_IN_BOX,
                            "the iron is still in the input");
                    ItemCensus.assertEquals(helper, conserved, "nothing left an input through a Packager");
                })
                .thenSucceed();
    }

    /**
     * A Packager <b>placed</b> next to a warehouse input faces away from it by itself, so the player who builds an in
     * door never has to think about orientation.
     * <p>
     * The placement is real: a mock player holding the Packager uses the floor block, so Create's
     * {@code getStateForPlacement} runs with a genuine {@code BlockPlaceContext} and picks the facing off the only
     * neighbour that answers a null-side item capability. A Create change there would leave new doors facing the wrong
     * way while every other test in this file, which sets the facing itself, kept passing.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packagerPlacedAgainstAnInputFacesAway(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    placeByHand(helper, PACKAGER, AllBlocks.PACKAGER.asStack());
                    helper.assertBlockPresent(AllBlocks.PACKAGER.get(), PACKAGER);
                    helper.assertValueEqual(helper.getBlockState(PACKAGER).getValue(PackagerBlock.FACING),
                            PACKAGER_SIDE, "a placed Packager turns its back on the station it found");
                })
                .thenIdle(LAZY_TICKS)
                .thenExecute(() -> {
                    // And the facing really does make the input its target, which is the half that matters.
                    helper.assertTrue(packagerAt(helper, PACKAGER).targetInventory.hasInventory(),
                            "the placed Packager resolved the input as its target inventory");
                    helper.assertValueEqual(PackageHandover.packagerFor(helper.getLevel(),
                            helper.absolutePos(STATION)), Optional.of(helper.absolutePos(PACKAGER)),
                            "and the station names it as its Packager");
                })
                .thenSucceed();
    }

    /**
     * A station's Packager is the one whose <b>back</b> touches it, and no other block in any other arrangement.
     * <p>
     * This is the rule that decides whether a door is described at all, and it is invertible without breaking anything
     * else: turn it round and the goggle lines simply never appear, while every assertion about addresses and refusals
     * still passes. So it is asserted on its own, for every side, in both facings, against a Repackager (which extends
     * {@code PackagerBlockEntity} and would pass a bare {@code instanceof}) and against a Packager one block too far.
     * <p>
     * {@code Direction.DOWN} is left out of the sweep because the template floor is there; the five remaining sides
     * cover both axes and both signs of each.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packagerFoundOnlyBehindTheStation(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        ServerLevel level = helper.getLevel();
        BlockPos station = helper.absolutePos(STATION);

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    helper.assertTrue(PackageHandover.packagerFor(level, station).isEmpty(),
                            "a station with nothing around it has no Packager");

                    for (Direction side : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST,
                            Direction.EAST)) {
                        BlockPos at = STATION.relative(side);
                        placePackager(helper, at, side);
                        helper.assertValueEqual(PackageHandover.packagerFor(level, station),
                                Optional.of(helper.absolutePos(at)),
                                "a Packager whose back touches the " + side + " face is the station's");

                        placePackager(helper, at, side.getOpposite());
                        helper.assertTrue(PackageHandover.packagerFor(level, station).isEmpty(),
                                "a Packager facing the station packs from something else, so it is not the door");

                        helper.setBlock(at, AllBlocks.REPACKAGER.getDefaultState()
                                .setValue(PackagerBlock.FACING, side));
                        helper.assertTrue(PackageHandover.packagerFor(level, station).isEmpty(),
                                "a Repackager only re-boxes network fragments and is never a door");
                        helper.setBlock(at, Blocks.AIR);
                    }

                    // One block too far is not a door either, however it is turned.
                    BlockPos tooFar = STATION.relative(PACKAGER_SIDE, 2);
                    placePackager(helper, tooFar, PACKAGER_SIDE);
                    helper.assertTrue(PackageHandover.packagerFor(level, station).isEmpty(),
                            "a Packager that does not touch the station is not its door");
                })
                .thenSucceed();
    }

    /**
     * The address Wareworks names is, character for character, the address Create writes on the box — for every shape
     * of sign that matters.
     * <p>
     * Wareworks <b>mirrors</b> Create's sign rule rather than calling it, because the field Create keeps is only
     * refreshed immediately before a send and is stale while a door stands idle. A mirror can drift, so this test
     * compares the two implementations directly: {@code PackageHandover.addressAt} against Create's own
     * {@code updateSignAddress}, over an empty sign, front text, back text, front winning over back, a multi-line
     * address with blank lines and stray spacing, and two signs where the <b>last</b> one in
     * {@code Direction.values()} order wins. The last shape then really is packed onto a box, so the comparison is
     * anchored to a package and not only to a field.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packageSignAddressMatchesCreate(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    assertSameAddress(helper, "no sign at all", PackagerSignAddress.NONE);

                    helper.setBlock(SIGN, Blocks.OAK_SIGN);
                    assertSameAddress(helper, "a blank sign", PackagerSignAddress.NONE);

                    writeSign(helper, SIGN, true, ADDRESS);
                    assertSameAddress(helper, "front text", ADDRESS);

                    writeSign(helper, SIGN, true, "");
                    writeSign(helper, SIGN, false, OTHER_ADDRESS);
                    assertSameAddress(helper, "back text only", OTHER_ADDRESS);

                    writeSign(helper, SIGN, true, ADDRESS);
                    assertSameAddress(helper, "front text wins over back text", ADDRESS);

                    // Each non-blank line trimmed and joined with one space; a blank line is skipped, not doubled.
                    writeSign(helper, SIGN, true, "  Base  ", "", " North ", "");
                    assertSameAddress(helper, "a multi-line address", ADDRESS);

                    helper.setBlock(EARLIER_SIGN, Blocks.OAK_SIGN);
                    writeSign(helper, EARLIER_SIGN, true, OTHER_ADDRESS);
                    assertSameAddress(helper, "two signs: the last one around the Packager wins", ADDRESS);
                })
                .thenExecute(() -> {
                    // And the winner is what a real box gets.
                    helper.assertTrue(portAt(helper).insert(IRON.toStack(IRON_IN_BOX), false).isEmpty(),
                            "the port took the crane's delivery");
                    helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, PACKAGER).heldBox.isEmpty(),
                        "the door packed a box"))
                .thenExecute(() -> {
                    ItemStack box = packagerAt(helper, PACKAGER).heldBox;
                    helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(),
                            helper.absolutePos(PACKAGER)), PackageItem.getAddress(box),
                            "the address Wareworks reads is the address Create wrote");
                    ItemCensus.assertEquals(helper, ItemCensus.of(IRON, IRON_IN_BOX),
                            "and the iron is accounted for inside the box");
                })
                .thenSucceed();
    }

    /**
     * A Stock Link on the Packager takes the door off redstone entirely — the one failure of this feature that nothing
     * else in the game diagnoses, and the reason the port's goggle tooltip carries a gold line about it.
     * <p>
     * {@code redstoneModeActive()} is {@code !LINKED}, both of its callers check it, and the Packager gives no feedback
     * of any kind: a signal arrives, nothing happens, the items sit in the port for ever. The link is placed as a real
     * block rather than by setting {@code LINKED} by hand, because Create heals a hand-set flag away in the next lazy
     * tick ({@code recheckIfLinksPresent}) — which this test also pins, by removing the link again and watching the
     * same door open.
     * <p>
     * It also pins what the port is allowed to <b>say</b> while the link is on. Create applies a sign on one branch
     * only, {@code if (!requestQueue && !signBasedAddress.isBlank())}, and a linked Packager never reaches it: the
     * redstone callers stop at {@code !redstoneModeActive()}, and the logistics network's caller always passes a
     * request list, so such a box carries the <b>order's</b> address. So while the link is on the gold row must stand
     * <b>in place of</b> the address row, not beside it — otherwise the tooltip predicts an address no box will carry,
     * and without a sign it asks the player for one that provably changes nothing.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packagerLinkedToANetworkIgnoresRedstone(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_BOX);

        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(portAt(helper).insert(IRON.toStack(IRON_IN_BOX), false).isEmpty(),
                            "the port took the crane's delivery");
                    writeSign(helper, SIGN, true, ADDRESS);
                    helper.setBlock(STOCK_LINK, AllBlocks.STOCK_LINK.getDefaultState()
                            // Attached to the Packager below it, so the direction it connects in is UP -- which is
                            // the direction Create's getLinkPos looks for when it walks out from the Packager.
                            .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR));
                })
                .thenWaitUntil(() -> helper.assertBlockProperty(PACKAGER, PackagerBlock.LINKED, true))
                .thenExecute(() -> {
                    helper.assertTrue(PackageHandover.ignoresRedstone(helper.getLevel(),
                            helper.absolutePos(PACKAGER)), "the port can tell the door is off redstone");
                    helper.assertFalse(packagerAt(helper, PACKAGER).redstoneModeActive(),
                            "and that is exactly the gate Create itself uses");

                    // And what the port is allowed to say about it: no address row at all, although a sign is
                    // hanging there and would be read the moment the link came off.
                    helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(),
                            helper.absolutePos(PACKAGER)), ADDRESS, "the sign still spells the address");
                    helper.assertTrue(portAt(helper).packageAddressShown().isEmpty(),
                            "a linked door must promise no address, because it can apply none");

                    helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(packagerAt(helper, PACKAGER).heldBox.isEmpty(),
                            "a linked Packager answers no redstone edge, and says nothing about it");
                    helper.assertValueEqual(portAt(helper).bufferedItems().count(IRON), (long) IRON_IN_BOX,
                            "so the goods wait in the door for ever");
                    ItemCensus.assertEquals(helper, conserved, "nothing is lost while a door is stuck");

                    // Take the link away and the very same door opens: the diagnosis names something a player can fix.
                    helper.setBlock(STOCK_LINK, Blocks.AIR);
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(PackageHandover.ignoresRedstone(helper.getLevel(),
                            helper.absolutePos(PACKAGER)), "the door is back on redstone");
                    helper.assertFalse(packagerAt(helper, PACKAGER).heldBox.isEmpty(), "and it packed the goods");
                })
                .thenExecute(() -> {
                    ItemStack box = packagerAt(helper, PACKAGER).heldBox;
                    helper.assertValueEqual(PackageItem.getAddress(box), ADDRESS, "with the sign's address");
                    ItemCensus.assertEquals(helper, conserved, "and the iron inside it");

                    // And the address the port predicts is back, now that the sign is the channel again.
                    helper.assertValueEqual(portAt(helper).packageAddressShown(), Optional.of(ADDRESS),
                            "the door names its address again once the link is gone");
                })
                .thenSucceed();
    }

    // --- the in door -------------------------------------------------------------------------------------------------

    /**
     * The whole in door, end to end: a hopper feeds packages at a Packager behind a warehouse input, the contents reach
     * the input's buffer, and the crane stores them in a rack.
     * <p>
     * This is also the required proof that the Wareworks unpacking handler registered for our block — which
     * <b>replaces</b> Create's default for it — did not close the way in. The handler is supposed to add nothing but a
     * diagnosis; if it ever did, every package in the game would stop at this block, and only a test that drives the
     * real funnel-to-rack path would notice.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageArrivalStored(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        BlockPos chest = aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos packager = aisle.inventoryPos(INPUT_RACK);
        BlockPos hopper = packager.above();
        placePackager(helper, packager, aisle.sideDirection(INPUT_RACK));
        helper.setBlock(hopper, AisleFixture.hopperState(Direction.DOWN));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while packages arrive at a door"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    // Two boxes, so the second has to wait out the first Packager cycle rather than racing it.
                    feedHopper(helper, hopper, conserved, 0, packageOf(IRON.toStack(IRON_IN_BOX)));
                    feedHopper(helper, hopper, conserved, 1,
                            packageOf(GOLD.toStack(GOLD_IN_BOX), DIAMOND.toStack(DIAMONDS_IN_BOX)));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(hopperAt(helper, hopper).isEmpty(), "both boxes went into the Packager");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_IN_BOX, "iron stored");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, GOLD), (long) GOLD_IN_BOX, "gold stored");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) DIAMONDS_IN_BOX,
                            "diamonds stored");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertFalse(aisle.inputAt(INPUT_RACK).hasBufferedItems(), "the input buffer is empty again");
                    // Create destroys the box, and nothing anywhere holds one: the warehouse stores goods, not boxes.
                    helper.assertValueEqual(countPackagesIn(helper, chest), 0, "no box was stored as an item");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), (long) IRON_IN_BOX,
                            "and what arrived in a box is ordinary stock");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * The all-or-nothing cliff as a player meets it: a package that does not fit the input <b>whole</b> is refused
     * whole, and it waits in the funnel that offered it instead of being half-emptied or lost.
     * <p>
     * That is the right backpressure and it is Create's, not ours — but it is also invisible, which is why the input
     * records how many stacks found no room beside how many slots were free. A hopper retries every few ticks for the
     * whole refusal, so the per-tick conservation assertion covers dozens of failed attempts, each of which runs
     * Create's simulate pass against a real buffer. The crane is then started and the same package goes in.
     * <p>
     * The two numbers themselves, and the refusal through the Packager's bare capability, belong to
     * {@code PackageUnpackingGameTests}; what is new here is the funnel, the census across the retries and the crane
     * that clears the jam.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void packageArrivalRefusedWhenFull(GameTestHelper helper) {
        // No motor: a crane that cannot move is what makes a full buffer stay full long enough to be seen.
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos packager = aisle.inventoryPos(INPUT_RACK);
        BlockPos hopper = packager.above();
        placePackager(helper, packager, aisle.sideDirection(INPUT_RACK));
        helper.setBlock(hopper, AisleFixture.hopperState(Direction.DOWN));
        ItemStack box = packageOf(IRON.toStack(IRON_IN_BOX), GOLD.toStack(GOLD_IN_BOX),
                DIAMOND.toStack(DIAMONDS_IN_BOX));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a full door refuses a package"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    fillAllButOneSlot(helper, aisle.inputAt(INPUT_RACK), conserved);
                    feedHopper(helper, hopper, conserved, 0, box.copy());
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(hopperAt(helper, hopper).getItem(0).getCount(), 1,
                            "the package is still in the funnel, offered and refused over and over");
                    helper.assertValueEqual(contentsOf(hopperAt(helper, hopper).getItem(0)), contentsOf(box),
                            "and it still holds everything");
                    helper.assertValueEqual(aisle.inputAt(INPUT_RACK).bufferedItems().count(IRON), 0L,
                            "not one item of a refused package enters");
                    helper.assertTrue(observe(aisle.inputAt(INPUT_RACK)).hasRefusal(),
                            "the input recorded the refusal nothing else in the game names");

                    // The crane arrives: the buffer drains, and the very same package fits.
                    aisle.placeMotor();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(hopperAt(helper, hopper).isEmpty(), "the funnel finally got rid of the package");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_IN_BOX, "iron stored");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, GOLD), (long) GOLD_IN_BOX, "gold stored");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) DIAMONDS_IN_BOX,
                            "diamonds stored");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertFalse(observe(aisle.inputAt(INPUT_RACK)).hasRefusal(),
                            "and the refusal is history once a package fits");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A package arrives holding something no storage location of this warehouse accepts, and it takes the existing
     * overflow path: the box is opened, the contents enter the input, and the warehouse hands them to the accepting
     * port — never back out as a package, because Wareworks cannot make one.
     * <p>
     * Composed deliberately out of parts that already work: the dedication of {@code filterMixedStreamPerChest} and the
     * unwired overflow port of {@code portOverflowUnwired}. The point is that M26 adds <b>no</b> row to the overflow
     * table in {@code docs/warehouse-system.md} §8 — once the box is open, the items are ordinary items.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageArrivalTakesTheOverflow(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(PORT_RACK);
        BlockPos packager = aisle.inventoryPos(INPUT_RACK);
        BlockPos hopper = packager.above();
        placePackager(helper, packager, aisle.sideDirection(INPUT_RACK));
        helper.setBlock(hopper, AisleFixture.hopperState(Direction.DOWN));
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an arrival takes the overflow"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 1))
                .thenExecute(() -> {
                    // The only rack of this aisle is dedicated to diamonds, so the iron can go nowhere but out again.
                    aisle.setStoreFilter(STORAGE_RACK, DIAMOND.toStack());
                    WarehouseOutputBlockEntity port = aisle.outputAt(PORT_RACK);
                    helper.assertTrue(port.setPortRank(-1), "the port accepts now");
                    port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
                    helper.assertValueEqual(port.portDirection(), PortDirection.ACCEPT, "an accepting overflow port");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feedHopper(helper, hopper, conserved, 0,
                            packageOf(IRON.toStack(IRON_IN_BOX), DIAMOND.toStack(DIAMONDS_IN_BOX)));
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) DIAMONDS_IN_BOX,
                            "what the rack is dedicated to was stored");
                    helper.assertValueEqual(aisle.stationCount(PORT_RACK, IRON), (long) IRON_IN_BOX,
                            "and what it refuses left through the overflow port, as ordinary items");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertFalse(aisle.inputAt(INPUT_RACK).hasBufferedItems(), "nothing jammed the input");
                    helper.assertValueEqual(aisle.controller().countOf(IRON), 0L,
                            "and what sits in a port is not stock");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * A package addressed to somewhere else is unpacked all the same, and that is asserted <b>on purpose</b> so nobody
     * silently "fixes" it.
     * <p>
     * Wareworks owns no address and matches none. An in door takes what is pushed into it, exactly as a chest does; the
     * block that filters by address is Create's own Package Port, and the clean build puts one in front of the door.
     * Teaching the door to refuse a foreign address would back a funnel up for ever with nothing to read.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void packageAddressedElsewhereIsStillUnpacked(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        ItemStack box = addressedPackage(OTHER_ADDRESS, IRON.toStack(IRON_IN_BOX));

        helper.startSequence()
                .thenIdle(SETTLE_SHORT)
                .thenExecute(() -> {
                    // The door's own sign says something different, and it changes nothing about what comes in.
                    writeSign(helper, SIGN, true, ADDRESS);
                    helper.assertValueEqual(PackageItem.getAddress(box), OTHER_ADDRESS, "the box is addressed away");
                    helper.assertTrue(packagerInventory(helper, PACKAGER).insertItem(0, box.copy(), false).isEmpty(),
                            "an in door takes a package addressed elsewhere");
                    helper.assertValueEqual(inputAt(helper).bufferedItems().count(IRON), (long) IRON_IN_BOX,
                            "and unpacks it like any other");
                    ItemCensus.assertEquals(helper, ItemCensus.of(IRON, IRON_IN_BOX),
                            "with every item accounted for");
                })
                .thenSucceed();
    }

    /**
     * A package that reaches a warehouse input with <b>no Packager anywhere</b> is stored as an ordinary item — and one
     * stock entry per address, because an {@link ItemKey} is the item together with its components.
     * <p>
     * The consequence is accepted and documented rather than policed: boxes of different addresses are as many distinct
     * stock entries as differently enchanted books are, and refusing them would jam a funnel instead. This test is what
     * makes that a decision rather than a surprise; it also pins that the contents of a stored box stay visible to the
     * conservation census, which is the only reason a box sitting in a rack is not a blind spot.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageStoredAsAnItem(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK);
        aisle.input(INPUT_RACK);
        BlockPos hopper = aisle.rackPos(INPUT_RACK).above();
        helper.setBlock(hopper, AisleFixture.hopperState(Direction.DOWN));
        ItemStack here = addressedPackage(ADDRESS, IRON.toStack());
        ItemStack away = addressedPackage(OTHER_ADDRESS, GOLD.toStack());
        ItemKey hereKey = ItemKey.of(here);
        ItemKey awayKey = ItemKey.of(away);
        Map<ItemKey, Long> conserved = ItemCensus.of();
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while boxes are stored as items"));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    helper.assertFalse(hereKey.equals(awayKey), "two addresses are two stock entries");
                    helper.assertTrue(hereKey.getItem() instanceof PackageItem, "and both of them are packages");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    feedHopper(helper, hopper, conserved, 0, here.copy());
                    feedHopper(helper, hopper, conserved, 1, here.copy());
                    feedHopper(helper, hopper, conserved, 2, away.copy());
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, hereKey), 2L, "both boxes of one address");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, awayKey), 1L, "and the other one beside them");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.controller().countOf(hereKey), 2L, "counted as stock by address");
                    helper.assertValueEqual(aisle.controller().countOf(awayKey), 1L, "and the other address apart");
                    // The census never saw a box at all: three boxes are two iron and one gold, wherever they stand.
                    ItemCensus.assertEquals(helper, ItemCensus.of(IRON, 2, GOLD, 1),
                            "a stored box is still its contents to the conservation gate");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- the box as a thing in the world -----------------------------------------------------------------------------

    /**
     * A door caught mid-handover survives a save and a load: the box the Packager holds, the address on it and whatever
     * is left in the port buffer all come back.
     * <p>
     * Wareworks persists nothing new for any of this — the box is Create's {@code "HeldBox"} and the buffer is the
     * port's existing one — which is the claim being checked. A block entity rebuilt from its own save is exactly what a
     * chunk load builds.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packagePersistenceMidHandover(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        // More than a box holds in one go, so the port keeps something back and both halves have to persist.
        int kept = DIAMONDS_IN_BOX;

        helper.startSequence()
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = portAt(helper);
                    helper.assertTrue(port.insert(IRON.toStack(IRON_IN_BOX), false).isEmpty(), "iron delivered");
                    writeSign(helper, SIGN, true, ADDRESS);
                    helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, PACKAGER).heldBox.isEmpty(),
                        "the door packed a box"))
                .thenExecute(() -> {
                    // A second delivery the Packager has not taken yet: it must still be in the port after the load.
                    helper.assertTrue(portAt(helper).insert(DIAMOND.toStack(kept), false).isEmpty(),
                            "a second delivery while the box waits");
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    PackagerBlockEntity packager = packagerAt(helper, PACKAGER);
                    ItemStack boxBefore = packager.heldBox.copy();

                    PackagerBlockEntity loadedPackager = loadCopy(helper, packager,
                            packager.saveWithFullMetadata(registries), PackagerBlockEntity.class);
                    helper.assertTrue(ItemStack.matches(loadedPackager.heldBox, boxBefore),
                            "the box survived the save: " + loadedPackager.heldBox);
                    helper.assertValueEqual(PackageItem.getAddress(loadedPackager.heldBox), ADDRESS,
                            "and so did its address");

                    WarehouseOutputBlockEntity loadedPort = loadCopy(helper, portAt(helper),
                            portAt(helper).saveWithFullMetadata(registries), WarehouseOutputBlockEntity.class);
                    helper.assertValueEqual(loadedPort.bufferedItems().count(DIAMOND), (long) kept,
                            "what the door had not handed over yet is still there");
                    ItemCensus.assertEquals(helper, ItemCensus.of(IRON, IRON_IN_BOX, DIAMOND, kept),
                            "nothing was created or lost by saving a door mid-handover");
                })
                .thenSucceed();
    }

    /**
     * A box that falls out of a broken door is still its contents to the conservation gate, both while it lies there
     * and after it is destroyed.
     * <p>
     * This is census rule 2 on the path a player really takes: Create drops the held box through the Packager's own
     * {@code destroy()}, and NeoForge then swaps the dropped {@code ItemEntity} for a {@code PackageEntity} — a
     * {@code LivingEntity} that no item-entity sweep can see, and one that can <b>die</b> where it lands: dropped
     * against the station it served it may suffocate at once, and then its contents lie on the floor and the box is
     * gone. Both outcomes are real and roughly equally likely, so what is asserted here is the census, which is
     * identical for both — asserting the box itself is what made this test fail one run in two. The swap is deferred
     * to a server task, so the census is taken once it has happened rather than every tick: across the drop there
     * really is a moment when the contents are in no inventory and no entity, and a per-tick assertion would read zero
     * for it — honest, and useless. How long
     * that moment lasts is not promised either, which is why the swap is waited for and not counted in ticks.
     * {@code ItemCensusGameTests} pins the same rule on a package spawned directly; what is pinned here is Create's
     * real drop, off a door a player broke.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packageEntityDroppedCountsAsItsContents(GameTestHelper helper) {
        placeStation(helper, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState());
        placePackager(helper, PACKAGER, PACKAGER_SIDE);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_BOX);

        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(portAt(helper).insert(IRON.toStack(IRON_IN_BOX), false).isEmpty(),
                            "iron delivered");
                    writeSign(helper, SIGN, true, ADDRESS);
                    helper.setBlock(POWER, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> {
                    PackagerBlockEntity packager = packagerAt(helper, PACKAGER);
                    helper.assertFalse(packager.heldBox.isEmpty(), "the door packed a box");
                    helper.assertValueEqual(packager.animationTicks, 0, "and finished its cycle");
                })
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved, "before the door is broken");
                    helper.getLevel().destroyBlock(helper.absolutePos(PACKAGER), false);
                })
                // Waited for, never counted in ticks: NeoForge discards the dropped item entity, cancels its join and
                // re-adds the package entity from a server task, and a task drain is not promised within any fixed
                // number of ticks. This test measured two and failed one run in two.
                .thenWaitUntil(() -> {
                    if (helper.getEntities(AllEntityTypes.PACKAGE.get()).isEmpty()
                            && helper.getEntities(EntityType.ITEM).isEmpty())
                        helper.fail("the broken door must leave its box behind — as a package entity, or as the "
                                + "contents of one that did not survive — but the world holds neither");
                })
                .thenExecute(() -> {
                    List<PackageEntity> boxes = helper.getEntities(AllEntityTypes.PACKAGE.get());
                    ItemCensus.assertEquals(helper, conserved, "whichever form the box took, nothing was lost");
                    if (boxes.isEmpty())
                        // A box is a LivingEntity, and one dropped against the station it served can suffocate before
                        // anybody has looked at it; its contents then lie on the floor instead. That is why this test
                        // asserts the census and not the form: both outcomes conserve, and only one of them is a box.
                        // Measured on this machine, each outcome happens about half the time.
                        return;
                    helper.assertValueEqual(boxes.size(), 1, "exactly one box lies where the door stood");
                    helper.assertValueEqual(PackageItem.getAddress(boxes.getFirst().getBox()), ADDRESS,
                            "still addressed");
                    ItemCensus.assertEquals(helper, conserved, "a box lying in the world is its contents");

                    // Destroyed: the contents drop as item entities and the box simply ceases to exist, which conserves
                    // exactly because the box never counted for anything.
                    boxes.getFirst().hurt(helper.getLevel().damageSources().explosion(null, null), KILLING_DAMAGE);
                    helper.assertTrue(boxes.getFirst().isRemoved(), "the package entity is gone");
                    ItemCensus.assertEquals(helper, conserved, "and its contents dropped where it stood");
                })
                .thenSucceed();
    }

    // --- building ----------------------------------------------------------------------------------------------------

    /** The bare station of an {@code empty_7x5x7} test, with its opening facing north. */
    private static void placeStation(GameTestHelper helper, BlockState station) {
        helper.setBlock(STATION, station.setValue(WarehouseStationBlock.FACING, Direction.NORTH));
    }

    /**
     * A Create Packager at {@code pos} facing {@code facing} — so its <b>back</b> touches the block at
     * {@code pos - facing}, which is the station it serves.
     */
    private static void placePackager(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, facing));
    }

    /**
     * Places the block of {@code item} at {@code pos} the way a player does: a mock player uses the block below it, so
     * the item's own {@code BlockItem#place} runs with a real {@code BlockPlaceContext} and the block's
     * {@code getStateForPlacement} decides the state.
     * <p>
     * The hit is the top face of the supporting block, which is never replaceable, so the placement lands at
     * {@code pos} and {@code getNearestLookingDirections} starts at {@code DOWN} — the template floor, which has no
     * block entity and is skipped by Create's search for a target inventory.
     */
    private static void placeByHand(GameTestHelper helper, BlockPos pos, ItemStack item) {
        BlockPos support = pos.below();
        helper.assertFalse(helper.getBlockState(support).isAir(), "the block placed on must exist");
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos absolute = helper.absolutePos(support);
        Vec3 hit = Vec3.atCenterOf(absolute).add(0, 0.5, 0);
        helper.useBlock(support, player, new BlockHitResult(hit, Direction.UP, absolute, false));
    }

    /**
     * Writes {@code lines} on the front or back of the sign at {@code pos}, placing a standing sign first if there is
     * none. Lines beyond {@code SignText.LINES} are a programming error in the test, not a case to handle.
     */
    private static void writeSign(GameTestHelper helper, BlockPos pos, boolean front, String... lines) {
        if (!(helper.getBlockState(pos).getBlock() instanceof SignBlock))
            helper.setBlock(pos, Blocks.OAK_SIGN);
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof SignBlockEntity sign)) {
            helper.fail("missing sign block entity", pos);
            return;
        }
        SignText text = new SignText();
        for (int line = 0; line < lines.length && line < SignText.LINES; line++)
            text = text.setMessage(line, Component.literal(lines[line]));
        helper.assertTrue(sign.setText(text, front), "the sign took the text");
    }

    /** A deterministic Create package holding {@code contents}, built from the component and never through Create. */
    private static ItemStack packageOf(ItemStack... contents) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    /** A {@link #packageOf} box addressed to {@code address}, as a Packager with a sign would have addressed it. */
    private static ItemStack addressedPackage(String address, ItemStack... contents) {
        ItemStack box = packageOf(contents);
        PackageItem.addAddress(box, address);
        return box;
    }

    /** Puts {@code stack} into one slot of the funnel hopper and keeps the conservation expectation in step. */
    private static void feedHopper(GameTestHelper helper, BlockPos pos, Map<ItemKey, Long> conserved, int slot,
            ItemStack stack) {
        hopperAt(helper, pos).setItem(slot, stack.copy());
        ItemCensus.change(conserved, ItemKey.of(stack), stack.getCount());
    }

    /**
     * Fills every buffer slot of {@code input} but one with a full stack of a distinct, storable item, and keeps the
     * conservation expectation in step.
     */
    private static void fillAllButOneSlot(GameTestHelper helper, WarehouseInputBlockEntity input,
            Map<ItemKey, Long> conserved) {
        int toFill = input.bufferSlots() - 1;
        helper.assertTrue(toFill >= 1 && toFill <= FILLERS.size(),
                "the buffer has " + input.bufferSlots() + " slots, which this test can fill");
        for (int slot = 0; slot < toFill; slot++) {
            ItemStack filler = new ItemStack(FILLERS.get(slot), STACK);
            helper.assertTrue(input.insert(filler.copy(), false).isEmpty(), "filler " + filler + " accepted");
            ItemCensus.change(conserved, ItemKey.of(filler), STACK);
        }
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    /**
     * What {@code box} holds, in the terms the conservation census uses: a package counts as its contents, so this is
     * also exactly what the census reports for a box standing anywhere in the test.
     */
    private static Map<ItemKey, Long> contentsOf(ItemStack box) {
        return ItemCensus.of(ItemKey.of(box), 1);
    }

    /**
     * The input's package summary through the real goggle path: the numbers are plain server fields and
     * {@code summary()} only answers what the last observation built, which is the discipline that keeps a funnel
     * retrying every tick from sending a packet every tick.
     */
    private static PackageUnpackSummary observe(WarehouseInputBlockEntity input) {
        input.onGoggleObserved();
        return input.summary().packages();
    }

    /**
     * Asserts that {@link PackageHandover#addressAt} answers {@code expected} and that Create's own
     * {@code updateSignAddress} answers the same thing — the assertion that makes a drift between the mirror and the
     * original impossible to ship.
     */
    private static void assertSameAddress(GameTestHelper helper, String shape, String expected) {
        PackagerBlockEntity packager = packagerAt(helper, PACKAGER);
        packager.updateSignAddress();
        helper.assertValueEqual(packager.signBasedAddress, expected, "Create reads " + shape);
        helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(), helper.absolutePos(PACKAGER)), expected,
                "and Wareworks reads " + shape + " the same way");
    }

    /** Create packages lying in the inventory at a test-relative position (0 without an inventory). */
    private static int countPackagesIn(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null)
            return 0;
        int boxes = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (PackageItem.isPackage(handler.getStackInSlot(slot)))
                boxes += handler.getStackInSlot(slot).getCount();
        return boxes;
    }

    private static PackagerBlockEntity packagerAt(GameTestHelper helper, BlockPos pos) {
        PackagerBlockEntity be = AllBlockEntityTypes.PACKAGER.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing Create packager block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    /** The Packager's own item capability: the call a funnel, a chute or a Frogport makes. */
    private static IItemHandler packagerInventory(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null) {
            helper.fail("the packager exposes no item handler", pos);
            throw new IllegalStateException("unreachable");
        }
        return handler;
    }

    private static WarehouseOutputBlockEntity portAt(GameTestHelper helper) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(helper.getLevel(),
                helper.absolutePos(STATION));
        if (be == null) {
            helper.fail("missing warehouse output block entity", STATION);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    private static WarehouseInputBlockEntity inputAt(GameTestHelper helper) {
        WarehouseInputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(helper.getLevel(),
                helper.absolutePos(STATION));
        if (be == null) {
            helper.fail("missing warehouse input block entity", STATION);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    private static HopperBlockEntity hopperAt(GameTestHelper helper, BlockPos pos) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof HopperBlockEntity hopper)) {
            helper.fail("missing hopper block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return hopper;
    }

    /** A fresh block entity of the same type loaded from {@code tag}, standing in for a chunk load. */
    private static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
