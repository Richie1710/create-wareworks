package dev.wareworks.compat.computercraft;

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
 * The guard of ADR-074's code boundary (issue #9): CC: Tweaked is optional, so no class the mod loads without it may
 * name a CC: Tweaked type, and the mod compiles against CC's API only.
 * <p>
 * CC's API is {@code compileOnly}: an import of it anywhere compiles, and the GameTest server without
 * {@code -Pwareworks.cc} would only notice one in a class it happens to load. This test reads every main source file,
 * line by line:
 * <ol>
 * <li><b>CC's types</b> ({@code dan200}, as an import, a static import or a fully qualified name) appear only in
 * {@code compat/computercraft} and in the GameTest bodies {@code gametest/ComputerCraftPeripheralBodies}, which are
 * test code left out of the jar and reached only after the loaded check; and never in {@link ComputerCraftCompat}, the
 * one class the rest of the mod calls.</li>
 * <li><b>The compat package</b> is reached from outside only by {@code WareworksCapabilities} (through
 * {@code ComputerCraftCompat}), by those GameTest bodies and by their holder.</li>
 * <li><b>The API only</b>: no import of {@code dan200.computercraft} below anything but {@code api}.</li>
 * <li><b>No client code</b>: the compat package names no {@code client} package, so a dedicated server is safe.</li>
 * </ol>
 * Runs without Minecraft and without CC: Tweaked: it only reads text.
 */
class ComputerCraftBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path COMPAT = Path.of("dev/wareworks/compat/computercraft");
    /** The GameTest bodies of the real peripheral: test code, left out of the jar, reached only with CC loaded. */
    private static final Path GAMETEST_BODIES = Path.of("dev/wareworks/gametest/ComputerCraftPeripheralBodies.java");
    /** Their holder, which names no CC type and asks {@link ComputerCraftCompat#isLoaded()} first. */
    private static final Path GAMETEST_HOLDER = Path.of("dev/wareworks/gametest/ComputerCraftPeripheralGameTests.java");
    private static final Path ENTRY = COMPAT.resolve("ComputerCraftCompat.java");
    private static final Path CAPABILITIES = Path.of("dev/wareworks/registry/WareworksCapabilities.java");
    private static final List<String> CC_CLASSES = List.of("WareworksPeripherals.java", "TerminalPeripheral.java");

    private static final Pattern NAMES_CC = Pattern.compile("\\bdan200\\.");
    private static final Pattern NAMES_COMPAT = Pattern.compile("\\bdev\\.wareworks\\.compat\\.computercraft\\b");
    private static final Pattern INTERNAL_IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?dan200\\.computercraft\\.(?!api\\.)");
    private static final Pattern NAMES_CLIENT = Pattern.compile("\\bdev\\.wareworks\\.client\\b|net\\.minecraft\\.client\\b");

    @Test
    void onlyTheCompatPackageNamesACcType() throws IOException {
        List<String> offenders = offenders(NAMES_CC, path -> path.startsWith(COMPAT) && !path.equals(ENTRY)
                || path.equals(GAMETEST_BODIES));
        assertTrue(offenders.isEmpty(), "Only compat/computercraft (not ComputerCraftCompat) may name a CC: Tweaked "
                + "type, so the mod loads without it (ADR-074); found " + offenders);
    }

    @Test
    void onlyTheCapabilityListAndTheGameTestBodiesReachTheCompatPackage() throws IOException {
        List<String> offenders = offenders(NAMES_COMPAT, path -> path.startsWith(COMPAT) || path.equals(CAPABILITIES)
                || path.equals(GAMETEST_BODIES) || path.equals(GAMETEST_HOLDER));
        assertTrue(offenders.isEmpty(), "Only WareworksCapabilities and the GameTest bodies may reach "
                + "compat.computercraft (ADR-074); found " + offenders);
    }

    @Test
    void onlyCcsApiIsImported() throws IOException {
        List<String> offenders = offenders(INTERNAL_IMPORT, path -> false);
        assertTrue(offenders.isEmpty(), "Only dan200.computercraft.api may be imported (ADR-074); found " + offenders);
    }

    @Test
    void theCompatPackageNamesNoClientCode() throws IOException {
        List<String> offenders = offenders(NAMES_CLIENT, path -> !path.startsWith(COMPAT));
        assertTrue(offenders.isEmpty(), "compat.computercraft runs on dedicated servers and must not name client code; "
                + "found " + offenders);
    }

    /** The allowed places still hold the uses, so a renamed or moved package cannot empty the guard. */
    @Test
    void theAllowedPlacesStillHoldTheUses() throws IOException {
        for (String name : CC_CLASSES)
            assertTrue(source(COMPAT.resolve(name)).contains("import dan200.computercraft.api."),
                    name + " no longer imports CC's API: update this guard together with ADR-074");
        assertTrue(source(GAMETEST_BODIES).contains("import dan200.computercraft.api."),
                "the peripheral GameTests no longer use CC's API: update this guard together with ADR-074");
        assertTrue(source(CAPABILITIES).contains("ComputerCraftCompat::registerCapabilities"),
                "WareworksCapabilities no longer registers the CC compat: update this guard together with ADR-074");
        assertTrue(mainSources().size() > 100, "expected the main sources under " + MAIN_SOURCES.toAbsolutePath());
    }

    @Test
    void thePatternsCatchWhatTheyShould() {
        for (String line : List.of("import dan200.computercraft.api.lua.LuaFunction;",
                "dan200.computercraft.api.peripheral.IPeripheral peripheral = null;",
                "import static dan200.computercraft.api.lua.LuaValues.*;"))
            assertTrue(NAMES_CC.matcher(line).find(), "the CC scan misses: " + line);
        assertTrue(INTERNAL_IMPORT.matcher("import dan200.computercraft.shared.util.InventoryUtil;").find());
        assertFalse(INTERNAL_IMPORT.matcher("import dan200.computercraft.api.lua.IArguments;").find());
        assertTrue(NAMES_COMPAT.matcher("import dev.wareworks.compat.computercraft.ComputerCraftCompat;").find());
        assertFalse(NAMES_CC.matcher("public static final String MOD_ID = \"computercraft\";").find(),
                "the CC scan takes CC's mod id for a type");
        assertTrue(NAMES_CLIENT.matcher("import net.minecraft.client.Minecraft;").find());
    }

    private interface Allowed {
        boolean test(Path relative);
    }

    private static List<String> offenders(Pattern pattern, Allowed allowed) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            Path relative = MAIN_SOURCES.relativize(file);
            if (allowed.test(relative))
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
        assertTrue(Files.isRegularFile(file), file + " is missing: update this guard together with ADR-074");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).sorted()
                    .toList();
        }
    }
}
