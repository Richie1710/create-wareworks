package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search of a warehouse terminal screen: which lines a query keeps.
 * <p>
 * Rules (documented in {@code docs/warehouse-system.md} §3.4.2):
 * <ul>
 * <li>a blank query keeps everything;</li>
 * <li>the query is split at whitespace and <b>every</b> token must match (AND), so "iron ing" narrows down;</li>
 * <li>a token matches the display name if it is contained in it, case-insensitively;</li>
 * <li>a token that starts with {@value #MOD_PREFIX} matches the <b>mod id</b> instead ("@create"), which is cheap
 * because the id is already part of every line;</li>
 * <li>a lone {@value #MOD_PREFIX} matches everything, so typing the prefix does not empty the list.</li>
 * </ul>
 * Pure text matching: no allocation per line beyond the lowercase copies, and no dependency on the client's locale
 * beyond {@link Locale#ROOT}.
 */
public final class TerminalSearch {
    /** A query token starting with this character matches the mod id instead of the name. */
    public static final char MOD_PREFIX = '@';

    private TerminalSearch() {
    }

    /** Whether {@code line} matches {@code query} by the rules above. */
    public static boolean matches(StockLine<?> line, String query) {
        String[] tokens = tokens(query);
        if (tokens.length == 0)
            return true;
        String name = lower(line.name());
        String modId = lower(line.modId());
        for (String token : tokens) {
            if (token.charAt(0) == MOD_PREFIX) {
                String needle = token.substring(1);
                if (!needle.isEmpty() && !modId.contains(needle))
                    return false;
            } else if (!name.contains(token))
                return false;
        }
        return true;
    }

    /** The lines of {@code lines} that match {@code query}, in their original order. */
    public static <K> List<StockLine<K>> filter(List<StockLine<K>> lines, String query) {
        String[] tokens = tokens(query);
        if (tokens.length == 0)
            return List.copyOf(lines);
        List<StockLine<K>> matching = new ArrayList<>(lines.size());
        for (StockLine<K> line : lines) {
            if (matches(line, query))
                matching.add(line);
        }
        return List.copyOf(matching);
    }

    /** The lowercase tokens of {@code query}; empty for a blank query. */
    private static String[] tokens(String query) {
        if (query == null)
            return new String[0];
        String normalized = lower(query).trim();
        return normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
