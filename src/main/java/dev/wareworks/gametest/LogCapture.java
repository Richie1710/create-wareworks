package dev.wareworks.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * Records the log lines the game really writes, so that a GameTest can assert one of them (M30, issue #21).
 * <p>
 * It exists for exactly one claim in this mod, and it is a claim that has to be pinned rather than trusted:
 * <b>breaking a full fluid bay loses the fluid, and says so in the server log</b> with the fluid, the amount and the
 * position ({@code FluidBayBlockEntity#destroy}, {@code docs/warehouse-system.md} §3.9). That log line is the only
 * record a player or a server owner ever gets of the one deliberate loss this mod allows, so a later change that
 * quietly turned it into a silent loss — a refactor, a "noisy log" clean-up — must fail a test rather than ship.
 * Asserting it through a <b>real log4j appender</b> rather than through a hook in the production code is the point: a
 * seam built for a test could be satisfied without anything reaching a log file.
 * <p>
 * It attaches to the <b>root</b> logger's configuration, which is where every {@code LogUtils.getLogger()} of this mod
 * ends up (nothing in Minecraft's or NeoForge's {@code log4j2.xml} configures a {@code dev.wareworks} logger), and it
 * detaches again in {@link #close()}. Use it in a try-with-resources, or with {@link #close()} in the same step that
 * opened it: an appender left behind would keep recording for the rest of the run.
 * <p>
 * <b>Give the event a tick before reading it.</b> Log4j may hand an appender its events on another thread, so a test
 * asserts after a short wait rather than in the same statement as the action — which is what the GameTest sequences
 * using this class do.
 * <p>
 * Dev tooling, like everything in this package: it is reached only from GameTests.
 */
final class LogCapture implements AutoCloseable {
    /** Appender names are global in a log4j configuration, so each capture gets one nobody else can collide with. */
    private static final AtomicInteger NAMES = new AtomicInteger();

    private final List<String> messages = new ArrayList<>();
    private final Configuration configuration;
    private final AbstractAppender appender;
    private boolean closed;

    private LogCapture(Level level) {
        String name = "wareworks-gametest-log-capture-" + NAMES.incrementAndGet();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        configuration = context.getConfiguration();
        appender = new AbstractAppender(name, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                synchronized (messages) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        configuration.getRootLogger().addAppender(appender, level, null);
    }

    /** Starts recording every {@code WARN} and worse until {@link #close()}. */
    static LogCapture ofWarnings() {
        return new LogCapture(Level.WARN);
    }

    /** The formatted messages recorded so far, oldest first. */
    List<String> messages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /**
     * Detaches and answers everything that was recorded, so that a test asserts on a plain list and cannot leave an
     * appender behind by failing. Every caller of this class uses it rather than {@link #messages()} plus
     * {@link #close()} in two statements, for exactly that reason.
     */
    List<String> closeAndTake() {
        List<String> recorded = messages();
        close();
        return recorded;
    }

    /** The one message in {@code recorded} that contains all of {@code parts}, or {@code null} when there is none. */
    @org.jetbrains.annotations.Nullable
    static String firstContaining(List<String> recorded, String... parts) {
        for (String message : recorded) {
            boolean all = true;
            for (String part : parts)
                all &= message.contains(part);
            if (all)
                return message;
        }
        return null;
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        configuration.getRootLogger().removeAppender(appender.getName());
        appender.stop();
    }
}
