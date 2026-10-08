package dev.wareworks.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Draws the fluid standing in a fluid bay ({@code docs/warehouse-system.md} §3.9, M30 step 5, issue #21).
 *
 * <p>
 * <b>The level is the readout</b>, which is the issue's own phrase and the reason this renderer exists at all. A rack
 * bay answers "how full" with block state geometry baked into the chunk mesh and uses its renderer only for
 * <i>which</i> item it holds (ADR-047, ADR-049). That split does not transfer, for a reason in the fluid API rather
 * than in taste: a fluid's look is its own still sprite with its own tint, the set of fluids is open, and no finite
 * set of baked variants can reference a sprite it has never heard of. So a fluid bay carries <b>no</b> fill geometry
 * in its blockstate and the whole answer is drawn here, in one call, over the fluid's own texture.
 *
 * <p>
 * <b>Where it is drawn</b> is {@link FluidBayLayout}: a box inside the vessel the bay's model stands, from the
 * vessel's floor up to the surface {@link FluidBayLayout#surfacePx} computes. Read that class for the geometry and the
 * two rules a picture cannot carry — the rim is the crane's arm port sill, and a bay holding anything at all shows a
 * minimum film. {@code CraneModelLayoutTest} holds both the model and these corners to it.
 *
 * <p>
 * <b>The view distance is the vanilla one</b>, 64 blocks, and that is the decision rather than an omission. Every
 * renderer before it in this mod cuts {@code getViewDistance()} to Create's {@code filterItemRenderDistance} — ten
 * blocks by default — because registering a block entity renderer puts <i>every</i> block of that type into its chunk
 * section's per-frame render list whether anything is drawn or not ({@code SectionCompiler#handleBlockEntity}). That
 * cut is right where the renderer draws a <b>detail</b> of a block: beyond it a rack bay still shows the shape of its
 * load and a warehouse interface still shows its block. Here the renderer draws the <b>whole readout</b>, so the cut
 * would make a full bay and an empty bay pixel-identical from eleven blocks away, and a level that cannot be read
 * from across the room is not the thing this block exists for (issue #21).
 * <p>
 * The cost is paid where ADR-053 argues it can be: a bay's count is bounded by how many <b>fluids</b> a player stores,
 * three or four, not by how many item types. A bay outside the frustum costs one test and one squared distance per
 * frame. If a pack ever builds a tank farm, the mitigation is a cap of this method's own — the design reserved it as a
 * mitigation and not as a default. {@code dev.BlocksVisualScenario} measures what is really drawn from the client's own
 * per-frame list rather than claiming it.
 *
 * <p>
 * <b>No smoothing.</b> Create's tanks glide because a {@code LerpedFloat} is advanced from the block entity's tick; a
 * fluid bay's {@code getTicker} returns {@code null} on both sides, as a rack bay's does, and a basement of a thousand
 * bays costing nothing per tick is worth more than the glide. A bay's step is 0.4 %–1.6 % of its capacity against a
 * Create tank's 12.5 %, so there is far less to smooth in the first place.
 *
 * <p>
 * <b>Gases hang from the rim</b> rather than lying on the floor. {@code renderFluidBox}'s {@code invertGasses} flag
 * turns the box about its own centre, which leaves the box where it is, so the box itself is moved up — the same two
 * lines Create's own {@code FluidTankRenderer} carries, for the same reason.
 */
public class FluidBayRenderer extends SafeBlockEntityRenderer<FluidBayBlockEntity> {
    /**
     * How many display stacks are kept before the cache is thrown away. A warehouse holds a handful of fluids and
     * only bays within the view distance are ever drawn, so a real one never reaches this; it exists because
     * the cache is keyed by the full {@link FluidKey}, and a creative session could otherwise walk it up without
     * bound.
     */
    private static final int MAX_DISPLAY_STACKS = 64;
    private static final float QUARTER_TURN_DEGREES = 90.0F;
    /** {@code renderFluidBox} draws no bottom face: there is a vessel floor under the fluid and nothing to see. */
    private static final boolean RENDER_BOTTOM = false;
    /** Gases are drawn upside down, which is what makes a gas read as a gas; see the class comment. */
    private static final boolean INVERT_GASSES = true;

    /**
     * One display stack per stored key. A bay syncs a {@link FluidKey}, which carries no stack of its own, and
     * {@code FluidKey#toStack()} allocates — which a renderer must not do per bay per frame. The stacks are handed
     * only to Create's fluid renderer, which reads the fluid and its components and never writes, so caching a
     * <b>mutable</b> {@code FluidStack} is safe here; nothing else may be given one of these. Read and written on the
     * render thread only, exactly as {@link RackBayRenderer} keeps its own.
     */
    private final Map<FluidKey, FluidStack> displayStacks = new HashMap<>();
    /**
     * The aisle block the fluid's light is read from, reused rather than allocated — the same render-thread-only rule
     * as {@link #displayStacks}, because {@code BlockPos#relative} would hand back a fresh one per bay per frame.
     */
    private final BlockPos.MutableBlockPos lightCursor = new BlockPos.MutableBlockPos();

    public FluidBayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FluidBayBlockEntity bay, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        FluidKey stored = bay.storedFluidOrNull();
        if (stored == null)
            return;
        int millibuckets = bay.millibuckets();
        if (millibuckets <= 0)
            return;
        Level level = bay.getLevel();
        if (level == null)
            return;
        FluidStack display = displayStack(stored);
        if (display.isEmpty())
            return;
        float film = FluidBayLayout.filmPx(millibuckets, bay.capacity());
        // A gas fills its vessel from the top down, so the film hangs from the rim instead of standing on the floor.
        float bottom = display.getFluid().getFluidType().isLighterThanAir()
                ? FluidBayLayout.VESSEL_RIM_PX - film
                : FluidBayLayout.FLUID_FLOOR_PX;
        Direction facing = bay.facing();

        ms.pushPose();
        // The rotation the multipart blockstate applies to the bay's own models, so the box below is written in the
        // north frame the models are authored in: quarter turns clockwise about the block's vertical centre axis.
        ms.translate(0.5F, 0.0F, 0.5F);
        ms.mulPose(Axis.YP.rotationDegrees(-QUARTER_TURN_DEGREES * quarterTurns(facing)));
        ms.translate(-0.5F, 0.0F, -0.5F);
        NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(display,
                pixels(FluidBayLayout.FLUID_MIN_X_PX), pixels(bottom), pixels(FluidBayLayout.FLUID_MIN_Z_PX),
                pixels(FluidBayLayout.FLUID_MAX_X_PX), pixels(bottom + film), pixels(FluidBayLayout.FLUID_MAX_Z_PX),
                buffer, ms, fluidLight(bay, facing, light), RENDER_BOTTOM, INVERT_GASSES);
        ms.popPose();
    }

    /** The cached one-millibucket stack of {@code key}, built once and reused; see {@link #displayStacks}. */
    private FluidStack displayStack(FluidKey key) {
        FluidStack display = displayStacks.get(key);
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
     * ({@code WareworksBlockStateGen#fluidBayBlockProvider}). A bay whose state somehow carries a vertical facing is
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
     * The brighter of the bay's own light (passed in by the dispatcher) and the aisle block's, the rule
     * {@link RackBayRenderer} and the crane's arm already use: a bay is not a solid block but still attenuates light
     * like any non-solid one, so deep in a rack wall its own position is dim while its open front faces a lit aisle.
     * {@code renderFluidBox} then raises the block light further to the fluid's own luminosity, which is what makes
     * lava light itself.
     */
    private int fluidLight(FluidBayBlockEntity bay, Direction facing, int ownLight) {
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
