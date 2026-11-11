package dev.wareworks.content.selector;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Recipe Selector (ADR-085, design §2): a thin plate set against a machine's recipe filter. {@link #FACING} points
 * into the machine, which is at {@code pos.relative(FACING)}.
 * <p>
 * Two tiers share this class: the local Recipe Selector (on an up-facing Mechanical Saw or a Basin, linked to a
 * production station and registered there on placement) and, with {@code ender}, the Ender Recipe Selector of a far
 * factory. Not kinetic, no item capability, no screen, no tick.
 * <p>
 * <b>The wrench never turns it</b> (design §2): a turned selector would point at another machine with an old
 * selection. A sneaking wrench picks it up as usual.
 * <p>
 * <b>Set-back</b> (design §6.2): every change of the machine's block entity calls {@link #onNeighborChange} here in the
 * same tick ({@code BlockEntity#setChanged} → {@code Level#updateNeighbourForOutputSignal}), and the block entity sets
 * a filter a hand changed back to the station's selection.
 */
public class RecipeSelectorBlock extends DirectionalBlock implements IBE<RecipeSelectorBlockEntity>, IWrenchable {
    private static final Map<Direction, VoxelShape> SHAPES = shapes();

    private final boolean ender;
    private final MapCodec<RecipeSelectorBlock> codec;

    public RecipeSelectorBlock(Properties properties, boolean ender) {
        super(properties);
        this.ender = ender;
        this.codec = simpleCodec(p -> new RecipeSelectorBlock(p, ender));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    /** The local tier's constructor (Registrate's factory). */
    public static RecipeSelectorBlock local(Properties properties) {
        return new RecipeSelectorBlock(properties, false);
    }

    /** The far tier's constructor (Registrate's factory): the Ender Recipe Selector. */
    public static RecipeSelectorBlock ender(Properties properties) {
        return new RecipeSelectorBlock(properties, true);
    }

    /** Whether this is the far tier, the Ender Recipe Selector. */
    public boolean isEnder() {
        return ender;
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING));
    }

    /** Faces the block it was placed against: the clicked block is the machine. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** A rotated schematic or structure turns the selector with its machine. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Never turned by a wrench (design §2); a sneaking wrench still picks it up. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /**
     * Placement by a player with a station-linked item (the item checked every rule before the block was placed):
     * links the block entity to that station and registers it there (design §3.3). The far tier registers nowhere.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide)
            return;
        SelectorLink link = stack.get(WareworksDataComponents.SELECTOR_LINK.get());
        if (ender) {
            // The far tier registers nowhere: it only takes the item's machine link (same dimension, checked in range
            // by the item) and waits for the first slip.
            withBlockEntityDo(level, pos, selector -> selector.placedAsEnder(link == null ? Optional.empty()
                    : link.machine().filter(machine -> machine.dimension().equals(level.dimension()))
                            .map(machine -> machine.pos())));
            return;
        }
        if (link == null || link.station().isEmpty() || !link.station().get().dimension().equals(level.dimension()))
            return;
        withBlockEntityDo(level, pos, selector -> selector.linkAndRegister(link.station().get().pos()));
    }

    /** The machine in front changed: set a hand-changed filter back (design §6.2). Server only, local tier only. */
    @Override
    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (ender || !(level instanceof Level real) || real.isClientSide)
            return;
        if (!neighbor.equals(pos.relative(state.getValue(FACING))))
            return;
        if (real.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector && !selector.isRemoved())
            selector.onMachineChanged();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    /** The far tier changes the item capability of the Packager it sits on: NeoForge must look again. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (ender && !level.isClientSide)
            level.invalidateCapabilities(pos.relative(state.getValue(FACING)));
    }

    /**
     * Ender tier: a sneak-click with an empty hand makes the selector forget the station it serves, so a slip from any
     * station is taken again (design §3.2).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!ender || !player.isSecondaryUseActive() || !player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector) {
            selector.clearLatch();
            player.displayClientMessage(WareworksLang.translateDirect(WareworksLang.SELECTOR_LATCH_CLEARED), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<RecipeSelectorBlockEntity> getBlockEntityClass() {
        return RecipeSelectorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RecipeSelectorBlockEntity> getBlockEntityType() {
        return WareworksBlockEntityTypes.RECIPE_SELECTOR.get();
    }

    /** A 12 x 12 px plate, 4 px thick, against the face of the block {@code FACING} points at. */
    private static Map<Direction, VoxelShape> shapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, Block.box(2, 2, 0, 14, 14, 4));
        shapes.put(Direction.SOUTH, Block.box(2, 2, 12, 14, 14, 16));
        shapes.put(Direction.WEST, Block.box(0, 2, 2, 4, 14, 14));
        shapes.put(Direction.EAST, Block.box(12, 2, 2, 16, 14, 14));
        shapes.put(Direction.DOWN, Block.box(2, 0, 2, 14, 4, 14));
        shapes.put(Direction.UP, Block.box(2, 12, 2, 14, 16, 14));
        return shapes;
    }
}
