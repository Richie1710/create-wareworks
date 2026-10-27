package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The fluid question and its answer (M31, issue #21, ADR-073): covering is <b>equality</b> on the served amount, the
 * containers per portion and the containers to be made, because serving more costs containers the player did not agree
 * to and serving less is not what they asked for.
 */
class FluidQuestionTest {
    private static FluidQuestion<String, String> question(long served, List<ContainerPlan.Portion<String>> portions,
            Optional<FluidQuestion.MakeOffer<String>> make) {
        return new FluidQuestion<>("lava", 10_000, served, portions, make, 10, 1, ContainerPlan.Shortfall.NONE,
                32_000, 12, 1000, 9, 0, 0, List.of());
    }

    private static final List<ContainerPlan.Portion<String>> TEN_BUCKETS =
            List.of(new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000, 10));

    @Test
    void anAnswerCoversExactlyItsQuestion() {
        FluidQuestion<String, String> asked = question(10_000, TEN_BUCKETS, Optional.empty());
        FluidAcknowledgement yes = asked.acknowledgement();
        assertTrue(yes.covers(asked));
        assertTrue(yes.covers(question(10_000, TEN_BUCKETS, Optional.empty())), "an equal question is covered");
    }

    @Test
    void moreOrLessIsANewQuestion() {
        FluidAcknowledgement yes = question(10_000, TEN_BUCKETS, Optional.empty()).acknowledgement();
        assertFalse(yes.covers(question(9_000, List.of(new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000, 9)),
                Optional.empty())), "serving less");
        assertFalse(yes.covers(question(11_000, List.of(new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000,
                11)), Optional.empty())), "serving more costs containers nobody agreed to");
        assertFalse(yes.covers(question(10_000, List.of(new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000, 6),
                new ContainerPlan.Portion<>("cell", "lava_cell", 1000, 4)), Optional.empty())),
                "the same amount in other containers");
        assertFalse(yes.covers(question(10_000, List.of(new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000, 6)),
                Optional.of(new FluidQuestion.MakeOffer<>("bucket", "lava_bucket", 1000, 4)))),
                "the same amount with containers made first");
    }

    @Test
    void noAnswerAndNothingToFetchAreNeverCovered() {
        FluidQuestion<String, String> asked = question(10_000, TEN_BUCKETS, Optional.empty());
        assertFalse(FluidAcknowledgement.NONE.covers(asked), "a first click asks");
        FluidQuestion<String, String> nothing = question(0, List.of(), Optional.empty());
        assertFalse(nothing.canFetch());
        assertFalse(nothing.acknowledgement().covers(nothing), "a question that serves nothing has no Fetch");
    }

    @Test
    void theQuestionCountsItsContainersAndTheStationsRoom() {
        FluidQuestion<String, String> asked = question(10_000, List.of(new ContainerPlan.Portion<>("bucket",
                "lava_bucket", 1000, 6)), Optional.of(new FluidQuestion.MakeOffer<>("bucket", "lava_bucket", 1000, 4)));
        assertEquals(10, asked.containers());
        assertEquals(List.of(6), asked.portionCounts());
        assertEquals(4, asked.makeCount());
        assertTrue(asked.overflowsStation(), "ten filled buckets into nine slots: the crane waits");
        assertEquals(4_000, asked.make().orElseThrow().millibuckets());
    }

    @Test
    void theUnservedRestIsWhatNoContainerHolds() {
        FluidQuestion<String, String> asked = new FluidQuestion<>("lava", 2_500, 2_000, List.of(
                new ContainerPlan.Portion<>("bucket", "lava_bucket", 1000, 2)), Optional.empty(), 2, 1,
                ContainerPlan.Shortfall.NO_CONTAINER_FITS, 32_000, 5, 1000, 9, 0, 0, List.of());
        assertEquals(500, asked.unservedMb());
    }

    @Test
    void readingIsBounded() {
        List<Integer> many = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertEquals(ContainerPlan.MAX_PORTIONS, new FluidAcknowledgement(true, 1, many, 0).portionCounts().size());
    }
}
