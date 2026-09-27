package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleChunkSpan;
import dev.wareworks.content.controller.AisleChunkTickets;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.controller.ChunkKeepReason;
import dev.wareworks.content.controller.ControllerGoggleSummary;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Scenario "chunks": optional chunk loading for aisles that have work (M19, issue #10, ADR-031) — <b>the same aisle run
 * twice</b>, once with the setting off and once with it on.
 * <p>
 * The whole point of the feature is what happens when <b>nobody is nearby</b>, and that is the one thing no GameTest can
 * see: a GameTest area is force-loaded by vanilla itself, so there a held chunk and an ordinary one look exactly alike.
 * So this scenario builds one aisle far outside the spawn chunks, gives it work <b>the warehouse drives itself</b> (a
 * collecting port whose machine is full of items, M18), teleports the player a thousand blocks away and asserts the
 * difference on the server:
 * <ol>
 * <li><b>off (the shipped default)</b> — the aisle unloads in the middle of a collection, holds <b>no</b> ticket at any
 * tick, makes no progress at all while the player is away (its port's own counter of collected items is unchanged
 * afterwards), and carries on when the player comes back. Exactly as before M19.</li>
 * <li><b>on</b> — the aisle holds its own footprint, and the <b>whole collection finishes while the player is a thousand
 * blocks away</b>: the dock's block entity is the same object throughout (its chunk never unloaded), the machine ends up
 * empty, the racks hold its items, and the hold never drops to zero in between. Then the idle aisle lets go, and
 * <b>only then</b> does the dock really become removed — which is the proof that the release was real. A zero in the
 * mod's own record would not have shown that the chunk was free again.</li>
 * </ol>
 * The second chapter covers the separate collect opt-in on its own, too: with the crane <b>unpowered</b> no job can be
 * planned ({@code StackerCraneBlockEntity#canAcceptJob}), so an aisle whose machine still has something pending is a
 * steady {@link ChunkKeepReason#COLLECTING} hold — the state that opt-in exists for, and one that can be photographed
 * instead of being caught between two trips. Powering the crane turns the same hold into
 * {@link ChunkKeepReason#CRANE_JOB} without ever letting go.
 * <p>
 * <b>The operator's view</b> is the other half of the run, because a chunk loader nobody can inspect is a chunk loader
 * nobody can trust: the controller's goggle line in all of its shapes (nothing at all while the feature is off, holding
 * for a waiting machine, holding for a crane job, refused because this aisle needs more chunks than the server allows,
 * and gave up), and {@code /wareworks chunks} with its rows, its totals and its {@code release} valve, run as a player
 * runs them and read back out of the chat. Every chapter ends by proving that <b>no ticket is left</b>: after the work
 * finishes, after the controller is broken, and after the setting is switched off.
 * <p>
 * <b>Asserted on every tick of every chapter</b> ({@link #leakProbe}): the union of the chunks the mod says it holds
 * equals the number of chunks NeoForge itself has force-loaded by block tickets in that level. No other mod holds block
 * tickets in this world, so the two numbers must be equal, and a ticket that outlived its owner is exactly a difference
 * between them (the per-owner raw count cannot be asked for: NeoForge's owner type is package-private, which is also why
 * {@code /wareworks chunks} exists). An {@link SceneItemCensus} around every item move proves that none of it created or
 * lost an item.
 * <p>
 * Needs a real player: five of its shots are goggle tooltips, which Create only draws for a non-spectator who looks at a
 * block within reach, so it runs with {@link VisualWorldProfile#playable} and sets that reach back to 0 for the world
 * shots ({@link GoggleShots}).
 */
public final class ChunkLoadingVisualScenario implements VisualScenario {
    public static final String NAME = "chunks";

    /** Its own throw-away world: a scenario with a player must not reuse the camera world of another one. */
    private static final String WORLD_FOLDER = "wareworks_visual_chunks";

    private static final Direction AISLE = Direction.EAST;
    /**
     * Build site, far outside the spawn chunks: the world spawn keeps a radius of about 11 chunks permanently loaded
     * ({@code TicketType.START}), so an aisle at spawn could never unload and the whole run would prove nothing.
     */
    private static final int BUILD_X = 512;
    private static final int BUILD_Z = 512;
    /** Where the player goes to leave the aisle behind: far beyond the view distance, but not so far that much is generated. */
    private static final int AWAY_X = 1500;
    private static final int AWAY_Z = 1500;
    private static final double FLIGHT_Y = 90.0;
    /** How far the player must really be while a chapter claims that nobody is near the aisle, in blocks. */
    private static final double MIN_AWAY_DISTANCE = 900.0;
    /** Any y inside the build height, for the "is the build site loaded" probe. */
    private static final int LOADED_PROBE_Y = 0;

    private static final int RAILS = 6;
    private static final int MOTOR_RPM = 128;
    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 9;
    /** Blocks added around the rack bounds for the census box, so a dropped item would be inside it. */
    private static final int CENSUS_MARGIN = 2;

    /** The collecting port, far enough from the dock that the crane really travels to it. */
    private static final RackPosition PORT = RackPosition.of(5, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 1;
    private static final int STORAGE_LAST_POSITION = 3;

    /** What the machine leaves in its output barrel; one item type, so every census line reads at a glance. */
    private static final ItemKey COLLECTED = ItemKey.of(Items.OAK_PLANKS);
    /** A stack is one trip of the handling head, so this is "three trips" — about twenty seconds of crane work. */
    private static final int THREE_TRIPS = 192;
    private static final int ONE_TRIP = 64;

    // --- the config values the chapters set (the shipped defaults are restored at the end) ---------------------------
    /** Aisles allowed to hold chunks in the level while a chapter has the feature on. */
    private static final int HOLD_AISLES = 2;
    /** Aisles allowed to hold chunks only because a collecting port has something pending. */
    private static final int COLLECT_HOLD_AISLES = 2;
    /** A per-aisle cap below this aisle's footprint, for the refusal chapter. */
    private static final int TOO_SMALL_CHUNK_CAP = 3;
    /** The folder a world's own server configs live in ({@code ServerLifecycleHooks}); a file there overrides the global one. */
    private static final LevelResource SERVER_CONFIG_DIRECTORY = new LevelResource("serverconfig");

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final int PORT_READY_TIMEOUT_TICKS = 400;
    private static final int SYNC_TIMEOUT_TICKS = 200;
    private static final int UNLOAD_TIMEOUT_TICKS = 2400;
    private static final int RELOAD_TIMEOUT_TICKS = 1200;
    private static final int HOLD_TIMEOUT_TICKS = 600;
    /** A whole collection, with the crane travelling the aisle once per stack. */
    private static final int COLLECT_TIMEOUT_TICKS = 3600;
    /** The release linger plus the controller's own bounded re-check, with room to spare. */
    private static final int RELEASE_TIMEOUT_TICKS = 1200;
    /** Ticks the player stays away while the chapter with the feature off proves that nothing happens. */
    private static final int AWAY_TICKS = 120;
    private static final int SETTLE_TICKS = 10;
    /** Ticks a state that waits for no event must be given before it is asserted (the bounded re-check is 20). */
    private static final int RECHECK_SLACK_TICKS = 60;
    /**
     * The run has two chunk round trips and six chapters of real crane work, so it needs more than the default budget.
     * It stays below ten minutes, so the watchdog always fires before any tool timeout around the Gradle task.
     */
    private static final long RUN_TIMEOUT_MILLIS = 9L * 60L * 1000L;

    /** Overview from the right side, over the port's rack row. */
    private static final CameraView SIDE = CameraView.of("side", 4.5, 9.0, 8.0, 4.5, 1.0, 0.5);
    /** From beyond the aisle end, along the rails back to the dock. */
    private static final CameraView ALONG = CameraView.of("aisle", 10.5, 3.0, 0.5, 1.0, 1.5, 0.5);
    /** From the far side of the port, where its machine stands: the barrel, the port and the rack row behind them. */
    private static final CameraView AT_MACHINE = CameraView.of("machine", 9.0, 3.5, 5.5, 5.5, 0.8, 2.2);
    /**
     * The camera of the two shots that are about the <b>chat</b>. It rests the crosshair on the machine's barrel, which
     * has no goggle information of its own, so Create draws no tooltip across the command's answer — the goggle overlay
     * is a GUI layer, and these are the only shots of this run that show the GUI without being about a tooltip.
     */
    private static final CameraView AT_CHAT = CameraView.of("chat", 5.5, 4.5, 8.0, 5.5, 0.6, 2.5);
    /**
     * Goggle shot of the controller, west of it and aimed at the <b>lower left corner</b> of its back face rather than at
     * its middle: the controller's aisle letter is a scroll value box on every face but the dock's, and a value box the
     * crosshair really hits makes Create's goggle overlay bail out before it draws a line ({@link GoggleShots}).
     */
    private static final CameraView AT_CONTROLLER = CameraView.of("at-controller", -3.6, 1.9, 0.4, -1.0, 0.25, 0.25);

    /** The box every item census of this run counts, set once the scene origin is known. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** What the scene must hold, item for item; the items only move inside the box, they are never transformed. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** One line of what the run is doing, for the shot index. */
    private volatile String chapter = "not started";
    /**
     * The dock's block entity before the player leaves. A chunk that really unloads marks it removed and builds a new
     * instance from the save when it loads again — which is what tells the two chapters apart: with the feature off the
     * instance must be a new one afterwards, with it on it must be the very same object.
     */
    private volatile StackerCraneBlockEntity dockBeforeAway;
    /** The port's block entity before the player leaves, looked up again afterwards to see whether anything happened. */
    private volatile WarehouseOutputBlockEntity portBeforeAway;
    /** What the port had collected when the player left (the crane goes on working for the few ticks until the unload). */
    private volatile long collectedWhenLeaving;
    /**
     * What the port had collected when its chunk really went away, read from the block entity the unload left behind:
     * that object is the one that was ticking, so its fields are exactly what the save was written from.
     */
    private volatile long collectedAtUnload;
    /**
     * Game tick the away wait runs until. A real game time rather than a count of polls: a server condition of the
     * harness needs two client ticks per evaluation ({@code VisualScript#serverUntil} submits and then joins), so a
     * counter would need twice the ticks the step is given.
     */
    private volatile long awayUntilTick;
    /** The smallest hold seen while the player was away; 0 would mean the aisle let go with work left to do. */
    private volatile int minHoldWhileAway = Integer.MAX_VALUE;
    /** What the broken controller held, and what the level must force-load once those tickets are gone. */
    private volatile int heldBeforeBreak;
    private volatile int rawAfterBreak;
    /** What the aisle held, had collected and still had in its machine when the world was saved for the restart. */
    private volatile int heldBeforeRestart;
    private volatile long collectedBeforeRestart;
    private volatile long inMachineBeforeRestart;
    /** Every reason the aisle reported while a chapter watched it, for the log and the assertions (server thread only). */
    private final Set<ChunkKeepReason> reasonsSeen = EnumSet.noneOf(ChunkKeepReason.class);
    /** System chat messages, filled by the client thread from NeoForge's chat event ({@link #installChatListener}). */
    private final List<String> chatLines = new CopyOnWriteArrayList<>();
    private boolean listening;

    @Override
    public String name() {
        return NAME;
    }

    /** A throw-away world with a real player: the goggle shots need a non-spectator with the vanilla reach. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("chunks: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "chunks run"))
                .client("chunks: listen for the server's chat answers", context -> installChatListener())
                .server("chunks: move the player to the build site", ChunkLoadingVisualScenario::moveToBuildSite)
                .serverUntil("chunks: wait until the build site is loaded",
                        (server, context) -> server.overworld().isLoaded(new BlockPos(BUILD_X, LOADED_PROBE_Y, BUILD_Z)),
                        RELOAD_TIMEOUT_TICKS)
                .server("chunks: clear the area and place the creative motor", this::placeMotor)
                .server("chunks: build dock, rails, controller, the rack wall and the port with its machine",
                        ChunkLoadingVisualScenario::buildAisle)
                .serverUntil("chunks: wait until the controller is ready with every member",
                        ChunkLoadingVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                // A creative player standing on the ground switches flying off again at once (LocalPlayer#aiStep), so it
                // is lifted into the air first and only then made to fly.
                .server("chunks: lift the player into the air", ChunkLoadingVisualScenario::liftPlayer)
                .waitTicks(SETTLE_TICKS)
                .server("chunks: the player flies and wears Engineer's Goggles",
                        ChunkLoadingVisualScenario::equipPlayer)
                .server("chunks: turn the port around with the collect row of its board",
                        ChunkLoadingVisualScenario::makeItCollect)
                .serverUntil("chunks: wait until the controller has read the port and its machine",
                        ChunkLoadingVisualScenario::portReady, PORT_READY_TIMEOUT_TICKS)
                .server("chunks: check the shipped default before anything is switched on", this::assertShippedDefault)
                .server("chunks: count every item of the scene once", this::baselineCensus)
                .server("chunks: creative motor to " + MOTOR_RPM + " RPM", ChunkLoadingVisualScenario::powerOn);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        // Create draws the value box of whatever the crosshair targets even with the GUI hidden, and the port has two of
        // them, so the world shots are taken by a player who reaches nothing.
        reach(script, 0.0);
        fly(script);
        for (CameraView view : List.of(SIDE, ALONG, AT_MACHINE))
            script.shotFrom(view, "scene");
        if (pass != VisualPass.FLYWHEEL)
            return;

        offChapter(script);
        holdChapter(script);
        restartChapter(script);
        refusedChapter(script);
        operatorChapter(script);
        brokenChapter(script);
        switchedOffChapter(script);

        script.server("chunks: nothing is held anywhere any more", this::assertNothingHeldAtAll)
                .client("chunks: every check passed",
                        context -> LOGGER.info(PREFIX + "chunks: ALL CHECKS PASSED (the aisle paused while the setting "
                                + "was off, finished a whole collection with nobody within " + (int) MIN_AWAY_DISTANCE
                                + " blocks while it was on, carried a hold through a real save, quit and rejoin, showed "
                                + "every goggle line, answered /wareworks chunks, and left no ticket after the work, "
                                + "the broken controller or the switched-off setting)"));
    }

    @Override
    public String status(VisualContext context) {
        StringBuilder status = new StringBuilder();
        clientCrane(context).ifPresent(crane -> status.append(String.format(Locale.ROOT,
                "posX=%.2f posY=%.2f arm=%.2f phase=%s held=%d", crane.craneState().pose().x(),
                crane.craneState().pose().y(), crane.craneState().pose().arm(), crane.craneState().phase(),
                crane.goggleInfo().heldCount())));
        clientSummary(context).ifPresent(summary -> status.append(" chunkKeep=")
                .append(summary.chunkKeepReason().name()).append('/').append(summary.chunkKeepChunks()));
        return status + " chapter=" + chapter + GoggleShots.describeHover(context);
    }

    // --- chapter 1: the setting off, which is what a server gets today ------------------------------------------------

    /**
     * With chunk loading off — the shipped default — the aisle behaves exactly as it did before M19: it unloads in the
     * middle of a collection, holds no ticket at any tick, makes <b>no progress at all</b> while the player is away, and
     * carries on when the player comes back.
     * <p>
     * That nothing happened is proven by the port's own counter of collected items, read from the block entity the save
     * produced afterwards and compared with the value the one before the unload had. That the aisle really went away is
     * proven by the dock's block entity <b>identity</b>: {@code isLoaded} can flip while the object is still the same
     * live one waiting in the unload queue, and then the chapter would prove nothing.
     */
    private void offChapter(VisualScript script) {
        script.server("chunks: chapter 1 - the setting off", (server, context) -> chapter("off: the aisle pauses"))
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the crane carries what it fetched", this::craneCarries,
                        COLLECT_TIMEOUT_TICKS);
        // The goggle line a default server must never see: the feature is off, so the controller says nothing about
        // chunks at all, although this aisle is busy.
        goggles(script, "goggles-off", summaryHas(ChunkKeepReason.NONE), this::checkNoChunkLine);
        script.server("chunks: remember what the aisle looked like before the player left", this::rememberBeforeAway)
                .server("chunks: the player flies a thousand blocks away", ChunkLoadingVisualScenario::moveAway)
                .serverUntil("chunks: wait until the aisle chunks really unload", this::dockUnloadedAndNothingHeld,
                        UNLOAD_TIMEOUT_TICKS)
                .server("chunks: the aisle went away in the middle of its work", this::assertLeftUnfinished)
                .server("chunks: note when the player left",
                        (server, context) -> awayUntilTick = server.overworld().getGameTime() + AWAY_TICKS)
                // The content of this wait is its condition: for every one of these ticks the aisle's chunk is not
                // loaded and the mod holds nothing, which is why nothing there can have run.
                .serverUntil("chunks: stay away for " + AWAY_TICKS + " ticks, with the aisle unloaded and holding "
                        + "nothing on every tick", (server, context) -> {
                            leakProbe(server, context);
                            requireNothingHeld(server, context, "while the setting is off");
                            if (server.overworld().isLoaded(context.origin()))
                                throw new VisualTestException("the aisle's chunk is loaded again although nobody is near "
                                        + "it and nothing holds a ticket for it");
                            return server.overworld().getGameTime() >= awayUntilTick;
                        }, AWAY_TICKS + RECHECK_SLACK_TICKS)
                .server("chunks: the player comes back", ChunkLoadingVisualScenario::moveToBuildSite)
                .serverUntil("chunks: wait until the aisle is loaded again", this::sceneLoaded, RELOAD_TIMEOUT_TICKS)
                .server("chunks: check that nothing at all happened while the player was away", this::assertNoProgress)
                .server("chunks: census after the aisle was away", (server, context) -> census(server,
                        "a collection interrupted by a chunk unload while the setting was off"))
                .serverUntil("chunks: wait until the resumed collection finished", this::machineCollected,
                        COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the resumed collection",
                        (server, context) -> census(server, "the collection resumed when the player came back"));
    }

    // --- chapter 2: the setting on, and the work finishes with nobody near --------------------------------------------

    /**
     * With chunk loading on, the same aisle keeps its own footprint loaded and <b>finishes the whole collection while the
     * player is a thousand blocks away</b>, then lets go once it is idle.
     * <p>
     * It starts with the crane unpowered on purpose (see the class comment): that makes
     * {@link ChunkKeepReason#COLLECTING} a steady state rather than a moment between two trips, so the separate collect
     * opt-in can be asserted and photographed. While the player is away, <b>every tick</b> asserts that the hold never
     * drops to zero, that the dock is still the same block entity, that the player really is far away, and that NeoForge
     * holds exactly the chunks the mod says it does.
     */
    private void holdChapter(VisualScript script) {
        script.server("chunks: chapter 2 - the setting on", (server, context) -> {
                    chapter("on: the work finishes while nobody is near");
                    reasonsSeen.clear();
                    minHoldWhileAway = Integer.MAX_VALUE;
                    setChunkLoading(HOLD_AISLES, COLLECT_HOLD_AISLES);
                })
                .server("chunks: unpower the crane, so no job can be planned", ChunkLoadingVisualScenario::powerOff)
                .serverUntil("chunks: wait until the crane is idle and unpowered", this::craneIdleAndEmpty,
                        COLLECT_TIMEOUT_TICKS)
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the aisle holds its chunks for the waiting machine alone",
                        this::holdingForCollecting, HOLD_TIMEOUT_TICKS)
                .server("chunks: check that the held set is exactly the aisle's footprint", this::assertFootprintHeld);
        goggles(script, "goggles-collecting", summaryHas(ChunkKeepReason.COLLECTING),
                context -> checkChunkLine(context, ChunkKeepReason.COLLECTING));
        script.server("chunks: creative motor to " + MOTOR_RPM + " RPM again", ChunkLoadingVisualScenario::powerOn)
                .serverUntil("chunks: wait until the crane has a job, with the hold never letting go",
                        (server, context) -> {
                            leakProbe(server, context);
                            requireHolding(server, context, "while the crane starts its job");
                            reasonsSeen.add(controller(server, context).chunkKeepReason());
                            return controller(server, context).chunkKeepReason() == ChunkKeepReason.CRANE_JOB;
                        }, HOLD_TIMEOUT_TICKS);
        goggles(script, "goggles-holding", summaryHas(ChunkKeepReason.CRANE_JOB),
                context -> checkChunkLine(context, ChunkKeepReason.CRANE_JOB));
        script.server("chunks: remember what the aisle looked like before the player left", this::rememberBeforeAway)
                .server("chunks: the player flies a thousand blocks away", ChunkLoadingVisualScenario::moveAway)
                .serverUntil("chunks: wait until the whole machine is collected with nobody near the aisle",
                        (server, context) -> {
                            guardWhileHolding(server, context);
                            return machineCollected(server, context);
                        }, COLLECT_TIMEOUT_TICKS)
                .server("chunks: check that the aisle did all of that without ever being unloaded",
                        this::assertWorkedWhileAway)
                .server("chunks: census after a collection that ran with nobody nearby", (server, context) -> census(
                        server, "a whole collection that finished while the player was a thousand blocks away"))
                .serverUntil("chunks: wait until the idle aisle lets its chunks go", (server, context) -> {
                    leakProbe(server, context);
                    reasonsSeen.add(controller(server, context).chunkKeepReason());
                    return heldChunks(server, context) == 0;
                }, RELEASE_TIMEOUT_TICKS)
                .server("chunks: check how the aisle let go", this::assertReleasedWhenIdle)
                .serverUntil("chunks: wait until the dock really unloads after the release",
                        this::dockUnloadedAndNothingHeld, UNLOAD_TIMEOUT_TICKS)
                .server("chunks: the player comes back", ChunkLoadingVisualScenario::moveToBuildSite)
                .serverUntil("chunks: wait until the aisle is loaded again", this::sceneLoaded, RELOAD_TIMEOUT_TICKS)
                .server("chunks: census after the hold chapter",
                        (server, context) -> census(server, "the aisle held and released its own chunks"));
    }

    // --- chapter 3: a real restart while the aisle holds ---------------------------------------------------------------

    /**
     * The hold and the work it exists for survive a <b>real</b> save, quit to the title screen and rejoin — the one
     * thing that must never turn a ticket into a leak, and the one path that only a restart can reach: NeoForge's own
     * {@code LoadingValidationCallback}, which hands the saved tickets back before a single chunk of ours is loaded.
     * <p>
     * The player is a thousand blocks away <b>before</b> the save and stays there, so the aisle that comes back is
     * loaded by nothing but its own reinstated ticket: the seed chunk loads its controller, the controller claims it and
     * grows the hold back to the whole footprint, and the interrupted collection finishes with nobody near it.
     * <p>
     * A server config is loaded <b>per world</b>, so an in-memory override does not survive the rejoin. The chapter
     * therefore writes a real config file into the world's own {@code serverconfig} folder, which NeoForge documents as
     * the per-world override of {@code <instance>/config/wareworks-server.toml} — a restart that reads its setting from
     * a file is the only honest version of this test, and the file lives inside the throw-away world, so nothing outside
     * it is touched.
     */
    private void restartChapter(VisualScript script) {
        script.server("chunks: chapter 3 - a real save, quit and rejoin while the aisle holds", (server, context) -> {
                    chapter("restart: the hold survives a save, quit and rejoin");
                    setChunkLoading(HOLD_AISLES, 0);
                    writeWorldConfigOverride(server, HOLD_AISLES);
                })
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the aisle holds its chunks for a crane job", this::holdingForCraneJob,
                        HOLD_TIMEOUT_TICKS)
                .server("chunks: the player flies a thousand blocks away", ChunkLoadingVisualScenario::moveAway)
                .server("chunks: save the world while the aisle holds and works", this::saveWhileHolding);
        VisualWorld.reload(script, worldProfile());
        script.server("chunks: check that the rejoined world really has chunk loading on",
                        this::assertConfigAfterRestart)
                .serverUntil("chunks: wait until the reinstated ticket brought the aisle back with nobody near it",
                        this::aisleBackAfterRestart, RELOAD_TIMEOUT_TICKS)
                .server("chunks: check what the restart reinstated", this::assertRestartedHold);
        // The operator's view of a hold that survived a restart, shot from where the player is: a thousand blocks of
        // empty grass, and a chat that says the warehouse is holding four chunks somewhere else entirely.
        runCommand(script, "wareworks chunks", context -> "wareworks chunks", 4);
        script.client("chunks: check the listing of the hold that survived the restart", this::checkListing)
                .shotWithGui("operator-after-restart")
                .serverUntil("chunks: wait until the interrupted collection finished, with nobody near the aisle",
                        (server, context) -> {
                            guardWhileHolding(server, context);
                            return machineCollected(server, context);
                        }, COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the restart", (server, context) -> census(server,
                        "a collection that survived a save, quit and rejoin and finished with nobody near the aisle"))
                .serverUntil("chunks: wait until the idle aisle lets go after the restart", (server, context) -> {
                    leakProbe(server, context);
                    return heldChunks(server, context) == 0;
                }, RELEASE_TIMEOUT_TICKS)
                .server("chunks: the player comes back", ChunkLoadingVisualScenario::moveToBuildSite)
                .serverUntil("chunks: wait until the aisle is loaded again", this::sceneLoaded, RELOAD_TIMEOUT_TICKS)
                .server("chunks: census after the restart chapter", (server, context) -> census(server,
                        "the aisle held, restarted, finished and let go"));
    }

    // --- chapter 4: a cap that refuses, and an aisle that works anyway ------------------------------------------------

    /**
     * An aisle whose footprint needs more chunks than {@code chunkLoading.maxChunksPerAisle} allows holds <b>nothing at
     * all</b> — never a partial hold — says so through the goggles with both numbers, and does its work exactly as it did
     * before M19. The crane is unpowered while the refusal is photographed, so the state is a steady one.
     */
    private void refusedChapter(VisualScript script) {
        script.server("chunks: chapter 3 - a per-aisle cap below this aisle's footprint", (server, context) -> {
                    chapter("refused: the aisle needs more chunks than allowed");
                    setChunkLoading(HOLD_AISLES, COLLECT_HOLD_AISLES);
                    setConfig(WareworksConfig.SERVER.maxChunksPerAisle, TOO_SMALL_CHUNK_CAP);
                })
                .server("chunks: unpower the crane", ChunkLoadingVisualScenario::powerOff)
                .serverUntil("chunks: wait until the crane is idle and unpowered", this::craneIdleAndEmpty,
                        COLLECT_TIMEOUT_TICKS)
                .server("chunks: fill the machine with " + ONE_TRIP + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, ONE_TRIP))
                .serverUntil("chunks: wait until the aisle reports that it may not hold its footprint",
                        (server, context) -> {
                            leakProbe(server, context);
                            requireNothingHeld(server, context, "over the per-aisle cap");
                            return controller(server, context).chunkKeepReason() == ChunkKeepReason.TOO_MANY_CHUNKS;
                        }, HOLD_TIMEOUT_TICKS);
        goggles(script, "goggles-refused", summaryHas(ChunkKeepReason.TOO_MANY_CHUNKS), this::checkRefusedChunkLine);
        script.server("chunks: creative motor to " + MOTOR_RPM + " RPM again", ChunkLoadingVisualScenario::powerOn)
                .serverUntil("chunks: wait until the refused aisle collected the machine anyway", (server, context) -> {
                    leakProbe(server, context);
                    requireNothingHeld(server, context, "while it is refused");
                    return machineCollected(server, context);
                }, COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the refused aisle did its work", (server, context) -> census(server,
                        "an aisle that may not hold chunks works exactly as it did before M19"))
                .server("chunks: give the aisle its footprint back", (server, context) -> setConfig(
                        WareworksConfig.SERVER.maxChunksPerAisle,
                        WareworksConfig.SERVER.maxChunksPerAisle.getDefault()));
    }

    // --- chapter 4: the operator's view ------------------------------------------------------------------------------

    /**
     * {@code /wareworks chunks} and its emergency valve, run as a player runs them and read back out of the chat.
     * <p>
     * This command is not a convenience: {@code /forceload query} cannot see a mod's tickets, and NeoForge's own owner
     * type is package-private, so this listing is the only way an operator can find what Wareworks holds. The line with
     * the dimension's <b>block-ticket</b> count next to its whole force-loaded count is the leak check in the player's own
     * hands — in this world nothing else force-loads anything, so both numbers must be the total above them.
     * <p>
     * The crane is unpowered again for the release: a frozen job keeps the aisle's work (and therefore its work
     * fingerprint) unchanged, so "it gave up and does not take again" is a steady state instead of a race against the
     * next job, which would re-arm the hold by design.
     */
    private void operatorChapter(VisualScript script) {
        script.server("chunks: chapter 4 - the operator's view",
                        (server, context) -> chapter("operator: /wareworks chunks"))
                .server("chunks: only crane jobs may hold from here on",
                        (server, context) -> setChunkLoading(HOLD_AISLES, 0))
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the aisle holds its chunks for a crane job", this::holdingForCraneJob,
                        HOLD_TIMEOUT_TICKS)
                .server("chunks: freeze the job by unpowering the crane", ChunkLoadingVisualScenario::powerOff)
                .camera(AT_CHAT);
        runCommand(script, "wareworks chunks", context -> "wareworks chunks", 4);
        script.client("chunks: check the listing the server answered", this::checkListing)
                .shotWithGui("operator-chunks");
        runCommand(script, "wareworks chunks release <controller>",
                context -> "wareworks chunks release " + format(controllerPos(context.origin())), 1);
        script.client("chunks: check the answer of the release command", this::checkReleaseAnswer)
                .shotWithGui("operator-released")
                .server("chunks: the emergency valve let everything go", this::assertReleasedByCommand)
                .waitTicks(RECHECK_SLACK_TICKS)
                .server("chunks: and the aisle does not take it straight back", this::assertStillGivenUp);
        goggles(script, "goggles-gave-up", summaryHas(ChunkKeepReason.GAVE_UP),
                context -> checkChunkNoneLine(context, ChunkKeepReason.GAVE_UP));
        script.server("chunks: creative motor to " + MOTOR_RPM + " RPM again", ChunkLoadingVisualScenario::powerOn)
                .serverUntil("chunks: wait until the aisle collected the machine", this::machineCollected,
                        COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the operator released the hold", (server, context) -> census(server,
                        "an aisle whose hold an operator released finishes its work anyway"));
    }

    // --- chapter 5: the controller is broken while it holds ----------------------------------------------------------

    /** A ticket that outlives its owner is the one defect this feature must not have: the controller is broken mid hold. */
    private void brokenChapter(VisualScript script) {
        script.server("chunks: chapter 5 - the controller broken while it holds",
                        (server, context) -> chapter("broken: the controller is removed mid hold"))
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the aisle holds its chunks for the new work", this::holdingAnything,
                        HOLD_TIMEOUT_TICKS)
                .server("chunks: break the controller while it holds its chunks", this::breakControllerWhileHolding)
                .server("chunks: the tickets went with their owner, in the same tick", this::assertReleasedWithOwner)
                .camera(AT_CHAT);
        runCommand(script, "wareworks chunks", context -> "wareworks chunks", 1);
        script.client("chunks: check that the listing names nothing at all", this::checkEmptyListing)
                .shotWithGui("operator-none")
                .serverUntil("chunks: wait until the crane finished its job without a controller",
                        this::craneIdleAndEmpty, COLLECT_TIMEOUT_TICKS)
                .server("chunks: place a controller again", ChunkLoadingVisualScenario::placeController)
                .serverUntil("chunks: wait until the new controller adopted the aisle",
                        ChunkLoadingVisualScenario::sceneReady, SCENE_READY_TIMEOUT_TICKS)
                .serverUntil("chunks: wait until it has read the port and its machine again",
                        ChunkLoadingVisualScenario::portReady, PORT_READY_TIMEOUT_TICKS)
                .serverUntil("chunks: wait until the new controller collected the rest of the machine",
                        this::machineCollected, COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the controller was broken mid hold", (server, context) -> census(server,
                        "the controller broken while it held its chunks"));
    }

    // --- chapter 6: the setting is switched off while an aisle holds --------------------------------------------------

    /**
     * Switching the setting off releases what is held although the work is still there, and nothing comes back. The run
     * then ends the way a default server starts: the feature off, nothing held anywhere, and the aisle finishing its work
     * by itself.
     */
    private void switchedOffChapter(VisualScript script) {
        script.server("chunks: chapter 6 - the setting switched off mid hold",
                        (server, context) -> chapter("off again: the setting is switched off mid hold"))
                .server("chunks: fill the machine with " + THREE_TRIPS + " " + COLLECTED,
                        (server, context) -> fillMachine(server, context, THREE_TRIPS))
                .serverUntil("chunks: wait until the aisle holds its chunks once more", this::holdingAnything,
                        HOLD_TIMEOUT_TICKS)
                .server("chunks: switch chunk loading off while the aisle holds and works",
                        (server, context) -> setChunkLoading(0, 0))
                .serverUntil("chunks: wait until the aisle let everything go", (server, context) -> {
                    leakProbe(server, context);
                    return heldChunks(server, context) == 0;
                }, RECHECK_SLACK_TICKS + SETTLE_TICKS)
                .server("chunks: check that it let go although its work is unchanged", this::assertReleasedByConfig)
                .waitTicks(RECHECK_SLACK_TICKS)
                .server("chunks: and nothing came back", (server, context) -> {
                    leakProbe(server, context);
                    requireNothingHeld(server, context, "after the setting was switched off");
                })
                .serverUntil("chunks: wait until the aisle collected the machine with the setting off",
                        this::machineCollected, COLLECT_TIMEOUT_TICKS)
                .server("chunks: census after the setting was switched off", (server, context) -> census(server,
                        "the setting switched off mid hold, and the work finished anyway"));
    }

    // --- build (server thread) ---------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = new BlockPos(BUILD_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, BUILD_X, BUILD_Z), BUILD_Z);
        context.setOrigin(dock);
        censusBox = layout(dock).bounds().inflate(CENSUS_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
        LOGGER.info(PREFIX + "chunks: the aisle is built at {}, its footprint is {} chunk(s)", dock,
                footprintSize(dock));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        motor(level, dock).generatedSpeed.setValue(0);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        placeController(server, context);

        AisleLayout layout = layout(dock);
        Direction storageOutward = layout.sideDirection(Side.LEFT);
        for (int x = STORAGE_FIRST_POSITION; x <= STORAGE_LAST_POSITION; x++) {
            BlockPos rack = layout.rackPos(RackPosition.of(x, 0, Side.LEFT));
            level.setBlockAndUpdate(rack.relative(storageOutward), Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, storageOutward.getOpposite()));
            level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                    .setValue(WarehouseInterfaceBlock.FACING, storageOutward));
        }
        // The port faces the aisle like every station, and its machine's output barrel stands right behind it - lateral
        // 2, which is exactly why AisleChunkSpan inflates the aisle box by one block on every horizontal side.
        Direction portOutward = layout.sideDirection(PORT.side());
        BlockPos portPos = layout.rackPos(PORT);
        level.setBlockAndUpdate(portPos, WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, portOutward.getOpposite()));
        level.setBlockAndUpdate(portPos.relative(portOutward), Blocks.BARREL.defaultBlockState());
    }

    private static void placeController(MinecraftServer server, VisualContext context) {
        server.overworld().setBlockAndUpdate(controllerPos(context.origin()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
    }

    private static boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                controllerPos(dock));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        int expectedStorage = STORAGE_LAST_POSITION - STORAGE_FIRST_POSITION + 1;
        return controller != null && crane != null && controller.status() == ControllerStatus.READY
                && !controller.isMembershipDirty() && controller.pendingSnapshotCount() == 0
                && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && crane.aisleLength() == RAILS;
    }

    /** The wrench board's fourth row, as a player sets it: the direction is the rank, and nothing else. */
    private static void makeItCollect(MinecraftServer server, VisualContext context) {
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        if (!port.setPortRank(PortSettings.COLLECT_RANK))
            throw new VisualTestException("the port did not take the collect direction");
        port.setRedstoneMode(PortRedstone.UNLESS_POWERED);
        if (port.portDirection() != PortDirection.COLLECT)
            throw new VisualTestException("the port does not collect: " + port.portSettings());
        BlockState state = server.overworld().getBlockState(layout(context.origin()).rackPos(PORT));
        if (!state.getValue(WarehouseOutputBlock.COLLECTING))
            throw new VisualTestException("the block does not show the collect direction: " + state);
    }

    /** Whether the controller has really <b>read</b> the port and the inventory behind it (an unread port collects nothing). */
    private static boolean portReady(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = controller(server, context);
        return controller.collectingPortCount() == 1
                && !controller.collectsFromOwnStorage(layout(context.origin()).rackPos(PORT));
    }

    private static void powerOn(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(MOTOR_RPM);
    }

    private static void powerOff(MinecraftServer server, VisualContext context) {
        motor(server.overworld(), context.origin()).generatedSpeed.setValue(0);
    }

    // --- items and census --------------------------------------------------------------------------------------------

    /** Fills the machine's output barrel through its item capability, exactly as a machine's own output would. */
    private void fillMachine(MinecraftServer server, VisualContext context, int amount) {
        ServerLevel level = server.overworld();
        BlockPos barrel = machinePos(context.origin());
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, barrel, null);
        if (handler == null)
            throw new VisualTestException("the machine's output barrel at " + barrel + " has no item handler");
        ItemStack rest = ItemHandlerHelper.insertItem(handler, COLLECTED.toStack(amount), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the machine's output barrel refused " + rest);
        expected = SceneItemCensus.plus(expected, COLLECTED, amount);
    }

    /** The scene starts empty, and every later census is this one plus exactly what was put into the machine. */
    private void baselineCensus(MinecraftServer server, VisualContext context) {
        expected = SceneItemCensus.take(server.overworld(), censusBox);
        if (!expected.isEmpty())
            throw new VisualTestException("the scene must start without a single item, but it holds "
                    + SceneItemCensus.describe(expected));
        LOGGER.info(PREFIX + "chunks: baseline census is empty in {}", censusBox);
    }

    private void census(MinecraftServer server, String step) {
        ServerLevel level = server.overworld();
        if (!SceneItemCensus.isFullyLoaded(level, censusBox))
            throw new VisualTestException("the census box is not fully loaded, so a census would miss inventories");
        SceneItemCensus.assertEquals(level, censusBox, expected, step);
        LOGGER.info(PREFIX + "chunks PASS: {} (items {})", step, SceneItemCensus.describe(expected));
    }

    private void chapter(String name) {
        chapter = name;
        LOGGER.info(PREFIX + "chunks: --- {} ---", name);
    }

    // --- the state of the aisle (server thread) ----------------------------------------------------------------------

    private boolean craneCarries(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        return crane(server, context).heldItems().totalCount() > 0;
    }

    private boolean craneIdleAndEmpty(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        StackerCraneBlockEntity crane = crane(server, context);
        return crane.craneState().phase() == CranePhase.IDLE && crane.currentJob().isEmpty()
                && crane.heldItems().isEmpty();
    }

    /** Everything is out of the machine and in the racks, and the crane is idle with an empty head. */
    private boolean machineCollected(MinecraftServer server, VisualContext context) {
        return craneIdleAndEmpty(server, context) && countIn(server.overworld(), machinePos(context.origin())) == 0;
    }

    private boolean holdingAnything(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        return heldChunks(server, context) > 0;
    }

    /** Holding because the crane has a job — the reason a default server with chunk loading on sees most of the time. */
    private boolean holdingForCraneJob(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        return heldChunks(server, context) > 0
                && controller(server, context).chunkKeepReason() == ChunkKeepReason.CRANE_JOB;
    }

    /** Holding with nothing but a waiting machine behind it: the state the separate collect opt-in exists for. */
    private boolean holdingForCollecting(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        return heldChunks(server, context) > 0
                && controller(server, context).chunkKeepReason() == ChunkKeepReason.COLLECTING
                && crane(server, context).currentJob().isEmpty();
    }

    /**
     * The aisle chunk really unloaded: vanilla's unload callback marked the dock's block entity removed and the position
     * no longer counts as loaded. Checking {@code isLoaded} alone would pass while the block entity is still the same
     * live object waiting in the unload queue.
     */
    private boolean dockUnloadedAndNothingHeld(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        requireNothingHeld(server, context, "while the aisle is unloading");
        StackerCraneBlockEntity before = dockBeforeAway;
        return before != null && before.isRemoved() && !server.overworld().isLoaded(context.origin());
    }

    /**
     * The scene is back: the dock position is loaded, <b>every chunk the census box touches</b> is loaded (the box
     * straddles chunk borders, so waiting for the origin's chunk alone would let a census miss a rack wall), and the
     * controller has all its members and its port again.
     */
    private boolean sceneLoaded(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        return level.isLoaded(context.origin()) && SceneItemCensus.isFullyLoaded(level, censusBox)
                && sceneReady(server, context) && portReady(server, context);
    }

    private void rememberBeforeAway(MinecraftServer server, VisualContext context) {
        dockBeforeAway = crane(server, context);
        portBeforeAway = port(server.overworld(), context.origin());
        collectedWhenLeaving = portBeforeAway.collectedItems();
        collectedAtUnload = collectedWhenLeaving;
        LOGGER.info(PREFIX + "chunks: before the player leaves: phase={} head={} collected={} held={} raw={}",
                dockBeforeAway.craneState().phase(), dockBeforeAway.heldItems().totalCount(), collectedWhenLeaving,
                heldChunks(server, context), AisleChunkTickets.rawBlockForcedChunkCount(server.overworld()));
    }

    // --- the assertions of chapter 1 ---------------------------------------------------------------------------------

    /**
     * The shipped default really is off and really holds nothing — the guard for "a server that does not want this pays
     * nothing", and the guard against a run that would assert the defaults without ever changing one.
     */
    private void assertShippedDefault(MinecraftServer server, VisualContext context) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so this run could not change it");
        if (WareworksConfig.chunkLoadingEnabled())
            throw new VisualTestException("chunk loading must be off by default, but maxTicketedAislesPerLevel is "
                    + WareworksConfig.maxTicketedAislesPerLevel());
        if (WareworksConfig.maxCollectHoldAislesPerLevel() != 0)
            throw new VisualTestException("the collect opt-in must be off by default, but it is "
                    + WareworksConfig.maxCollectHoldAislesPerLevel());
        requireNothingHeld(server, context, "with the shipped config");
        leakProbe(server, context);
    }

    /**
     * The aisle went away with work left to do — without that, "nothing happened while away" would say nothing — and this
     * is where the numbers the away period is judged against are read.
     * <p>
     * They are read <b>here</b> rather than before the teleport on purpose: the crane goes on working for the handful of
     * ticks between the teleport and the unload, so the value from before the teleport is not the one the save was written
     * from. The block entity the unload left behind is the object that was ticking, so its fields are.
     */
    private void assertLeftUnfinished(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity before = dockBeforeAway;
        WarehouseOutputBlockEntity port = portBeforeAway;
        if (before == null || port == null)
            throw new VisualTestException("no dock and port were remembered before the player left");
        if (before.currentJob().isEmpty() && before.heldItems().isEmpty())
            throw new VisualTestException("the aisle had nothing left to do when its chunks went away, so this chapter "
                    + "would prove nothing");
        collectedAtUnload = port.collectedItems();
        LOGGER.info(PREFIX + "chunks: the aisle unloaded in the middle of its work (phase {}, {} item(s) in the head, "
                + "{} collected when the player left and {} when the chunks went away)", before.craneState().phase(),
                before.heldItems().totalCount(), collectedWhenLeaving, collectedAtUnload);
    }

    /**
     * Nothing at all happened while the player was away, and the aisle carried on when the player came back.
     * <p>
     * The claim rests on three things that cannot race: the whole away period was asserted tick by tick to have the
     * dock's chunk <b>unloaded</b> and the mod holding <b>nothing</b>, so nothing there could run; the block entity the
     * unload left behind still carries the counter it had then; and the dock is a <b>new</b> object now, so it really was
     * read from the save rather than kept in memory. The live counter is only logged — by the time a controller is ready
     * again the crane has legitimately resumed, and an equality on it would be a race against the aisle's own recovery.
     */
    private void assertNoProgress(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity now = crane(server, context);
        if (now == dockBeforeAway)
            throw new VisualTestException("the dock block entity was never unloaded and read again, so the aisle did not "
                    + "really go away");
        WarehouseOutputBlockEntity port = port(server.overworld(), context.origin());
        if (port == portBeforeAway)
            throw new VisualTestException("the port block entity was never unloaded and read again");
        WarehouseOutputBlockEntity stale = portBeforeAway;
        if (stale == null || stale.collectedItems() != collectedAtUnload)
            throw new VisualTestException("the unloaded aisle changed while it was away: the block entity the unload left "
                    + "behind now counts " + (stale == null ? "nothing" : stale.collectedItems())
                    + " collected items instead of " + collectedAtUnload);
        long left = countIn(server.overworld(), machinePos(context.origin()));
        if (port.collectedItems() < collectedAtUnload)
            throw new VisualTestException("the aisle lost collected items over the unload: " + port.collectedItems()
                    + " instead of at least " + collectedAtUnload);
        if (now.currentJob().isEmpty() && now.heldItems().isEmpty() && left == 0)
            throw new VisualTestException("the collection was already finished when the aisle came back, although its "
                    + "chunks were unloaded the whole time, so this chapter would prove nothing");
        requireNothingHeld(server, context, "with the setting off");
        LOGGER.info(PREFIX + "chunks: the aisle was away with {} collected and came back with {} ({} still in the "
                + "machine, phase {}) - it resumed instead of having finished", collectedAtUnload,
                port.collectedItems(), left, now.craneState().phase());
    }

    // --- the assertions of chapter 2 ---------------------------------------------------------------------------------

    /** The held set is exactly {@link AisleChunkSpan}'s footprint of this aisle — never more, and never a partial hold. */
    private void assertFootprintHeld(MinecraftServer server, VisualContext context) {
        BlockPos dock = context.origin();
        LongSet wanted = footprint(dock);
        LongSet held = AisleChunkTickets.heldChunks(server.overworld(), controllerPos(dock));
        if (!held.equals(wanted))
            throw new VisualTestException("the aisle holds " + describe(held) + " instead of its footprint "
                    + describe(wanted));
        WarehouseControllerBlockEntity controller = controller(server, context);
        if (controller.chunkKeepChunks() != wanted.size() || controller.chunkFootprintSize() != wanted.size())
            throw new VisualTestException("the aisle holds " + wanted.size() + " chunk(s) but reports "
                    + controller.chunkKeepChunks() + " of a footprint of " + controller.chunkFootprintSize());
        LOGGER.info(PREFIX + "chunks: the aisle holds exactly its footprint: {}", describe(held));
    }

    /**
     * The per-tick guard of the away phase: the hold never drops to zero, the dock is never unloaded, the player really
     * is far away, and NeoForge holds exactly what the mod says it does.
     */
    private void guardWhileHolding(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        requireHolding(server, context, "while the player is away and the aisle still has work");
        minHoldWhileAway = Math.min(minHoldWhileAway, heldChunks(server, context));
        reasonsSeen.add(controller(server, context).chunkKeepReason());
        StackerCraneBlockEntity before = dockBeforeAway;
        if (before == null || before.isRemoved())
            throw new VisualTestException("the dock's chunk unloaded although the aisle holds tickets for it");
        double distance = context.serverPlayer(server).position().distanceTo(Vec3.atCenterOf(context.origin()));
        if (distance < MIN_AWAY_DISTANCE)
            throw new VisualTestException("the player is only " + Math.round(distance) + " blocks from the aisle, so "
                    + "this chapter would not be about an aisle nobody is near");
    }

    /** The headline claim: the whole collection ran while the player was far away, and the dock never left memory. */
    private void assertWorkedWhileAway(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        StackerCraneBlockEntity now = crane(server, context);
        if (now != dockBeforeAway || now.isRemoved())
            throw new VisualTestException("the dock block entity was unloaded and read again while the aisle was "
                    + "supposed to be holding its own chunks");
        long collected = port(level, context.origin()).collectedItems() - collectedWhenLeaving;
        if (collected <= 0)
            throw new VisualTestException("the aisle collected nothing at all while the player was away");
        long left = countIn(level, machinePos(context.origin()));
        if (left != 0)
            throw new VisualTestException("the machine is not empty: " + left + " " + COLLECTED + " left");
        if (minHoldWhileAway <= 0)
            throw new VisualTestException("the hold dropped to 0 while the aisle still had work");
        if (!reasonsSeen.contains(ChunkKeepReason.CRANE_JOB))
            throw new VisualTestException("the aisle never reported a crane job while it worked: " + reasonsSeen);
        LOGGER.info(PREFIX + "chunks: {} item(s) were collected with nobody within {} blocks; the racks hold {}, the "
                + "hold never fell below {} chunk(s), reasons seen {}", collected, (int) MIN_AWAY_DISTANCE,
                controller(server, context).countOf(COLLECTED), minHoldWhileAway, reasonsSeen);
    }

    /** The idle aisle let go, and it lingered first rather than letting go in the tick its last job ended. */
    private void assertReleasedWhenIdle(MinecraftServer server, VisualContext context) {
        requireNothingHeld(server, context, "once the aisle is idle");
        if (!reasonsSeen.contains(ChunkKeepReason.RELEASING))
            throw new VisualTestException("the aisle never reported that it was letting go, so it did not linger: "
                    + reasonsSeen);
        WarehouseControllerBlockEntity controller = controller(server, context);
        if (controller.chunkKeepReason() != ChunkKeepReason.NONE || controller.chunkKeepChunks() != 0)
            throw new VisualTestException("the released aisle still reports " + controller.chunkKeepReason() + "/"
                    + controller.chunkKeepChunks());
        LOGGER.info(PREFIX + "chunks: the idle aisle lingered and then released everything (reasons seen {})",
                reasonsSeen);
    }

    // --- the assertions of chapter 3 (the restart) --------------------------------------------------------------------

    /** Saves the world in the middle of a held, running job, and remembers what the save has to come back with. */
    private void saveWhileHolding(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        heldBeforeRestart = heldChunks(server, context);
        if (heldBeforeRestart <= 0)
            throw new VisualTestException("the aisle must hold its chunks when the world is saved");
        collectedBeforeRestart = port(level, context.origin()).collectedItems();
        inMachineBeforeRestart = countIn(level, machinePos(context.origin()));
        if (inMachineBeforeRestart <= 0)
            throw new VisualTestException("the machine must still have something to collect when the world is saved, or "
                    + "the restart would have nothing to carry");
        server.saveEverything(true, true, true);
        LOGGER.info(PREFIX + "chunks: saved the world while the aisle held {} chunk(s), had collected {} and still had "
                + "{} in its machine", heldBeforeRestart, collectedBeforeRestart, inMachineBeforeRestart);
    }

    /**
     * Writes the world's own server config, which is what the rejoin reads. NeoForge loads server configs <b>per
     * world</b> from {@code <world>/serverconfig/}, where a file overrides the one in {@code <instance>/config/} (its
     * own {@code readme.txt} in that folder says so), and it corrects every key the file leaves out to its default.
     */
    private static void writeWorldConfigOverride(MinecraftServer server, int aisles) throws IOException {
        Path folder = server.getWorldPath(SERVER_CONFIG_DIRECTORY);
        Files.createDirectories(folder);
        Path file = folder.resolve(Wareworks.ID + "-server.toml");
        Files.writeString(file, """
                # Written by the Wareworks visual test scenario "chunks" (M19, issue #10): the chapter about a real
                # restart needs the world it reopens to have chunk loading switched on in a file, because a server
                # config is loaded per world and an in-memory override does not survive a rejoin. This file overrides
                # <instance>/config/wareworks-server.toml for this throw-away world only.
                [chunkLoading]
                \tmaxTicketedAislesPerLevel = %d
                """.formatted(aisles), StandardCharsets.UTF_8);
        LOGGER.info(PREFIX + "chunks: wrote {} with maxTicketedAislesPerLevel = {}", file, aisles);
    }

    /** The rejoined world really reads the setting from its own config file, or the chapter would prove nothing. */
    private void assertConfigAfterRestart(MinecraftServer server, VisualContext context) {
        if (WareworksConfig.maxTicketedAislesPerLevel() != HOLD_AISLES)
            throw new VisualTestException("the rejoined world reads maxTicketedAislesPerLevel = "
                    + WareworksConfig.maxTicketedAislesPerLevel() + " instead of the " + HOLD_AISLES
                    + " its own server config file says");
    }

    private boolean aisleBackAfterRestart(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        return heldChunks(server, context) >= heldBeforeRestart && server.overworld().isLoaded(context.origin());
    }

    /**
     * What the restart brought back: the whole footprint held again, the aisle loaded although the player never came
     * near it, the crane's job still there and the machine still holding what it had not handed over yet.
     */
    private void assertRestartedHold(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        double distance = context.serverPlayer(server).position().distanceTo(Vec3.atCenterOf(context.origin()));
        if (distance < MIN_AWAY_DISTANCE)
            throw new VisualTestException("the player is only " + Math.round(distance) + " blocks from the aisle after "
                    + "the rejoin, so the aisle may simply have been loaded by the player");
        LongSet held = AisleChunkTickets.heldChunks(level, controllerPos(context.origin()));
        if (!held.equals(footprint(context.origin())))
            throw new VisualTestException("the restarted aisle holds " + describe(held) + " instead of its footprint "
                    + describe(footprint(context.origin())));
        StackerCraneBlockEntity crane = crane(server, context);
        long left = countIn(level, machinePos(context.origin()));
        if (crane.currentJob().isEmpty() && crane.heldItems().isEmpty() && left == 0)
            throw new VisualTestException("nothing was left to do after the restart, so this chapter would prove "
                    + "nothing");
        if (left <= 0)
            throw new VisualTestException("the machine was already empty after the restart");
        long collected = port(level, context.origin()).collectedItems();
        if (collected < collectedBeforeRestart)
            throw new VisualTestException("the restarted aisle counts " + collected + " collected items, fewer than the "
                    + collectedBeforeRestart + " it had when the world was saved");
        // The rest of this chapter guards against an unload, and after a restart every block entity is a new object.
        dockBeforeAway = crane;
        portBeforeAway = port(level, context.origin());
        LOGGER.info(PREFIX + "chunks: the rejoined world brought the aisle back {} blocks from the player: {} held "
                + "again, phase {}, {} collected, {} left in the machine", Math.round(distance), describe(held),
                crane.craneState().phase(), collected, left);
    }

    // --- the assertions of the operator, broken and switched-off chapters --------------------------------------------

    private void assertReleasedByCommand(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        requireNothingHeld(server, context, "after /wareworks chunks release");
        if (!craneHasWork(server, context))
            throw new VisualTestException("the aisle had nothing to do when the operator released its hold, so the "
                    + "release would prove nothing about a hold that is still wanted");
        LOGGER.info(PREFIX + "chunks: the operator's release let everything go while the aisle still had work");
    }

    private void assertStillGivenUp(MinecraftServer server, VisualContext context) {
        leakProbe(server, context);
        requireNothingHeld(server, context, "after the operator released it and its work went on");
        WarehouseControllerBlockEntity controller = controller(server, context);
        if (controller.chunkKeepReason() != ChunkKeepReason.GAVE_UP)
            throw new VisualTestException("the released aisle reports " + controller.chunkKeepReason()
                    + " instead of having given up");
    }

    private void breakControllerWhileHolding(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos owner = controllerPos(context.origin());
        heldBeforeBreak = heldChunks(server, context);
        int raw = AisleChunkTickets.rawBlockForcedChunkCount(level);
        if (heldBeforeBreak <= 0)
            throw new VisualTestException("the controller must hold chunks when it is broken");
        rawAfterBreak = raw - heldBeforeBreak;
        // dropBlock = false: the controller block itself must not become an item entity in the census.
        level.destroyBlock(owner, false);
        LOGGER.info(PREFIX + "chunks: broke the controller at {} while it held {} of the level's {} force-loaded "
                + "chunk(s)", owner, heldBeforeBreak, raw);
    }

    private void assertReleasedWithOwner(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        requireNothingHeld(server, context, "after its owner was broken");
        int raw = AisleChunkTickets.rawBlockForcedChunkCount(level);
        if (raw != rawAfterBreak)
            throw new VisualTestException("NeoForge still force-loads " + raw + " chunk(s) in this level, not the "
                    + rawAfterBreak + " left after the " + heldBeforeBreak + " the broken controller held");
        leakProbe(server, context);
        LOGGER.info(PREFIX + "chunks: the {} ticket(s) of the broken controller are gone in the same tick",
                heldBeforeBreak);
    }

    private void assertReleasedByConfig(MinecraftServer server, VisualContext context) {
        requireNothingHeld(server, context, "after the setting was switched off");
        long left = countIn(server.overworld(), machinePos(context.origin()));
        if (!craneHasWork(server, context) && left == 0)
            throw new VisualTestException("the aisle had nothing left to do, so switching the setting off proves "
                    + "nothing");
        WarehouseControllerBlockEntity controller = controller(server, context);
        if (controller.chunkKeepReason() != ChunkKeepReason.NONE)
            throw new VisualTestException("with the feature off the aisle must report nothing, not "
                    + controller.chunkKeepReason());
        LOGGER.info(PREFIX + "chunks: the setting was switched off and the aisle let go although it still had work "
                + "({} item(s) left in the machine)", left);
    }

    /** The end state of the run: the shipped config again, and not one ticket anywhere on the server. */
    private void assertNothingHeldAtAll(MinecraftServer server, VisualContext context) {
        setChunkLoading(0, 0);
        setConfig(WareworksConfig.SERVER.maxChunksPerAisle, WareworksConfig.SERVER.maxChunksPerAisle.getDefault());
        for (ServerLevel level : server.getAllLevels()) {
            if (AisleChunkTickets.holdingAisleCount(level) != 0)
                throw new VisualTestException("aisles in " + level.dimension().location() + " still hold chunks: "
                        + AisleChunkTickets.entries(level));
            int raw = AisleChunkTickets.rawBlockForcedChunkCount(level);
            if (raw != 0)
                throw new VisualTestException("NeoForge still force-loads " + raw + " chunk(s) in "
                        + level.dimension().location());
        }
        LOGGER.info(PREFIX + "chunks: no level of this server force-loads a single chunk any more");
    }

    // --- the invariants every tick of the run is checked against -----------------------------------------------------

    /**
     * The leak check: the union of every chunk the mod believes it holds in a level equals the number of chunks NeoForge
     * itself has force-loaded there by block tickets. No other mod holds block tickets in this world, so the two must be
     * equal, and a ticket that outlived its owner is exactly a difference between them.
     */
    private void leakProbe(MinecraftServer server, VisualContext context) {
        for (ServerLevel level : server.getAllLevels()) {
            int ours = AisleChunkTickets.allHeldChunks(level).size();
            int raw = AisleChunkTickets.rawBlockForcedChunkCount(level);
            if (ours != raw)
                throw new VisualTestException("chunk ticket leak in " + level.dimension().location() + ": Wareworks "
                        + "says it holds " + ours + " chunk(s), NeoForge force-loads " + raw + " (aisles "
                        + AisleChunkTickets.entries(level) + ")");
        }
    }

    private void requireNothingHeld(MinecraftServer server, VisualContext context, String when) {
        int held = heldChunks(server, context);
        if (held != 0)
            throw new VisualTestException("the aisle holds " + held + " chunk(s) " + when);
        int holders = AisleChunkTickets.holdingAisleCount(server.overworld());
        if (holders != 0)
            throw new VisualTestException("another aisle holds chunks " + when + ": "
                    + AisleChunkTickets.entries(server.overworld()));
    }

    private void requireHolding(MinecraftServer server, VisualContext context, String when) {
        if (heldChunks(server, context) <= 0)
            throw new VisualTestException("the aisle holds nothing " + when);
    }

    private static boolean craneHasWork(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = crane(server, context);
        return crane.currentJob().isPresent() || !crane.heldItems().isEmpty();
    }

    private static int heldChunks(MinecraftServer server, VisualContext context) {
        return AisleChunkTickets.heldChunkCount(server.overworld(), controllerPos(context.origin()));
    }

    /** The chunk keys of this aisle's footprint, straight from the pure arithmetic the feature itself uses. */
    private static LongSet footprint(BlockPos dock) {
        int[] pairs = AisleChunkSpan.chunks(dock.getX(), dock.getZ(), AISLE.getStepX(), AISLE.getStepZ(), RAILS);
        LongSet chunks = new LongOpenHashSet(pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2)
            chunks.add(ChunkPos.asLong(pairs[i], pairs[i + 1]));
        return chunks;
    }

    private static int footprintSize(BlockPos dock) {
        return AisleChunkSpan.chunkCount(dock.getX(), dock.getZ(), AISLE.getStepX(), AISLE.getStepZ(), RAILS);
    }

    private static String describe(LongSet chunks) {
        List<Long> keys = new ArrayList<>(chunks.size());
        for (LongIterator it = chunks.iterator(); it.hasNext();)
            keys.add(it.nextLong());
        keys.sort(null);
        List<String> names = new ArrayList<>(keys.size());
        for (long key : keys)
            names.add(new ChunkPos(key).toString());
        return names.size() + " chunk(s) " + names;
    }

    // --- the goggle lines --------------------------------------------------------------------------------------------

    /** A goggle shot of the controller, with the tooltip's own lines asserted before the shutter ({@link GoggleShots}). */
    private void goggles(VisualScript script, String label, Predicate<VisualContext> synced,
            VisualScript.ClientAction check) {
        reach(script, GoggleShots.vanillaReach());
        fly(script);
        GoggleShots.shot(script, NAME, AT_CONTROLLER, label, ChunkLoadingVisualScenario::controllerPos, synced, check);
        reach(script, 0.0);
    }

    /** Waits until the client's own copy of the summary carries {@code reason}: a shot must never race the sync packet. */
    private static Predicate<VisualContext> summaryHas(ChunkKeepReason reason) {
        return context -> clientSummary(context).filter(summary -> summary.chunkKeepReason() == reason).isPresent();
    }

    /** With the feature off the controller says nothing about chunks at all — not even a zero. */
    private void checkNoChunkLine(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        LOGGER.info(PREFIX + "chunks: controller goggles with the setting off {}", lines);
        GoggleShots.requireNoLine(lines, chunkLinePrefix());
    }

    /**
     * "Chunk loading: 4 chunks (crane job)", built exactly as the block entity builds it and with the chunk count the
     * pure footprint arithmetic says, so a wrong number cannot pass.
     * <p>
     * The reason is read from the client in the same tick as the lines rather than from {@code expected}: between the
     * step that waited for the synced reason and this one the aisle may legitimately have moved on to the next holding
     * reason (a job starting between two trips), and a shot of that is just as true. What the chapter <b>asserts</b>
     * about which reason occurs is done on the server, where it is exact.
     */
    private void checkChunkLine(VisualContext context, ChunkKeepReason expectedReason) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        ChunkKeepReason shown = clientSummary(context).map(ControllerGoggleSummary::chunkKeepReason)
                .orElse(ChunkKeepReason.NONE);
        LOGGER.info(PREFIX + "chunks: controller goggles while holding ({}, expected {}) {}", shown, expectedReason,
                lines);
        if (!shown.isHolding())
            throw new VisualTestException("the client says the aisle is not holding at all: " + shown);
        GoggleShots.requireLine(lines,
                WareworksLang.chunkLoading(footprintSize(context.origin()), shown).component().getString());
    }

    /** "Chunk loading: none (gave up ...)": a reason that holds nothing and is not about a number. */
    private void checkChunkNoneLine(VisualContext context, ChunkKeepReason reason) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        LOGGER.info(PREFIX + "chunks: controller goggles while refusing {}", lines);
        GoggleShots.requireLine(lines, WareworksLang.chunkLoadingNone(reason).component().getString());
    }

    /** "Chunk loading: none (this aisle needs 4 of 3 chunks)": the one refusal a player can act on. */
    private void checkRefusedChunkLine(VisualContext context) {
        List<String> lines = GoggleShots.lines(context, controllerPos(context.origin()));
        LOGGER.info(PREFIX + "chunks: controller goggles over the per-aisle cap {}", lines);
        int needed = footprintSize(context.origin());
        if (needed <= TOO_SMALL_CHUNK_CAP)
            throw new VisualTestException("this aisle needs only " + needed + " chunk(s), which the cap "
                    + TOO_SMALL_CHUNK_CAP + " allows, so nothing would be refused");
        GoggleShots.requireLine(lines,
                WareworksLang.chunkLoadingTooMany(needed, TOO_SMALL_CHUNK_CAP).component().getString());
    }

    /** The label every chunk goggle line starts with, whatever it goes on to say ("Chunk loading:"). */
    private static String chunkLinePrefix() {
        String line = WareworksLang.translateDirect(WareworksLang.GOGGLES_CHUNK_LOADING_NONE, "").getString();
        int colon = line.indexOf(':');
        return colon > 0 ? line.substring(0, colon + 1) : line;
    }

    private static Optional<ControllerGoggleSummary> clientSummary(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(controllerPos(context.origin()))
                instanceof WarehouseControllerBlockEntity controller ? Optional.of(controller.summary())
                        : Optional.empty();
    }

    // --- the operator's command, read back out of the chat -----------------------------------------------------------

    /**
     * Runs a {@code /wareworks} command as the player and waits until the server's answer is in the chat. The player of a
     * single-player world with cheats is its owner, so they have permission level 4 and may run it
     * ({@code ServerPlayer#getPermissionLevel} → {@code MinecraftServer#getProfilePermissions}) — which is the point of
     * the command: an operator must be able to look without a mod menu.
     */
    private void runCommand(VisualScript script, String label, Function<VisualContext, String> command,
            int expectedLines) {
        script.client("chunks: forget the earlier chat answers", context -> chatLines.clear())
                .client("chunks: run /" + label, context -> {
                    LocalPlayer player = context.minecraft().player;
                    if (player == null)
                        throw new VisualTestException("no local player for the command /" + label);
                    player.connection.sendCommand(command.apply(context));
                })
                .until("chunks: wait for the answer of /" + label, context -> chatLines.size() >= expectedLines,
                        SYNC_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("chunks: log the answer of /" + label,
                        context -> LOGGER.info(PREFIX + "chunks: /{} answered {}", label, List.copyOf(chatLines)));
    }

    /** The listing must name this aisle, its chunk count and the dimension's whole force-loaded count — which is ours. */
    private void checkListing(VisualContext context) {
        List<String> lines = List.copyOf(chatLines);
        int chunks = footprintSize(context.origin());
        requireChat(lines,
                WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_HEADER, overworld()).getString());
        requireChat(lines, format(controllerPos(context.origin())));
        requireChat(lines, WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_TOTAL,
                WareworksLang.number(chunks), WareworksLang.number(1), WareworksLang.number(1)).getString());
        // The leak check in the operator's own hands: the dimension's whole block-ticket count is ours and nothing else,
        // and in this throw-away world nothing else is force-loaded at all, so the total is the same number again.
        requireChat(lines, WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_RAW, overworld(),
                WareworksLang.number(chunks), WareworksLang.number(chunks)).getString());
    }

    private void checkReleaseAnswer(VisualContext context) {
        requireChat(List.copyOf(chatLines), WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_RELEASED,
                format(controllerPos(context.origin()))).getString());
    }

    private void checkEmptyListing(VisualContext context) {
        requireChat(List.copyOf(chatLines),
                WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_NONE).getString());
    }

    private static void requireChat(List<String> lines, String text) {
        if (lines.stream().noneMatch(line -> line.contains(text)))
            throw new VisualTestException("the server never said '" + text + "'; it said " + lines);
    }

    private void installChatListener() {
        if (listening)
            return;
        listening = true;
        NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.System.class, event -> {
            if (!event.isOverlay())
                chatLines.add(event.getMessage().getString());
        });
    }

    private static String overworld() {
        return net.minecraft.world.level.Level.OVERWORLD.location().toString();
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // --- the config (in memory only, exactly as a GameTest override does) --------------------------------------------

    /**
     * Changes the chunk-loading settings the way {@code ConfigOverrides} does in a GameTest:
     * {@code ModConfigSpec.ConfigValue#set} plus {@code clearCache}, which writes into the loaded config in memory only.
     * No file is written and no config event fires, so {@code wareworks-server.toml} is left exactly as the player has
     * it.
     * <p>
     * Because no event fires, {@code AisleChunkTickets.configGeneration()} does not change either — so a controller
     * notices a change here on its own bounded re-check rather than in the next tick. Every chapter that switches the
     * feature <b>on</b> therefore gives the aisle fresh work afterwards, and the one that switches it <b>off</b> waits
     * for that re-check on purpose: it is the safety net that has to work.
     */
    private static void setChunkLoading(int aisles, int collectAisles) {
        setConfig(WareworksConfig.SERVER.maxTicketedAislesPerLevel, aisles);
        setConfig(WareworksConfig.SERVER.maxCollectHoldAislesPerLevel, collectAisles);
    }

    private static void setConfig(ModConfigSpec.IntValue value, int newValue) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so overriding it would be a silent no-op");
        value.set(newValue);
        value.clearCache();
    }

    // --- the player --------------------------------------------------------------------------------------------------

    private static void moveToBuildSite(MinecraftServer server, VisualContext context) {
        teleport(server, context, BUILD_X + 0.5, FLIGHT_Y, BUILD_Z + 0.5);
    }

    private static void moveAway(MinecraftServer server, VisualContext context) {
        teleport(server, context, AWAY_X + 0.5, FLIGHT_Y, AWAY_Z + 0.5);
        LOGGER.info(PREFIX + "chunks: the player is {} blocks from the aisle", Math.round(context.serverPlayer(server)
                .position().distanceTo(Vec3.atCenterOf(context.origin()))));
    }

    private static void teleport(MinecraftServer server, VisualContext context, double x, double y, double z) {
        ServerPlayer player = context.serverPlayer(server);
        player.teleportTo(server.overworld(), x, y, z, player.getYRot(), player.getXRot());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    private static void liftPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        BlockPos above = context.origin().above(4);
        player.teleportTo(server.overworld(), above.getX() + 0.5, above.getY(), above.getZ() + 0.5, player.getYRot(),
                player.getXRot());
    }

    /**
     * Lifts the player back into the air and switches creative flight on again before every group of camera views: a
     * creative player that touches the ground switches flying off by itself, and the next camera in mid-air would then
     * never arrive.
     */
    private static void fly(VisualScript script) {
        script.server("chunks: put the player back in the air and flying", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            liftPlayer(server, context);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until("chunks: wait until the client is flying too", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getAbilities().flying;
        }, SYNC_TIMEOUT_TICKS);
    }

    /** Flying, wearing Engineer's Goggles and holding nothing, so no item covers a corner of a GUI shot. */
    private static void equipPlayer(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // clearContent() empties the armour slots too, so the goggles go on afterwards, never before.
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, AllItems.GOGGLES.asStack());
        player.inventoryMenu.broadcastChanges();
    }

    private static void reach(VisualScript script, double range) {
        GoggleShots.reach(script, NAME, range);
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static AisleLayout layout(BlockPos dock) {
        return AisleLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos controllerPos(BlockPos dock) {
        return dock.relative(AISLE.getOpposite());
    }

    /** The inventory a collecting port reaches into: the block behind it, i.e. away from the aisle. */
    private static BlockPos machinePos(BlockPos dock) {
        AisleLayout layout = layout(dock);
        return layout.rackPos(PORT).relative(layout.sideDirection(PORT.side()));
    }

    private static WarehouseControllerBlockEntity controller(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), controllerPos(context.origin()));
        if (controller == null)
            throw new VisualTestException("the warehouse controller of the aisle is missing");
        return controller;
    }

    private static StackerCraneBlockEntity crane(MinecraftServer server, VisualContext context) {
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(server.overworld(),
                context.origin());
        if (crane == null)
            throw new VisualTestException("the stacker crane dock at " + context.origin() + " is gone");
        return crane;
    }

    private static WarehouseOutputBlockEntity port(ServerLevel level, BlockPos dock) {
        WarehouseOutputBlockEntity port = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT.getNullable(level,
                layout(dock).rackPos(PORT));
        if (port == null)
            throw new VisualTestException("the warehouse port of the aisle is missing");
        return port;
    }

    private static CreativeMotorBlockEntity motor(ServerLevel level, BlockPos dock) {
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        return motor;
    }

    private static long countIn(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
            total += handler.getStackInSlot(slot).getCount();
        return total;
    }

    private static Optional<StackerCraneBlockEntity> clientCrane(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return Optional.empty();
        return level.getBlockEntity(context.origin()) instanceof StackerCraneBlockEntity crane ? Optional.of(crane)
                : Optional.empty();
    }
}
