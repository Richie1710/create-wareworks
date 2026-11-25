package dev.wareworks.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Draws the fluid standing in a fluid bay ({@code docs/warehouse-system.md} §3.9, M30 step 5, issue #21), and the
 * filter item of an empty one (issue #61).
 *
 * <p>
 * <b>An empty bay shows its filter</b> (issue #61, the fluid half of issue #49): the filter <b>item</b> (the water
 * bucket a player clicked in, or a Create filter item) at the mouth of the bay, drawn by the {@link BayItemRenderer} a
 * rack bay uses and chosen by the same rule ({@link BayDisplay}). Not a ghost of the fluid: the drawn fluid is the
 * contents readout, so anything fluid-shaped would read as stock. Any stored fluid wins over the filter, and an empty
 * bay without a filter draws nothing. The item is a <b>detail</b>, so unlike the fluid it is cut off at Create's
 * {@code filterItemRenderDistance}, measured as {@link WarehouseInterfaceRenderer} measures it.
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
    /** Draws the filter item of an empty bay, and reads the light of the open front; shared with the rack bay. */
    private final BayItemRenderer bayItem;

    public FluidBayRenderer(BlockEntityRendererProvider.Context context) {
        bayItem = new BayItemRenderer(context.getItemRenderer());
    }

    @Override
    protected void renderSafe(FluidBayBlockEntity bay, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        Level level = bay.getLevel();
        if (level == null)
            return;
        FluidKey stored = bay.storedFluidOrNull();
        int millibuckets = bay.millibuckets();
        if (stored == null || millibuckets <= 0) {
            renderFilter(bay, level, ms, buffer, light);
            return;
        }
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
        // The box below is written in the north frame the models are authored in.
        BayItemRenderer.turnOnto(ms, facing);
        ms.translate(-0.5F, 0.0F, -0.5F);
        NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(display,
                pixels(FluidBayLayout.FLUID_MIN_X_PX), pixels(bottom), pixels(FluidBayLayout.FLUID_MIN_Z_PX),
                pixels(FluidBayLayout.FLUID_MAX_X_PX), pixels(bottom + film), pixels(FluidBayLayout.FLUID_MAX_Z_PX),
                buffer, ms, bayItem.openFrontLight(level, bay.getBlockPos(), facing, light), RENDER_BOTTOM,
                INVERT_GASSES);
        ms.popPose();
        // Recorded for the visual harness, which checks what a bay really drew.
        bay.recordDrawnFluid(stored, Util.getNanos());
    }

    /**
     * The filter item an empty bay shows (issue #61), cut off at Create's {@code filterItemRenderDistance}. Most bays
     * have no filter and return before anything is looked up or allocated.
     */
    private void renderFilter(FluidBayBlockEntity bay, Level level, PoseStack ms, MultiBufferSource buffer,
                              int light) {
        ItemStack filter = displayedItem(bay);
        if (filter.isEmpty() || beyondFilterItemDistance(bay, level))
            return;
        bayItem.draw(filter, level, bay.getBlockPos(), bay.facing(), ms, buffer, light);
        // Recorded for the visual harness, which checks that this draw happened, not only what displayedItem decides.
        bay.recordDrawn(filter, Util.getNanos());
    }

    /**
     * The item this renderer draws for {@code bay}, by {@link BayDisplay#choose}: the store filter item of an empty bay,
     * else {@link ItemStack#EMPTY}. A bay with fluid in it draws that fluid and <b>no</b> item. Allocates nothing: the
     * filter is the behaviour's own stack ({@code FluidBayBlockEntity#storeFilterView}), which is only read here.
     * Public so the visual harness can check what a bay shows ({@code dev.wareworks.dev.BlocksVisualScenario}).
     */
    public ItemStack displayedItem(FluidBayBlockEntity bay) {
        ItemStack filter = bay.storeFilterView();
        boolean holdsFluid = bay.storedFluidOrNull() != null && bay.millibuckets() > 0;
        return BayDisplay.choose(holdsFluid, !filter.isEmpty()) == BayDisplay.FILTER ? filter : ItemStack.EMPTY;
    }

    /**
     * Whether the camera is farther from {@code bay} than the distance Create cuts a filter item off at; the check
     * {@link WarehouseInterfaceRenderer} makes, which leaves a Ponder scene's virtual block entities alone.
     */
    private static boolean beyondFilterItemDistance(FluidBayBlockEntity bay, Level level) {
        if (bay.isVirtual())
            return false;
        Entity camera = Minecraft.getInstance().cameraEntity;
        float max = AllConfigs.client().filterItemRenderDistance.getF();
        BlockPos pos = bay.getBlockPos();
        // distanceToSqr with three doubles, so nothing is allocated per bay per frame.
        return camera != null && level == camera.level()
                && camera.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > max * max;
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

    private static float pixels(float modelPixels) {
        return modelPixels / CraneModelLayout.PIXELS_PER_BLOCK;
    }
}
