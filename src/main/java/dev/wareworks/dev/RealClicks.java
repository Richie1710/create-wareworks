package dev.wareworks.dev;

import java.lang.reflect.Field;
import java.util.Objects;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Real clicks in the world for the scenarios with far sites (dev tooling, ADR-014), shared by {@code robobee},
 * {@code selector} and {@code selector-remote}: a camera that looks at the intended face, the crosshair checked on it,
 * the server's confirmation of the teleport the camera move is, then one press of the use key through
 * {@code KeyMapping} (never the mouse grab or the window focus, owner's rule). Every step's description starts with the
 * scenario's prefix, so a log line names the run it belongs to.
 */
final class RealClicks {
    static final int AIM_TIMEOUT_TICKS = 60;
    static final int SYNC_TIMEOUT_TICKS = 200;
    static final int SCREEN_TIMEOUT_TICKS = 300;
    static final int SETTLE_TICKS = 4;

    /** The position a server waits for the client to confirm after a teleport, {@code null} once it has. */
    private static final Field AWAITING_TELEPORT = field(ServerGamePacketListenerImpl.class,
            "awaitingPositionFromClient");

    private RealClicks() {
    }

    /** A click on the centre of {@code face} of {@code block} (relative to the origin), seen from {@code eyeOffset}. */
    static ArmVisualScenario.Click click(String label, BlockPos block, Direction face, Vec3 eyeOffset) {
        return new ArmVisualScenario.Click(label, block, face, new Vec3(0.5, 0.5, 0.5), eyeOffset);
    }

    /** A right-click on {@code click}: camera, crosshair checked on the intended face, one press of the use key. */
    static void rightClick(VisualScript script, String prefix, ArmVisualScenario.Click click) {
        fly(script, prefix);
        script.camera(click.label(), context -> ArmVisualScenario.clickView(context, click));
        ArmVisualScenario.untilOrFail(script, prefix + ": the crosshair is on the " + click.face() + " face of "
                        + click.label(), context -> ArmVisualScenario.aimedAt(context, click), AIM_TIMEOUT_TICKS,
                context -> "the crosshair hits " + ArmVisualScenario.describeHit(context) + " instead of the "
                        + click.face() + " face of " + click.block());
        script.serverUntil(prefix + ": the server has the player's answer to every teleport",
                (server, context) -> teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        RequesterVisualScenario.useKey(script, click.label());
        script.waitTicks(SETTLE_TICKS);
    }

    /**
     * A sneaking right-click the way a player makes one: standing on the ground at {@code feet} (relative to the
     * origin; a flying player would sink while sneaking), looking at {@code local} inside {@code block}, the sneak key
     * held until the client and the server crouch, the crosshair checked on {@code face} of {@code block}, one press of
     * the use key, the key released and the player flying again.
     */
    static void sneakClick(VisualScript script, String prefix, String label, BlockPos block, Direction face,
            Vec3 local, Vec3 feet) {
        script.server(prefix + ": the player stops flying to stand beside " + label, (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }).camera(label, context -> new CameraView(label, feet.add(0.0, Player.DEFAULT_EYE_HEIGHT, 0.0),
                Vec3.atLowerCornerOf(block).add(local)));
        ArmVisualScenario.untilOrFail(script, prefix + ": the player stands on the ground beside " + label,
                context -> context.minecraft().player.onGround() && !context.minecraft().player.getAbilities().flying,
                SYNC_TIMEOUT_TICKS, context -> "the player is at " + context.minecraft().player.position());
        RequesterVisualScenario.sneak(script, true);
        ArmVisualScenario.untilOrFail(script, prefix + ": the crosshair is on the " + face + " face of " + label,
                context -> context.minecraft().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(context.origin().offset(block)) && hit.getDirection() == face,
                AIM_TIMEOUT_TICKS, context -> "the crosshair hits " + ArmVisualScenario.describeHit(context)
                        + " instead of the " + face + " face of " + block);
        script.serverUntil(prefix + ": the server has the player's answer to every teleport",
                (server, context) -> teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        RequesterVisualScenario.useKey(script, label + " while sneaking");
        script.waitTicks(SETTLE_TICKS);
        RequesterVisualScenario.sneak(script, false);
        // A creative player on the ground stops flying at once (LocalPlayer#aiStep): lift it first.
        script.server(prefix + ": lift the player off the ground and let it fly", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.teleportTo(server.overworld(), player.getX(), player.getY() + 1.5, player.getZ(), player.getYRot(),
                    player.getXRot());
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        script.serverUntil(prefix + ": the server has the player's answer to the lift",
                (server, context) -> teleportConfirmed(context.serverPlayer(server)), SYNC_TIMEOUT_TICKS);
        fly(script, prefix);
    }

    static boolean teleportConfirmed(ServerPlayer player) {
        try {
            return AWAITING_TELEPORT.get(player.connection) == null;
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read " + AWAITING_TELEPORT + ": " + error);
        }
    }

    /** The player flying where it is: every camera move is a teleport, and a flying player stays where it is put. */
    static void fly(VisualScript script, String prefix) {
        script.server(prefix + ": the player flies", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }).until(prefix + ": the client flies too", context -> context.minecraft().player != null
                && context.minecraft().player.getAbilities().flying, SYNC_TIMEOUT_TICKS);
    }

    /**
     * Places a sign from the held stack on top of {@code sign}'s floor block with a real click, types {@code text} into
     * the editor and clicks Done; waits until the server has the text.
     */
    static void writeSign(VisualScript script, String prefix, String what, BlockPos sign, String text, Vec3 eyeOffset) {
        rightClick(script, prefix, click("the ground under " + what, sign.below(), Direction.UP, eyeOffset));
        ArmVisualScenario.untilOrFail(script, prefix + ": the sign editor of " + what + " is open",
                context -> context.minecraft().screen instanceof AbstractSignEditScreen, SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(SETTLE_TICKS).client(prefix + ": type '" + text + "' on " + what, context -> {
            Screen screen = Objects.requireNonNull(context.minecraft().screen, "screen");
            for (char c : text.toCharArray())
                screen.charTyped(c, 0);
        }).waitTicks(SETTLE_TICKS).client(prefix + ": click Done", context -> {
            Screen screen = Objects.requireNonNull(context.minecraft().screen, "screen");
            Button done = null;
            for (GuiEventListener child : screen.children())
                if (child instanceof Button button && button.getMessage().equals(CommonComponents.GUI_DONE))
                    done = button;
            if (done == null)
                throw new VisualTestException("the sign editor has no Done button: " + screen.children());
            ScreenInput.click(context.minecraft(), ScreenInput.centre(done));
        }).until(prefix + ": the sign editor is closed", context -> context.minecraft().screen == null,
                SCREEN_TIMEOUT_TICKS).serverUntil(prefix + ": the server has the text of " + what,
                (server, context) -> server.overworld().getBlockEntity(context.origin().offset(sign))
                        instanceof SignBlockEntity entity
                        && text.equals(entity.getFrontText().getMessage(0, false).getString()), SYNC_TIMEOUT_TICKS);
    }

    static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
        }
    }

    static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read " + field + ": " + error);
        }
    }
}
