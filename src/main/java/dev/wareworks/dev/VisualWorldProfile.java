package dev.wareworks.dev;

import java.util.Objects;

import net.minecraft.world.level.GameType;

/**
 * How the harness prepares the world of a scenario (dev tooling, ADR-014).
 * <p>
 * The screenshot scenarios use {@link #camera()}: a throw-away world whose player is a spectator with <b>zero</b>
 * interaction range, so the crosshair never targets anything and Create draws no value box into a shot. A scenario that
 * builds a world meant to be <b>kept and walked around in</b> uses {@link #playable}, which puts the player into a real
 * game mode and restores the vanilla interaction ranges.
 *
 * @param worldFolder   folder under {@code <gameDir>/saves}; an earlier world of that name is deleted at the start of
 *                      every run, so a scenario always builds from scratch
 * @param levelName     the name shown in the world list ({@code LevelSettings#levelName}), which may differ from the
 *                      folder name
 * @param gameMode      the game mode of the player and the world default
 * @param cameraPlayer  {@code true} for a pure camera (spectator, no interaction range); {@code false} to restore the
 *                      vanilla interaction ranges, which a world a player opens afterwards needs
 */
public record VisualWorldProfile(String worldFolder, String levelName, GameType gameMode, boolean cameraPlayer) {
    public VisualWorldProfile {
        Objects.requireNonNull(worldFolder, "worldFolder");
        Objects.requireNonNull(levelName, "levelName");
        Objects.requireNonNull(gameMode, "gameMode");
        if (worldFolder.isBlank())
            throw new IllegalArgumentException("worldFolder must not be blank");
        if (levelName.isBlank())
            throw new IllegalArgumentException("levelName must not be blank");
    }

    /** The screenshot scenarios: throw-away world {@code wareworks_visual} with a spectator camera that touches nothing. */
    public static VisualWorldProfile camera() {
        return new VisualWorldProfile("wareworks_visual", "wareworks_visual", GameType.SPECTATOR, true);
    }

    /** A world built to be kept: a real game mode and the vanilla interaction ranges. */
    public static VisualWorldProfile playable(String worldFolder, String levelName, GameType gameMode) {
        return new VisualWorldProfile(worldFolder, levelName, gameMode, false);
    }
}
