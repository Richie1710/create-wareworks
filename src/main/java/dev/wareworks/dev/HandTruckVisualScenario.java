package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import dev.wareworks.content.handtruck.HandTruckCarry;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Visual scenario {@code handtruck} (issue #37, ADR-070): the hand truck in a survival player's hands, loaded and
 * unloaded with real clicks, in first and third person, with Flywheel on and off, in English and German.
 * <p>
 * Clicks in the world are {@link KeyMapping#click} on the use, drop, inventory and hotbar keys, so
 * {@code Minecraft#handleKeybinds} runs {@code startUseItem} on the crosshair target exactly as for a mouse button; the
 * crosshair target is checked before every click ({@code ArmVisualScenario}'s pattern). Clicks into screens go through
 * {@link ScreenInput}. Walking is the movement keys held down with {@link KeyMapping#setDown}, so the client's own
 * {@code KeyboardInput} reads them and the hand truck's input scaling ({@code HandTruckClientEvents}) runs on what a
 * keyboard would have sent.
 * <p>
 * Every load, unload and refused move is bracketed by a {@link SceneItemCensus} of the scene and the player, which
 * counts a loaded truck as the empty truck plus what the carried chest holds.
 * <p>
 * First pass (Flywheel on): the empty truck in first and third person; a walking baseline (sprinting starts, walking
 * speed measured) and an elytra control (gliding starts) with the empty truck, which prove that the keys really do
 * what the loaded checks expect them <i>not</i> to do; loading the named chest; the loaded truck in first person and
 * pushed in third person from behind and in front; its tooltip; walking (no sprint, about half the walking speed) and
 * no gliding; the drop key refused; the truck put into a barrel slot coming straight back; a second truck refused while
 * one is loaded; the German tooltip and refusals; unloading two blocks further on, contents and name exact. Second pass
 * (Flywheel off): load and unload again with the shots of the loaded truck.
 */
final class HandTruckVisualScenario implements VisualScenario {
    static final String NAME = "handtruck";
    private static final String WORLD_FOLDER = "wareworks_visual_handtruck";
    private static final long RUN_TIMEOUT_MILLIS = 8L * 60L * 1000L;
    private static final int SETTLE_TICKS = 10;
    private static final int CLICK_TIMEOUT_TICKS = 40;
    private static final int AIM_TIMEOUT_TICKS = 60;
    private static final int RESULT_TIMEOUT_TICKS = 100;
    private static final int CAMERA_TYPE_TICKS = 8;
    /** Ticks the movement keys are held for one walk; the speed is measured over the ticks after {@link #WALK_RAMP}. */
    private static final int WALK_TICKS = 40;
    private static final int WALK_RAMP = 10;
    /** The loaded walk must reach this share of the empty walking speed, and not more than {@link #SLOW_MAX}. */
    private static final double SLOW_MIN = 0.40;
    private static final double SLOW_MAX = 0.60;
    /** Ticks the jump key is pulsed while falling with an elytra on. */
    private static final int GLIDE_TICKS = 30;
    /** How high above the ground the elytra checks start. */
    private static final int GLIDE_HEIGHT = 12;
    private static final String CHEST_NAME = "Ore Crate";

    // --- the scene, relative to the origin (the first air block above the ground) ------------------------------------

    /** Where the player stands for every click: facing south, towards the chest and the barrel. */
    private static final Vec3 STAND_EYE = new Vec3(0.5, 1.62, 0.5);
    private static final BlockPos CHEST = new BlockPos(0, 0, 3);
    /** Where the first pass sets the chest down again and the second pass picks it up from. */
    private static final BlockPos CHEST_AGAIN = new BlockPos(0, 0, 2);
    private static final BlockPos BARREL = new BlockPos(3, 0, 3);
    /** The runway the walks start on: the player looks east along it. */
    private static final Vec3 RUNWAY_EYE = new Vec3(-4.5, 1.62, -3.5);
    private static final Vec3 RUNWAY_TARGET = new Vec3(25.5, 1.62, -3.5);
    private static final BlockPos CLEAR_FROM = new BlockPos(-8, 0, -8);
    private static final BlockPos CLEAR_TO = new BlockPos(28, 24, 12);
    private static final int HOTBAR_TRUCK = 0;
    private static final int HOTBAR_FREE = 1;

    /** What the chest holds: twenty stacks, among them a shulker box with contents inside. */
    private static final List<ItemStack> CHEST_CONTENTS = chestContents();

    private final VisualLanguage language = new VisualLanguage(1200, 10);

    // --- state shared by the steps -------------------------------------------------------------------------------------

    @Nullable
    private volatile Map<ItemKey, Long> census;
    private volatile AABB censusBox = new AABB(BlockPos.ZERO);
    private final List<String> passed = new ArrayList<>();
    /** Walk measurements of the client: blocks per tick and whether a sprint ever started. */
    private double walkingSpeed;
    private double sprintSpeed;
    private int walkTicks;
    @Nullable
    private Vec3 walkStart;
    private double walkMeasured;
    private boolean walkSprinted;
    private int glideTicks;
    private boolean glided;

    @Override
    public String name() {
        return NAME;
    }

    /** A survival player with the vanilla reach: the penalty applies to survival players only (creative is exempt). */
    @Override
    public VisualWorldProfile worldProfile() {
        return VisualWorldProfile.playable(WORLD_FOLDER, WORLD_FOLDER, GameType.SURVIVAL);
    }

    @Override
    public void setup(VisualScript script) {
        script.client("handtruck: give the run its own time budget",
                        context -> context.watchdog().rearm(RUN_TIMEOUT_MILLIS, "handtruck run"))
                .server("handtruck: build the scene and give the player an empty hand truck", this::buildScene)
                .until("handtruck: the client sees the chest, the barrel and the truck in hand", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && context.minecraft().level != null
                            && context.minecraft().level.getBlockState(at(context, CHEST)).is(Blocks.CHEST)
                            && context.minecraft().level.getBlockState(at(context, BARREL)).is(Blocks.BARREL)
                            && player.getInventory().getItem(HOTBAR_TRUCK).is(WareworksItems.HAND_TRUCK.get());
                }, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL)
            fullTour(script);
        else
            secondPass(script);
    }

    @Override
    public String status(VisualContext context) {
        LocalPlayer player = context.minecraft().player;
        if (player == null)
            return "player=none";
        ItemStack held = player.getMainHandItem();
        return "held=" + held.getHoverName().getString() + " loaded=" + HandTruckItem.isLoaded(held) + " carrying="
                + HandTruckCarry.isCarrying(player) + " sprinting=" + player.isSprinting() + " gliding="
                + player.isFallFlying() + " camera=" + context.minecraft().options.getCameraType();
    }

    // --- the first pass ------------------------------------------------------------------------------------------------

    private void fullTour(VisualScript script) {
        selectHotbar(script, HOTBAR_TRUCK, "the empty hand truck", stack -> stack.is(WareworksItems.HAND_TRUCK.get())
                && !HandTruckItem.isLoaded(stack));
        standAndLookAt(script, "chest", CHEST, Direction.NORTH);
        script.shotWithGui("empty-first");
        thirdPersonShots(script, "empty");

        // Controls with the empty truck: the keys really start a sprint and a glide, so the loaded checks below that
        // see neither are about the truck and not about keys that did nothing.
        walk(script, "empty truck, sprint key held", true, false);
        walk(script, "empty truck, walking", false, false);
        glide(script, "empty truck", false);

        standAndLookAt(script, "chest", CHEST, Direction.NORTH);
        load(script, CHEST, "the named chest");
        script.shotWithGui("loaded-first");
        thirdPersonShots(script, "loaded");

        walk(script, "loaded truck, sprint key held", true, true);
        glide(script, "loaded truck", true);

        tooltip(script, "en_us", "tooltip-en", "Hand Truck with " + CHEST_NAME, "20 stacks inside");
        dropRefused(script, "drop-refused");
        menuReturns(script);
        secondTruckRefused(script, "already-loaded");

        language.switchTo(script, "handtruck: ", "de_de");
        tooltip(script, "de_de", "tooltip-de", "Sackkarre mit " + CHEST_NAME, "20 Stapel darin");
        dropRefused(script, "drop-refused-de");
        secondTruckRefused(script, "already-loaded-de");
        language.switchTo(script, "handtruck: ", "en_us");

        unload(script, CHEST_AGAIN);
        script.shotWithGui("unloaded");
        script.client("handtruck: every check passed", context -> LOGGER.info(PREFIX
                + "handtruck: ALL CHECKS PASSED in English and German ({})", String.join(", ", passed)));
    }

    // --- the second pass: Flywheel off ---------------------------------------------------------------------------------

    private void secondPass(VisualScript script) {
        selectHotbar(script, HOTBAR_TRUCK, "the empty hand truck", stack -> stack.is(WareworksItems.HAND_TRUCK.get())
                && !HandTruckItem.isLoaded(stack));
        standAndLookAt(script, "chest set down", CHEST_AGAIN, Direction.NORTH);
        load(script, CHEST_AGAIN, "the chest set down in the first pass");
        script.shotWithGui("loaded-first");
        thirdPersonShots(script, "loaded");
        unload(script, CHEST);
        script.shotWithGui("unloaded");
        script.client("handtruck: the second pass passed", context -> LOGGER.info(PREFIX
                + "handtruck: CHECK PASS (Flywheel off): loaded and unloaded again, census exact"));
    }

    // --- scene ---------------------------------------------------------------------------------------------------------

    private void buildScene(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
        BlockPos origin = new BlockPos(0, ground, 0);
        context.setOrigin(origin);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(CLEAR_FROM), origin.offset(CLEAR_TO)))
            if (!level.getBlockState(pos).isAir())
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        censusBox = new AABB(Vec3.atLowerCornerOf(origin.offset(CLEAR_FROM).below()),
                Vec3.atLowerCornerOf(origin.offset(CLEAR_TO).offset(1, 1, 1)));

        BlockPos chest = origin.offset(CHEST);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        if (!(level.getBlockEntity(chest) instanceof ChestBlockEntity chestEntity))
            throw new VisualTestException("no chest block entity at " + chest);
        for (int slot = 0; slot < CHEST_CONTENTS.size(); slot++)
            chestEntity.setItem(slot, CHEST_CONTENTS.get(slot).copy());
        CompoundTag tag = chestEntity.saveWithoutMetadata(level.registryAccess());
        tag.putString("CustomName", Component.Serializer.toJson(Component.literal(CHEST_NAME), level.registryAccess()));
        chestEntity.loadWithComponents(tag, level.registryAccess());
        chestEntity.setChanged();
        level.sendBlockUpdated(chest, chestEntity.getBlockState(), chestEntity.getBlockState(), 3);

        level.setBlockAndUpdate(origin.offset(BARREL), Blocks.BARREL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.NORTH));

        ServerPlayer player = context.serverPlayer(server);
        player.getInventory().clearContent();
        player.getInventory().setItem(HOTBAR_TRUCK, new ItemStack(WareworksItems.HAND_TRUCK.get()));
        player.getInventory().selected = HOTBAR_TRUCK;
        player.getFoodData().setFoodLevel(20);
        Vec3 feet = Vec3.atLowerCornerOf(origin).add(STAND_EYE).subtract(0, player.getEyeHeight(), 0);
        player.teleportTo(level, feet.x, feet.y, feet.z, 0.0F, 30.0F);
        LOGGER.info(PREFIX + "handtruck: scene at {}: chest '{}' with {} stacks at {}, barrel at {}", origin, CHEST_NAME,
                CHEST_CONTENTS.size(), chest, origin.offset(BARREL));
    }

    private static List<ItemStack> chestContents() {
        List<ItemStack> contents = new ArrayList<>();
        Item[] items = { Items.IRON_INGOT, Items.GOLD_INGOT, Items.COAL, Items.REDSTONE, Items.COPPER_INGOT,
                Items.RAW_IRON, Items.RAW_GOLD, Items.LAPIS_LAZULI, Items.EMERALD, Items.DIAMOND, Items.QUARTZ,
                Items.AMETHYST_SHARD, Items.IRON_NUGGET, Items.GOLD_NUGGET, Items.FLINT, Items.CLAY_BALL,
                Items.GLOWSTONE_DUST, Items.RAW_COPPER, Items.NETHERITE_SCRAP };
        int[] counts = { 64, 32, 64, 48, 20, 17, 9, 33, 5, 3, 40, 12, 50, 21, 8, 30, 16, 27, 2 };
        for (int i = 0; i < items.length; i++)
            contents.add(new ItemStack(items[i], counts[i]));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.APPLE, 5))));
        contents.add(box);
        return List.copyOf(contents);
    }

    // --- views and clicks ----------------------------------------------------------------------------------------------

    /** Stands at {@link #STAND_EYE} and looks at the {@code face} of {@code block}, checking the crosshair. */
    private static void standAndLookAt(VisualScript script, String what, BlockPos block, Direction face) {
        Vec3 target = Vec3.atLowerCornerOf(block).add(0.5, 0.4, 0.5)
                .add(face.getStepX() * 0.4, 0, face.getStepZ() * 0.4);
        Vec3 eye = new Vec3(block.getX() + 0.5, STAND_EYE.y, STAND_EYE.z);
        script.client("handtruck: first person", context -> context.minecraft().options
                .setCameraType(CameraType.FIRST_PERSON))
                .camera(CameraView.of("at-" + what.replace(' ', '-'), eye.x, eye.y, eye.z, target.x, target.y,
                        target.z));
        aim(script, what, block, face);
    }

    private static void aim(VisualScript script, String what, BlockPos block, Direction face) {
        ArmVisualScenario.untilOrFail(script, "handtruck: the crosshair is on the " + face + " face of the " + what,
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(at(context, block))
                        && hit.getDirection() == face,
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the " + face + " face of " + at(context, block));
    }

    private static void rightClick(VisualScript script, String what) {
        script.client("handtruck: right-click " + what,
                context -> KeyMapping.click(context.minecraft().options.keyUse.getKey()));
        ArmVisualScenario.untilOrFail(script, "handtruck: the client handled the right-click on " + what,
                context -> ArmVisualScenario.clickCount(context.minecraft().options.keyUse) == 0, CLICK_TIMEOUT_TICKS,
                context -> "the click was never consumed (screen " + context.minecraft().screen + ")");
    }

    /** Selects a hotbar slot with its hotbar key, as a player does, and waits until the client holds what it should. */
    private static void selectHotbar(VisualScript script, int slot, String what, Predicate<ItemStack> expected) {
        script.client("handtruck: press hotbar key " + (slot + 1) + " for " + what,
                context -> KeyMapping.click(context.minecraft().options.keyHotbarSlots[slot].getKey()));
        ArmVisualScenario.untilOrFail(script, "handtruck: the player holds " + what, context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.getInventory().selected == slot && expected.test(player.getMainHandItem());
        }, RESULT_TIMEOUT_TICKS, context -> "the player holds " + context.minecraft().player.getMainHandItem()
                + " in slot " + context.minecraft().player.getInventory().selected);
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * Third person from behind and from the front, then back to first person. The player first steps onto the open
     * runway and looks a little down along it: in front of the chest, the third person camera would be pulled in by
     * the blocks behind the player.
     */
    private static void thirdPersonShots(VisualScript script, String moment) {
        script.camera(CameraView.of("pose", RUNWAY_EYE.x, RUNWAY_EYE.y, RUNWAY_EYE.z, RUNWAY_EYE.x + 10.0,
                        RUNWAY_EYE.y - 2.0, RUNWAY_EYE.z))
                .client("handtruck: third person from behind",
                        context -> context.minecraft().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .waitTicks(CAMERA_TYPE_TICKS)
                .shot(moment + "-third-back")
                .client("handtruck: third person from the front",
                        context -> context.minecraft().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .waitTicks(CAMERA_TYPE_TICKS)
                .shot(moment + "-third-front")
                .client("handtruck: back to first person",
                        context -> context.minecraft().options.setCameraType(CameraType.FIRST_PERSON))
                .waitTicks(CAMERA_TYPE_TICKS);
    }

    // --- load and unload -----------------------------------------------------------------------------------------------

    private void load(VisualScript script, BlockPos chest, String what) {
        takeCensus(script, "before loading " + what);
        rightClick(script, what);
        ArmVisualScenario.untilOrFail(script, "handtruck: the client sees " + what + " on the truck", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && HandTruckItem.isLoaded(player.getMainHandItem())
                    && context.minecraft().level.getBlockState(at(context, chest)).isAir();
        }, RESULT_TIMEOUT_TICKS, context -> "the client holds " + context.minecraft().player.getMainHandItem()
                + " and sees " + context.minecraft().level.getBlockState(at(context, chest)) + " at the chest");
        script.server("handtruck: CHECK " + what + " is on the truck, census exact", (server, context) -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = context.serverPlayer(server);
            ItemStack truck = player.getMainHandItem();
            if (!HandTruckItem.isLoaded(truck))
                throw new VisualTestException("the server's truck is not loaded: " + truck);
            if (!level.getBlockState(context.origin().offset(chest)).isAir())
                throw new VisualTestException("the chest is still standing: " + level.getBlockState(
                        context.origin().offset(chest)));
            if (!truck.getHoverName().getString().contains(CHEST_NAME))
                throw new VisualTestException("the loaded truck is called '" + truck.getHoverName().getString()
                        + "', without the chest's name");
            assertCensus(level, player, "loading " + what);
            pass("loaded " + what);
        }).waitTicks(SETTLE_TICKS);
    }

    private void unload(VisualScript script, BlockPos target) {
        BlockPos ground = target.below();
        Vec3 eye = new Vec3(target.getX() + 0.5, STAND_EYE.y, STAND_EYE.z);
        Vec3 top = Vec3.atLowerCornerOf(target).add(0.5, 0.0, 0.5);
        selectHotbar(script, HOTBAR_TRUCK, "the loaded hand truck", HandTruckItem::isLoaded);
        script.camera(CameraView.of("set-down", eye.x, eye.y, eye.z, top.x, top.y, top.z));
        aim(script, "ground where the chest goes", ground, Direction.UP);
        takeCensus(script, "before unloading");
        rightClick(script, "the ground");
        ArmVisualScenario.untilOrFail(script, "handtruck: the client sees the chest set down", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && !HandTruckItem.isLoaded(player.getMainHandItem())
                    && context.minecraft().level.getBlockState(at(context, target)).is(Blocks.CHEST);
        }, RESULT_TIMEOUT_TICKS, context -> "the client holds " + context.minecraft().player.getMainHandItem()
                + " and sees " + context.minecraft().level.getBlockState(at(context, target)));
        script.server("handtruck: CHECK the chest stands again, facing the player, contents and name exact",
                (server, context) -> {
                    ServerLevel level = server.overworld();
                    ServerPlayer player = context.serverPlayer(server);
                    BlockPos pos = context.origin().offset(target);
                    if (level.getBlockState(pos).getValue(ChestBlock.FACING) != Direction.NORTH)
                        throw new VisualTestException("the chest faces " + level.getBlockState(pos)
                                .getValue(ChestBlock.FACING) + ", not the player (north)");
                    if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest))
                        throw new VisualTestException("no chest block entity at " + pos);
                    if (chest.getCustomName() == null || !CHEST_NAME.equals(chest.getCustomName().getString()))
                        throw new VisualTestException("the chest lost its name: " + chest.getCustomName());
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack expected = slot < CHEST_CONTENTS.size() ? CHEST_CONTENTS.get(slot) : ItemStack.EMPTY;
                        if (!ItemStack.matches(expected, chest.getItem(slot)))
                            throw new VisualTestException("slot " + slot + " holds " + chest.getItem(slot)
                                    + " instead of " + expected);
                    }
                    if (HandTruckItem.isLoaded(player.getMainHandItem()))
                        throw new VisualTestException("the truck is still loaded after setting the chest down");
                    assertCensus(level, player, "unloading");
                    pass("unloaded at " + target + " facing north, name and 20 stacks exact");
                }).waitTicks(SETTLE_TICKS);
    }

    // --- walking and gliding -------------------------------------------------------------------------------------------

    /**
     * Holds the forward key (and the sprint key) for {@link #WALK_TICKS} on the runway and measures the speed over the
     * ticks after {@link #WALK_RAMP}. Without a load the sprint must start and the walking speed is recorded; with one
     * no sprint may ever start and the speed must be about {@code handTruck.speedMultiplier} of the walking speed.
     */
    private void walk(VisualScript script, String label, boolean sprintKey, boolean loaded) {
        script.client("handtruck: first person", context -> context.minecraft().options
                        .setCameraType(CameraType.FIRST_PERSON))
                .camera(CameraView.of("runway", RUNWAY_EYE.x, RUNWAY_EYE.y, RUNWAY_EYE.z, RUNWAY_TARGET.x,
                        RUNWAY_TARGET.y, RUNWAY_TARGET.z))
                .client("handtruck: hold the forward key" + (sprintKey ? " and the sprint key" : "") + " (" + label
                        + ")", context -> {
                    walkTicks = 0;
                    walkStart = null;
                    walkSprinted = false;
                    context.minecraft().options.keyUp.setDown(true);
                    context.minecraft().options.keySprint.setDown(sprintKey);
                })
                .until("handtruck: walk " + WALK_TICKS + " ticks (" + label + ")", context -> {
                    LocalPlayer player = Objects.requireNonNull(context.minecraft().player, "player");
                    walkTicks++;
                    walkSprinted |= player.isSprinting();
                    if (walkTicks == WALK_RAMP)
                        walkStart = player.position();
                    if (walkTicks < WALK_TICKS)
                        return false;
                    Vec3 start = Objects.requireNonNull(walkStart, "walk start");
                    Vec3 end = player.position();
                    walkMeasured = Math.hypot(end.x - start.x, end.z - start.z) / (WALK_TICKS - WALK_RAMP);
                    return true;
                }, WALK_TICKS + 20)
                .client("handtruck: CHECK release the keys and judge the walk (" + label + ")", context -> {
                    context.minecraft().options.keyUp.setDown(false);
                    context.minecraft().options.keySprint.setDown(false);
                    if (!loaded && sprintKey) {
                        if (!walkSprinted)
                            throw new VisualTestException("control failed: with the empty truck the sprint key did "
                                    + "not start a sprint, so the loaded check would prove nothing");
                        sprintSpeed = walkMeasured;
                    } else if (!loaded) {
                        if (walkSprinted)
                            throw new VisualTestException("the walk without the sprint key sprinted");
                        walkingSpeed = walkMeasured;
                    } else {
                        if (walkSprinted)
                            throw new VisualTestException("a sprint started while carrying a loaded truck");
                        double share = walkMeasured / walkingSpeed;
                        if (share < SLOW_MIN || share > SLOW_MAX)
                            throw new VisualTestException(String.format(java.util.Locale.ROOT,
                                    "loaded with the sprint key held the player made %.3f blocks per tick, %.2f of the "
                                            + "walking speed %.3f (expected %.2f to %.2f)",
                                    walkMeasured, share, walkingSpeed, SLOW_MIN, SLOW_MAX));
                        pass(String.format(java.util.Locale.ROOT, "loaded walk %.3f b/t = %.2f of walking %.3f "
                                + "(sprint %.3f), no sprint", walkMeasured, share, walkingSpeed, sprintSpeed));
                    }
                    LOGGER.info(PREFIX + "handtruck: walk ({}): {} blocks per tick, sprinted {}", label,
                            String.format(java.util.Locale.ROOT, "%.3f", walkMeasured), walkSprinted);
                })
                .waitTicks(SETTLE_TICKS);
    }

    /**
     * Puts an elytra on, lifts the player {@link #GLIDE_HEIGHT} blocks with slow falling (no fall damage, long air
     * time) and pulses the jump key while falling, which is what starts a glide. With the empty truck a glide must
     * start; with a loaded one never, on the client or on the server.
     */
    private void glide(VisualScript script, String label, boolean loaded) {
        script.server("handtruck: elytra on, slow falling, " + GLIDE_HEIGHT + " blocks up (" + label + ")",
                (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0, false, false));
                    Vec3 up = Vec3.atLowerCornerOf(context.origin()).add(RUNWAY_EYE).add(0, GLIDE_HEIGHT, 0);
                    player.teleportTo(server.overworld(), up.x, up.y, up.z, -90.0F, 0.0F);
                    glideTicks = 0;
                    glided = false;
                })
                .until("handtruck: the client is in the air (" + label + ")", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && !player.onGround()
                            && player.getY() > context.origin().getY() + GLIDE_HEIGHT / 2.0;
                }, RESULT_TIMEOUT_TICKS)
                .until("handtruck: pulse the jump key for " + GLIDE_TICKS + " ticks (" + label + ")", context -> {
                    LocalPlayer player = Objects.requireNonNull(context.minecraft().player, "player");
                    glideTicks++;
                    context.minecraft().options.keyJump.setDown(glideTicks % 4 < 2);
                    glided |= player.isFallFlying();
                    return glideTicks >= GLIDE_TICKS;
                }, GLIDE_TICKS + 20)
                .serverUntil("handtruck: CHECK the glide (" + label + ")", (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    boolean serverGlides = player.isFallFlying();
                    if (!loaded && !glided && !serverGlides)
                        throw new VisualTestException("control failed: with the empty truck the jump key did not "
                                + "start a glide, so the loaded check would prove nothing");
                    if (loaded && (glided || serverGlides))
                        throw new VisualTestException("a glide started while carrying a loaded truck (client "
                                + glided + ", server " + serverGlides + ")");
                    if (loaded)
                        pass("no glide with a loaded truck");
                    LOGGER.info(PREFIX + "handtruck: glide ({}): client glided {}, server glides {}", label, glided,
                            serverGlides);
                    player.stopFallFlying();
                    player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                    player.removeEffect(MobEffects.SLOW_FALLING);
                    Vec3 feet = Vec3.atLowerCornerOf(context.origin()).add(RUNWAY_EYE).subtract(0, player.getEyeHeight(),
                            0);
                    player.teleportTo(server.overworld(), feet.x, feet.y, feet.z, -90.0F, 0.0F);
                    player.fallDistance = 0.0F;
                    return true;
                }, RESULT_TIMEOUT_TICKS)
                .client("handtruck: release the jump key",
                        context -> context.minecraft().options.keyJump.setDown(false))
                .until("handtruck: the client stands on the ground again", context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && player.onGround() && !player.isFallFlying()
                            && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
                }, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    // --- tooltip and refusals ------------------------------------------------------------------------------------------

    /** Opens the inventory with its key, rests the mouse on the loaded truck and shoots the tooltip. */
    private void tooltip(VisualScript script, String code, String label, String name, String stacksLine) {
        script.client("handtruck: open the inventory with its key (" + code + ")",
                        context -> KeyMapping.click(context.minecraft().options.keyInventory.getKey()))
                .until("handtruck: the inventory screen is open",
                        context -> context.minecraft().screen instanceof InventoryScreen, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("handtruck: CHECK rest the mouse on the loaded truck and read its tooltip (" + code + ")",
                        context -> {
                            Minecraft minecraft = context.minecraft();
                            InventoryScreen screen = (InventoryScreen) minecraft.screen;
                            ScreenInput.hover(minecraft, ScreenInput.hotbarSlot(screen, HOTBAR_TRUCK));
                            LocalPlayer player = Objects.requireNonNull(minecraft.player, "player");
                            ItemStack truck = player.getInventory().getItem(HOTBAR_TRUCK);
                            List<String> lines = truck.getTooltipLines(Item.TooltipContext.of(minecraft.level), player,
                                    TooltipFlag.Default.NORMAL).stream().map(Component::getString).toList();
                            if (lines.isEmpty() || !lines.getFirst().equals(name)
                                    || lines.stream().noneMatch(line -> line.equals(stacksLine)))
                                throw new VisualTestException("[" + code + "] the loaded truck's tooltip is " + lines
                                        + ", expected the name '" + name + "' and the line '" + stacksLine + "'");
                            pass("[" + code + "] tooltip " + lines);
                        })
                .waitTicks(SETTLE_TICKS)
                .shot(label)
                .client("handtruck: close the inventory",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("handtruck: the inventory is closed", context -> context.minecraft().screen == null,
                        RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /** The drop key with the loaded truck in hand: refused, the truck stays loaded in the hand, census exact. */
    private void dropRefused(VisualScript script, String label) {
        selectHotbar(script, HOTBAR_TRUCK, "the loaded hand truck", HandTruckItem::isLoaded);
        takeCensus(script, "before the drop key");
        script.client("handtruck: press the drop key",
                context -> KeyMapping.click(context.minecraft().options.keyDrop.getKey()));
        ArmVisualScenario.untilOrFail(script, "handtruck: the truck is back in hand and the refusal is shown",
                context -> {
                    LocalPlayer player = context.minecraft().player;
                    return player != null && HandTruckItem.isLoaded(player.getMainHandItem())
                            && ArmVisualScenario.overlayText(context).equals(message(WareworksLang.HAND_TRUCK_DROP_REFUSED));
                }, RESULT_TIMEOUT_TICKS, context -> "the client holds " + context.minecraft().player.getMainHandItem()
                        + ", action bar '" + ArmVisualScenario.overlayText(context) + "'");
        // The truck that came back is raised into the hand again by the equip animation first.
        script.waitTicks(SETTLE_TICKS).shotWithGui(label);
        script.server("handtruck: CHECK the drop key was refused, census exact", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            if (!HandTruckItem.isLoaded(player.getMainHandItem()))
                throw new VisualTestException("the server's truck left the hand: " + player.getMainHandItem());
            assertCensus(server.overworld(), player, "the refused drop key");
            pass("drop key refused with '" + message(WareworksLang.HAND_TRUCK_DROP_REFUSED) + "'");
        });
    }

    /**
     * With an empty hand the barrel opens; the loaded truck is picked up from the hotbar and put into a barrel slot with
     * two real clicks. The server hands it straight back into the player's inventory.
     */
    private void menuReturns(VisualScript script) {
        selectHotbar(script, HOTBAR_FREE, "nothing", ItemStack::isEmpty);
        standAndLookAt(script, "barrel", BARREL, Direction.NORTH);
        takeCensus(script, "before the barrel slot");
        rightClick(script, "the barrel with an empty hand");
        script.until("handtruck: the barrel screen is open",
                        context -> context.minecraft().screen instanceof ContainerScreen, RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS)
                .client("handtruck: pick the loaded truck up from the hotbar", context -> {
                    ContainerScreen screen = (ContainerScreen) context.minecraft().screen;
                    ScreenInput.click(context.minecraft(), ScreenInput.hotbarSlot(screen, HOTBAR_TRUCK));
                })
                .until("handtruck: the truck is on the cursor", context -> context.minecraft().player != null
                        && HandTruckItem.isLoaded(context.minecraft().player.containerMenu.getCarried()),
                        RESULT_TIMEOUT_TICKS)
                .client("handtruck: put it into the barrel's first slot", context -> {
                    ContainerScreen screen = (ContainerScreen) context.minecraft().screen;
                    ScreenInput.click(context.minecraft(), ScreenInput.menuSlot(screen, 0));
                });
        ArmVisualScenario.untilOrFail(script, "handtruck: the truck came back into the inventory", context -> {
            LocalPlayer player = context.minecraft().player;
            return player != null && player.containerMenu.getSlot(0).getItem().isEmpty()
                    && player.containerMenu.getCarried().isEmpty()
                    && HandTruckItem.isLoaded(player.getInventory().getItem(HOTBAR_TRUCK))
                    && ArmVisualScenario.overlayText(context).equals(message(WareworksLang.HAND_TRUCK_STAYS_WITH_YOU));
        }, RESULT_TIMEOUT_TICKS, context -> "barrel slot " + context.minecraft().player.containerMenu.getSlot(0)
                .getItem() + ", cursor " + context.minecraft().player.containerMenu.getCarried() + ", hotbar "
                + context.minecraft().player.getInventory().getItem(HOTBAR_TRUCK) + ", action bar '"
                + ArmVisualScenario.overlayText(context) + "'");
        script.shotWithGui("barrel-refused");
        script.server("handtruck: CHECK the barrel holds nothing, the truck is back, census exact",
                (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    BlockEntity barrel = server.overworld().getBlockEntity(context.origin().offset(BARREL));
                    if (!(barrel instanceof BarrelBlockEntity container) || !container.isEmpty())
                        throw new VisualTestException("the barrel is not empty: " + barrel);
                    if (!HandTruckItem.isLoaded(player.getInventory().getItem(HOTBAR_TRUCK)))
                        throw new VisualTestException("the server's truck is not back in hotbar slot "
                                + (HOTBAR_TRUCK + 1));
                    assertCensus(server.overworld(), player, "the truck put into the barrel");
                    pass("barrel slot refused, truck back in the hotbar");
                })
                .client("handtruck: close the barrel",
                        context -> ScreenInput.key(context.minecraft(), GLFW.GLFW_KEY_ESCAPE))
                .until("handtruck: the barrel screen is closed", context -> context.minecraft().screen == null,
                        RESULT_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);
    }

    /** A second, empty truck clicked on the barrel while the first one is loaded: refused, the barrel stays. */
    private void secondTruckRefused(VisualScript script, String label) {
        script.server("handtruck: give a second, empty hand truck", (server, context) -> context.serverPlayer(server)
                .getInventory().setItem(HOTBAR_FREE, new ItemStack(WareworksItems.HAND_TRUCK.get())));
        selectHotbar(script, HOTBAR_FREE, "the second, empty truck",
                stack -> stack.is(WareworksItems.HAND_TRUCK.get()) && !HandTruckItem.isLoaded(stack));
        standAndLookAt(script, "barrel", BARREL, Direction.NORTH);
        takeCensus(script, "before the second truck");
        rightClick(script, "the barrel with the second truck");
        ArmVisualScenario.untilOrFail(script, "handtruck: the second truck is refused",
                context -> ArmVisualScenario.overlayText(context)
                        .equals(message(WareworksLang.HAND_TRUCK_ALREADY_LOADED)),
                RESULT_TIMEOUT_TICKS, context -> "action bar '" + ArmVisualScenario.overlayText(context) + "'");
        script.shotWithGui(label);
        script.client("handtruck: CHECK the click opened no screen", context -> {
            if (context.minecraft().screen != null)
                throw new VisualTestException("the click with the second truck opened " + context.minecraft().screen);
        });
        script.server("handtruck: CHECK the barrel stands, the second truck is empty, census exact",
                (server, context) -> {
                    ServerPlayer player = context.serverPlayer(server);
                    if (!server.overworld().getBlockState(context.origin().offset(BARREL)).is(Blocks.BARREL))
                        throw new VisualTestException("the barrel was taken by a second truck");
                    if (HandTruckItem.isLoaded(player.getInventory().getItem(HOTBAR_FREE)))
                        throw new VisualTestException("the second truck got loaded");
                    assertCensus(server.overworld(), player, "the refused second truck");
                    player.getInventory().setItem(HOTBAR_FREE, ItemStack.EMPTY);
                    pass("second truck refused with '" + message(WareworksLang.HAND_TRUCK_ALREADY_LOADED) + "'");
                });
        selectHotbar(script, HOTBAR_FREE, "nothing again", ItemStack::isEmpty);
    }

    // --- census --------------------------------------------------------------------------------------------------------

    private void takeCensus(VisualScript script, String moment) {
        script.server("handtruck: census " + moment, (server, context) -> {
            ServerLevel level = server.overworld();
            if (!SceneItemCensus.isFullyLoaded(level, censusBox))
                throw new VisualTestException("the census box is not loaded");
            census = SceneItemCensus.take(level, censusBox, context.serverPlayer(server));
        });
    }

    private void assertCensus(ServerLevel level, ServerPlayer player, String step) {
        Map<ItemKey, Long> expected = Objects.requireNonNull(census, "no census was taken before " + step);
        SceneItemCensus.assertEquals(level, censusBox, expected, "handtruck: " + step, player);
    }

    // --- small helpers -------------------------------------------------------------------------------------------------

    private synchronized void pass(String what) {
        passed.add(what);
        LOGGER.info(PREFIX + "handtruck: CHECK PASS: {}", what);
    }

    /** A hand truck message in the client's current language, checked to be translated. */
    private static String message(String relativeKey) {
        String key = "wareworks." + relativeKey;
        String text = Component.translatable(key).getString();
        if (text.equals(key))
            throw new VisualTestException("the message " + key + " is not translated");
        return text;
    }

    private static BlockPos at(VisualContext context, BlockPos offset) {
        return context.origin().offset(offset);
    }
}
