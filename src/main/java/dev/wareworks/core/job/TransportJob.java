package dev.wareworks.core.job;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.warehouse.LocationKind;

/**
 * One transport order for a crane ({@code docs/warehouse-system.md} §7): move up to {@link #plannedAmount()} items of
 * {@link #key()} from {@link #source()} to {@link #target()}.
 * <p>
 * Immutable; progress creates a copy with the same {@link #id()}:
 * <ul>
 *   <li>{@link #withPicked(int)} records the real pick result once ({@link #picked()} becomes true; the amount may be
 *       lower than planned, down to 0).</li>
 *   <li>{@link #plusDelivered(int)} adds a real drop result. {@link #heldAmount()} = picked − delivered is what the
 *       handling head carries.</li>
 *   <li>{@link #withTarget} reroutes the leftovers (§8); the target kind must be allowed for the type
 *       ({@link JobType#allowsTarget}).</li>
 *   <li>{@link #withoutRequest()} detaches a retrieve job from a request that no longer exists.</li>
 * </ul>
 * All components are plain values (UUIDs, enums, ints), so the job persists as is. The canonical constructor validates
 * everything and throws {@link IllegalArgumentException} for inconsistent data; loaders catch that and skip the entry.
 *
 * @param id              stable identity, also across restarts
 * @param type            store or retrieve
 * @param source          where the items are picked ({@link JobType#sourceKind()})
 * @param target          where the items are dropped
 * @param targetKind      the kind of {@code target}
 * @param key             item key (exact identity)
 * @param plannedAmount   amount the planner reserved, at least 1
 * @param requestId       who is waiting for these items: the retrieval request a {@code RETRIEVE} job serves, or the
 *                        ingredient line ({@code core.production.SupplyLine}) of the production order a
 *                        {@code SUPPLY} job serves. Always empty for store jobs — nothing asked for those items, they
 *                        simply arrived at an input ({@link JobType#mayCarryRequestId()})
 * @param picked          whether the pick happened
 * @param pickedAmount    real pick result, {@code 0..plannedAmount}; 0 while not picked
 * @param deliveredAmount sum of the real drop results, {@code 0..pickedAmount}; 0 while not picked
 * @param <K>             item key type
 * @param <L>             location type
 */
public record TransportJob<K, L>(UUID id, JobType type, L source, L target, LocationKind targetKind, K key,
        int plannedAmount, Optional<UUID> requestId, boolean picked, int pickedAmount, int deliveredAmount) {
    public TransportJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestId, "requestId");
        if (plannedAmount < 1)
            throw new IllegalArgumentException("plannedAmount must be at least 1: " + plannedAmount);
        if (!type.allowsTarget(targetKind))
            throw new IllegalArgumentException(type + " job cannot drop at a " + targetKind + " location");
        if (requestId.isPresent() && !type.mayCarryRequestId())
            throw new IllegalArgumentException(type + " jobs serve nobody and carry no request id");
        if (!picked && (pickedAmount != 0 || deliveredAmount != 0))
            throw new IllegalArgumentException("a job that was not picked has no picked or delivered amount: "
                    + pickedAmount + ", " + deliveredAmount);
        if (pickedAmount < 0 || pickedAmount > plannedAmount)
            throw new IllegalArgumentException("pickedAmount must be within 0.." + plannedAmount + ": " + pickedAmount);
        if (deliveredAmount < 0 || deliveredAmount > pickedAmount)
            throw new IllegalArgumentException("deliveredAmount must be within 0.." + pickedAmount + ": "
                    + deliveredAmount);
    }

    /** A new store job from an input station to a storage location. */
    public static <K, L> TransportJob<K, L> store(UUID id, L input, L storage, K key, int amount) {
        return new TransportJob<>(id, JobType.STORE, input, storage, LocationKind.STORAGE, key, amount, Optional.empty(),
                false, 0, 0);
    }

    /** A new retrieve job from a storage location to an output station, serving {@code requestId} if not null. */
    public static <K, L> TransportJob<K, L> retrieve(UUID id, L storage, L output, K key, int amount,
            @Nullable UUID requestId) {
        return new TransportJob<>(id, JobType.RETRIEVE, storage, output, LocationKind.OUTPUT, key, amount,
                Optional.ofNullable(requestId), false, 0, 0);
    }

    /**
     * A new supply job from a storage location to a production station, serving the ingredient line {@code lineId} of
     * a production order ({@code docs/warehouse-system.md} §3.5, ADR-024).
     */
    public static <K, L> TransportJob<K, L> supply(UUID id, L storage, L station, K key, int amount,
            @Nullable UUID lineId) {
        return new TransportJob<>(id, JobType.SUPPLY, storage, station, LocationKind.PRODUCTION, key, amount,
                Optional.ofNullable(lineId), false, 0, 0);
    }

    /** The kind of {@link #source()}. */
    public LocationKind sourceKind() {
        return type.sourceKind();
    }

    /** Items in the handling head: picked minus delivered (0 before the pick). */
    public int heldAmount() {
        return pickedAmount - deliveredAmount;
    }

    /** Whether the job was picked and everything picked is delivered (including a pick of 0). */
    public boolean isFinished() {
        return picked && heldAmount() == 0;
    }

    /** The request id or {@code null}, for persistence. */
    public @Nullable UUID requestIdOrNull() {
        return requestId.orElse(null);
    }

    /**
     * This job with the real pick result.
     *
     * @throws IllegalStateException    if the job was picked already
     * @throws IllegalArgumentException if {@code amount} is outside {@code 0..plannedAmount}
     */
    public TransportJob<K, L> withPicked(int amount) {
        if (picked)
            throw new IllegalStateException("job " + id + " was picked already");
        return new TransportJob<>(id, type, source, target, targetKind, key, plannedAmount, requestId, true, amount, 0);
    }

    /**
     * This job with {@code amount} more items delivered (a real drop result).
     *
     * @throws IllegalStateException    if the job was not picked
     * @throws IllegalArgumentException if {@code amount} is negative or more than {@link #heldAmount()}
     */
    public TransportJob<K, L> plusDelivered(int amount) {
        if (!picked)
            throw new IllegalStateException("job " + id + " was not picked");
        if (amount < 0 || amount > heldAmount())
            throw new IllegalArgumentException("delivered amount must be within 0.." + heldAmount() + ": " + amount);
        return new TransportJob<>(id, type, source, target, targetKind, key, plannedAmount, requestId, true,
                pickedAmount, deliveredAmount + amount);
    }

    /**
     * This job with another target (reroute). The request stays attached; a controller that reroutes a retrieve job
     * away from the request's output station detaches it with {@link #withoutRequest()}.
     *
     * @throws IllegalArgumentException if the type does not allow {@code kind}
     */
    public TransportJob<K, L> withTarget(L newTarget, LocationKind kind) {
        return new TransportJob<>(id, type, source, newTarget, kind, key, plannedAmount, requestId, picked, pickedAmount,
                deliveredAmount);
    }

    /** This job without a request (the request was cancelled, or the items go elsewhere). */
    public TransportJob<K, L> withoutRequest() {
        if (requestId.isEmpty())
            return this;
        return new TransportJob<>(id, type, source, target, targetKind, key, plannedAmount, Optional.empty(), picked,
                pickedAmount, deliveredAmount);
    }
}
