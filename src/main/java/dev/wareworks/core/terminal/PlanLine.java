package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of a screen's production section (M20, issue #4, ADR-032): either a single production order, exactly as
 * before M20, or a whole <b>production plan</b> folded into one line.
 * <p>
 * A chain can be six orders at five machines, and a terminal has two lines. Showing one line per order would push
 * everything else off the section and still not say what a player wants to know, which is not "what is running" but
 * <b>"how far along is my chest"</b>. So a plan takes one line: {@link #head()} says what it makes, and
 * {@link #frontier()} says where the work actually is right now.
 *
 * @param head        the order the line names: the plan's root, i.e. the item a player ordered
 * @param plan        the plan's id, empty for a single order — a line with an empty plan is drawn exactly as it was
 *                    before M20, which is how a warehouse with no chains stays unchanged
 * @param members     every order of the line in display order — the head first, then the deeper steps, deepest last,
 *                    ties in the order they were created. A single order is one member, itself
 * @param frontier    the <b>deepest open</b> member: the step where something is really happening, because every
 *                    shallower open order of a plan is waiting for it ({@code ProductionOrders#hasOpenChildren}). Empty
 *                    once every member has finished
 * @param openMembers how many members are still running
 */
public record PlanLine(UUID head, Optional<UUID> plan, List<UUID> members, Optional<UUID> frontier, int openMembers) {
    public PlanLine {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(plan, "plan");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(frontier, "frontier");
        openMembers = Math.max(0, openMembers);
    }

    /** A line for one ordinary order, which is what every line was before M20. */
    public static PlanLine single(UUID id, boolean open) {
        return new PlanLine(id, Optional.empty(), List.of(id), open ? Optional.of(id) : Optional.empty(), open ? 1 : 0);
    }

    /** Whether this line stands for a chain of more than one order, i.e. whether it has steps to show at all. */
    public boolean isChain() {
        return plan.isPresent() && members.size() > 1;
    }

    /** Whether the frontier is the head itself, i.e. whether the chain is down to the ordered item's own order. */
    public boolean frontierIsHead() {
        return frontier.filter(head::equals).isPresent();
    }
}
