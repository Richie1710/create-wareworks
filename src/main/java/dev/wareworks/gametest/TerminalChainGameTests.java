package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.ProductionScreenState;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.production.ProductionOrderState;
import dev.wareworks.core.terminal.PlanCancelCost;
import dev.wareworks.core.terminal.PlanLine;
import dev.wareworks.core.terminal.PlanLines;
import dev.wareworks.core.terminal.PlanMember;
import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalResultPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of what a <b>warehouse terminal tells its screen about a chain</b> (M20, issue #4, ADR-032,
 * {@code docs/warehouse-system.md} §3.4.2, §3.5.6).
 * <p>
 * The screen itself is client code, so what can be proved here is everything the client is given and everything a click
 * of it does — which is the whole of the trust boundary:
 * <ul>
 * <li>{@code terminalchainrowsnametheplan} — the rows of a running two-level chain: the plan each order belongs to, its
 * depth in it, the <b>address of the station it runs at</b> and the blocked root that fetches nothing. Then the grouping
 * the screen applies to exactly these rows ({@code core.terminal.PlanLines}), so the pure model and the real payload are
 * checked against each other rather than only against a hand-written list;</li>
 * <li>{@code terminalchainrowssurvivethewire} — the same rows through the real payload codec, and the bound on how many
 * of them one payload carries;</li>
 * <li>{@code terminalchaincancelfromthescreenendsthewholeplan} — the cancel a click sends, re-checked on arrival: the id
 * of <b>one</b> step ends every order of its plan, and a second click changes nothing;</li>
 * <li>{@code terminalchaincancelcostcountsonlyopenorders} — the number the step panel puts in front of a player before
 * they give up: ingredient items at the machines of the orders it would <b>end</b>, never those of a member that has
 * already finished;</li>
 * <li>{@code terminalrefusalnamesthemissingitem} — the other half of a click: what the screen is told when the chain
 * could <b>not</b> be planned, which names the item that is really missing rather than the one that was clicked;</li>
 * <li>{@code terminalsinglelevelrowsareunchanged} — the guarantee that a warehouse which runs no chains looks exactly as
 * it did before M20, on the wire and in the grouping.</li>
 * </ul>
 * Layout ({@code aisle_16x10x7}): the {@link AisleFixture} aisle at z = 3 with {@value #RAILS} rails, deliberately
 * <b>without</b> a motor, so a test sees the orders exactly as the click created them instead of racing a crane. Right
 * rack plane: the saw station at 0 (one log makes {@value #PLANKS_PER_RUN} planks), a warehouse input at 1, a warehouse
 * output at 2 and the crafter station at 3 ({@value #PLANKS_PER_CHEST} planks make one chest). Left rack plane: the
 * terminal at 0, a chest of logs at 1 and an empty storage location at 2.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalChainGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1200;

    private static final RackPosition SAW_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition INPUT_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition CRAFTER_RACK = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition LOG_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition STORE_RACK = new RackPosition(2, 0, Side.LEFT);

    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);

    /** One log makes four planks, the pattern from the feature request. */
    private static final int PLANKS_PER_RUN = 4;
    /** Four planks make one chest: exactly one run of the saw's pattern, so the chain has no surplus to hide in. */
    private static final int PLANKS_PER_CHEST = 4;
    private static final int LOGS_IN_STOCK = 16;
    /** A container id no menu of these tests has, for the hostile half of the cancel test. */
    private static final int OTHER_CONTAINER_ID = 4321;

    private TerminalChainGameTests() {
    }

    /**
     * <b>A chain is one line, and every row of it says where its machine is.</b> A chest ordered with nothing but logs
     * in the racks creates two orders; this is what the terminal hands the screen about them.
     * <p>
     * The blocked root is the row that matters most. It is nominally waiting for ingredients, but while its step runs it
     * fetches <b>nothing at all</b> (ADR-032), so a screen that showed only its state would look like a stuck crane.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalChainRowsNameThePlan(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertChainReady(helper, aisle)).thenExecute(() -> {
            WarehouseControllerBlockEntity controller = aisle.controller();
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            Optional<RequestResult> ordered = WarehouseTerminalMenu.submitRequest(player, menu.containerId, CHEST, 1);
            helper.assertTrue(ordered.isPresent() && ordered.get().isAccepted(), "the chest is ordered: " + ordered);

            List<ProductionScreenState.OrderView> rows = terminal.productionOrderViews();
            helper.assertValueEqual(rows.size(), 2, "the screen is told about both orders of the chain");
            ProductionScreenState.OrderView step = row(helper, rows, PLANK);
            ProductionScreenState.OrderView root = row(helper, rows, CHEST);
            UUID rootId = root.id();

            // Both rows name the same plan, and the plan is the ordered item's own order.
            helper.assertValueEqual(root.plan(), Optional.of(rootId), "the root names its own plan");
            helper.assertValueEqual(step.plan(), Optional.of(rootId), "and the step names the same one");
            helper.assertValueEqual(root.depth(), 0, "the ordered item sits at the top");
            helper.assertValueEqual(step.depth(), 1, "its step one below");
            helper.assertFalse(root.isStep(), "the root is not a step of anything");
            helper.assertTrue(step.isStep(), "the other row is");

            // The address is what makes a stalled chain actionable: it says which machine to walk to.
            helper.assertValueEqual(step.address(), Optional.of(address(controller, SAW_RACK)), "the saw's address");
            helper.assertValueEqual(root.address(), Optional.of(address(controller, CRAFTER_RACK)),
                    "and the crafter's");

            // The blocked root fetches nothing while its step runs, and says so rather than looking stuck.
            helper.assertTrue(root.waitingForStep(), "the root is waiting for its step");
            helper.assertFalse(step.waitingForStep(), "the step is waiting for nobody");
            helper.assertValueEqual(root.state(), ProductionOrderState.WAITING_FOR_INGREDIENTS,
                    "its own state is still the ordinary one, which is why the flag is sent separately");

            // The grouping the screen applies to exactly these rows.
            PlanLine line = onlyLine(helper, rows);
            helper.assertTrue(line.isChain(), "the two rows are one chain");
            helper.assertValueEqual(line.head(), rootId, "named by the item the player ordered");
            helper.assertValueEqual(line.plan(), Optional.of(rootId), "so a cancel ends that plan");
            helper.assertValueEqual(line.members(), List.of(rootId, step.id()), "the head first, then its step");
            helper.assertValueEqual(line.frontier(), Optional.of(step.id()), "the step is where the work is");
            helper.assertFalse(line.frontierIsHead(), "so the line names the step, not the chest");
            helper.assertValueEqual(line.openMembers(), 2, "and giving up would end both orders");
        }).thenSucceed();
    }

    /**
     * <b>Every new field survives the wire.</b> The rows of a real chain are encoded and decoded with the payload's own
     * codec, because a field that is written but not read is invisible to a server-side test of the rows and to a
     * client-side test of the grouping alike — and the payload is the only thing between them.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalChainRowsSurviveTheWire(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertChainReady(helper, aisle)).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, CHEST, 1)
                    .filter(RequestResult::isAccepted).isPresent(), "the chest is ordered");

            List<ProductionScreenState.OrderView> rows = terminal.productionOrderViews();
            TerminalOrdersPayload sent = new TerminalOrdersPayload(menu.containerId, rows);
            TerminalOrdersPayload received = roundTrip(helper, sent);
            helper.assertValueEqual(received.orders(), rows, "every field of every row, plan fields included");
            helper.assertValueEqual(onlyLine(helper, received.orders()).members(), onlyLine(helper, rows).members(),
                    "so the client groups the very chain the server sent");

            // The bound holds whatever a length prefix claims: a chain is one order per step, and a payload says how
            // many of them it will ever carry.
            List<ProductionScreenState.OrderView> many = new ArrayList<>(rows);
            while (many.size() <= TerminalOrdersPayload.MAX_ORDERS)
                many.addAll(rows);
            helper.assertValueEqual(new TerminalOrdersPayload(menu.containerId, many).orders().size(),
                    TerminalOrdersPayload.MAX_ORDERS, "a payload never carries more rows than it may");

            // The depth is bounded like every other field of a row (review fix): the step panel turns it into an indent
            // string, so an unbounded varint would let a payload make a client build megabytes of spaces or overflow.
            ProductionScreenState.OrderView deep = withDepth(row(helper, rows, PLANK), Integer.MAX_VALUE);
            helper.assertValueEqual(deep.depth(), ProductionScreenState.MAX_DEPTH,
                    "a depth no real plan can reach is clamped where it is built, not only where it is read");
            helper.assertValueEqual(roundTrip(helper,
                            new TerminalOrdersPayload(menu.containerId, List.of(deep))).orders().getFirst().depth(),
                    ProductionScreenState.MAX_DEPTH, "and the same bound holds on the wire");
        }).thenSucceed();
    }

    /**
     * <b>A click gives up on the chain, not on one machine.</b> The screen sends nothing but a container id and an order
     * id, and the terminal re-checks both on arrival ({@code WarehouseTerminalBlockEntity#cancelProductionOrder}): the
     * player must still be allowed to use the block and the order must belong to <b>that</b> aisle.
     * <p>
     * The id sent is a <b>step</b> of the chain here, which is the interesting direction: cancelling it also ends every
     * order above it, because those are waiting for something that will never be made ({@code ProductionOrders
     * #failPlan}). Nothing had been delivered to a machine, so nothing is lost — that is the point of a blocked parent
     * fetching nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalChainCancelFromTheScreenEndsTheWholePlan(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertChainReady(helper, aisle)).thenExecute(() -> {
            WarehouseControllerBlockEntity controller = aisle.controller();
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, CHEST, 1)
                    .filter(RequestResult::isAccepted).isPresent(), "the chest is ordered");
            helper.assertValueEqual(controller.openProductionOrders().size(), 2, "a chain of two is running");
            UUID stepId = row(helper, terminal.productionOrderViews(), PLANK).id();
            long lost = 0L;
            for (ProductionScreenState.OrderView view : terminal.productionOrderViews())
                lost += view.delivered();
            helper.assertValueEqual(lost, 0L, "nothing has reached a machine, so giving up costs nothing");

            // Nothing without the right menu: the same guards a request passes.
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(player, menu.containerId, stepId).isEmpty(),
                    "a player without a terminal menu cancels nothing");
            player.containerMenu = menu;
            helper.assertTrue(WarehouseTerminalMenu.submitCancel(player, OTHER_CONTAINER_ID, stepId).isEmpty(),
                    "and neither does a crafted container id");
            helper.assertValueEqual(controller.openProductionOrders().size(), 2, "the chain is intact");

            // The real click: one step's id ends every order of its plan.
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, stepId),
                    Optional.of(true), "the step is cancelled");
            helper.assertValueEqual(controller.openProductionOrders().size(), 0,
                    "and with it the order that was waiting for it");
            helper.assertValueEqual(controller.openRequestCount(), 0,
                    "so nothing is left waiting for a chest nobody will make");
            helper.assertValueEqual(controller.availableStock(LOG), (long) LOGS_IN_STOCK,
                    "the logs the chain had promised are free again");

            List<ProductionScreenState.OrderView> after = terminal.productionOrderViews();
            helper.assertValueEqual(after.size(), 2, "both lines stay for a while, so a player can read what happened");
            for (ProductionScreenState.OrderView view : after) {
                helper.assertValueEqual(view.state(), ProductionOrderState.CANCELLED, "every row says it was given up");
                helper.assertFalse(view.waitingForStep(), "and nothing is waiting for anything any more");
            }
            helper.assertValueEqual(WarehouseTerminalMenu.submitCancel(player, menu.containerId, stepId),
                    Optional.of(false), "a finished order cannot be given up on twice");
        }).thenSucceed();
    }

    /**
     * <b>A warehouse that runs no chains looks exactly as it did before M20.</b> One order for planks, which the racks'
     * logs can pay for on their own, is a single row with no plan, no depth and nothing to wait for — and a line the
     * screen draws exactly as it drew every line before.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalSingleLevelRowsAreUnchanged(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertChainReady(helper, aisle)).thenExecute(() -> {
            WarehouseControllerBlockEntity controller = aisle.controller();
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, PLANK, PLANKS_PER_RUN)
                    .filter(RequestResult::isAccepted).isPresent(), "the planks are ordered");
            helper.assertValueEqual(controller.openProductionOrders().size(), 1, "one order, no chain");

            List<ProductionScreenState.OrderView> rows = terminal.productionOrderViews();
            helper.assertValueEqual(rows.size(), 1, "one row");
            ProductionScreenState.OrderView row = rows.getFirst();
            helper.assertValueEqual(row.plan(), Optional.empty(), "an order in no plan names no plan");
            helper.assertValueEqual(row.depth(), 0, "and sits at no depth");
            helper.assertFalse(row.waitingForStep(), "and waits for no step");
            helper.assertFalse(row.isStep(), "and is nobody's step");
            helper.assertValueEqual(row.address(), Optional.of(address(controller, SAW_RACK)),
                    "its station's address is named all the same, which is new but says nothing about chains");

            PlanLine line = onlyLine(helper, rows);
            helper.assertFalse(line.isChain(), "so the screen draws the line it always drew");
            helper.assertValueEqual(line.head(), row.id(), "the order itself names it");
            helper.assertValueEqual(line.members(), List.of(row.id()), "with nothing under it");
            helper.assertValueEqual(line.frontier(), Optional.of(row.id()), "it is its own frontier");
            helper.assertTrue(line.frontierIsHead(), "so its own state is what the line shows");
        }).thenSucceed();
    }

    /**
     * <b>What giving up on a chain costs is what {@code failPlan} would really do.</b> The step panel draws up to three
     * numbers before a player gives up: how many orders would end, how many ingredient items were already delivered and
     * never come back, and that the click arms the safety stop ({@code docs/warehouse-system.md} §3.5.6). All three come
     * from {@link PlanCancelCost}, and this pins them against the orders a real click created.
     * <p>
     * The state that matters is the one a chain sits in for most of its life, and the one a player opens the panel in while
     * a machine runs down its timeout: the <b>root blocked</b> with nothing at a machine and one <b>step at its machine</b>.
     * The server cancels the root, detaches the step and loses nothing, because the step still makes its planks — so the
     * panel must say one order and no loss. Summing the open members instead said two orders and a loss that never happens.
     * A member that ended badly is left out for the mirror-image reason: those items were lost when it ended.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalChainCancelCostCountsOnlyOpenOrders(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper);
        helper.startSequence().thenWaitUntil(() -> assertChainReady(helper, aisle)).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);
            helper.assertTrue(WarehouseTerminalMenu.submitRequest(player, menu.containerId, CHEST, 1)
                    .filter(RequestResult::isAccepted).isPresent(), "the chest is ordered");

            List<ProductionScreenState.OrderView> rows = terminal.productionOrderViews();
            PlanCancelCost fresh = cost(helper, rows);
            helper.assertValueEqual(fresh.endedOrders(), 2, "a chain that has fetched nothing collapses whole");
            helper.assertValueEqual(fresh.lostIngredients(), 0L, "and costs no items to give up");
            helper.assertFalse(fresh.armsSafetyStop(), "so it arms no safety stop either");

            ProductionScreenState.OrderView step = row(helper, rows, PLANK);
            ProductionScreenState.OrderView root = row(helper, rows, CHEST);
            // The state a player really clicks in: the step has its log at the saw, the blocked root has nothing.
            PlanCancelCost working = cost(helper, List.of(withDelivered(step, ProductionOrderState.DELIVERED, 1L), root));
            helper.assertValueEqual(working.target(), root.id(), "the click names the open root");
            helper.assertValueEqual(working.endedOrders(), 1,
                    "only the root ends: the step's batch is in the machine, so it is detached and runs on");
            helper.assertValueEqual(working.lostIngredients(), 0L,
                    "and nothing is lost, because that step still makes its planks");

            // The chain down to its last step: the step is complete and the root's planks are at the crafter.
            ProductionScreenState.OrderView doneStep = withDelivered(step, ProductionOrderState.COMPLETE, 1L);
            PlanCancelCost last = cost(helper,
                    List.of(doneStep, withDelivered(root, ProductionOrderState.DELIVERED, PLANKS_PER_CHEST)));
            helper.assertValueEqual(last.endedOrders(), 1, "the completed step is not touched by a cancellation");
            helper.assertValueEqual(last.lostIngredients(), (long) PLANKS_PER_CHEST,
                    "the named order's own batch is the whole loss");
            helper.assertTrue(last.armsSafetyStop(), "and it is what arms the safety stop for the chest");

            PlanLine line = onlyLine(helper, rows);
            helper.assertTrue(PlanCancelCost.of(line, ProductionScreenState.members(
                            List.of(doneStep, withDelivered(root, ProductionOrderState.TIMED_OUT, PLANKS_PER_CHEST))))
                    .isEmpty(), "a plan whose every member has finished can be given up on by nobody");
            helper.assertTrue(PlanCancelCost.of(line, List.of()).isEmpty(), "no orders, no cost");
        }).thenSucceed();
    }

    /**
     * <b>A refused click reaches the screen naming the item that is really missing</b> (M20 part 2, issue #4): the
     * promise the whole feature is judged by. Ordering a chest with not a log in the aisle is refused, and what the
     * server sends the screen is the {@link PlanRefusal} plus the <b>log</b> — not the chest that was clicked, and not
     * only the generic "not in stock" a warehouse port's goggles show.
     * <p>
     * Everything the client is given is checked through the real codec: the plain answers every pre-M20 request gets
     * still read exactly as they did, and an answer with a plan behind it carries both halves or neither, because half
     * a sentence would name nothing.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalRefusalNamesTheMissingItem(GameTestHelper helper) {
        AisleFixture aisle = chainAisle(helper, 0);
        helper.startSequence().thenWaitUntil(() -> {
            WarehouseControllerBlockEntity controller = aisle.controller();
            helper.assertValueEqual(controller.productionStations().size(), 2, "both production stations are recorded");
            helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage locations were read");
            helper.assertValueEqual(controller.countOf(LOG), 0L, "and there is not a log in the aisle");
        }).thenExecute(() -> {
            WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
            Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
            WarehouseTerminalMenu menu = openMenu(player, terminal);

            RequestResult result = WarehouseTerminalMenu.submitRequest(player, menu.containerId, CHEST, 1)
                    .orElseThrow(() -> new AssertionError("the click was answered"));
            helper.assertFalse(result.isAccepted(), "a chest cannot be made without a log: " + result);
            helper.assertValueEqual(result.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                    "why the chain could not be planned");
            helper.assertValueEqual(result.about(), Optional.of(LOG),
                    "and the item it is about, two steps below the chest");

            TerminalResultPayload sent = TerminalResultPayload.of(menu.containerId, CHEST, result);
            TerminalResultPayload seen = roundTrip(helper, sent);
            helper.assertValueEqual(seen.refusal(), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                    "the screen is told why");
            helper.assertValueEqual(seen.about(), Optional.of(LOG), "and which item to go and get");
            helper.assertValueEqual(seen.key(), CHEST, "while the item that was clicked is still the chest");
            helper.assertFalse(seen.isAccepted(), "and the answer is still a refusal");
            helper.assertValueEqual(seen.rejection(), result.rejection(),
                    "the reason a warehouse port's goggles show travels beside it, unchanged");

            // Every answer that walked no plan: the shape this payload had before M20, read back byte for byte.
            TerminalResultPayload plain = roundTrip(helper,
                    TerminalResultPayload.of(menu.containerId, CHEST, RequestResult.rejected(RequestRejection.NOT_IN_STOCK)));
            helper.assertValueEqual(plain.refusal(), Optional.empty(), "a plain refusal names no chain");
            helper.assertValueEqual(plain.about(), Optional.empty(), "and no item of its own");
            helper.assertValueEqual(plain.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK), "only its reason");

            // Half a sentence names nothing, so a payload built with one half only drops both rather than sending a
            // refusal with no item in it.
            TerminalResultPayload half = new TerminalResultPayload(menu.containerId, CHEST, 0, 0, 0,
                    Optional.of(RequestRejection.NOT_IN_STOCK), Optional.of(PlanRefusal.MISSING_INGREDIENT),
                    Optional.empty());
            helper.assertValueEqual(half.refusal(), Optional.empty(), "a refusal without its item is dropped");
            helper.assertValueEqual(roundTrip(helper, half).about(), Optional.empty(), "on the wire as well");
        }).thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A copy of {@code view} in {@code state} that has handed {@code delivered} ingredient items to its machine. */
    private static ProductionScreenState.OrderView withDelivered(ProductionScreenState.OrderView view,
            ProductionOrderState state, long delivered) {
        return new ProductionScreenState.OrderView(view.id(), state, view.result(), view.amount(), view.produced(),
                view.missing(), delivered, view.plan(), view.depth(), view.address(), view.waitingForStep());
    }

    /** The same row claiming to sit {@code depth} steps below its root, for the bound on that number. */
    private static ProductionScreenState.OrderView withDepth(ProductionScreenState.OrderView view, int depth) {
        return new ProductionScreenState.OrderView(view.id(), view.state(), view.result(), view.amount(),
                view.produced(), view.missing(), view.delivered(), view.plan(), depth, view.address(),
                view.waitingForStep());
    }

    /**
     * Two production stations whose patterns form a chain — one log to {@value #PLANKS_PER_RUN} planks at the saw,
     * {@value #PLANKS_PER_CHEST} planks to one chest at the crafter — a warehouse input for both products, an output, a
     * terminal and {@value #LOGS_IN_STOCK} logs in the racks. No motor: the crane never moves, so a test sees the orders
     * exactly as the click created them.
     */
    private static AisleFixture chainAisle(GameTestHelper helper) {
        return chainAisle(helper, LOGS_IN_STOCK);
    }

    /** The same aisle with {@code logs} logs in its racks; 0 is the aisle a chain cannot be planned in at all. */
    private static AisleFixture chainAisle(GameTestHelper helper, int logs) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        if (logs > 0)
            aisle.storage(LOG_RACK, LOG.toStack(logs));
        else
            aisle.storage(LOG_RACK);
        aisle.storage(STORE_RACK);
        aisle.input(INPUT_RACK);
        aisle.output(OUTPUT_RACK);
        aisle.production(SAW_RACK);
        aisle.production(CRAFTER_RACK);
        aisle.terminal(TERMINAL_RACK);
        setPattern(helper, aisle, SAW_RACK, LOG, 1, PLANK, PLANKS_PER_RUN);
        setPattern(helper, aisle, CRAFTER_RACK, PLANK, PLANKS_PER_CHEST, CHEST, 1);
        return aisle;
    }

    /** One ingredient to one result in the first pattern slot of the station at {@code rack}. */
    private static void setPattern(GameTestHelper helper, AisleFixture aisle, RackPosition rack, ItemKey ingredient,
            int ingredientCount, ItemKey result, int resultCount) {
        WarehouseProductionBlockEntity station = aisle.productionAt(rack);
        helper.assertTrue(station.setPatternEntry(0, 0, ingredient, ingredientCount), "the ingredient of " + rack);
        helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, result, resultCount),
                "the result of " + rack);
    }

    /** Waits until both stations are recorded, their patterns are readable and the logs are in the index. */
    private static void assertChainReady(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.productionStations().size(), 2, "both production stations are recorded");
        helper.assertValueEqual(controller.aislePatterns().size(), 2, "with one pattern each");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "the storage locations were read");
        helper.assertTrue(controller.producibleKeys().contains(CHEST), "and the aisle knows it can make a chest");
    }

    /** The canonical address of a rack position of the aisle, as the rows are expected to carry it. */
    private static String address(WarehouseControllerBlockEntity controller, RackPosition rack) {
        return StorageAddress.of(controller.aisleLetter(), rack).format();
    }

    /** The one row making {@code key}; fails the test when there is none or more than one. */
    private static ProductionScreenState.OrderView row(GameTestHelper helper,
            List<ProductionScreenState.OrderView> rows, ItemKey key) {
        ProductionScreenState.OrderView found = null;
        for (ProductionScreenState.OrderView row : rows) {
            if (!row.result().equals(key))
                continue;
            if (found != null)
                helper.fail("more than one row makes " + key);
            found = row;
        }
        if (found == null)
            helper.fail("no row makes " + key);
        return found;
    }

    /** The single line {@code rows} group into; fails the test when they group into another number. */
    private static PlanLine onlyLine(GameTestHelper helper, List<ProductionScreenState.OrderView> rows) {
        List<PlanLine> lines = PlanLines.of(ProductionScreenState.members(rows));
        helper.assertValueEqual(lines.size(), 1, "the rows are one line of the production section");
        return lines.getFirst();
    }

    /** What giving up on the one line {@code rows} group into would cost, exactly as the step panel computes it. */
    private static PlanCancelCost cost(GameTestHelper helper, List<ProductionScreenState.OrderView> rows) {
        List<PlanMember> members = ProductionScreenState.members(rows);
        PlanCancelCost cost = PlanCancelCost.of(onlyLine(helper, rows), members).orElse(null);
        if (cost == null)
            helper.fail("the line has no open order a cancellation could name");
        return cost;
    }

    /** The same, for the answer to one click. */
    private static TerminalResultPayload roundTrip(GameTestHelper helper, TerminalResultPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        TerminalResultPayload.STREAM_CODEC.encode(buffer, payload);
        TerminalResultPayload decoded = TerminalResultPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        return decoded;
    }

    /** Encodes and decodes a payload with its own codec, on a buffer with the server's registries. */
    private static TerminalOrdersPayload roundTrip(GameTestHelper helper, TerminalOrdersPayload payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        TerminalOrdersPayload.STREAM_CODEC.encode(buffer, payload);
        TerminalOrdersPayload decoded = TerminalOrdersPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the payload reads exactly what it wrote");
        return decoded;
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    /** A survival player standing at the test-relative position, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
