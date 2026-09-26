package dev.wareworks.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox;

import dev.wareworks.util.WareworksLang;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Screenshots of a Create <b>goggle tooltip</b>, and the player a goggle shot needs.
 * <p>
 * A goggle tooltip is a GUI layer that Create only draws for a non-spectator who looks at a block within reach
 * ({@code GoggleOverlayRenderer}), so a scenario that shoots one runs with {@link VisualWorldProfile#playable} — a
 * creative, flying player with the vanilla reach — and sets that reach to 0 again for its world shots, because Create
 * draws the value box of whatever the crosshair targets even with the GUI hidden.
 * <p>
 * Before every goggle shot {@link #shot} waits until Create's overlay really has that block under the crosshair and is
 * fully faded in, and lets the scenario assert the tooltip's own lines on the client block entity that draws them: a
 * screenshot cannot tell a right number from a wrong one.
 * <p>
 * Shared by the stock rule scenarios ({@link StockRulesVisualScenario}, {@link RestockVisualScenario}) and by
 * {@link PrioritiesVisualScenario}; dev tooling only (ADR-014).
 */
final class GoggleShots {
    /** Frames Create's goggle overlay needs to fade in completely ({@code GoggleOverlayRenderer}: {@code fade = t/24}). */
    private static final int FULL_FADE_FRAMES = 24;
    /** What creative mode adds to the block reach on top of the attribute's base value ({@code ServerPlayer}). */
    private static final double CREATIVE_REACH_BONUS = 0.5;
    private static final int GOGGLE_TIMEOUT_TICKS = 300;
    private static final int SYNC_TIMEOUT_TICKS = 200;

    private GoggleShots() {
    }

    /**
     * A goggle shot: camera, GUI on, wait until Create's overlay really has that block under the crosshair and is fully
     * faded in, assert the lines it draws, shoot, GUI off again.
     *
     * @param prefix the scenario's log prefix, e.g. {@code "rules"}
     * @param target the block the camera looks at, from the scene origin
     * @param synced a client condition that holds once the numbers behind the tooltip have arrived
     * @param check  asserts the lines the overlay draws
     */
    static void shot(VisualScript script, String prefix, CameraView view, String label,
            Function<BlockPos, BlockPos> target, Predicate<VisualContext> synced, VisualScript.ClientAction check) {
        script.camera(view)
                // The pass switched Flywheel's backend with a command, whose answer would otherwise sit in the chat
                // line across the tooltip this shot is about.
                .client(prefix + ": clear the chat before " + label, GoggleShots::clearChat)
                .client(prefix + ": show the GUI for " + label,
                        context -> context.minecraft().options.hideGui = false)
                .until(prefix + ": wait until the goggle tooltip of " + label + " is fully drawn",
                        context -> drawn(context, target.apply(context.origin())), GOGGLE_TIMEOUT_TICKS)
                // The numbers travel in their own (throttled) block entity packet, which the server only sends once it
                // has seen the player look at the block; waiting for them keeps the assertion below about the values
                // and not about the moment they arrive.
                .until(prefix + ": wait until the numbers behind " + label + " reached the client", synced,
                        GOGGLE_TIMEOUT_TICKS)
                .client(prefix + ": check the goggle lines behind " + label, check)
                .shot(label)
                .client(prefix + ": hide the GUI again after " + label,
                        context -> context.minecraft().options.hideGui = true);
    }

    /**
     * Sets the player's block reach and waits until the client has it, so a shot never races the attribute packet.
     * <p>
     * What the client ends up with is the <b>base</b> value plus creative mode's own
     * {@code minecraft:creative_mode_block_range} modifier ({@value #CREATIVE_REACH_BONUS} in {@code ServerPlayer}), so
     * the condition is a range rather than an equality: before the packet the value is the other setting's, afterwards
     * it is this one's.
     */
    static void reach(VisualScript script, String prefix, double range) {
        script.server(prefix + ": set the player's block reach to " + range, (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            AttributeInstance instance = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (instance == null)
                throw new VisualTestException("the player has no block interaction range");
            instance.setBaseValue(range);
        }).until(prefix + ": wait until the client sees the reach " + range, context -> {
            LocalPlayer player = context.minecraft().player;
            if (player == null)
                return false;
            double seen = player.blockInteractionRange();
            return seen >= range - 1.0e-6 && seen <= range + CREATIVE_REACH_BONUS + 1.0e-6;
        }, SYNC_TIMEOUT_TICKS);
    }

    /** The reach a player has without creative mode's bonus, i.e. what a goggle shot needs. */
    static double vanillaReach() {
        return Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue();
    }

    /** The lines the overlay draws, read from the client block entity that draws them. */
    static List<String> lines(VisualContext context, BlockPos pos) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            throw new VisualTestException("the client is not in a world");
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof IHaveGoggleInformation goggles))
            throw new VisualTestException("the block entity at " + pos + " has no goggle information: " + be);
        List<Component> tooltip = new ArrayList<>();
        goggles.addToGoggleTooltip(tooltip, false);
        return tooltip.stream().map(Component::getString).toList();
    }

    /** Fails unless one of the tooltip's lines contains {@code text}. */
    static void requireLine(List<String> lines, String text) {
        if (lines.stream().noneMatch(line -> line.contains(text)))
            throw new VisualTestException("the goggle tooltip does not say '" + text + "': " + lines);
    }

    /** Fails when any of the tooltip's lines contains {@code text}, e.g. a line a state must <b>not</b> show. */
    static void requireNoLine(List<String> lines, String text) {
        if (lines.stream().anyMatch(line -> line.contains(text)))
            throw new VisualTestException("the goggle tooltip still says '" + text + "': " + lines);
    }

    /** A Create-style "label: number" goggle line, exactly as the block entity builds it. */
    static String count(String key, long value) {
        return WareworksLang.countLine(key, value).component().getString();
    }

    /**
     * Whether Create's goggle overlay is drawing a tooltip for {@code pos} right now.
     * <p>
     * {@code GoggleOverlayRenderer} records the block it last drew for and counts the frames it has been hovered; the
     * counter is reset the moment the tooltip comes out empty, so the pair is exactly "this block, with lines, at full
     * opacity". A value box the crosshair really hits makes the renderer return before any of that, which is why an
     * active one is a failure rather than a wait.
     */
    private static boolean drawn(VisualContext context, BlockPos pos) {
        if (activeValueBox())
            throw new VisualTestException("a value box under the crosshair suppresses Create's goggle overlay; aim "
                    + "the camera away from the value box slot of " + pos);
        BlockPos hovered = GoggleOverlayRenderer.lastHovered;
        return hovered != null && hovered.equals(pos) && GoggleOverlayRenderer.hoverTicks >= FULL_FADE_FRAMES;
    }

    private static void clearChat(VisualContext context) {
        context.minecraft().gui.getChat().clearMessages(true);
    }

    private static boolean activeValueBox() {
        for (Outliner.OutlineEntry entry : new ArrayList<>(Outliner.getInstance().getOutlines().values())) {
            if (entry.isAlive() && entry.getOutline() instanceof ValueBox box && !box.isPassive)
                return true;
        }
        return false;
    }

    /** One log line of what the overlay is doing, for a scenario's {@code status()}. */
    static String describeHover(VisualContext context) {
        BlockPos hovered = GoggleOverlayRenderer.lastHovered;
        return " hovered=" + (hovered == null ? "none" : hovered) + " hoverTicks=" + GoggleOverlayRenderer.hoverTicks
                + " gui=" + !context.minecraft().options.hideGui;
    }
}
