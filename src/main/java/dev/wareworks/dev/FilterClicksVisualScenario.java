package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.StorageFilterClicks;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Scenario "filterclicks": the store filter slot of a warehouse interface, a rack bay and a fluid bay under <b>real
 * input</b> (issue #30, ADR-028) — the client half the GameTests ({@code gametest.StorageFilterClickGameTests}) cannot
 * reach, because Create's warmup, the priority board and the press guard all live on the client.
 * <p>
 * The player is a creative player with the vanilla reach, flying so the camera views hold, and every click is the
 * use key the way the mouse handler drives it: {@code KeyMapping#set} and {@code KeyMapping#click} on a press,
 * {@code KeyMapping#set(false)} on the release, so a <b>held</b> button is held for real ticks and vanilla repeats it
 * every four. On each block, aimed at the centre of the slot and checked to be on it:
 * <ol>
 * <li>the hover tip's third line is the translated "hold with an empty hand ... higher fills first", and no line of
 * it is wider than {@value #MIN_GUI_WIDTH} GUI px, the narrowest GUI any window gets ({@code Window#calculateScale}):
 * Create's {@code ValueSettingsClient#render} draws each line centred with one {@code drawString} and never wraps;</li>
 * <li>an item held for {@value #HOLD_TICKS} ticks sets the filter and opens <b>no</b> board — before the fix the board
 * opened after five ticks and no filter was set;</li>
 * <li>(the interface) an item pressed, the crosshair moved off the slot and only then released still sets the filter —
 * before the fix such a click was dropped;</li>
 * <li>a List Filter pressed, the hand emptied while the button stays down (in creative a filter item stays in the hand,
 * so the hotbar key does what the slot does to a survival player) and the button held for
 * {@value #GUARD_HOLD_TICKS} ticks: no board opens and the filter is still the List Filter after the release
 * ({@code client.input.StorageFilterPressGuard});</li>
 * <li>a short click with an empty hand clears the filter, through Create's warmup as before;</li>
 * <li>holding an empty hand opens the priority board, and on the interface so does holding the wrench, and an item
 * scrolled to while an empty hand's warmup runs is not taken as the filter;</li>
 * <li>the button pressed and held with an item on the face <b>above</b> the slot (an iron ingot on the interface,
 * which does nothing there, cobblestone into the rack bay and a water bucket into the fluid bay, which each take one
 * per repeat), then the crosshair slid down onto the slot and held there for {@value #SLOT_HOLD_TICKS} ticks, three
 * of vanilla's four-tick repeats: no filter is set, no screen opens and nothing more goes into the bay — only a press
 * sets a filter, not a repeat that reaches the slot ({@code client.input.StorageFilterPressGuard});</li>
 * <li>a plain click with the item, the button never held (how a scripted click arrives), sets the filter again.</li>
 * </ol>
 * The hover tip is read again in German, and in both languages the rack bay's Ponder scene "Rack Bays in a Warehouse
 * Aisle" is sought to the keyframe whose text says how the priority is set and which way it ranks: that text must be
 * the translated one and is photographed ({@code ponder-priority}, {@code ponder-priority-de}); the fixed 20/60/90 %
 * moments of the {@code ponder} scenario do not land on it. Every filter is read on the <b>server</b>, so a client
 * that only drew a filter fails the run.
 */
public final class FilterClicksVisualScenario implements VisualScenario {
    public static final String NAME = "filterclicks";

    private static final String WORLD_FOLDER = "wareworks_visual_filterclicks";
    /** Three blocks with seven checks each and two language switches: above the default budget. */
    private static final long RUN_TIMEOUT_MILLIS = 8L * 60L * 1000L;

    // --- the scene (offsets from the origin, the block under the interface) ------------------------------------------
    /** Every block faces north, into the rack depth, so its aisle face and filter slot are on the south. */
    private static final Direction FACING = Direction.NORTH;
    private static final Direction AISLE_FACE = FACING.getOpposite();
    /**
     * The row stands one block up, on stone: a camera aimed at a slot 5 px above the ground would put the player's
     * feet into the floor, and a flying player that touches the ground stops flying ({@code LocalPlayer#aiStep}).
     */
    private static final BlockPos INTERFACE = new BlockPos(0, 1, 0);
    private static final BlockPos CHEST = INTERFACE.relative(FACING);
    private static final BlockPos RACK_BAY = new BlockPos(2, 1, 0);
    private static final BlockPos FLUID_BAY = new BlockPos(4, 1, 0);
    /** Where the player is lifted to before it starts flying: in the air in front of the row. */
    private static final BlockPos LIFT = new BlockPos(2, 4, 4);
    private static final int CLEAR_MIN_X = -3;
    private static final int CLEAR_MAX_X = 7;
    private static final int CLEAR_MIN_Z = -3;
    private static final int CLEAR_MAX_Z = 6;
    private static final int CLEAR_HEIGHT = 6;
    private static final double BLOCK_CENTER = 0.5;
    private static final double PIXEL = 1.0 / 16.0;
    /**
     * The narrowest GUI in GUI px at any GUI scale a player can pick: {@code Window#calculateScale} keeps the width at
     * 320 or more. Create's hover tip is drawn centred and unwrapped, so a wider line runs off both edges.
     */
    private static final int MIN_GUI_WIDTH = 320;
    /** The eye stands this far in front of the face and this far above the slot. */
    private static final Vec3 EYE_OFFSET = new Vec3(0.0, 1.5, 2.2);
    /** Pitched up by this much from the slot, the crosshair lands on the same face about 8 px above its centre. */
    private static final float DRIFT_DEGREES = 10.0F;
    private static final CameraView ROW = CameraView.of("row", 2.5, 3.2, 4.6, 2.5, 1.4, 0.5);

    // --- hotbar ------------------------------------------------------------------------------------------------------
    private static final int SLOT_COBBLESTONE = 0;
    private static final int SLOT_IRON = 1;
    private static final int SLOT_LIST_FILTER = 2;
    private static final int SLOT_WATER = 3;
    private static final int SLOT_WRENCH = 4;
    /** Never filled: selecting it gives the player an empty hand. */
    private static final int SLOT_EMPTY = 8;
    private static final int HELD_STACK = 16;

    // --- timing ------------------------------------------------------------------------------------------------------
    /** Four times Create's warmup: its board opens once a held click has lasted more than five ticks. */
    private static final int HOLD_TICKS = 20;
    /** Four of vanilla's four-tick repeats with an empty hand, each of which used to start Create's warmup. */
    private static final int GUARD_HOLD_TICKS = 16;
    /** Held on the face beside the slot past vanilla's first four-tick repeat, so the face has had a repeat too. */
    private static final int FACE_HOLD_TICKS = 6;
    /** Held on the slot for three of vanilla's four-tick repeats, any of which used to set the filter. */
    private static final int SLOT_HOLD_TICKS = 12;
    /** A use sent from the face just before the slide reaches the server within this many ticks. */
    private static final int SLIDE_SETTLE_TICKS = 3;
    private static final int BOARD_TIMEOUT_TICKS = 20;
    private static final int FILTER_TIMEOUT_TICKS = 20;
    private static final int HOVER_TIP_TIMEOUT_TICKS = 40;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int CLICK_TIMEOUT_TICKS = 20;
    private static final int HELD_ITEM_TIMEOUT_TICKS = 100;
    private static final int LANGUAGE_RELOAD_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 6;
    /** Ticks into Create's warmup (five) at which the hotbar is scrolled, before vanilla's first repeat. */
    private static final int WARMUP_SCROLL_TICKS = 2;
    private static final String GERMAN = "de_de";
    private static final String ENGLISH = "en_us";

    // --- the Ponder line ---------------------------------------------------------------------------------------------
    private static final ResourceLocation PRIORITY_SCENE = Wareworks.asResource("bays_in_an_aisle");
    /** The scene's sixth text, the one that names the empty hand and the order. */
    private static final String PRIORITY_TEXT_KEY = Wareworks.ID + ".ponder.bays_in_an_aisle.text_6";
    /** The rack bay carries three scenes; the priority one is among them. */
    private static final int RACK_BAY_SCENES = 3;
    /** A text window is shown from its keyframe on; this far in, it has faded in. */
    private static final int PONDER_TEXT_LEAD_TICKS = 10;
    private static final int PONDER_OPEN_TIMEOUT_TICKS = 200;
    private static final int PONDER_SETTLE_TICKS = 10;

    /** One storage location of the row and the hotbar slot of the item it is dedicated with. */
    private record Location(String name, BlockPos block, int itemSlot) {
        ArmVisualScenario.Click slotClick() {
            return new ArmVisualScenario.Click(name + " filter slot", block, AISLE_FACE,
                    new Vec3(BLOCK_CENTER, StorageFilterValueBox.CENTER_Y_PIXELS * PIXEL, BLOCK_CENTER), EYE_OFFSET);
        }
    }

    private static final Location AT_INTERFACE = new Location("interface", INTERFACE, SLOT_COBBLESTONE);
    private static final List<Location> LOCATIONS = List.of(AT_INTERFACE, new Location("rack bay", RACK_BAY,
            SLOT_COBBLESTONE), new Location("fluid bay", FLUID_BAY, SLOT_WATER));

    private final VisualLanguage language = new VisualLanguage(LANGUAGE_RELOAD_TIMEOUT_TICKS, SETTLE_TICKS);

    @Override
    public String name() {
        return NAME;
    }

    /** Real interaction: creative and the vanilla reach, because every check is a right-click on a 4 px sphere. */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.CREATIVE);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("filterclicks: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "filterclicks run"))
                .server("filterclicks: clear the area and build the row", FilterClicksVisualScenario::buildRow)
                // The player flies before the first camera move: a camera view above the ground only holds while it
                // does (a client player standing on the ground switches flying off again at once, LocalPlayer#aiStep).
                .server("filterclicks: lift the player into the air", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos above = context.origin().offset(LIFT);
                    player.teleportTo(server.overworld(), above.getX() + BLOCK_CENTER, above.getY(),
                            above.getZ() + BLOCK_CENTER, player.getYRot(), player.getXRot());
                })
                .waitTicks(SETTLE_TICKS)
                .server("filterclicks: the player flies and carries the items", FilterClicksVisualScenario::equip);
        for (Location location : LOCATIONS)
            checkLocation(script, location);
        checkPonderText(script, ENGLISH, "empty hand", "ponder-priority");
        // The hover tip in German, read the way a German player meets it.
        language.switchTo(script, "filterclicks: ", GERMAN);
        aim(script, AT_INTERFACE);
        selectHotbar(script, SLOT_EMPTY);
        checkHoverTip(script, AT_INTERFACE, GERMAN, "leerer Hand");
        script.shotWithGui("tip-de");
        checkPonderText(script, GERMAN, "leerer Hand", "ponder-priority-de");
        language.switchTo(script, "filterclicks: ", ENGLISH);
        script.client("filterclicks: ALL CHECKS PASSED",
                context -> LOGGER.info(PREFIX + "filterclicks: ALL CHECKS PASSED in English and German"));
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        script.shotFrom(ROW, "scene");
    }

    @Override
    public String status(VisualContext context) {
        return describe(context);
    }

    /** The screen, the held item, the use button and the crosshair: what every input check here depends on. */
    private static String describe(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        LocalPlayer player = minecraft.player;
        Screen screen = minecraft.screen;
        return String.format(Locale.ROOT, "screen=%s slot=%s held=%s use=%s hit=%s",
                screen == null ? "none" : screen.getClass().getSimpleName(),
                player == null ? "-" : player.getInventory().selected,
                player == null ? "-" : player.getMainHandItem(), minecraft.options.keyUse.isDown(),
                ArmVisualScenario.describeHit(context));
    }

    // --- one location ------------------------------------------------------------------------------------------------

    private void checkLocation(VisualScript script, Location location) {
        String at = " on the " + location.name();
        aim(script, location);
        selectHotbar(script, SLOT_EMPTY);
        checkHoverTip(script, location, ENGLISH, "empty hand");
        if (location == AT_INTERFACE)
            script.shotWithGui("tip");

        // 2. Held: the press sets the filter, and holding it longer than Create's warmup opens no board.
        selectHotbar(script, location.itemSlot());
        press(script, "the item" + at);
        holdWithoutScreen(script, "the item" + at, HOLD_TICKS);
        release(script);
        expectFilter(script, location, location.itemSlot(), "the item held" + at);

        // 3. Moved off the slot before the release (one block is enough: the gesture, not the block, is under test).
        if (location == AT_INTERFACE) {
            selectHotbar(script, SLOT_IRON);
            press(script, "an iron ingot" + at);
            script.client("filterclicks: the crosshair drifts off the slot" + at, context -> {
                LocalPlayer player = requirePlayer(context);
                player.setXRot(player.getXRot() - DRIFT_DEGREES);
            });
            ArmVisualScenario.untilOrFail(script, "filterclicks: the crosshair left the slot" + at,
                    context -> !onSlot(context, location), CLICK_TIMEOUT_TICKS,
                    context -> "the crosshair still hits " + ArmVisualScenario.describeHit(context));
            release(script);
            expectFilter(script, location, SLOT_IRON, "a press that drifted off the slot" + at);
            aim(script, location);
        }

        // 4. The hand emptied while the button stays down: its repeats must not start Create's warmup.
        selectHotbar(script, SLOT_LIST_FILTER);
        press(script, "a List Filter" + at);
        script.client("filterclicks: empty the hand while the button stays down" + at,
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[SLOT_EMPTY].getKey()));
        ArmVisualScenario.untilOrFail(script, "filterclicks: the hand is empty with the button down" + at,
                context -> requirePlayer(context).getMainHandItem().isEmpty()
                        && context.minecraft().options.keyUse.isDown(),
                CLICK_TIMEOUT_TICKS, FilterClicksVisualScenario::describe);
        holdWithoutScreen(script, "the emptied hand" + at, GUARD_HOLD_TICKS);
        release(script);
        script.waitTicks(SETTLE_TICKS);
        expectFilterStays(script, location, SLOT_LIST_FILTER, "the List Filter after the emptied hand's hold" + at);

        // 5. A short click with an empty hand clears it, through Create's warmup as before.
        script.client("filterclicks: short click with an empty hand" + at,
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        script.serverUntil("filterclicks: a short click with an empty hand cleared the filter" + at,
                (server, context) -> slotAt(server, context, location).getFilter().isEmpty(), FILTER_TIMEOUT_TICKS);
        logPass(script, "a short click with an empty hand cleared the filter" + at);

        // 6. Holding an empty hand (and the wrench, once) opens the priority board.
        holdOpensBoard(script, "an empty hand" + at, location == AT_INTERFACE);
        if (location == AT_INTERFACE) {
            selectHotbar(script, SLOT_WRENCH);
            holdOpensBoard(script, "the wrench" + at, false);
            // An empty hand held, then an item scrolled to while the warmup runs: the item is not taken.
            selectHotbar(script, SLOT_EMPTY);
            press(script, "an empty hand, then cobblestone," + at);
            script.waitTicks(WARMUP_SCROLL_TICKS)
                    .client("filterclicks: scroll to the cobblestone while the warmup runs" + at, context -> KeyMapping
                            .click(context.minecraft().options.keyHotbarSlots[SLOT_COBBLESTONE].getKey()));
            ArmVisualScenario.untilOrFail(script, "filterclicks: the warmup opens the board with the cobblestone" + at,
                    context -> context.minecraft().screen instanceof ValueSettingsScreen, BOARD_TIMEOUT_TICKS,
                    FilterClicksVisualScenario::describe);
            script.server("filterclicks: CHECK the item scrolled to during the warmup set nothing" + at,
                    (server, context) -> {
                        ItemStack filter = slotAt(server, context, location).getFilter();
                        if (!filter.isEmpty())
                            throw new VisualTestException("the item scrolled to during the warmup became the filter: "
                                    + filter);
                        LOGGER.info(PREFIX + "filterclicks: CHECK PASS: an item scrolled to during Create's warmup"
                                + " set no filter{}", at);
                    });
            script.client("filterclicks: close the priority board", context -> context.minecraft().setScreen(null));
            release(script);
            script.waitTicks(SETTLE_TICKS);
        }

        // 7. A hold that starts on the face above the slot and slides onto it sets nothing: only a press does.
        holdSlidesOntoSlot(script, location);

        // 8. A plain click, the button never held.
        selectHotbar(script, location.itemSlot());
        script.client("filterclicks: click with the item" + at,
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        expectFilter(script, location, location.itemSlot(), "a plain click with the item" + at);
    }

    /**
     * The button pressed with an item on the aisle face above the slot, held there past a repeat, then slid down onto
     * the slot and held for {@value #SLOT_HOLD_TICKS} ticks before the release. The slot's filter is empty when this
     * starts (the empty hand cleared it) and must still be, and while the crosshair is on the slot nothing more may go
     * into a bay: vanilla's repeats of a hold that began elsewhere are no press. The iron ingot on the interface does
     * nothing on the face (a block would be placed against it), so there the face is just the start of the hold.
     */
    private static void holdSlidesOntoSlot(VisualScript script, Location location) {
        String at = " on the " + location.name();
        int itemSlot = location == AT_INTERFACE ? SLOT_IRON : location.itemSlot();
        long[] amount = { 0L, 0L };
        selectHotbar(script, itemSlot);
        script.client("filterclicks: the crosshair goes to the face above the slot" + at, context -> {
            LocalPlayer player = requirePlayer(context);
            player.setXRot(player.getXRot() - DRIFT_DEGREES);
        });
        ArmVisualScenario.untilOrFail(script, "filterclicks: the crosshair is on the face above the slot" + at,
                context -> onFaceBesideSlot(context, location), CLICK_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context));
        script.server("filterclicks: note what the " + location.name() + " holds",
                (server, context) -> amount[0] = storedAmount(server, context, location));
        press(script, "the item on the face above the slot" + at);
        holdWithoutScreen(script, "the item on the face above the slot" + at, FACE_HOLD_TICKS);
        if (location != AT_INTERFACE)
            script.serverUntil("filterclicks: the held button filled the " + location.name() + " from its face",
                    (server, context) -> storedAmount(server, context, location) > amount[0], FILTER_TIMEOUT_TICKS);
        script.client("filterclicks: the crosshair slides onto the slot with the button down" + at, context -> {
            LocalPlayer player = requirePlayer(context);
            player.setXRot(player.getXRot() + DRIFT_DEGREES);
        });
        ArmVisualScenario.untilOrFail(script, "filterclicks: the held crosshair reached the slot" + at,
                context -> onSlot(context, location) && context.minecraft().options.keyUse.isDown(),
                CLICK_TIMEOUT_TICKS, FilterClicksVisualScenario::describe);
        script.waitTicks(SLIDE_SETTLE_TICKS)
                .server("filterclicks: note what the " + location.name() + " holds with the crosshair on the slot",
                        (server, context) -> amount[1] = storedAmount(server, context, location));
        holdWithoutScreen(script, "the item slid onto the slot" + at, SLOT_HOLD_TICKS);
        release(script);
        script.waitTicks(SETTLE_TICKS);
        script.server("filterclicks: CHECK a hold that slid onto the slot set nothing" + at, (server, context) -> {
            ItemStack filter = slotAt(server, context, location).getFilter();
            if (!filter.isEmpty())
                throw new VisualTestException("a hold that started beside the slot and slid onto it set the filter"
                        + at + " to " + filter);
            long now = storedAmount(server, context, location);
            if (now != amount[1])
                throw new VisualTestException("the held button stored " + (now - amount[1]) + " more" + at
                        + " while the crosshair was on the slot");
            LOGGER.info(PREFIX + "filterclicks: CHECK PASS: a hold that slid onto the slot{} set no filter and stored"
                    + " nothing there (the face took {} first)", at, amount[1] - amount[0]);
        });
    }

    /** The crosshair is on the location's aisle face, outside the slot's sphere. */
    private static boolean onFaceBesideSlot(VisualContext context, Location location) {
        Minecraft minecraft = context.minecraft();
        LocalPlayer player = minecraft.player;
        return player != null && minecraft.level != null && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(context.origin().offset(location.block()))
                && hit.getDirection() == AISLE_FACE
                && StorageFilterClicks.slotUnder(player, minecraft.level, hit.getBlockPos(), hit) == null;
    }

    /** What a bay holds — items in a rack bay, millibuckets in a fluid bay — and 0 for the interface. */
    private static long storedAmount(MinecraftServer server, VisualContext context, Location location) {
        BlockEntity entity = server.overworld().getBlockEntity(context.origin().offset(location.block()));
        if (entity instanceof RackBayBlockEntity bay)
            return bay.storedCount();
        if (entity instanceof FluidBayBlockEntity tank)
            return tank.millibuckets();
        return 0L;
    }

    private static void aim(VisualScript script, Location location) {
        ArmVisualScenario.Click click = location.slotClick();
        script.camera(click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, "filterclicks: the crosshair is on the " + click.label(),
                context -> ArmVisualScenario.aimedAt(context, click) && onSlot(context, location), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context) + " instead of the "
                        + click.label());
    }

    /** The hover tip Create shows for the slot under the crosshair names the empty hand and the order. */
    private static void checkHoverTip(VisualScript script, Location location, String language, String phrase) {
        script.client("filterclicks: forget the last hover tip",
                context -> CreateClient.VALUE_SETTINGS_HANDLER.lastHoverTip = null);
        ArmVisualScenario.untilOrFail(script, "filterclicks: the hover tip of the " + location.name() + " in "
                        + language, context -> expectedTip().equals(shownTip()), HOVER_TIP_TIMEOUT_TICKS,
                context -> "the hover tip's third line reads '" + shownTip() + "' instead of '" + expectedTip() + "'");
        script.client("filterclicks: CHECK the hover tip of the " + location.name() + " in " + language, context -> {
            String tip = shownTip();
            if (tip == null || tip.equals(WareworksLang.key(WareworksLang.INTERFACE_STORE_PRIORITY_TIP))
                    || !tip.contains(phrase))
                throw new VisualTestException("the hover tip in " + language + " reads '" + tip + "', without '"
                        + phrase + "'");
            for (MutableComponent line : Objects.requireNonNull(CreateClient.VALUE_SETTINGS_HANDLER.lastHoverTip)) {
                int width = context.minecraft().font.width(line);
                if (width > MIN_GUI_WIDTH)
                    throw new VisualTestException("the hover tip line '" + line.getString() + "' in " + language
                            + " is " + width + " GUI px wide, more than the " + MIN_GUI_WIDTH
                            + " of the narrowest GUI");
            }
            LOGGER.info(PREFIX + "filterclicks: CHECK PASS [{}]: the hover tip of the {} reads '{}', {} GUI px wide",
                    language, location.name(), tip, context.minecraft().font.width(tip));
        });
    }

    @Nullable
    private static String shownTip() {
        List<MutableComponent> tip = CreateClient.VALUE_SETTINGS_HANDLER.lastHoverTip;
        return tip == null || tip.size() < 3 ? null : tip.get(2).getString();
    }

    private static String expectedTip() {
        return WareworksLang.translateDirect(WareworksLang.INTERFACE_STORE_PRIORITY_TIP).getString();
    }

    private static boolean onSlot(VisualContext context, Location location) {
        Minecraft minecraft = context.minecraft();
        LocalPlayer player = minecraft.player;
        return player != null && minecraft.level != null && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(context.origin().offset(location.block()))
                && StorageFilterClicks.slotUnder(player, minecraft.level, hit.getBlockPos(), hit) != null;
    }

    // --- the Ponder line ---------------------------------------------------------------------------------------------

    /**
     * Opens the rack bay's Ponder scenes, goes to "Rack Bays in a Warehouse Aisle", seeks keyframe by keyframe until
     * the priority text is shown, checks that it is the translation of the current language, and photographs it.
     */
    private static void checkPonderText(VisualScript script, String language, String phrase, String label) {
        // The item is looked up when the step runs: the script is built while the mod loads, before registries bind.
        script.client("filterclicks: open the rack bay's Ponder scenes in " + language, context -> context.minecraft()
                        .setScreen(PonderUI.of(BuiltInRegistries.ITEM.getKey(WareworksBlocks.RACK_BAY_WOOD.asItem()))))
                .until("filterclicks: the Ponder screen is shown",
                        context -> context.minecraft().screen instanceof PonderUI, PONDER_OPEN_TIMEOUT_TICKS)
                .waitTicks(PONDER_SETTLE_TICKS)
                .client("filterclicks: go to the scene " + PRIORITY_SCENE, context -> {
                    PonderUI ui = requirePonder(context);
                    for (int scrolled = 0; !PRIORITY_SCENE.equals(ui.getActiveScene().getId()); scrolled++)
                        if (scrolled >= RACK_BAY_SCENES || !ui.mouseScrolled(0.0, 0.0, 0.0, 1.0))
                            throw new VisualTestException("the rack bay shows no Ponder scene " + PRIORITY_SCENE);
                })
                .waitTicks(PONDER_SETTLE_TICKS)
                .client("filterclicks: seek to the priority text in " + language,
                        FilterClicksVisualScenario::seekToPriorityText)
                .waitTicks(PONDER_SETTLE_TICKS)
                .client("filterclicks: CHECK the Ponder priority text in " + language, context -> {
                    String expected = I18n.get(PRIORITY_TEXT_KEY);
                    List<String> shown = shownTexts(requirePonder(context).getActiveScene());
                    if (expected.equals(PRIORITY_TEXT_KEY) || !expected.contains(phrase) || !shown.contains(expected))
                        throw new VisualTestException("the Ponder scene shows " + shown + ", not the " + language
                                + " priority text '" + expected + "'");
                    LOGGER.info(PREFIX + "filterclicks: CHECK PASS [{}]: the Ponder scene {} shows '{}'", language,
                            PRIORITY_SCENE, expected);
                })
                .shot(label)
                .client("filterclicks: close the Ponder screen", context -> context.minecraft().setScreen(null))
                .until("filterclicks: the Ponder screen is closed", context -> context.minecraft().screen == null,
                        PONDER_OPEN_TIMEOUT_TICKS);
    }

    /** Fast-forwards from keyframe to keyframe until the priority text is among the shown texts. */
    private static void seekToPriorityText(VisualContext context) {
        PonderUI ui = requirePonder(context);
        PonderScene scene = ui.getActiveScene();
        String expected = I18n.get(PRIORITY_TEXT_KEY);
        for (int keyframe = 0; keyframe < scene.getKeyframeCount(); keyframe++) {
            ui.seekToTime(scene.getKeyframeTime(keyframe) + PONDER_TEXT_LEAD_TICKS);
            if (shownTexts(scene).contains(expected)) {
                LOGGER.info(PREFIX + "filterclicks: the priority text is shown at keyframe {}, {}/{} ticks", keyframe,
                        scene.getCurrentTime(), scene.getTotalTime());
                return;
            }
        }
        throw new VisualTestException("no keyframe of " + PRIORITY_SCENE + " shows '" + expected + "'");
    }

    /** The texts of the scene's visible text windows, as the current language reads them. */
    @SuppressWarnings("unchecked")
    private static List<String> shownTexts(PonderScene scene) {
        List<String> texts = new ArrayList<>();
        for (Object element : scene.getElements())
            if (element instanceof TextWindowElement window && window.isVisible()) {
                try {
                    texts.add(((Supplier<String>) PonderReflect.TEXT_GETTER.get(window)).get());
                } catch (IllegalAccessException error) {
                    throw new VisualTestException("cannot read a Ponder text window: " + error);
                }
            }
        return texts;
    }

    private static PonderUI requirePonder(VisualContext context) {
        if (context.minecraft().screen instanceof PonderUI ui)
            return ui;
        throw new VisualTestException("no Ponder screen is open: " + context.minecraft().screen);
    }

    /**
     * Ponder keeps a text window's text behind a package-private supplier (dev tooling only). A holder, so the field is
     * looked up when a text is first read and not while the mod loads, which is when the script is built.
     */
    private static final class PonderReflect {
        static final Field TEXT_GETTER = textGetter();

        private PonderReflect() {
        }

        private static Field textGetter() {
            try {
                Field field = TextWindowElement.class.getDeclaredField("textGetter");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException("cannot reach TextWindowElement#textGetter", error);
            }
        }
    }

    // --- input -------------------------------------------------------------------------------------------------------

    /** The use button goes down the way {@code MouseHandler#onPress} puts it down. */
    private static void press(VisualScript script, String what) {
        script.client("filterclicks: press the use button with " + what, context -> {
            InputConstants.Key use = context.minecraft().options.keyUse.getKey();
            KeyMapping.set(use, true);
            KeyMapping.click(use);
        });
        ArmVisualScenario.untilOrFail(script, "filterclicks: the client handled the press with " + what,
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the press was never consumed (screen " + context.minecraft().screen + ")");
    }

    private static void release(VisualScript script) {
        script.client("filterclicks: release the use button",
                context -> KeyMapping.set(context.minecraft().options.keyUse.getKey(), false));
    }

    /** The button stays down for {@code ticks} ticks, and no screen may open meanwhile. */
    private static void holdWithoutScreen(VisualScript script, String what, int ticks) {
        int[] held = { 0 };
        script.until("filterclicks: hold " + what + " for " + ticks + " ticks with no screen opening", context -> {
            Screen screen = context.minecraft().screen;
            if (screen != null)
                throw new VisualTestException("holding " + what + " opened " + screen.getClass().getSimpleName()
                        + " after " + held[0] + " ticks");
            if (!context.minecraft().options.keyUse.isDown())
                throw new VisualTestException("the use button came up while holding " + what);
            return ++held[0] >= ticks;
        }, ticks + CLICK_TIMEOUT_TICKS);
        logPass(script, "holding " + what + " for " + ticks + " ticks opened no screen");
    }

    /**
     * Holding the button opens Create's priority board (its warmup is five ticks), which is closed again without
     * picking a value; opening a screen releases every key ({@code Minecraft#setScreen}).
     */
    private static void holdOpensBoard(VisualScript script, String what, boolean shoot) {
        press(script, what);
        ArmVisualScenario.untilOrFail(script, "filterclicks: holding " + what + " opens the priority board",
                context -> context.minecraft().screen instanceof ValueSettingsScreen, BOARD_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen + " after holding " + what);
        logPass(script, "holding " + what + " opened the priority board");
        if (shoot)
            script.shotWithGui("board");
        script.client("filterclicks: close the priority board", context -> context.minecraft().setScreen(null));
        release(script);
        script.waitTicks(SETTLE_TICKS);
    }

    private static void selectHotbar(VisualScript script, int slot) {
        script.server("filterclicks: select hotbar slot " + slot, (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getInventory().selected = slot;
            player.connection.send(new ClientboundSetCarriedItemPacket(slot));
        }).until("filterclicks: the client holds hotbar slot " + slot, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot;
        }, HELD_ITEM_TIMEOUT_TICKS);
    }

    // --- server checks -----------------------------------------------------------------------------------------------

    /** The server's filter becomes the item of {@code slot} within {@value #FILTER_TIMEOUT_TICKS} ticks. */
    private static void expectFilter(VisualScript script, Location location, int slot, String what) {
        script.serverUntil("filterclicks: " + what + " set the filter",
                (server, context) -> holds(server, context, location, slot), FILTER_TIMEOUT_TICKS);
        logPass(script, what + " set the filter");
    }

    /** The server's filter is the item of {@code slot} now, after the hold and the release. */
    private static void expectFilterStays(VisualScript script, Location location, int slot, String what) {
        script.server("filterclicks: CHECK " + what, (server, context) -> {
            if (!holds(server, context, location, slot))
                throw new VisualTestException(what + ": the filter is "
                        + slotAt(server, context, location).getFilter());
            LOGGER.info(PREFIX + "filterclicks: CHECK PASS: {} is still the filter", what);
        });
    }

    private static boolean holds(MinecraftServer server, VisualContext context, Location location, int slot) {
        ItemStack expected = context.serverPlayer(server).getInventory().getItem(slot);
        ItemStack filter = slotAt(server, context, location).getFilter();
        if (expected.getItem() instanceof FilterItem)
            // Create trims a stored filter item's enchantments and attribute modifiers, so it is compared by its list.
            return filter.is(expected.getItem()) && Objects.equals(filter.get(AllDataComponents.FILTER_ITEMS),
                    expected.get(AllDataComponents.FILTER_ITEMS));
        return !expected.isEmpty() && ItemStack.isSameItemSameComponents(filter, expected);
    }

    private static StorageFilterBehaviour slotAt(MinecraftServer server, VisualContext context, Location location) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(server.overworld(),
                context.origin().offset(location.block()), FilteringBehaviour.TYPE);
        if (!(behaviour instanceof StorageFilterBehaviour slot))
            throw new VisualTestException("no store filter slot at the " + location.name() + ": " + behaviour);
        return slot;
    }

    private static void logPass(VisualScript script, String what) {
        script.client("filterclicks: CHECK " + what,
                context -> LOGGER.info(PREFIX + "filterclicks: CHECK PASS: {}", what));
    }

    // --- scene (server thread) ---------------------------------------------------------------------------------------

    private static void buildRow(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos column = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(column))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(origin);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(CLEAR_MIN_X, 0, CLEAR_MIN_Z),
                origin.offset(CLEAR_MAX_X, CLEAR_HEIGHT, CLEAR_MAX_Z)))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
        for (BlockPos block : List.of(INTERFACE, CHEST, RACK_BAY, FLUID_BAY))
            level.setBlockAndUpdate(origin.offset(block).below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(CHEST), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(INTERFACE), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, FACING));
        level.setBlockAndUpdate(origin.offset(RACK_BAY), WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, FACING));
        level.setBlockAndUpdate(origin.offset(FLUID_BAY), WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, FACING));
    }

    private static void equip(MinecraftServer server, VisualContext context) {
        ServerPlayer player = context.serverPlayer(server);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.getInventory().clearContent();
        player.getInventory().setItem(SLOT_COBBLESTONE, new ItemStack(Items.COBBLESTONE, HELD_STACK));
        player.getInventory().setItem(SLOT_IRON, new ItemStack(Items.IRON_INGOT, HELD_STACK));
        ItemStack list = AllItems.FILTER.asStack();
        list.set(AllDataComponents.FILTER_ITEMS,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.IRON_INGOT))));
        player.getInventory().setItem(SLOT_LIST_FILTER, list);
        player.getInventory().setItem(SLOT_WATER, new ItemStack(Items.WATER_BUCKET));
        player.getInventory().setItem(SLOT_WRENCH, AllItems.WRENCH.asStack());
        player.getInventory().selected = SLOT_EMPTY;
        player.connection.send(new ClientboundSetCarriedItemPacket(SLOT_EMPTY));
        player.inventoryMenu.broadcastChanges();
    }

    private static LocalPlayer requirePlayer(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player == null)
            throw new VisualTestException("no client player");
        return player;
    }
}
