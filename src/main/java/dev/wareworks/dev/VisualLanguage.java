package dev.wareworks.dev;

import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;

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
        }, timeoutTicks).waitTicks(settleTicks);
    }
}
