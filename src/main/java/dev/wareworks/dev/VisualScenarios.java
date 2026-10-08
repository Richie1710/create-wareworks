package dev.wareworks.dev;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** The visual test scenarios by name. Add new scenarios here. */
final class VisualScenarios {
    private static final Map<String, Supplier<VisualScenario>> SCENARIOS = Map.ofEntries(
            Map.entry(AisleVisualScenario.NAME, AisleVisualScenario::new),
            Map.entry(CranePosesVisualScenario.NAME, CranePosesVisualScenario::new),
            Map.entry(CornerVisualScenario.NAME, CornerVisualScenario::new),
            Map.entry(CombVisualScenario.NAME, CombVisualScenario::new),
            Map.entry(HomeVisualScenario.NAME, HomeVisualScenario::new),
            Map.entry(BlocksVisualScenario.NAME, BlocksVisualScenario::new),
            Map.entry(BaysVisualScenario.NAME, BaysVisualScenario::new),
            Map.entry(FiltersVisualScenario.NAME, FiltersVisualScenario::new),
            Map.entry(PrioritiesVisualScenario.NAME, PrioritiesVisualScenario::new),
            Map.entry(PortsVisualScenario.NAME, PortsVisualScenario::new),
            Map.entry(CollectVisualScenario.NAME, CollectVisualScenario::new),
            Map.entry(CollectLoopVisualScenario.NAME, CollectLoopVisualScenario::new),
            Map.entry(RobustnessVisualScenario.NAME, RobustnessVisualScenario::new),
            Map.entry(ChunkLoadingVisualScenario.NAME, ChunkLoadingVisualScenario::new),
            Map.entry(PonderVisualScenario.NAME, PonderVisualScenario::new),
            Map.entry(TerminalVisualScenario.NAME, TerminalVisualScenario::new),
            Map.entry(ChecklistVisualScenario.NAME, ChecklistVisualScenario::new),
            Map.entry(StockKeeperVisualScenario.NAME, StockKeeperVisualScenario::new),
            Map.entry(StockRulesVisualScenario.NAME, StockRulesVisualScenario::new),
            Map.entry(RestockVisualScenario.NAME, RestockVisualScenario::new),
            Map.entry(ChainVisualScenario.NAME, ChainVisualScenario::new),
            Map.entry(DisplayVisualScenario.NAME, DisplayVisualScenario::new),
            Map.entry(PackagesVisualScenario.NAME, PackagesVisualScenario::new),
            Map.entry(ShowcaseVisualScenario.NAME, ShowcaseVisualScenario::new),
            Map.entry(ArmVisualScenario.NAME, ArmVisualScenario::new),
            Map.entry(ArmDedicatedServerScenario.NAME, ArmDedicatedServerScenario::new));

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
