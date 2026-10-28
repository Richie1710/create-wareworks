package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link MemberJobs}: a crane of a warehouse with several whose chunk is away keeps the job it was last seen with, so
 * the reservations rebuilt from every crane's job never drop it and no other crane is promised its stock or its share
 * of a request (issue #14, ADR-075 §9).
 */
class MemberJobsTest {
    private final Map<String, String> carrying = new HashMap<>();
    private final Set<String> away = new HashSet<>();

    private List<String> refresh(MemberJobs<String, String> jobs, List<String> listed) {
        return jobs.refresh(listed, crane -> !away.contains(crane), crane -> Optional.ofNullable(carrying.get(crane)));
    }

    @Test
    void aCraneWhoseChunkIsAwayKeepsTheJobItWasLastSeenWith() {
        MemberJobs<String, String> jobs = new MemberJobs<>();
        carrying.put("one", "iron");
        carrying.put("two", "gold");
        assertEquals(List.of("iron", "gold"), refresh(jobs, List.of("one", "two")));

        away.add("two");
        carrying.remove("two"); // what the warehouse can no longer see
        assertEquals(List.of("iron", "gold"), refresh(jobs, List.of("one", "two")),
                "crane 2's job still counts while its chunk is away");
        assertEquals(List.of("gold"), jobs.ofAbsent(List.of("one", "two"), crane -> !away.contains(crane)));

        away.clear();
        assertEquals(List.of("iron"), refresh(jobs, List.of("one", "two")), "read again: it has no job any more");
        assertTrue(jobs.snapshot().keySet().equals(Set.of("one")));
    }

    @Test
    void aReportReplacesWhatWasSeenAndAFinishedJobIsForgotten() {
        MemberJobs<String, String> jobs = new MemberJobs<>();
        carrying.put("two", "gold x32");
        refresh(jobs, List.of("one", "two"));
        jobs.remember("two", "gold x32 picked");
        away.add("two");
        assertEquals(List.of("gold x32 picked"), refresh(jobs, List.of("one", "two")));
        jobs.forget("two");
        assertEquals(List.of(), refresh(jobs, List.of("one", "two")), "finished, then its chunk went away");
    }

    @Test
    void aCraneTheWarehouseNoLongerListsIsForgotten() {
        MemberJobs<String, String> jobs = new MemberJobs<>();
        jobs.restore(Map.of("one", "iron", "three", "dirt"));
        away.add("three");
        assertEquals(List.of("dirt"), refresh(jobs, List.of("three")), "restored from a save while still away");
        assertEquals(Map.of("three", "dirt"), jobs.snapshot(), "crane 1 is not listed, so it is forgotten");
        refresh(jobs, List.of());
        assertEquals(Map.of(), jobs.snapshot());
    }
}
