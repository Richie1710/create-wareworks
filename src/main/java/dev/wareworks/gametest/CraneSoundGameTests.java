package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.simibubi.create.AllSoundEvents;

import dev.wareworks.Wareworks;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the crane sounds played on the server ({@code docs/stacker-crane.md} §8.1): the wiring of
 * {@code content.crane.CraneSounds} into the crane's tick, transfers and phase changes, which {@code CraneSoundCuesTest}
 * (pure cue logic) cannot see.
 * <p>
 * Sounds are recorded from NeoForge's {@link PlayLevelSoundEvent.AtPosition}, which {@code ServerLevel#playSeededSound}
 * fires for every server-played sound, filtered to the test area (tests of a batch run side by side in one level). The
 * listener is registered in the first step and removed in the last; a failed test leaves an inert recorder of its own area
 * on the bus until the test server stops.
 * <p>
 * Layout on the {@code aisle_16x10x7} floor as in {@link CraneJobGameTests} ({@link AisleFixture}): input at position 0 on
 * the right, output at position 1 on the right, storage at position 5 on the left; creative motor at {@value #TEST_RPM} RPM.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class CraneSoundGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 6;
    private static final RackPosition INPUT = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition FAR = new RackPosition(5, 0, Side.LEFT);

    private static final int TEST_RPM = 128;
    private static final int JOB_TIMEOUT_TICKS = 1200;
    private static final int PAUSE_IDLE_TICKS = 40;
    private static final int STORED_IRON = 32;
    private static final int STOCKED_DIAMONDS = 20;
    private static final int REQUESTED_DIAMONDS = 10;
    private static final int STACK = 64;
    /** A transfer sound comes from the grabber at the rack position: within this distance of that block's centre. */
    private static final double TRANSFER_SOUND_RADIUS = 0.75;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    /** The main event of each cue ({@code CraneSounds}); Create's wrapped entries play extra "_compounded" events too. */
    private static final ResourceLocation TRAVEL_START = AllSoundEvents.CONTRAPTION_ASSEMBLE.getId();
    private static final ResourceLocation TRAVEL_STOP = AllSoundEvents.CONTRAPTION_DISASSEMBLE.getId();
    private static final ResourceLocation RAIL_CLACK = SoundEvents.METAL_STEP.getLocation();
    private static final ResourceLocation LIFT_CHAIN = SoundEvents.CHAIN_STEP.getLocation();
    private static final ResourceLocation ARM_EXTEND = SoundEvents.PISTON_EXTEND.getLocation();
    private static final ResourceLocation ARM_RETRACT = SoundEvents.PISTON_CONTRACT.getLocation();
    private static final ResourceLocation PICK = SoundEvents.ITEM_PICKUP.getLocation();
    private static final ResourceLocation DROP = AllSoundEvents.DEPOT_PLOP.getId();

    private CraneSoundGameTests() {
    }

    /**
     * A store job from the input to a storage location, paused by losing rotation while the crane carries the items: one
     * pick sound at the input and one drop sound at the storage location, arm sounds for both stops, one travel start
     * and one travel stop (the resumed crane does not start again), rail clacks on the way, no lift sound on level 0,
     * and not a single sound while the crane is paused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneSoundsFollowTheJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR);
        aisle.input(INPUT);
        SoundRecorder sounds = new SoundRecorder(helper);
        AtomicInteger heardBeforePause = new AtomicInteger();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    sounds.start();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT)), IRON.toStack(STORED_IRON));
                })
                .thenWaitUntil(() -> aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, STORED_IRON))
                .thenExecute(() -> {
                    sounds.assertOnceAt(PICK, aisle.absoluteRackPos(INPUT), "pick");
                    helper.assertValueEqual(sounds.count(DROP), 0, "no drop sound before the drop");
                    aisle.motor().generatedSpeed.setValue(0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().pauseReason(), CranePauseReason.NO_ROTATION,
                        "paused after losing rotation"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.dock().craneState().phase(), CranePhase.TRAVEL_TO_TARGET,
                            "paused while travelling");
                    heardBeforePause.set(sounds.size());
                })
                .thenIdle(PAUSE_IDLE_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(sounds.size(), heardBeforePause.get(),
                            "a paused crane is silent, but played " + sounds.since(heardBeforePause.get()));
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(FAR, IRON), (long) STORED_IRON, "iron stored");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    sounds.stop();
                    sounds.assertOnceAt(PICK, aisle.absoluteRackPos(INPUT), "pick");
                    sounds.assertOnceAt(DROP, aisle.absoluteRackPos(FAR), "drop");
                    helper.assertValueEqual(sounds.count(ARM_EXTEND), 2, "arm extends at the input and at the target");
                    helper.assertValueEqual(sounds.count(ARM_RETRACT), 2, "arm retracts at the input and at the target");
                    helper.assertValueEqual(sounds.count(TRAVEL_START), 1,
                            "one travel start (none to the source at position 0, none after the pause)");
                    helper.assertValueEqual(sounds.count(TRAVEL_STOP), 1, "one travel stop at the target");
                    helper.assertTrue(sounds.count(RAIL_CLACK) > 0, "rail clacks on the way to position 5");
                    helper.assertValueEqual(sounds.count(LIFT_CHAIN), 0, "no lift sound on level 0");
                })
                .thenSucceed();
    }

    /**
     * A retrieve job whose source a player empties while the crane travels to it: the arm extends and retracts at the
     * source, but the zero pick plays no pick sound and nothing is dropped.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = JOB_TIMEOUT_TICKS)
    public static void craneZeroPickIsSilent(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(FAR, DIAMOND.toStack(STOCKED_DIAMONDS));
        aisle.output(OUTPUT);
        SoundRecorder sounds = new SoundRecorder(helper);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) STOCKED_DIAMONDS, "indexed");
                })
                .thenExecute(() -> {
                    sounds.start();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    helper.assertTrue(aisle.controller().request(aisle.absoluteRackPos(OUTPUT), DIAMOND, REQUESTED_DIAMONDS)
                            .isAccepted(), "request accepted");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isPresent(), "retrieve job assigned"))
                .thenExecute(() -> {
                    helper.assertFalse(aisle.dock().currentJob().orElseThrow().picked(), "not picked yet");
                    IItemHandler chest = aisle.handlerAt(aisle.inventoryPos(FAR));
                    long taken = 0;
                    for (int slot = 0; slot < chest.getSlots(); slot++)
                        taken += chest.extractItem(slot, STACK, false).getCount();
                    helper.assertValueEqual(taken, (long) STOCKED_DIAMONDS, "a player took every diamond");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().currentJob().isEmpty(), "the job ended"))
                .thenExecute(() -> {
                    sounds.stop();
                    helper.assertTrue(aisle.dock().heldItems().isEmpty(), "nothing held");
                    helper.assertTrue(sounds.count(ARM_EXTEND) > 0 && sounds.count(ARM_RETRACT) > 0,
                            "the arm moved at the empty source: " + sounds.since(0));
                    helper.assertValueEqual(sounds.count(PICK), 0, "no pick sound for a zero pick");
                    helper.assertValueEqual(sounds.count(DROP), 0, "no drop sound");
                })
                .thenSucceed();
    }

    /** One sound heard in the test area: its event id and world position. */
    private record Heard(ResourceLocation sound, Vec3 position) {
        @Override
        public String toString() {
            return sound + "@" + position;
        }
    }

    /** Records every sound the server plays inside one test's area while started. Server thread only. */
    private static final class SoundRecorder implements Consumer<PlayLevelSoundEvent.AtPosition> {
        private final GameTestHelper helper;
        private final Level level;
        private final AABB area;
        private final List<Heard> heard = new ArrayList<>();

        SoundRecorder(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.area = helper.getBounds();
        }

        void start() {
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, this);
        }

        void stop() {
            NeoForge.EVENT_BUS.unregister(this);
        }

        @Override
        public void accept(PlayLevelSoundEvent.AtPosition event) {
            if (event.getLevel() != level || event.getSound() == null || !area.contains(event.getPosition()))
                return;
            heard.add(new Heard(event.getSound().value().getLocation(), event.getPosition()));
        }

        int size() {
            return heard.size();
        }

        List<Heard> since(int index) {
            return List.copyOf(heard.subList(index, heard.size()));
        }

        int count(ResourceLocation sound) {
            return (int) heard.stream().filter(entry -> entry.sound().equals(sound)).count();
        }

        /** Exactly one {@code sound} was heard, and it came from within reach of the block at {@code rackPos}. */
        void assertOnceAt(ResourceLocation sound, BlockPos rackPos, String what) {
            List<Heard> matching = heard.stream().filter(entry -> entry.sound().equals(sound)).toList();
            helper.assertValueEqual(matching.size(), 1, "one " + what + " sound, heard: " + heard);
            double distance = matching.getFirst().position().distanceTo(Vec3.atCenterOf(rackPos));
            helper.assertTrue(distance < TRANSFER_SOUND_RADIUS,
                    what + " sound at the rack position " + rackPos + ", but " + distance + " blocks away");
        }
    }
}
