package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;

/**
 * Switches the running client's language the way the language screen itself does, for the scenarios that read or
 * photograph translated rows.
 * <p>
 * Dev tooling, like everything in this package: it is reached only through a {@link VisualScenario}, which
 * {@code WareworksClient} installs only when the {@code wareworks.visualTest} system property is set (ADR-014).
 * <p>
 * It is a small object rather than a static helper because the reload has to be <b>waited for</b>: the future
 * {@code Minecraft#reloadResourcePacks} returns is what the step after the switch polls, so one switch at a time needs
 * somewhere to keep it. A scenario holds one instance and switches as often as it likes.
 */
public final class VisualLanguage {
    /** The font manager behind {@code Minecraft#font}, its one-entry font set cache, and its uncached lookup. */
    private static final Field FONT_MANAGER = field(Minecraft.class, "fontManager");
    private static final Field FONT_SET_CACHE = field(FontManager.class, "lastFontSetCache");
    private static final Method FONT_SET_RAW = method(FontManager.class, "getFontSetRaw", ResourceLocation.class);

    /** How long a resource reload may take before the step gives up; a language switch rebuilds every atlas. */
    private final int timeoutTicks;
    /** Ticks let pass after the reload, so the next shot is of a settled screen. */
    private final int settleTicks;
    /** The reload a switch started, so the step after it waits for the same one. */
    @Nullable
    private CompletableFuture<Void> reload;

    public VisualLanguage(int timeoutTicks, int settleTicks) {
        this.timeoutTicks = timeoutTicks;
        this.settleTicks = settleTicks;
    }

    /**
     * Adds the two steps a language switch needs: what {@code LanguageSelectScreen#onDone} does — select the language
     * in the language manager and in the options, then reload the resource packs — and a wait until that reload has
     * really finished and the loading overlay is gone.
     * <p>
     * The options are deliberately <b>not saved</b>: this is a throw-away client, and a run that crashed between the
     * two steps must not leave a German {@code options.txt} behind for the next one. An open screen stays open and
     * simply reads the new language, because its lines are translatable components and not strings it kept.
     *
     * @param prefix the scenario's own step-label prefix, e.g. {@code "terminal: "}
     * @param code   a language code as the language manager knows it, e.g. {@code "de_de"}
     */
    public void switchTo(VisualScript script, String prefix, String code) {
        script.client(prefix + "switch the game language to " + code + " like the language screen does", context -> {
            Minecraft minecraft = context.minecraft();
            minecraft.getLanguageManager().setSelected(code);
            minecraft.options.languageCode = code;
            reload = minecraft.reloadResourcePacks();
        }).until(prefix + "wait until the resource reload for " + code + " finished", context -> {
            CompletableFuture<Void> started = reload;
            return started != null && started.isDone() && context.minecraft().getOverlay() == null
                    && code.equals(context.minecraft().getLanguageManager().getSelected());
        }, timeoutTicks).client(prefix + "check the font cache after the reload for " + code,
                VisualLanguage::dropStaleFontSet).waitTicks(settleTicks);
    }

    /**
     * Drops a font set the reload closed but the font manager's one-entry cache still hands out.
     * <p>
     * The reload closes every font set and glyph provider of before it and empties that cache
     * ({@code FontManager#apply}); a lookup that fills the cache again from the old map while the reload is applied
     * leaves a closed font set in it, and the first glyph drawn from it afterwards fails with "Image is not allocated"
     * (seen in the {@code requester} scenario, issue #45). A correct cache is the font set the manager holds under the
     * same name, so anything else is dropped here and named in the log.
     */
    private static void dropStaleFontSet(VisualContext context) {
        FontManager fonts = (FontManager) get(FONT_MANAGER, context.minecraft());
        FontSet cached = (FontSet) get(FONT_SET_CACHE, fonts);
        if (cached == null || cached == invoke(FONT_SET_RAW, fonts, cached.name()))
            return;
        LOGGER.warn(PREFIX + "the font cache still held the font set '{}' of before the language reload; dropped it",
                cached.name());
        try {
            FONT_SET_CACHE.set(fonts, null);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot clear the font set cache: " + error);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("cannot reach " + owner.getName() + "#" + name, error);
        }
    }

    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read " + field + ": " + error);
        }
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new VisualTestException("cannot call " + method + ": " + error);
        }
    }
}
