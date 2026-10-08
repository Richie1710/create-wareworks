package dev.wareworks.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.RackBayBlockEntity;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Draws the item a rack bay stores, at the mouth of the bay in front of its load ({@code docs/warehouse-system.md}
 * §3.8, M29 step 13, issue #20, ADR-049).
 * <p>
 * <b>What this adds and what it does not.</b> The coarse fill level stays exactly where M28 put it — in the block state
 * property {@code RackBayBlock.FILL}, four steps of cartons on the pallet, baked into the chunk mesh and therefore free
 * at any distance (ADR-047). This renderer adds the one thing a silhouette cannot carry: <b>which</b> item those cartons
 * are. So the shape of the load says how full the bay is from across the warehouse, and the item says what is in it from
 * the aisle. Nothing here reads the count: the fill level already answers "how much", and a second answer drawn on top
 * of it would only be a worse one.
 * <p>
 * <b>The view distance is the whole point of the design and is not negotiable.</b> Registering a block entity renderer
 * puts <i>every</i> block of that type into its chunk section's per-frame render list, whether anything is drawn or not
 * ({@code SectionCompiler#handleBlockEntity} adds a block entity exactly when its type has a renderer), and vanilla's
 * default bound is 64 blocks ({@code BlockEntityRenderer#getViewDistance}). A warehouse places bays by the hundred, so
 * {@link #getViewDistance()} is cut to Create's {@code filterItemRenderDistance} — the same cut, for the same reason, as
 * {@code WarehouseInterfaceRenderer#getViewDistance()} (see there, and §3.1.1). A bay beyond it costs one frustum test
 * and one squared distance per frame and draws nothing at all; what is left is a handful of bays an aisle wide.
 * Measured rather than claimed, on the client's own per-frame list by {@code dev.wareworks.dev.BaysVisualScenario}: of
 * a hundred-bay wall, 40 are drawn from the aisle, 28 from 9.5 blocks and 0 from eleven, and the frustum and distance
 * tests for the whole wall cost about 1.5 µs per frame once the loop is warm. What the scenario <b>asserts</b> is the
 * shape of that, not the three figures: beyond the cap not one bay of the hundred may be drawn, from the aisle at
 * least one must be, and at the edge camera some but not all — so widening the cap fails a run instead of costing
 * frames quietly. The counts and the nanoseconds are recorded in the run's {@code index.txt} for a person to read.
 * <p>
 * <b>Where the item stands.</b> At the <b>front of the bay</b>, on the load beam, immediately in front of the pallet —
 * not on top of the load, which is where the design expected it. The reason is measured rather than chosen: the two top
 * fill steps spread their cartons over the pallet's whole footprint (x/z 3..13, i.e. the pallet exactly), so an item on
 * the pallet at those steps is <i>inside</i> the cartons; and above a full load there are 1.5 px left before
 * {@code CraneModelLayout}'s arm floor at y 9.5, where nothing in a bay may ever reach
 * ({@code CraneModelLayoutTest#aRackBaysLoadStaysUnderTheArmAndInsideItsWindow}). The 3 px between the load's front
 * plane and the aisle face is the only part of a bay that is free at every fill level, and it is the part a player in
 * the aisle looks straight at. {@code CraneModelLayoutTest#theItemDrawnInARackBayStandsClearOfTheArmAndTheLoad} pins
 * every one of those bounds against the model files, so a later edit to a load model or to the arm cannot silently
 * bury the item or push it into the crane's path.
 * <p>
 * <b>How it is drawn.</b> The way the crane draws what it carries ({@code docs/stacker-crane.md} §7):
 * {@link ItemDisplayContext#FIXED} at {@link CraneModelLayout#ITEM_SCALE}, so an item does not change size when the arm
 * sets it down. A block item stands, as it does on the arm. A flat item <b>stands up facing the aisle</b> instead of
 * lying down, which is the one place this differs from the arm: a bay is read face-on from the aisle, where a 0.34 px
 * sheet lying flat is an invisible line, and a flat item laid down needs 5.4 px of depth that the front of a bay does
 * not have. Lying flat is right on the arm, because the arm's stage is a shelf seen from above and from the side.
 * <p>
 * The item is lit by the brighter of the bay's own block and the aisle block in front of it, the rule the crane's arm
 * already uses for parts that reach into a rack: a bay in the middle of a wall attenuates light like any non-solid
 * block, and the goods face an open aisle.
 */
public class RackBayRenderer extends SafeBlockEntityRenderer<RackBayBlockEntity> {
    /** Never cull closer than this, whatever a client configured (the warehouse interface's own floor). */
    private static final int MIN_VIEW_DISTANCE = 1;

    /**
     * Where the drawn item's <b>back</b> face sits, in model pixels of a bay authored facing north: the pallet's front
     * edge, which is also the front plane of the widest load step ({@code models/block/rack_bay/load_base.json}). So
     * the item stands against the goods at a full bay and a pixel or two in front of them at a low fill level, and
     * never inside them.
     */
    public static final float ITEM_BACK_Z_PX = 13.0F;
    /**
     * Where the drawn item <b>stands</b>, in model pixels: the top of the load beam, the same plane the pallet itself
     * stands on. One pixel below the pallet's deck, because the pallet's deck is covered by the load at the two top
     * fill steps and the item would be the only thing in the bay with a gap under it.
     */
    public static final float ITEM_BASE_Y_PX = 3.0F;
    /** Across the bay: its centre, between the two uprights. */
    public static final float ITEM_X_PX = CraneModelLayout.BLOCK_CENTER_PX;

    /**
     * The aisle face of a bay, in model pixels: the plane the drawn item must stay behind, so it never pokes into the
     * aisle the crane travels in. The {@value #ITEM_BACK_Z_PX}..{@value #AISLE_FACE_Z_PX} strip is the whole budget.
     */
    public static final float AISLE_FACE_Z_PX = 16.0F;
    /**
     * How many display stacks are kept before the cache is thrown away. A bay holds one item type and only bays within
     * {@link #getViewDistance()} are ever drawn, so a real warehouse never reaches this; it exists because the cache is
     * keyed by the full {@link ItemKey} rather than by the item, and a creative session could otherwise walk the cache
     * up without bound.
     */
    private static final int MAX_DISPLAY_STACKS = 256;
    /** Seed for the item model lookup, so a bay does not flicker between model variants. */
    private static final int ITEM_SEED = 0;
    private static final float QUARTER_TURN_DEGREES = 90.0F;
    private static final float HALF_TURN_DEGREES = 180.0F;

    private final ItemRenderer itemRenderer;
    /**
     * One display stack per stored key; a bay syncs an {@link ItemKey}, which carries no stack of its own, and
     * {@code ItemKey#toStack()} allocates — which a renderer of the mod's most mass-placed block must not do per frame.
     * Keyed by the whole key rather than by the item, so a stored potion or a dyed shulker box is drawn as the variant
     * it is. Read and written on the render thread only, exactly as {@code StackerCraneRenderer} keeps its own.
     */
    private final Map<ItemKey, ItemStack> displayStacks = new HashMap<>();
    /**
     * The aisle block the item's light is read from, reused rather than allocated. The same render-thread-only rule as
     * {@link #displayStacks}: a renderer of the mod's most mass-placed block allocates nothing per bay per frame, and
     * {@code BlockPos#relative} would hand back a fresh {@code BlockPos} on every one.
     */
    private final BlockPos.MutableBlockPos lightCursor = new BlockPos.MutableBlockPos();

    public RackBayRenderer(BlockEntityRendererProvider.Context context) {
        itemRenderer = context.getItemRenderer();
    }

    /**
     * The distance Create cuts a filter item off at, rounded up to whole blocks — the bound a rack wall is culled by
     * before this renderer is entered. See the class comment: this is the budget, not a preference.
     */
    @Override
    public int getViewDistance() {
        return Math.max(MIN_VIEW_DISTANCE, Mth.ceil(AllConfigs.client().filterItemRenderDistance.getF()));
    }

    @Override
    protected void renderSafe(RackBayBlockEntity bay, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        ItemKey stored = bay.storedKeyOrNull();
        if (stored == null)
            return;
        Level level = bay.getLevel();
        if (level == null)
            return;
        ItemStack display = displayStack(stored);
        if (display.isEmpty())
            return;
        Direction facing = bay.facing();
        BakedModel model = itemRenderer.getModel(display, level, null, ITEM_SEED);
        boolean blockModel = model.isGui3d();
        // Half of what the item model covers in its fixed display transform: a block item is a cube of
        // BLOCK_ITEM_SIZE_PX, a flat item a FLAT_ITEM_THICKNESS_PX sheet one block across, and ItemRenderer centres
        // both on the pose origin — so the anchor planes below have to be met by moving the centre out by this much.
        float halfDepth = (blockModel ? CraneModelLayout.BLOCK_ITEM_SIZE_PX : CraneModelLayout.FLAT_ITEM_THICKNESS_PX)
                * CraneModelLayout.ITEM_SCALE / 2.0F;
        float halfHeight = (blockModel ? CraneModelLayout.BLOCK_ITEM_SIZE_PX : CraneModelLayout.PIXELS_PER_BLOCK)
                * CraneModelLayout.ITEM_SCALE / 2.0F;

        ms.pushPose();
        // The rotation the multipart blockstate applies to the bay's own models, so everything below is authored in
        // the north frame the models are authored in: quarter turns clockwise, about the block's vertical centre axis.
        ms.translate(0.5F, 0.0F, 0.5F);
        ms.mulPose(Axis.YP.rotationDegrees(-QUARTER_TURN_DEGREES * quarterTurns(facing)));
        ms.translate(pixels(ITEM_X_PX) - 0.5F, pixels(ITEM_BASE_Y_PX + halfHeight),
                pixels(ITEM_BACK_Z_PX + halfDepth) - 0.5F);
        // The half turn an item frame applies on top of the fixed display transform, which is what makes an item in a
        // frame face the viewer: verified against the vanilla transforms rather than guessed. `item/generated` carries
        // `fixed: rotation [0, 180, 0]` and `block/block` carries none, and ItemFrameRenderer adds
        // `Axis.YP.rotationDegrees(180 - yRot)`, i.e. 180° for a frame on a south face. So this one turn presents both
        // kinds exactly as a frame on the bay's aisle face would: a block item shows the face a player knows from the
        // GUI, and a flat item its own sprite rather than the mirrored back of its two quads.
        ms.mulPose(Axis.YP.rotationDegrees(HALF_TURN_DEGREES));
        ms.scale(CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE);
        // No pitch for a flat item: the fixed transform already stands a generated model up in the x/y plane, which is
        // the plane of the aisle face after the rotation above. The crane lays one down; a bay is read face-on.
        itemRenderer.render(display, ItemDisplayContext.FIXED, false, ms, buffer, itemLight(bay, facing, light),
                OverlayTexture.NO_OVERLAY, model);
        ms.popPose();
    }

    /** The cached one-item stack of {@code key}, built once and reused; see {@link #displayStacks}. */
    private ItemStack displayStack(ItemKey key) {
        ItemStack display = displayStacks.get(key);
        if (display != null)
            return display;
        if (displayStacks.size() >= MAX_DISPLAY_STACKS)
            displayStacks.clear();
        display = key.toStack();
        displayStacks.put(key, display);
        return display;
    }

    /**
     * Quarter turns clockwise from north, i.e. the {@code y} rotation the generated blockstate gives the bay's models
     * ({@code WareworksBlockStateGen#rackBayBlockProvider}). A bay whose state somehow carries a vertical facing is
     * drawn as if it faced north rather than not at all.
     */
    private static int quarterTurns(Direction facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /**
     * The brighter of the bay's own light (passed in by the dispatcher) and the aisle block's. A bay is not a solid
     * block, but it still attenuates light like any non-solid one, so deep in a rack wall its own position is dim while
     * the goods face an open, lit aisle — the same reason {@code StackerCraneRenderer}'s arm takes the brighter of its
     * carriage and the rack block it reaches into.
     */
    private int itemLight(RackBayBlockEntity bay, Direction facing, int ownLight) {
        Level level = bay.getLevel();
        if (level == null)
            return ownLight;
        lightCursor.setWithOffset(bay.getBlockPos(), facing.getOpposite());
        return SuperByteBuffer.maxLight(ownLight, LevelRenderer.getLightColor(level, lightCursor));
    }

    private static float pixels(float modelPixels) {
        return modelPixels / CraneModelLayout.PIXELS_PER_BLOCK;
    }
}
