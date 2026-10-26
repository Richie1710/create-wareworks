package dev.wareworks.content.station;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The guard of ADR-059 (M32, issue #24): Wareworks never runs a recipe on its own, and the one place a recipe runs is a
 * player's click at a warehouse terminal ({@link TerminalCrafting}).
 * <p>
 * ADR-024 keeps the mod out of crafting — no station crafts by itself, no order completes through a virtual crafting
 * step, the controller has no recipe layer — and ADR-059 allows exactly one exception. This test reads every main
 * source file, line by line, and fails on two kinds of line anywhere but in the places allowed for it:
 * <ol>
 * <li><b>Running a recipe</b> ({@link #onlyTheTerminalRunsARecipe}): looking one up, making its result, asking it for
 * its remainders, firing the crafting event, or running one of Create's processing recipes — as a call or as a method
 * reference. Only {@code content/station/TerminalCrafting.java} may.</li>
 * <li><b>Reaching that class</b> ({@link #onlyThePayloadReachesTheCraft}): its one way in is the network handler
 * ({@code network/WareworksNetwork.java}) calling {@code WarehouseTerminalMenu#submitCraft}, which calls the menu's own
 * {@code craft}, which calls {@code WarehouseTerminalBlockEntity#craftFromBuffer}, which calls
 * {@code TerminalCrafting#craft}; the menu alone asks for the preview. Each link may be used only by the link before
 * it, so a production tick, a stock rule or a port that tried to craft through the terminal's own entry fails the
 * build too, not only one that ran a recipe itself.</li>
 * </ol>
 * The {@code gametest} package is exempt from both: tests check recipes and drive the entry points, they do not craft
 * in game. One class is exempt from the first scan by ADR-067 (issue #34): {@code content/station/CrafterRecipeCheck.java}
 * <b>evaluates</b> recipes for the Warehouse Crafter Feeder — is a pattern a crafting recipe, and does what a vanilla
 * Crafter is about to output fit in front of it — and runs none: the vanilla Crafter crafts. It is held to that by
 * {@link #theCrafterCheckOnlyEvaluates}. A call that really belongs somewhere else needs a decision recorded as an ADR first, and then a change to
 * these lists. Two more tests keep the guard from going quiet: the allowed places really are where the calls are, so a
 * renamed or moved class cannot empty a scan, and the patterns really catch every form they are meant to.
 * <p>
 * Runs without Minecraft: it only reads text.
 */
class RecipeExecutionBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    /** The one class allowed to run a recipe, relative to {@link #MAIN_SOURCES}. */
    private static final Path TERMINAL_CRAFTING = Path.of("dev/wareworks/content/station/TerminalCrafting.java");
    /** The one class allowed to evaluate a recipe without running it (ADR-067), relative to {@link #MAIN_SOURCES}. */
    private static final Path CRAFTER_RECIPE_CHECK = Path.of("dev/wareworks/content/station/CrafterRecipeCheck.java");
    /** The GameTests, relative to {@link #MAIN_SOURCES}: they check recipes, they do not craft in game. */
    private static final Path GAME_TESTS = Path.of("dev/wareworks/gametest");
    /** The links of the one way in, relative to {@link #MAIN_SOURCES}. */
    private static final Path TERMINAL_BLOCK_ENTITY =
            Path.of("dev/wareworks/content/station/WarehouseTerminalBlockEntity.java");
    private static final Path TERMINAL_MENU = Path.of("dev/wareworks/content/station/WarehouseTerminalMenu.java");
    private static final Path NETWORK = Path.of("dev/wareworks/network/WareworksNetwork.java");
    /** The visual harness's Shift-click on the panel's result, which only sends the player's own payload. */
    private static final Path CRAFT_SCENARIO = Path.of("dev/wareworks/dev/CraftVisualScenario.java");

    /**
     * What running a recipe takes, as a call or as a method reference: looking it up ({@code getRecipeManager}),
     * making its result ({@code assemble}, or {@code getResultItem} copied, the shortest way to write a "virtual
     * crafting step"), asking it or the recipe manager for what it leaves behind, telling the game an item was crafted,
     * and Create's ways to run a processing recipe ({@code BasinRecipe.apply}, {@code ProcessingRecipe#rollResults}).
     */
    private static final Pattern RECIPE_CALL = Pattern.compile(
            "(?:\\.|::)\\s*(?:assemble|getRemainingItems|getRemainingItemsFor|getResultItem|rollResults)\\b"
                    + "|\\bfirePlayerCraftingEvent\\s*\\(|::\\s*firePlayerCraftingEvent\\b"
                    + "|\\bgetRecipeManager\\s*\\(|::\\s*getRecipeManager\\b"
                    + "|\\bBasinRecipe\\s*(?:\\.|::)\\s*apply\\b");

    /**
     * One link of the way into {@link TerminalCrafting}: how a use of it reads, and the only files that may contain
     * one.
     */
    private record Link(String name, Pattern use, Set<Path> allowedIn) {
    }

    /**
     * The way in, link by link. A Javadoc mention ({@code #craftFromBuffer}) has no parenthesis and is no use. The
     * menu's {@code craft} is public for the GameTests' server-player path and shares its name with the panel's, so
     * every method named {@code craft} is held to the files that call one of the two today.
     */
    private static final List<Link> ENTRY_LINKS = List.of(
            new Link("TerminalCrafting.craft", Pattern.compile("\\bTerminalCrafting\\s*(?:\\.|::)\\s*craft\\b"),
                    Set.of(TERMINAL_BLOCK_ENTITY)),
            new Link("TerminalCrafting.preview", Pattern.compile("\\bTerminalCrafting\\s*(?:\\.|::)\\s*preview\\b"),
                    Set.of(TERMINAL_MENU)),
            new Link("a static import from TerminalCrafting",
                    Pattern.compile("\\bimport\\s+static\\s+dev\\.wareworks\\.content\\.station\\.TerminalCrafting\\b"),
                    Set.of()),
            new Link("craftFromBuffer", Pattern.compile("\\bcraftFromBuffer\\s*\\(|::\\s*craftFromBuffer\\b"),
                    Set.of(TERMINAL_BLOCK_ENTITY, TERMINAL_MENU)),
            new Link("submitCraft", Pattern.compile("\\bsubmitCraft\\s*\\(|::\\s*submitCraft\\b"),
                    Set.of(TERMINAL_MENU, NETWORK)),
            new Link("a method named craft", Pattern.compile("(?:\\.|::)\\s*craft\\s*\\(|::\\s*craft\\b"),
                    Set.of(TERMINAL_BLOCK_ENTITY, TERMINAL_MENU, CRAFT_SCENARIO)));

    @Test
    void onlyTheTerminalRunsARecipe() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            Path relative = MAIN_SOURCES.relativize(file);
            if (relative.equals(TERMINAL_CRAFTING) || relative.equals(CRAFTER_RECIPE_CHECK)
                    || relative.startsWith(GAME_TESTS))
                continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int line = 0; line < lines.size(); line++) {
                Matcher call = RECIPE_CALL.matcher(lines.get(line));
                while (call.find())
                    offenders.add(relative + ":" + (line + 1) + ": " + call.group());
            }
        }
        assertTrue(offenders.isEmpty(), "Only content/station/TerminalCrafting.java may run a recipe (ADR-059); "
                + "found " + offenders);
    }

    @Test
    void onlyThePayloadReachesTheCraft() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            Path relative = MAIN_SOURCES.relativize(file);
            if (relative.equals(TERMINAL_CRAFTING) || relative.startsWith(GAME_TESTS))
                continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (Link link : ENTRY_LINKS) {
                if (link.allowedIn().contains(relative))
                    continue;
                for (int line = 0; line < lines.size(); line++) {
                    if (link.use().matcher(lines.get(line)).find())
                        offenders.add(relative + ":" + (line + 1) + ": " + link.name());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Only a player's payload may reach TerminalCrafting, through submitCraft, the "
                + "terminal menu and craftFromBuffer (ADR-059); found " + offenders);
    }

    /** The allowed place really is where the recipe runs, so a renamed or moved class cannot empty the guard. */
    @Test
    void theAllowedClassIsWhereTheRecipeRuns() throws IOException {
        String source = source(TERMINAL_CRAFTING);
        for (String call : List.of(".assemble(", ".getRemainingItems(", "firePlayerCraftingEvent(",
                "getRecipeManager(")) {
            assertTrue(source.contains(call), "TerminalCrafting no longer calls " + call);
            assertTrue(RECIPE_CALL.matcher(call).find(), "the recipe scan does not catch " + call);
        }
    }

    /**
     * The crafter feeder's recipe check (ADR-067) really is where the feeder's recipe lookups are, so a renamed class
     * cannot empty the exemption, and it only evaluates: it fires no crafting event, takes no recipe through the
     * terminal's way in, and puts nothing anywhere.
     */
    @Test
    void theCrafterCheckOnlyEvaluates() throws IOException {
        String source = source(CRAFTER_RECIPE_CHECK);
        for (String call : List.of(".assemble(", ".getRemainingItems(", "getRecipeManager("))
            assertTrue(source.contains(call), "CrafterRecipeCheck no longer calls " + call);
        for (String forbidden : List.of("firePlayerCraftingEvent", "onCraftedBy", "insertItem(", "setItem(",
                "popResource(", "addFreshEntity(", "TerminalCrafting.", "TerminalCrafting::"))
            assertFalse(source.contains(forbidden), "CrafterRecipeCheck must only evaluate recipes, but uses "
                    + forbidden);
    }

    /** Every link of the way in is still where the scan allows it, so a renamed link cannot empty the guard. */
    @Test
    void theWayInIsWhereTheScanAllowsIt() throws IOException {
        assertUses(TERMINAL_BLOCK_ENTITY, "TerminalCrafting.craft", "a method named craft");
        assertUses(TERMINAL_MENU, "TerminalCrafting.preview", "craftFromBuffer", "a method named craft");
        assertUses(NETWORK, "submitCraft");
    }

    /**
     * The patterns catch what they are meant to — calls and method references alike — and pass over what merely
     * shares a word with them.
     */
    @Test
    void theScansCatchCallsAndMethodReferences() {
        for (String line : List.of("recipe.value().assemble(input, registries)", "map(CraftingRecipe::assemble)",
                "holder.value().getResultItem(level.registryAccess()).copy()", "map(Recipe::getResultItem)",
                "recipe.getRemainingItems(input)", "manager.getRemainingItemsFor(type, input, level)",
                "map(Recipe::getRemainingItems)", "EventHooks.firePlayerCraftingEvent(player, result, matrix)",
                "forEach(EventHooks::firePlayerCraftingEvent)", "level.getRecipeManager().getRecipeFor(type, input)",
                "BasinRecipe.apply(basin, recipe)", "recipe.rollResults(level.random)"))
            assertTrue(RECIPE_CALL.matcher(line).find(), "the recipe scan misses: " + line);
        for (String line : List.of("contraption.assembleContraption(level, pos)", "{@link Recipe#assemble}",
                "\"gui.terminal.craft.result.crafted\"", "getResultItemCount()"))
            assertFalse(RECIPE_CALL.matcher(line).find(), "the recipe scan takes this for a recipe call: " + line);

        assertCaught("TerminalCrafting.craft", "return TerminalCrafting.craft(level, player, buffer, 9, grid, hint);",
                "Function<Object, Object> f = TerminalCrafting::craft;");
        assertCaught("TerminalCrafting.preview", "TerminalCrafting.preview(level, player, cells, hint)");
        assertCaught("a static import from TerminalCrafting",
                "import static dev.wareworks.content.station.TerminalCrafting.*;");
        assertCaught("craftFromBuffer", "terminal.craftFromBuffer(player, menu, grid, hint, true)",
                "use(WarehouseTerminalBlockEntity::craftFromBuffer)");
        assertCaught("submitCraft", "WarehouseTerminalMenu.submitCraft(player, id, action, cells, hint)",
                "use(WarehouseTerminalMenu::submitCraft)");
        assertCaught("a method named craft", "menu.craft(player, cells, hint, false)",
                "use(WarehouseTerminalMenu::craft)");
        for (String line : List.of("{@link WarehouseTerminalBlockEntity#craftFromBuffer}", "{@link #submitCraft}",
                "WarehouseTerminalMenu.submitCraftOrder(player)", "\"gui.terminal.craft.title\"",
                "TerminalCrafting.Preview.NONE", "screen.craftPanel()")) {
            for (Link link : ENTRY_LINKS)
                assertFalse(link.use().matcher(line).find(), link.name() + " takes this for a use: " + line);
        }
    }

    /** The scan reads the real source tree, not an empty directory. */
    @Test
    void theScanSeesTheSources() throws IOException {
        List<Path> files = mainSources();
        assertTrue(files.size() > 100, "expected the main sources under " + MAIN_SOURCES.toAbsolutePath()
                + ", found " + files.size() + " files");
        assertTrue(files.stream().anyMatch(file -> MAIN_SOURCES.relativize(file).startsWith(GAME_TESTS)),
                "the gametest package moved: update this guard");
    }

    private static void assertUses(Path file, String... links) throws IOException {
        String source = source(file);
        for (String name : links) {
            Link link = link(name);
            assertTrue(link.allowedIn().contains(file), name + " is not allowed in " + file);
            assertTrue(link.use().matcher(source).find(), file + " no longer uses " + name
                    + ": update this guard together with ADR-059");
        }
    }

    private static void assertCaught(String name, String... lines) {
        Link link = link(name);
        for (String line : lines)
            assertTrue(link.use().matcher(line).find(), name + " is not caught in: " + line);
    }

    private static Link link(String name) {
        return ENTRY_LINKS.stream().filter(link -> link.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no link named " + name));
    }

    private static String source(Path relative) throws IOException {
        Path file = MAIN_SOURCES.resolve(relative);
        assertTrue(Files.isRegularFile(file), file + " is missing: update this guard together with ADR-059");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java")).sorted()
                    .toList();
        }
    }
}
