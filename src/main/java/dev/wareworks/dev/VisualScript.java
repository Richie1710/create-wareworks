package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builder of the step list of a visual test run. Scenarios add their steps here ({@link VisualScenario}).
 * <p>
 * Server steps run on the integrated server thread through {@code server.submit(...)}; client steps and conditions run on
 * the client thread. Shot labels get the current pass prefix ({@link VisualPass#labelPrefix()}), and the file name is
 * {@code visual-<scenario>-<nn>-<label>.png} in {@code <gameDir>/screenshots}.
 */
public final class VisualScript {
    /** Ticks a server task may take. */
    private static final int SERVER_TASK_TIMEOUT_TICKS = 200;
    /** Ticks until the client sees a changed tick freeze state. */
    private static final int FREEZE_SYNC_TIMEOUT_TICKS = 100;
    /** Minimum ticks after a camera move before a shot (chunk sections get scheduled on the next frames). */
    private static final int CAMERA_MIN_TICKS = 10;
    /** Consecutive ticks with the camera in place and no pending chunk section compilation. */
    private static final int CAMERA_SETTLED_TICKS = 5;
    /** Ticks after which a camera shot is taken even though sections are still compiling. */
    private static final int CAMERA_MAX_TICKS = 200;
    private static final double CAMERA_ARRIVAL_DISTANCE_SQR = 0.01;
    /** Ticks until the screenshot file is written by the IO pool. */
    private static final int SHOT_WRITE_TIMEOUT_TICKS = 200;
    /** Ticks between showing the GUI and a {@link #shotWithGui} (one frame would do; two leave room for a slow frame). */
    private static final int GUI_SETTLE_TICKS = 2;
    private static final String SHOT_FILE_FORMAT = "visual-%s-%02d-%s.png";

    /** A server-thread action of a step. */
    @FunctionalInterface
    public interface ServerAction {
        void run(MinecraftServer server, VisualContext context) throws Exception;
    }

    /** A server-thread condition, polled once per client tick. */
    @FunctionalInterface
    public interface ServerCondition {
        boolean test(MinecraftServer server, VisualContext context) throws Exception;
    }

    /** A client-thread action of a step. */
    @FunctionalInterface
    public interface ClientAction {
        void run(VisualContext context) throws Exception;
    }

    private final List<VisualStep> steps = new ArrayList<>();
    private String labelPrefix = "";

    VisualScript() {
    }

    List<VisualStep> steps() {
        return steps;
    }

    /** Starts a pass: shot labels get its prefix, and its Flywheel backend command is run. */
    void beginPass(VisualPass pass) {
        labelPrefix = pass.labelPrefix();
        clientCommand(pass.backendCommand());
        client("log the Flywheel backend of pass " + pass,
                context -> LOGGER.info(PREFIX + "pass {}: Flywheel backend {}", pass, VisualContext.flywheelBackend()));
    }

    /** Runs {@code action} once on the client thread. */
    public VisualScript client(String description, ClientAction action) {
        Objects.requireNonNull(action, "action");
        return add(new VisualStep() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public void start(VisualContext context) {
                try {
                    action.run(context);
                } catch (RuntimeException error) {
                    throw error;
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }

            @Override
            public boolean tick(VisualContext context) {
                return true;
            }

            @Override
            public int timeoutTicks() {
                return 1;
            }
        });
    }

    /** Runs {@code action} once on the integrated server thread and waits for it. */
    public VisualScript server(String description, ServerAction action) {
        Objects.requireNonNull(action, "action");
        return add(new VisualStep() {
            @Nullable
            private CompletableFuture<Void> task;

            @Override
            public String describe() {
                return description;
            }

            @Override
            public void start(VisualContext context) {
                MinecraftServer server = context.server();
                task = server.submit(() -> {
                    try {
                        action.run(server, context);
                    } catch (RuntimeException error) {
                        throw error;
                    } catch (Exception error) {
                        throw new CompletionException(error);
                    }
                });
            }

            @Override
            public boolean tick(VisualContext context) {
                CompletableFuture<Void> running = Objects.requireNonNull(task, "task");
                if (!running.isDone())
                    return false;
                running.join();
                return true;
            }

            @Override
            public int timeoutTicks() {
                return SERVER_TASK_TIMEOUT_TICKS;
            }
        });
    }

    /** Polls {@code condition} on the integrated server thread until it holds. */
    public VisualScript serverUntil(String description, ServerCondition condition, int timeoutTicks) {
        Objects.requireNonNull(condition, "condition");
        return add(new VisualStep() {
            @Nullable
            private CompletableFuture<Boolean> poll;

            @Override
            public String describe() {
                return description;
            }

            @Override
            public boolean tick(VisualContext context) {
                CompletableFuture<Boolean> pending = poll;
                if (pending == null) {
                    MinecraftServer server = context.server();
                    poll = server.submit((Supplier<Boolean>) () -> {
                        try {
                            return condition.test(server, context);
                        } catch (RuntimeException error) {
                            throw error;
                        } catch (Exception error) {
                            throw new CompletionException(error);
                        }
                    });
                    return false;
                }
                if (!pending.isDone())
                    return false;
                poll = null;
                return pending.join();
            }

            @Override
            public int timeoutTicks() {
                return timeoutTicks;
            }
        });
    }

    /** Waits until {@code condition} holds on the client thread. */
    public VisualScript until(String description, Predicate<VisualContext> condition, int timeoutTicks) {
        Objects.requireNonNull(condition, "condition");
        return add(new VisualStep() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public boolean tick(VisualContext context) {
                return condition.test(context);
            }

            @Override
            public int timeoutTicks() {
                return timeoutTicks;
            }
        });
    }

    /** Waits {@code ticks} client ticks. */
    public VisualScript waitTicks(int ticks) {
        return add(new VisualStep() {
            private int waited;

            @Override
            public String describe() {
                return "wait " + ticks + " ticks";
            }

            @Override
            public boolean tick(VisualContext context) {
                return ++waited >= ticks;
            }

            @Override
            public int timeoutTicks() {
                return ticks + 1;
            }
        });
    }

    /** Runs a command as the local player; client commands (e.g. {@code flywheel ...}) run locally. */
    public VisualScript clientCommand(String command) {
        return client("command /" + command, context -> {
            LocalPlayer player = context.minecraft().player;
            if (player == null)
                throw new VisualTestException("no local player for the command /" + command);
            player.connection.sendCommand(command);
        });
    }

    /**
     * Freezes or unfreezes the game ticks (like {@code /tick freeze}) and waits until the client sees it. While frozen, block
     * entities do not tick on either side, so several camera views show the same moment. Players still move.
     */
    public VisualScript freeze(boolean frozen) {
        String name = frozen ? "freeze" : "unfreeze";
        server(name + " the game ticks", (server, context) -> server.tickRateManager().setFrozen(frozen));
        return until("client sees " + name, context -> {
            Minecraft minecraft = context.minecraft();
            return minecraft.level != null && minecraft.level.tickRateManager().isFrozen() == frozen;
        }, FREEZE_SYNC_TIMEOUT_TICKS);
    }

    /**
     * Moves the camera (the spectator player) to {@code view} relative to the scene origin and waits until it arrived and no
     * chunk section is left to compile.
     */
    public VisualScript camera(CameraView view) {
        Objects.requireNonNull(view, "view");
        return camera(view.label(), context -> view);
    }

    /**
     * Like {@link #camera(CameraView)}, but the view is computed when the step starts (client thread), for example from the
     * synced state of a block entity while the ticks are frozen, so the camera can follow a moving machine.
     */
    public VisualScript camera(String label, Function<VisualContext, CameraView> viewAtStart) {
        Objects.requireNonNull(viewAtStart, "viewAtStart");
        return add(new VisualStep() {
            @Nullable
            private CompletableFuture<CameraView.Placement> teleport;
            private CameraView view = CameraView.of(label, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            private int ticks;
            private int settled;

            @Override
            public String describe() {
                return "camera " + label;
            }

            @Override
            public void start(VisualContext context) {
                view = Objects.requireNonNull(viewAtStart.apply(context), "camera view " + label);
                MinecraftServer server = context.server();
                teleport = server.submit((Supplier<CameraView.Placement>) () -> {
                    ServerPlayer player = context.serverPlayer(server);
                    CameraView.Placement placement = view.placement(context.origin(), player.getEyeHeight());
                    player.teleportTo(server.overworld(), placement.x(), placement.y(), placement.z(), placement.yaw(),
                            placement.pitch());
                    return placement;
                });
            }

            @Override
            public boolean tick(VisualContext context) {
                CompletableFuture<CameraView.Placement> pending = Objects.requireNonNull(teleport, "teleport");
                if (!pending.isDone())
                    return false;
                CameraView.Placement placement = pending.join();
                ticks++;
                Minecraft minecraft = context.minecraft();
                LocalPlayer player = minecraft.player;
                boolean arrived = player != null
                        && player.position().distanceToSqr(placement.feet()) <= CAMERA_ARRIVAL_DISTANCE_SQR;
                boolean compiled = minecraft.levelRenderer.hasRenderedAllSections();
                settled = arrived && compiled ? settled + 1 : 0;
                if (ticks >= CAMERA_MIN_TICKS && settled >= CAMERA_SETTLED_TICKS)
                    return true;
                if (ticks < CAMERA_MAX_TICKS)
                    return false;
                if (!arrived)
                    throw new VisualTestException("the camera did not arrive at view " + view.label() + " " + placement.feet()
                            + " (the player is at " + (player == null ? "nowhere" : player.position()) + ")");
                LOGGER.warn(PREFIX + "chunk sections still compiling after {} ticks at view {}; taking the shot anyway", ticks,
                        view.label());
                return true;
            }

            @Override
            public int timeoutTicks() {
                return CAMERA_MAX_TICKS + SERVER_TASK_TIMEOUT_TICKS;
            }
        });
    }

    /** Takes a screenshot of the next rendered frame, labelled {@code label} (with the pass prefix). */
    public VisualScript shot(String label) {
        String fullLabel = labelPrefix.isEmpty() ? label : labelPrefix + "-" + label;
        return add(new VisualStep() {
            private final AtomicReference<Component> result = new AtomicReference<>();
            private int number;
            private String fileName = "";
            private boolean grabbed;

            @Override
            public String describe() {
                return "shot " + fullLabel;
            }

            @Override
            public void start(VisualContext context) {
                number = context.nextShotNumber();
                fileName = String.format(Locale.ROOT, SHOT_FILE_FORMAT, context.scenarioName(), number, fullLabel);
            }

            @Override
            public void afterFrame(VisualContext context) {
                if (grabbed)
                    return;
                grabbed = true;
                String status = context.status();
                Minecraft minecraft = context.minecraft();
                // Main render target after the frame: the world without GUI (hideGui), before the blit to the window.
                Screenshot.grab(minecraft.gameDirectory, fileName, minecraft.getMainRenderTarget(), result::set);
                context.index().add(new VisualShotIndex.Entry(number, fileName, fullLabel, status));
                LOGGER.info(PREFIX + "shot {} {} {}", String.format(Locale.ROOT, "%02d", number), fullLabel, status);
            }

            @Override
            public boolean tick(VisualContext context) {
                Component message = result.get();
                if (!grabbed || message == null)
                    return false;
                Path file = context.screenshotDirectory().resolve(fileName);
                if (!Files.isRegularFile(file))
                    throw new VisualTestException("screenshot " + fileName + " was not written: " + message.getString());
                return true;
            }

            @Override
            public int timeoutTicks() {
                return SHOT_WRITE_TIMEOUT_TICKS;
            }
        });
    }

    /**
     * Like {@link #shot}, but with the in-game GUI shown (hotbar, crosshair, action bar), i.e. what a player sees.
     * Every run hides the GUI ({@code VisualWorld#applyClientOptions}); this shows it for the frames of one shot and hides
     * it again afterwards, so the other shots of a scenario stay GUI-free.
     */
    public VisualScript shotWithGui(String label) {
        client("show the GUI for the shot " + label, context -> context.minecraft().options.hideGui = false);
        waitTicks(GUI_SETTLE_TICKS);
        shot(label);
        return client("hide the GUI again after the shot " + label,
                context -> context.minecraft().options.hideGui = true);
    }

    /** {@link #camera} to {@code view}, then {@link #shot} labelled {@code <moment>-<view label>}. */
    public VisualScript shotFrom(CameraView view, String moment) {
        return camera(view).shot(moment + "-" + view.label());
    }

    private VisualScript add(VisualStep step) {
        steps.add(step);
        return this;
    }
}
