package dev.wareworks.core.traffic;

import java.util.Objects;

import dev.wareworks.core.warehouse.CraneRoute;

/**
 * What one pass of the traffic ledger tells one crane (issue #14, ADR-075 §5.3): drive, finish an earlier route
 * first, move aside, or wait — and why.
 */
public sealed interface TrafficVerdict {
    /** Nothing to wait for. */
    TrafficVerdict CLEAR = new Clear();

    /** No want, or a want inside the blocks the crane already stands on. */
    record Clear() implements TrafficVerdict {
    }

    /** The whole route to the stop it wants is reserved for it. */
    record Granted(CraneRoute route, int stopCell) implements TrafficVerdict {
        public Granted {
            Objects.requireNonNull(route, "route");
        }
    }

    /**
     * It still holds an earlier grant whose stop it has not reached and keeps driving that one, while the stop it wants
     * now is not granted yet ({@code waiting} says why). A committed route is always driven to its end.
     */
    record Detour(CraneRoute route, int stopCell, TrafficWait waiting) implements TrafficVerdict {
        public Detour {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(waiting, "waiting");
        }
    }

    /** It moves aside for crane {@code forCrane}, along {@code route} to its park. */
    record Yield(CraneRoute route, int stopCell, int forCrane) implements TrafficVerdict {
        public Yield {
            Objects.requireNonNull(route, "route");
        }
    }

    /** It stands still: its want was not granted and it holds no route ({@code waiting} says why). */
    record Hold(TrafficWait waiting) implements TrafficVerdict {
        public Hold {
            Objects.requireNonNull(waiting, "waiting");
        }
    }
}
