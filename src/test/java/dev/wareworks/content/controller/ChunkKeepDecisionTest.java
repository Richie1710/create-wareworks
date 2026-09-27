package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.controller.ChunkKeepDecision.Action;
import dev.wareworks.content.controller.ChunkKeepDecision.Decision;
import dev.wareworks.content.controller.ChunkKeepDecision.Limits;
import dev.wareworks.content.controller.ChunkKeepDecision.State;
import dev.wareworks.content.controller.ChunkKeepDecision.Work;

/**
 * {@link ChunkKeepDecision}: the whole take / keep / release / refuse table of M19 (issue #10, ADR-031).
 * <p>
 * This is the only coverage of the paths no world test reaches cheaply: the exact linger boundary, the give-up deadline
 * with its fingerprint re-arm, and the precedence of the reasons the goggle line reads.
 * <p>
 * Three of the rows below exist because of the M19 review: the caps apply to a hold that already exists (a cap lowered
 * under a holder, an aisle grown past it), the collect opt-in's own cap has a reason of its own instead of looking like an
 * idle aisle, and "nothing to do" beats the give-up flag so no aisle ever reports that it let go of work it does not have.
 */
class ChunkKeepDecisionTest {
    private static final int FOOTPRINT = 6;
    private static final int MAX_CHUNKS = 8;
    private static final int LINGER = 100;
    private static final int MAX_HOLD = 72000;

    private static Limits on() {
        return new Limits(true, false, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
    }

    private static State holding(long heldSince, long idleSince) {
        return new State(true, heldSince, idleSince, false);
    }

    // --- the feature being off -------------------------------------------------------------------------------------

    @Test
    void anAisleDoesNothingWhileTheFeatureIsOff() {
        Limits off = new Limits(false, false, false, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 3, 2, 0), off, State.IDLE, 1000);
        assertEquals(Action.NONE, decision.action());
        assertEquals(ChunkKeepReason.NONE, decision.reason());
        assertEquals(ChunkKeepDecision.NO_RECHECK, decision.recheckAtTick(), "and it schedules nothing at all");
    }

    @Test
    void switchingTheFeatureOffReleasesWhatIsHeld() {
        Limits off = new Limits(false, false, false, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), off, holding(100, ChunkKeepDecision.NOT_SET),
                200);
        assertEquals(Action.RELEASE, decision.action());
    }

    // --- taking ----------------------------------------------------------------------------------------------------

    @Test
    void aCraneJobMakesTheAisleTakeItsChunks() {
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), on(), State.IDLE, 500);
        assertEquals(Action.TAKE, decision.action());
        assertEquals(ChunkKeepReason.CRANE_JOB, decision.reason());
    }

    @Test
    void anOpenRequestAndAnOpenOrderAreWorkOfTheirOwn() {
        assertEquals(Action.TAKE, ChunkKeepDecision.decide(new Work(false, 1, 0, 0), on(), State.IDLE, 0).action());
        assertEquals(ChunkKeepReason.OPEN_REQUESTS,
                ChunkKeepDecision.decide(new Work(false, 1, 0, 0), on(), State.IDLE, 0).reason());
        assertEquals(Action.TAKE, ChunkKeepDecision.decide(new Work(false, 0, 1, 0), on(), State.IDLE, 0).action());
        assertEquals(ChunkKeepReason.PRODUCTION_ORDERS,
                ChunkKeepDecision.decide(new Work(false, 0, 1, 0), on(), State.IDLE, 0).reason());
    }

    @Test
    void theReasonsHaveAFixedPrecedence() {
        Work everything = new Work(true, 5, 5, 5);
        assertEquals(ChunkKeepReason.CRANE_JOB, everything.reason(true), "the crane job is what a player sees moving");
        assertEquals(ChunkKeepReason.OPEN_REQUESTS, new Work(false, 5, 5, 5).reason(true));
        assertEquals(ChunkKeepReason.PRODUCTION_ORDERS, new Work(false, 0, 5, 5).reason(true));
        assertEquals(ChunkKeepReason.COLLECTING, new Work(false, 0, 0, 5).reason(true));
        assertEquals(ChunkKeepReason.NONE, new Work(false, 0, 0, 5).reason(false), "collecting only with the opt-in");
    }

    @Test
    void anAisleWithoutAFootprintHoldsNothing() {
        Limits noAisle = new Limits(true, false, true, false, 0, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(false, 1, 0, 0), noAisle, State.IDLE, 0);
        assertEquals(Action.NONE, decision.action());
        assertEquals(ChunkKeepReason.NONE, decision.reason());
    }

    @Test
    void anAisleThatLosesItsDockReleasesAtOnce() {
        // The dock broken under a holding controller: there is no footprint left, and the open request must not keep the
        // hold alive for the linger - there is nothing to keep.
        Limits noAisle = new Limits(true, false, true, false, 0, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(false, 1, 0, 0), noAisle,
                holding(0, ChunkKeepDecision.NOT_SET), 10);
        assertEquals(Action.RELEASE, decision.action());
        assertEquals(ChunkKeepReason.NONE, decision.reason());
    }

    // --- keeping and the linger ------------------------------------------------------------------------------------

    @Test
    void workKeepsTheHold() {
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), on(),
                holding(1000, ChunkKeepDecision.NOT_SET), 1400);
        assertEquals(Action.KEEP, decision.action());
        assertEquals(ChunkKeepReason.CRANE_JOB, decision.reason());
        assertEquals(1000 + MAX_HOLD, decision.recheckAtTick(), "and it looks again at the give-up deadline");
    }

    @Test
    void anIdleHoldLingersUntilExactlyTheConfiguredDelay() {
        Decision oneTickEarly = ChunkKeepDecision.decide(Work.NONE, on(), holding(500, 1000), 1000 + LINGER - 1);
        assertEquals(Action.KEEP, oneTickEarly.action());
        assertEquals(ChunkKeepReason.RELEASING, oneTickEarly.reason());
        assertEquals(1000 + LINGER, oneTickEarly.recheckAtTick());

        Decision onTheTick = ChunkKeepDecision.decide(Work.NONE, on(), holding(500, 1000), 1000 + LINGER);
        assertEquals(Action.RELEASE, onTheTick.action());
    }

    @Test
    void aZeroLingerReleasesAtOnce() {
        Limits noLinger = new Limits(true, false, true, false, FOOTPRINT, MAX_CHUNKS, 0, MAX_HOLD);
        assertEquals(Action.RELEASE, ChunkKeepDecision.decide(Work.NONE, noLinger, holding(500, 1000), 1000).action());
    }

    @Test
    void workThatComesBackInsideTheLingerNeverReleases() {
        // The anti-thrash guarantee: work clears idleSince, so the hold is simply kept and nothing is re-taken.
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), on(),
                holding(500, ChunkKeepDecision.NOT_SET), 1000 + LINGER - 10);
        assertEquals(Action.KEEP, decision.action());
    }

    @Test
    void anIdleHoldWithoutAKnownIdleTimeReleasesAtOnce() {
        // A hold reinstated from the save: there was no burst to smooth out, so it does not linger.
        assertEquals(Action.RELEASE,
                ChunkKeepDecision.decide(Work.NONE, on(), holding(0, ChunkKeepDecision.NOT_SET), 5).action());
    }

    @Test
    void anIdleAisleThatHoldsNothingDoesNothing() {
        Decision decision = ChunkKeepDecision.decide(Work.NONE, on(), State.IDLE, 99);
        assertEquals(Action.NONE, decision.action());
        assertEquals(ChunkKeepDecision.NO_RECHECK, decision.recheckAtTick());
    }

    @Test
    void bufferedInputItemsAreNotWork() {
        // Not a parameter at all, on purpose: a buffer cannot change while its own chunk does not tick, so it can never
        // appear while the aisle is unloaded - and the moment it is planned it becomes a crane job.
        assertEquals(Action.NONE, ChunkKeepDecision.decide(Work.NONE, on(), State.IDLE, 0).action());
    }

    // --- giving up -------------------------------------------------------------------------------------------------

    @Test
    void aHoldGivesUpAfterTheConfiguredMaximum() {
        Decision oneTickEarly = ChunkKeepDecision.decide(new Work(false, 1, 0, 0), on(),
                holding(0, ChunkKeepDecision.NOT_SET), MAX_HOLD - 1);
        assertEquals(Action.KEEP, oneTickEarly.action());

        Decision decision = ChunkKeepDecision.decide(new Work(false, 1, 0, 0), on(),
                holding(0, ChunkKeepDecision.NOT_SET), MAX_HOLD);
        assertEquals(Action.RELEASE, decision.action());
        assertEquals(ChunkKeepReason.GAVE_UP, decision.reason());
    }

    @Test
    void aHoldThatGaveUpRefusesToTakeAgain() {
        State gaveUp = new State(false, ChunkKeepDecision.NOT_SET, ChunkKeepDecision.NOT_SET, true);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 4, 2, 0), on(), gaveUp, 1_000_000);
        assertEquals(Action.REFUSE, decision.action());
        assertEquals(ChunkKeepReason.GAVE_UP, decision.reason(), "and the goggles keep saying why");
    }

    @Test
    void aHoldThatGaveUpWhileStillHoldingReleases() {
        State gaveUp = new State(true, 0, ChunkKeepDecision.NOT_SET, true);
        assertEquals(Action.RELEASE, ChunkKeepDecision.decide(new Work(true, 0, 0, 0), on(), gaveUp, 10).action());
    }

    @Test
    void anAisleThatGaveUpAndThenBecameIdleReportsNothing() {
        // Reachable with /wareworks chunks release during the release linger: the aisle is idle, so the flag's own
        // fingerprint can never change again. It used to keep reporting "let go, holds again when its work changes" for
        // an aisle with nothing to do, and to log "may not hold its chunks" about work that did not exist (M19 review).
        State gaveUp = new State(false, ChunkKeepDecision.NOT_SET, 500, true);
        Decision decision = ChunkKeepDecision.decide(Work.NONE, on(), gaveUp, 1000);
        assertEquals(Action.NONE, decision.action());
        assertEquals(ChunkKeepReason.NONE, decision.reason());
    }

    @Test
    void anAisleThatGaveUpAndThenLostItsWorkWhileHoldingStillReleases() {
        State gaveUp = new State(true, 0, 500, true);
        assertEquals(Action.RELEASE, ChunkKeepDecision.decide(Work.NONE, on(), gaveUp, 500 + LINGER).action());
    }

    @Test
    void clearingTheGiveUpFlagRearmsTheHold() {
        // The caller clears the flag when the work fingerprint changed; then the ordinary table applies again.
        State rearmed = new State(false, ChunkKeepDecision.NOT_SET, ChunkKeepDecision.NOT_SET, false);
        assertEquals(Action.TAKE, ChunkKeepDecision.decide(new Work(true, 0, 0, 0), on(), rearmed, 1_000_000).action());
    }

    @Test
    void anUnlimitedMaximumNeverGivesUp() {
        Limits unlimited = new Limits(true, false, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, 0);
        Decision decision = ChunkKeepDecision.decide(new Work(false, 1, 0, 0), unlimited,
                holding(0, ChunkKeepDecision.NOT_SET), Long.MAX_VALUE / 2);
        assertEquals(Action.KEEP, decision.action());
        assertEquals(ChunkKeepDecision.NO_RECHECK, decision.recheckAtTick(), "and there is no deadline to look at");
    }

    // --- the caps --------------------------------------------------------------------------------------------------

    @Test
    void anAisleOverThePerAisleCapHoldsNothingAtAll() {
        Limits tight = new Limits(true, false, true, false, 12, 8, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), tight, State.IDLE, 0);
        assertEquals(Action.REFUSE, decision.action(), "never a partial hold");
        assertEquals(ChunkKeepReason.TOO_MANY_CHUNKS, decision.reason());
    }

    @Test
    void exactlyTheCapStillFits() {
        Limits exact = new Limits(true, false, true, false, 8, 8, LINGER, MAX_HOLD);
        assertEquals(Action.TAKE, ChunkKeepDecision.decide(new Work(true, 0, 0, 0), exact, State.IDLE, 0).action());
    }

    @Test
    void aFullLevelRefusesButTheChunkCapIsNamedFirst() {
        Limits full = new Limits(true, false, false, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), full, State.IDLE, 0);
        assertEquals(Action.REFUSE, decision.action());
        assertEquals(ChunkKeepReason.AT_LEVEL_LIMIT, decision.reason());

        Limits both = new Limits(true, false, false, false, 99, MAX_CHUNKS, LINGER, MAX_HOLD);
        assertEquals(ChunkKeepReason.TOO_MANY_CHUNKS,
                ChunkKeepDecision.decide(new Work(true, 0, 0, 0), both, State.IDLE, 0).reason(),
                "the permanent reason is the one worth reporting");
    }

    @Test
    void anAisleThatAlreadyHoldsIsNotRefusedByAFullLevel() {
        // AisleChunkTickets#levelCapAllows counts a holder itself out, so a level AT its cap answers true for its own
        // holders and none of them ever lets go because of the others.
        Limits atCap = new Limits(true, false, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        assertEquals(Action.KEEP, ChunkKeepDecision.decide(new Work(true, 0, 0, 0), atCap,
                holding(0, ChunkKeepDecision.NOT_SET), 10).action());
    }

    @Test
    void aLoweredLevelCapMakesAHolderLetGo() {
        // The other half of the same rule: false for a holder can only mean the cap was really lowered under it, and then
        // it has to let go - the cap is not a take-time-only test (M19 review).
        Limits lowered = new Limits(true, false, false, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), lowered,
                holding(0, ChunkKeepDecision.NOT_SET), 10);
        assertEquals(Action.RELEASE, decision.action());
        assertEquals(ChunkKeepReason.AT_LEVEL_LIMIT, decision.reason(), "and says which cap did it");
    }

    @Test
    void anAisleThatGrowsPastTheChunkCapWhileHoldingLetsGo() {
        // The defect this pins: the per-aisle cap used to be checked only for a hold being taken, so an aisle extended
        // while it held - or one whose cap was lowered - went on holding over the bound with nothing said (M19 review).
        Limits tight = new Limits(true, false, true, false, 12, 8, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(true, 0, 0, 0), tight,
                holding(0, ChunkKeepDecision.NOT_SET), 10);
        assertEquals(Action.RELEASE, decision.action(), "never a hold bigger than the cap allows");
        assertEquals(ChunkKeepReason.TOO_MANY_CHUNKS, decision.reason());

        Limits stillFits = new Limits(true, false, true, false, 8, 8, LINGER, MAX_HOLD);
        assertEquals(Action.KEEP, ChunkKeepDecision.decide(new Work(true, 0, 0, 0), stillFits,
                holding(0, ChunkKeepDecision.NOT_SET), 10).action(), "exactly at the cap goes on holding");
    }

    // --- the collecting opt-in -------------------------------------------------------------------------------------

    @Test
    void aPendingCollectIsNoWorkWithoutTheOptIn() {
        assertEquals(Action.NONE, ChunkKeepDecision.decide(new Work(false, 0, 0, 3), on(), State.IDLE, 0).action());
    }

    @Test
    void aPendingCollectIsWorkWithTheOptIn() {
        Limits collecting = new Limits(true, true, true, true, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(false, 0, 0, 3), collecting, State.IDLE, 0);
        assertEquals(Action.TAKE, decision.action());
        assertEquals(ChunkKeepReason.COLLECTING, decision.reason());
    }

    @Test
    void theCollectOptInHasItsOwnCap() {
        Limits capped = new Limits(true, true, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        // A cap with no reason of its own reported nothing at all here - no goggle line, no log line, no command row -
        // and was byte-for-byte an idle aisle, so an operator who set the opt-in could not see who was queued behind it
        // (M19 review).
        Decision decision = ChunkKeepDecision.decide(new Work(false, 0, 0, 3), capped, State.IDLE, 0);
        assertEquals(Action.REFUSE, decision.action(), "a collect-only aisle over its own cap is refused, not silent");
        assertEquals(ChunkKeepReason.AT_COLLECT_LIMIT, decision.reason());
        assertEquals(Action.TAKE, ChunkKeepDecision.decide(new Work(true, 0, 0, 3), capped, State.IDLE, 0).action(),
                "while real work is held whatever the collect cap says");
        assertEquals(ChunkKeepReason.CRANE_JOB,
                ChunkKeepDecision.decide(new Work(true, 0, 0, 3), capped, State.IDLE, 0).reason());
    }

    @Test
    void anIdleAisleIsNotRefusedByTheCollectCap() {
        // The refusal is about a pending collect that may not count. With nothing pending there is nothing to report.
        Limits capped = new Limits(true, true, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        assertEquals(Action.NONE, ChunkKeepDecision.decide(Work.NONE, capped, State.IDLE, 0).action());
        assertEquals(ChunkKeepReason.NONE, ChunkKeepDecision.decide(Work.NONE, capped, State.IDLE, 0).reason());
    }

    @Test
    void withoutTheOptInThereIsNoCollectRefusalEither() {
        // collectHoldEnabled off means the opt-in is off (or the feature is): a pending collect is simply not work then,
        // and reporting a cap the operator never set would be noise.
        assertEquals(ChunkKeepReason.NONE,
                ChunkKeepDecision.decide(new Work(false, 0, 0, 3), on(), State.IDLE, 0).reason());
    }

    @Test
    void aCollectOnlyHoldReleasesWhenTheOptInIsSwitchedOff() {
        Limits off = new Limits(true, false, true, false, FOOTPRINT, MAX_CHUNKS, LINGER, MAX_HOLD);
        Decision decision = ChunkKeepDecision.decide(new Work(false, 0, 0, 3), off, holding(0, 1000), 1000 + LINGER);
        assertEquals(Action.RELEASE, decision.action());
    }

    // --- the goggle contract ---------------------------------------------------------------------------------------

    @Test
    void holdingAndRefusingReasonsAreToldApart() {
        for (ChunkKeepReason reason : ChunkKeepReason.values()) {
            boolean holding = switch (reason) {
                case CRANE_JOB, OPEN_REQUESTS, PRODUCTION_ORDERS, COLLECTING, RELEASING -> true;
                case NONE, AT_LEVEL_LIMIT, AT_COLLECT_LIMIT, TOO_MANY_CHUNKS, GAVE_UP -> false;
            };
            assertEquals(holding, reason.isHolding(), reason.name());
        }
    }

    @Test
    void everyReasonHasALangKeyAndSurvivesASyncRoundTrip() {
        for (ChunkKeepReason reason : ChunkKeepReason.values()) {
            assertTrue(reason.langKey().startsWith("gui.goggles.chunk_keep_reason."), reason.langKey());
            assertEquals(reason, ChunkKeepReason.byName(reason.name()));
        }
        assertEquals(ChunkKeepReason.NONE, ChunkKeepReason.byName("something else"));
        assertEquals(ChunkKeepReason.NONE, ChunkKeepReason.byName(""));
    }

    @Test
    void negativeCountsAndNullsNeverThrow() {
        Work work = new Work(false, -5, -5, -5);
        assertEquals(0, work.openRequests());
        assertEquals(0, work.openOrders());
        assertEquals(0, work.collectPending());
        Decision decision = new Decision(null, null, 0);
        assertEquals(Action.NONE, decision.action());
        assertEquals(ChunkKeepReason.NONE, decision.reason());
        Limits limits = new Limits(true, true, true, true, -1, -1, -1, -1);
        assertEquals(0, limits.footprintChunks());
        assertEquals(0, limits.maxChunks());
        assertEquals(0, limits.releaseDelayTicks());
        assertEquals(0, limits.maxHoldTicks());
    }
}
