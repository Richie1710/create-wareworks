package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;

import dev.wareworks.core.inventory.KeyCount;

/**
 * What a terminal says about one address of a package-mode warehouse beyond the box count (issue #40, owner decision
 * E5): what is inside its boxes, summed over all of them, and whether any of them belongs to a network order.
 * <p>
 * Bounded on purpose, because it travels with every group row ({@code TerminalStockPayload}): at most
 * {@value #MAX_CONTENTS} content lines, the largest first, plus the number of content kinds there are in all, so the
 * tooltip can say "and 3 more". Built on the server ({@link PackageGroupFold}) and only shown by the client.
 *
 * @param boxes        how many boxes of the address are stored, i.e. the group row's total
 * @param contents     what the boxes hold, summed over every box and largest first, at most {@value #MAX_CONTENTS}
 * @param kinds        how many different content items the boxes hold in all, at least {@code contents.size()}
 * @param networkOrder whether at least one box carries Create's order data (part of a stock ticker's order)
 * @param <K>          item key type
 */
public record PackageGroupSummary<K>(long boxes, List<KeyCount<K>> contents, int kinds, boolean networkOrder) {
    /** Most content lines one summary carries. */
    public static final int MAX_CONTENTS = 8;

    public PackageGroupSummary {
        Objects.requireNonNull(contents, "contents");
        boxes = Math.max(0L, boxes);
        contents = List.copyOf(contents.size() <= MAX_CONTENTS ? contents : contents.subList(0, MAX_CONTENTS));
        kinds = Math.max(kinds, contents.size());
    }

    /** Content kinds the summary does not list: the "and N more" of the tooltip. */
    public int moreKinds() {
        return kinds - contents.size();
    }
}
