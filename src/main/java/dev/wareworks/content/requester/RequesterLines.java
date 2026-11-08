package dev.wareworks.content.requester;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.core.requester.RequesterLimits;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterStatus;
import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * The lines a Schematicannon Requester says (issue #45, ADR-080 §11): its goggle tooltip and the one line an
 * empty-hand click puts on the action bar, both read off one {@link RequesterSummary}, so the two can never disagree.
 * <p>
 * The goggles, in this order: the header; the linked terminal, or "put in a remote"; the owner; what the cannon does;
 * up to {@value RequesterLimits#MAX_ORDERS} order rows; the current refusal when no line above says it already; the
 * order the dead-man switch gave up last and how many it gave up. Red is what a player has to act on, gold what is
 * worth knowing, gray the rest — the colours of every other Wareworks goggle tooltip. Items are named by their generic
 * name, never by a stack's custom name.
 */
public final class RequesterLines {
    /** Create's own name of the cannon option that keeps it from ever asking: "Skip missing Blocks". */
    public static final String SKIP_MISSING_OPTION = "create.gui.schematicannon.option.skipMissing";
    /** Create's own status words of a paused cannon: {@code create.schematicannon.status.<word>}. */
    private static final String CANNON_STATUS_PREFIX = "create.schematicannon.status.";
    private static final int TICKS_PER_SECOND = 20;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final double TICKS_PER_MINUTE = 1200.0;

    private RequesterLines() {
    }

    /** The goggle lines below the header, for a block whose slot holds a remote linked by {@code link}. */
    public static List<LangBuilder> goggles(RequesterSummary shown, @Nullable RemoteLink link) {
        List<LangBuilder> lines = new ArrayList<>();
        if (link == null) {
            lines.add(status(RequesterStatus.NO_REMOTE).style(ChatFormatting.GOLD));
        } else {
            lines.add(terminal(link));
            if (!shown.ownerName().isEmpty())
                lines.add(WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_OWNER,
                        WareworksLang.text(shown.ownerName()).style(ChatFormatting.WHITE)).style(ChatFormatting.GRAY));
        }
        lines.add(cannon(shown));
        for (RequesterSummary.Row row : shown.rows())
            lines.add(row(row));
        refusal(shown).ifPresent(lines::add);
        if (shown.gaveUp() != null)
            lines.add(WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_GAVE_UP,
                    shown.gaveUp().item().getDescription(),
                    WareworksLang.number(shown.gaveUp().amount()).style(ChatFormatting.GOLD),
                    WareworksLang.number(shown.gaveUp().liveTicks() / TICKS_PER_MINUTE))
                    .style(ChatFormatting.GOLD));
        if (shown.timeouts() > 0)
            lines.add(WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_TIMEOUTS,
                    WareworksLang.number(shown.timeouts()).style(ChatFormatting.GOLD)).style(ChatFormatting.GRAY));
        return lines;
    }

    /**
     * The one line an empty-hand click shows: what the requester waits for now. A working requester names its first
     * order, a cannon that needs nothing says so, and a refusal says what to do.
     */
    public static LangBuilder actionBar(RequesterSummary shown) {
        if (shown.status() == RequesterStatus.NO_REMOTE)
            return status(RequesterStatus.NO_REMOTE).style(ChatFormatting.GOLD);
        Optional<LangBuilder> refusal = refusal(shown);
        if (refusal.isPresent())
            return refusal.get();
        if (shown.status().isLit() && !shown.rows().isEmpty())
            return row(shown.rows().get(0));
        return cannon(shown);
    }

    /** "Terminal at x, y, z", with the dimension when it is not the overworld. */
    private static LangBuilder terminal(RemoteLink link) {
        BlockPos pos = link.terminal().pos();
        if (link.terminal().dimension().equals(Level.OVERWORLD))
            return WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_TERMINAL, coordinate(pos.getX()),
                    coordinate(pos.getY()), coordinate(pos.getZ())).style(ChatFormatting.GRAY);
        return WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_TERMINAL_IN, coordinate(pos.getX()),
                coordinate(pos.getY()), coordinate(pos.getZ()),
                WareworksLang.text(link.terminal().dimension().location().toString()).style(ChatFormatting.WHITE))
                .style(ChatFormatting.GRAY);
    }

    private static LangBuilder coordinate(int value) {
        return WareworksLang.text(Integer.toString(value)).style(ChatFormatting.GOLD);
    }

    /** What the cannon in front does. */
    private static LangBuilder cannon(RequesterSummary shown) {
        return switch (shown.cannon()) {
            case NO_CANNON -> status(RequesterStatus.NO_CANNON).style(ChatFormatting.RED);
            case SKIP_MISSING -> skipMissing().style(ChatFormatting.RED);
            case CREATIVE -> status(RequesterStatus.CREATIVE).style(ChatFormatting.GRAY);
            case NOT_PRINTING -> status(RequesterStatus.NOT_PRINTING).style(ChatFormatting.GRAY);
            case BLOCKED -> WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.BLOCKED),
                    Component.translatable(CANNON_STATUS_PREFIX + shown.cannonWord()).withStyle(ChatFormatting.GOLD))
                    .style(ChatFormatting.GRAY);
            case PRINTING -> status(RequesterStatus.PRINTING).style(ChatFormatting.GREEN);
            case MISSING, NO_INVENTORY -> WareworksLang.translate(WareworksLang.GOGGLES_REQUESTER_CANNON_MISSING,
                    shown.missing().getDescription(),
                    WareworksLang.number(shown.missingCount()).style(ChatFormatting.GOLD)).style(ChatFormatting.GRAY);
        };
    }

    /**
     * One order: running with its delivery and live age, being made in part (no clock: the dead-man switch waits for
     * the machine, ADR-081), in the buffer, or on its way. Whether part is made is the order's own state, not a count:
     * once a root has produced its promise but not its whole result, nothing is left to make and the row still says
     * "being made", since its clock stands until the root ends.
     */
    private static LangBuilder row(RequesterSummary.Row row) {
        if (row.phase() == RequesterOrder.Phase.IN_BUFFER)
            return WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.IN_BUFFER),
                    row.item().getDescription()).style(ChatFormatting.AQUA);
        if (row.phase() == RequesterOrder.Phase.GRACE)
            return WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.GRACE),
                    WareworksLang.number(row.graceLeft()).style(ChatFormatting.GOLD), row.item().getDescription())
                    .style(ChatFormatting.AQUA);
        if (row.making())
            return WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.MAKING),
                    row.item().getDescription(), WareworksLang.number(row.share()).style(ChatFormatting.GOLD),
                    WareworksLang.number(row.made()).style(ChatFormatting.GOLD),
                    WareworksLang.number(row.delivered()).style(ChatFormatting.GOLD)).style(ChatFormatting.AQUA);
        return WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.ORDERED),
                row.item().getDescription(), WareworksLang.number(row.share()).style(ChatFormatting.GOLD),
                WareworksLang.number(row.delivered()).style(ChatFormatting.GOLD),
                WareworksLang.text(clock(row.liveTicks())).style(ChatFormatting.GOLD)).style(ChatFormatting.AQUA);
    }

    /**
     * The line of a status that no other line says already: the link, the warehouse, the cap on orders and why the
     * missing item was not ordered. The cannon's own phases are said by the cannon line, and an order in the works by
     * its row — except a stack in the buffer that this requester did not order (a player's, another requester's): no
     * row says it, so this line does, or the lit lamp would explain nothing.
     */
    private static Optional<LangBuilder> refusal(RequesterSummary shown) {
        RequesterStatus status = shown.status();
        Item subject = shown.subject() == Items.AIR ? shown.missing() : shown.subject();
        LangBuilder line = switch (status) {
            case NO_REMOTE, NO_CANNON, SKIP_MISSING, CREATIVE, NOT_PRINTING, BLOCKED, PRINTING, ORDERED, MAKING,
                    GRACE -> null;
            case IN_BUFFER -> rowInBuffer(shown) ? null
                    : WareworksLang.translate(WareworksLang.requesterStatusKey(status), subject.getDescription());
            case LINK_OUT_OF_RANGE -> WareworksLang.translate(WareworksLang.requesterStatusKey(status),
                    WareworksLang.number(shown.distance()).style(ChatFormatting.GOLD),
                    WareworksLang.number(shown.range()).style(ChatFormatting.GOLD));
            case LINK_NOT_ALLOWED -> WareworksLang.translate(WareworksLang.requesterStatusKey(status),
                    WareworksLang.text(shown.ownerName()).style(ChatFormatting.WHITE));
            case OUT_OF_STOCK, RESERVED -> WareworksLang.translate(WareworksLang.requesterStatusKey(status),
                    subject.getDescription());
            // The item the safety stop halted, which the station's stopped list shows: an ingredient further down the
            // chain as often as the missing item itself.
            case MAKING_STOPPED -> WareworksLang.translate(WareworksLang.requesterStatusKey(status),
                    (shown.about() == Items.AIR ? subject : shown.about()).getDescription());
            // "Oak Planks is not in stock and cannot be made: Oak Log is missing", the controller's own sentence for
            // the plan refusal; without a readable one, the plain out-of-stock line.
            case CANNOT_MAKE -> shown.planRefusal() == null
                    ? WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.OUT_OF_STOCK),
                            subject.getDescription())
                    : WareworksLang.translate(WareworksLang.requesterStatusKey(status), subject.getDescription(),
                            WareworksLang.translate(shown.planRefusal().langKey(),
                                    (shown.about() == Items.AIR ? subject : shown.about()).getDescription())
                                    .style(ChatFormatting.WHITE));
            default -> status(status);
        };
        if (line == null)
            return Optional.empty();
        return Optional.of(line.style(status.isRefused() ? ChatFormatting.RED
                : status.isLit() ? ChatFormatting.AQUA : ChatFormatting.GOLD));
    }

    /** Whether one of the requester's own orders already says that the item in question lies in the buffer. */
    private static boolean rowInBuffer(RequesterSummary shown) {
        for (RequesterSummary.Row row : shown.rows()) {
            if (row.phase() == RequesterOrder.Phase.IN_BUFFER
                    && (shown.subject() == Items.AIR || row.item() == shown.subject()))
                return true;
        }
        return false;
    }

    private static LangBuilder status(RequesterStatus status) {
        return WareworksLang.translate(WareworksLang.requesterStatusKey(status));
    }

    /** "The cannon skips missing blocks: turn off "Skip missing Blocks"", with Create's own option name. */
    private static LangBuilder skipMissing() {
        return WareworksLang.translate(WareworksLang.requesterStatusKey(RequesterStatus.SKIP_MISSING),
                Component.translatable(SKIP_MISSING_OPTION).withStyle(ChatFormatting.WHITE));
    }

    /** {@code m:ss} of a number of ticks. */
    static String clock(int ticks) {
        int seconds = Math.max(0, ticks) / TICKS_PER_SECOND;
        int minutes = seconds / SECONDS_PER_MINUTE;
        int rest = seconds % SECONDS_PER_MINUTE;
        return minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }
}
