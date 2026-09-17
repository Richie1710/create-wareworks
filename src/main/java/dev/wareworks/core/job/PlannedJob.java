package dev.wareworks.core.job;

import java.util.Objects;

/**
 * A job created by {@link JobPlanner#plan}, not reserved or assigned yet. The controller reserves it
 * ({@link ReservationLedger#track}) and assigns it to the crane (§7.1 step 3).
 *
 * @param job            the new job
 * @param estimatedTicks {@link TravelTimeModel#tripTicks} from the crane's position, or
 *                       {@link TravelTimeModel#UNAVAILABLE}
 * @param <K>            item key type
 * @param <L>            location type
 */
public record PlannedJob<K, L>(TransportJob<K, L> job, long estimatedTicks) {
    public PlannedJob {
        Objects.requireNonNull(job, "job");
        if (estimatedTicks < 0)
            throw new IllegalArgumentException("estimatedTicks must not be negative: " + estimatedTicks);
    }
}
