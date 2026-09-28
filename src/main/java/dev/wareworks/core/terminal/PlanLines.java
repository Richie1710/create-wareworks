package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folds the production orders a screen was sent into the lines it draws (M20, issue #4, ADR-032): every order of one
 * production plan becomes <b>one</b> {@link PlanLine}, and an ordinary order becomes a line of its own.
 * <p>
 * This is pure client-side grouping of what the server already sent, and it is deliberately the only place it happens:
 * the terminal's two-line section, its step panel and its tooltips all read the same lines, so the badge, the panel and
 * the cancel affordance can never disagree about what belongs to which chain.
 * <p>
 * <b>Order.</b> The input is the orders as a payload carries them, oldest first. A line appears at the position of its
 * <b>last</b> member, so a plan shows up where its root does — the root is the last order of a plan to be created
 * ({@code ProductionPlan#nodes()}) — and a screen that shows the newest lines first reads the result backwards exactly
 * as it read the orders before M20.
 * <p>
 * <b>Nothing here trusts the numbers.</b> A payload may be cut short, and hand-edited save data may name a plan whose
 * root is not in the list at all. Every case answers rather than throws: the shallowest member becomes the head, a
 * duplicate id keeps its first appearance, and a plan of one order is still drawn as a plain line.
 */
public final class PlanLines {
    private PlanLines() {
    }

    /**
     * The lines for {@code members}, in the order described above.
     *
     * @param members the orders a screen was sent, oldest first; {@code null} is an empty list
     */
    public static List<PlanLine> of(List<PlanMember> members) {
        if (members == null || members.isEmpty())
            return List.of();
        // Insertion order is the order the groups are emitted in, refined below to the position of the last member.
        Map<UUID, List<PlanMember>> groups = new LinkedHashMap<>();
        List<UUID> byLastMember = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (PlanMember member : members) {
            if (member == null || !seen.add(member.id()))
                continue; // a duplicate id keeps its first appearance
            UUID group = member.plan().orElse(member.id());
            groups.computeIfAbsent(group, key -> new ArrayList<>()).add(member);
            byLastMember.remove(group);
            byLastMember.add(group);
        }
        List<PlanLine> lines = new ArrayList<>(byLastMember.size());
        for (UUID group : byLastMember)
            lines.add(line(group, groups.get(group)));
        return List.copyOf(lines);
    }

    /** One line from the members of one group; {@code group} is the plan's root id, or a single order's own id. */
    private static PlanLine line(UUID group, List<PlanMember> members) {
        if (members.size() == 1 && members.getFirst().plan().isEmpty())
            return PlanLine.single(members.getFirst().id(), members.getFirst().open());
        List<PlanMember> sorted = new ArrayList<>(members);
        // Shallowest first, ties in the order they arrived: the head, then the steps under it, deepest last.
        sorted.sort(Comparator.comparingInt(PlanMember::depth));
        PlanMember head = head(group, sorted);
        List<UUID> ids = new ArrayList<>(sorted.size());
        ids.add(head.id());
        for (PlanMember member : sorted) {
            if (!member.id().equals(head.id()))
                ids.add(member.id());
        }
        // The deepest open member is where the work is; ties keep the order they arrived in.
        PlanMember frontier = null;
        int open = 0;
        for (PlanMember member : sorted) {
            if (!member.open())
                continue;
            open++;
            if (frontier == null || member.depth() > frontier.depth())
                frontier = member;
        }
        return new PlanLine(head.id(), Optional.of(group), ids,
                frontier == null ? Optional.empty() : Optional.of(frontier.id()), open);
    }

    /**
     * The member the line names: the plan's root when it is in the list, otherwise the shallowest member there is. A
     * payload cut off before the root, or save data naming a root that is gone, therefore still produces a line a player
     * can read instead of no line at all.
     */
    private static PlanMember head(UUID group, List<PlanMember> sortedByDepth) {
        for (PlanMember member : sortedByDepth) {
            if (member.id().equals(group))
                return member;
        }
        return sortedByDepth.getFirst();
    }

    /** The line whose plan is {@code plan}, if it is still there — what keeps an open step panel honest. */
    public static Optional<PlanLine> byPlan(List<PlanLine> lines, UUID plan) {
        if (lines == null || plan == null)
            return Optional.empty();
        for (PlanLine line : lines) {
            if (line.plan().filter(plan::equals).isPresent())
                return Optional.of(line);
        }
        return Optional.empty();
    }
}
