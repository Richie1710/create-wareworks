package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.foundation.item.ItemHelper;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.core.storage.BayTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of the pallet ({@code docs/warehouse-system.md} §3.8 and §8, M28, issue #20, ADR-046): the load of a broken
 * rack bay, lying in the world as <b>one</b> entity.
 * <p>
 * The <b>break</b> itself — that a bay broken with goods in it leaves exactly one pallet, that an item census is equal
 * before and after, that a refused spawn still loses nothing, and that a pallet refills a bay by hand — is the bay's
 * behaviour and lives in {@code gametest.RackBayGameTests}. What is here is everything the pallet itself has to be true
 * about:
 * <ul>
 * <li>{@code palletpersistenceroundtrip} — 65 536 items through a save and a load, <b>twice</b>. The one test that can
 * catch the {@code ItemStack.save} trap: its codec bounds a count at 99 while the network codec does not, so a pallet
 * built on a stack would look perfect for a whole session and empty itself on the next world load;</li>
 * <li>{@code palletsurvivesanysavedata} — save data nobody should trust, and the one clamp that <i>is</i> item
 * loss;</li>
 * <li>{@code palletsurviveslavafireandexplosion} — the three things the issue names, for the reason it gives: a pallet
 * holding 65 000 items falling in lava would be the stupidest loss in the game;</li>
 * <li>{@code palletisnotpocketableandcannotride} — the refusal the whole feature rests on. Without it a brass bay
 * would be a removal crate worth thirty-eight shulker boxes, obtainable without ever visiting the End;</li>
 * <li>{@code ahopperdrainsapalletandafunneldoesnot} — the one Create limitation that would otherwise be the first bug
 * report, pinned against Create's own code rather than remembered, with a control that proves the funnel was live. It
 * also proves that a drained pallet goes away instead of standing there as an invisible husk;</li>
 * <li>{@code pallethandgestures} — an empty hand takes one item, Shift takes one stack, a full inventory gets nothing
 * and spills nothing, and <b>nothing is ever put in</b>.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PalletGameTests {
    /** Where a pallet is put in most tests: well inside the test area, on the template's floor. */
    private static final BlockPos PALLET = new BlockPos(3, BASE_Y, 3);
    /** The chest a funnel feeds, with the funnel one block above it. */
    private static final BlockPos FUNNEL_TARGET = new BlockPos(1, BASE_Y, 1);
    /** A hopper standing on the floor, with a pallet on top of it. */
    private static final BlockPos HOPPER = new BlockPos(5, BASE_Y, 1);
    /** The lava pit, in the far corner, so flowing lava cannot reach anything else a test looks at. */
    private static final BlockPos PIT = new BlockPos(5, BASE_Y, 5);

    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey ENDER_PEARL = ItemKey.of(Items.ENDER_PEARL);

    private static final int STACK = 64;
    /** A full brass bay of cobblestone: the number that makes the {@code ItemStack.save} trap visible. */
    private static final int BRASS_LOAD = 65_536;
    /** A load small enough that a hopper empties it inside one test's budget: one item per pull, every eight ticks. */
    private static final int HOPPER_LOAD = 5;
    /** Ticks the pallet sits in the funnel's own block before the funnel is believed to have refused it. */
    private static final int FUNNEL_TICKS = 40;
    /**
     * How high above the bottom of the funnel's block the control item entity is dropped: above the funnel's own
     * collision shape ({@code AllShapes.FUNNEL_FLOOR} reaches 8 of 16 pixels), so it falls into the mouth.
     */
    private static final double CONTROL_DROP_HEIGHT = 0.75;
    /** Ticks a pallet sits in lava, on fire, after an explosion. */
    private static final int BURN_TICKS = 200;
    /** Seconds of fire a pallet is set alight for — far more than the 15 lava would give it. */
    private static final float FIRE_SECONDS = 60.0F;
    /** Radius of the explosion set off on top of the pallet, a little more than a creeper's. */
    private static final float BLAST_RADIUS = 4.0F;
    /** Generous budgets: a hopper moves one item every eight ticks, and a pallet has to fall onto it first. */
    private static final int MACHINE_TIMEOUT_TICKS = 400;
    private static final int BURN_TIMEOUT_TICKS = 400;
    /** How far around the test bounds dropped item entities are looked for. */
    private static final double DROP_RADIUS = 3.0;
    /** Edge length of the box a freshly spawned pallet is looked for in. */
    private static final double FIND_BOX = 3.0;
    /** The hotbar slot the test's player holds things in: the last one, so it is never the first free slot. */
    private static final int HAND_SLOT = 8;

    private PalletGameTests() {
    }

    // --- persistence -------------------------------------------------------------------------------------------------

    /**
     * 65 536 items — a full brass bay — through a save and a load, twice.
     * <p>
     * <b>The only test that can catch the {@code ItemStack.save} trap</b>, and it fails only <i>after</i> the load:
     * {@code ItemStack.CODEC} bounds its count at 1..99 while the network codec is unbounded, so a pallet that saved
     * itself as a stack would show the right number all session and come back with 99 or with nothing. The round trip
     * runs twice, because a load that truncated would still have looked right on the first pass.
     * <p>
     * A 16-stacking item goes through it as well, so that nothing on the path quietly assumes 64.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void palletPersistenceRoundTrip(GameTestHelper helper) {
        assertRoundTrip(helper, COBBLESTONE, BRASS_LOAD);
        assertRoundTrip(helper, ENDER_PEARL, BayTier.BRASS.defaultStacks() * ENDER_PEARL.getMaxStackSize());
        helper.succeed();
    }

    private static void assertRoundTrip(GameTestHelper helper, ItemKey key, int load) {
        PalletEntity pallet = spawn(helper, PALLET, key, load);
        PalletEntity reloaded = pallet;
        for (int pass = 1; pass <= 2; pass++) {
            CompoundTag saved = new CompoundTag();
            helper.assertTrue(reloaded.save(saved), "a living pallet is saved, pass " + pass);
            reloaded = loadFrom(helper, saved, "pass " + pass + " of " + key);
            helper.assertValueEqual(reloaded.carriedCount(), load, "the load came back whole, pass " + pass);
            helper.assertTrue(reloaded.carriedKey().filter(key::equals).isPresent(),
                    "and it is still " + key + ", pass " + pass);
        }
        reloaded.discard();
        pallet.discard();
    }

    /**
     * Save data nobody should trust, because {@code /data merge} and an uploaded schematic both reach it. Every case is
     * bounded, none throws, and the one that <b>is</b> item loss says so in the log instead of quietly happening:
     * <ul>
     * <li>no count at all, a count of 0 and a negative count: the pallet <b>removes itself</b>, which is vanilla's own
     * answer for an item entity whose stack it cannot read — an empty pallet nobody can get rid of would be worse;</li>
     * <li>an item type that cannot be decoded (its mod was removed): removed, by the same rule;</li>
     * <li>a count above what any bay can hold: clamped to {@link PalletEntity#MAX_LOAD}, and logged as the loss it
     * is.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void palletSurvivesAnySaveData(GameTestHelper helper) {
        PalletEntity live = spawn(helper, PALLET, COBBLESTONE, STACK);

        assertRemoved(helper, savedWith(helper, live, null, 0), "a pallet with no load at all");
        assertRemoved(helper, savedWith(helper, live, COBBLESTONE, 0), "a count of zero");
        assertRemoved(helper, savedWith(helper, live, COBBLESTONE, -1), "a negative count");
        CompoundTag brokenKey = savedWith(helper, live, COBBLESTONE, STACK);
        brokenKey.putString(PalletEntity.STORED_TAG, "not a compound at all");
        assertRemoved(helper, brokenKey, "an item type that cannot be read");

        PalletEntity clamped = loadFrom(helper, savedWith(helper, live, COBBLESTONE, PalletEntity.MAX_LOAD + STACK),
                "a count no configuration can produce");
        helper.assertTrue(!clamped.isRemoved(), "a clamped pallet keeps everything it legally could");
        helper.assertValueEqual(clamped.carriedCount(), PalletEntity.MAX_LOAD, "clamped to the ceiling");
        clamped.discard();

        live.discard();
        helper.succeed();
    }

    // --- what must not destroy it ------------------------------------------------------------------------------------

    /**
     * Lava, fire and an explosion, which is the list the issue names: "a pallet holding 65 000 items falling in lava
     * would be the stupidest loss in the game".
     * <p>
     * None of it costs an override — {@code Entity#hurt} has no health to take from a plain entity and
     * {@code Entity#lavaHurt} has its whole body inside {@code if (!fireImmune())} — so this test exists to catch
     * anything later added that breaks either, and to prove that after 200 ticks of all three at once the pallet is
     * still alive and still carrying every item.
     * <p>
     * The explosion uses {@code ExplosionInteraction.NONE}, which maps to {@code BlockInteraction.KEEP}, so it breaks
     * no blocks: what is under test is the pallet, and an explosion that tore out the test's own floor would only drop
     * it into the void. Entity damage and knockback are identical either way, because both go through
     * {@code Explosion#explode}.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = BURN_TIMEOUT_TICKS)
    public static void palletSurvivesLavaFireAndExplosion(GameTestHelper helper) {
        // A basin, so the lava source cannot flow across the test area and reach anything else.
        for (Direction side : Direction.Plane.HORIZONTAL)
            helper.setBlock(PIT.relative(side), Blocks.STONE);
        PalletEntity pallet = spawn(helper, PIT, COBBLESTONE, BRASS_LOAD);
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, BRASS_LOAD);
        ItemCensus.assertEquals(helper, conserved, "before the pallet is burned");

        helper.setBlock(PIT, Blocks.LAVA);
        pallet.igniteForSeconds(FIRE_SECONDS);
        helper.getLevel().explode(null, pallet.getX(), pallet.getY(), pallet.getZ(), BLAST_RADIUS,
                Level.ExplosionInteraction.NONE);

        helper.startSequence()
                .thenExecuteAfter(BURN_TICKS, () -> {
                    helper.assertTrue(pallet.isAlive(), "a pallet survives lava, fire and an explosion");
                    helper.assertValueEqual(pallet.carriedCount(), BRASS_LOAD, "with its whole load");
                    helper.assertTrue(pallet.carriedKey().filter(COBBLESTONE::equals).isPresent(),
                            "and the load is unchanged");
                    ItemCensus.assertEquals(helper, conserved, "after the pallet was burned");
                })
                .thenSucceed();
    }

    /**
     * The refusal the whole feature rests on. Dropping a bay as a filled item was rejected because a brass bay is
     * 1 024 stacks, which would make it thirty-eight times a shulker box and obtainable without ever visiting the End
     * — so the pallet must not become that by another route:
     * <ul>
     * <li><b>no item form at all</b>, which is also why both pick hooks can safely be left at their defaults;</li>
     * <li>middle-clicking it gives nothing, through either of the two methods that answer that;</li>
     * <li>it rides nothing, so it cannot be carried away in a minecart or a boat;</li>
     * <li>it uses no portal and changes no dimension;</li>
     * <li>and it <b>is</b> saved with its chunk, which is the one thing that must not have been traded away for any of
     * the above.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void palletIsNotPocketableAndCannotRide(GameTestHelper helper) {
        helper.assertTrue(
                !BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(Wareworks.ID, "pallet")),
                "a pallet has no item form, and that is what makes it unpocketable");

        PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, BRASS_LOAD);
        helper.assertTrue(pallet.getPickResult() == null, "middle-clicking a pallet gives nothing");
        ItemStack picked = pallet.getPickedResult(null);
        helper.assertTrue(picked == null || picked.isEmpty(), "and nothing through NeoForge's own pick hook either");

        ServerLevel level = helper.getLevel();
        for (EntityType<?> vehicleType : List.of(EntityType.MINECART, EntityType.BOAT)) {
            Entity vehicle = vehicleType.create(level);
            if (vehicle == null) {
                helper.fail("could not create a " + vehicleType.getDescriptionId() + " to try a pallet on", PALLET);
                return;
            }
            vehicle.moveTo(pallet.getX(), pallet.getY(), pallet.getZ());
            level.addFreshEntity(vehicle);
            helper.assertTrue(!pallet.startRiding(vehicle),
                    "a pallet does not ride a " + vehicleType.getDescriptionId());
            helper.assertTrue(pallet.getVehicle() == null, "and is still standing on its own");
            vehicle.discard();
        }

        helper.assertTrue(!pallet.canUsePortal(false), "a pallet uses no portal");
        helper.assertTrue(!pallet.canChangeDimensions(level, level), "and changes no dimension");
        helper.assertTrue(pallet.shouldBeSaved(), "and it is still saved with its chunk");

        pallet.discard();
        helper.succeed();
    }

    // --- machines ----------------------------------------------------------------------------------------------------

    /**
     * The one Create limitation of this feature, pinned against Create's own code rather than remembered: <b>a vanilla
     * hopper drains a pallet and a Create funnel does not</b>.
     * <p>
     * A hopper reaches it because NeoForge's own hopper code falls back to the entity item capability
     * ({@code VanillaInventoryCodeHooks.getItemHandlerAt}). Create's belts, chutes, funnels, depots and ejectors all
     * gate on {@code ItemHelper.fromItemEntity}, whose whole body answers only for a {@code PackageEntity} and an
     * {@code ItemEntity} — so a pallet is invisible to them. That cuts both ways, and the second half is the better
     * half: <b>no Create logistics block can delete or teleport a pallet either.</b>
     * <p>
     * Nothing is called by hand here. The pallet simply <b>stands in the funnel's own block</b> for the whole test, so
     * the real {@code entityInside} runs every tick from {@code Entity#move}, which is the only honest way to say "the
     * funnel had every chance". A plain {@code ItemEntity} is then dropped into the same block and <b>is</b> taken,
     * which is the control that proves the funnel was live and aimed at the chest rather than merely idle — and the
     * gate itself is asserted directly, because that is the thing that must not drift when Create changes.
     * <p>
     * One running census covers both halves, so nothing may be destroyed to set the second one up.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = MACHINE_TIMEOUT_TICKS)
    public static void aHopperDrainsAPalletAndAFunnelDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos funnelPos = FUNNEL_TARGET.above();
        helper.setBlock(FUNNEL_TARGET, Blocks.CHEST);
        // FACING is the direction the funnel's mouth looks, and the block it feeds is the one on the OTHER side
        // (AbstractFunnelBlock#tryInsert goes through an InvManipulationBehaviour aimed at
        // pos.relative(getFunnelFacing(state).getOpposite())). So: mouth up, chest below.
        helper.setBlock(funnelPos, AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP));
        PalletEntity onFunnel = spawn(helper, funnelPos, COBBLESTONE, STACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, STACK);
        ItemCensus.assertEquals(helper, conserved, "before the funnel is offered a pallet");
        helper.assertTrue(ItemHelper.fromItemEntity(onFunnel).isEmpty(),
                "Create sees no items on a pallet at all, which is what gates every one of its logistics blocks");

        helper.startSequence()
                // No manual calls: the pallet sits in the funnel's own block, so the real entityInside runs every tick
                // from Entity#move, which is the only honest way to say "the funnel had every chance".
                .thenExecuteAfter(FUNNEL_TICKS, () -> {
                    helper.assertTrue(onFunnel.isAlive(), "a funnel leaves a pallet alone");
                    helper.assertValueEqual(onFunnel.carriedCount(), STACK, "with its whole load");
                    helper.assertValueEqual(inventoryCount(helper, FUNNEL_TARGET, COBBLESTONE), 0L,
                            "and takes nothing out of it");
                    // The control: an ordinary item entity in the funnel's own block, beside the pallet. Without it,
                    // "the funnel did nothing" could just as well mean "the funnel was never working".
                    // Zero motion on purpose: ItemEntity's short constructor gives a drop a random sideways shove,
                    // which can carry it out of the funnel's block and makes the test a coin toss.
                    Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(funnelPos)).add(0, CONTROL_DROP_HEIGHT, 0);
                    ItemEntity loose = new ItemEntity(level, spot.x, spot.y, spot.z, COBBLESTONE.toStack(1),
                            0.0, 0.0, 0.0);
                    helper.assertTrue(level.addFreshEntity(loose), "the control item entity is in the world");
                    ItemCensus.change(conserved, COBBLESTONE, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(inventoryCount(helper, FUNNEL_TARGET, COBBLESTONE), 1L,
                        "the same funnel does take an ordinary item entity, so it was live and aimed at the chest"))
                .thenExecute(() -> {
                    helper.assertValueEqual(onFunnel.carriedCount(), STACK, "and still nothing off the pallet");
                    ItemCensus.assertEquals(helper, conserved,
                            "after the funnel refused a pallet and took an item entity");
                    // The hopper, which does reach a pallet, one item every eight ticks. The funnel's pallet stays
                    // where it is: one census covers the whole test, so nothing may be destroyed to set this up.
                    helper.setBlock(HOPPER, AisleFixture.hopperState(Direction.DOWN));
                    ItemCensus.change(conserved, COBBLESTONE, HOPPER_LOAD);
                    spawn(helper, HOPPER.above(), COBBLESTONE, HOPPER_LOAD);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(inventoryCount(helper, HOPPER, COBBLESTONE), (long) HOPPER_LOAD,
                            "a vanilla hopper drains a pallet item by item");
                    helper.assertTrue(level.getEntitiesOfClass(PalletEntity.class,
                            AABB.ofSize(Vec3.atCenterOf(helper.absolutePos(HOPPER)), FIND_BOX, FIND_BOX, FIND_BOX))
                            .isEmpty(),
                            "and a pallet with nothing left on it goes away instead of standing there empty");
                })
                .thenExecute(() -> {
                    helper.assertTrue(onFunnel.isAlive() && onFunnel.carriedCount() == STACK,
                            "the funnel never took anything off its pallet, not once in all those ticks");
                    ItemCensus.assertEquals(helper, conserved, "after the hopper drained a pallet");
                })
                .thenSucceed();
    }

    // --- a player's hands --------------------------------------------------------------------------------------------

    /**
     * The gesture a pallet is emptied with, which is the bay's own: an <b>empty hand</b> takes one item, Shift takes
     * one stack, and that is the only way goods come off a pallet by hand.
     * <ul>
     * <li>a hand that holds something is passed straight on — <b>nothing is ever put into a pallet</b>, because the
     * only way goods get back into storage is a bay;</li>
     * <li>a {@code FakePlayer} is refused, for the bay's reason: in-aisle automation has the item capability for this,
     * and a fake player that could right-click would empty a pallet item by item;</li>
     * <li>the offhand does nothing, so the gesture is the main hand's alone;</li>
     * <li>one call hands out at most <b>one stack</b>, whatever is asked for, and a simulate changes nothing.</li>
     * </ul>
     * The "only what fits" rule itself cannot be reached through a hand at all, and it is worth saying why rather than
     * writing a test that pretends: a player clicking with an <b>empty hand</b> has, by definition, an empty inventory
     * slot, and one empty slot takes a whole stack — so a take always fits. The simulate-first discipline is there for
     * the paths that <i>can</i> fail (a modded inventory, a future caller) and so that the pallet and the bay share one
     * shape; what this test can prove is that nothing ever lands on the floor.
     * Every item the pallet started with is accounted for after each step, in the pallet, the player's slots or the
     * world — the item census cannot see a player's inventory, and a hand gesture is exactly a transfer between the
     * two.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void palletHandGestures(GameTestHelper helper) {
        PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, STACK * 2);
        Player player = NamingClick.player(helper);
        player.getInventory().clearContent();
        // The hand is the LAST hotbar slot, because what the pallet hands over goes into the FIRST free slot: with the
        // hand on slot 0, emptying it for the next click would delete what the click before put there — a test
        // artefact that reads exactly like an item being lost.
        player.getInventory().selected = HAND_SLOT;

        helper.assertValueEqual(click(pallet, player, COBBLESTONE.toStack(1), false), InteractionResult.PASS,
                "a hand holding something is passed straight on");
        helper.assertValueEqual(pallet.carriedCount(), STACK * 2, "nothing is ever put into a pallet");
        player.getInventory().clearContent();

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertValueEqual(pallet.interact(player, InteractionHand.OFF_HAND), InteractionResult.PASS,
                "the offhand does nothing");
        helper.assertValueEqual(pallet.carriedCount(), STACK * 2, "and takes nothing");

        FakePlayer fake = FakePlayerFactory.getMinecraft(helper.getLevel());
        fake.getInventory().clearContent();
        helper.assertValueEqual(click(pallet, fake, ItemStack.EMPTY, true), InteractionResult.PASS,
                "a fake player may not empty a pallet by hand");
        helper.assertValueEqual(pallet.carriedCount(), STACK * 2, "and took nothing");
        fake.getInventory().clearContent();

        helper.assertValueEqual(click(pallet, player, ItemStack.EMPTY, false), InteractionResult.CONSUME,
                "a plain right-click with an empty hand takes one item");
        helper.assertValueEqual(pallet.carriedCount(), STACK * 2 - 1, "exactly one");
        assertNothingLost(helper, pallet, player, STACK * 2, "after a plain take");

        helper.assertValueEqual(click(pallet, player, ItemStack.EMPTY, true), InteractionResult.CONSUME,
                "Shift takes a whole stack");
        helper.assertValueEqual(pallet.carriedCount(), STACK - 1, "exactly one stack");
        assertNothingLost(helper, pallet, player, STACK * 2, "after a Shift take");

        // Nothing was ever spilled on the floor on the way: a take is simulated against the player's own inventory
        // first and only the accepted amount is extracted, so a hand gesture never puts items anywhere else.
        helper.assertTrue(helper.getLevel()
                .getEntities(EntityType.ITEM, helper.getBounds().inflate(DROP_RADIUS), entity -> true).isEmpty(),
                "a hand gesture spills nothing");

        // One call hands out at most one stack, whatever is asked for — the item handler contract, and the bound that
        // keeps a 65 536-item pallet from ever building an oversized stack for somebody else to choke on. On a full
        // brass bay's worth, so the bound is what decides the answer and not the load. A simulate changes nothing.
        PalletEntity bulk = spawn(helper, PIT, COBBLESTONE, BRASS_LOAD);
        int stack = COBBLESTONE.getMaxStackSize();
        helper.assertValueEqual(bulk.extract(BRASS_LOAD, true).getCount(), stack,
                "a simulated take of everything answers one stack");
        helper.assertValueEqual(bulk.carriedCount(), BRASS_LOAD, "and changed nothing");
        helper.assertValueEqual(bulk.extract(BRASS_LOAD, false).getCount(), stack,
                "and a real one hands out exactly one stack");
        helper.assertValueEqual(bulk.carriedCount(), BRASS_LOAD - stack, "taking exactly that much off the pallet");

        bulk.discard();
        pallet.discard();
        helper.succeed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** A pallet put at a test-relative position with a load, exactly as a broken bay leaves one. */
    private static PalletEntity spawn(GameTestHelper helper, BlockPos pos, ItemKey key, int load) {
        BlockPos absolute = helper.absolutePos(pos);
        helper.assertTrue(PalletEntity.spawn(helper.getLevel(), absolute, key, load),
                "the level accepted a pallet at " + pos);
        List<PalletEntity> pallets = helper.getLevel().getEntitiesOfClass(PalletEntity.class,
                AABB.ofSize(Vec3.atCenterOf(absolute), FIND_BOX, FIND_BOX, FIND_BOX),
                entity -> entity.carriedCount() == load);
        if (pallets.size() != 1) {
            helper.fail("exactly one pallet carrying " + load + " must stand at " + pos + ", found " + pallets.size(),
                    pos);
            throw new IllegalStateException("unreachable");
        }
        return pallets.get(0);
    }

    /**
     * {@code live}'s own save data with the load replaced by {@code key} and {@code count}, however absurd they are.
     * Everything else — the id, the position, the UUID — is what a real save holds, so only the load is under test. A
     * {@code null} key leaves the pallet's two keys out altogether, which is what a pallet written by a future version
     * that stopped writing them would look like.
     */
    private static CompoundTag savedWith(GameTestHelper helper, PalletEntity live, ItemKey key, int count) {
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(live.save(tag), "a living pallet is saved");
        tag.remove(PalletEntity.STORED_TAG);
        tag.remove(PalletEntity.COUNT_TAG);
        if (key != null) {
            key.saveTo(tag, PalletEntity.STORED_TAG, helper.getLevel().registryAccess());
            tag.putInt(PalletEntity.COUNT_TAG, count);
        }
        return tag;
    }

    /**
     * A fresh pallet built from {@code tag}, the way a chunk load builds one: {@code EntityType.create} reads the id,
     * creates the entity and runs {@code Entity#load} on it. It is deliberately never added to the level, so a UUID it
     * shares with the living pallet cannot matter.
     */
    private static PalletEntity loadFrom(GameTestHelper helper, CompoundTag tag, String what) {
        Optional<Entity> loaded = EntityType.create(tag, helper.getLevel());
        if (loaded.isEmpty() || !(loaded.get() instanceof PalletEntity pallet)) {
            helper.fail("a saved pallet must load again as one: " + what, PALLET);
            throw new IllegalStateException("unreachable");
        }
        return pallet;
    }

    /** Fails unless a pallet loaded from {@code tag} removes itself, which is what unreadable data must do. */
    private static void assertRemoved(GameTestHelper helper, CompoundTag tag, String what) {
        PalletEntity loaded = loadFrom(helper, tag, what);
        helper.assertTrue(loaded.isRemoved(), "a pallet with nothing to carry removes itself: " + what);
        helper.assertValueEqual(loaded.carriedCount(), 0, "and carries nothing: " + what);
    }

    /**
     * A right-click on the pallet, answering what the <b>entity</b> did with it. This is the call
     * {@code Player#interactOn} makes, which is what the server reaches from {@code ServerboundInteractPacket} — and
     * that packet is also where the crouch state comes from, which is why Shift can mean anything here at all.
     */
    private static InteractionResult click(PalletEntity pallet, Player player, ItemStack held, boolean sneaking) {
        player.setShiftKeyDown(sneaking);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return pallet.interact(player, InteractionHand.MAIN_HAND);
    }

    /**
     * Fails unless the pallet, the player's own slots and the floor together still hold every cobblestone the test
     * started with. The item census cannot see a player's inventory, and a hand gesture is exactly a transfer between
     * the two, so this is the conservation assertion for it.
     */
    private static void assertNothingLost(GameTestHelper helper, PalletEntity pallet, Player player, int expected,
                                          String when) {
        long total = pallet.carriedKey().filter(COBBLESTONE::equals).map(key -> (long) pallet.carriedCount())
                .orElse(0L);
        for (ItemStack stack : player.getInventory().items)
            if (COBBLESTONE.matches(stack))
                total += stack.getCount();
        for (ItemEntity entity : helper.getLevel().getEntities(EntityType.ITEM,
                helper.getBounds().inflate(DROP_RADIUS), entity -> COBBLESTONE.matches(entity.getItem())))
            total += entity.getItem().getCount();
        helper.assertValueEqual(total, (long) expected, "every item is still accounted for " + when);
    }

    /** Items of {@code key} in the inventory at a test-relative position, read as a machine reads it. */
    private static long inventoryCount(GameTestHelper helper, BlockPos pos, ItemKey key) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(pos), null);
        if (handler == null)
            return 0L;
        long total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (key.matches(stack))
                total += stack.getCount();
        }
        return total;
    }
}
