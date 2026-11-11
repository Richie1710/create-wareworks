package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.handtruck.HandTruckLoad;
import dev.wareworks.content.handtruck.HandTruckPallet;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksEntityTypes;
import dev.wareworks.registry.WareworksItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scene of the hand truck (issue #37, ADR-070): load a chest with everything in it, walk slowly, set it down;
 * then lift a pallet whole (ADR-084).
 * <p>
 * A {@code PonderLevel} has no player, so the one who pushes the truck is an <b>armour stand</b> holding it, moved a
 * little every tick; the chest on the truck is drawn by the truck's own item renderer, exactly as a player sees it in
 * hand. The loaded truck the scene shows carries an empty chest: the renderer only reads the carried block, and a
 * storyboard has no level to save a block entity in. The pallet is built as {@code RackBayScenes} builds one
 * ({@link PalletEntity#showClientLoad}), and the pallet truck's component inside the entity callback, at run time.
 * <p>
 * Storyboards stay level-free (they also run during lang datagen), so every stack is built inside the storyboard and
 * the armour stand inside its factory. The order of the {@code .text(...)} calls defines the {@code text_n} keys.
 */
public final class HandTruckScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    private static final int CONTROL_LEAD = 7;
    /** Ticks the slow walk across the plate takes: two blocks at a deliberately slow pace. */
    private static final int WALK_TICKS = 60;
    private static final int PLATE = 5;
    private static final int FLOOR_Y = 1;
    /** Where the pusher stands, north of the chests, facing them (yaw 0 faces south). */
    private static final double PUSHER_Z = 1.5;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_EAST = -90.0F;
    /** The right arm held forward and a little down, as if pushing the truck's handle. */
    private static final Rotations PUSHING_ARM = new Rotations(-50.0F, 0.0F, 0.0F);
    /** The pallet the truck lifts at the end: a full wooden bay of cobblestone. */
    private static final int PALLET_LOAD = 4_096;

    private HandTruckScenes() {
    }

    /** Carrying a chest with the hand truck (issue #37). */
    public static void carrying(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("hand_truck", "Carrying Containers with the Hand Truck");
        scene.configureBasePlate(0, 0, PLATE);
        scene.scaleSceneView(1.2f);

        BlockPos from = util.grid().at(1, FLOOR_Y, 3);
        BlockPos to = util.grid().at(3, FLOOR_Y, 3);
        Selection fromSelection = util.select().position(from);
        Selection toSelection = util.select().position(to);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        ItemStack empty = new ItemStack(WareworksItems.HAND_TRUCK.get());
        ItemStack loaded = empty.copy();
        loaded.set(WareworksDataComponents.HAND_TRUCK_LOAD.get(), new HandTruckLoad(chest,
                BlockEntityType.getKey(BlockEntityType.CHEST), new CompoundTag()));
        Vec3 fromFront = util.vector().blockSurface(from, Direction.NORTH);
        Vec3 toGround = util.vector().topOf(to.below());

        scene.world().setBlock(from, chest, false);
        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(fromSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- loading ---------------------------------------------------------------------------------------------------
        scene.overlay().showControls(fromFront, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(empty);
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Right-Click a Chest or a Barrel with an empty Hand Truck")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(fromFront);
        scene.idle(TEXT_IDLE);

        scene.world().hideSection(fromSelection, Direction.UP);
        scene.idle(FADE_IDLE);
        ElementLink<EntityElement> pusher = scene.world().createEntity(level -> {
            ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
            stand.setShowArms(true);
            stand.setNoBasePlate(true);
            stand.setRightArmPose(PUSHING_ARM);
            stand.moveTo(from.getX() + 0.5, from.getY(), PUSHER_Z, FACING_SOUTH, 0.0F);
            stand.setYBodyRot(FACING_SOUTH);
            stand.setYHeadRot(FACING_SOUTH);
            return stand;
        });
        // The truck goes into the hand only once the stand is in the level: a PonderLevel strips every item component
        // but a few from an armour stand's equipment when the stand is added (catnip's SchematicLevel), which would take
        // the carried chest off the truck.
        holding(scene, pusher, loaded);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("The container goes onto the truck with everything inside it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().of(from.getX() + 0.5, from.getY() + 1.2, PUSHER_Z));
        scene.idle(TEXT_IDLE);

        // --- the slow walk -------------------------------------------------------------------------------------------
        turn(scene, pusher, FACING_EAST);
        scene.overlay().showText(WALK_TICKS + 20)
                .text("While it is loaded you walk slowly, cannot sprint and cannot glide with an Elytra")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().of(to.getX() + 0.5, to.getY() + 1.2, PUSHER_Z));
        double startX = from.getX() + 0.5;
        double distance = to.getX() - from.getX();
        for (int tick = 1; tick <= WALK_TICKS; tick++) {
            double x = startX + distance * tick / WALK_TICKS;
            scene.world().modifyEntity(pusher, entity -> {
                entity.setOldPosAndRot();
                entity.setPos(x, entity.getY(), entity.getZ());
            });
            scene.idle(1);
        }
        scene.idle(20);
        turn(scene, pusher, FACING_SOUTH);

        // --- unloading -----------------------------------------------------------------------------------------------
        scene.overlay().showControls(toGround, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(loaded);
        scene.idle(CONTROL_LEAD);
        scene.world().setBlock(to, chest, false);
        scene.world().showSection(toSelection, Direction.DOWN);
        holding(scene, pusher, empty);
        scene.overlay().showText(TEXT_TICKS)
                .text("Right-Click the ground to set it down again, facing you, with exactly what it held")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(to, Direction.NORTH));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("A loaded truck fits into no other container and cannot be dropped; warehouse blocks cannot be "
                        + "carried")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(to));
        scene.idle(TEXT_IDLE);

        // --- a pallet (ADR-084) --------------------------------------------------------------------------------------
        BlockPos palletPos = util.grid().at(2, FLOOR_Y, 2);
        Vec3 onThePallet = util.vector().of(palletPos.getX() + 0.5, palletPos.getY() + 0.2, palletPos.getZ() + 0.5);
        ElementLink<EntityElement> pallet = scene.world().createEntity(level -> pallet(level, palletPos));
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(onThePallet, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(empty);
        scene.idle(CONTROL_LEAD);
        scene.world().modifyEntity(pallet, Entity::discard);
        scene.world().modifyEntity(pusher, entity -> {
            if (entity instanceof ArmorStand stand)
                stand.setItemSlot(EquipmentSlot.MAINHAND, palletTruck(empty));
        });
        scene.overlay().showText(TEXT_TICKS)
                .text("An empty truck lifts a whole Pallet too, with everything on it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(onThePallet);
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Right-Click the ground to set it down, or an empty Rack Bay that holds it all to put the whole "
                        + "load in")
                .placeNearTarget()
                .pointAt(util.vector().of(to.getX() + 0.5, to.getY() + 1.2, PUSHER_Z));
        scene.idle(TEXT_IDLE);
    }

    /**
     * A pallet of {@value #PALLET_LOAD} cobblestone standing on the bottom of {@code pos}, built as
     * {@code RackBayScenes} builds one: {@link PalletEntity#showClientLoad} stands in for a server's sync.
     */
    private static Entity pallet(Level level, BlockPos pos) {
        PalletEntity pallet = WareworksEntityTypes.PALLET.create(level);
        if (pallet == null)
            throw new IllegalStateException("the pallet entity type refused to create an entity");
        pallet.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        pallet.showClientLoad(ItemKey.of(Items.COBBLESTONE), PALLET_LOAD);
        return pallet;
    }

    /** {@code empty} carrying that pallet; built at run time, in the entity callback, never in the storyboard. */
    private static ItemStack palletTruck(ItemStack empty) {
        ItemStack truck = empty.copy();
        truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(),
                new HandTruckPallet(ItemKey.of(Items.COBBLESTONE), PALLET_LOAD));
        return truck;
    }

    /** Puts a copy of {@code truck} into the armour stand's main hand. */
    private static void holding(CreateSceneBuilder scene, ElementLink<EntityElement> pusher, ItemStack truck) {
        scene.world().modifyEntity(pusher, entity -> {
            if (entity instanceof ArmorStand stand)
                stand.setItemSlot(EquipmentSlot.MAINHAND, truck.copy());
        });
    }

    /** Turns the armour stand to {@code yaw} at once. */
    private static void turn(CreateSceneBuilder scene, ElementLink<EntityElement> pusher, float yaw) {
        scene.world().modifyEntity(pusher, entity -> {
            entity.setYRot(yaw);
            entity.yRotO = yaw;
            if (entity instanceof ArmorStand stand) {
                stand.setYBodyRot(yaw);
                stand.yBodyRotO = yaw;
                stand.setYHeadRot(yaw);
                stand.yHeadRotO = yaw;
            }
        });
        scene.idle(5);
    }
}
