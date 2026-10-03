package dev.wareworks.core.crane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.job.TravelTimeModel;
import dev.wareworks.core.warehouse.CraneRoute;
import dev.wareworks.core.warehouse.RouteModel;

/**
 * The crane driving through corners (M21, ADR-033): the pose carries its aisle and its heading, a turn is a bounded
 * piece of motion with its own progress, and a warehouse that does not bend moves exactly as it always did.
 */
class CraneTurnTest {
    /** X two ticks per block, Y four ticks per level, arm two ticks — the state machine test's crane. */
    private static final CraneSpeeds SPEEDS = new CraneSpeeds(0.5, 0.25, 0.5);
    /** A quarter turn costs one block of travel, so it takes two ticks at {@link #SPEEDS}. */
    private static final double PENALTY = TravelTimeModel.DEFAULT_TURN_PENALTY_BLOCKS;
    private static final int MAX_TICKS = 10_000;
    private static final double EPSILON = 1.0E-9;

    /** An L: 8 rails east from the dock, then 5 south from the corner block. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5)), 6);
    /** The same warehouse with its corner rail taken out: two aisles, no route between them. */
    private static final NetworkGeometry SPLIT = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 20, 20, Heading.SOUTH, 5)), 6);
    /** Four short aisles in a zigzag, so one tick's travel budget can cross three corners at once. */
    private static final NetworkGeometry ZIGZAG = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 2),
            new BranchGeometry(1, 2, 0, Heading.SOUTH, 2),
            new BranchGeometry(2, 2, 2, Heading.EAST, 2),
            new BranchGeometry(3, 4, 2, Heading.SOUTH, 2)), 6);
    /** A shaft fast enough to cross the whole {@link #ZIGZAG} in one tick. */
    private static final CraneSpeeds FAST = new CraneSpeeds(16.0, 16.0, 16.0);

    private static final CraneNetwork NETWORK = CraneNetwork.of(CORNER, PENALTY);
    private static final CraneNetwork BROKEN = CraneNetwork.of(SPLIT, PENALTY);

    // --- a warehouse that does not bend --------------------------------------------------------------------------

    @Test
    void oneAisleMovesExactlyAsItDidBeforeCorners() {
        CraneNetwork straight = CraneNetwork.of(NetworkGeometry.single(Heading.EAST, 12, 6), PENALTY);
        for (double[] trip : new double[][] { { 0, 0, 7, 3 }, { 7, 3, 0, 0 }, { 2, 1, 2, 4 }, { 5, 2, 5, 2 } }) {
            List<CranePose> before = drive(CraneNetwork.SINGLE_BRANCH,
                    CranePose.at(trip[0], trip[1], Side.LEFT), CranePose.at(trip[2], trip[3], Side.RIGHT));
            List<CranePose> now = drive(straight,
                    CranePose.at(0, trip[0], trip[1], Side.LEFT, Heading.EAST),
                    CranePose.at(0, trip[2], trip[3], Side.RIGHT, Heading.EAST));
            assertEquals(axes(before), axes(now), "trip " + List.of(trip[0], trip[1], trip[2], trip[3]));
            assertEquals(TravelTimeModel.travelTicks(SPEEDS, trip[0], trip[1], trip[2], trip[3]), now.size());
        }
    }

    @Test
    void aCraneOnOneAisleNeverAsksForARouteAndNeverTurns() {
        List<CranePose> poses = drive(NETWORK, CranePose.at(0, 1.0, 0.0, Side.LEFT, Heading.EAST),
                CranePose.at(0, 6.0, 2.0, Side.LEFT, Heading.EAST));
        for (CranePose pose : poses) {
            assertEquals(0, pose.branch());
            assertEquals(CranePose.yawOf(Heading.EAST), pose.yaw());
        }
        assertEquals(TravelTimeModel.travelTicks(SPEEDS, 1.0, 0.0, 6.0, 2.0), poses.size());
    }

    // --- one corner ----------------------------------------------------------------------------------------------

    @Test
    void aCraneDrivesRoundACornerInTheTicksTheCostModelPromises() {
        CranePose start = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose target = CranePose.at(1, 5.0, 0.0, Side.LEFT, Heading.SOUTH);
        CraneRoute route = RouteModel.route(CORNER, 0, 0.0, 1, 5.0).orElseThrow();
        assertEquals(14.0, route.costBlocks(PENALTY), EPSILON, "8 east + 5 south + one quarter turn");
        List<CranePose> poses = drive(NETWORK, start, target);
        assertEquals(TravelTimeModel.travelAlongTicks(SPEEDS, route.costBlocks(PENALTY), 0.0, 0.0), poses.size(),
                "the whole route is one axis of blocks, so it is tick-exact and not rounded per leg");
        assertEquals(target, poses.get(poses.size() - 1));
    }

    @Test
    void theHandOverRenamesOneBlockAndTheTurnFollowsIt() {
        List<CranePose> poses = drive(NETWORK, CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST),
                CranePose.at(1, 5.0, 0.0, Side.LEFT, Heading.SOUTH));
        int handOver = -1;
        for (int i = 0; i < poses.size(); i++) {
            if (poses.get(i).branch() == 1) {
                handOver = i;
                break;
            }
        }
        assertTrue(handOver > 0, "the crane never reached the second aisle");
        assertEquals(0.0, poses.get(handOver).x(), EPSILON, "the corner block is position 0 of the second aisle");
        assertEquals(CranePose.yawOf(Heading.EAST), poses.get(handOver).yaw(),
                "the rename moves nothing: the machine still faces the way it drove in");
        for (int i = handOver + 1; i < poses.size(); i++)
            assertEquals(1, poses.get(i).branch(), "the crane never goes back to the aisle it came from");
    }

    @Test
    void theMachineDoesNotDriveWhileItTurns() {
        List<CranePose> poses = drive(NETWORK, CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST),
                CranePose.at(1, 5.0, 0.0, Side.LEFT, Heading.SOUTH));
        CranePose previous = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST);
        boolean turned = false;
        for (CranePose pose : poses) {
            if (pose.yaw() != previous.yaw() && pose.branch() == previous.branch()) {
                turned = true;
                assertEquals(previous.x(), pose.x(), EPSILON, "moved along the aisle while turning: " + pose);
            }
            previous = pose;
        }
        assertTrue(turned, "the crane never turned at all");
    }

    @Test
    void aTurnTakesAsManyTicksAsItsPenaltyInBlocks() {
        CranePose onTheCorner = CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.EAST);
        List<CranePose> poses = drive(NETWORK, onTheCorner, CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.SOUTH));
        assertEquals(2, poses.size(), "one block of penalty at half a block per tick");
        assertEquals(List.of(1.5, 2.0), poses.stream().map(CranePose::yaw).toList(),
                "the turn has its own progress, one tick's worth at a time");
    }

    @Test
    void aFreeTurnHappensInOneTick() {
        CraneNetwork instant = CraneNetwork.of(CORNER, 0.0);
        List<CranePose> poses = drive(instant, CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.EAST),
                CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.SOUTH));
        assertEquals(1, poses.size());
        assertEquals(CraneMotion.INSTANT_TURN, CraneMotion.turnSpeed(SPEEDS, 0.0));
        assertEquals(0.5, CraneMotion.turnSpeed(SPEEDS, 1.0), EPSILON);
    }

    @Test
    void theArmComesInBeforeTheMachineTurns() {
        CranePose out = new CranePose(1, 0.0, 0.0, CranePose.EXTENDED, Side.LEFT, CranePose.yawOf(Heading.EAST));
        List<CranePose> poses = drive(NETWORK, out, CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.SOUTH));
        for (CranePose pose : poses)
            assertTrue(pose.arm() == CranePose.RETRACTED || pose.yaw() == CranePose.yawOf(Heading.EAST),
                    "turned with the arm out: " + pose);
    }

    @Test
    void aMachineThatStillHasToTurnHasNotArrived() {
        CranePose facingEast = CranePose.at(1, 4.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose facingSouth = CranePose.at(1, 4.0, 0.0, Side.LEFT, Heading.SOUTH);
        assertFalse(CraneMotion.isAt(facingEast, facingSouth));
        assertTrue(CraneMotion.isAt(facingSouth, facingSouth));
        assertFalse(CraneMotion.isAt(CranePose.at(0, 4.0, 0.0, Side.LEFT, Heading.EAST), facingEast),
                "the same position on another aisle is another place");
    }

    // --- interruption and restoring ------------------------------------------------------------------------------

    @Test
    void aTurnInterruptedByANewTargetCarriesOnFromWhereItStood() {
        CranePose target = CranePose.at(1, 5.0, 0.0, Side.LEFT, Heading.SOUTH);
        CranePose pose = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST);
        while (pose.isAligned() || pose.branch() != 1)
            pose = stepOnce(NETWORK, pose, target);
        assertEquals(1.5, pose.yaw(), EPSILON, "caught in the middle of the turn");
        assertEquals(0.0, pose.x(), EPSILON);

        // The job changes: the crane is wanted back on the aisle it just left.
        CranePose newTarget = CranePose.at(0, 3.0, 0.0, Side.RIGHT, Heading.EAST);
        List<CranePose> rest = driveFrom(NETWORK, pose, newTarget);
        assertEquals(newTarget, rest.get(rest.size() - 1));
        for (CranePose step : rest)
            assertNotEquals(CranePose.yawOf(Heading.SOUTH), step.yaw(),
                    "finished a turn nobody wants any more: " + step);
        assertEquals(0, rest.stream().filter(step -> step.branch() == 1).count(),
                "the hand-over back onto the first aisle is a rename inside the same tick, not a tick of its own");
    }

    @Test
    void aPoseSavedInTheMiddleOfATurnRestoresExactly() {
        CranePose target = CranePose.at(1, 5.0, 0.0, Side.LEFT, Heading.SOUTH);
        CranePose pose = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST);
        int before = 0;
        while (pose.isAligned() || pose.branch() != 1) {
            pose = stepOnce(NETWORK, pose, target);
            before++;
        }
        assertFalse(pose.isAligned(), "mid-turn");
        assertEquals(Optional.empty(), pose.heading(), "a machine in mid-turn faces no aisle");

        // Exactly what persistence does with an untrusted save: read every field back, never throwing.
        CranePose restored = CranePose.sanitized(pose.branch(), pose.x(), pose.y(), pose.arm(), pose.side(),
                pose.yaw());
        assertEquals(pose, restored);
        assertEquals(drive(NETWORK, pose, target).size(), driveFrom(NETWORK, restored, target).size());
        assertEquals(TravelTimeModel.travelAlongTicks(SPEEDS,
                RouteModel.route(CORNER, 0, 0.0, 1, 5.0).orElseThrow().costBlocks(PENALTY), 0.0, 0.0),
                before + driveFrom(NETWORK, restored, target).size(),
                "a save in mid-turn costs the trip nothing");
    }

    // --- no route ------------------------------------------------------------------------------------------------

    @Test
    void aCraneWithNoRouteStandsStillWithItsArmIn() {
        CranePose pose = new CranePose(0, 4.0, 1.0, CranePose.EXTENDED, Side.LEFT, CranePose.yawOf(Heading.EAST));
        CranePose target = CranePose.at(1, 2.0, 3.0, Side.RIGHT, Heading.SOUTH);
        assertFalse(BROKEN.reachable(0, 1));
        CranePose retracted = pose;
        for (int i = 0; i < 10; i++)
            retracted = stepOnce(BROKEN, retracted, target);
        assertEquals(CranePose.RETRACTED, retracted.arm(), "the arm always comes in");
        assertEquals(4.0, retracted.x(), EPSILON, "and then the machine waits where it is");
        assertEquals(1.0, retracted.y(), EPSILON);
        assertEquals(0, retracted.branch());
        assertFalse(CraneMotion.isAt(retracted, target));
    }

    // --- the state machine ---------------------------------------------------------------------------------------

    @Test
    void aWholeJobAcrossACornerTakesExactlyItsTripTicks() {
        RackPosition source = new RackPosition(0, 2, 0, Side.LEFT);
        RackPosition target = new RackPosition(1, 4, 1, Side.RIGHT);
        CraneStateMachine<String, RackPosition> machine = new CraneStateMachine<>(Function.identity(),
                new CraneTimings(3, 4, 5), NETWORK);
        Harness harness = new Harness(machine, CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST));
        harness.send(CraneEvent.jobAssigned(
                TransportJob.store(new UUID(0L, 1L), source, target, "ore", 16)));

        long stop = TravelTimeModel.stopTicks(SPEEDS, 3);
        long toSource = TravelTimeModel.travelAlongTicks(SPEEDS,
                RouteModel.route(CORNER, 0, 0.0, 0, 2.0).orElseThrow().costBlocks(PENALTY), 0.0, 0.0);
        long toTarget = TravelTimeModel.travelAlongTicks(SPEEDS,
                RouteModel.route(CORNER, 0, 2.0, 1, 4.0).orElseThrow().costBlocks(PENALTY), 0.0, 1.0);
        assertEquals(toSource + stop + toTarget + stop, harness.tickUntilPhase(CranePhase.COMPLETE));
        assertEquals(1, harness.state.pose().branch(), "the crane ends the job on the second aisle");
        assertEquals(CranePose.yawOf(Heading.SOUTH), harness.state.pose().yaw());
    }

    @Test
    void aJobOnOneAisleIsPlannedAndDrivenExactlyAsItWasBeforeCorners() {
        RackPosition source = new RackPosition(0, 1, 0, Side.RIGHT);
        RackPosition target = new RackPosition(0, 6, 2, Side.LEFT);
        long expected = TravelTimeModel.tripTicks(SPEEDS, 3, 0.0, 0.0, source.x(), source.y(), target.x(),
                target.y());
        CraneStateMachine<String, RackPosition> withNetwork = new CraneStateMachine<>(Function.identity(),
                new CraneTimings(3, 4, 5), NETWORK);
        Harness onNetwork = new Harness(withNetwork, CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST));
        onNetwork.send(CraneEvent.jobAssigned(TransportJob.store(new UUID(0L, 1L), source, target, "ore", 16)));
        assertEquals(expected, onNetwork.tickUntilPhase(CranePhase.COMPLETE));

        CraneStateMachine<String, RackPosition> plain = new CraneStateMachine<>(Function.identity(),
                new CraneTimings(3, 4, 5));
        Harness before = new Harness(plain, CranePose.at(0.0, 0.0, Side.LEFT));
        before.send(CraneEvent.jobAssigned(TransportJob.store(new UUID(0L, 1L), source, target, "ore", 16)));
        assertEquals(expected, before.tickUntilPhase(CranePhase.COMPLETE));
        assertEquals(axes(before.poses), axes(onNetwork.poses), "the same machine, corner or no corner");
    }

    // --- interpolation -------------------------------------------------------------------------------------------

    @Test
    void aTurnIsInterpolatedTheShortWayRound() {
        CranePose from = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.NORTH);
        CranePose to = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.WEST);
        assertEquals(3.5, CranePose.lerp(from, to, 0.5).yaw(), EPSILON,
                "north to west is a quarter turn anticlockwise, not three quarters the other way");
        assertEquals(3.5, CranePose.lerp(to, from, 0.5).yaw(), EPSILON, "and back again the other way round");
        assertEquals(-1.0, CranePose.yawDelta(0.0, 3.0), EPSILON);
        assertEquals(2.0, CranePose.yawDelta(0.0, 2.0), EPSILON, "a half turn resolves clockwise");
    }

    /**
     * The tick a machine is handed over at a corner is not interpolated along {@code x}: the number before and the
     * number after count along different aisles, so a renderer that mixed them would drag the machine up to a whole
     * aisle length through the warehouse and back inside one tick. The pose keeps the link block it was renamed to,
     * and the world offsets of the two names — which do line up — are what the renderer really interpolates
     * ({@code WarehouseLayout#railOffset}).
     */
    @Test
    void aHandOverIsNotInterpolatedAsMotion() {
        CranePose before = CranePose.at(0, 7.5, 1.0, Side.LEFT, Heading.EAST);
        CranePose after = before.handedOver(1, 0.0).withYaw(1.25);
        for (double partial : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            CranePose shown = CranePose.lerp(before, after, partial);
            assertEquals(1, shown.branch(), "the machine is named on the aisle it was handed over to");
            assertEquals(0.0, shown.x(), EPSILON, "and stays at the link block rather than " + 7.5 * (1.0 - partial));
        }
        // Everything that does mean the same before and after still interpolates: the lift, the arm and the yaw.
        CranePose lifting = CranePose.at(0, 8.0, 0.0, Side.LEFT, Heading.EAST).handedOver(1, 0.0);
        CranePose lifted = new CranePose(1, 0.0, 2.0, CranePose.RETRACTED, Side.LEFT, 1.5);
        CranePose half = CranePose.lerp(lifting, lifted, 0.5);
        assertEquals(1.0, half.y(), EPSILON);
        assertEquals(1.25, half.yaw(), EPSILON);
        // On one aisle nothing changed: x is interpolated exactly as it always was.
        assertEquals(2.5, CranePose.lerp(CranePose.at(1, 2.0, 0.0, Side.LEFT, Heading.SOUTH),
                CranePose.at(1, 3.0, 0.0, Side.LEFT, Heading.SOUTH), 0.5).x(), EPSILON);
    }

    @Test
    void aPoseSaysWhichAisleItIsOnAndWhichWayItFaces() {
        assertEquals(0.0, CranePose.yawOf(Heading.NORTH));
        assertEquals(1.0, CranePose.yawOf(Heading.EAST));
        assertEquals(2.0, CranePose.yawOf(Heading.SOUTH));
        assertEquals(3.0, CranePose.yawOf(Heading.WEST));
        for (Heading heading : Heading.values()) {
            CranePose pose = CranePose.at(1, 2.0, 0.0, Side.LEFT, heading);
            assertEquals(Optional.of(heading), pose.heading());
            assertTrue(pose.isAligned());
            assertEquals(CranePose.yawOf(heading.right()), CranePose.normalizeYaw(pose.yaw() + 1.0));
        }
        CranePose old = new CranePose(3.0, 1.0, CranePose.RETRACTED, Side.LEFT);
        assertEquals(RackPosition.FIRST_BRANCH, old.branch(), "a pose built the way it always was is the first aisle");
        assertEquals(CranePose.DEFAULT_YAW, old.yaw());
        assertEquals(old, CranePose.at(3.0, 1.0, Side.LEFT));
    }

    @Test
    void aClientOnlySnapsWhenItReallyTurnedTheWrongWay() {
        CranePose own = CranePose.at(1, 0.0, 0.0, Side.LEFT, Heading.EAST).withYaw(1.1);
        assertFalse(CraneResync.diverges(own, own.withYaw(1.2), SPEEDS));
        assertTrue(CraneResync.diverges(own, own.withYaw(1.6), SPEEDS));
        CranePose renamed = own.handedOver(0, 8.0);
        assertFalse(CraneResync.diverges(own, renamed, SPEEDS),
                "a hand-over renames one block, so the two sides may disagree about its number for a tick");
    }

    // --- what the wheels see -------------------------------------------------------------------------------------

    /**
     * The wheels turn the way the machine really moves. A trip out and the same trip back leave the odometer exactly
     * where it started, because the blocks driven are <b>signed</b> — measuring the distance instead made every
     * return trip look like a drive forward (M21 review fix).
     */
    @Test
    void anOutAndBackTripLeavesTheWheelsWhereTheyStarted() {
        CranePose home = CranePose.at(0, 1.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose away = CranePose.at(0, 6.0, 0.0, Side.LEFT, Heading.EAST);
        double odometer = driveOdometer(NETWORK, home, away, 0.0);
        assertEquals(5.0, odometer, EPSILON, "five blocks out");
        assertEquals(0.0, driveOdometer(NETWORK, away, home, odometer), EPSILON, "and five back");
    }

    /**
     * A tick that hands over at the corner is counted leg by leg: the rename itself moves the machine by nothing, and
     * the short drive up to the corner in the same tick still turns the wheels. Over the whole trip the odometer is
     * the route's own blocks — the turn costs time, not distance.
     */
    @Test
    void aTripRoundACornerCountsTheBlocksOfItsRouteAndNotItsRenames() {
        CranePose start = CranePose.at(0, 5.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose target = CranePose.at(1, 3.0, 0.0, Side.RIGHT, Heading.SOUTH);
        double expected = RouteModel.route(CORNER, 0, 5.0, 1, 3.0).orElseThrow().blocks();
        assertEquals(expected, driveOdometer(NETWORK, start, target, 0.0), EPSILON);
        assertEquals(6.0, expected, EPSILON, "three blocks to the corner and three down the second aisle");
    }

    /**
     * The swing of a tick is measured leg by leg along its route, exactly as the blocks driven are, because one tick's
     * budget can finish several legs (M25 review fix, issue #16).
     * <p>
     * With {@code crane.turnPenaltyBlocks = 0} — a documented instant turn — and a fast enough shaft, a crane crosses
     * a whole zigzag in one tick. The three quarter turns it really spent are what the goggles' corner count is made
     * of; the shortest arc between the two end poses reads <b>one</b>, because the first and the third cancel.
     */
    @Test
    void theSwingOfATickIsCountedLegByLegAndNotAsTheNetArc() {
        CraneNetwork instant = CraneNetwork.of(ZIGZAG, 0.0);
        CranePose start = CranePose.at(0, 0.0, 0.0, Side.LEFT, Heading.EAST);
        CranePose target = CranePose.at(3, 1.0, 0.0, Side.LEFT, Heading.SOUTH);
        CraneRoute route = RouteModel.route(ZIGZAG, 0, 0.0, 3, 1.0).orElseThrow();
        CranePose after = CraneMotion.step(start, target, FAST, route, 0.0);
        assertEquals(target, after, "the whole zigzag fits in one tick of this shaft with free turns");
        assertEquals(3.0, CraneMotion.quarterTurnsSwung(start, after, route), EPSILON,
                "three corners: east, south, east, south");
        assertEquals(1.0, Math.abs(CranePose.yawDelta(start.yaw(), after.yaw())), EPSILON,
                "while the two end poses alone read one, because the first and the third corner cancel");
        assertEquals(3.0, CraneTickMotion.of(start, after, CraneMotion.quarterTurnsSwung(start, after, route))
                .quarterTurns(), EPSILON);
        // Without a route there is no leg to walk, and a warehouse of one aisle has no corner to miss.
        assertEquals(1.0, CraneMotion.quarterTurnsSwung(start, after, null), EPSILON);
    }

    @Test
    void aRenameOnItsOwnTurnsTheWheelsByNothing() {
        CranePose onFirst = CranePose.at(0, 8.0, 0.0, Side.LEFT, Heading.EAST);
        CraneRoute route = RouteModel.route(CORNER, 0, 8.0, 1, 3.0).orElseThrow();
        assertEquals(0.0, CraneMotion.blocksDriven(onFirst, onFirst.handedOver(1, 0.0), route), EPSILON);
        assertEquals(0.0, CraneMotion.blocksDriven(onFirst, onFirst.handedOver(1, 0.0), null), EPSILON,
                "without a route there is nothing the wheels could have measured");
    }

    /** Drives the whole trip and sums what the wheels saw, exactly as the client does every tick. */
    private static double driveOdometer(CraneNetwork network, CranePose start, CranePose target, double from) {
        double odometer = from;
        CranePose pose = start;
        for (int tick = 0; !CraneMotion.isAt(pose, target); tick++) {
            CraneRoute route = pose.branch() == target.branch() ? null
                    : network.route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
            CranePose next = CraneMotion.step(pose, target, SPEEDS, route, network.turnPenaltyBlocks());
            odometer += CraneMotion.blocksDriven(pose, next, route);
            pose = next;
            if (tick > MAX_TICKS)
                fail("target not reached: " + pose + " → " + target);
        }
        return odometer;
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** One motion tick the way the state machine takes it: the route is recomputed from the live network. */
    private static CranePose stepOnce(CraneNetwork network, CranePose pose, CranePose target) {
        CraneRoute route = pose.branch() == target.branch() ? null
                : network.route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
        return CraneMotion.step(pose, target, SPEEDS, route, network.turnPenaltyBlocks());
    }

    private static List<CranePose> drive(CraneNetwork network, CranePose start, CranePose target) {
        return driveFrom(network, start, target);
    }

    private static List<CranePose> driveFrom(CraneNetwork network, CranePose start, CranePose target) {
        List<CranePose> poses = new ArrayList<>();
        CranePose pose = start;
        while (!CraneMotion.isAt(pose, target)) {
            pose = stepOnce(network, pose, target);
            poses.add(pose);
            if (poses.size() > MAX_TICKS)
                fail("target not reached: " + pose + " → " + target);
        }
        return poses;
    }

    /** Everything about a pose that a warehouse without corners ever had, so the two can be compared. */
    private static List<String> axes(List<CranePose> poses) {
        List<String> axes = new ArrayList<>(poses.size());
        for (CranePose pose : poses)
            axes.add(pose.x() + "/" + pose.y() + "/" + pose.arm() + "/" + pose.side());
        return axes;
    }

    /** Plays the block entity: answers perform effects at once, records every pose. */
    private static final class Harness {
        private final CraneStateMachine<String, RackPosition> machine;
        private final List<CranePose> poses = new ArrayList<>();
        private CraneState<String, RackPosition> state;

        Harness(CraneStateMachine<String, RackPosition> machine, CranePose start) {
            this.machine = machine;
            this.state = CraneState.idle(start);
        }

        void send(CraneEvent<String, RackPosition> event) {
            CraneStateMachine.Transition<String, RackPosition> transition = machine.apply(state, event);
            state = transition.state();
            assertTrue(state.isConsistent(), () -> "inconsistent after " + event + ": " + state);
            for (CraneEffect<String, RackPosition> effect : transition.effects()) {
                switch (effect) {
                    case CraneEffect.PerformPick<String, RackPosition> pick ->
                            send(CraneEvent.pickResult(pick.amount()));
                    case CraneEffect.PerformDrop<String, RackPosition> drop ->
                            send(CraneEvent.dropResult(drop.amount(), 0));
                    default -> {
                    }
                }
            }
        }

        int tickUntilPhase(CranePhase phase) {
            int ticks = 0;
            while (state.phase() != phase) {
                if (ticks++ > MAX_TICKS)
                    fail("phase " + phase + " not reached, state " + state);
                send(CraneEvent.tick(SPEEDS));
                poses.add(state.pose());
            }
            return ticks;
        }
    }
}
