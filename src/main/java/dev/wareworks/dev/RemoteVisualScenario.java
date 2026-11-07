package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.gui.widget.ScrollInput;

import dev.wareworks.client.gui.TerminalCraftPanel;
import dev.wareworks.client.gui.WarehouseTerminalScreen;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.BranchLayout;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteAccess;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.content.station.TerminalMenuLayout;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.inventory.InventorySnapshot;
import dev.wareworks.core.remote.RemoteLimits;
import dev.wareworks.core.terminal.StockLine;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Scenario "remote" (issue #44, ADR-077): the Warehouse Remote in a real client, linked, used from afar and put away
 * with real input, in English and German.
 * <p>
 * It builds a short powered aisle with a warehouse terminal beside the dock and stocked racks in a creative world with
 * the vanilla reach ({@link VisualWorldProfile#playable}), force-loads the aisle's chunks and gives the player a
 * remote. The player stands on the ground throughout, so holding the sneak key crouches and never sinks a flying
 * camera. One <b>round</b> ({@link #round}) per language:
 * <ol>
 * <li>Behind the terminal, with the sneak key held down ({@link KeyMapping#setDown}, so the client's own input reads
 * it and tells the server) and a real click on the use key ({@link KeyMapping#click}, as the mouse handler does): the
 * remote is linked to that terminal — the link names its position, dimension and identity on the server — the action
 * bar says so, the item's tooltip names the position, and no screen opens ({@code remote-linked}).</li>
 * <li>{@value #OUT_OF_RANGE_BLOCKS} blocks down the aisle line, beyond the server's {@code remote.range}, a use into the
 * air is refused with the distance and the range on the action bar ({@code remote-out-of-range}).</li>
 * <li>{@value #IN_RANGE_BLOCKS} blocks away — far outside the client's render distance, which the run checks: the client
 * no longer has the terminal's chunk — a use into the air opens the terminal's screen from afar, titled
 * "Terminal via Remote", with the whole stock list and no client block entity behind it ({@code remote-screen}, and the
 * remote's own tooltip over its hotbar slot, {@code remote-item-tooltip}).</li>
 * <li>A click on the iron in the stock list asks for {@value #IRON_REQUESTED} ingots: the crane delivers them into the
 * terminal's <b>buffer</b> ({@code remote-delivered}), and a census of the aisle and the player shows that nothing reached
 * the player. A click on the buffer slot takes nothing, on the client and on the server.</li>
 * <li>A recipe laid from afar (a ghost of the iron, stamped into the crafting grid) previews iron nuggets; the result
 * cell's tooltip ends with the red line that says to craft in person ({@code remote-craft-tooltip}), and a click on the
 * result plays the refusal and crafts nothing.</li>
 * <li>With the mouse on an empty inventory slot, the hotbar key moves the remote out of the hand: the server closes the
 * screen ({@code remote-put-away}). The remote goes back into the hand on the server thread — the harness has no real
 * way to move it there without a screen — and a sneaking use into the air unlinks it ({@code remote-unlinked}).</li>
 * </ol>
 * Every move of an item is bracketed by a {@link SceneItemCensus} of the aisle and the player. The only declared
 * changes are the remote's own link, which is part of its item key, and the emptying of the buffer between the rounds.
 * <p>
 * The second render pass shoots the remote in hand beside the terminal once more.
 */
public final class RemoteVisualScenario implements VisualScenario {
    public static final String NAME = "remote";

    private static final String WORLD_FOLDER = "wareworks_visual_remote";
    /** Two rounds with two crane trips each and a language switch take longer than the default. */
    private static final long RUN_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    private static final Direction AISLE = Direction.EAST;
    private static final int DOCK_X = 0;
    private static final int DOCK_Z = 0;
    private static final int RAILS = 4;
    private static final int MOTOR_RPM = 128;
    /** The terminal sits beside the dock, on the right rack side, as in the jei and craft scenarios. */
    private static final RackPosition TERMINAL = RackPosition.of(1, 0, Side.RIGHT);
    private static final int STORAGE_FIRST_POSITION = 2;
    private static final int STORAGE_LEVELS = 2;
    private static final int CLEAR_MARGIN = 3;
    private static final int CLEAR_HEIGHT = 8;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    /** One item type per rack position; only the iron is asked for, the rest makes the list look like a store. */
    private static final List<ItemStack> STOCK = List.of(new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.REDSTONE, 64),
            new ItemStack(Items.GLASS, 48), new ItemStack(Items.COAL, 32), new ItemStack(Items.STRING, 16),
            new ItemStack(Items.LEATHER, 8));
    private static final int ITEM_TYPES = 8;
    private static final int IRON_REQUESTED = 16;
    /** A single iron ingot in the grid makes nine nuggets: a recipe the buffer's iron covers from afar. */
    private static final CraftGrid<ItemKey> NUGGET_GRID = CraftGrid.of(IRON);

    /** Where the player stands down the aisle line: inside {@code remote.range} but far beyond the render distance. */
    private static final double IN_RANGE_BLOCKS = 200.0;
    /** Half a block short of 300 from the eye, so the action bar's rounded-up distance reads 300. */
    private static final double OUT_OF_RANGE_BLOCKS = 299.5;
    /** Blocks behind the terminal's back face where the player stands to link it, well inside the vanilla reach. */
    private static final double LINK_DISTANCE = 3.0;
    /** The hotbar slot the remote is held in, and the main inventory slot it is moved to when it is put away. */
    private static final int REMOTE_SLOT = 0;
    private static final int PUT_AWAY_SLOT = 9;

    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";
    private static final String BASS = SoundEvents.NOTE_BLOCK_BASS.value().getLocation().toString();

    private static final int SCENE_READY_TIMEOUT_TICKS = 900;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int SNEAK_TIMEOUT_TICKS = 40;
    private static final int AIM_TIMEOUT_TICKS = 40;
    private static final int MESSAGE_TIMEOUT_TICKS = 60;
    private static final int HELD_TIMEOUT_TICKS = 100;
    private static final int CHUNK_TIMEOUT_TICKS = 300;
    private static final int SCREEN_TIMEOUT_TICKS = 200;
    private static final int AMOUNT_TIMEOUT_TICKS = 200;
    private static final int PREVIEW_TIMEOUT_TICKS = 200;
    private static final int DELIVERY_TIMEOUT_TICKS = 1200;
    private static final int RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 4;
    /** Client ticks after a click that must change nothing, in which a change, had one been made, would have arrived. */
    private static final int NO_CHANGE_TICKS = 10;
    /** The terminal's row of text and the gap the screen keeps between the title and the title row's right label. */
    private static final int ROW_WIDTH = TerminalMenuLayout.WIDTH - 2 * TerminalMenuLayout.MARGIN;
    private static final int TITLE_GAP = 4;
    /** Where the mouse is parked on the terminal for a shot without a tooltip: its title row. */
    private static final int TITLE_CORNER = 5;

    /** The aisle the census counts, around the whole scene; set by the build. */
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    /** The chunk columns of the census box, force-loaded while the player is away; set by the build. */
    private volatile List<ChunkPos> forcedChunks = List.of();
    /** Server thread: what the census of the aisle and the player has to be right now. */
    private volatile Map<ItemKey, Long> expected = Map.of();
    /** Server thread: the remote's item key as the census counts it, which its link is part of; set with the remote. */
    @Nullable
    private volatile ItemKey remoteKey;
    /** Ground heights, relative to the dock, where the player stands to link and down the aisle line. */
    private volatile int linkGround;
    private volatile int inRangeGround;
    private volatile int outOfRangeGround;
    /** Server thread: the rounded distance and the range the out-of-range refusal names. */
    private volatile long shownDistance;
    private volatile int range;
    /** Client thread: the refusal basses heard before the click on the result cell. */
    private int bassesBefore;
    private final VisualLanguage language = new VisualLanguage(RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** Real clicks need the vanilla reach, and a sneaking player must stand rather than fly: a creative world. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("remote: arm the watchdog for the whole run",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "remote run"))
                .server("remote: clear the area and place the creative motor", this::placeMotor)
                .server("remote: build the aisle, the terminal and the stocked racks", RemoteVisualScenario::buildAisle)
                .server("remote: CHECK the server's remote range and the ground", this::measureGround)
                .camera("link", this::linkView)
                .serverUntil("remote: wait until the controller has indexed every rack", this::sceneReady,
                        SCENE_READY_TIMEOUT_TICKS)
                .server("remote: force-load the aisle's chunks for the time the player is away",
                        (server, context) -> force(server, true))
                .server("remote: give the player a Warehouse Remote", this::giveRemote);
        ArmVisualScenario.untilOrFail(script, "remote: the client holds the remote", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == REMOTE_SLOT
                    && WareworksItems.WAREHOUSE_REMOTE.isIn(player.getMainHandItem());
        }, HELD_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem());
        script.server("remote: the census every move is measured against", (server, context) -> {
            expected = SceneItemCensus.take(server.overworld(), censusBox, context.serverPlayer(server));
            LOGGER.info(PREFIX + "remote: baseline census {}", SceneItemCensus.describe(expected));
        });
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass != VisualPass.FLYWHEEL) {
            // Neither the screen nor an item in hand depends on Flywheel: one look at the remote beside the terminal.
            script.camera("link", this::linkView)
                    .client("remote: clear the action bar of the last round",
                            context -> context.minecraft().gui.setOverlayMessage(Component.empty(), false))
                    .shotWithGui("remote-in-hand");
            return;
        }
        round(script, "");
        language.switchTo(script, "remote: ", GERMAN);
        round(script, "-de");
        language.switchTo(script, "remote: ", ENGLISH);
        script.client("remote: every check passed",
                context -> LOGGER.info(PREFIX + "remote: ALL CHECKS PASSED in English and German"));
    }

    /** Lets the aisle's chunks go again; the world is thrown away, but a scenario leaves no ticket behind. */
    @Override
    public void finish(VisualScript script) {
        script.server("remote: release the aisle's forced chunks", (server, context) -> force(server, false));
    }

    @Override
    public String status(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        LocalPlayer player = minecraft.player;
        if (player == null)
            return "player=none";
        RemoteLink link = WarehouseRemoteItem.link(player.getMainHandItem());
        String held = WareworksItems.WAREHOUSE_REMOTE.isIn(player.getMainHandItem())
                ? link == null ? "remote(unlinked)" : "remote(" + link.terminal().pos().toShortString() + ")"
                : player.getMainHandItem().isEmpty() ? "empty" : player.getMainHandItem().getItem().toString();
        String where = String.format(Locale.ROOT, "pos=%.1f,%.1f,%.1f sneak=%s held=%s lang=%s", player.getX(),
                player.getY(), player.getZ(), player.isShiftKeyDown(), held,
                minecraft.getLanguageManager().getSelected());
        Screen open = minecraft.screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return where + String.format(Locale.ROOT, " screen=terminal remote=%s title='%s' panel=%s status='%s'",
                    terminal.getMenu().isRemote(), terminal.getTitle().getString(),
                    terminal.isCraftPanelOpen() ? "open" : "closed",
                    terminal.feedbackLine().map(Component::getString).orElse(""));
        return where + " screen=" + (open == null ? "none" : open.getClass().getSimpleName()) + " actionbar='"
                + ArmVisualScenario.overlayText(context) + "'";
    }

    // --- one round ---------------------------------------------------------------------------------------------------

    /** Everything a player does with the remote once, with a shot of every moment worth seeing. */
    private void round(VisualScript script, String suffix) {
        linkSteps(script, suffix);
        outOfRangeSteps(script, suffix);
        openSteps(script, suffix);
        requestSteps(script, suffix);
        craftSteps(script, suffix);
        putAwaySteps(script, suffix);
        unlinkSteps(script, suffix);
        emptyBuffer(script);
    }

    /**
     * Behind the terminal: the unlinked remote says so, then a sneaking right-click on the terminal links it. The click
     * is a link and nothing else: no screen opens, in person or from afar.
     */
    private void linkSteps(VisualScript script, String suffix) {
        script.camera("link", this::linkView)
                .client("remote: CHECK the unlinked remote's tooltip", context -> checkTooltip(context,
                        WareworksLang.translateDirect(WareworksLang.REMOTE_TOOLTIP_UNLINKED).getString()));
        sneak(script, true);
        ArmVisualScenario.untilOrFail(script, "remote: the crosshair is on the terminal",
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(terminalPos(context)),
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the terminal at " + terminalPos(context));
        useKey(script, "the terminal while sneaking");
        ArmVisualScenario.untilOrFail(script, "remote: the client's remote is linked to the terminal", context -> {
            RemoteLink link = WarehouseRemoteItem.link(context.minecraft().player.getMainHandItem());
            return link != null && link.terminal().pos().equals(terminalPos(context));
        }, MESSAGE_TIMEOUT_TICKS, context -> "the remote in hand is " + context.minecraft().player.getMainHandItem());
        actionBar(script, "the link", context -> {
            BlockPos pos = terminalPos(context);
            return WareworksLang.translateDirect(WareworksLang.REMOTE_LINKED, pos.getX(), pos.getY(), pos.getZ());
        });
        script.waitTicks(NO_CHANGE_TICKS)
                .client("remote: CHECK the link click opened no screen", context -> {
                    if (context.minecraft().screen != null)
                        throw new VisualTestException("the link click opened " + context.minecraft().screen);
                    BlockPos pos = terminalPos(context);
                    checkTooltip(context, WareworksLang.translateDirect(WareworksLang.REMOTE_TOOLTIP_LINKED, pos.getX(),
                            pos.getY(), pos.getZ()).getString());
                })
                .server("remote: CHECK the server's link and the census", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
                    RemoteLink wanted = new RemoteLink(GlobalPos.of(Level.OVERWORLD, terminal.getBlockPos()),
                            terminal.terminalId());
                    RemoteLink link = WarehouseRemoteItem.link(player.getMainHandItem());
                    if (!wanted.equals(link))
                        throw new VisualTestException("the remote is linked to " + link + ", not to " + wanted);
                    if (player.containerMenu != player.inventoryMenu)
                        throw new VisualTestException("the link click opened " + player.containerMenu);
                    declareRemote(server, context, "the remote was linked");
                    LOGGER.info(PREFIX + "remote: CHECK PASS a sneaking right-click linked the remote to {} (id {}) and "
                            + "opened no screen", terminal.getBlockPos().toShortString(), terminal.terminalId());
                });
        sneak(script, false);
        script.shotWithGui("remote-linked" + suffix);
    }

    /** Beyond the range: a use into the air is refused on the action bar, and nothing opens. */
    private void outOfRangeSteps(VisualScript script, String suffix) {
        script.camera("out-of-range", context -> farView(context, OUT_OF_RANGE_BLOCKS, outOfRangeGround))
                .server("remote: the distance the refusal will name", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    double distance = RemoteAccess.distance(player, terminalPos(context));
                    range = WareworksConfig.remoteRange();
                    shownDistance = RemoteLimits.shownBlocks(distance);
                    if (RemoteLimits.withinRange(distance, range))
                        throw new VisualTestException("the player is only " + distance + " blocks from the terminal, "
                                + "within the range " + range);
                });
        skyAim(script);
        useKey(script, "into the air beyond the range");
        actionBar(script, "the out-of-range refusal", context -> WareworksLang.translateDirect(
                WareworksLang.REMOTE_OUT_OF_RANGE, shownDistance, range));
        script.waitTicks(NO_CHANGE_TICKS)
                .client("remote: CHECK the refused use opened no screen", context -> {
                    if (context.minecraft().screen != null)
                        throw new VisualTestException("the use out of range opened " + context.minecraft().screen);
                })
                .server("remote: CHECK the server opened nothing out of range", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    if (player.containerMenu != player.inventoryMenu)
                        throw new VisualTestException("the use out of range opened " + player.containerMenu);
                    LOGGER.info(PREFIX + "remote: CHECK PASS the use {} blocks away was refused (range {})",
                            shownDistance, range);
                })
                .shotWithGui("remote-out-of-range" + suffix);
    }

    /**
     * Within the range but far beyond the render distance: once the client has dropped the terminal's chunk, a use into
     * the air opens the screen from afar, with its title, its stock list and no client block entity.
     */
    private void openSteps(VisualScript script, String suffix) {
        script.camera("in-range", context -> farView(context, IN_RANGE_BLOCKS, inRangeGround));
        ArmVisualScenario.untilOrFail(script, "remote: the client has dropped the terminal's chunk",
                context -> !clientHasTerminalChunk(context), CHUNK_TIMEOUT_TICKS,
                context -> "the client still has the chunk of " + terminalPos(context));
        script.server("remote: CHECK the terminal is out of reach in person but loaded", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            ServerLevel level = server.overworld();
            BlockPos pos = terminalPos(context);
            WarehouseTerminalBlockEntity terminal = terminal(level, context.origin());
            if (!level.shouldTickBlocksAt(pos) || !SceneItemCensus.isFullyLoaded(level, censusBox))
                throw new VisualTestException("the aisle's chunks are not loaded while the player is away");
            if (terminal.canPlayerUse(player) || player.canInteractWithBlock(pos, 0.0))
                throw new VisualTestException("the terminal is still within the player's reach in person");
            LOGGER.info(PREFIX + "remote: CHECK PASS {} blocks from the terminal, out of reach in person, the "
                    + "client without its chunk, the aisle force-loaded", String.format(Locale.ROOT, "%.1f",
                            RemoteAccess.distance(player, pos)));
        });
        skyAim(script);
        useKey(script, "into the air within the range");
        ArmVisualScenario.untilOrFail(script, "remote: the terminal's screen opens from afar with its stock",
                RemoteVisualScenario::screenReady, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.client("remote: CHECK the screen opened from afar", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    WarehouseTerminalMenu menu = screen.getMenu();
                    String title = WareworksLang.translateDirect(WareworksLang.TERMINAL_REMOTE_TITLE).getString();
                    if (!menu.isRemote() || menu.terminal() != null)
                        throw new VisualTestException("the client menu is " + (menu.isRemote() ? "" : "not ")
                                + "remote and has the block entity " + menu.terminal());
                    if (!menu.terminalPos().equals(terminalPos(context)) || !menu.terminalDimension().equals(Level.OVERWORLD))
                        throw new VisualTestException("the client menu names " + menu.terminalDimension().location()
                                + " " + menu.terminalPos());
                    if (!screen.getTitle().getString().equals(title))
                        throw new VisualTestException("the screen is titled '" + screen.getTitle().getString()
                                + "', not '" + title + "'");
                    LOGGER.info(PREFIX + "remote: CHECK PASS the screen '{}' opened from afar with {} stock lines and no "
                            + "client block entity", title, screen.matchingEntries().size());
                    measureTitleRow(context, screen);
                })
                .server("remote: CHECK the server's menu is bound to the main hand", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    WarehouseTerminalMenu menu = serverMenu(player);
                    WarehouseTerminalBlockEntity terminal = terminal(server.overworld(), context.origin());
                    if (menu.terminal() != terminal || menu.remote().isEmpty()
                            || menu.remote().get().hand() != InteractionHand.MAIN_HAND
                            || !menu.remote().get().terminalId().equals(terminal.terminalId()))
                        throw new VisualTestException("the server's menu is not a remote one of this terminal bound to "
                                + "the main hand: " + menu.remote());
                    if (!menu.stillValid(player))
                        throw new VisualTestException("the remote menu is not valid from afar");
                    assertCensus(server, context, "the screen opened from afar");
                    LOGGER.info(PREFIX + "remote: CHECK PASS the server's menu is the terminal's, bound to {}",
                            menu.remote().get());
                });
        terminalShot(script, "remote-screen" + suffix);
        script.client("remote: rest the mouse on the remote's hotbar slot", context -> ScreenInput.hover(
                        context.minecraft(), ScreenInput.hotbarSlot(screen(context), REMOTE_SLOT)))
                .waitTicks(SETTLE_TICKS)
                .shot("remote-item-tooltip" + suffix);
    }

    /**
     * A click on the iron in the stock list from afar: the crane brings it into the terminal's buffer and nothing to the
     * player. A click on the buffer slot then takes nothing out.
     */
    private void requestSteps(VisualScript script, String suffix) {
        amount(script, IRON_REQUESTED);
        script.server("remote: census before the request", (server, context) -> assertCensus(server, context,
                        "before the request from afar"))
                .client("remote: click the iron in the stock list", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    if (screen.craftPanel().hasGhost())
                        throw new VisualTestException("a ghost is on the cursor, so the click would pick, not request");
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCell(screen, stockCell(context, IRON)));
                    if (screen.isAskingSomething())
                        throw new VisualTestException("the request for the iron raised a question");
                })
                .serverUntil("remote: wait until the crane delivered the iron into the buffer",
                        (server, context) -> bufferIs(server, context, Map.of(IRON, (long) IRON_REQUESTED))
                                && controller(server, context).requestsFor(terminalPos(context)).isEmpty(),
                        DELIVERY_TIMEOUT_TICKS)
                .server("remote: CHECK the iron is in the buffer and nothing reached the player", (server, context) -> {
                    assertCensus(server, context, "after the delivery requested from afar");
                    checkOnlyTheRemote(context.serverPlayer(server), "after the delivery");
                    LOGGER.info(PREFIX + "remote: CHECK PASS {} iron requested from afar are in the terminal's buffer, "
                            + "the player holds only the remote", IRON_REQUESTED);
                });
        ArmVisualScenario.untilOrFail(script, "remote: the screen shows the iron in the buffer",
                context -> bufferSlot(context, IRON) >= 0, SCREEN_TIMEOUT_TICKS,
                context -> "the screen's buffer shows nothing of the iron");
        terminalShot(script, "remote-delivered" + suffix);
        script.client("remote: click the iron in the buffer", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    ScreenInput.click(context.minecraft(), ScreenInput.menuSlot(screen, bufferSlot(context, IRON)));
                    if (!screen.getMenu().getCarried().isEmpty())
                        throw new VisualTestException("the client put " + screen.getMenu().getCarried()
                                + " on the cursor");
                })
                .waitTicks(NO_CHANGE_TICKS)
                .client("remote: CHECK the click took nothing on the client", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    int slot = bufferSlot(context, IRON);
                    if (!screen.getMenu().getCarried().isEmpty() || slot < 0
                            || screen.getMenu().slots.get(slot).getItem().getCount() != IRON_REQUESTED)
                        throw new VisualTestException("the click on the buffer moved something: cursor "
                                + screen.getMenu().getCarried() + ", buffer slot " + slot);
                })
                .server("remote: CHECK the click took nothing on the server", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    if (!serverMenu(player).getCarried().isEmpty())
                        throw new VisualTestException("the server put " + player.containerMenu.getCarried()
                                + " on the cursor");
                    if (!bufferIs(server, context, Map.of(IRON, (long) IRON_REQUESTED)))
                        throw new VisualTestException("the buffer changed: "
                                + terminal(server.overworld(), context.origin()).bufferedItems());
                    assertCensus(server, context, "after the click on the buffer from afar");
                    LOGGER.info(PREFIX + "remote: CHECK PASS a click on the buffer from afar took nothing");
                });
    }

    /**
     * The crafting panel from afar: a recipe can be laid and previewed, but the result cell says to craft in person and
     * a click on it crafts nothing. The panel is cleared and folded in again by real clicks.
     */
    private void craftSteps(VisualScript script, String suffix) {
        script.client("remote: click the crafting toggle", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCraftToggle(screen(context))))
                .until("remote: the panel is folded out", context -> screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS)
                .client("remote: right-click the iron in the stock list to pick it as a ghost", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    ScreenInput.rightClick(context.minecraft(), ScreenInput.terminalCell(screen,
                            stockCell(context, IRON)));
                });
        ArmVisualScenario.untilOrFail(script, "remote: the iron is the ghost on the cursor",
                context -> screen(context).craftPanel().ghost().filter(IRON::equals).isPresent(), SETTLE_TICKS,
                context -> "the ghost is " + screen(context).craftPanel().ghost());
        script.client("remote: stamp the ghost into cell 0", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCraftCell(screen(context), 0)))
                .client("remote: press Escape to drop the ghost", context -> {
                    if (!screen(context).craftPanel().hasGhost())
                        throw new VisualTestException("no ghost on the cursor: Escape would close the screen");
                    ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE);
                });
        ArmVisualScenario.untilOrFail(script, "remote: the server previews the iron nuggets",
                context -> CraftVisualScenario.previewShows(context, NUGGET_GRID, Items.IRON_NUGGET),
                PREVIEW_TIMEOUT_TICKS, context -> context.minecraft().screen instanceof WarehouseTerminalScreen screen
                        ? CraftVisualScenario.describePanel(screen) : "the screen is " + context.minecraft().screen);
        script.client("remote: rest the mouse on the result", context -> ScreenInput.hover(context.minecraft(),
                        ScreenInput.terminalCraftResult(screen(context))))
                .client("remote: CHECK the result's tooltip says to craft in person",
                        RemoteVisualScenario::checkResultTooltip)
                .waitTicks(SETTLE_TICKS)
                .shot("remote-craft-tooltip" + suffix)
                .server("remote: census before the click on the result", (server, context) -> assertCensus(server,
                        context, "before the click on the result from afar"))
                .client("remote: click the result", context -> {
                    bassesBefore = context.soundCount(BASS);
                    ScreenInput.click(context.minecraft(), ScreenInput.terminalCraftResult(screen(context)));
                })
                .waitTicks(NO_CHANGE_TICKS)
                .client("remote: CHECK the click on the result was refused on the client", context -> {
                    int basses = context.soundCount(BASS) - bassesBefore;
                    if (basses != 1)
                        throw new VisualTestException("the click on the result played " + basses + " refusal basses, "
                                + "not one; heard " + context.soundCounts());
                    if (!screen(context).getMenu().getCarried().isEmpty())
                        throw new VisualTestException("the click on the result put "
                                + screen(context).getMenu().getCarried() + " on the cursor");
                })
                .server("remote: CHECK the click on the result crafted nothing", (server, context) -> {
                    if (!bufferIs(server, context, Map.of(IRON, (long) IRON_REQUESTED)))
                        throw new VisualTestException("the buffer changed: "
                                + terminal(server.overworld(), context.origin()).bufferedItems());
                    assertCensus(server, context, "after the click on the result from afar");
                    checkOnlyTheRemote(context.serverPlayer(server), "after the click on the result");
                    LOGGER.info(PREFIX + "remote: CHECK PASS a click on the result from afar crafted nothing");
                })
                .client("remote: click the Clear button", context -> ScreenInput.click(context.minecraft(),
                        ScreenInput.terminalCraftClear(screen(context))))
                .until("remote: the grid is empty", context -> screen(context).craftPanel().grid().isEmpty(),
                        SETTLE_TICKS)
                .client("remote: click the crafting toggle to fold the panel in", context -> ScreenInput.click(
                        context.minecraft(), ScreenInput.terminalCraftToggle(screen(context))))
                .until("remote: the panel is folded in", context -> !screen(context).isCraftPanelOpen(),
                        SCREEN_TIMEOUT_TICKS);
    }

    /**
     * The hotbar key over an empty inventory slot moves the remote out of the hand, and the server closes the screen.
     * Then the remote goes back into the hand on the server thread.
     */
    private void putAwaySteps(VisualScript script, String suffix) {
        script.client("remote: rest the mouse on an empty inventory slot", context -> ScreenInput.hover(
                        context.minecraft(), ScreenInput.menuSlot(screen(context), putAwaySlot(context))))
                .waitTicks(SETTLE_TICKS)
                .client("remote: press hotbar key 1 over the empty slot", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    Slot hovered = screen.getSlotUnderMouse();
                    if (hovered == null || hovered.index != putAwaySlot(context) || hovered.hasItem())
                        throw new VisualTestException("the mouse is not on the empty inventory slot: " + hovered);
                    ScreenInput.key(context.minecraft(),
                            context.minecraft().options.keyHotbarSlots[REMOTE_SLOT].getKey().getValue());
                });
        ArmVisualScenario.untilOrFail(script, "remote: the screen closes once the remote left the hand",
                context -> context.minecraft().screen == null, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is still " + context.minecraft().screen);
        script.server("remote: CHECK the remote is in the inventory and the menu is closed", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    Inventory inventory = player.getInventory();
                    if (player.containerMenu != player.inventoryMenu)
                        throw new VisualTestException("the server still has " + player.containerMenu + " open");
                    if (!inventory.getItem(REMOTE_SLOT).isEmpty()
                            || WarehouseRemoteItem.link(inventory.getItem(PUT_AWAY_SLOT)) == null)
                        throw new VisualTestException("the remote is not linked in inventory slot " + PUT_AWAY_SLOT
                                + ": hand " + inventory.getItem(REMOTE_SLOT) + ", slot "
                                + inventory.getItem(PUT_AWAY_SLOT));
                    assertCensus(server, context, "after putting the remote away");
                    LOGGER.info(PREFIX + "remote: CHECK PASS putting the remote away closed the screen");
                })
                .shotWithGui("remote-put-away" + suffix)
                .server("remote: put the remote back into the hand (the census does not move)", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    Inventory inventory = player.getInventory();
                    inventory.setItem(REMOTE_SLOT, inventory.getItem(PUT_AWAY_SLOT));
                    inventory.setItem(PUT_AWAY_SLOT, ItemStack.EMPTY);
                    player.inventoryMenu.broadcastChanges();
                    assertCensus(server, context, "after putting the remote back into the hand");
                });
        ArmVisualScenario.untilOrFail(script, "remote: the client holds the remote again",
                context -> WareworksItems.WAREHOUSE_REMOTE.isIn(context.minecraft().player.getMainHandItem()),
                HELD_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem());
    }

    /** A sneaking use into the air clears the link. */
    private void unlinkSteps(VisualScript script, String suffix) {
        sneak(script, true);
        skyAim(script);
        useKey(script, "into the air while sneaking");
        ArmVisualScenario.untilOrFail(script, "remote: the client's remote is unlinked",
                context -> WarehouseRemoteItem.link(context.minecraft().player.getMainHandItem()) == null,
                MESSAGE_TIMEOUT_TICKS, context -> "the remote in hand is "
                        + context.minecraft().player.getMainHandItem());
        actionBar(script, "the unlink", context -> WareworksLang.translateDirect(WareworksLang.REMOTE_UNLINKED));
        script.server("remote: CHECK the server's remote is unlinked", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            if (WarehouseRemoteItem.link(player.getMainHandItem()) != null
                    || player.containerMenu != player.inventoryMenu)
                throw new VisualTestException("the sneaking use left " + player.getMainHandItem() + " and opened "
                        + player.containerMenu);
            declareRemote(server, context, "the remote was unlinked");
            LOGGER.info(PREFIX + "remote: CHECK PASS a sneaking use into the air unlinked the remote");
        });
        sneak(script, false);
        script.shotWithGui("remote-unlinked" + suffix);
    }

    /** Takes everything out of the buffer for the next round; declared in the census. */
    private void emptyBuffer(VisualScript script) {
        script.server("remote: empty the buffer for the next round (declared in the census)", (server, context) -> {
            Map<ItemKey, Long> next = expected;
            IItemHandler handler = terminal(server.overworld(), context.origin()).menuBuffer();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                for (ItemStack taken = handler.extractItem(slot, Item.ABSOLUTE_MAX_STACK_SIZE, false);
                        !taken.isEmpty(); taken = handler.extractItem(slot, Item.ABSOLUTE_MAX_STACK_SIZE, false))
                    next = SceneItemCensus.plus(next, ItemKey.of(taken), -taken.getCount());
            }
            expected = next;
            assertCensus(server, context, "the scenario emptied the buffer for the next round");
        });
    }

    // --- gestures (client thread) ------------------------------------------------------------------------------------

    /**
     * Holds or releases the sneak key, and waits until the client crouches and the server has heard it: the server
     * learns it from the player's next movement packet, which a click in the same tick would overtake.
     */
    private static void sneak(VisualScript script, boolean down) {
        String what = down ? "sneaks" : "stands up";
        script.client("remote: " + (down ? "hold" : "release") + " the sneak key",
                context -> context.minecraft().options.keyShift.setDown(down));
        ArmVisualScenario.untilOrFail(script, "remote: the client " + what,
                context -> context.minecraft().player.isShiftKeyDown() == down, SNEAK_TIMEOUT_TICKS,
                context -> "the client's sneak state is " + context.minecraft().player.isShiftKeyDown());
        script.serverUntil("remote: the server sees that the player " + what,
                (server, context) -> context.serverPlayer(server).isShiftKeyDown() == down, SNEAK_TIMEOUT_TICKS);
    }

    /** One press of the use key, the way the mouse handler does it, and a wait until the client handled it. */
    private static void useKey(VisualScript script, String what) {
        script.client("remote: right-click " + what,
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        ArmVisualScenario.untilOrFail(script, "remote: the client handled the right-click " + what,
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
    }

    /** Fails unless the crosshair hits nothing, so a use goes into the air and reaches the remote's own use. */
    private static void skyAim(VisualScript script) {
        ArmVisualScenario.untilOrFail(script, "remote: the crosshair is on the sky",
                context -> context.minecraft().hitResult != null
                        && context.minecraft().hitResult.getType() == HitResult.Type.MISS,
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context));
    }

    /** Waits until the action bar shows {@code message} in the current language. */
    private static void actionBar(VisualScript script, String what,
            Function<VisualContext, Component> message) {
        ArmVisualScenario.untilOrFail(script, "remote: the action bar tells about " + what,
                context -> ArmVisualScenario.overlayText(context).equals(message.apply(context).getString()),
                MESSAGE_TIMEOUT_TICKS, context -> "the action bar reads '" + ArmVisualScenario.overlayText(context)
                        + "', not '" + message.apply(context).getString() + "'");
        script.client("remote: CHECK the action bar after " + what, context -> LOGGER.info(PREFIX
                + "remote: CHECK PASS the action bar reads '{}'", ArmVisualScenario.overlayText(context)));
    }

    /** The amount field to {@code amount}, one notch of the wheel per tick. */
    private static void amount(VisualScript script, int amount) {
        ArmVisualScenario.untilOrFail(script, "remote: scroll the amount field to " + amount, context -> {
            ScrollInput input = ScreenInput.terminalAmount(screen(context));
            int state = input.getState();
            if (state == amount)
                return true;
            ScreenInput.scroll(context.minecraft(), ScreenInput.centre(input), state < amount ? 1 : -1);
            return false;
        }, AMOUNT_TIMEOUT_TICKS, context -> "the amount field shows "
                + ScreenInput.terminalAmount(screen(context)).getState());
    }

    /** A shot of the terminal with the mouse in its title row. */
    private static void terminalShot(VisualScript script, String label) {
        script.client("remote: park the mouse in the terminal's title row", context -> {
                    WarehouseTerminalScreen screen = screen(context);
                    ScreenInput.hover(context.minecraft(), new ScreenInput.Point(screen.getGuiLeft() + TITLE_CORNER,
                            screen.getGuiTop() + TITLE_CORNER));
                })
                .waitTicks(SETTLE_TICKS)
                .shot(label);
    }

    // --- client checks -----------------------------------------------------------------------------------------------

    /** The held remote's tooltip has the line {@code line}. */
    private static void checkTooltip(VisualContext context, String line) {
        Minecraft minecraft = context.minecraft();
        LocalPlayer player = Objects.requireNonNull(minecraft.player, "player");
        List<String> lines = player.getMainHandItem().getTooltipLines(Item.TooltipContext.of(minecraft.level), player,
                TooltipFlag.Default.NORMAL).stream().map(Component::getString).toList();
        if (!lines.contains(line))
            throw new VisualTestException("the remote's tooltip is " + lines + ", without the line '" + line + "'");
        LOGGER.info(PREFIX + "remote: CHECK PASS the remote's tooltip has the line '{}'", line);
    }

    /**
     * The result cell's tooltip from afar: the nuggets the buffer's iron makes, and as its last line the red sentence that
     * says to craft in person, in place of the two click hints.
     */
    private static void checkResultTooltip(VisualContext context) {
        TerminalCraftPanel panel = screen(context).craftPanel();
        if (panel.readyCrafts() != IRON_REQUESTED)
            throw new VisualTestException("the panel counts " + panel.readyCrafts() + " crafts ready, not "
                    + IRON_REQUESTED);
        List<Component> tooltip = panel.resultTooltip();
        List<String> lines = tooltip.stream().map(Component::getString).toList();
        String remote = WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_REMOTE).getString();
        List<String> hints = List.of(WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_ONCE).getString(),
                WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_ALL).getString());
        if (tooltip.isEmpty() || !lines.getLast().equals(remote)
                || !Objects.equals(tooltip.getLast().getStyle().getColor(), TextColor.fromLegacyFormat(ChatFormatting.RED)))
            throw new VisualTestException("the result's tooltip is " + lines + ", not ending with the red line '"
                    + remote + "'");
        if (lines.stream().anyMatch(hints::contains))
            throw new VisualTestException("the result's tooltip from afar still offers the clicks: " + lines);
        LOGGER.info(PREFIX + "remote: CHECK PASS the result's tooltip from afar is {}", lines);
    }

    /**
     * Checks how the title row is shared: the screen's title on the left, the warehouse (or a chosen door) on the
     * right, at least {@value #TITLE_GAP} px apart within the {@value #ROW_WIDTH} px row, measured with the client's
     * real font. The title opened from afar is not the plain one, so this run is where a collision would show; the
     * JUnit twin is {@code TerminalTitleRowWidthTest}.
     */
    private static void measureTitleRow(VisualContext context, WarehouseTerminalScreen screen) {
        int title = context.minecraft().font.width(screen.getTitle());
        int label = context.minecraft().font.width(screen.titleRowLabel());
        int used = title + TITLE_GAP + label;
        if (used > ROW_WIDTH)
            throw new VisualTestException("the title row overlaps: '" + screen.getTitle().getString() + "' " + title
                    + " px + " + TITLE_GAP + " px + '" + screen.titleRowLabel().getString() + "' " + label + " px = "
                    + used + " of " + ROW_WIDTH + " px");
        LOGGER.info(PREFIX + "remote: CHECK PASS the title row: '{}' {} px + {} px + '{}' {} px = {} of {} px",
                screen.getTitle().getString(), title, TITLE_GAP, screen.titleRowLabel().getString(), label, used,
                ROW_WIDTH);
    }

    private static boolean screenReady(VisualContext context) {
        return context.minecraft().screen instanceof WarehouseTerminalScreen terminal && terminal.getMenu().isRemote()
                && terminal.hasStock() && terminal.matchingEntries().size() >= ITEM_TYPES;
    }

    private static WarehouseTerminalScreen screen(VisualContext context) {
        Screen open = context.minecraft().screen;
        if (open instanceof WarehouseTerminalScreen terminal)
            return terminal;
        throw new VisualTestException("the terminal screen is not open (screen: " + open + ")");
    }

    /** The visible stock cell showing {@code key}. */
    private static int stockCell(VisualContext context, ItemKey key) {
        List<StockLine<ItemKey>> visible = screen(context).visibleEntries();
        for (int cell = 0; cell < visible.size(); cell++)
            if (visible.get(cell).key().equals(key))
                return cell;
        throw new VisualTestException("the stock list does not show " + key);
    }

    /** The menu slot index of the buffer slot the screen shows {@code key} in, or -1. */
    private static int bufferSlot(VisualContext context, ItemKey key) {
        WarehouseTerminalMenu menu = screen(context).getMenu();
        for (int slot = 0; slot < menu.bufferSlots(); slot++)
            if (!menu.slots.get(slot).getItem().isEmpty() && ItemKey.of(menu.slots.get(slot).getItem()).equals(key))
                return slot;
        return -1;
    }

    /** The menu slot index of the main inventory slot the remote is put away into. */
    private static int putAwaySlot(VisualContext context) {
        for (Slot slot : screen(context).getMenu().slots)
            if (slot.container instanceof Inventory && slot.getContainerSlot() == PUT_AWAY_SLOT)
                return slot.index;
        throw new VisualTestException("the screen shows no slot for inventory slot " + PUT_AWAY_SLOT);
    }

    private static boolean clientHasTerminalChunk(VisualContext context) {
        BlockPos pos = terminalPos(context);
        return context.minecraft().level != null && context.minecraft().level.getChunkSource()
                .hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    // --- views -------------------------------------------------------------------------------------------------------

    /** Standing on the ground behind the terminal, looking at its back face; within the vanilla reach. */
    private CameraView linkView(VisualContext context) {
        BlockPos dock = context.origin();
        Vec3 centre = Vec3.atLowerCornerOf(terminalPos(context).subtract(dock)).add(0.5, 0.0, 0.5);
        Direction outward = layout(dock).sideDirection(TERMINAL.side());
        Vec3 eye = centre.add(outward.getStepX() * LINK_DISTANCE, linkGround + Player.DEFAULT_EYE_HEIGHT,
                outward.getStepZ() * LINK_DISTANCE);
        Vec3 target = centre.add(outward.getStepX() * 0.5, 0.6, outward.getStepZ() * 0.5);
        return new CameraView("link", eye, target);
    }

    /**
     * Standing on the ground {@code blocks} down the aisle line from the terminal, looking up into the sky along it, so
     * the crosshair hits nothing.
     */
    private static CameraView farView(VisualContext context, double blocks, int ground) {
        BlockPos dock = context.origin();
        Vec3 centre = Vec3.atLowerCornerOf(terminalPos(context).subtract(dock)).add(0.5, 0.0, 0.5);
        Vec3 eye = centre.add(AISLE.getStepX() * blocks, ground + Player.DEFAULT_EYE_HEIGHT, AISLE.getStepZ() * blocks);
        Vec3 target = eye.add(AISLE.getStepX() * 10.0, 6.0, AISLE.getStepZ() * 10.0);
        return new CameraView(String.format(Locale.ROOT, "far-%.0f", blocks), eye, target);
    }

    // --- server thread -----------------------------------------------------------------------------------------------

    private void placeMotor(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(DOCK_X, level.getMinBuildHeight(), DOCK_Z);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos dock = new BlockPos(DOCK_X, level.getHeight(Heightmap.Types.WORLD_SURFACE, DOCK_X, DOCK_Z), DOCK_Z);
        context.setOrigin(dock);
        censusBox = AABB.encapsulatingFullBlocks(dock.offset(-CLEAR_MARGIN, -1, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN));
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = SectionPos.blockToSectionCoord(dock.getX() - CLEAR_MARGIN);
                x <= SectionPos.blockToSectionCoord(dock.getX() + RAILS + CLEAR_MARGIN); x++)
            for (int z = SectionPos.blockToSectionCoord(dock.getZ() - CLEAR_MARGIN);
                    z <= SectionPos.blockToSectionCoord(dock.getZ() + CLEAR_MARGIN); z++)
                chunks.add(new ChunkPos(x, z));
        forcedChunks = List.copyOf(chunks);
        for (BlockPos pos : BlockPos.betweenClosed(dock.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN),
                dock.offset(RAILS + CLEAR_MARGIN, CLEAR_HEIGHT, CLEAR_MARGIN)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dock.below(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.UP));
    }

    private static void buildAisle(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        CreativeMotorBlockEntity motor = AllBlockEntityTypes.MOTOR.getNullable(level, dock.below());
        if (motor == null)
            throw new VisualTestException("the creative motor below the dock is missing");
        motor.generatedSpeed.setValue(MOTOR_RPM);
        level.setBlockAndUpdate(dock, WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, AISLE));
        for (int x = 1; x <= RAILS; x++)
            level.setBlockAndUpdate(dock.relative(AISLE, x), WareworksBlocks.WAREHOUSE_RAIL.getDefaultState()
                    .setValue(WarehouseRailBlock.AXIS, AISLE.getAxis()));
        level.setBlockAndUpdate(dock.relative(AISLE.getOpposite()), WareworksBlocks.WAREHOUSE_CONTROLLER
                .getDefaultState().setValue(WarehouseControllerBlock.FACING, AISLE));
        BranchLayout layout = layout(dock);
        level.setBlockAndUpdate(layout.rackPos(TERMINAL), WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                .setValue(WarehouseTerminalBlock.FACING, layout.sideDirection(TERMINAL.side()).getOpposite()));
        int next = 0;
        for (Side side : Side.values()) {
            Direction outward = layout.sideDirection(side);
            for (int x = STORAGE_FIRST_POSITION; x <= RAILS; x++) {
                for (int y = 0; y < STORAGE_LEVELS; y++) {
                    BlockPos rack = layout.rackPos(RackPosition.of(x, y, side));
                    BlockPos chest = rack.relative(outward);
                    level.setBlockAndUpdate(chest,
                            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, outward.getOpposite()));
                    level.setBlockAndUpdate(rack, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                            .setValue(WarehouseInterfaceBlock.FACING, outward));
                    if (next < STOCK.size())
                        CraftVisualScenario.fillChest(level, chest, STOCK.get(next++));
                }
            }
        }
        if (next < STOCK.size())
            throw new VisualTestException("only " + next + " of " + STOCK.size() + " item types fit into the racks");
    }

    /**
     * The run needs the default {@code remote.range} between its two far spots, and the ground under every spot the
     * player stands on; the far spots' chunks are generated here, once.
     */
    private void measureGround(MinecraftServer server, VisualContext context) {
        int configured = WareworksConfig.remoteRange();
        if (configured <= IN_RANGE_BLOCKS + 1 || configured >= OUT_OF_RANGE_BLOCKS - 1)
            throw new VisualTestException("the remote scenario needs remote.range between " + IN_RANGE_BLOCKS
                    + " and " + OUT_OF_RANGE_BLOCKS + " blocks (the default is " + RemoteLimits.DEFAULT_RANGE
                    + "), and it is " + configured);
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        BlockPos terminal = layout(dock).rackPos(TERMINAL);
        BlockPos behind = terminal.relative(layout(dock).sideDirection(TERMINAL.side()), (int) LINK_DISTANCE);
        linkGround = groundAt(level, behind) - dock.getY();
        inRangeGround = groundAt(level, terminal.relative(AISLE, (int) IN_RANGE_BLOCKS)) - dock.getY();
        outOfRangeGround = groundAt(level, terminal.relative(AISLE, (int) OUT_OF_RANGE_BLOCKS)) - dock.getY();
        LOGGER.info(PREFIX + "remote: CHECK PASS remote.range is {}; ground at the link spot {}, at {} blocks {}, at {} "
                + "blocks {} (relative to the dock)", configured, linkGround, IN_RANGE_BLOCKS, inRangeGround,
                OUT_OF_RANGE_BLOCKS, outOfRangeGround);
    }

    /** The first free block above the ground at {@code pos}'s column; loads its chunk, since an unloaded one has none. */
    private static int groundAt(ServerLevel level, BlockPos pos) {
        return level.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                .getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15) + 1;
    }

    private boolean sceneReady(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos dock = context.origin();
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER.getNullable(level,
                dock.relative(AISLE.getOpposite()));
        StackerCraneBlockEntity crane = WareworksBlockEntityTypes.STACKER_CRANE.getNullable(level, dock);
        if (controller == null || crane == null || !SceneItemCensus.isFullyLoaded(level, censusBox))
            return false;
        int expectedStorage = (RAILS - STORAGE_FIRST_POSITION + 1) * STORAGE_LEVELS * Side.values().length;
        return controller.status() == ControllerStatus.READY && !controller.isMembershipDirty()
                && controller.pendingSnapshotCount() == 0 && controller.storageLocations().size() == expectedStorage
                && controller.outputStations().size() == 1 && crane.isControllerLinked()
                && controller.stockIndex().distinctKeys() >= ITEM_TYPES;
    }

    private void force(MinecraftServer server, boolean force) {
        ServerLevel level = server.overworld();
        for (ChunkPos chunk : forcedChunks)
            level.setChunkForced(chunk.x, chunk.z, force);
        LOGGER.info(PREFIX + "remote: chunks {} forced={}", forcedChunks, force);
    }

    /** An empty inventory and a remote in the first hotbar slot, which is the selected one. */
    private void giveRemote(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getInventory().clearContent();
        player.getInventory().selected = REMOTE_SLOT;
        ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
        player.getInventory().setItem(REMOTE_SLOT, remote);
        remoteKey = ItemKey.of(remote);
        player.connection.send(new ClientboundSetCarriedItemPacket(REMOTE_SLOT));
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * The remote's link changed, and with it its item key: the census must be what it was with the old key swapped for
     * the new one, and nothing else.
     */
    private void declareRemote(MinecraftServer server, VisualContext context, String step) {
        ItemStack held = context.serverPlayer(server).getMainHandItem();
        if (!WareworksItems.WAREHOUSE_REMOTE.isIn(held))
            throw new VisualTestException("the player does not hold the remote: " + held);
        Map<ItemKey, Long> next = SceneItemCensus.plus(expected, Objects.requireNonNull(remoteKey, "remoteKey"), -1);
        remoteKey = ItemKey.of(held);
        expected = SceneItemCensus.plus(next, remoteKey, 1);
        assertCensus(server, context, step);
    }

    /** The player's inventory and cursors hold the remote and nothing else. */
    private static void checkOnlyTheRemote(ServerPlayer player, String when) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !WareworksItems.WAREHOUSE_REMOTE.isIn(stack))
                throw new VisualTestException(when + " the player has " + stack + " in slot " + slot);
        }
        if (!player.containerMenu.getCarried().isEmpty() || !player.inventoryMenu.getCarried().isEmpty())
            throw new VisualTestException(when + " the player's cursor holds " + player.containerMenu.getCarried());
    }

    /** Whether the buffer holds exactly {@code counts} and nothing else. */
    private static boolean bufferIs(MinecraftServer server, VisualContext context, Map<ItemKey, Long> counts) {
        InventorySnapshot<ItemKey> buffered = terminal(server.overworld(), context.origin()).bufferedItems();
        if (!buffered.keys().equals(counts.keySet()))
            return false;
        for (Map.Entry<ItemKey, Long> entry : counts.entrySet())
            if (buffered.count(entry.getKey()) != entry.getValue())
                return false;
        return true;
    }

    private void assertCensus(MinecraftServer server, VisualContext context, String step) {
        SceneItemCensus.assertEquals(server.overworld(), censusBox, expected, "remote: " + step,
                context.serverPlayer(server));
    }

    private static WarehouseTerminalMenu serverMenu(ServerPlayer player) {
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu))
            throw new VisualTestException("the player has no terminal menu open on the server: " + player.containerMenu);
        return menu;
    }

    // --- lookups -----------------------------------------------------------------------------------------------------

    private static BranchLayout layout(BlockPos dock) {
        return BranchLayout.of(dock, AISLE, AisleGeometry.of(RAILS, StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT));
    }

    private static BlockPos terminalPos(VisualContext context) {
        return layout(context.origin()).rackPos(TERMINAL);
    }

    private static WarehouseTerminalBlockEntity terminal(ServerLevel level, BlockPos dock) {
        WarehouseTerminalBlockEntity terminal = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.getNullable(level,
                layout(dock).rackPos(TERMINAL));
        if (terminal == null)
            throw new VisualTestException("the warehouse terminal of the aisle is missing");
        return terminal;
    }

    private static WarehouseControllerBlockEntity controller(MinecraftServer server, VisualContext context) {
        WarehouseControllerBlockEntity controller = WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER
                .getNullable(server.overworld(), context.origin().relative(AISLE.getOpposite()));
        if (controller == null)
            throw new VisualTestException("the controller of the aisle is missing");
        return controller;
    }
}
