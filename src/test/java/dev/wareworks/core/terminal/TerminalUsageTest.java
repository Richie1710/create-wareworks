package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.terminal.TerminalUsage.Entry;

/**
 * The bounded per-player request counter behind the terminal's "most used" order (M24, issue #17): counting, the cap,
 * which entry is dropped when it is full, the fade, what one player action that names many item types does, and what
 * crafted save data can do to it.
 */
class TerminalUsageTest {
    private static List<String> keys(List<Entry<String>> entries) {
        return entries.stream().map(Entry::key).toList();
    }

    private static List<Integer> counts(List<Entry<String>> entries) {
        return entries.stream().map(Entry::count).toList();
    }

    @Test
    void aFreshStoreRemembersNothing() {
        TerminalUsage<String> usage = new TerminalUsage<>();
        assertTrue(usage.isEmpty());
        assertEquals(0, usage.size());
        assertEquals(TerminalUsage.DEFAULT_CAPACITY, usage.capacity());
        assertEquals(0L, usage.countFor("iron"), "an item never requested counts zero");
        assertEquals(0L, usage.countFor(null), "a null key is a question, not an error");
        assertEquals(List.of(), usage.entries());
    }

    @Test
    void oneRequestCountsOnceWhateverAmountItAskedFor() {
        TerminalUsage<String> usage = new TerminalUsage<>();
        assertEquals(1, usage.record("iron"));
        assertEquals(2, usage.record("iron"));
        assertEquals(3, usage.record("iron"));
        assertEquals(1, usage.record("cog"));
        assertEquals(3L, usage.countFor("iron"), "three requests for iron, whatever each one asked for");
        assertEquals(1L, usage.countFor("cog"));
        assertEquals(2, usage.size());
        assertFalse(usage.isEmpty());
        assertThrows(NullPointerException.class, () -> usage.record(null), "nothing may be counted for no item");
    }

    @Test
    void capacityIsClampedSoSaveDataCannotMakeItUnbounded() {
        assertEquals(1, new TerminalUsage<>(0).capacity(), "a store always has room for one item type");
        assertEquals(1, new TerminalUsage<>(-5).capacity());
        assertEquals(TerminalUsage.MAX_CAPACITY, new TerminalUsage<>(Integer.MAX_VALUE).capacity());
        assertEquals(7, new TerminalUsage<>(7).capacity());
    }

    @Test
    void theStoreNeverGrowsPastItsCapacity() {
        TerminalUsage<String> usage = new TerminalUsage<>(3);
        for (int i = 0; i < 50; i++)
            usage.record("item-" + i);
        assertEquals(3, usage.size(), "fifty item types, three remembered");
        assertEquals(1L, usage.countFor("item-49"), "the newest request is always learned");
    }

    @Test
    void theWeakestEntryIsDroppedWhenTheCapIsReached() {
        TerminalUsage<String> usage = new TerminalUsage<>(3);
        usage.record("a");
        usage.record("b");
        usage.record("c");
        usage.record("d");
        assertEquals(0L, usage.countFor("a"), "all three were asked for once, so the oldest of them goes");
        assertEquals(List.of("d", "c", "b"), keys(usage.entries()));
    }

    @Test
    void aFavouriteSurvivesAOneOffClick() {
        TerminalUsage<String> usage = new TerminalUsage<>(2);
        usage.record("favourite");
        usage.record("favourite");
        usage.record("favourite");
        usage.record("passing");
        usage.record("another");
        assertEquals(3L, usage.countFor("favourite"), "a low count is dropped before an old one");
        assertEquals(0L, usage.countFor("passing"));
        assertEquals(1L, usage.countFor("another"));
    }

    @Test
    void aNewItemTypeIsNeverRefused() {
        TerminalUsage<String> usage = new TerminalUsage<>(2);
        for (int i = 0; i < 10; i++) {
            usage.record("old-one");
            usage.record("old-two");
        }
        // The player's build moved on: they must be able to teach the terminal the new set of items.
        usage.record("new-one");
        assertEquals(1L, usage.countFor("new-one"));
        usage.record("new-one");
        usage.record("new-one");
        assertEquals(3L, usage.countFor("new-one"));
        assertEquals(2, usage.size());
    }

    @Test
    void countsFadeByHalvingWhenOneReachesTheCeiling() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        usage.record("once");
        for (int i = 0; i < 10; i++)
            usage.record("sometimes");
        for (int i = 0; i < TerminalUsage.MAX_COUNT; i++)
            usage.record("always");

        assertEquals(TerminalUsage.MAX_COUNT / 2, usage.countFor("always"), "the count that hit the ceiling is halved");
        assertEquals(5L, usage.countFor("sometimes"), "and so is every other count");
        assertEquals(0L, usage.countFor("once"), "what falls to zero is forgotten, which also frees capacity");
        assertEquals(2, usage.size());
    }

    @Test
    void noCountEverPassesTheCeiling() {
        TerminalUsage<String> usage = new TerminalUsage<>(4);
        for (int i = 0; i < 10_000; i++)
            usage.record("always");
        long count = usage.countFor("always");
        assertTrue(count > 0L && count < TerminalUsage.MAX_COUNT,
                "ten thousand requests, and the number stays small: " + count);
        assertEquals(1, usage.size());
    }

    @Test
    void aStoppedHabitFallsBehindANewOne() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        for (int i = 0; i < TerminalUsage.MAX_COUNT / 2; i++)
            usage.record("old-habit");
        for (int i = 0; i < TerminalUsage.MAX_COUNT / 2; i++)
            usage.record("new-habit");
        // The new habit's last request hit the ceiling and halved both; from here the old one only shrinks.
        for (int i = 0; i < TerminalUsage.MAX_COUNT / 2; i++)
            usage.record("new-habit");
        assertTrue(usage.countFor("new-habit") > usage.countFor("old-habit"),
                "what the player does now leads: " + usage.entries());
        assertEquals("new-habit", keys(usage.entries()).get(0));
    }

    @Test
    void everyNumberStaysBoundedEvenForAPlayerWhoOnlyEverAsksForSomethingNew() {
        // This is the one history that never triggers the fade: no count ever grows past one.
        TerminalUsage<String> usage = new TerminalUsage<>(16);
        for (int i = 0; i <= TerminalUsage.MAX_STAMP + 5; i++)
            usage.record("item-" + i);

        assertEquals(16, usage.size());
        List<Entry<String>> entries = usage.entries();
        List<Integer> stamps = new ArrayList<>();
        for (Entry<String> entry : entries) {
            assertTrue(entry.stamp() > 0 && entry.stamp() <= TerminalUsage.MAX_STAMP,
                    "stamps are renumbered before they could run away: " + entry);
            assertFalse(stamps.contains(entry.stamp()), "two entries may never share a stamp: " + entry);
            stamps.add(entry.stamp());
        }
        assertEquals("item-" + (TerminalUsage.MAX_STAMP + 5), keys(entries).get(0),
                "renumbering keeps the order of events, so the newest request is still the newest");
    }

    @Test
    void entriesAreStrongestFirstAndMostRecentAmongEqualCounts() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        usage.record("twice");
        usage.record("older-single");
        usage.record("twice");
        usage.record("newer-single");
        assertEquals(List.of("twice", "newer-single", "older-single"), keys(usage.entries()));
        assertEquals(List.of(2, 1, 1), counts(usage.entries()));
    }

    // --- one action that names many item types (a clipboard order; M24 review fix) ---------------------------------

    @Test
    void oneActionCountsEveryItemTypeItNamesExactlyOnce() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        assertEquals(3, usage.recordAll(Arrays.asList("iron", "cog", "iron", "brass", null)),
                "three item types, however many lines named them, and a null line is skipped");
        assertEquals(1L, usage.countFor("iron"), "a list that names an item twice is one thing the player asked for");
        assertEquals(1L, usage.countFor("cog"));
        assertEquals(1L, usage.countFor("brass"));
        assertEquals(3, usage.size());
        assertEquals(0, usage.recordAll(List.of()), "an empty list counts nothing");
        assertThrows(NullPointerException.class, () -> usage.recordAll(null));
    }

    @Test
    void oneKeyAsAListDoesExactlyWhatOneRequestDoes() {
        TerminalUsage<String> batch = new TerminalUsage<>(8);
        TerminalUsage<String> single = new TerminalUsage<>(8);
        for (int i = 0; i < 3; i++) {
            batch.recordAll(List.of("iron"));
            single.record("iron");
        }
        assertEquals(keys(single.entries()), keys(batch.entries()));
        assertEquals(counts(single.entries()), counts(batch.entries()));
    }

    @Test
    void theSameListOrderedAgainRaisesItsOwnCounts() {
        // The list names more item types than the store can hold, which is what the default config really allows
        // (maxTerminalListEntries 128 against 64 entries here). Counting it key by key made the later keys evict the
        // earlier ones, so a repeated order was a fixed point at count 1 and taught the terminal nothing at all.
        TerminalUsage<String> usage = new TerminalUsage<>(4);
        List<String> list = List.of("a", "b", "c", "d", "e", "f");
        assertEquals(4, usage.recordAll(list), "four of the six fit, and the list keeps the ones it named first");
        assertEquals(List.of(1, 1, 1, 1), counts(usage.entries()));

        assertEquals(4, usage.recordAll(list));
        assertEquals(List.of(2, 2, 2, 2), counts(usage.entries()), "ordering the same list again raises its counts");
        assertEquals(2L, usage.countFor("a"));
        usage.recordAll(list);
        assertEquals(List.of(3, 3, 3, 3), counts(usage.entries()));
        assertEquals(0L, usage.countFor("e"), "and the tail it never had room for is still not remembered");
    }

    @Test
    void aListLongerThanTheStoreKeepsTheItemTypesItNamedFirst() {
        TerminalUsage<String> usage = new TerminalUsage<>(3);
        assertEquals(3, usage.recordAll(List.of("1", "2", "3", "4", "5")),
                "the return value says how many of the list were counted");
        assertEquals(3, usage.size());
        assertEquals(1L, usage.countFor("1"), "the head of the list is what a player wrote first");
        assertEquals(1L, usage.countFor("2"));
        assertEquals(1L, usage.countFor("3"));
        assertEquals(0L, usage.countFor("4"), "and nothing the same action counted was dropped for the rest of it");
        assertEquals(0L, usage.countFor("5"));
    }

    @Test
    void aListGivesUpTheWeakestOlderEntriesAndNeverItsOwn() {
        TerminalUsage<String> usage = new TerminalUsage<>(4);
        for (int i = 0; i < 5; i++)
            usage.record("favourite");
        usage.record("one-off");
        usage.record("another-one-off");

        assertEquals(3, usage.recordAll(List.of("new-a", "new-b", "new-c")));
        assertEquals(5L, usage.countFor("favourite"), "a list gives up the weakest older entries, not the strongest");
        assertEquals(0L, usage.countFor("one-off"), "the older of the two one-offs went first, as for a single click");
        assertEquals(0L, usage.countFor("another-one-off"));
        assertEquals(1L, usage.countFor("new-a"), "and every item type of the list is remembered");
        assertEquals(1L, usage.countFor("new-b"));
        assertEquals(1L, usage.countFor("new-c"));
        assertEquals(4, usage.size());
    }

    @Test
    void aListRaisesWhatIsKnownBeforeItLearnsAnythingNew() {
        // Both keys are known and the store is full: the list may not spend its own capacity on its new keys first,
        // or the two counts it came to raise would be gone before it reached them.
        TerminalUsage<String> usage = new TerminalUsage<>(2);
        usage.record("known-one");
        usage.record("known-two");
        assertEquals(2, usage.recordAll(List.of("new-one", "known-one", "known-two", "new-two")));
        assertEquals(2L, usage.countFor("known-one"));
        assertEquals(2L, usage.countFor("known-two"));
        assertEquals(0L, usage.countFor("new-one"), "with no older entry left to give up, the new keys wait");
        assertEquals(0L, usage.countFor("new-two"));
    }

    @Test
    void clearForgetsEverything() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        usage.record("iron");
        usage.clear();
        assertTrue(usage.isEmpty());
        assertEquals(0L, usage.countFor("iron"));
        usage.record("cog");
        assertEquals(1L, usage.countFor("cog"), "and counting starts again from one");
    }

    @Test
    void savingAndRestoringKeepsWhatThePlayerUses() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        usage.record("iron");
        usage.record("iron");
        usage.record("iron");
        usage.record("cog");
        usage.record("cog");
        usage.record("brass");

        TerminalUsage<String> restored = new TerminalUsage<>(8);
        restored.replaceAll(usage.entries());
        assertEquals(keys(usage.entries()), keys(restored.entries()));
        assertEquals(counts(usage.entries()), counts(restored.entries()));
        assertEquals(3L, restored.countFor("iron"));
        assertEquals(List.of("iron", "cog", "brass"), keys(restored.entries()));
    }

    @Test
    void restoringReAppliesTheCapAndKeepsTheStrongest() {
        TerminalUsage<String> usage = new TerminalUsage<>(2);
        usage.replaceAll(List.of(new Entry<>("weak", 1, 40), new Entry<>("strong", 9, 10),
                new Entry<>("middle", 5, 20)));
        assertEquals(2, usage.size(), "a smaller cap than the save data was written with");
        assertEquals(List.of("strong", "middle"), keys(usage.entries()));
        assertEquals(0L, usage.countFor("weak"));
    }

    @Test
    void restoringSurvivesCraftedSaveData() {
        TerminalUsage<String> usage = new TerminalUsage<>(8);
        List<Entry<String>> saved = new ArrayList<>();
        saved.add(new Entry<>("huge", Integer.MAX_VALUE, Integer.MAX_VALUE));
        saved.add(new Entry<>("negative", -7, 3));
        saved.add(new Entry<>("zero", 0, 4));
        saved.add(new Entry<>("twice", 2, 5));
        saved.add(new Entry<>("twice", 6, 1));
        saved.add(null);
        usage.replaceAll(saved);

        assertEquals(TerminalUsage.MAX_COUNT, usage.countFor("huge"), "a count beyond the ceiling is clamped to it");
        assertEquals(0L, usage.countFor("negative"), "a negative count means nothing remembered");
        assertEquals(0L, usage.countFor("zero"));
        assertEquals(6L, usage.countFor("twice"), "a key written twice keeps its strongest entry");
        assertEquals(2, usage.size());
        for (Entry<String> entry : usage.entries())
            assertTrue(entry.stamp() > 0 && entry.stamp() <= TerminalUsage.MAX_STAMP,
                    "stamps are renumbered on restore: " + entry);
        assertThrows(NullPointerException.class, () -> usage.replaceAll(null));
    }

    @Test
    void restoredEntriesStillEvictInTheSameOrder() {
        TerminalUsage<String> usage = new TerminalUsage<>(3);
        usage.replaceAll(List.of(new Entry<>("old-single", 1, 10), new Entry<>("new-single", 1, 99),
                new Entry<>("favourite", 8, 20)));
        usage.record("fresh");
        assertEquals(0L, usage.countFor("old-single"), "the saved stamps decided which of the two singles goes");
        assertEquals(1L, usage.countFor("new-single"));
        assertEquals(8L, usage.countFor("favourite"));
    }

    @Test
    void anEntryWithoutAStampIsAllAClientNeeds() {
        // The server sends counts; a screen only reads them (TerminalUsage#ofEntries).
        TerminalUsage<String> client = TerminalUsage.ofEntries(List.of(new Entry<>("iron", 4), new Entry<>("cog", 2)));
        assertEquals(TerminalUsage.MAX_CAPACITY, client.capacity(), "a client never drops what the server sent");
        assertEquals(4L, client.countFor("iron"));
        assertEquals(2L, client.countFor("cog"));
        assertEquals(List.of("iron", "cog"), keys(client.entries()));
        assertThrows(NullPointerException.class, () -> new Entry<>(null, 1));
    }
}
