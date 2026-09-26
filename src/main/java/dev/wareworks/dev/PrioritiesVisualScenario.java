package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "priorities": the player decides which storage location fills first ({@code docs/warehouse-system.md} §3.1,
 * ADR-028, M16, issue #11).
 * <p>
 * One aisle, one rack row of six storage locations at level {@value #ROW_LEVEL}, a <b>belt</b> feeding the warehouse
 * input, and a crane that fills the row in the order the numbers demand. Every claim a screenshot makes is asserted on
 * the server <b>before</b> the shot that makes it — a picture of a priority that did not happen is worse than no
 * picture:
 * <ul>
 *   <li><b>the priority decides</b> — the row is empty, so travel time alone would fill the location nearest to the
 *       input. Instead the first delivery drives past the two nearest, unprioritised ones to the one a player
 *       prioritised {@value #PREFERRED_PRIORITY};</li>
 *   <li><b>a filter still outranks it</b> — one location is dedicated to {@link #DEDICATED_TO} and prioritised
 *       {@value #DEDICATED_PRIORITY}, higher than the preferred one. It never receives a single gold ingot, and it is
 *       the slot whose filter item and digit share one 6 px plate in the {@code slot} shot;</li>
 *   <li><b>the preferred location is used until it is full</b> — its attached inventory is a hopper, i.e.
 *       {@value #PREFERRED_SLOTS} slots, so the crane really fills it trip after trip
 *       ({@value #PREFERRED_CAPACITY} items) instead of the scenario topping it up by hand;</li>
 *   <li><b>then the ranking continues</b> — the overflow goes to the location prioritised
 *       {@value #FALLBACK_PRIORITY}, the <b>farthest</b> of the whole row, past three unprioritised ones the crane
 *       drives by on the way;</li>
 *   <li><b>a changed number takes effect</b> — in the Flywheel pass one location is raised from 0 to
 *       {@value #CHANGED_PRIORITY} between two shots of the same camera, a second item type is fed, and it lands
 *       there instead of in the two unprioritised locations nearer to the input that would have taken it before;</li>
 *   <li><b>retrieval ignores all of it</b> — the closing request is served out of the location prioritised
 *       {@value #FALLBACK_PRIORITY} because it is the cheaper source, not out of the one prioritised
 *       {@value #PREFERRED_PRIORITY}. The crane is standing beyond both when the job is planned, so the two travel
 *       times really differ (on a straight aisle every source <i>between</i> crane and output costs the same).</li>
 * </ul>
 * The order of the deliveries is recorded per server poll and asserted as a sequence, not just as a final total: which
 * location received which items, in which order.
 * <p>
 * <b>What the shots show.</b> The digit is drawn on the block by {@code client.render.WarehouseInterfaceRenderer},
 * independently of what the player looks at — which is why it exists. Two constraints from Create's code shape every
 * camera: filter items and digits are only drawn within Create's client config {@code filterItemRenderDistance}
 * (default <b>10</b> blocks), and the crane must not stand between a camera and the row, which is what the closing
 * retrieval is for — it parks the crane at the far end, past every rack the row cameras see.
 * <p>
 * <b>Why this scenario needs a real player.</b> The number must also be readable where a player normally reads numbers,
 * i.e. in a goggle tooltip, and Create only draws one for a non-spectator who looks at a block within reach
 * ({@code GoggleOverlayRenderer}). Like the stock rule scenarios this one therefore runs with
 * {@link VisualWorldProfile#playable} — a creative, flying player with the vanilla reach — and sets that reach to 0
 * again for the world shots, because Create draws the value box of whatever the crosshair targets even with the GUI
 * hidden, and an active value box would cover the very digit these shots are about. Because the player is a real
 * creature, every camera eye sits high enough for its feet to clear the floor, and the row stands {@value #ROW_LEVEL}
 * levels up on a plinth so that a close-up is a level look at the plate rather than a view down onto it.
 */
public final class PrioritiesVisualScenario implements VisualScenario {
    public static final String NAME = "priorities";

    /** Its own throw-away world: goggles need a non-spectator with the vanilla reach. Deleted and rebuilt per run. */
    private static final String WORLD_FOLDER = "wareworks_visual_priorities";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 8;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 32;
    /** Blocks of belt in front of the warehouse input. */
    private static final int BELT_LENGTH = 6;
    /** Blocks cleared to air around the aisle and the belt lane, and above the floor. */
    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;

    /**
     * The demonstrated row: one level, one side, so every location is seen from a single camera.
     * <p>
     * Two levels up on a solid plinth, which is what a high-bay rack wall looks like and what the cameras need: the
     * player is a real creature here (see the class comment), so a camera that looks at a plate <b>level</b> rather than
     * from above must have its eye at the plate's height — and its feet a comfortable distance above the floor.
     */
    private static final int ROW_LEVEL = 2;
    private static final Side ROW_SIDE = Side.LEFT;

    private static final int NO_PRIORITY = StorageFilterBehaviour.MIN_PRIORITY;
    /** The location the fed items must end up in although two nearer ones would do. */
    private static final int PREFERRED_PRIORITY = 3;
    /** Higher than the preferred one, on a location whose filter rejects the item: a filter is the harder rule. */
    private static final int DEDICATED_PRIORITY = 7;
    /** The overflow's destination once the preferred location is full: farther away than two unprioritised ones. */
    private static final int FALLBACK_PRIORITY = 1;
    /** What the changed location is raised to in the Flywheel pass. */
    private static final int CHANGED_PRIORITY = 9;

    /** Slots of the preferred location's attached inventory (a hopper), asserted before anything is fed. */
    private static final int PREFERRED_SLOTS = 5;
    /** Slots of every other location's attached inventory (a barrel). */
    private static final int ORDINARY_SLOTS = 27;
    private static final int STACK = 64;
    /** What the preferred location holds when it is full, i.e. what the crane must put there before moving on. */
    private static final int PREFERRED_CAPACITY = PREFERRED_SLOTS * STACK;
    /** The overflow that must go to the prioritised fallback rather than to a nearer unprioritised location. */
    private static final int FALLBACK_BATCH = STACK;
    private static final int GOLD_FED = PREFERRED_CAPACITY + FALLBACK_BATCH;
    /** The second item type, fed after a priority was changed. A fresh type, so no consolidation can decide for it. */
    private static final int CHANGE_BATCH = STACK;
    /** Items fetched back out again at the end. */
    private static final int REQUEST_AMOUNT = 5;
    /**
     * Arm extension of the reconstructed delivery pose. Deliberately <b>not</b> fully extended: at
     * {@link CranePose#EXTENDED} the grabber is inside the rack block, and the items it carries
     * ({@code StackerCraneRenderer#renderHeldItems}) are drawn inside that block, where nothing can see them. This is a
     * pose the crane really passes through on its way in, and it is the one that shows what it is carrying.
     */
    private static final double DELIVERY_ARM = 0.7;

    /**
     * The item that is stored. Create draws a filter item with the light of the block it belongs to, and a warehouse
     * interface is a full cube, so strongly hued items stay recognisable where an iron ingot comes out near-black (the
     * lesson of the {@code filters} scenario); the same holds for the items in the crane's head.
     */
    private static final Item STORED = Items.GOLD_INGOT;
    /** What the one dedicated location of the row is reserved for: not a fed item, so it must stay empty. */
    private static final Item DEDICATED_TO = Items.DIAMOND;
    /**
     * The item fed after the priority change: a type nothing in the row holds yet, so no consolidation and no item-type
     * grouping can decide for it and the new number is the only thing left that can. A <b>block</b> item on purpose: it
     * is the one thing in the crane's head that a screenshot can make out, because Create renders a block item as a
     * cube while a dust or an ingot is a flat sprite that disappears against the grabber.
     */
    private static final Item CHANGED_ITEM = Items.REDSTONE_BLOCK;

    private static final RackPosition INPUT = RackPosition.of(1, 0, Side.RIGHT);
    /** At the far end of the aisle: the crane parks here after the retrieval, past every rack the row cameras see. */
    private static final RackPosition OUTPUT = RackPosition.of(RAILS, 0, Side.RIGHT);

    /** A belt, a hopper filled trip by trip, two redstone requests and two screens need more than the default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 15L * 60L * 1000L;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    /** The gold phase is six crane trips plus the belt; the change phase one trip. */
    private static final int FEED_TIMEOUT_TICKS = 6000;
    private static final int DELIVERED_TIMEOUT_TICKS = 2400;
    private static final int BELT_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 8;

    /** What a location is in this scene, for the logs and for the assertions that name it. */
    private enum Role {
        /** Unprioritised, empty, and nearer to the input than the preferred one: the location distance would choose. */
        ORDINARY,
        /** Dedicated to another item and prioritised higher than the preferred one. */
        DEDICATED,
        /** The one a player prioritised: filled first, and until it is full. */
        PREFERRED,
        /** Prioritised lower than the preferred one: takes the overflow, although two nearer ones carry nothing. */
        FALLBACK,
        /** Unprioritised until the pass raises it, and then the destination of the second item type. */
        CHANGED
    }

    /** One storage location of the row: where it is, what it is for, its priority and the item it is dedicated to. */
    private record Rack(RackPosition position, Role role, int priority, Optional<Item> dedicatedTo, int slots) {
        static Rack ordinary(int x) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), Role.ORDINARY, NO_PRIORITY, Optional.empty(),
                    ORDINARY_SLOTS);
        }

        static Rack of(int x, Role role, int priority) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), role, priority, Optional.empty(), ORDINARY_SLOTS);
        }

        static Rack preferred(int x) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), Role.PREFERRED, PREFERRED_PRIORITY,
                    Optional.empty(), PREFERRED_SLOTS);
        }

        static Rack dedicated(int x, Item item) {
            return new Rack(RackPosition.of(x, ROW_LEVEL, ROW_SIDE), Role.DEDICATED, DEDICATED_PRIORITY,
                    Optional.of(item), ORDINARY_SLOTS);
        }
    }

    /**
     * The row, in aisle order. Every position is load-bearing, and a wrong ranking cannot produce the asserted outcome:
     * <ul>
     *   <li>the two locations nearest to the input are unprioritised and stay empty, so distance alone would fill
     *       <b>them</b> — both in the gold run and after the change;</li>
     *   <li>the preferred one is further away than both, and the fallback further still, so neither can be explained by
     *       travel time;</li>
     *   <li>the location the pass raises sits <b>between</b> the preferred one and the fallback. That is what makes the
     *       closing retrieval mean something: the crane ends its trip there, so the fallback lies between the crane and
     *       the output and the preferred one lies behind it. On a straight aisle every source between crane and output
     *       costs the same, so any other arrangement would have compared two equal travel times and proved nothing.</li>
     * </ul>
     */
    private static final List<Rack> ROW = List.of(Rack.ordinary(2), Rack.ordinary(3), Rack.preferred(4),
            Rack.dedicated(5, DEDICATED_TO), Rack.of(6, Role.CHANGED, NO_PRIORITY),
            Rack.of(7, Role.FALLBACK, FALLBACK_PRIORITY));

    // Cameras, relative to the lower corner of the dock block. The row occupies x 2..8 at z -1..0 and y 2..3, so its
    // faces lie on the plane z = 0 and the aisle line (rails, crane) is z 0..1. The player is a real creature here, so
    // every eye sits high enough for its feet (eye − 1.62) to stand well clear of the floor: a camera that has to hover
    // just above the ground is one lost tick of creative flight away from failing to arrive. Every distance stays below
    // the 10 blocks after which Create stops drawing filter items and this renderer stops drawing digits.
    /** Straight at the row from across the aisle: all six faces with their digits, one of them with a filter item. */
    private static final CameraView WALL = CameraView.of("wall", 5.0, 3.4, 4.4, 5.0, 2.45, -0.5);
    /**
     * Almost level with the dedicated, prioritised location, two blocks away: the plate that carries a filter item and a
     * digit side by side, clear of the crane's arm port. This is the shot the number has to survive.
     */
    private static final CameraView SLOT = CameraView.of("slot", 5.5, 2.75, 1.8, 5.5, 2.36, -0.02);
    /** Along the aisle from the input end: the unprioritised racks in front, the preferred one far down the row. */
    private static final CameraView PAST = CameraView.of("past", -0.6, 3.6, 1.3, 7.0, 2.45, -0.5);
    /** The whole scene: the row, the rail line, the parked crane, the stations and the feed belt. */
    private static final CameraView OVERVIEW = CameraView.of("overview", 8.5, 4.8, 5.0, 4.5, 2.2, -0.5);
    /**
     * The before/after pair of the change, on the location the pass raises.
     * <p>
     * Taken from <b>west</b> of it and turned along the wall rather than straight at it, because at this moment the
     * crane is standing one position further east, where the gold run left it. The crane is in the aisle and the row
     * behind it, so a camera straight across the aisle photographs a mast where the number should be; from here the
     * mast sits a good 17° off to the right of the subject instead.
     */
    private static final CameraView CHANGE = CameraView.of("change", 3.6, 3.2, 2.6, 6.4, 2.45, -0.4);
    /**
     * The delivery into the location whose number was raised: in the aisle a step past the crane, looking back at the
     * carriage and the arm as it reaches into the rack.
     * <p>
     * The numbers are the {@code aisle} scenario's own arm framing ({@code AisleVisualScenario#armView}: 1.6 blocks
     * along the aisle, 0.3 to the far side, 1.35 above the carriage, looking 0.75 towards the arm side) resolved for a
     * crane at the changed location, and three other framings were tried and thrown away for it. A camera
     * <b>across</b> the aisle has the crane between itself and the face the crane is reaching into, and the mast is
     * nearer than the wall, so it covers the whole delivery. A camera far past the end of the aisle keeps the crane
     * clear but shrinks it to a figurine. A camera inside the aisle on the <b>other</b> side of the crane looks down
     * the tops of the rack wall, which then fills half the frame. This one costs the next rack of the row standing in
     * the foreground — which at least carries a digit of its own.
     */
    private static final CameraView DELIVER = CameraView.of("deliver", 8.1, 3.35, 0.8, 6.5, 2.55, -0.25);
    /**
     * The goggle shot of the preferred location. Aimed at the <b>upper</b> part of its aisle face: the store filter
     * value box sits at (8, 5) px with a hit radius of 4 px, and a value box the crosshair really hits makes Create's
     * goggle overlay bail out before it draws a single line ({@code GoggleOverlayRenderer}).
     */
    private static final CameraView AT_PREFERRED = CameraView.of("goggles-location", 4.5, 3.3, 2.3, 4.5, 2.88, -0.05);
    /**
     * West of the controller, aimed at the lower left corner of its back face rather than at its middle, for the same
     * reason: the controller's aisle letter is a scroll value box on every face but the dock's.
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("goggles-controller", -3.6, 2.6, 0.55, -1.0, 0.25,
            0.25);

    /** One delivery the crane made into a storage location, in the order the server saw them grow. */
    private record Delivery(RackPosition rack, long amount) {
    }

    /** Deliveries of the running feed phase, oldest first (server thread). */
    private final List<Delivery> deliveries = new ArrayList<>();
    /** What each location held when the running feed phase started, so only growth is recorded (server thread). */
    private final Map<RackPosition, Long> seen = new HashMap<>();
    /** The item the running feed phase is about (server thread). */
    private Item feeding = STORED;
    /** How many items the running feed phase put into the feed chest, i.e. how many must arrive (server thread). */
    private long feedTotal;

    /**
     * The client crane pose the injected one replaced, so it can be put back after the shot. A displayed pose survives
     * until the next sync packet from the server, and without restoring it the row cameras would keep a crane standing
     * in front of a rack the next shot is about.
     */
    private CranePose parkedPose;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: goggles need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("priorities: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "priorities run"))
                .server("priorities: clear the area and place the motors", PrioritiesVisualScenario::placeMotors)
                .server("priorities: build the aisle, the stations, the rack row and the feed belt",
                        PrioritiesVisualScenario::buildScene)
                .serverUntil("priorities: wait until the controller is ready with every member",
                        PrioritiesVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("priorities: aim the feed belt at the warehouse input", PrioritiesVisualScenario::aimBelt)
                .serverUntil("priorities: wait until the belt really carries towards the input",
                        PrioritiesVisualScenario::beltAimed, BELT_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // it is lifted into the air first and only then made to fly.
                .server("priorities: lift the player into the air", PrioritiesVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("priorities: the player flies and wears Engineer's Goggles",
                        PrioritiesVisualScenario::equipPlayer)
                .server("priorities: set the priorities and the one store filter",
                        PrioritiesVisualScenario::setStoreSettings)
                .server("priorities: check the inventories the assertions depend on",
                        PrioritiesVisualScenario::assertInventoryShapes)

                // The steered stream: one item type, one belt, and a row in which only the numbers differ.
                .server("priorities: put " + GOLD_FED + " gold ingots into the feed chest",
                        (server, context) -> startFeed(server, context, STORED, GOLD_FED))
                .serverUntil("priorities: run the belt and the crane until every gold ingot is stored",
                        this::feedStep, FEED_TIMEOUT_TICKS)
                .server("priorities: check which location received which items, in which order",
                        this::assertGoldOrder)

                .until("priorities: wait until the client sees every store setting",
                        PrioritiesVisualScenario::storeSettingsSynced, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and that box would
        // cover the very digit these shots are about, so the world shots are taken by a player who reaches nothing.
        reach(script, 0.0);
        if (pass == VisualPass.FLYWHEEL) {
            fly(script);
            changeAPriority(script);
            // The closing retrieval parks the crane past the whole row and proves that a priority never reaches the
            // retrieval path. Both passes shoot the same settled scene afterwards.
            retrieveFromTheCheaperSource(script);
        }

        fly(script);
        for (CameraView view : List.of(WALL, SLOT, PAST, OVERVIEW))
            script.shotFrom(view, "priorities");

        if (pass == VisualPass.FLYWHEEL) {
            // The goggles: the only shots with the GUI shown, and the only ones that need the real reach.
            reach(script, vanillaReach());
            fly(script);
            GoggleShots.shot(script, "priorities", AT_PREFERRED, "goggles-location",
                    dock -> layout(dock).rackPos(rack(Role.PREFERRED).position()),
                    PrioritiesVisualScenario::preferredNumbersSynced,
                    PrioritiesVisualScenario::checkPreferredGoggles);
            fly(script);
            GoggleShots.shot(script, "priorities", AT_CONTROLLER, "goggles-controller",
                    PrioritiesVisualScenario::controllerPos, PrioritiesVisualScenario::controllerNumbersSynced,
                    PrioritiesVisualScenario::checkControllerGoggles);
            reach(script, 0.0);
            script.client("priorities: every check passed",
                    context -> LOGGER.info(PREFIX + "priorities: ALL CHECKS PASSED (the priority decides, a filter "
                            + "outranks it, the preferred location fills until it is full, the prioritised fallback "
                            + "takes the overflow, a changed number takes effect, retrieval ignores priorities, "
                            + "goggles)"));
        }
    }

    /**
     * A priority a player changes, between two shots of the same camera, and the items that follow it.
     * <p>
     * Flywheel pass only, and it runs <b>before</b> the shots both passes share, so the second pass photographs exactly
     * the same finished scene instead of a state that no longer exists.
     */
    private void changeAPriority(VisualScript script) {
        Rack changed = rack(Role.CHANGED);
        script.camera(CHANGE).shot("change-before")
                .server("priorities: raise " + changed.position() + " from " + NO_PRIORITY + " to " + CHANGED_PRIORITY,
                        PrioritiesVisualScenario::raiseChangedPriority)
                .until("priorities: wait until the client sees the new number",
                        PrioritiesVisualScenario::changedPrioritySynced, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("change-after")
                .server("priorities: feed " + CHANGE_BATCH + " " + CHANGED_ITEM + " through the belt",
                        (server, context) -> startFeed(server, context, CHANGED_ITEM, CHANGE_BATCH))
                .serverUntil("priorities: run the belt and the crane until they are stored", this::feedStep,
                        FEED_TIMEOUT_TICKS)
                .server("priorities: check that the changed number decided where they went", this::assertChangeOrder)
                // The trip itself is over by now, so the moment is reconstructed on the client alone: the crane at the
                // location whose number was just raised, arm extended, the fed items in its head. Nothing on the
                // server moves, and the pose is put back before the next shot.
                .freeze(true)
                .camera(DELIVER)
                .client("priorities: show the crane at the location whose priority was raised",
                        this::showCraneAtChanged)
                .shot("change-delivered")
                .client("priorities: put the client crane back where it stands", this::restoreParkedPose)
                .freeze(false);
    }

    /**
     * The closing retrieval. It is the only place this scenario asks the warehouse for something back, and it is set up
     * so that the answer means something: the crane stands <b>past</b> both gold sources when the job is planned, so
     * the source prioritised {@value #FALLBACK_PRIORITY} is genuinely cheaper than the one prioritised
     * {@value #PREFERRED_PRIORITY}, and taking the cheaper one is what "a priority never sends the crane past a nearer
     * source" looks like. On a straight aisle every source between the crane and the output costs the same, so a crane
     * parked at the input end would have made the check vacuous.
     */
    private void retrieveFromTheCheaperSource(VisualScript script) {
        script.server("priorities: ask the output for " + REQUEST_AMOUNT + " " + STORED + " with a redstone pulse",
                        PrioritiesVisualScenario::requestStored)
                .serverUntil("priorities: wait until the output has the requested items",
                        PrioritiesVisualScenario::requestDelivered, DELIVERED_TIMEOUT_TICKS)
                .server("priorities: drop the lever again", (server, context) -> setLever(server, context, false))
                .serverUntil("priorities: wait until the crane is parked past the row", this::craneParkedAtOutput,
                        DELIVERED_TIMEOUT_TICKS)
                .until("priorities: wait until the client sees the parked crane",
                        PrioritiesVisualScenario::clientCraneParked, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        ClientLevel level = context.minecraft().level;
        if (level != null) {
            AisleLayout layout = layout(context.origin());
            for (Rack rack : ROW) {
                status.append(status.isEmpty() ? "row=" : ",").append(rack.position().x()).append(':');
                if (level.getBlockEntity(layout.rackPos(rack.position())) instanceof WarehouseInterfaceBlockEntity be)
                    status.append('p').append(be.storePriority()).append(be.hasStoreFilter() ? "f" : "");
                else
                    status.append('?');
            }
            if (level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane)
                status.append(String.format(Locale.ROOT, " crane=x%.2f phase=%s held=%d",
                        crane.craneState().pose().x(), crane.craneState().phase(),
                        crane.goggleInfo().held().stream().mapToLong(KeyCount::count).sum()));
        }
        status.append(GoggleShots.describeHover(context));
        return status.toString();
    }

    // --- build (server thread) --------------------------------------------------------------------------------------

    private static void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        Direction right = AISLE.getClockWise();
        for (BlockPos pos : BlockPos.betweenClosed(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, BELT_LENGTH + CLEAR_MARGIN)
                        .above(CLEAR_HEIGHT)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock.below()).generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x),
                    WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(controllerPos(dock),
                WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));

        AisleLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));
        level.setBlockAndUpdate(layout.rackPos(OUTPUT), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, inward(layout, OUTPUT)));
        // A lever on the floor beside the output reports signal 15 to every side, so the station sees the rising edge
        // of a real redstone request (LeverBlock#getSignal), exactly as in the showcase world.
        level.setBlockAndUpdate(leverPos(dock), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, AISLE)
                .setValue(LeverBlock.POWERED, false));

        buildRow(level, layout);
        buildFeed(level, dock);
    }

    /**
     * The rack row, one level up on a solid plinth.
     * <p>
     * <b>Barrels, not chests.</b> Two chests side by side on the axis across their facing merge into a double chest,
     * which would make one inventory of two rack positions — a shared-inventory alias (§3.1.1), on which a priority is
     * dead. A row of chests would therefore have tested the shadowing hint rather than the ranking.
     * <p>
     * <b>A hopper for the preferred location</b>, because it has {@value #PREFERRED_SLOTS} slots: the crane can fill it
     * in {@value #PREFERRED_SLOTS} trips, so "the preferred location is used until it is full" is something the crane
     * does in this run rather than something the scenario arranges by hand. A barrel would have taken 27 stacks.
     */
    private static void buildRow(ServerLevel level, AisleLayout layout) {
        Direction outward = layout.sideDirection(ROW_SIDE);
        for (Rack rack : ROW) {
            BlockPos interfacePos = layout.rackPos(rack.position());
            BlockPos inventoryPos = interfacePos.relative(outward);
            // The plinth: the level below carries the wall, and nothing there is a warehouse block, so the row above
            // is the only storage row of the aisle and every assertion names a location that really exists.
            for (int level0 = 0; level0 < ROW_LEVEL; level0++) {
                level.setBlockAndUpdate(interfacePos.below(level0 + 1), Blocks.POLISHED_ANDESITE.defaultBlockState());
                level.setBlockAndUpdate(inventoryPos.below(level0 + 1), Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
            level.setBlockAndUpdate(inventoryPos, rack.slots() == PREFERRED_SLOTS
                    ? Blocks.HOPPER.defaultBlockState() // facing DOWN by default: the plinth below takes nothing
                    : Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(interfacePos, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
    }

    /**
     * The feed: a chest over the belt's tail and a belt into the warehouse input, whose
     * {@code DirectBeltInputBehaviour} is the path Create designs for a belt-fed machine.
     * <p>
     * {@link #feedStep} empties the chest onto the belt one stack at a time, from above, with exactly the call a chute
     * or a brass funnel over the tail makes ({@code AbstractChuteBlock}: {@code handleInsertion(stack, UP, false)}).
     * A vanilla hopper was the alternative and is far too slow — it moves one item per 8 ticks, so the
     * {@value #GOLD_FED} ingots of this run would have spent four minutes in it.
     */
    private static void buildFeed(ServerLevel level, BlockPos dock) {
        BlockPos start = beltStartPos(dock);
        BlockPos end = beltEndPos(dock);
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException("the feed belt was not created between " + start + " and " + end);
        // A belt along the rack side turns on the axis across it, so its motor sits beside the belt's first pulley.
        level.setBlockAndUpdate(beltMotorPos(dock), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, AISLE.getOpposite()));
        motor(level, beltMotorPos(dock)).generatedSpeed.setValue(BELT_RPM);
        level.setBlockAndUpdate(feedChestPos(dock),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, AISLE));
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null)
            return false;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == ROW.size()
                && controller.inputStations().size() == 1 && controller.outputStations().size() == 1
                && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    /** Which way a belt carries follows the sign of its rotation, so it is read back and the motor reversed if wrong. */
    private static void aimBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        Direction wanted = beltDirection();
        Direction actual = beltMovement(level, dock);
        LOGGER.info(PREFIX + "priorities: the feed belt carries {} and must carry {}", actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, beltMotorPos(dock));
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltAimed(MinecraftServer server, VisualContext context) {
        return beltMovement(server.overworld(), context.origin()) == beltDirection();
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(ROW_LEVEL + 5);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * Lifts the player back into the air and switches creative flight on again, and waits until the client has it.
     * <p>
     * Needed before <b>every</b> group of camera views, not once at the start: a creative player that touches the ground
     * switches flying off again by itself ({@code LocalPlayer#aiStep}, which then tells the server), and the very next
     * camera whose feet stand in mid-air fails to arrive because the client falls out of it. That is exactly how the
     * first version of this scenario died — "the camera did not arrive at view change", with the player sitting 0.48
     * blocks lower, on the floor.
     */
    private static void fly(VisualScript script) {
        script.server("priorities: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("priorities: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    /**
     * The player of a goggle shot: flying (so a camera view in mid-air holds), wearing Engineer's Goggles (Create asks
     * {@code GogglesItem.isWearingGoggles}, which reads the head slot) and holding nothing, so no item covers a corner
     * of a shot with the GUI shown.
     */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- the store settings -----------------------------------------------------------------------------------------

    /** Sets the priorities as holding the click on the filter slot would, and the one store filter. */
    private static void setStoreSettings(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        for (Rack rack : ROW) {
            WarehouseInterfaceBlockEntity storage = interfaceAt(level, layout, rack.position());
            storage.setStorePriority(rack.priority());
            if (storage.storePriority() != rack.priority())
                throw new VisualTestException("the interface at " + rack.position() + " refused the priority "
                        + rack.priority() + " and reports " + storage.storePriority());
            if (rack.dedicatedTo().isPresent() && !storage.setStoreFilter(new ItemStack(rack.dedicatedTo().get())))
                throw new VisualTestException("the interface at " + rack.position() + " refused the filter "
                        + rack.dedicatedTo().get());
        }
        assertControllerCounts(level, context.origin(), prioritisedCount(false));
    }

    private static void raiseChangedPriority(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        Rack changed = rack(Role.CHANGED);
        WarehouseInterfaceBlockEntity storage = interfaceAt(level, layout(context.origin()), changed.position());
        if (storage.storePriority() != NO_PRIORITY)
            throw new VisualTestException("the location " + changed.position() + " was already prioritised "
                    + storage.storePriority() + " before the change");
        storage.setStorePriority(CHANGED_PRIORITY);
        if (storage.storePriority() != CHANGED_PRIORITY)
            throw new VisualTestException("the location " + changed.position() + " refused the new priority");
        assertControllerCounts(level, context.origin(), prioritisedCount(true));
        LOGGER.info(PREFIX + "priorities: CHECK a player raised {} from {} to {}", changed.position(), NO_PRIORITY,
                CHANGED_PRIORITY);
    }

    /** The controller counts what the row carries; the goggle summary and the planner read the same cache. */
    private static void assertControllerCounts(ServerLevel level, BlockPos dock, int expectedPrioritised) {
        WarehouseControllerBlockEntity controller = controller(level, dock);
        int prioritised = controller.prioritisedLocationCount();
        int filtered = controller.filteredLocationCount();
        if (prioritised != expectedPrioritised || filtered != filteredCount())
            throw new VisualTestException("the controller counts " + prioritised + " prioritised and " + filtered
                    + " filtered locations, expected " + expectedPrioritised + " and " + filteredCount());
        for (Rack rack : ROW) {
            boolean expected = level.getBlockEntity(layout(dock).rackPos(rack.position()))
                    instanceof WarehouseInterfaceBlockEntity be && be.storePriority() != NO_PRIORITY;
            if (controller.isStoragePrioritised(rack.position()) != expected)
                throw new VisualTestException("the controller disagrees about the priority of " + rack.position());
        }
        LOGGER.info(PREFIX + "priorities: {} of {} locations prioritised, {} filtered", prioritised, ROW.size(),
                filtered);
    }

    /**
     * The inventories the assertions depend on are the ones that were built: {@value #PREFERRED_SLOTS} slots behind the
     * preferred location, {@value #ORDINARY_SLOTS} behind every other, one inventory per location (no shared-inventory
     * alias), and nothing stored anywhere yet. Without this a silently missing hopper capability would only show up as
     * a puzzling item count minutes later.
     */
    private static void assertInventoryShapes(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        for (Rack rack : ROW) {
            BlockPos inventory = inventoryPos(layout, rack.position());
            IItemHandler handler = handlerAt(level, inventory);
            if (handler.getSlots() != rack.slots())
                throw new VisualTestException("the inventory of " + rack.position() + " (" + rack.role() + ") has "
                        + handler.getSlots() + " slots, expected " + rack.slots());
            if (totalAt(level, inventory) != 0)
                throw new VisualTestException("the inventory of " + rack.position() + " is not empty before the run");
            if (controller.isStorageFilterShadowed(rack.position()))
                throw new VisualTestException("the store setting of " + rack.position() + " is shadowed, so two rack "
                        + "positions share one inventory; the row must have one inventory per location");
        }
        if (STORED.getDefaultMaxStackSize() != STACK || CHANGED_ITEM.getDefaultMaxStackSize() != STACK)
            throw new VisualTestException("the fed items no longer stack to " + STACK + ", so the expected amounts "
                    + "of this scenario are wrong");
        LOGGER.info(PREFIX + "priorities: CHECK the row is {} locations, {} slots behind the preferred one and {} "
                + "behind the others, all empty", ROW.size(), PREFERRED_SLOTS, ORDINARY_SLOTS);
    }

    // --- the steered stream ------------------------------------------------------------------------------------------

    /** Starts a feed phase: the items go into the feed chest, and the delivery log starts from what is stored now. */
    private void startFeed(MinecraftServer server, VisualContext context, Item item, int amount) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);
        feeding = item;
        feedTotal = amount;
        deliveries.clear();
        seen.clear();
        for (Rack rack : ROW)
            seen.put(rack.position(), (long) countAt(level, inventoryPos(layout, rack.position()), item));
        insertAll(level, feedChestPos(dock), new ItemStack(item, amount));
        LOGGER.info(PREFIX + "priorities: {} {} are on their way onto the belt", amount, item);
    }

    /**
     * One poll of a feed phase: keep the belt fed, record every location that grew, and report when the last item has
     * been stored. Polled once per client tick by {@link VisualScript#serverUntil}.
     * <p>
     * Reading the six row inventories per poll is what makes the <b>order</b> of the deliveries observable instead of
     * only their sum. It is dev tooling and bounded by this scene; nothing in the mod scans inventories per tick.
     */
    private boolean feedStep(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);
        feedBelt(level, dock);
        for (Rack rack : ROW) {
            RackPosition position = rack.position();
            long now = countAt(level, inventoryPos(layout, position), feeding);
            long before = seen.getOrDefault(position, 0L);
            if (now <= before)
                continue;
            seen.put(position, now);
            deliveries.add(new Delivery(position, now - before));
            LOGGER.info(PREFIX + "priorities: delivery {} -> {} ({} {}, now {})", deliveries.size(), position,
                    now - before, feeding, now);
        }
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout.rackPos(INPUT));
        if (crane == null || input == null)
            throw new VisualTestException("the dock or the input of the aisle is missing");
        // The delivered sum, not just an empty chest and belt: Create's belt takes an inserted stack into a pending
        // queue that its own next tick moves into the visible one (BeltInventory#addItem -> toInsert -> items), so for
        // one tick after every insertion the belt looks empty. Without this term the first poll of a single-stack phase
        // reports "everything stored" before a single item has left the chest.
        return delivered() == feedTotal && countAt(level, feedChestPos(dock), feeding) == 0
                && beltItems(level, dock, feeding) == 0
                && input.bufferedItems().count(ItemKey.of(feeding)) == 0 && craneIdle(crane);
    }

    /** What the running feed phase has put into storage so far. */
    private long delivered() {
        return deliveries.stream().mapToLong(Delivery::amount).sum();
    }

    /**
     * Moves one stack from the feed chest onto the belt's tail, from above, whenever that segment is free — the call a
     * chute or a brass funnel over the tail makes. Returns whether anything moved.
     */
    private static boolean feedBelt(ServerLevel level, BlockPos dock) {
        IItemHandler chest = handlerAt(level, feedChestPos(dock));
        BlockPos start = beltStartPos(dock);
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(level, start, DirectBeltInputBehaviour.TYPE);
        if (belt == null)
            throw new VisualTestException("no belt with a direct belt input at " + start);
        if (!belt.canInsertFromSide(Direction.UP) || belt.isOccupied(Direction.UP))
            return false;
        for (int slot = 0; slot < chest.getSlots(); slot++) {
            ItemStack available = chest.extractItem(slot, STACK, true);
            if (available.isEmpty())
                continue;
            ItemStack rest = belt.handleInsertion(available.copy(), Direction.UP, false);
            int moved = available.getCount() - rest.getCount();
            if (moved <= 0)
                return false;
            chest.extractItem(slot, moved, false);
            return true;
        }
        return false;
    }

    /**
     * What the gold run claims, as a sequence: the first delivery went to the preferred location although two nearer
     * ones were empty, every delivery went there until it was full, and only then did the <b>prioritised</b> fallback
     * take over — not either of the nearer unprioritised locations, and never the dedicated one.
     */
    private void assertGoldOrder(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        RackPosition preferred = rack(Role.PREFERRED).position();
        RackPosition fallback = rack(Role.FALLBACK).position();
        if (deliveries.isEmpty())
            throw new VisualTestException("the crane stored nothing at all");
        if (!deliveries.getFirst().rack().equals(preferred))
            throw new VisualTestException("the first delivery went to " + deliveries.getFirst().rack()
                    + ", expected the preferred location " + preferred + " although nearer ones were empty");
        long toPreferred = 0;
        long toFallback = 0;
        for (Delivery delivery : deliveries) {
            if (delivery.rack().equals(preferred)) {
                if (toFallback > 0)
                    throw new VisualTestException("the crane went back to the preferred location " + preferred
                            + " after the fallback had already been used: " + describeDeliveries());
                toPreferred += delivery.amount();
            } else if (delivery.rack().equals(fallback)) {
                toFallback += delivery.amount();
            } else {
                throw new VisualTestException("a delivery went to " + delivery.rack()
                        + ", which no priority points at: " + describeDeliveries());
            }
        }
        if (toPreferred != PREFERRED_CAPACITY || toFallback != FALLBACK_BATCH)
            throw new VisualTestException("the preferred location received " + toPreferred + " and the fallback "
                    + toFallback + " " + STORED + ", expected " + PREFERRED_CAPACITY + " and " + FALLBACK_BATCH + ": "
                    + describeDeliveries());
        if (!isFull(level, layout, preferred, STORED))
            throw new VisualTestException("the preferred location still accepts " + STORED
                    + ", so the overflow did not leave it because it was full");
        assertRowContents(level, layout, STORED,
                Map.of(preferred, (long) PREFERRED_CAPACITY, fallback, (long) FALLBACK_BATCH));
        assertConserved(level, context.origin(), Map.of(ItemKey.of(STORED), (long) GOLD_FED));
        LOGGER.info(PREFIX + "priorities: CHECK the priority decided, the preferred location filled to {} and the "
                + "prioritised fallback took the rest: {}", PREFERRED_CAPACITY, describeDeliveries());
    }

    /** What the changed number claims: the second item type went to the raised location and nowhere else. */
    private void assertChangeOrder(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        AisleLayout layout = layout(context.origin());
        RackPosition changed = rack(Role.CHANGED).position();
        if (deliveries.isEmpty())
            throw new VisualTestException("nothing was stored after the priority was raised");
        long total = 0;
        for (Delivery delivery : deliveries) {
            if (!delivery.rack().equals(changed))
                throw new VisualTestException("a delivery went to " + delivery.rack() + " instead of the location "
                        + changed + " whose priority was just raised: " + describeDeliveries());
            total += delivery.amount();
        }
        if (total != CHANGE_BATCH)
            throw new VisualTestException("the raised location received " + total + " " + CHANGED_ITEM + ", expected "
                    + CHANGE_BATCH);
        assertRowContents(level, layout, CHANGED_ITEM, Map.of(changed, (long) CHANGE_BATCH));
        assertConserved(level, context.origin(),
                Map.of(ItemKey.of(STORED), (long) GOLD_FED, ItemKey.of(CHANGED_ITEM), (long) CHANGE_BATCH));
        LOGGER.info(PREFIX + "priorities: CHECK the changed number took effect: {} {} went to {} (priority {}), past "
                        + "the two unprioritised locations nearer to the input that would have taken them before: {}",
                total, CHANGED_ITEM, changed, CHANGED_PRIORITY, describeDeliveries());
    }

    /** Every location of the row holds exactly what {@code expected} says of {@code item}, and the rest holds none. */
    private static void assertRowContents(ServerLevel level, AisleLayout layout, Item item,
            Map<RackPosition, Long> expected) {
        for (Rack rack : ROW) {
            long actual = countAt(level, inventoryPos(layout, rack.position()), item);
            long wanted = expected.getOrDefault(rack.position(), 0L);
            if (actual != wanted)
                throw new VisualTestException(rack.position() + " (" + rack.role() + ", priority " + rack.priority()
                        + ") holds " + actual + " " + item + ", expected " + wanted);
        }
    }

    /** Nothing was lost, duplicated or changed on the way: the whole scene holds exactly what was fed into it. */
    private static void assertConserved(ServerLevel level, BlockPos dock, Map<ItemKey, Long> expected) {
        AABB box = sceneBounds(dock);
        if (!SceneItemCensus.isFullyLoaded(level, box))
            throw new VisualTestException("the census box of the scene is not fully loaded");
        Map<ItemKey, Long> actual = SceneItemCensus.take(level, box);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated: expected " + SceneItemCensus.describe(expected)
                    + " but the scene holds " + SceneItemCensus.describe(actual));
        LOGGER.info(PREFIX + "priorities: CHECK every item accounted for: {}", SceneItemCensus.describe(actual));
    }

    private String describeDeliveries() {
        StringBuilder text = new StringBuilder();
        for (Delivery delivery : deliveries)
            text.append(text.isEmpty() ? "" : " -> ").append(delivery.rack()).append('+').append(delivery.amount());
        return "[" + text + "]";
    }

    // --- the retrieval -----------------------------------------------------------------------------------------------

    /** Asks the output for items that live in two locations of different priority, through a real redstone pulse. */
    private static void requestStored(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        WarehouseOutputBlockEntity output = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(context.origin()).rackPos(OUTPUT));
        if (output == null)
            throw new VisualTestException("the warehouse output of the aisle is missing");
        FilteringBehaviour filter = BlockEntityBehaviour.get(output, FilteringBehaviour.TYPE);
        if (filter == null)
            throw new VisualTestException("the warehouse output has no filtering behaviour");
        if (!filter.setFilter(new ItemStack(STORED)))
            throw new VisualTestException("the warehouse output refused the request filter");
        filter.count = REQUEST_AMOUNT; // after setFilter, which may clamp the count
        // Where the crane stands decides whether the check below means anything, so it is recorded with the request.
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, context.origin());
        LOGGER.info(PREFIX + "priorities: the crane stands at x={} when the request is planned",
                crane == null ? "?" : String.format(Locale.ROOT, "%.2f", crane.craneState().pose().x()));
        setLever(server, context, true);
    }

    private static boolean requestDelivered(MinecraftServer server, VisualContext context) {
        return countAt(server.overworld(), layout(context.origin()).rackPos(OUTPUT), STORED) >= REQUEST_AMOUNT;
    }

    /**
     * The crane is idle past the whole row, and the items came out of the location prioritised
     * {@value #FALLBACK_PRIORITY} — the cheaper source — while the one prioritised {@value #PREFERRED_PRIORITY} was
     * left untouched.
     */
    private boolean craneParkedAtOutput(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        AisleLayout layout = layout(dock);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        if (!craneIdle(crane))
            return false;
        int lastRow = ROW.getLast().position().x();
        if (crane.craneState().pose().x() < lastRow + 1 - 1.0e-6)
            throw new VisualTestException("the crane parked at x=" + crane.craneState().pose().x()
                    + ", which is not past the row (last rack at x=" + lastRow + "); the row cameras would photograph "
                    + "it instead of the rack it stands in front of");
        long fromPreferred = PREFERRED_CAPACITY
                - countAt(level, inventoryPos(layout, rack(Role.PREFERRED).position()), STORED);
        long fromFallback = FALLBACK_BATCH
                - countAt(level, inventoryPos(layout, rack(Role.FALLBACK).position()), STORED);
        if (fromPreferred != 0 || fromFallback != REQUEST_AMOUNT)
            throw new VisualTestException("the retrieval took " + fromPreferred + " out of the location prioritised "
                    + PREFERRED_PRIORITY + " and " + fromFallback + " out of the one prioritised " + FALLBACK_PRIORITY
                    + ", expected 0 and " + REQUEST_AMOUNT + ": a priority must not decide where items come from");
        assertConserved(level, dock,
                Map.of(ItemKey.of(STORED), (long) GOLD_FED, ItemKey.of(CHANGED_ITEM), (long) CHANGE_BATCH));
        LOGGER.info(PREFIX + "priorities: CHECK retrieval ignored the priorities: all {} {} came out of {} "
                        + "(priority {}), none out of {} (priority {}); the crane is parked at x={}", REQUEST_AMOUNT,
                STORED, rack(Role.FALLBACK).position(), FALLBACK_PRIORITY, rack(Role.PREFERRED).position(),
                PREFERRED_PRIORITY, String.format(Locale.ROOT, "%.2f", crane.craneState().pose().x()));
        return true;
    }

    /**
     * Flips the lever beside the output. {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the
     * station see the edge and submit (or drop) the request.
     */
    private static void setLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BlockPos pos = leverPos(context.origin());
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever beside the warehouse output at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
    }

    // --- client ------------------------------------------------------------------------------------------------------

    /**
     * The shots can only show what the client knows: every location must carry its own number there too, exactly as the
     * row table sets it. Used before the first shots, i.e. while the changed location is still unprioritised.
     */
    private static boolean storeSettingsSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        AisleLayout layout = layout(context.origin());
        for (Rack rack : ROW) {
            if (!(level.getBlockEntity(layout.rackPos(rack.position()))
                    instanceof WarehouseInterfaceBlockEntity storage))
                return false;
            if (storage.storePriority() != rack.priority())
                return false;
            if (rack.dedicatedTo().isPresent() != storage.hasStoreFilter())
                return false;
        }
        return true;
    }

    /** The one number a player changed during the pass has arrived on the client, so the shot can show it. */
    private static boolean changedPrioritySynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos pos = layout(context.origin()).rackPos(rack(Role.CHANGED).position());
        return level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity storage
                && storage.storePriority() == CHANGED_PRIORITY;
    }

    private static boolean clientCraneParked(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane
                && crane.craneState().phase() == CranePhase.IDLE
                && crane.craneState().pose().x() >= ROW.getLast().position().x() + 1 - 1.0e-6;
    }

    /**
     * Puts the <b>client</b> crane at the location whose priority was just raised, arm extended towards it and the fed
     * items in its head, so one frozen frame shows the trip the server already made. The server state is untouched.
     */
    private void showCraneAtChanged(VisualContext context) {
        StackerCraneBlockEntity crane = clientCrane(context);
        parkedPose = crane.craneState().pose();
        RackPosition target = rack(Role.CHANGED).position();
        CranePose pose = CranePose.sanitized(target.x(), target.y(), DELIVERY_ARM, target.side());
        if (!crane.showClientPose(pose, CranePhase.EXTEND_TARGET,
                List.of(new KeyCount<>(CHANGED_ITEM, (long) CHANGE_BATCH))))
            throw new VisualTestException("cannot show the crane pose at " + context.origin());
    }

    /** Puts the injected pose back to where the crane really stands, so the next shot is of the settled scene. */
    private void restoreParkedPose(VisualContext context) {
        if (parkedPose == null)
            return;
        if (!clientCrane(context).showClientPose(parkedPose, CranePhase.IDLE, List.of()))
            throw new VisualTestException("cannot restore the crane pose at " + context.origin());
        parkedPose = null;
    }

    private static StackerCraneBlockEntity clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("no client level for the crane pose");
        if (!(level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane))
            throw new VisualTestException("no stacker crane on the client at " + context.origin());
        return crane;
    }

    // --- goggles -----------------------------------------------------------------------------------------------------

    /** The player's block reach, set and waited for through the shared goggle helper ({@link GoggleShots#reach}). */
    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "priorities", range);
    }

    private static double vanillaReach() {
        return GoggleShots.vanillaReach();
    }

    /**
     * The preferred location's own numbers have reached the client: its priority and the summary of what it holds. The
     * summary travels in its own throttled packet, which the server only sends once it has seen the player look at the
     * block, so waiting for it keeps the check below about the values and not about the moment they arrive.
     */
    private static boolean preferredNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos pos = layout(context.origin()).rackPos(rack(Role.PREFERRED).position());
        if (!(level.getBlockEntity(pos) instanceof WarehouseInterfaceBlockEntity storage))
            return false;
        return storage.storePriority() == PREFERRED_PRIORITY && storage.summary().hasInventory()
                && storage.summary().contents().totalSlots() == PREFERRED_SLOTS
                && storage.summary().contents().usedSlots() == PREFERRED_SLOTS;
    }

    private static boolean controllerNumbersSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller))
            return false;
        ControllerGoggleSummary summary = controller.summary();
        return summary.storageLocations() == ROW.size() && summary.prioritisedLocations() == prioritisedCount(true)
                && summary.filteredLocations() == filteredCount();
    }

    /** A screenshot cannot tell a right number from a wrong one, so the lines the overlay draws are read as well. */
    private static void checkPreferredGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context,
                layout(context.origin()).rackPos(rack(Role.PREFERRED).position()));
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_STORAGE_LOCATION).getString());
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STORAGE_PRIORITY, PREFERRED_PRIORITY));
        // The one a player prioritised carries no filter at all, so nothing but the number can have steered the items.
        GoggleShots.requireLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_STORAGE_FILTER_NONE).getString());
        GoggleShots.requireNoLine(lines,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_STORAGE_FILTER_SHADOWED).getString());
        LOGGER.info(PREFIX + "priorities: CHECK the location's goggles say {}", lines);
    }

    private static void checkControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        GoggleShots.requireLine(lines,
                GoggleShots.count(WareworksLang.GOGGLES_PRIORITISED_LOCATIONS, prioritisedCount(true)));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_FILTERED_LOCATIONS, filteredCount()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STORAGE_LOCATIONS, ROW.size()));
        LOGGER.info(PREFIX + "priorities: CHECK the controller's goggles say {}", lines);
    }

    // --- the row -----------------------------------------------------------------------------------------------------

    private static Rack rack(Role role) {
        return ROW.stream().filter(entry -> entry.role() == role).findFirst()
                .orElseThrow(() -> new VisualTestException("the row has no " + role + " location"));
    }

    /** Locations that carry a priority, before or after the change the Flywheel pass makes. */
    private static int prioritisedCount(boolean afterTheChange) {
        return (int) ROW.stream()
                .filter(rack -> rack.priority() != NO_PRIORITY || (afterTheChange && rack.role() == Role.CHANGED))
                .count();
    }

    private static int filteredCount() {
        return (int) ROW.stream().filter(rack -> rack.dedicatedTo().isPresent()).count();
    }

    // --- geometry ----------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    /** Which way a station on {@code rack} faces to look into the aisle. */
    private static Direction inward(AisleLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    /** The inventory behind a storage location: one block further away from the aisle than its interface. */
    private static BlockPos inventoryPos(AisleLayout layout, RackPosition rack) {
        return layout.rackPos(rack).relative(layout.sideDirection(rack.side()));
    }

    /** Beside the output, one block further out than the station itself, standing on the superflat floor. */
    private static BlockPos leverPos(BlockPos dock) {
        AisleLayout layout = layout(dock);
        return layout.rackPos(OUTPUT).relative(layout.sideDirection(OUTPUT.side()));
    }

    /** The belt's last block, right in front of the warehouse input. */
    private static BlockPos beltEndPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT).relative(layout(dock).sideDirection(INPUT.side()));
    }

    /** The belt's tail, {@value #BELT_LENGTH} blocks out along the rack side; the feed chest stands over it. */
    private static BlockPos beltStartPos(BlockPos dock) {
        return layout(dock).rackPos(INPUT).relative(layout(dock).sideDirection(INPUT.side()), BELT_LENGTH);
    }

    private static BlockPos beltMotorPos(BlockPos dock) {
        return beltStartPos(dock).relative(AISLE);
    }

    private static BlockPos feedChestPos(BlockPos dock) {
        return beltStartPos(dock).above();
    }

    /** Which way the belt must carry: from its tail towards the warehouse input. */
    private static Direction beltDirection() {
        return AISLE.getClockWise().getOpposite();
    }

    private static AABB sceneBounds(BlockPos dock) {
        Direction right = AISLE.getClockWise();
        return AABB.encapsulatingFullBlocks(
                dock.relative(AISLE.getOpposite(), CLEAR_MARGIN).relative(right.getOpposite(), CLEAR_MARGIN).below(),
                dock.relative(AISLE, RAILS + CLEAR_MARGIN).relative(right, BELT_LENGTH + CLEAR_MARGIN)
                        .above(CLEAR_HEIGHT));
    }

    // --- block entities and inventories ------------------------------------------------------------------------------

    private static boolean craneIdle(StackerCraneBlockEntity crane) {
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos pos) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, pos);
        if (motor == null)
            throw new VisualTestException("the creative motor at " + pos + " is missing");
        return motor;
    }

    private static WarehouseControllerBlockEntity controller(ServerLevel level, BlockPos dock) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }

    private static WarehouseInterfaceBlockEntity interfaceAt(ServerLevel level, AisleLayout layout, RackPosition rack) {
        WarehouseInterfaceBlockEntity storage = WareworksBlockEntityTypes.WAREHOUSE_INTERFACE.getNullable(level,
                layout.rackPos(rack));
        if (storage == null)
            throw new VisualTestException("no warehouse interface at " + rack);
        return storage;
    }

    private static Direction beltMovement(ServerLevel level, BlockPos dock) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, beltStartPos(dock));
        if (belt == null)
            throw new VisualTestException("no belt at " + beltStartPos(dock));
        return belt.getMovementFacing();
    }

    /**
     * Items of {@code item} standing on the feed belt.
     * <p>
     * A belt's inventory lives on <b>one</b> of its segments, the controller ({@code BeltBlockEntity#isController}), and
     * which one that is depends on how the belt was built. Asking the tail segment directly would silently answer 0 for
     * a belt whose controller is the other end — and a 0 here means "everything has arrived", which would end a feed
     * phase while a stack was still riding.
     */
    private static long beltItems(ServerLevel level, BlockPos dock, Item item) {
        BeltBlockEntity segment = AllBlockEntityTypes.BELT.getNullable(level, beltStartPos(dock));
        BeltBlockEntity belt = segment == null ? null : segment.getControllerBE();
        if (belt == null || belt.getInventory() == null)
            return 0L;
        long items = 0;
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems()) {
            if (transported.stack.is(item))
                items += transported.stack.getCount();
        }
        return items;
    }

    private static void insertAll(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = handlerAt(level, pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
    }

    /** Whether the inventory behind {@code rack} takes no further item of {@code item} at all. */
    private static boolean isFull(ServerLevel level, AisleLayout layout, RackPosition rack, Item item) {
        IItemHandler handler = handlerAt(level, inventoryPos(layout, rack));
        return !ItemHandlerHelper.insertItem(handler, new ItemStack(item, 1), true).isEmpty();
    }

    private static int countAt(ServerLevel level, BlockPos pos, Item item) {
        IItemHandler handler = handlerAt(level, pos);
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static int totalAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = handlerAt(level, pos);
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }
}
