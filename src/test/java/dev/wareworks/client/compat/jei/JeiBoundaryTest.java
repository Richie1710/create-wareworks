package dev.wareworks.client.compat.jei;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The guard of ADR-063's code boundary (M32, issue #24, slice 4): JEI is optional, so no class the mod loads without
 * JEI may name a JEI type, and the mod compiles against JEI's API only.
 * <p>
 * JEI's API is {@code compileOnly}: a JEI import anywhere compiles, and the GameTest server never loads a client
 * class, so neither the build nor the GameTests would notice one in, say, the terminal's screen — only a client
 * without JEI that happens to load that class, at runtime. This test reads every main source file, line by line:
 * <ol>
 * <li><b>JEI's types</b> ({@link #onlyTheCompatPackageNamesAJeiType}): {@code mezz.jei}, as an import, a static import
 * or a fully qualified name, only in {@code client/compat/jei}. Any use of a JEI type from elsewhere needs one of
 * those, so a simple name cannot slip past.</li>
 * <li><b>The compat package</b> ({@link #onlyTheJeiScenarioReachesTheCompatPackage}): its package name, which every
 * import of one of its classes and every fully qualified use contains, only in the package itself and in the dev
 * harness's {@code jei} scenario, which reaches it after checking that JEI is loaded. JEI finds the plugin by its
 * annotation; nothing else in the mod may load the package.</li>
 * <li><b>The API only</b> ({@link #onlyJeisApiIsImported}): an import of {@code mezz.jei} below anything but
 * {@code api}, anywhere. The compile classpath holds only the API jars, so the compiler already refuses one; this says
 * why.</li>
 * </ol>
 * Two more tests keep the guard from going quiet: the allowed places still hold the uses, so a moved package cannot
 * empty a scan, and the patterns catch every form they are meant to and pass over a mention in a comment.
 * <p>
 * Runs without Minecraft and without JEI: it only reads text.
 */
class JeiBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    /** The one package that may name a JEI type, relative to {@link #MAIN_SOURCES}. */
    private static final Path COMPAT = Path.of("dev/wareworks/client/compat/jei");
    /** The dev harness's scenario that drives the real JEI, the one class outside {@link #COMPAT} that reaches it. */
    private static final Path JEI_SCENARIO = Path.of("dev/wareworks/dev/JeiVisualScenario.java");
    /** The classes of {@link #COMPAT}, which must all still import JEI's API. */
    private static final List<String> COMPAT_CLASSES = List.of("WareworksJeiPlugin.java",
            "TerminalCraftTransferHandler.java", "JeiRuntimeAccess.java");

    /** A JEI package or type: every import, static import or fully qualified use of one contains it. */
    private static final Pattern NAMES_JEI = Pattern.compile("\\bmezz\\.jei\\b");
    /** The compat package: every import of one of its classes, wildcard or not, and every qualified use contains it. */
    private static final Pattern NAMES_COMPAT = Pattern.compile("\\bdev\\.wareworks\\.client\\.compat\\.jei\\b");
    /** An import of JEI below anything but its API ({@code mezz.jei.common}, {@code library}, {@code gui}, ...). */
    private static final Pattern INTERNAL_IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?mezz\\.jei\\.(?!api\\.)");

    @Test
    void onlyTheCompatPackageNamesAJeiType() throws IOException {
        List<String> offenders = offenders(NAMES_JEI, List.of());
        assertTrue(offenders.isEmpty(), "Only client/compat/jei may name a JEI type, so the mod loads without JEI "
                + "(ADR-063); found " + offenders);
    }

    @Test
    void onlyTheJeiScenarioReachesTheCompatPackage() throws IOException {
        List<String> offenders = offenders(NAMES_COMPAT, List.of(JEI_SCENARIO));
        assertTrue(offenders.isEmpty(), "Only JEI itself, through the plugin's annotation, and the dev harness's jei "
                + "scenario may reach client.compat.jei (ADR-063); found " + offenders);
    }

    @Test
    void onlyJeisApiIsImported() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int line = 0; line < lines.size(); line++) {
                if (INTERNAL_IMPORT.matcher(lines.get(line)).find())
                    offenders.add(MAIN_SOURCES.relativize(file) + ":" + (line + 1));
            }
        }
        assertTrue(offenders.isEmpty(), "Only mezz.jei.api may be imported, never JEI's internals (ADR-063); found "
                + offenders);
    }

    /** The allowed places still hold the uses, so a renamed or moved package cannot empty the guard. */
    @Test
    void theAllowedPlacesStillHoldTheUses() throws IOException {
        for (String name : COMPAT_CLASSES) {
            String source = source(COMPAT.resolve(name));
            assertTrue(source.contains("import mezz.jei.api."), name + " no longer imports JEI's API: update this "
                    + "guard together with ADR-063");
        }
        assertTrue(NAMES_COMPAT.matcher(source(JEI_SCENARIO)).find(), JEI_SCENARIO + " no longer reaches the compat "
                + "package: update this guard together with ADR-063");
        List<Path> files = mainSources();
        assertTrue(files.size() > 100, "expected the main sources under " + MAIN_SOURCES.toAbsolutePath() + ", found "
                + files.size() + " files");
    }

    /** The patterns catch every form of a use and pass over what only mentions a name in prose. */
    @Test
    void thePatternsCatchWhatTheyShould() {
        for (String line : List.of("import mezz.jei.api.IModPlugin;",
                "import static mezz.jei.api.constants.VanillaTypes.*;",
                "mezz.jei.api.runtime.IJeiRuntime runtime = null;", "import mezz.jei.common.Internal;"))
            assertTrue(NAMES_JEI.matcher(line).find(), "the JEI scan misses: " + line);
        for (String line : List.of("import dev.wareworks.client.compat.jei.JeiRuntimeAccess;",
                "import dev.wareworks.client.compat.jei.*;",
                "dev.wareworks.client.compat.jei.JeiRuntimeAccess.available()"))
            assertTrue(NAMES_COMPAT.matcher(line).find(), "the compat scan misses: " + line);
        for (String line : List.of("import mezz.jei.common.Internal;", "  import mezz.jei.library.load.PluginCaller;",
                "import static mezz.jei.gui.recipes.RecipesGui.*;"))
            assertTrue(INTERNAL_IMPORT.matcher(line).find(), "the internal-import scan misses: " + line);

        for (String line : List.of(" * plugin in {@code client.compat.jei} — turns its recipe into nine lists",
                "import dev.wareworks.client.gui.TerminalRecipeLay;", "\"wareworks.jei\""))
            assertFalse(NAMES_COMPAT.matcher(line).find(), "the compat scan takes this for a use: " + line);
        for (String line : List.of(" * {@code mezz.jei.library}, {@code mezz.jei.gui}).",
                "import mezz.jei.api.JeiPlugin;", "import static mezz.jei.api.constants.RecipeTypes.CRAFTING;"))
            assertFalse(INTERNAL_IMPORT.matcher(line).find(), "the internal-import scan takes this for one: " + line);
        assertFalse(NAMES_JEI.matcher("private static final String JEI_MOD_ID = \"jei\";").find(),
                "the JEI scan takes JEI's mod id for a type");
    }

    /** Every line outside {@link #COMPAT} and {@code allowed} that {@code pattern} finds, as file:line. */
    private static List<String> offenders(Pattern pattern, List<Path> allowed) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            Path relative = MAIN_SOURCES.relativize(file);
            if (relative.startsWith(COMPAT) || allowed.contains(relative))
                continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int line = 0; line < lines.size(); line++) {
                if (pattern.matcher(lines.get(line)).find())
                    offenders.add(relative + ":" + (line + 1));
            }
        }
        return offenders;
    }

    private static String source(Path relative) throws IOException {
        Path file = MAIN_SOURCES.resolve(relative);
        assertTrue(Files.isRegularFile(file), file + " is missing: update this guard together with ADR-063");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).sorted()
                    .toList();
        }
    }
}
