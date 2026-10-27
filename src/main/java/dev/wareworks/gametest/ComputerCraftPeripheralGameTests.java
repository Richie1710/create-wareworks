package dev.wareworks.gametest;

import java.util.Collection;
import java.util.List;

import dev.wareworks.Wareworks;
import dev.wareworks.compat.computercraft.ComputerCraftCompat;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * GameTests of the real {@code wareworks_terminal} peripheral (issue #9, ADR-074), present only in a run with CC: Tweaked
 * ({@code ./gradlew runGameTestServer -Pwareworks.cc}).
 * <p>
 * This holder names no CC: Tweaked type, so every GameTest run loads it: without CC the generator returns no tests and
 * never touches {@link ComputerCraftPeripheralBodies}, which does name CC types, so the JVM never resolves one. The
 * logic behind the peripheral is tested in every run by {@link ComputerAccessGameTests}.
 */
@GameTestHolder(Wareworks.ID)
public final class ComputerCraftPeripheralGameTests {
    private ComputerCraftPeripheralGameTests() {
    }

    /** The peripheral tests, or none without CC: Tweaked. */
    @GameTestGenerator
    public static Collection<TestFunction> computerCraftPeripheralTests() {
        if (!ComputerCraftCompat.isLoaded())
            return List.of();
        return ComputerCraftPeripheralBodies.tests();
    }
}
