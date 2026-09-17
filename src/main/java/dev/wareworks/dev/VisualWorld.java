package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * World preparation steps of every run: title screen, client options, a fresh superflat world (named by the scenario's
 * {@link VisualWorldProfile} and deleted first), world settings and the player.
 * <p>
 * World creation uses {@code WorldOpenFlows#createFreshLevel} with the vanilla {@code minecraft:flat} preset, cheats on,
 * peaceful, no structures, no bonus chest, mob spawning, daylight and weather cycles off. The profile decides the world
 * folder, the name shown in the world list, the game mode, and whether the player is a pure camera (spectator with zero
 * interaction range, so nothing is ever targeted) or a real player meant to walk around afterwards.
 */
final class VisualWorld {
    /** Fixed seed, so every run gets the same world. */
    private static final long WORLD_SEED = 20_260_915L;
    private static final long NOON_DAY_TIME = 6_000L;
    /** Clear weather duration in ticks (the weather cycle is off anyway). */
    private static final int CLEAR_WEATHER_TICKS = 1_000_000;
    private static final int CAMERA_FOV_DEGREES = 70;
    private static final int RENDER_DISTANCE_CHUNKS = 8;
    private static final double MUTED_VOLUME = 0.0;
    /** Block and entity interaction range of the camera player (synced attributes), so it never targets anything. */
    private static final double NO_INTERACTION_RANGE = 0.0;
    /** Client ticks on the loading overlay and title screen (20 per second). */
    private static final int TITLE_SCREEN_TIMEOUT_TICKS = 20 * 60 * 4;
    /** Client ticks after world creation until the player is in the world (the blocking load itself does not tick). */
    private static final int WORLD_JOIN_TIMEOUT_TICKS = 20 * 120;
    private static final int WORLD_SETTLE_TICKS = 40;

    private VisualWorld() {
    }

    static void prepare(VisualScript script, VisualWorldProfile profile) {
        script.client("delete screenshots of an earlier run", VisualContext::cleanScreenshots)
                .until("wait for the title screen", VisualWorld::atTitleScreen, TITLE_SCREEN_TIMEOUT_TICKS)
                .client("arm the run watchdog",
                        context -> context.watchdog().rearm(VisualTestHarness.RUN_TIMEOUT_MILLIS, "run"))
                .client("apply client options", VisualWorld::applyClientOptions)
                .client("create the world " + profile.worldFolder(), context -> createWorld(context, profile))
                .until("wait for the player in the world", VisualWorld::inWorld, WORLD_JOIN_TIMEOUT_TICKS)
                .server("world settings and player", (server, context) -> applyWorldSettings(server, context, profile))
                .waitTicks(WORLD_SETTLE_TICKS);
    }

    /**
     * Saves the world and returns to the title screen, which stops the integrated server and releases the session lock.
     * A scenario whose world is meant to be kept ({@link VisualWorldProfile#playable}) ends with this, so the save on
     * disk is complete and consistent before the harness stops the game.
     */
    static void saveAndQuit(VisualScript script) {
        script.server("save the world", (server, context) -> server.saveEverything(true, true, true))
                .client("quit to the title screen", VisualWorld::leaveWorld)
                .until("wait until the world is saved and the server has stopped", VisualWorld::leftWorld,
                        TITLE_SCREEN_TIMEOUT_TICKS);
    }

    /**
     * A real save, quit and rejoin of the same world: back to the title screen (which stops the integrated server and
     * saves), then opening the world of {@code profile} again and re-applying the world settings. Used by the robustness
     * scenario to reload a world in the middle of a crane job ({@code docs/warehouse-system.md} §8).
     */
    static void reload(VisualScript script, VisualWorldProfile profile) {
        script.client("quit to the title screen", VisualWorld::leaveWorld)
                .until("wait until the world is left and the server has stopped", VisualWorld::leftWorld,
                        TITLE_SCREEN_TIMEOUT_TICKS)
                .client("open the world " + profile.worldFolder() + " again",
                        context -> openExistingWorld(context, profile))
                .until("wait for the player in the world again", VisualWorld::inWorld, WORLD_JOIN_TIMEOUT_TICKS)
                .server("world settings and player", (server, context) -> applyWorldSettings(server, context, profile))
                .waitTicks(WORLD_SETTLE_TICKS);
    }

    /**
     * Exactly the sequence of vanilla's "Save and Quit to Title" button ({@code PauseScreen#onDisconnect}). The first
     * call is the important one: closing the connection is what makes the integrated server halt. Without it,
     * {@link Minecraft#disconnect(Screen)} waits for a server shutdown that never comes and the client hangs for ever
     * in its own tick loop.
     * <p>
     * Queued with {@link Minecraft#tell}, not run inside the tick event: disconnecting pumps the client's own loop
     * until the integrated server has stopped. That pump is {@code runTick(false)}, which renders but does not call
     * {@code Minecraft#tick}, so it delivers render frames (and no client ticks) into the harness while the level is
     * torn down - hence the guards in {@code VisualTestHarness#frame()}.
     */
    private static void leaveWorld(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        minecraft.tell(() -> {
            if (minecraft.level != null)
                minecraft.level.disconnect();
            minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            minecraft.setScreen(new TitleScreen());
        });
    }

    /** The world is really gone: no client level, no integrated server left to stop, title screen shown. */
    private static boolean leftWorld(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        return minecraft.level == null && minecraft.getSingleplayerServer() == null && atTitleScreen(context);
    }

    private static void openExistingWorld(VisualContext context, VisualWorldProfile profile) {
        Minecraft minecraft = context.minecraft();
        minecraft.tell(() -> minecraft.createWorldOpenFlows().openWorld(profile.worldFolder(),
                () -> LOGGER.error(PREFIX + "could not open the world {} again", profile.worldFolder())));
    }

    private static boolean atTitleScreen(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        if (minecraft.getOverlay() != null)
            return false;
        if (minecraft.screen instanceof AccessibilityOnboardingScreen onboarding) {
            LOGGER.info(PREFIX + "dismissing the accessibility onboarding screen");
            onboarding.onClose();
            return false;
        }
        return minecraft.screen instanceof TitleScreen;
    }

    private static void applyClientOptions(VisualContext context) {
        Options options = context.minecraft().options;
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.hideGui = true;
        options.fov().set(CAMERA_FOV_DEGREES);
        options.renderDistance().set(RENDER_DISTANCE_CHUNKS);
        options.bobView().set(false);
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(MUTED_VOLUME);
    }

    private static void createWorld(VisualContext context, VisualWorldProfile profile) throws IOException {
        Minecraft minecraft = context.minecraft();
        LevelStorageSource storage = minecraft.getLevelSource();
        String folder = profile.worldFolder();
        if (storage.levelExists(folder)) {
            LOGGER.info(PREFIX + "deleting the world {} of an earlier run", folder);
            try (LevelStorageSource.LevelStorageAccess access = storage.createAccess(folder)) {
                access.deleteLevel();
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        // The first argument of createFreshLevel is the save FOLDER; LevelSettings carries the name the world list shows.
        LevelSettings settings = new LevelSettings(profile.levelName(), profile.gameMode(), false, Difficulty.PEACEFUL,
                true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = new WorldOptions(WORLD_SEED, false, false);
        Screen lastScreen = minecraft.screen;
        // Queued, not run inside the tick event: world loading blocks the client thread in its own render loop.
        minecraft.tell(() -> minecraft.createWorldOpenFlows().createFreshLevel(folder, settings, worldOptions,
                VisualWorld::flatDimensions, lastScreen));
    }

    /** The dimensions of the vanilla {@code minecraft:flat} world preset (like {@code WorldPresets#createNormalWorldDimensions}). */
    private static WorldDimensions flatDimensions(RegistryAccess registries) {
        return registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
                .createWorldDimensions();
    }

    private static boolean inWorld(VisualContext context) {
        Minecraft minecraft = context.minecraft();
        return minecraft.level != null && minecraft.player != null && minecraft.screen == null
                && minecraft.getOverlay() == null && minecraft.getSingleplayerServer() != null;
    }

    private static void applyWorldSettings(MinecraftServer server, VisualContext context, VisualWorldProfile profile) {
        GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.setDefaultGameType(profile.gameMode());
        ServerLevel level = server.overworld();
        level.setDayTime(NOON_DAY_TIME);
        level.setWeatherParameters(CLEAR_WEATHER_TICKS, 0, false, false);
        ServerPlayer player = context.serverPlayer(server);
        player.setGameMode(profile.gameMode());
        if (profile.cameraPlayer()) {
            // Nothing under the crosshair: Create draws value boxes of the targeted block even with the GUI hidden.
            setBaseValue(player, Attributes.BLOCK_INTERACTION_RANGE, NO_INTERACTION_RANGE);
            setBaseValue(player, Attributes.ENTITY_INTERACTION_RANGE, NO_INTERACTION_RANGE);
        } else {
            // A world a player opens afterwards: the vanilla reach, or nothing could be clicked, mined or placed.
            resetToDefault(player, Attributes.BLOCK_INTERACTION_RANGE);
            resetToDefault(player, Attributes.ENTITY_INTERACTION_RANGE);
        }
    }

    private static void setBaseValue(ServerPlayer player, Holder<Attribute> attribute, double value) {
        instanceOf(player, attribute).setBaseValue(value);
    }

    /** Restores the attribute's own default, so a player built by a scenario reaches as far as any vanilla player. */
    private static void resetToDefault(ServerPlayer player, Holder<Attribute> attribute) {
        instanceOf(player, attribute).setBaseValue(attribute.value().getDefaultValue());
    }

    private static AttributeInstance instanceOf(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null)
            throw new VisualTestException("the player has no attribute " + attribute.getRegisteredName());
        return instance;
    }
}
