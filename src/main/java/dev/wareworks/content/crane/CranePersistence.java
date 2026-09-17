package dev.wareworks.content.crane;

import java.util.Optional;
import java.util.UUID;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneInterruption;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.crane.CraneState;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * NBT form of a stacker crane's state ({@code docs/stacker-crane.md} §4-5, {@code docs/warehouse-system.md} §8 "Server
 * restart"):
 * <pre>
 * Crane: { Pose: {X: double, Y: double, Arm: double, Side: "L"|"R"}, Target: {...}, Phase: "TRAVEL_TO_TARGET",
 *          PhaseTicks: int, RetryTicks: int, Awaiting: bool, Interruption?: "TARGET_MISSING",
 *          Job?: { Id: UUID, Type: "STORE"|"RETRIEVE", Source: {X, Y, Side}, TargetLocation: {X, Y, Side},
 *                  TargetKind: "STORAGE"|"INPUT"|"OUTPUT", Item: &lt;ItemKey&gt;, Planned: int, Request?: UUID,
 *                  Picked: bool, PickedAmount: int, Delivered: int } }
 * CraneSync (client packets): { Pose, Target, Phase, Paused: bool }
 * </pre>
 * The pause flag is not saved (it is recomputed every tick). Rack positions are aisle-local, so a crane moved without
 * rotation keeps its job. Writing never throws; reading never throws and treats the data as untrusted: positions are
 * clamped to the address limits, tick counters to {@value #MAX_SAVED_TICKS}, and an invalid job is dropped (its held
 * items are then reconciled by the block entity). A loaded state is made consistent by
 * {@code CraneStateMachine#resume} on the first server tick.
 */
final class CranePersistence {
    static final String STATE_TAG = "Crane";
    static final String SYNC_TAG = "CraneSync";
    static final String HEAD_TAG = "Head";
    /** Upper bound for saved phase and retry tick counters (the largest configurable retry interval). */
    static final int MAX_SAVED_TICKS = 1200;

    private static final String POSE = "Pose";
    private static final String TARGET = "Target";
    private static final String PHASE = "Phase";
    private static final String PHASE_TICKS = "PhaseTicks";
    private static final String RETRY_TICKS = "RetryTicks";
    private static final String AWAITING = "Awaiting";
    private static final String PAUSED = "Paused";
    private static final String INTERRUPTION = "Interruption";
    private static final String JOB = "Job";
    private static final String X = "X";
    private static final String Y = "Y";
    private static final String ARM = "Arm";
    private static final String SIDE = "Side";
    private static final String ID = "Id";
    private static final String TYPE = "Type";
    private static final String SOURCE = "Source";
    private static final String TARGET_LOCATION = "TargetLocation";
    private static final String TARGET_KIND = "TargetKind";
    private static final String ITEM = "Item";
    private static final String PLANNED = "Planned";
    private static final String REQUEST = "Request";
    private static final String PICKED = "Picked";
    private static final String PICKED_AMOUNT = "PickedAmount";
    private static final String DELIVERED = "Delivered";

    private CranePersistence() {
    }

    // --- disk ----------------------------------------------------------------------------------------------------

    static void writeState(CompoundTag tag, CraneState<ItemKey, RackPosition> state, HolderLookup.Provider registries) {
        CompoundTag stateTag = new CompoundTag();
        stateTag.put(POSE, writePose(state.pose()));
        stateTag.put(TARGET, writePose(state.target()));
        stateTag.putString(PHASE, state.phase().name());
        stateTag.putInt(PHASE_TICKS, state.phaseTicks());
        stateTag.putInt(RETRY_TICKS, state.retryTicks());
        stateTag.putBoolean(AWAITING, state.awaitingResult());
        state.interruption().ifPresent(interruption -> stateTag.putString(INTERRUPTION, interruption.name()));
        state.job().ifPresent(job -> {
            try {
                writeJob(job, registries).ifPresent(jobTag -> stateTag.put(JOB, jobTag));
            } catch (RuntimeException e) {
                Wareworks.LOGGER.warn("Could not save stacker crane job {}", job, e);
            }
        });
        tag.put(STATE_TAG, stateTag);
    }

    static CraneState<ItemKey, RackPosition> readState(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(STATE_TAG, Tag.TAG_COMPOUND))
            return CraneState.idle(StackerCraneBlockEntity.HOME_POSE);
        CompoundTag stateTag = tag.getCompound(STATE_TAG);
        CranePose pose = readPose(stateTag.getCompound(POSE));
        try {
            CranePose target = stateTag.contains(TARGET, Tag.TAG_COMPOUND) ? readPose(stateTag.getCompound(TARGET))
                    : pose.withArm(CranePose.RETRACTED);
            CranePhase phase = CranePhase.byName(stateTag.getString(PHASE)).orElse(CranePhase.IDLE);
            Optional<CraneInterruption> interruption = stateTag.contains(INTERRUPTION, Tag.TAG_STRING)
                    ? interruptionByName(stateTag.getString(INTERRUPTION)) : Optional.empty();
            Optional<TransportJob<ItemKey, RackPosition>> job = stateTag.contains(JOB, Tag.TAG_COMPOUND)
                    ? readJob(stateTag.getCompound(JOB), registries) : Optional.empty();
            return new CraneState<>(pose, pose, target, phase,
                    Mth.clamp(stateTag.getInt(PHASE_TICKS), 0, MAX_SAVED_TICKS),
                    Mth.clamp(stateTag.getInt(RETRY_TICKS), 0, MAX_SAVED_TICKS), stateTag.getBoolean(AWAITING), false,
                    job, interruption);
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Skipping unreadable stacker crane state {}", stateTag, e);
            return CraneState.idle(pose);
        }
    }

    static Optional<CompoundTag> writeJob(TransportJob<ItemKey, RackPosition> job, HolderLookup.Provider registries) {
        Tag keyTag = job.key().save(registries);
        if (keyTag instanceof CompoundTag compound && compound.isEmpty())
            return Optional.empty(); // unencodable key, already logged by ItemKey
        CompoundTag jobTag = new CompoundTag();
        jobTag.putUUID(ID, job.id());
        jobTag.putString(TYPE, job.type().name());
        jobTag.put(SOURCE, CraneJobSummary.writeRack(job.source()));
        jobTag.put(TARGET_LOCATION, CraneJobSummary.writeRack(job.target()));
        jobTag.putString(TARGET_KIND, job.targetKind().name());
        jobTag.put(ITEM, keyTag);
        jobTag.putInt(PLANNED, job.plannedAmount());
        job.requestId().ifPresent(id -> jobTag.putUUID(REQUEST, id));
        jobTag.putBoolean(PICKED, job.picked());
        jobTag.putInt(PICKED_AMOUNT, job.pickedAmount());
        jobTag.putInt(DELIVERED, job.deliveredAmount());
        return Optional.of(jobTag);
    }

    static Optional<TransportJob<ItemKey, RackPosition>> readJob(CompoundTag jobTag, HolderLookup.Provider registries) {
        try {
            Optional<JobType> type = JobType.byName(jobTag.getString(TYPE));
            Optional<LocationKind> targetKind = LocationKind.byName(jobTag.getString(TARGET_KIND));
            Optional<RackPosition> source = CraneJobSummary.readRack(jobTag.getCompound(SOURCE));
            Optional<RackPosition> target = CraneJobSummary.readRack(jobTag.getCompound(TARGET_LOCATION));
            if (!jobTag.hasUUID(ID) || type.isEmpty() || targetKind.isEmpty() || source.isEmpty() || target.isEmpty()) {
                Wareworks.LOGGER.warn("Skipping invalid stacker crane job {}", jobTag);
                return Optional.empty();
            }
            Optional<ItemKey> key = ItemKey.load(registries, jobTag.get(ITEM));
            if (key.isEmpty())
                return Optional.empty();
            Optional<UUID> request = jobTag.hasUUID(REQUEST) ? Optional.of(jobTag.getUUID(REQUEST)) : Optional.empty();
            return Optional.of(new TransportJob<>(jobTag.getUUID(ID), type.get(), source.get(), target.get(),
                    targetKind.get(), key.get(), jobTag.getInt(PLANNED), request, jobTag.getBoolean(PICKED),
                    jobTag.getInt(PICKED_AMOUNT), jobTag.getInt(DELIVERED)));
        } catch (RuntimeException e) {
            Wareworks.LOGGER.warn("Skipping unreadable stacker crane job {}", jobTag, e);
            return Optional.empty();
        }
    }

    // --- client sync ---------------------------------------------------------------------------------------------

    static CompoundTag writeSync(CraneState<?, ?> state) {
        CompoundTag tag = new CompoundTag();
        tag.put(POSE, writePose(state.pose()));
        tag.put(TARGET, writePose(state.target()));
        tag.putString(PHASE, state.phase().name());
        tag.putBoolean(PAUSED, state.paused());
        return tag;
    }

    /** A job-less state with the synced pose, target, phase and pause flag. Never throws. */
    static <K, L> CraneState<K, L> readSync(CompoundTag tag) {
        CranePose pose = readPose(tag.getCompound(POSE));
        CranePose target = tag.contains(TARGET, Tag.TAG_COMPOUND) ? readPose(tag.getCompound(TARGET)) : pose;
        return new CraneState<>(pose, pose, target, CranePhase.byName(tag.getString(PHASE)).orElse(CranePhase.IDLE), 0, 0,
                false, tag.getBoolean(PAUSED), Optional.empty(), Optional.empty());
    }

    // --- poses ---------------------------------------------------------------------------------------------------

    static CompoundTag writePose(CranePose pose) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble(X, pose.x());
        tag.putDouble(Y, pose.y());
        tag.putDouble(ARM, pose.arm());
        tag.putString(SIDE, String.valueOf(pose.side().letter()));
        return tag;
    }

    /** A pose from untrusted data: non-finite values become 0, coordinates are clamped to the address limits. */
    static CranePose readPose(CompoundTag tag) {
        String sideText = tag.getString(SIDE);
        Side side = sideText.length() == 1 ? Side.fromLetter(sideText.charAt(0)).orElse(null) : null;
        CranePose sanitized = CranePose.sanitized(tag.getDouble(X), tag.getDouble(Y), tag.getDouble(ARM), side);
        return new CranePose(Mth.clamp(sanitized.x(), 0.0, AisleGeometry.MAX_LENGTH),
                Mth.clamp(sanitized.y(), 0.0, AisleGeometry.MAX_HEIGHT - 1), sanitized.arm(), sanitized.side());
    }

    private static Optional<CraneInterruption> interruptionByName(String name) {
        for (CraneInterruption interruption : CraneInterruption.values()) {
            if (interruption.name().equals(name))
                return Optional.of(interruption);
        }
        return Optional.empty();
    }
}
