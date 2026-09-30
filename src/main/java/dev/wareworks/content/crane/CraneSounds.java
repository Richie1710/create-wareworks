package dev.wareworks.content.crane;

import java.util.Objects;

import com.simibubi.create.AllSoundEvents;

import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneSoundCues;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.CraneSpeeds;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Plays the sounds of one stacker crane on the server ({@code docs/stacker-crane.md} §8): {@link CraneSoundCues} decides
 * when, this class maps each cue to an existing Create or vanilla sound event and plays it at the moving part.
 * <p>
 * Sounds are played once on the server with a {@code null} player, which broadcasts them to every player in range
 * ({@code Level#playSound}; a {@code null} player on a client plays nothing). No custom sound events are registered and no
 * client-side loop is used: the cues are short and rate-limited ({@link CraneSoundCues}), and the kinetic hum at the dock
 * comes from Create's {@code KineticBlockEntity#tickAudio}.
 * <p>
 * Owned by {@link CraneExecution}; server thread only. Calls on a client level do nothing.
 */
final class CraneSounds {
    /** Height of the base above the crane's floor, in blocks (wheels on the rail). */
    private static final double BASE_HEIGHT = 0.35;
    /** Height of the carriage deck above its level, in blocks. */
    private static final double CARRIAGE_HEIGHT = 0.7;

    /** Where on the crane a sound comes from. */
    private enum Anchor {
        BASE,
        CARRIAGE,
        GRABBER
    }

    @FunctionalInterface
    private interface Emitter {
        void play(Level level, Vec3 pos, float volume, float pitch);
    }

    /**
     * One cue's sound: event, anchor, base volume (multiplied with the cue's volume factor), pitch and random pitch spread
     * (pitch is {@code pitch + random · spread}). Create's wrapped entries already scale their vanilla events, so their base
     * volumes are higher.
     */
    private record Voice(Anchor anchor, float volume, float pitch, float pitchSpread, Emitter emitter) {
        static Voice create(Anchor anchor, AllSoundEvents.SoundEntry entry, float volume, float pitch, float pitchSpread) {
            return new Voice(anchor, volume, pitch, pitchSpread,
                    (level, pos, v, p) -> entry.play(level, null, pos, v, p));
        }

        static Voice vanilla(Anchor anchor, SoundEvent event, float volume, float pitch, float pitchSpread) {
            return new Voice(anchor, volume, pitch, pitchSpread,
                    (level, pos, v, p) -> level.playSound(null, pos.x, pos.y, pos.z, event, SoundSource.BLOCKS, v, p));
        }
    }

    /** Like Create's contraptions: a wooden trapdoor and chest creak when the crane sets off. */
    private static final Voice TRAVEL_START =
            Voice.create(Anchor.CARRIAGE, AllSoundEvents.CONTRAPTION_ASSEMBLE, 0.6F, 1.1F, 0.0F);
    /** Like Create's elevator pulley arriving: an iron trapdoor closes. */
    private static final Voice TRAVEL_STOP =
            Voice.create(Anchor.CARRIAGE, AllSoundEvents.CONTRAPTION_DISASSEMBLE, 0.75F, 0.8F, 0.0F);
    /**
     * The whole machine swinging a quarter turn at a corner: a heavy iron trapdoor, deep and slow, at the chassis
     * (M21, ADR-033).
     * <p>
     * It was Create's {@code COGS} rumble, which is the one sample a player will <b>never</b> hear it over: Create
     * drones that very event continuously at volume 1.5 for every cogwheel, large cogwheel and gearbox within 16
     * blocks ({@code SoundScapes#cogwheel}), so a 0.35 cue in a warehouse driven by a cogwheel is masked by its own
     * drivetrain and reads as "Cogwheels rumble" in the subtitles either way (M21 review fix). Create's wrench sound
     * is not used either — its subtitle says "Wrench used" — and neither is the travel-stop cue, which already means
     * "arrived"; the iron trapdoor is its sibling ({@code CONTRAPTION_DISASSEMBLE} is an iron trapdoor closing), a
     * quarter turn lower, so the swing and the stop belong to one machine without sounding the same.
     */
    private static final Voice TURN =
            Voice.vanilla(Anchor.BASE, SoundEvents.IRON_TRAPDOOR_OPEN, 0.4F, 0.55F, 0.05F);
    /**
     * The same turn locking in, in the tick the machine squares up with its new aisle — three ticks after the rumble
     * at the default speed and penalty: a metal step, deeper than a rail clack, so the swing has an end a player
     * hears.
     */
    private static final Voice TURN_SETTLE = Voice.vanilla(Anchor.BASE, SoundEvents.METAL_STEP, 0.25F, 0.5F, 0.0F);
    /** Wheels over a rail joint: a quiet, low metal step. */
    private static final Voice RAIL_CLACK = Voice.vanilla(Anchor.BASE, SoundEvents.METAL_STEP, 0.2F, 0.6F, 0.15F);
    /** The hoist moving the carriage: a chain step. */
    private static final Voice LIFT_CHAIN = Voice.vanilla(Anchor.CARRIAGE, SoundEvents.CHAIN_STEP, 0.3F, 0.8F, 0.2F);
    /** The telescopic arm: a soft, high piston. */
    private static final Voice ARM_EXTEND = Voice.vanilla(Anchor.CARRIAGE, SoundEvents.PISTON_EXTEND, 0.1F, 1.5F, 0.1F);
    private static final Voice ARM_RETRACT = Voice.vanilla(Anchor.CARRIAGE, SoundEvents.PISTON_CONTRACT, 0.1F, 1.5F, 0.1F);
    /** Picking items, like Create's mechanical arm (item pickup, low pitch). */
    private static final Voice PICK = Voice.vanilla(Anchor.GRABBER, SoundEvents.ITEM_PICKUP, 0.15F, 0.5F, 0.25F);
    /** Dropping items, like an item landing on a Create depot. */
    private static final Voice DROP = Voice.create(Anchor.GRABBER, AllSoundEvents.DEPOT_PLOP, 1.0F, 1.0F, 0.0F);

    private final CraneSoundCues cues = new CraneSoundCues();

    /** After a server tick of {@code crane}, whose pose was {@code before} at the start of the tick. */
    void afterTick(Level level, StackerCraneBlockEntity crane, CranePose before, CraneState<?, ?> after,
            CraneSpeeds speeds) {
        for (CraneSoundCues.Sound sound : cues.motion(level.getGameTime(), before, after.pose(), after.target(), speeds,
                after.paused()))
            play(level, crane, after.pose(), sound);
    }

    /** The crane's phase changed ({@code StackerCraneBlockEntity#onPhaseChanged}); the state is already the new one. */
    void onPhaseChanged(Level level, StackerCraneBlockEntity crane, CranePhase from, CranePhase to) {
        CraneState<?, ?> state = crane.craneState();
        cues.phaseChanged(from, to, state.pose(), state.target())
                .ifPresent(sound -> play(level, crane, state.pose(), sound));
    }

    /** A real pick moved {@code amount} items into the handling head. */
    void onPicked(Level level, StackerCraneBlockEntity crane, int amount) {
        CraneSoundCues.picked(amount).ifPresent(sound -> play(level, crane, crane.craneState().pose(), sound));
    }

    /** A real drop delivered {@code amount} items from the handling head. */
    void onDropped(Level level, StackerCraneBlockEntity crane, int amount) {
        CraneSoundCues.dropped(amount).ifPresent(sound -> play(level, crane, crane.craneState().pose(), sound));
    }

    private static void play(Level level, StackerCraneBlockEntity crane, CranePose pose, CraneSoundCues.Sound sound) {
        Objects.requireNonNull(level, "level");
        if (level.isClientSide)
            return;
        Voice voice = voiceOf(sound.cue());
        float pitch = voice.pitch() + level.random.nextFloat() * voice.pitchSpread();
        Vec3 at = position(crane, pose, voice.anchor());
        voice.emitter().play(level, at, (float) (voice.volume() * sound.volume()), pitch);
    }

    private static Voice voiceOf(CraneSoundCues.Cue cue) {
        return switch (cue) {
            case TRAVEL_START -> TRAVEL_START;
            case TRAVEL_STOP -> TRAVEL_STOP;
            case TURN -> TURN;
            case TURN_SETTLE -> TURN_SETTLE;
            case RAIL_CLACK -> RAIL_CLACK;
            case LIFT_CHAIN -> LIFT_CHAIN;
            case ARM_EXTEND -> ARM_EXTEND;
            case ARM_RETRACT -> ARM_RETRACT;
            case PICK -> PICK;
            case DROP -> DROP;
        };
    }

    /**
     * World position of {@code anchor} for the crane at {@code pose}: base on the rail, carriage deck, grabber at the
     * arm tip.
     * <p>
     * Measured through the warehouse's own rails, not along the dock's aisle: once the crane has turned a corner its
     * position runs down another line entirely, and a sound played at the dock's aisle would come from the wrong side
     * of the room (ADR-033).
     */
    private static Vec3 position(StackerCraneBlockEntity crane, CranePose pose, Anchor anchor) {
        WarehouseLayout warehouse = crane.warehouse();
        Vec3 floor = Vec3.atBottomCenterOf(crane.getBlockPos()).add(warehouse.railOffset(pose.branch(), pose.x()));
        return switch (anchor) {
            case BASE -> floor.add(0.0, BASE_HEIGHT, 0.0);
            case CARRIAGE -> floor.add(0.0, pose.y() + CARRIAGE_HEIGHT, 0.0);
            case GRABBER -> floor.add(0.0, pose.y() + CARRIAGE_HEIGHT, 0.0)
                    .add(Vec3.atLowerCornerOf(warehouse.sideDirection(pose.branch(), pose.side()).getNormal())
                            .scale(pose.arm()));
        };
    }
}
