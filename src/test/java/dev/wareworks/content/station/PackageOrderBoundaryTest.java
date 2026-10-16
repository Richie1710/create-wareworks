package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The guard of ADR-065 D3 (M27, issue #18): <b>Wareworks never writes order data and never packs, triggers, addresses
 * or unpacks a box, and never talks to Create's logistics network.</b> A package door is a requesting warehouse port
 * with the player's own Create Packager at its back; the crane hands the goods into the port, the player's redstone fires
 * the Packager, and Create does the rest with the sign's address. A stored package retrieved to a door therefore leaves
 * as itself, its own order data intact, because Create forwards it unchanged apart from the address.
 * <p>
 * This test reads every main source file line by line and fails on a <b>code shape</b> that would break that promise:
 * <ul>
 * <li>building or stamping a package through Create's helpers ({@code PackageItem.setOrder}, {@code addAddress},
 * {@code clearAddress}, {@code containing}), as a call or a method reference;</li>
 * <li>naming the order components themselves ({@code PACKAGE_ORDER_DATA}, {@code PACKAGE_ORDER_CONTEXT});</li>
 * <li>calling a Packager's or a network block's actions ({@code attemptToSend}, {@code activate}, {@code unwrapBox},
 * {@code updateSignAddress}, {@code triggerStockCheck}, {@code getAvailableItems});</li>
 * <li>writing a Packager's held box or its tray ({@code .heldBox =}, {@code .animationTicks =},
 * {@code .animationInward =}): a door's box that waits is only ever <b>read</b> off them (M27, ADR-065 D14);</li>
 * <li>naming a type of Create's logistics network or its order requests at all.</li>
 * </ul>
 * <b>Comments do not count</b>: a line whose trimmed start is {@code *}, {@code /*} or {@code //} is skipped and a
 * {@code //} tail outside a string is cut off, because the javadoc explains exactly these calls — the port's goggles
 * name {@code LogisticsManager} to say why a linked door ignores a sign.
 * <p>
 * <b>Exempt</b>, each for a reason asserted below so a move cannot quietly empty the scan: the GameTests and the dev
 * harness, which build boxes to test with and which the jar leaves out; and the Ponder scenes of packages, which put a
 * box into a Packager of a Ponder level, a scene's own copy of the world that no player's items ever enter.
 * <p>
 * Runs without Minecraft: it only reads text.
 */
class PackageOrderBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path BUILD_SCRIPT = Path.of("build.gradle");
    private static final Path GAME_TESTS = Path.of("dev/wareworks/gametest");
    private static final Path DEV_HARNESS = Path.of("dev/wareworks/dev");
    private static final Path PACKAGE_SCENES = Path.of("dev/wareworks/client/ponder/scenes/PackageScenes.java");
    private static final Path REQUEST_PAYLOAD = Path.of("dev/wareworks/network/TerminalRequestPayload.java");

    /** What would build, address or stamp a package through Create's own helpers. */
    private static final Pattern PACKAGE_STAMP = Pattern.compile(
            "\\bPackageItem\\s*(?:\\.|::)\\s*(?:setOrder|addAddress|clearAddress|containing)\\b");
    /** The order components themselves. */
    private static final Pattern ORDER_COMPONENT = Pattern.compile("\\bPACKAGE_ORDER_(?:DATA|CONTEXT)\\b");
    /** A Packager's or a network block's actions. */
    private static final Pattern PACKING_CALL = Pattern.compile(
            "\\.\\s*(?:attemptToSend|activate|unwrapBox|updateSignAddress|triggerStockCheck|getAvailableItems)\\s*\\(");
    /** Writing the box a Packager holds, or the tray animation that decides when it may be taken. */
    private static final Pattern PACKAGER_STATE_WRITE = Pattern.compile(
            "\\.(?:heldBox|animationTicks|animationInward)\\s*=(?!=)");
    /** Create's logistics network and its order requests. */
    private static final Pattern NETWORK_TYPE = Pattern.compile("\\b(?:LogisticsManager|LogisticsNetwork|PackagingRequest"
            + "|PackageOrderRequestPacket|PackagerLinkBlockEntity|StockTickerBlockEntity|RedstoneRequesterBlockEntity)\\b");

    private static final List<Pattern> SHAPES = List.of(PACKAGE_STAMP, ORDER_COMPONENT, PACKING_CALL,
            PACKAGER_STATE_WRITE, NETWORK_TYPE);

    @Test
    void noProductionCodePacksStampsOrOrders() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            Path relative = MAIN_SOURCES.relativize(file);
            if (isExempt(relative))
                continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int line = 0; line < lines.size(); line++) {
                String code = codeOf(lines.get(line));
                for (Pattern shape : SHAPES) {
                    Matcher match = shape.matcher(code);
                    while (match.find())
                        offenders.add(relative + ":" + (line + 1) + ": " + match.group());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Wareworks never writes order data, packs, triggers or addresses a box, and "
                + "never talks to Create's logistics network (ADR-065 D3); found " + offenders);
    }

    /**
     * Every exemption is still needed and still justified: the Ponder scenes really do hold a box in a Packager of a
     * Ponder level, the GameTests really do build stamped boxes, and both exempt packages are left out of the jar.
     */
    @Test
    void theExemptionsAreWhereTheyAreNeeded() throws IOException {
        String scenes = source(PACKAGE_SCENES);
        assertTrue(scenes.contains("PonderLevel") || scenes.contains("PonderScene") || scenes.contains("SceneBuilder"),
                "PackageScenes is no Ponder scene any more: its exemption is no longer justified");
        assertTrue(anyCodeLineMatches(scenes, PACKAGER_STATE_WRITE),
                "PackageScenes no longer sets a held box: drop it");
        assertTrue(anyCodeLineMatches(scenes, PACKAGE_STAMP), "PackageScenes no longer addresses a box: drop it");

        boolean gameTestsStamp = false;
        for (Path file : mainSources()) {
            if (MAIN_SOURCES.relativize(file).startsWith(GAME_TESTS)
                    && anyCodeLineMatches(Files.readString(file, StandardCharsets.UTF_8), PACKAGE_STAMP))
                gameTestsStamp = true;
        }
        assertTrue(gameTestsStamp, "no GameTest builds a box any more, or the gametest package moved");

        String build = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(build.contains("exclude('dev/wareworks/dev/**')"), "the dev harness is no longer left out of the jar");
        assertTrue(build.contains("exclude('dev/wareworks/gametest/**')"), "the GameTests are no longer left out of the jar");
    }

    /** A request names its door by position and carries no text a client could fill with an address. */
    @Test
    void theRequestPayloadCarriesNoText() throws IOException {
        String source = source(REQUEST_PAYLOAD);
        int start = source.indexOf("public record TerminalRequestPayload(");
        assertTrue(start >= 0, "TerminalRequestPayload is no record any more: update this guard with ADR-065");
        int end = source.indexOf(')', start);
        String header = source.substring(start, end + 1);
        assertTrue(header.contains("Optional<BlockPos> door"), "the door is a position: " + header);
        assertFalse(header.contains("String"), "the request carries no text at all: " + header);
        assertFalse(source.contains("writeUtf"), "nothing in the request is written as text");
    }

    /** The shapes catch calls and method references, and pass over comments and words that merely look alike. */
    @Test
    void theShapesCatchCodeAndSkipComments() {
        for (String line : List.of("PackageItem.setOrder(box, 1, 0, true, 0, true, null);",
                "PackageItem.addAddress(box, sign);", "PackageItem.clearAddress(created);",
                "ItemStack box = PackageItem.containing(stacks);", "map(PackageItem::addAddress)",
                "box.set(AllDataComponents.PACKAGE_ORDER_DATA, order);", "box.has(PACKAGE_ORDER_CONTEXT)",
                "packager.attemptToSend(null);", "packager.activate();", "packager.unwrapBox(box, false);",
                "packager.updateSignAddress();", "link.triggerStockCheck();", "summary.getAvailableItems()",
                "packager.heldBox = box.copy();", "be.animationTicks = PackagerBlockEntity.CYCLE;",
                "be.animationInward = false;", "LogisticsManager.findPackagersForRequest(network, items)",
                "List<PackagingRequest> requests", "be instanceof StockTickerBlockEntity ticker",
                "new PackageOrderRequestPacket(pos, order, address, false, null)")) {
            assertTrue(SHAPES.stream().anyMatch(shape -> shape.matcher(codeOf(line)).find()),
                    "the scan misses: " + line);
        }
        for (String line : List.of("     * {@code LogisticsManager#performPackageRequests:212}, always passes",
                "// packager.attemptToSend(null) would stamp an order", "/* PackageItem.setOrder */",
                "int held = 1; // packager.heldBox = box", "helper.assertTrue(packager.heldBox.isEmpty())",
                "if (packager.heldBox == box)", "packager.animationTicks != 0 ? null : held",
                "if (packager.animationTicks == 0)", "PackageItem.getAddress(box)", "PackageItem.isPackage(stack)",
                "String url = \"https://example.org\"; int a = 1;", "import com.simibubi.create.content.logistics."
                        + "stockTicker.PackageOrderWithCrafts;", "@Nullable PackageOrderWithCrafts orderContext",
                "reactivate(state)", "hasOrderData(box)")) {
            assertFalse(SHAPES.stream().anyMatch(shape -> shape.matcher(codeOf(line)).find()),
                    "the scan takes this for a packing call: " + line);
        }
        assertEquals("String url = \"https://example.org\"; int a = 1;",
                codeOf("String url = \"https://example.org\"; int a = 1; // and a comment"),
                "a // inside a string is not a comment");
    }

    /** The scan reads the real source tree, not an empty directory. */
    @Test
    void theScanSeesTheSources() throws IOException {
        List<Path> files = mainSources();
        assertTrue(files.size() > 100, "expected the main sources under " + MAIN_SOURCES.toAbsolutePath()
                + ", found " + files.size() + " files");
        assertTrue(files.stream().anyMatch(file -> MAIN_SOURCES.relativize(file).equals(PACKAGE_SCENES)),
                "PackageScenes moved: update this guard");
        assertTrue(files.stream().anyMatch(file -> MAIN_SOURCES.relativize(file)
                .equals(Path.of("dev/wareworks/content/station/PackageHandover.java"))),
                "the scan must see the class that reads a door's Packager");
    }

    private static boolean isExempt(Path relative) {
        return relative.startsWith(GAME_TESTS) || relative.startsWith(DEV_HARNESS) || relative.equals(PACKAGE_SCENES);
    }

    private static boolean anyCodeLineMatches(String source, Pattern shape) {
        for (String line : source.split("\n")) {
            if (shape.matcher(codeOf(line)).find())
                return true;
        }
        return false;
    }

    /**
     * The code of one source line: empty for a comment line (trimmed start {@code *}, {@code /*} or {@code //}), and
     * otherwise everything before a {@code //} or {@code /*} that is not inside a string or a character literal.
     */
    static String codeOf(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//"))
            return "";
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString || inChar) {
                if (c == '\\')
                    i++;
                else if (inString && c == '"')
                    inString = false;
                else if (inChar && c == '\'')
                    inChar = false;
                continue;
            }
            if (c == '"')
                inString = true;
            else if (c == '\'')
                inChar = true;
            else if (c == '/' && i + 1 < line.length() && (line.charAt(i + 1) == '/' || line.charAt(i + 1) == '*'))
                return line.substring(0, i).stripTrailing();
        }
        return line.stripTrailing();
    }

    private static String source(Path relative) throws IOException {
        Path file = MAIN_SOURCES.resolve(relative);
        assertTrue(Files.isRegularFile(file), file + " is missing: update this guard together with ADR-065");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).sorted()
                    .toList();
        }
    }
}
