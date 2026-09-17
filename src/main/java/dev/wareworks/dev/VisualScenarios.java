package dev.wareworks.dev;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** The visual test scenarios by name. Add new scenarios here. */
final class VisualScenarios {
    private static final Map<String, Supplier<VisualScenario>> SCENARIOS = Map.of(
            AisleVisualScenario.NAME, AisleVisualScenario::new,
            CranePosesVisualScenario.NAME, CranePosesVisualScenario::new,
            BlocksVisualScenario.NAME, BlocksVisualScenario::new,
            FiltersVisualScenario.NAME, FiltersVisualScenario::new,
            RobustnessVisualScenario.NAME, RobustnessVisualScenario::new,
            PonderVisualScenario.NAME, PonderVisualScenario::new,
            TerminalVisualScenario.NAME, TerminalVisualScenario::new,
            ShowcaseVisualScenario.NAME, ShowcaseVisualScenario::new,
            ArmVisualScenario.NAME, ArmVisualScenario::new,
            ArmDedicatedServerScenario.NAME, ArmDedicatedServerScenario::new);

    private VisualScenarios() {
    }

    static Optional<VisualScenario> byName(String name) {
        Supplier<VisualScenario> factory = SCENARIOS.get(name);
        return factory == null ? Optional.empty() : Optional.of(factory.get());
    }

    static Set<String> names() {
        return new TreeSet<>(SCENARIOS.keySet());
    }
}
