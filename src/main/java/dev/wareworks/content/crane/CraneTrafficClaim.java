package dev.wareworks.content.crane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.warehouse.CraneRoute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What a crane of a warehouse with several cranes holds of the rails beyond the blocks it stands on, as the traffic
 * ledger last told it (issue #14, ADR-075 §13): the route it is committed to and the stop it ends on, the crane it
 * moves aside for, and how long its waiting want has waited.
 * <p>
 * The ledger itself is never saved. Each crane saves this, the controller saves the last claim it knew of every crane,
 * and a ledger built after a load is restored from exactly these claims, which were disjoint and kept every crane's way
 * home when they were saved ({@code TrafficLedger#restore}).
 *
 * @param route      the committed route, or empty
 * @param stopBranch the committed stop's branch (meaningless without a route)
 * @param stopX      the committed stop's position on that branch
 * @param forCrane   the crane the route moves this one aside for, or 0
 * @param since      the game tick the crane's waiting want was first posted, or empty
 */
public record CraneTrafficClaim(Optional<CraneRoute> route, int stopBranch, int stopX, int forCrane,
                                OptionalLong since) {
    /** Nothing held beyond the blocks it stands on, and nothing waited for. */
    public static final CraneTrafficClaim NONE = new CraneTrafficClaim(Optional.empty(), 0, 0, 0, OptionalLong.empty());

    private static final String ROUTE = "Route";
    private static final String STOP_BRANCH = "StopB";
    private static final String STOP_X = "StopX";
    private static final String FOR = "For";
    private static final String SINCE = "Since";
    private static final String LEG_BRANCH = "B";
    private static final String LEG_FROM = "F";
    private static final String LEG_TO = "T";
    private static final String LEG_HEADING = "H";
    /** A route never has more legs than the warehouse has junctions plus one; this bounds untrusted data. */
    private static final int MAX_LEGS = 256;

    public CraneTrafficClaim {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(since, "since");
    }

    /** Whether a committed route is held. */
    public boolean committed() {
        return route.isPresent();
    }

    /** Writes the claim into {@code tag}; nothing at all for {@link #NONE}. */
    public void write(CompoundTag tag) {
        route.ifPresent(held -> {
            tag.put(ROUTE, writeRoute(held));
            tag.putInt(STOP_BRANCH, stopBranch);
            tag.putInt(STOP_X, stopX);
            if (forCrane != 0)
                tag.putInt(FOR, forCrane);
        });
        since.ifPresent(tick -> tag.putLong(SINCE, tick));
    }

    /** The claim a tag describes; {@link #NONE} for a tag without one or one that is not readable. Never throws. */
    public static CraneTrafficClaim read(CompoundTag tag) {
        OptionalLong since = tag.contains(SINCE, Tag.TAG_LONG) ? OptionalLong.of(tag.getLong(SINCE))
                : OptionalLong.empty();
        Optional<CraneRoute> route = tag.contains(ROUTE, Tag.TAG_LIST)
                ? readRoute(tag.getList(ROUTE, Tag.TAG_COMPOUND)) : Optional.empty();
        if (route.isEmpty())
            return new CraneTrafficClaim(Optional.empty(), 0, 0, 0, since);
        int stopBranch = tag.getInt(STOP_BRANCH);
        int stopX = tag.getInt(STOP_X);
        if (stopBranch < RackPosition.FIRST_BRANCH || stopBranch > RackPosition.MAX_BRANCH || stopX < 0
                || stopX > AisleGeometry.MAX_LENGTH)
            return new CraneTrafficClaim(Optional.empty(), 0, 0, 0, since);
        return new CraneTrafficClaim(route, stopBranch, stopX, Math.max(0, tag.getInt(FOR)), since);
    }

    /** A route as a list of legs. */
    public static ListTag writeRoute(CraneRoute route) {
        ListTag legs = new ListTag();
        for (CraneRoute.Leg leg : route.legs()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(LEG_BRANCH, leg.branch());
            entry.putDouble(LEG_FROM, leg.fromX());
            entry.putDouble(LEG_TO, leg.toX());
            entry.putString(LEG_HEADING, leg.heading().name());
            legs.add(entry);
        }
        return legs;
    }

    /** A route from untrusted data, or empty when the legs do not make one. Never throws. */
    public static Optional<CraneRoute> readRoute(ListTag legs) {
        if (legs.isEmpty() || legs.size() > MAX_LEGS)
            return Optional.empty();
        try {
            List<CraneRoute.Leg> read = new ArrayList<>(legs.size());
            for (int i = 0; i < legs.size(); i++) {
                CompoundTag entry = legs.getCompound(i);
                double from = entry.getDouble(LEG_FROM);
                double to = entry.getDouble(LEG_TO);
                if (!Double.isFinite(from) || !Double.isFinite(to))
                    return Optional.empty();
                read.add(new CraneRoute.Leg(entry.getInt(LEG_BRANCH), from, to,
                        Heading.valueOf(entry.getString(LEG_HEADING))));
            }
            return Optional.of(new CraneRoute(read));
        } catch (RuntimeException e) {
            return Optional.empty(); // not a route: the crane holds the blocks it stands on and asks again
        }
    }
}
