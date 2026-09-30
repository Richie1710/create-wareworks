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
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseInputBlock;
import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.NoJobReason;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
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
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "ports": the warehouse output as a configurable <b>port</b> ({@code docs/warehouse-system.md} §3.2, ADR-029,
 * M17, issue #12) — the whole table the feature was designed from, in one world.
 * <p>
 * One aisle, a belt feeding the warehouse input, a stock keeper whose <b>maximum</b> is what makes an overflow necessary
 * in the first place, five storage locations, and <b>five ports</b> in the opposite rack wall, each one row of that
 * table. Every port sits one level up with a vanilla hopper below it, so what the port receives really leaves the
 * warehouse into what a player built behind it — a barrel, a belt, or a Mechanical Press.
 * <table>
 *   <caption>The five ports of this scene, in aisle order</caption>
 *   <tr><th>x</th><th>direction</th><th>redstone</th><th>filter</th><th>behind it</th></tr>
 *   <tr><td>1</td><td>request</td><td>pulse</td><td>diamond</td><td>barrel</td></tr>
 *   <tr><td>3</td><td>diversion +4</td><td>while powered</td><td>—</td><td>barrel</td></tr>
 *   <tr><td>5</td><td>overflow −5</td><td>unless powered</td><td>cobblestone</td><td>barrel</td></tr>
 *   <tr><td>7</td><td>overflow −1</td><td>unless powered</td><td>—</td><td>a belt carrying the surplus away</td></tr>
 *   <tr><td>11</td><td>request</td><td>while powered</td><td>iron ingot</td><td>belt → Mechanical Press → sheets</td></tr>
 * </table>
 * <b>Every claim a screenshot makes is asserted on the server before the shot that makes it</b>, and the six phases run
 * in the Flywheel pass, so the second pass photographs exactly the same finished scene:
 * <ol>
 *   <li><b>the maximum feeds an unwired overflow</b> — {@value #IRON_FED} iron ingots ride the belt into the input, the
 *       rule keeps {@value #IRON_MAXIMUM} of them and the rest leaves through a port <b>that is wired to nothing at
 *       all</b> (the {@code overflow-*} shots, the surplus riding its belt);</li>
 *   <li><b>a lever switches it off</b> — the same port, one lever later: the iron stays in the input;</li>
 *   <li><b>a filtered overflow sorts</b> — only the surplus cobblestone leaves, the iron still backs up, and the
 *       controller's goggles say why in the words they always used;</li>
 *   <li><b>a diversion takes everything while powered</b> — a positive rank outranks five empty storage locations, and
 *       the moment the lever drops the warehouse stores again;</li>
 *   <li><b>a pulse still hands out</b> — the port of the fourth row is today's warehouse output, byte for byte;</li>
 *   <li><b>a held signal keeps a machine supplied</b> — the maximum is raised, iron is stored again, and one lever then
 *       feeds a Mechanical Press trip by trip with <b>at most one open request</b> and no clock at all.</li>
 * </ol>
 * <b>What the shots show.</b> An accepting port's aisle ring and back spout are andesite instead of brass, which is the
 * only thing readable from inside the aisle where no value box may go; its signed rank is painted on the back plate by
 * {@code client.render.WarehouseOutputRenderer}, together with the filter item that says what it handles at all. Both
 * are only drawn within Create's {@code filterItemRenderDistance} (default <b>10</b> blocks), so every camera stays
 * inside it. The port row is photographed from <b>both</b> sides: from the aisle for the rings, from behind the rack wall
 * for the digits — and from behind the crane can never stand in the way.
 * <p>
 * <b>Why this scenario needs a real player.</b> The three numbers of a port (its rank, what it accepts and how much it
 * has handed over) are also goggle lines, and Create only draws a tooltip for a non-spectator who looks at a block
 * within reach ({@code GoggleOverlayRenderer}). Like the stock rule scenarios this one therefore runs with
 * {@link VisualWorldProfile#playable} and sets the reach back to 0 for the world shots, because Create draws the value
 * box of whatever the crosshair targets even with the GUI hidden — and a port has two of them.
 */
public final class PortsVisualScenario implements VisualScenario {
    public static final String NAME = "ports";

    /** Its own throw-away world: goggles need a non-spectator with the vanilla reach. Deleted and rebuilt per run. */
    private static final String WORLD_FOLDER = "wareworks_visual_ports";

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 11;
    private static final int MOTOR_RPM = 128;
    private static final int BELT_RPM = 32;
    private static final int PRESS_RPM = 128;

    /**
     * Level of the port row: <b>one up</b>, because a vanilla hopper below a port is what drains it, and a hopper only
     * ever pulls from the block above itself. The hoppers are the level-0 rack wall of that side, so what a port
     * receives really leaves the warehouse instead of sitting in a buffer no screenshot can see.
     */
    private static final int PORT_LEVEL = 1;
    private static final Side PORT_SIDE = Side.LEFT;

    /** Blocks of belt in front of the warehouse input, on the warehouse's own rack side. */
    private static final int FEED_BELT = 6;
    /** Blocks of belt behind the general overflow port; long enough that the surplus is unmistakable in a shot. */
    private static final int OVERFLOW_BELT = 7;
    /** Blocks of belt behind the machine port; the press stands over its middle. */
    private static final int MACHINE_BELT = 5;
    /** Which belt block of the machine lane the Mechanical Press stands two blocks above. */
    private static final int PRESS_OFFSET = 2;

    private static final int CLEAR_ALONG = 5;
    private static final int CLEAR_HEIGHT = 10;

    private static final Item IRON = Items.IRON_INGOT;
    private static final Item COBBLE = Items.COBBLESTONE;
    private static final Item DIAMOND = Items.DIAMOND;
    private static final Item GOLD = Items.GOLD_INGOT;

    private static final ItemKey IRON_KEY = ItemKey.of(IRON);
    private static final ItemKey COBBLE_KEY = ItemKey.of(COBBLE);
    private static final ItemKey DIAMOND_KEY = ItemKey.of(DIAMOND);
    private static final ItemKey GOLD_KEY = ItemKey.of(GOLD);

    /** What the press turns one iron ingot into ({@code create:pressing}, one result per ingot). */
    private static Item sheet() {
        return AllItems.IRON_SHEET.get();
    }

    private static final int STACK = 64;

    /** Rule 0 of the stock keeper: the maximum that makes phase 1's overflow necessary. */
    private static final int IRON_MAXIMUM = 8;
    /** Rule 1: cobblestone is capped too, so phase 3's cobblestone really is a <b>surplus</b>. */
    private static final int COBBLE_MAXIMUM = 8;
    /** What the maximum is raised to before the machine phase, so the warehouse can hold the machine's supply. */
    private static final int IRON_MAXIMUM_RAISED = 64;
    private static final int RULES = 2;
    private static final int RULE_IRON = 0;
    private static final int RULE_COBBLE = 1;

    /** Phase 1: fed through the belt; {@value #IRON_MAXIMUM} are kept and the rest leaves through the overflow. */
    private static final int IRON_FED = 24;
    private static final int IRON_OVERFLOWED = IRON_FED - IRON_MAXIMUM;
    /** Phase 2: fed after a lever switched the overflow off, so it has nowhere to go. */
    private static final int IRON_JAMMED = 8;
    /** Phase 3: half of it fits under the cobblestone maximum, half leaves through the filtered overflow. */
    private static final int COBBLE_FED = 16;
    private static final int COBBLE_OVERFLOWED = COBBLE_FED - COBBLE_MAXIMUM;
    /** Phase 4: fed while the diversion is powered, so none of it is stored. */
    private static final int GOLD_DIVERTED = 16;
    /** Phase 4: fed after the lever dropped, so all of it is stored. */
    private static final int GOLD_STORED = 16;
    /** Phase 5: diamonds put into a storage location at build time, so the pulses have something to hand out. */
    private static final int DIAMONDS_IN_STOCK = 16;
    /** What the pulse port asks for per rising edge, and how many edges phase 5 gives it. */
    private static final int PULSE_REQUEST = 4;
    private static final int PULSES = 2;
    /** Phase 6: fed once the maximum is raised, and stored where the overflow used to take it. */
    private static final int IRON_RESTOCKED = 16;
    /** The whole iron stock the machine is then handed, trip by trip: what the rule kept plus the restock. */
    private static final int IRON_TO_THE_MACHINE = IRON_MAXIMUM + IRON_RESTOCKED;
    /** What the machine port asks for per trip: the whole supply takes three of them. */
    private static final int HELD_REQUEST = 8;
    /** Sheets the press must have made before the machine shot is taken. */
    private static final int SHEETS_BEFORE_THE_SHOT = 4;

    /**
     * Arm extension of the reconstructed delivery pose. Deliberately <b>not</b> fully extended: at
     * {@link CranePose#EXTENDED} the grabber is inside the rack block and the items it carries are drawn inside it,
     * where nothing can see them (the {@code priorities} lesson).
     */
    private static final double DELIVERY_ARM = 0.7;

    /** Items that must ride the overflow belt before its shot is taken. */
    private static final int ITEMS_ON_THE_BELT = 3;

    private static final RackPosition KEEPER = RackPosition.of(1, 0, Side.RIGHT);
    private static final RackPosition INPUT = RackPosition.of(3, 0, Side.RIGHT);
    /** Storage locations of the warehouse's own rack side, all unfiltered: only the rules and the ranks decide. */
    private static final List<Integer> STORAGE_X = List.of(5, 6, 7, 8, 9);

    /** One port of the scene: where it stands, its policy, its filter and how much it asks for. */
    private record Port(String id, int x, int rank, PortRedstone redstone, Optional<Item> filter, int requestAmount) {
        RackPosition position() {
            return RackPosition.of(x, PORT_LEVEL, PORT_SIDE);
        }

        PortSettings settings() {
            return new PortSettings(rank, redstone);
        }

        boolean accepts() {
            return settings().direction() == PortDirection.ACCEPT;
        }

        /** Belt blocks of the lane behind this port; 0 for a port whose drain pushes straight into a barrel. */
        int laneLength() {
            if (this == OVERFLOW_PORT)
                return OVERFLOW_BELT;
            return this == MACHINE_PORT ? MACHINE_BELT : 0;
        }
    }

    /**
     * Aisle positions of the five ports. Every other position is free, so no two ports share a receiver, a lever or a
     * lane, and the machine port stands <b>past</b> all of them: the crane parks where its last job ended, and the last
     * job of this run is that port's, so the row cameras never photograph a mast.
     */
    private static final int PULSE_X = 1;
    private static final int DIVERSION_X = 3;
    private static final int FILTERED_X = 5;
    private static final int OVERFLOW_X = 7;
    private static final int MACHINE_X = 11;

    /** Row 4 of the table: today's warehouse output, one delivery per rising edge. */
    private static final Port PULSE_PORT = new Port("pulse", PULSE_X, PortSettings.REQUEST_RANK, PortRedstone.PULSE,
            Optional.of(DIAMOND), PULSE_REQUEST);
    /** Row 3: a positive rank takes incoming items <b>before</b> they are stored, while the signal is high. */
    private static final Port DIVERSION_PORT =
            new Port("diversion", DIVERSION_X, 4, PortRedstone.WHILE_POWERED, Optional.empty(), 0);
    /** Row 2: only surplus cobblestone leaves; everything else backs up as before. */
    private static final Port FILTERED_PORT =
            new Port("filtered", FILTERED_X, -5, PortRedstone.UNLESS_POWERED, Optional.of(COBBLE), 0);
    /** Row 1: the general overflow — no filter, no wiring, and a lever that can turn it off. */
    private static final Port OVERFLOW_PORT =
            new Port("overflow", OVERFLOW_X, -1, PortRedstone.UNLESS_POWERED, Optional.empty(), 0);
    /** Row 5: keep a machine supplied, no clock. Past every other port, so the crane parks clear of the row. */
    private static final Port MACHINE_PORT =
            new Port("machine", MACHINE_X, PortSettings.REQUEST_RANK, PortRedstone.WHILE_POWERED, Optional.of(IRON),
                    HELD_REQUEST);

    private static final List<Port> PORTS =
            List.of(PULSE_PORT, DIVERSION_PORT, FILTERED_PORT, OVERFLOW_PORT, MACHINE_PORT);

    /** A belt, two machines, six phases and four goggle shots need far more than the harness's default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 25L * 60L * 1000L;
    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int BELT_TIMEOUT_TICKS = 300;
    private static final int RULE_TIMEOUT_TICKS = 300;
    private static final int FEED_TIMEOUT_TICKS = 4800;
    private static final int DELIVERY_TIMEOUT_TICKS = 2400;
    private static final int MACHINE_TIMEOUT_TICKS = 4800;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int SETTLE_TICKS = 8;
    /** Polls with an empty feed and an idle crane before a feed phase counts as finished. */
    private static final int IDLE_POLLS = 30;

    // Cameras, relative to the lower corner of the dock block. The aisle line is z 0..1, the warehouse's rack wall
    // z 1..2 with its barrels at z 2..3, and the port row is z -1..0 at y 1..2 with its hoppers below and its receivers
    // at z -2..-1. The player is a real creature here, so every eye sits high enough for its feet (eye - 1.62) to clear
    // the floor, and every distance stays inside the 10 blocks after which Create stops drawing filter items and this
    // renderer stops drawing digits.
    /**
     * The whole scene from the far corner: aisle, crane, feed belt, rack wall, the five ports and both machines.
     * <p>
     * Twelve blocks out and barely above the rack wall, not twenty-four and high up: the scene is twenty blocks across,
     * so an eye that takes all of it in with room to spare shrinks every block of it to nothing, and a steep look-down
     * fills the frame with the meadow instead. Two versions were thrown away for this one; the lane ends and the belt's
     * tail are left to the shots that are about them.
     */
    private static final CameraView OVERVIEW = CameraView.of("overview", 13.0, 5.0, 7.0, 4.5, 1.2, -1.5);
    /**
     * The port row from across the aisle, over the warehouse's own rack wall. Centred on the four near ports, because
     * the fifth stands eleven positions along and the crane parks in front of it.
     */
    private static final CameraView PORTS_AISLE = CameraView.of("ports-aisle", 4.5, 2.9, 4.6, 4.5, 1.45, -0.4);
    /**
     * Two neighbouring ports from the aisle, close enough for the one thing only this side can show: the
     * <b>andesite</b> ring of the accepting port at aisle position 3 beside the <b>brass</b> ring of the requesting one
     * at position 1 (ADR-017's material language, {@code WareworksBlockStateGen#warehousePortBlockProvider}). A row of
     * five seen from seven blocks away cannot tell that story — it needs three.
     */
    private static final CameraView RINGS = CameraView.of("rings", 2.6, 2.0, 2.2, 2.6, 1.5, -0.4);
    /**
     * The same row from behind the rack wall, where the ranks are painted and the filter items are drawn — and where the
     * crane can never stand in the way, whatever job it finished last.
     */
    private static final CameraView PORTS_BACK = CameraView.of("ports-back", 6.0, 3.0, -5.6, 6.0, 1.45, -1.0);
    /**
     * The whole lane behind the general overflow, from the near side: belt, funnel and the barrel it fills. Nine blocks
     * from the port it belongs to, and no more — past ten Create stops drawing the rank on its back plate, and a lane
     * shot whose port carries no number says nothing about which port it is.
     */
    private static final CameraView OVERFLOW_LANE = CameraView.of("overflow-lane", 4.4, 3.4, -8.8, 7.3, 1.35, -3.4);
    /**
     * The surplus <b>in motion</b>, three blocks from the belt: the ingots the warehouse could not keep, riding away
     * from the port that handed them over. Only ever used with the ticks frozen, so the picture is the same every run.
     */
    private static final CameraView OVERFLOW_FLOW = CameraView.of("overflow-flow", 5.0, 2.6, -5.0, 7.4, 1.25, -3.2);
    /**
     * The general overflow from behind, close enough for its {@code -1} to be legible, and with the block above it in
     * frame: that is where the lever of phase 2 appears, so one camera carries the before/after pair.
     */
    private static final CameraView AT_OVERFLOW = CameraView.of("overflow", 9.2, 2.75, -3.6, 7.6, 1.95, -1.05);
    /** The filtered overflow from behind: its {@code -5} and the cobblestone that says what it handles at all. */
    private static final CameraView AT_FILTERED = CameraView.of("filtered", 7.8, 2.7, -4.2, 5.6, 1.85, -1.1);
    /** The diversion from behind, with its lever in frame for the on/off pair. */
    private static final CameraView AT_DIVERSION = CameraView.of("diversion", 0.9, 2.7, -4.3, 3.45, 1.95, -1.05);
    /**
     * The machine: the port, the belt it feeds and the Mechanical Press over it, looked at <b>along</b> the lane from
     * the near side, so port, belt and press stand in one line and the shot says which port feeds which machine. From
     * the far side the barrel at the lane's end stands between the camera and the press.
     */
    private static final CameraView MACHINE = CameraView.of("machine", 9.2, 3.75, -8.0, 11.3, 1.7, -2.2);
    /**
     * The crane handing items over to an accepting port, reconstructed on the client alone: in the aisle two positions
     * past the crane, a little above the carriage, looking back at the arm as it reaches into the port.
     * <p>
     * The {@code priorities} scenario's own delivery framing, resolved for a crane at aisle position
     * {@value #FILTERED_X} and rack level {@value #PORT_LEVEL} — three other framings were tried and thrown away for it
     * there. The only number changed is {@code z}, from 0.8 to 0.55, because at 0.8 a flying player stands in the
     * storage location of the opposite rack wall. Two attempts to "improve" the offset along the aisle were thrown away:
     * at 3.4 positions past the crane the <b>next port of the row</b> covers the whole handover, which is exactly what
     * that scenario's comment warns about.
     * <p>
     * It is the <b>cobblestone</b> trip of the filtered overflow, not the iron trip of the general one, for the reason
     * the {@code priorities} scenario found: Create draws a held item at the grabber, and an ingot is a flat sprite that
     * disappears against it, while a block item is a cube a screenshot can make out.
     */
    private static final CameraView HANDOVER = CameraView.of("handover", 7.1, 2.35, 0.55, 5.5, 1.55, -0.3);

    /**
     * Goggle cameras. Each one aims at a <b>corner</b> of the port's back plate, a good 6 px away from the centre of its
     * filter slot: a value box the crosshair really hits makes Create's goggle overlay bail out before it draws a single
     * line, and a port's filter slot sits dead centre on every face it is on.
     */
    private static final CameraView GOGGLES_FILTERED =
            CameraView.of("goggles-filtered", 5.2, 2.35, -3.6, 5.2, 1.2, -1.02);
    private static final CameraView GOGGLES_DIVERSION =
            CameraView.of("goggles-diversion", 3.2, 2.35, -3.6, 3.2, 1.2, -1.02);
    /** The machine port: off the lane, because the press itself stands where a camera in line with it would be. */
    private static final CameraView GOGGLES_MACHINE =
            CameraView.of("goggles-machine", 13.0, 2.5, -3.0, 11.2, 1.2, -1.02);
    /**
     * West of the controller, aimed at the lower left corner of its back face rather than at its middle: the
     * controller's aisle letter is a scroll value box on every face but the dock's.
     */
    private static final CameraView GOGGLES_CONTROLLER =
            CameraView.of("goggles-controller", -3.6, 2.6, 0.55, -1.0, 0.25, 0.25);

    /**
     * Every camera of this scenario, for {@link #assertCamerasAreFree}: a camera whose eye stands inside a block is a
     * failure at its own step, minutes into the run, with a message about a player that "did not arrive". Checking all of
     * them while the scene is being built turns that into one line at second forty.
     */
    private static final List<CameraView> ALL_VIEWS =
            List.of(OVERVIEW, PORTS_AISLE, RINGS, PORTS_BACK, OVERFLOW_LANE, OVERFLOW_FLOW, AT_OVERFLOW, AT_FILTERED,
                    AT_DIVERSION, MACHINE, HANDOVER, GOGGLES_FILTERED, GOGGLES_DIVERSION, GOGGLES_MACHINE,
                    GOGGLES_CONTROLLER);

    /** What the scene must hold, per item key, after every phase (server thread). */
    private final Map<ItemKey, Long> expected = new HashMap<>();
    /** The item the running feed phase is about, so its poll knows what to look for in the chest (server thread). */
    private Item feeding = IRON;
    /** Consecutive polls with an empty feed and an idle crane (server thread). */
    private int idlePolls;
    /** Iron in stock when the machine phase started, so "what the machine was handed" is measurable. */
    private long ironBeforeTheMachine;
    /** Highest number of open requests the machine phase ever saw; the "at most one" rule, measured. */
    private int maxOpenRequests;

    /** The client crane pose the injected one replaced, so it can be put back after the handover shot. */
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
        script.client("ports: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "ports run"))
                .server("ports: clear the area and place the crane's motor", PortsVisualScenario::placeMotors)
                .server("ports: build the aisle, the stations, the racks, the ports and both machines",
                        PortsVisualScenario::buildScene)
                .serverUntil("ports: wait until the controller is ready with every member",
                        PortsVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .server("ports: aim all three belts", PortsVisualScenario::aimBelts)
                .serverUntil("ports: wait until every belt really carries the way it must",
                        PortsVisualScenario::beltsAimed, BELT_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so
                // it is lifted into the air first and only then made to fly.
                .server("ports: lift the player into the air", PortsVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("ports: the player flies and wears Engineer's Goggles", PortsVisualScenario::equipPlayer)
                .server("ports: write the two stock rules", PortsVisualScenario::writeRules)
                .serverUntil("ports: wait until the aisle enforces both", PortsVisualScenario::rulesLive,
                        RULE_TIMEOUT_TICKS)
                .server("ports: configure all five ports", PortsVisualScenario::configurePorts)
                .server("ports: put the levers on the ports that have one", PortsVisualScenario::placeLevers)
                .server("ports: check that every camera of this scenario can be stood in",
                        PortsVisualScenario::assertCamerasAreFree)
                .server("ports: check the scene every later assertion depends on", this::assertInitialState)
                .until("ports: wait until the client sees every port's direction",
                        PortsVisualScenario::portsSynced, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and a port has two of
        // them, so every world shot is taken by a player who reaches nothing.
        reach(script, 0.0);
        if (pass == VisualPass.FLYWHEEL) {
            fly(script);
            theMaximumFeedsAnUnwiredOverflow(script);
            aLeverSwitchesItOff(script);
            theFilteredOverflowSorts(script);
            theDiversionTakesEverything(script);
            thePulseStillHandsOut(script);
            theHeldSignalFeedsAMachine(script);
        }

        fly(script);
        for (CameraView view : List.of(OVERVIEW, PORTS_AISLE, RINGS, PORTS_BACK, OVERFLOW_LANE, MACHINE))
            script.shotFrom(view, "ports");

        if (pass == VisualPass.FLYWHEEL) {
            // The goggles: the only shots with the GUI shown, and the only ones that need the real reach.
            reach(script, vanillaReach());
            fly(script);
            GoggleShots.shot(script, "ports", GOGGLES_FILTERED, "goggles-filtered",
                    dock -> layout(dock).rackPos(FILTERED_PORT.position()),
                    PortsVisualScenario::filteredPortSynced, PortsVisualScenario::checkFilteredGoggles);
            fly(script);
            GoggleShots.shot(script, "ports", GOGGLES_DIVERSION, "goggles-diversion",
                    dock -> layout(dock).rackPos(DIVERSION_PORT.position()),
                    PortsVisualScenario::diversionPortSynced, PortsVisualScenario::checkDiversionGoggles);
            fly(script);
            GoggleShots.shot(script, "ports", GOGGLES_MACHINE, "goggles-machine",
                    dock -> layout(dock).rackPos(MACHINE_PORT.position()),
                    PortsVisualScenario::machinePortSynced, PortsVisualScenario::checkMachineGoggles);
            fly(script);
            GoggleShots.shot(script, "ports", GOGGLES_CONTROLLER, "goggles-controller",
                    PortsVisualScenario::controllerPos, PortsVisualScenario::controllerSynced,
                    PortsVisualScenario::checkControllerGoggles);
            reach(script, 0.0);
            script.client("ports: every check passed",
                    context -> LOGGER.info(PREFIX + "ports: ALL CHECKS PASSED (a maximum feeds an unwired overflow, a "
                            + "lever switches it off, a filtered overflow sorts, a diversion outranks storage while "
                            + "powered, a pulse hands out as before, a held signal feeds a machine with one open "
                            + "request, goggles)"));
        }
    }

    // --- phase 1: the maximum feeds an unwired overflow ---------------------------------------------------------------

    /**
     * The user's first row together with M15: a port with <b>no filter and no wiring at all</b> receives what a stock
     * rule's maximum stops the warehouse from keeping. Both the settled scene and the surplus in motion are shot, the
     * latter with the ticks frozen so the picture is the same on every run.
     */
    private void theMaximumFeedsAnUnwiredOverflow(VisualScript script) {
        script.shotFrom(AT_OVERFLOW, "unwired-before")
                .server("ports: put " + IRON_FED + " iron ingots into the feed chest",
                        (server, context) -> startFeed(server, context, IRON, IRON_FED))
                .serverUntil("ports: wait until the surplus really rides the overflow belt", this::surplusOnTheBelt,
                        FEED_TIMEOUT_TICKS)
                .freeze(true)
                .shotFrom(OVERFLOW_FLOW, "overflow-flowing")
                .freeze(false)
                .serverUntil("ports: run the belt and the crane until every iron ingot has moved", this::fedAndSettled,
                        FEED_TIMEOUT_TICKS)
                .server("ports: check that the maximum kept " + IRON_MAXIMUM + " and the overflow took the rest",
                        this::assertOverflowTookTheSurplus)
                .shotFrom(AT_OVERFLOW, "unwired-after");
    }

    // --- phase 2: a lever switches it off ----------------------------------------------------------------------------

    /** The second half of the user's first row: one lever, and the port acts no more. */
    private void aLeverSwitchesItOff(VisualScript script) {
        script.server("ports: put a lever on the general overflow and switch it on",
                        (server, context) -> switchOverflowLever(server, context, true))
                .until("ports: wait until the client sees the powered port",
                        PortsVisualScenario::overflowPowered, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shotFrom(AT_OVERFLOW, "levered-off")
                .server("ports: feed " + IRON_JAMMED + " more iron ingots that now have nowhere to go",
                        (server, context) -> startFeed(server, context, IRON, IRON_JAMMED))
                .serverUntil("ports: wait until they have stopped moving", this::fedAndSettled, FEED_TIMEOUT_TICKS)
                .server("ports: check that a switched-off port takes nothing and the input backs up",
                        this::assertLeverStoppedIt);
    }

    // --- phase 3: the filtered overflow sorts ------------------------------------------------------------------------

    /** The user's second row: only the surplus cobblestone leaves, and the controller says why the iron does not. */
    private void theFilteredOverflowSorts(VisualScript script) {
        script.server("ports: feed " + COBBLE_FED + " cobblestone while the iron is still stuck",
                        (server, context) -> startFeed(server, context, COBBLE, COBBLE_FED))
                .serverUntil("ports: wait until the cobblestone has been sorted", this::fedAndSettled,
                        FEED_TIMEOUT_TICKS)
                .server("ports: check that only the surplus cobblestone left", this::assertFilteredOverflowSorted)
                .shotFrom(AT_FILTERED, "filtered")
                // The trip itself is over by now, so the moment is reconstructed on the client alone: the crane at the
                // port, arm out, the surplus cobblestone in its head. Nothing on the server moves, and the pose is put
                // back before the next shot.
                .freeze(true)
                .camera(HANDOVER)
                .client("ports: show the crane handing the surplus over to the filtered overflow", this::showHandover)
                .shot("handover")
                .client("ports: put the client crane back where it stands", this::restoreParkedPose)
                .freeze(false);
    }

    // --- phase 4: the diversion takes everything ---------------------------------------------------------------------

    /**
     * The user's third row: while the signal is high a positive rank outranks five <b>empty</b> storage locations, so
     * everything incoming is diverted out — including the iron the maximum jammed. The moment the lever drops the
     * warehouse stores again, which is the whole interface.
     */
    private void theDiversionTakesEverything(VisualScript script) {
        script.shotFrom(AT_DIVERSION, "diversion-before")
                .server("ports: switch the diversion's lever on",
                        (server, context) -> setLever(server, context, DIVERSION_PORT, true))
                .until("ports: wait until the client sees the powered diversion",
                        PortsVisualScenario::diversionPowered, SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("diversion-on")
                .server("ports: feed " + GOLD_DIVERTED + " gold ingots into a warehouse that stores nothing",
                        (server, context) -> startFeed(server, context, GOLD, GOLD_DIVERTED))
                .serverUntil("ports: wait until the diversion has taken them", this::fedAndSettled,
                        FEED_TIMEOUT_TICKS)
                .server("ports: check that nothing was stored while the signal was high",
                        this::assertDiversionTookEverything)
                .server("ports: switch the diversion's lever off again",
                        (server, context) -> setLever(server, context, DIVERSION_PORT, false))
                .until("ports: wait until the client sees the unpowered diversion",
                        context -> !diversionPowered(context), SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .shot("diversion-off")
                .server("ports: feed " + GOLD_STORED + " gold ingots into the same warehouse",
                        (server, context) -> startFeed(server, context, GOLD, GOLD_STORED))
                .serverUntil("ports: wait until they are stored", this::fedAndSettled, FEED_TIMEOUT_TICKS)
                .server("ports: check that the warehouse stores again the moment the lever drops",
                        this::assertStoresAgain);
    }

    // --- phase 5: a pulse still hands out ----------------------------------------------------------------------------

    /** The user's fourth row: the port of a plain warehouse output, one delivery per rising edge, exactly as before. */
    private void thePulseStillHandsOut(VisualScript script) {
        for (int pulse = 1; pulse <= PULSES; pulse++) {
            int expectedDelivered = pulse * PULSE_REQUEST;
            script.server("ports: rising edge " + pulse + " at the pulse port",
                            (server, context) -> pulseTheLever(server, context, PULSE_PORT))
                    .serverUntil("ports: wait until " + expectedDelivered + " diamonds have arrived",
                            (server, context) -> pulseDelivered(server, context, expectedDelivered),
                            DELIVERY_TIMEOUT_TICKS);
        }
        script.serverUntil("ports: wait until the aisle is quiet again", this::fedAndSettled, FEED_TIMEOUT_TICKS)
                .server("ports: check that each edge handed out exactly what the filter asks for",
                        this::assertPulsesHandedOut);
    }

    // --- phase 6: a held signal feeds a machine ----------------------------------------------------------------------

    /**
     * The user's fifth row: a machine is kept supplied by one held signal and no clock. The maximum is raised first —
     * the same rule that made the overflow necessary now lets the warehouse hold the machine's supply — and the number
     * of open requests is measured on <b>every</b> server poll, because "at most one at a time" is the whole promise.
     */
    private void theHeldSignalFeedsAMachine(VisualScript script) {
        script.server("ports: raise the iron maximum to " + IRON_MAXIMUM_RAISED + " and feed "
                        + IRON_RESTOCKED + " iron ingots", this::raiseTheMaximumAndFeed)
                .serverUntil("ports: wait until the warehouse has stored them", this::fedAndSettled,
                        FEED_TIMEOUT_TICKS)
                .server("ports: check that raising the maximum stored what the overflow used to take",
                        this::assertRaisedMaximumStores)
                .server("ports: switch the machine port's lever on", this::startTheMachine)
                .serverUntil("ports: keep the press supplied until it has pressed " + SHEETS_BEFORE_THE_SHOT
                        + " sheets", this::machineRunning, MACHINE_TIMEOUT_TICKS)
                .freeze(true)
                .shotFrom(MACHINE, "machine-running")
                .freeze(false)
                // The whole stock, not a chosen number of trips: the lever then drops at a moment no request can be
                // open, because there is nothing left to ask for, so the closing assertion cannot race the controller.
                .serverUntil("ports: keep it running until the racks are empty and nothing is on its way",
                        this::machineSupplied, MACHINE_TIMEOUT_TICKS)
                .server("ports: switch the machine port's lever off",
                        (server, context) -> setLever(server, context, MACHINE_PORT, false))
                .serverUntil("ports: wait until the press has finished", this::pressFinished, MACHINE_TIMEOUT_TICKS)
                .server("ports: check that one signal fed the machine with one open request at a time",
                        this::assertMachineWasFed)
                .until("ports: wait until the client sees the parked crane", PortsVisualScenario::craneParkedOnClient,
                        SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        ClientLevel level = context.minecraft().level;
        if (level != null) {
            BranchLayout layout = layout(context.origin());
            for (Port port : PORTS) {
                BlockPos pos = layout.rackPos(port.position());
                status.append(status.isEmpty() ? "ports=" : ",").append(port.id()).append(':');
                BlockState state = level.getBlockState(pos);
                if (level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity be)
                    status.append(PortSettings.formatRank(be.portRank())).append('/')
                            .append(be.redstoneMode().name().charAt(0))
                            .append(state.getOptionalValue(WarehouseOutputBlock.POWERED).orElse(false) ? "+" : "-")
                            .append(state.getOptionalValue(WarehouseOutputBlock.ACCEPTING).orElse(false) ? "A" : "R");
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

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private static void placeMotors(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        for (BlockPos pos : BlockPos.betweenClosed(clearFrom(dock), clearTo(dock)))
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

        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(KEEPER), WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, inward(layout, KEEPER)));
        level.setBlockAndUpdate(layout.rackPos(INPUT), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState()
                .setValue(WarehouseInputBlock.FACING, inward(layout, INPUT)));

        buildStorage(level, layout);
        buildPorts(level, layout);
        buildFeedBelt(level, layout);
        buildOverflowLane(level, layout);
        buildMachine(level, layout);
    }

    /**
     * The warehouse's own rack side: five unfiltered storage locations behind <b>barrels</b>, never chests — two chests
     * side by side merge into one inventory of two rack positions, and a shared-inventory alias would make every
     * assertion of this scenario about the wrong thing.
     */
    private static void buildStorage(ServerLevel level, BranchLayout layout) {
        Direction outward = layout.sideDirection(Side.RIGHT);
        for (int x : STORAGE_X) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.RIGHT));
            level.setBlockAndUpdate(rack.relative(outward), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, outward));
        }
        // The stock the pulse port hands out: put in by hand, because a rising edge can only give away what is there.
        insertAll(level, layout.rackPos(RackPosition.of(STORAGE_X.getFirst(), 0, Side.RIGHT)).relative(outward),
                new ItemStack(DIAMOND, DIAMONDS_IN_STOCK));
    }

    /**
     * The port row and what drains it: a port one level up, a vanilla hopper below it facing out of the rack wall, and
     * whatever the player built behind that. The hopper is the honest drain — the port's capability is extract-only, so
     * a funnel, a chute, a hopper or a Mechanical Arm are what a player has, and a hopper is the one that needs no
     * kinetic power and no filter.
     */
    private static void buildPorts(ServerLevel level, BranchLayout layout) {
        Direction outward = layout.sideDirection(PORT_SIDE);
        for (Port port : PORTS) {
            BlockPos pos = layout.rackPos(port.position());
            level.setBlockAndUpdate(pos, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                    .setValue(WarehouseOutputBlock.FACING, outward.getOpposite()));
            level.setBlockAndUpdate(drainPos(layout, port),
                    Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, outward));
            // Barrels for the three ports whose receiver is just storage; the other two get a belt of their own.
            if (port.laneLength() == 0)
                level.setBlockAndUpdate(lanePos(layout, port, 0), Blocks.BARREL.defaultBlockState());
        }
    }

    /**
     * A lane behind a port: a belt out of its drain, a stop at the end so nothing is ever ejected onto the floor, and an
     * <b>andesite belt funnel</b> over the last segment that takes whatever arrives into a barrel beside it.
     * <p>
     * The funnel is what keeps the lane <b>moving</b>: a belt that ends in a solid face simply queues its items up
     * against it ({@code BeltInventory#resolveEnding}), and a queue that reaches the drain would stop the port from
     * being emptied at all — which is a picture of a jam, not of a surplus leaving. It faces along the aisle, because a
     * belt funnel is only perpendicular (and so only takes items off) when its facing axis differs from the belt's
     * movement axis, and it hands its items to the block at {@code facing.getOpposite()}.
     */
    private static void buildLane(ServerLevel level, BranchLayout layout, Port port, String what) {
        BlockPos start = lanePos(layout, port, 0);
        BlockPos end = lanePos(layout, port, port.laneLength() - 1);
        belt(level, start, end, what);
        motorAt(level, start.relative(AISLE.getOpposite()), AISLE, BELT_RPM);
        level.setBlockAndUpdate(lanePos(layout, port, port.laneLength()),
                Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(end.above(), AllBlocks.ANDESITE_BELT_FUNNEL.getDefaultState()
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, AISLE.getOpposite())
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.Shape.PULLING));
        BlockPos barrel = laneBarrelPos(layout, port);
        level.setBlockAndUpdate(barrel.below(), Blocks.POLISHED_ANDESITE.defaultBlockState());
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
    }

    /**
     * The feed: a chest over the belt's tail and a belt into the warehouse input, whose
     * {@code DirectBeltInputBehaviour} is the path Create designs for a belt-fed machine. {@link #feedStep} empties the
     * chest onto the belt one stack at a time, from above, with exactly the call a chute or a brass funnel over the tail
     * makes ({@code AbstractChuteBlock}).
     */
    private static void buildFeedBelt(ServerLevel level, BranchLayout layout) {
        BlockPos start = feedBeltStart(layout);
        BlockPos end = feedBeltEnd(layout);
        belt(level, start, end, "the feed belt");
        // A belt along the rack side turns on the axis across it, so its motor sits beside the belt's first pulley.
        motorAt(level, start.relative(AISLE), AISLE.getOpposite(), BELT_RPM);
        level.setBlockAndUpdate(start.above(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, AISLE));
    }

    /** The lane behind the general overflow: a belt carrying the surplus out of the warehouse. */
    private static void buildOverflowLane(ServerLevel level, BranchLayout layout) {
        buildLane(level, layout, OVERFLOW_PORT, "the overflow belt");
    }

    /**
     * The machine the fifth port keeps supplied: a lane out of the port and a <b>Mechanical Press</b> two blocks above
     * it, which is where Create looks for a belt to press on ({@code PressingBehaviour}: {@code below(2)}). The press
     * turns each iron ingot into an iron sheet as it rides past, so what a screenshot shows is a machine really being
     * fed, not a chest filling up. The press stands before the funnel in the belt's own direction, so nothing can reach
     * the barrel unpressed.
     */
    private static void buildMachine(ServerLevel level, BranchLayout layout) {
        buildLane(level, layout, MACHINE_PORT, "the machine belt");
        BlockPos press = pressPos(layout);
        // HORIZONTAL_FACING *is* the press's rotation axis (MechanicalPressBlock#getRotationAxis), so its motor stands
        // beside it on the aisle axis, west of it — east is where the camera of the machine shot looks from.
        level.setBlockAndUpdate(press, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        motorAt(level, press.relative(AISLE.getOpposite()), AISLE, PRESS_RPM);
    }

    private static void belt(ServerLevel level, BlockPos start, BlockPos end, String what) {
        BeltConnectorItem.createBelts(level, start, end);
        if (!AllBlocks.BELT.has(level.getBlockState(start)) || !AllBlocks.BELT.has(level.getBlockState(end)))
            throw new VisualTestException(what + " was not created between " + start + " and " + end);
    }

    private static void motorAt(ServerLevel level, BlockPos pos, Direction facing, int rpm) {
        level.setBlockAndUpdate(pos,
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, facing));
        motor(level, pos).generatedSpeed.setValue(rpm);
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
                && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == STORAGE_X.size()
                && controller.inputStations().size() == 1 && controller.outputStations().size() == PORTS.size()
                && controller.countOf(DIAMOND_KEY) == DIAMONDS_IN_STOCK
                && crane.isControllerLinked() && crane.aisleLength() == RAILS;
    }

    /** Which way a belt carries follows the sign of its rotation, so every belt is read back and reversed if wrong. */
    private static void aimBelts(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        aimBelt(level, feedBeltStart(layout), feedBeltStart(layout).relative(AISLE), feedDirection(layout),
                "the feed belt");
        aimBelt(level, lanePos(layout, OVERFLOW_PORT, 0),
                lanePos(layout, OVERFLOW_PORT, 0).relative(AISLE.getOpposite()), laneDirection(layout),
                "the overflow belt");
        aimBelt(level, lanePos(layout, MACHINE_PORT, 0),
                lanePos(layout, MACHINE_PORT, 0).relative(AISLE.getOpposite()), laneDirection(layout),
                "the machine belt");
    }

    private static void aimBelt(ServerLevel level, BlockPos segment, BlockPos motorPos, Direction wanted, String what) {
        Direction actual = beltMovement(level, segment);
        LOGGER.info(PREFIX + "ports: {} carries {} and must carry {}", what, actual, wanted);
        if (actual == wanted)
            return;
        CreativeMotorBlockEntity motor = motor(level, motorPos);
        motor.generatedSpeed.setValue(-motor.generatedSpeed.getValue());
    }

    private static boolean beltsAimed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        return beltMovement(level, feedBeltStart(layout)) == feedDirection(layout)
                && beltMovement(level, lanePos(layout, OVERFLOW_PORT, 0)) == laneDirection(layout)
                && beltMovement(level, lanePos(layout, MACHINE_PORT, 0)) == laneDirection(layout);
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(PORT_LEVEL + 6);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * Lifts the player back into the air and switches creative flight on again, and waits until the client has it.
     * <p>
     * Needed before <b>every</b> group of camera views, not once at the start: a creative player that touches the ground
     * switches flying off again by itself ({@code LocalPlayer#aiStep}), and the very next camera whose feet stand in
     * mid-air then fails to arrive because the client falls out of it.
     */
    private static void fly(VisualScript script) {
        script.server("ports: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("ports: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    /** Flying (so a camera in mid-air holds), goggles on the head, and nothing in hand, so no item covers a shot. */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    // --- the rules and the ports -------------------------------------------------------------------------------------

    private static void writeRules(MinecraftServer server, VisualContext context) {
        WarehouseStockKeeperBlockEntity keeper = keeper(server.overworld(), context.origin());
        rule(keeper, RULE_IRON, IRON_KEY, IRON_MAXIMUM);
        rule(keeper, RULE_COBBLE, COBBLE_KEY, COBBLE_MAXIMUM);
        LOGGER.info(PREFIX + "ports: the keeper holds {} rules", keeper.rules().ruleCount());
    }

    private static void rule(WarehouseStockKeeperBlockEntity keeper, int row, ItemKey key, long maximum) {
        if (!keeper.editRule(row, StockKeeperRules.FIELD_ITEM, key, 0L).changed()
                || !keeper.editRule(row, StockKeeperRules.FIELD_MAXIMUM, null, maximum).changed())
            throw new VisualTestException("the rule in row " + row + " could not be written");
    }

    private static boolean rulesLive(MinecraftServer server, VisualContext context) {
        return controller(server.overworld(), context.origin()).stockRules().governingCount() == RULES;
    }

    /** Sets rank, redstone behaviour and filter of every port, exactly as its two value boxes would. */
    private static void configurePorts(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        for (Port port : PORTS) {
            WarehouseOutputBlockEntity be = portAt(level, layout, port);
            if (port.rank() != PortSettings.REQUEST_RANK && !be.setPortRank(port.rank()))
                throw new VisualTestException("the port " + port.id() + " refused the rank " + port.rank());
            be.setRedstoneMode(port.redstone());
            if (!be.portSettings().equals(port.settings()))
                throw new VisualTestException("the port " + port.id() + " reports " + be.portSettings()
                        + " instead of " + port.settings());
            if (port.filter().isPresent()) {
                RequestFilterBehaviour filter = filterOf(be);
                if (!filter.setFilter(new ItemStack(port.filter().get())))
                    throw new VisualTestException("the port " + port.id() + " refused the filter "
                            + port.filter().get());
                // After setFilter, which may clamp the count; an accepting port's amount column is never read.
                if (port.requestAmount() > 0)
                    filter.count = port.requestAmount();
            }
            LOGGER.info(PREFIX + "ports: {} at {} is {} on '{}'{}", port.id(), port.position(),
                    PortSettings.formatRank(port.rank()), port.redstone().name(),
                    port.filter().map(item -> " filtered to " + item).orElse(" unfiltered"));
        }
    }

    /**
     * A lever on top of every port but the general overflow: a floor lever there is orthogonally adjacent to the port,
     * so {@code hasNeighborSignal} sees it, and it covers neither the aisle opening nor the back plate the rank is
     * painted on. The general overflow gets none yet — its first row is about a port that is <b>wired to nothing</b>.
     */
    private static void placeLevers(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        for (Port port : PORTS) {
            if (port == OVERFLOW_PORT)
                continue;
            placeLever(level, leverPos(layout, port));
        }
    }

    private static void placeLever(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, AISLE)
                .setValue(LeverBlock.POWERED, false));
        if (!level.getBlockState(pos).is(Blocks.LEVER))
            throw new VisualTestException("the lever at " + pos + " did not survive: " + level.getBlockState(pos));
    }

    /**
     * Every camera of this scenario stands in air, checked while the scene is fresh.
     * <p>
     * A camera view is a place a real, colliding player is teleported to, so a block in the way does not move the camera
     * a little — it fails the step with "the camera did not arrive", up to fifteen minutes into the run, and names a
     * position rather than the block that is in the way. This scene has two belt lanes, two machines and five levers
     * around the cameras, so the cheap check is worth one server step.
     */
    private static void assertCamerasAreFree(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ServerPlayer player = context.serverPlayer(server);
        for (CameraView view : ALL_VIEWS) {
            CameraView.Placement placement = view.placement(context.origin(), player.getEyeHeight());
            AABB box = player.getDimensions(Pose.STANDING)
                    .makeBoundingBox(placement.x(), placement.y(), placement.z());
            if (!level.noCollision(box))
                throw new VisualTestException("the camera view '" + view.label() + "' stands in a block: a player at "
                        + placement.feet() + " collides with the scene");
        }
        LOGGER.info(PREFIX + "ports: CHECK all {} camera positions are free of blocks", ALL_VIEWS.size());
    }

    /**
     * The scene every later assertion depends on: five ports with the policy they were given, the block state that
     * follows from it, a warehouse that has stored nothing but the diamonds, and one drain per port.
     */
    private void assertInitialState(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        for (Port port : PORTS) {
            WarehouseOutputBlockEntity be = portAt(level, layout, port);
            if (!be.portSettings().equals(port.settings()))
                throw new VisualTestException("the port " + port.id() + " lost its policy: " + be.portSettings());
            boolean accepting = level.getBlockState(layout.rackPos(port.position()))
                    .getValue(WarehouseOutputBlock.ACCEPTING);
            if (accepting != port.accepts())
                throw new VisualTestException("the port " + port.id() + " shows accepting=" + accepting
                        + ", but its rank " + port.rank() + " says " + port.accepts());
            if (be.exportedItems() != 0)
                throw new VisualTestException("the port " + port.id() + " has already handed something over");
            if (handlerAt(level, drainPos(layout, port)).getSlots() != 5)
                throw new VisualTestException("the drain below " + port.id() + " is not a hopper");
        }
        int accepting = (int) PORTS.stream().filter(Port::accepts).count();
        if (controller.acceptingPortCount() != accepting)
            throw new VisualTestException("the controller counts " + controller.acceptingPortCount()
                    + " accepting ports, expected " + accepting);
        if (IRON.getDefaultMaxStackSize() != STACK || GOLD.getDefaultMaxStackSize() != STACK)
            throw new VisualTestException("the fed items no longer stack to " + STACK);
        expected.clear();
        expected.put(DIAMOND_KEY, (long) DIAMONDS_IN_STOCK);
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK {} ports configured, {} of them accepting, {} storage locations, {} "
                + "diamonds in stock", PORTS.size(), accepting, STORAGE_X.size(), DIAMONDS_IN_STOCK);
    }

    // --- feeding -----------------------------------------------------------------------------------------------------

    /** Starts a feed phase: the items go into the feed chest, and the census expectation grows by them. */
    private void startFeed(MinecraftServer server, VisualContext context, Item item, int amount) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        feeding = item;
        idlePolls = 0;
        insertAll(level, feedChestPos(layout), new ItemStack(item, amount));
        add(ItemKey.of(item), amount);
        LOGGER.info(PREFIX + "ports: {} {} are on their way onto the feed belt", amount, item);
    }

    /**
     * One poll of a feed phase: keep the belt fed and report when nothing is moving any more — the feed chest and the
     * belt are empty and the crane has been idle for {@value #IDLE_POLLS} polls, which is far more than the
     * controller's dispatch interval, so "the warehouse decided to do nothing" is a decision and not a gap.
     */
    private boolean fedAndSettled(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        feedBelt(level, layout);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        // Create's belt takes an inserted stack into a pending queue that its own next tick moves into the visible one
        // (BeltInventory#addItem), so for one tick after every insertion the belt looks empty; the chest is checked too.
        boolean quiet = countAt(level, feedChestPos(layout), feeding) == 0
                && beltItems(level, feedBeltStart(layout)) == 0 && craneIdle(crane);
        idlePolls = quiet ? idlePolls + 1 : 0;
        return idlePolls >= IDLE_POLLS;
    }

    /**
     * Moves one stack from the feed chest onto the belt's tail, from above, whenever that segment is free — the call a
     * chute or a brass funnel over the tail makes.
     */
    private static void feedBelt(ServerLevel level, BranchLayout layout) {
        IItemHandler chest = handlerAt(level, feedChestPos(layout));
        BlockPos start = feedBeltStart(layout);
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(level, start, DirectBeltInputBehaviour.TYPE);
        if (belt == null)
            throw new VisualTestException("no belt with a direct belt input at " + start);
        if (!belt.canInsertFromSide(Direction.UP) || belt.isOccupied(Direction.UP))
            return;
        for (int slot = 0; slot < chest.getSlots(); slot++) {
            ItemStack available = chest.extractItem(slot, STACK, true);
            if (available.isEmpty())
                continue;
            ItemStack rest = belt.handleInsertion(available.copy(), Direction.UP, false);
            int moved = available.getCount() - rest.getCount();
            if (moved > 0)
                chest.extractItem(slot, moved, false);
            return;
        }
    }

    // --- phase assertions --------------------------------------------------------------------------------------------

    /** The surplus really rides the overflow belt, so the frozen shot of it is a picture of something happening. */
    private boolean surplusOnTheBelt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        feedBelt(level, layout);
        return beltItems(level, lanePos(layout, OVERFLOW_PORT, 0)) >= ITEMS_ON_THE_BELT;
    }

    private void assertOverflowTookTheSurplus(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = controller.countOf(IRON_KEY);
        long handedOver = portAt(level, layout, OVERFLOW_PORT).exportedItems();
        long waiting = input(level, layout).bufferedItems().count(IRON_KEY);
        if (stored != IRON_MAXIMUM || handedOver != IRON_OVERFLOWED || waiting != 0)
            throw new VisualTestException("the warehouse stored " + stored + " iron, handed " + handedOver
                    + " over and left " + waiting + " in the input, expected " + IRON_MAXIMUM + ", "
                    + IRON_OVERFLOWED + " and 0");
        if (controller.storeHeadroom(IRON_KEY) != 0L)
            throw new VisualTestException("the iron rule still leaves headroom at its maximum");
        long inTheLane = itemsBehind(level, layout, OVERFLOW_PORT, IRON);
        if (inTheLane != IRON_OVERFLOWED)
            throw new VisualTestException("the overflow lane holds " + inTheLane + " iron, expected "
                    + IRON_OVERFLOWED + ": what a port receives must really leave the warehouse");
        if (controller.countOf(IRON_KEY) != IRON_MAXIMUM)
            throw new VisualTestException("what sits in a port must never be counted as stock");
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK the maximum kept {} iron and a port wired to nothing handed {} over; the "
                + "input is empty", stored, handedOver);
    }

    private void assertLeverStoppedIt(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long handedOver = portAt(level, layout, OVERFLOW_PORT).exportedItems();
        long waiting = input(level, layout).bufferedItems().count(IRON_KEY);
        if (handedOver != IRON_OVERFLOWED || waiting != IRON_JAMMED)
            throw new VisualTestException("the switched-off port handed " + handedOver + " over in total and the input "
                    + "holds " + waiting + " iron, expected " + IRON_OVERFLOWED + " and " + IRON_JAMMED);
        if (controller.countOf(IRON_KEY) != IRON_MAXIMUM)
            throw new VisualTestException("the maximum stopped holding while the port was off");
        assertReason(controller, NoJobReason.AT_MAXIMUM);
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK one lever, and the port takes nothing: {} iron back up in the input, the "
                + "controller reports {}", waiting, NoJobReason.AT_MAXIMUM);
    }

    private void assertFilteredOverflowSorted(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long storedCobble = controller.countOf(COBBLE_KEY);
        long handedOver = portAt(level, layout, FILTERED_PORT).exportedItems();
        long ironWaiting = input(level, layout).bufferedItems().count(IRON_KEY);
        long cobbleWaiting = input(level, layout).bufferedItems().count(COBBLE_KEY);
        if (storedCobble != COBBLE_MAXIMUM || handedOver != COBBLE_OVERFLOWED || cobbleWaiting != 0)
            throw new VisualTestException("the warehouse stored " + storedCobble + " cobblestone, the filtered port "
                    + "handed " + handedOver + " over and " + cobbleWaiting + " are still in the input, expected "
                    + COBBLE_MAXIMUM + ", " + COBBLE_OVERFLOWED + " and 0");
        if (itemsBehind(level, layout, FILTERED_PORT, IRON) != 0)
            throw new VisualTestException("the filtered port took iron, which its filter does not name");
        if (ironWaiting != IRON_JAMMED)
            throw new VisualTestException("the input holds " + ironWaiting + " iron, expected the " + IRON_JAMMED
                    + " that still have nowhere to go: a port's filter is a hard rule");
        assertReason(controller, NoJobReason.AT_MAXIMUM);
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK only the surplus left: {} cobblestone stored, {} handed over, and {} iron "
                + "still backed up because no port accepts it", storedCobble, handedOver, ironWaiting);
    }

    private void assertDiversionTookEverything(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long handedOver = portAt(level, layout, DIVERSION_PORT).exportedItems();
        long expectedHandover = GOLD_DIVERTED + IRON_JAMMED;
        if (handedOver != expectedHandover)
            throw new VisualTestException("the diversion handed " + handedOver + " items over, expected "
                    + expectedHandover + " (the gold plus the iron the maximum had jammed)");
        if (controller.countOf(GOLD_KEY) != 0)
            throw new VisualTestException("the warehouse stored " + controller.countOf(GOLD_KEY) + " gold although a "
                    + "diversion outranks every storage location while it is powered");
        if (itemsBehind(level, layout, DIVERSION_PORT, GOLD) != GOLD_DIVERTED
                || itemsBehind(level, layout, DIVERSION_PORT, IRON) != IRON_JAMMED)
            throw new VisualTestException("the diversion's barrel does not hold what the port took");
        if (input(level, layout).bufferedItems().count(IRON_KEY) != 0)
            throw new VisualTestException("the diversion left the jammed iron in the input");
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK a powered diversion took everything incoming: {} gold and the {} iron the "
                + "maximum had jammed, past five empty storage locations", GOLD_DIVERTED, IRON_JAMMED);
    }

    private void assertStoresAgain(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = controller.countOf(GOLD_KEY);
        long handedOver = portAt(level, layout, DIVERSION_PORT).exportedItems();
        if (stored != GOLD_STORED || handedOver != GOLD_DIVERTED + IRON_JAMMED)
            throw new VisualTestException("after the lever dropped the warehouse stored " + stored + " gold and the "
                    + "port had handed " + handedOver + " over, expected " + GOLD_STORED + " and "
                    + (GOLD_DIVERTED + IRON_JAMMED));
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK the moment the lever drops the warehouse stores again: {} gold into the "
                + "racks, nothing more through the port", stored);
    }

    private static boolean pulseDelivered(MinecraftServer server, VisualContext context, int expectedTotal) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        return itemsBehind(level, layout, PULSE_PORT, DIAMOND) >= expectedTotal;
    }

    private void assertPulsesHandedOut(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long handedOut = itemsBehind(level, layout, PULSE_PORT, DIAMOND);
        long inStock = controller.countOf(DIAMOND_KEY);
        long wanted = (long) PULSES * PULSE_REQUEST;
        if (handedOut != wanted || inStock != DIAMONDS_IN_STOCK - wanted)
            throw new VisualTestException(PULSES + " rising edges handed " + handedOut + " diamonds out and left "
                    + inStock + " in stock, expected " + wanted + " and " + (DIAMONDS_IN_STOCK - wanted));
        WarehouseOutputBlockEntity port = portAt(level, layout, PULSE_PORT);
        if (port.exportedItems() != 0)
            throw new VisualTestException("a requesting port must never report items handed over, but it reports "
                    + port.exportedItems());
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK a pulse port is today's warehouse output: {} edges, {} diamonds, {} left in "
                + "stock", PULSES, handedOut, inStock);
    }

    private void raiseTheMaximumAndFeed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        keeper(level, context.origin()).editRule(RULE_IRON, StockKeeperRules.FIELD_MAXIMUM, null,
                (long) IRON_MAXIMUM_RAISED);
        startFeed(server, context, IRON, IRON_RESTOCKED);
    }

    private void assertRaisedMaximumStores(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long stored = controller.countOf(IRON_KEY);
        if (stored != IRON_TO_THE_MACHINE)
            throw new VisualTestException("the raised maximum stored " + stored + " iron, expected "
                    + IRON_TO_THE_MACHINE);
        if (portAt(level, layout, OVERFLOW_PORT).exportedItems() != IRON_OVERFLOWED)
            throw new VisualTestException("the general overflow took items although its lever is on");
        if (input(level, layout).bufferedItems().count(IRON_KEY) != 0)
            throw new VisualTestException("the input still holds iron although the maximum was raised");
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK raising the maximum stores what the overflow used to take: {} iron in the "
                + "racks", stored);
    }

    private void startTheMachine(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        ironBeforeTheMachine = controller(level, context.origin()).countOf(IRON_KEY);
        maxOpenRequests = 0;
        idlePolls = 0;
        setLever(server, context, MACHINE_PORT, true);
    }

    /**
     * One poll of the machine phase: the press must have made {@value #SHEETS_BEFORE_THE_SHOT} sheets, and on
     * <b>every</b> poll the machine port may have at most one open request — the promise of the "while powered"
     * behaviour, measured rather than believed.
     */
    private boolean machineRunning(MinecraftServer server, VisualContext context) {
        return measureSheetsAndRequests(server, context) >= SHEETS_BEFORE_THE_SHOT;
    }

    /**
     * The machine keeps being supplied until the racks are empty and nothing is on its way any more. Waiting for the
     * <b>whole</b> stock rather than for a chosen number of trips is what makes the closing assertion race-free: the
     * lever can only drop at a moment when no request can be open, because there is nothing left to ask for.
     */
    private boolean machineSupplied(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        measureSheetsAndRequests(server, context);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        return controller.countOf(IRON_KEY) == 0 && craneIdle(crane)
                && controller.requestsFor(layout.rackPos(MACHINE_PORT.position())).isEmpty();
    }

    /** Counts the sheets the press has made so far and records the highest number of open requests ever seen. */
    private int measureSheetsAndRequests(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        int open = controller(level, dock).requestsFor(layout.rackPos(MACHINE_PORT.position())).size();
        maxOpenRequests = Math.max(maxOpenRequests, open);
        if (open > 1)
            throw new VisualTestException("the machine port has " + open + " open requests at once; a continuous port "
                    + "must never promise more than one trip at a time");
        return sheets(level, layout);
    }

    private boolean pressFinished(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (crane == null)
            throw new VisualTestException("the dock of the aisle is missing");
        boolean quiet = craneIdle(crane) && sheets(level, layout) >= IRON_TO_THE_MACHINE
                && countAt(level, drainPos(layout, MACHINE_PORT), IRON) == 0
                && portAt(level, layout, MACHINE_PORT).bufferedItems().count(IRON_KEY) == 0;
        idlePolls = quiet ? idlePolls + 1 : 0;
        return idlePolls >= IDLE_POLLS;
    }

    private void assertMachineWasFed(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BranchLayout layout = layout(dock);
        WarehouseControllerBlockEntity controller = controller(level, dock);
        long handedToTheMachine = ironBeforeTheMachine - controller.countOf(IRON_KEY);
        int pressed = sheets(level, layout);
        if (handedToTheMachine != IRON_TO_THE_MACHINE || pressed != IRON_TO_THE_MACHINE)
            throw new VisualTestException("one held signal moved " + handedToTheMachine + " iron out of the racks and "
                    + "the press made " + pressed + " sheets, expected " + IRON_TO_THE_MACHINE + " of each");
        if (maxOpenRequests != 1)
            throw new VisualTestException("the machine phase saw at most " + maxOpenRequests + " open requests, "
                    + "expected exactly 1: without one the port never asked, with more it queued up");
        if (controller.requestsFor(layout.rackPos(MACHINE_PORT.position())).size() != 0)
            throw new VisualTestException("the machine port still waits for a delivery after its lever dropped");
        // The press converts one ingot into one sheet, which is the only identity change of the whole scene.
        add(IRON_KEY, -pressed);
        add(ItemKey.of(sheet()), pressed);
        assertConserved(level, dock);
        LOGGER.info(PREFIX + "ports: CHECK one held signal fed the machine trip by trip: {} iron pressed into {} "
                        + "sheets, never more than {} open request, and no clock anywhere", handedToTheMachine, pressed,
                maxOpenRequests);
    }

    /** Nothing was lost, duplicated or changed on the way: the whole scene holds exactly what was put into it. */
    private void assertConserved(ServerLevel level, BlockPos dock) {
        AABB box = sceneBounds(dock);
        if (!SceneItemCensus.isFullyLoaded(level, box))
            throw new VisualTestException("the census box of the scene is not fully loaded");
        Map<ItemKey, Long> actual = SceneItemCensus.take(level, box);
        if (!actual.equals(expected))
            throw new VisualTestException("item conservation violated: expected " + SceneItemCensus.describe(expected)
                    + " but the scene holds " + SceneItemCensus.describe(actual));
        LOGGER.info(PREFIX + "ports: CHECK every item accounted for: {}", SceneItemCensus.describe(actual));
    }

    private void add(ItemKey key, long delta) {
        long value = expected.getOrDefault(key, 0L) + delta;
        if (value == 0)
            expected.remove(key);
        else
            expected.put(key, value);
    }

    private static void assertReason(WarehouseControllerBlockEntity controller, NoJobReason wanted) {
        Optional<NoJobReason> reason = controller.lastPlanReason();
        if (reason.filter(wanted::equals).isEmpty())
            throw new VisualTestException("the controller stopped planning for the reason " + reason + ", expected "
                    + wanted);
    }

    // --- levers ------------------------------------------------------------------------------------------------------

    /**
     * Flips a port's lever. {@code Block.UPDATE_ALL} notifies the neighbours, which is what makes the port see the edge
     * and act on it.
     */
    private static void setLever(MinecraftServer server, VisualContext context, Port port, boolean powered) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        BlockPos pos = leverPos(layout, port);
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER))
            throw new VisualTestException("no lever on the port " + port.id() + " at " + pos + ", found " + state);
        level.setBlock(pos, state.setValue(LeverBlock.POWERED, powered), Block.UPDATE_ALL);
        boolean seen = level.getBlockState(layout.rackPos(port.position())).getValue(WarehouseOutputBlock.POWERED);
        if (seen != powered)
            throw new VisualTestException("the port " + port.id() + " reports powered=" + seen + " after its lever was "
                    + "set to " + powered);
    }

    /** A rising and a falling edge in one server step, which is what a button or a pulse clock gives a port. */
    private static void pulseTheLever(MinecraftServer server, VisualContext context, Port port) {
        setLever(server, context, port, true);
        setLever(server, context, port, false);
    }

    /** Phase 2: the lever the first row did without, placed and switched on. */
    private static void switchOverflowLever(MinecraftServer server, VisualContext context, boolean powered) {
        ServerLevel level = server.overworld();
        BranchLayout layout = layout(context.origin());
        BlockPos pos = leverPos(layout, OVERFLOW_PORT);
        if (!level.getBlockState(pos).is(Blocks.LEVER))
            placeLever(level, pos);
        setLever(server, context, OVERFLOW_PORT, powered);
    }

    // --- client ------------------------------------------------------------------------------------------------------

    /** The shots can only show what the client knows: every port must carry its direction and its rank there too. */
    private static boolean portsSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BranchLayout layout = layout(context.origin());
        for (Port port : PORTS) {
            BlockPos pos = layout.rackPos(port.position());
            if (!(level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity be))
                return false;
            if (be.portRank() != port.rank() || be.redstoneMode() != port.redstone())
                return false;
            if (level.getBlockState(pos).getOptionalValue(WarehouseOutputBlock.ACCEPTING).orElse(false)
                    != port.accepts())
                return false;
        }
        return true;
    }

    private static boolean overflowPowered(VisualContext context) {
        return clientPowered(context, OVERFLOW_PORT);
    }

    private static boolean diversionPowered(VisualContext context) {
        return clientPowered(context, DIVERSION_PORT);
    }

    private static boolean clientPowered(VisualContext context, Port port) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        return level.getBlockState(layout(context.origin()).rackPos(port.position()))
                .getOptionalValue(WarehouseOutputBlock.POWERED).orElse(false);
    }

    private static boolean craneParkedOnClient(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        return level != null && level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane
                && crane.craneState().phase() == CranePhase.IDLE;
    }

    /**
     * Puts the <b>client</b> crane in front of the filtered overflow, arm out and the surplus cobblestone in its head,
     * so one frozen frame shows the handover the server already made. The server state is untouched and the pose is put
     * back before the next shot.
     */
    private void showHandover(VisualContext context) {
        StackerCraneBlockEntity crane = clientCrane(context);
        parkedPose = crane.craneState().pose();
        RackPosition target = FILTERED_PORT.position();
        CranePose pose = CranePose.sanitized(target.x(), target.y(), DELIVERY_ARM, target.side());
        if (!crane.showClientPose(pose, CranePhase.EXTEND_TARGET,
                List.of(new KeyCount<>(COBBLE, (long) COBBLE_OVERFLOWED))))
            throw new VisualTestException("cannot show the crane pose at " + context.origin());
    }

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

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, "ports", range);
    }

    private static double vanillaReach() {
        return GoggleShots.vanillaReach();
    }

    private static boolean filteredPortSynced(VisualContext context) {
        return portSynced(context, FILTERED_PORT, COBBLE_OVERFLOWED);
    }

    private static boolean diversionPortSynced(VisualContext context) {
        return portSynced(context, DIVERSION_PORT, GOLD_DIVERTED + IRON_JAMMED);
    }

    private static boolean machinePortSynced(VisualContext context) {
        return portSynced(context, MACHINE_PORT, 0);
    }

    /**
     * A port's own numbers have reached the client: its policy travels in the block entity's packet, but the number of
     * items it handed over lives in the throttled goggle summary the server only sends once it has seen the player look
     * at the block, so waiting for it keeps the check about the values and not about the moment they arrive.
     */
    private static boolean portSynced(VisualContext context, Port port, long exported) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos pos = layout(context.origin()).rackPos(port.position());
        return level.getBlockEntity(pos) instanceof WarehouseOutputBlockEntity be && be.portRank() == port.rank()
                && be.redstoneMode() == port.redstone() && be.summary().exportedItems() == exported;
    }

    private static boolean controllerSynced(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null || !(level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller))
            return false;
        ControllerGoggleSummary summary = controller.summary();
        return summary.storageLocations() == STORAGE_X.size() && summary.outputs() == PORTS.size()
                && summary.acceptingPorts() == (int) PORTS.stream().filter(Port::accepts).count();
    }

    /** A screenshot cannot tell a right number from a wrong one, so the lines the overlay draws are read as well. */
    private static void checkFilteredGoggles(VisualContext context) {
        List<String> lines = goggleLines(context, FILTERED_PORT);
        GoggleShots.requireLine(lines, overflowLine(FILTERED_PORT));
        GoggleShots.requireLine(lines, acceptsLine(new ItemStack(COBBLE).getHoverName().getString()));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PORT_EXPORTED, COBBLE_OVERFLOWED));
        GoggleShots.requireLine(lines, redstoneLine(FILTERED_PORT));
        // Its lever is off and it acts unless powered, so this overflow is open right now.
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACTIVE).getString());
        LOGGER.info(PREFIX + "ports: CHECK the filtered overflow's goggles say {}", lines);
    }

    private static void checkDiversionGoggles(VisualContext context) {
        List<String> lines = goggleLines(context, DIVERSION_PORT);
        GoggleShots.requireLine(lines, WareworksLang.translate(WareworksLang.GOGGLES_PORT_DIVERSION,
                PortSettings.formatRank(DIVERSION_PORT.rank())).string());
        GoggleShots.requireLine(lines,
                acceptsLine(WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY).getString()));
        GoggleShots.requireLine(lines,
                GoggleShots.count(WareworksLang.GOGGLES_PORT_EXPORTED, GOLD_DIVERTED + IRON_JAMMED));
        GoggleShots.requireLine(lines, redstoneLine(DIVERSION_PORT));
        GoggleShots.requireLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_WAITING).getString());
        LOGGER.info(PREFIX + "ports: CHECK the diversion's goggles say {}", lines);
    }

    private static void checkMachineGoggles(VisualContext context) {
        List<String> lines = goggleLines(context, MACHINE_PORT);
        GoggleShots.requireLine(lines, redstoneLine(MACHINE_PORT));
        // A requesting port shows its request lines and never an accepting port's.
        GoggleShots.requireNoLine(lines, WareworksLang.translateDirect(WareworksLang.GOGGLES_PORT_ACCEPTS_ANY)
                .getString());
        GoggleShots.requireNoLine(lines, GoggleShots.count(WareworksLang.GOGGLES_PORT_EXPORTED, 0));
        LOGGER.info(PREFIX + "ports: CHECK the machine port's goggles say {}", lines);
    }

    private static void checkControllerGoggles(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        int accepting = (int) PORTS.stream().filter(Port::accepts).count();
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_ACCEPTING_PORTS, accepting));
        GoggleShots.requireLine(lines, GoggleShots.count(WareworksLang.GOGGLES_STORAGE_LOCATIONS, STORAGE_X.size()));
        LOGGER.info(PREFIX + "ports: CHECK the controller's goggles say {}", lines);
    }

    private static List<String> goggleLines(VisualContext context, Port port) {
        return GoggleShots.lines(context, layout(context.origin()).rackPos(port.position()));
    }

    private static String overflowLine(Port port) {
        return WareworksLang.translate(WareworksLang.GOGGLES_PORT_OVERFLOW, PortSettings.formatRank(port.rank()))
                .string();
    }

    private static String acceptsLine(String what) {
        return WareworksLang.translate(WareworksLang.GOGGLES_PORT_ACCEPTS, what).string();
    }

    private static String redstoneLine(Port port) {
        return WareworksLang.translate(WareworksLang.GOGGLES_PORT_REDSTONE,
                WareworksLang.translateDirect(port.redstone().langKey())).string();
    }

    // --- geometry ----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    /** Which way a station on {@code rack} faces to look into the aisle. */
    private static Direction inward(BranchLayout layout, RackPosition rack) {
        return layout.sideDirection(rack.side()).getOpposite();
    }

    /** The hopper that drains a port: directly below it, facing out of the rack wall. */
    private static BlockPos drainPos(BranchLayout layout, Port port) {
        return layout.rackPos(RackPosition.of(port.x(), 0, PORT_SIDE));
    }

    /** Position {@code step} of the lane behind a port, counted from the block the drain pushes into. */
    private static BlockPos lanePos(BranchLayout layout, Port port, int step) {
        return drainPos(layout, port).relative(layout.sideDirection(PORT_SIDE), step + 1);
    }

    /** Which way a lane behind the port row carries: away from the aisle. */
    private static Direction laneDirection(BranchLayout layout) {
        return layout.sideDirection(PORT_SIDE);
    }

    /**
     * The barrel a lane's belt funnel fills: beside the funnel, one step <b>along</b> the aisle, because that is the
     * block a belt funnel facing away from the aisle hands its items to ({@code InvManipulationBehaviour}:
     * {@code facing.getOpposite()}).
     * <p>
     * The far side on purpose: the near side of every lane is where the cameras of the ports before it stand, and a
     * barrel there is a block a flying player cannot fly through.
     */
    private static BlockPos laneBarrelPos(BranchLayout layout, Port port) {
        return lanePos(layout, port, port.laneLength() - 1).above().relative(AISLE);
    }

    /** The lever of a port: on top of it, orthogonally adjacent, clear of both the aisle opening and the back plate. */
    private static BlockPos leverPos(BranchLayout layout, Port port) {
        return layout.rackPos(port.position()).above();
    }

    /** The Mechanical Press: two blocks above the machine lane, which is where Create looks for a belt to press on. */
    private static BlockPos pressPos(BranchLayout layout) {
        return lanePos(layout, MACHINE_PORT, PRESS_OFFSET).above(2);
    }

    /** The feed belt's tail, {@value #FEED_BELT} blocks out along the warehouse's rack side. */
    private static BlockPos feedBeltStart(BranchLayout layout) {
        return layout.rackPos(INPUT).relative(layout.sideDirection(INPUT.side()), FEED_BELT);
    }

    /** The feed belt's last block, right in front of the warehouse input. */
    private static BlockPos feedBeltEnd(BranchLayout layout) {
        return layout.rackPos(INPUT).relative(layout.sideDirection(INPUT.side()));
    }

    private static BlockPos feedChestPos(BranchLayout layout) {
        return feedBeltStart(layout).above();
    }

    /** Which way the feed belt must carry: from its tail towards the warehouse input. */
    private static Direction feedDirection(BranchLayout layout) {
        return layout.sideDirection(INPUT.side()).getOpposite();
    }

    private static BlockPos clearFrom(BlockPos dock) {
        return dock.relative(AISLE.getOpposite(), CLEAR_ALONG)
                .relative(AISLE.getCounterClockWise(), OVERFLOW_BELT + 4);
    }

    private static BlockPos clearTo(BlockPos dock) {
        return dock.relative(AISLE, RAILS + CLEAR_ALONG).relative(AISLE.getClockWise(), FEED_BELT + 4)
                .above(CLEAR_HEIGHT);
    }

    private static AABB sceneBounds(BlockPos dock) {
        return AABB.encapsulatingFullBlocks(clearFrom(dock).below(), clearTo(dock));
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

    private static WarehouseStockKeeperBlockEntity keeper(ServerLevel level, BlockPos dock) {
        WarehouseStockKeeperBlockEntity keeper = WareworksBlockEntityTypes.WAREHOUSE_STOCK_KEEPER.getNullable(level,
                layout(dock).rackPos(KEEPER));
        if (keeper == null)
            throw new VisualTestException("the stock keeper of the aisle is missing");
        return keeper;
    }

    private static WarehouseInputBlockEntity input(ServerLevel level, BranchLayout layout) {
        WarehouseInputBlockEntity input = WareworksBlockEntityTypes.WAREHOUSE_INPUT.getNullable(level,
                layout.rackPos(INPUT));
        if (input == null)
            throw new VisualTestException("the warehouse input of the aisle is missing");
        return input;
    }

    private static WarehouseOutputBlockEntity portAt(ServerLevel level, BranchLayout layout, Port port) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout.rackPos(port.position()));
        if (be == null)
            throw new VisualTestException("the port " + port.id() + " is missing");
        return be;
    }

    private static RequestFilterBehaviour filterOf(WarehouseOutputBlockEntity port) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
        if (!(behaviour instanceof RequestFilterBehaviour filter))
            throw new VisualTestException("the port at " + port.getBlockPos() + " has no request filter behaviour");
        return filter;
    }

    /**
     * Everything of {@code item} that has left the warehouse through {@code port}: its own buffer, the hopper that
     * drains it and the whole lane behind it. A sum, because a hopper moves one item every eight ticks and an
     * assertion must not depend on how far it has got.
     */
    private static long itemsBehind(ServerLevel level, BranchLayout layout, Port port, Item item) {
        long total = portAt(level, layout, port).bufferedItems().count(ItemKey.of(item));
        total += countAt(level, drainPos(layout, port), item);
        for (BlockPos pos : receiverPositions(layout, port)) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null)
                total += countAt(level, pos, item);
        }
        return total;
    }

    /**
     * Every position behind a port that can hold an item: the barrel its drain pushes into, or all the belt segments of
     * its lane and the barrel the funnel at the end fills. A sum over all of them, because a hopper moves one item every
     * eight ticks and no assertion may depend on how far it has got.
     */
    private static List<BlockPos> receiverPositions(BranchLayout layout, Port port) {
        if (port.laneLength() == 0)
            return List.of(lanePos(layout, port, 0));
        List<BlockPos> positions = new ArrayList<>(port.laneLength() + 1);
        for (int step = 0; step < port.laneLength(); step++)
            positions.add(lanePos(layout, port, step));
        positions.add(laneBarrelPos(layout, port));
        return positions;
    }

    /** Iron sheets anywhere behind the machine port: on its belt, or queued up against the stop at the lane's end. */
    private static int sheets(ServerLevel level, BranchLayout layout) {
        return (int) itemsBehind(level, layout, MACHINE_PORT, sheet());
    }

    /**
     * Items of one type standing on the belt that starts at {@code segment}.
     * <p>
     * A belt's inventory lives on <b>one</b> of its segments, the controller ({@code BeltBlockEntity#isController}), so
     * asking a single segment's capability would silently answer 0 for a belt whose controller is the other end.
     */
    private static long beltItems(ServerLevel level, BlockPos segment) {
        BeltBlockEntity first = AllBlockEntityTypes.BELT.getNullable(level, segment);
        BeltBlockEntity belt = first == null ? null : first.getControllerBE();
        if (belt == null || belt.getInventory() == null)
            return 0L;
        long items = 0;
        for (TransportedItemStack transported : belt.getInventory().getTransportedItems())
            items += transported.stack.getCount();
        return items;
    }

    private static Direction beltMovement(ServerLevel level, BlockPos pos) {
        BeltBlockEntity belt = AllBlockEntityTypes.BELT.getNullable(level, pos);
        if (belt == null)
            throw new VisualTestException("no belt at " + pos);
        return belt.getMovementFacing();
    }

    private static void insertAll(ServerLevel level, BlockPos pos, ItemStack stack) {
        IItemHandler handler = handlerAt(level, pos);
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the inventory at " + pos + " refused " + rest);
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

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            throw new VisualTestException("no item handler at " + pos);
        return handler;
    }
}
