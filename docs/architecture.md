# Architecture

## Goals

* Physical, visible intralogistics: items move **only** through a machine's handling head. No teleportation.
* Create-first: reuse Create's registrate, kinetics, smart block entity behaviours, goggles, Flywheel visuals and Ponder.
* Universal storage through the NeoForge item capability. No storage-mod dependencies.
* Server-authoritative; dedicated-server safe; robust across chunk unloads, block removal and restarts.

## Layers

```text
┌───────────────────────────────────────────────────────────────┐
│ client        renderers / Flywheel visuals / ponder / screens │  Dist.CLIENT only
├───────────────────────────────────────────────────────────────┤
│ content       blocks, block entities, behaviours              │  world-facing glue
│               (interface, controller, crane, stations)        │
├───────────────────────────────────────────────────────────────┤
│ logistics     job model, planner, reservations, stock index   │  pure Java, unit tested
│ (core)        addresses, state machine                        │  no Level access
├───────────────────────────────────────────────────────────────┤
│ Create API · NeoForge Item Capability · Minecraft             │
└───────────────────────────────────────────────────────────────┘
```

The **core** layer does not depend on `Level`, `BlockEntity` or client classes. That keeps addressing, job planning, reservations and state transitions testable with plain JUnit. The **content** layer adapts core objects to the world and persists them.

## Package structure

```text
dev.wareworks
├── Wareworks                  common @Mod entry, CreateRegistrate instance
├── WareworksClient            @Mod(dist = CLIENT) entry
├── registry                   WareworksBlocks, WareworksBlockEntityTypes, WareworksCreativeTabs,
│                              WareworksCapabilities, WareworksTags, WareworksStress, WareworksMenuTypes;
│                              WareworksArmInteractionPoints (Create mechanical arm interaction point types of the
│                              stations, registered into Create's registry, M12, ADR-025);
│                              WareworksDisplaySources (the four Create display link sources and the transformer that
│                              binds two of them to one block in a fixed order, M14, ADR-026)
│                              (WareworksItems only if plain items are ever added)
├── config                     WareworksConfig (SERVER ModConfigSpec + safe typed getters)
├── core                       ── pure logic, no Minecraft world access ──
│   ├── address                AisleGeometry (size only), StorageAddress (A-LL-PP + side), Side, RackPosition (M2)
│   ├── inventory              SlotView, InventorySnapshot, InventorySummary, KeyCount, CapacityMath;
│   │                          StockIndex, StockView, LocationCount, SnapshotQueue, SharedInventories (M2)
│   ├── warehouse              aisle membership: LocationKind, LocationRecord, RackProbe, AisleMembership,
│   │                          MembershipChanges (M2)
│   ├── job                    RequestQueue, RetrievalRequest (M2 stations); TransportJob, JobType, Reservation,
│   │                          ReservationLedger / ReservationView, CraneSpeeds, CraneKinematics, TravelTimeModel,
│   │                          JobPlanner, PlannerInput, PlanResult, PlannedJob, RerouteTarget, NoJobReason, RefusalMemory (M3);
│   │                          FilterMatch (what a location's store filter says about a key, M8, ADR-021)
│   ├── crane                  CranePhase, CranePose, CraneState, CraneTimings, CraneEvent, CraneEffect,
│   │                          CraneInterruption, AbortReason, CraneStateMachine, CraneMotion (M3); CraneSoundCues
│   │                          (when the crane makes which sound, M4); CraneResync (when a client snaps to a
│   │                          synced pose, M5)
│   ├── terminal               the terminal screen's pure logic (M6): StockCount / StockLine (one item line),
│   │                          StockListModel (list, search, sort, paging), TerminalSearch, TerminalSort,
│   │                          TerminalAmounts (click → amount), CountFormat (compact cell amounts),
│   │                          StockDiff (what a terminal still has to send); RequestConfirmation (what a click would
│   │                          cross: the item's reserve, a reserved ingredient, the maximum) and
│   │                          RequestAcknowledgement (what the player accepted, and whether it covers a question)
│   │                          (M15 part 2, ADR-027)
│   ├── production             production patterns and orders (M11, ADR-024): ProductionEntry, ProductionPattern
│   │                          (3x3 grid → ingredient multiset via fromGrid), SupplyLine (one ingredient an order
│   │                          owes), ProductionOrder / ProductionOrderState (the order state machine),
│   │                          ProductionOrders (a controller's orders), ProduciblePlanner (what an aisle could make)
│   └── stock                  stock rules (M15, ADR-027): StockRule (item + minimum/maximum/reserve and every
│                              question about them), StockRuleAdjustment (what a clamp had to correct), StockRules
│                              (one aisle's rules, shadowing, the cap), StockLevels (stocked/inbound/expected/
│                              available and pipeline()), StockAccess (automation vs. player), StockAvailability
│                              (the one place a reserve enters the request path), StockRuleStatus,
│                              StockRuleEvaluation; and for automatic restocking (part 2) RestockPlanner,
│                              RestockInput, RestockDecision, RestockPlan, RestockOutcome, RestockLimits,
│                              StockRulePause (the safety stop)
├── content
│   ├── item                   ItemKey (item + components, count-less), ItemHandlerSnapshots, ItemTypeSummaries
│   │                          (goggle summary by item type), InsertOnlyItemHandler / ExtractOnlyItemHandler (views)
│   ├── storage                WarehouseInterfaceBlock / BlockEntity, AttachedInventorySummary (storage location);
│   │                          StorageFilterBehaviour (the store filter slot, empty filters not synced),
│   │                          StorageFilterValueBox (its slot on the aisle face) (M8, ADR-021)
│   ├── controller             AisleLayout (world mapping of an aisle), WarehouseControllerBlock / BlockEntity,
│   │                          AisleLetterBehaviour, WarehouseRegistry, WarehouseMember / StorageMember,
│   │                          ControllerStatus, ControllerGoggleSummary, AisleAssignment, ControllerPersistence,
│   │                          RequestRejection, RequestResult (M2); CraneDispatch (M3: planning, ledger, reroutes);
│   │                          LocationReservationSummary (bounded goggle data of a location's reservations, M4);
│   │                          WarehouseRegistry.StorageObservation (assignment + reservations in one scan, M4 review);
│   │                          AisleFilters (store filters of the aisle's storage locations, cached for planning, M8);
│   │                          AisleStockRules (the controller's own, saved copy of its keepers' stock rules, M15,
│   │                          ADR-027; the pauses of the safety stop live in the controller beside it)
│   ├── crane                  StackerCraneBlock / BlockEntity, WarehouseRailBlock, RailScan (M2); CraneExecution,
│   │   │                      CranePersistence, CraneGoggleInfo, CraneJobSummary, CranePauseReason, CranePauseDecision
│   │                      (M3; the pause priority became a pure, unit-tested function in M5); CraneSounds
│   │   │                      (server-played crane sounds, M4); MastHeightValueBox (value box on the rail bed, M4 review);
│   │   │                      CraneServerHooks (resets the once-per-server warning state on ServerStartingEvent, M5)
│   │   └── head               HandlingHead (API), HeldItems, InventoryGrabber (MVP impl), TransferContext /
│   │                          TransferContexts (storage interface, input, output) (M3)
│   ├── station                WarehouseStationBlock / BlockEntity (base), WarehouseInputBlock / BE,
│                              WarehouseOutputBlock / BE, RequestFilterBehaviour, StationBuffer, StationGoggleSummary (M2);
│                              WarehouseDeliveryStationBlockEntity (shared base of the stations a crane delivers into),
│                              WarehouseTerminalBlock / BE, TerminalStockEntry, TerminalStatus (M6, ADR-018);
│                              WarehouseTerminalMenu, TerminalMenuLayout (window geometry both sides need),
│                              TerminalScreenStatus (the bounded status a screen gets) (M6 screen, ADR-019);
│                              TerminalDisplaySide (where the terminal's screen sits relative to its intake port,
│                              M10, ADR-022);
│                              WarehouseProductionBlock / BlockEntity (the station the crane delivers a production
│                              order's ingredients into), ProductionPatterns (its editable 3x3 pattern slots and their
│                              NBT), ProductionGoggleSummary, ProductionMenu, ProductionMenuLayout,
│                              ProductionScreenState (M11, ADR-024);
│                              StationArmPointType (the arm interaction point type of exactly one station block),
│                              WarehouseInputArmPoint (deposit only), DeliveryStationArmPoint (take only: output,
│                              terminal, production station) (M12, ADR-025);
│                              WarehouseStockKeeperBlock / BlockEntity (the aisle member that holds the stock rules;
│                              no items, no capability, no arm point), StockKeeperRules (its editable rows and their
│                              NBT), StockKeeperGoggleSummary, StockKeeperScreenState, StockKeeperMenu,
│                              StockKeeperMenuLayout (M15, ADR-027);
│                              TerminalRequestOutcome (the third ending of a terminal click: accepted, refused, or
│                              asked about — kept out of RequestResult on purpose) (M15 part 2, ADR-027)
│   └── display                the four Create display link sources a player may read off a Wareworks block
│                              (M14, ADR-026): WarehouseDisplays (shared plumbing: the controller behind a source
│                              block, the row limit), AisleSummaryDisplaySource, StockListDisplaySource,
│                              FilteredStockDisplaySource, CraneStatusDisplaySource. Common code, server side only
├── network                    WareworksNetwork (payload registration), the terminal screen payloads:
│                              TerminalStockPayload, TerminalStatusPayload (server → client),
│                              TerminalRequestPayload (client → server), TerminalResultPayload (M6, ADR-019),
│                              TerminalOrdersPayload (server → client: the aisle's production orders, M11),
│                              and the three production station payloads: ProductionScreenPayload (server → client),
│                              ProductionPatternPayload, ProductionCancelPayload (client → server) (M11, ADR-024);
│                              the two stock keeper payloads: StockKeeperScreenPayload (server → client),
│                              StockKeeperRulePayload (client → server, moves no item) (M15, ADR-027);
│                              TerminalConfirmPayload (server → client: what a click would cross, and nothing was
│                              requested) (M15 part 2, ADR-027).
│                              Everything else syncs through block entity update packets
├── client
│   ├── gui                    WarehouseTerminalScreen (the terminal's screen) and TerminalScreenUpdates (where the
│   │                          terminal payloads land on the client) (M6, ADR-019); WarehouseProductionScreen (the
│   │                          pattern grid), ClientProductionStations, ProductionScreenUpdates (M11, ADR-024);
│   │                          WarehouseStockKeeperScreen (the rule rows), ClientStockKeepers,
│   │                          StockKeeperScreenUpdates (M15, ADR-027)
│   ├── render                 StackerCraneRenderer (animated crane, SafeBlockEntityRenderer without Flywheel visual),
│   │                          WareworksPartialModels (crane partials), CraneModelLayout (model dimensions, pose math) (M4)
│   └── ponder                 WareworksPonderPlugin (the one PonderPlugin), WareworksPonderScenes (which scene belongs
│       │                      to which item), WareworksPonderTags (own tag wareworks:warehouse + Create tags),
│       │                      WareworksPonderLang (ponder lang inside the Registrate LANG generator) (M5)
│       └── scenes             PonderAisle (shared stage layout), CraneScript (crane animation through the client pose
│                              API), CraneScenes (stacker_crane/overview), WarehouseScenes (interface, storing,
│                              retrieving, M5; filters, M13), TerminalScenes (terminal, requesting),
│                              ProductionScenes (production) (M13) and StockRuleScenes (stock_rules: the three
│                              numbers; restocking: the minimum ordering by itself and the safety stop) (M15)
├── data                       WareworksDatagen (GatherDataEvent hooks), WareworksLangGen (English lang),
│                              WareworksBlockStateGen (the blockstate generators Create's BlockStateGen does not cover:
│                              the terminal's multipart state, M10, ADR-022; the stock keeper's three lamp models over
│                              LIT and PAUSED, with paused winning, M15, ADR-027)
├── gametest                   @GameTestHolder classes (WareworksItemGameTests: recipes and creative tab, M4;
│                              CraneSoundGameTests: server-played crane sounds through PlayLevelSoundEvent, M4 review;
│                              RobustnessGameTests: aisle shrink, two aisles, config extremes, M5;
│                              WarehouseTerminalGameTests: terminal block, membership and screen API, M6;
│                              StorageFilterGameTests: one test per Create filter item plus the sorting, full,
│                              re-dedication, persistence and goggle cases, M8;
│                              ProductionGameTests: patterns, supply jobs, the full loop with the test playing the
│                              player's machine, refusal, cancel, timeout and persistence, M11;
│                              MechanicalArmGameTests: arm interaction point types, their fixed modes and real
│                              powered arms at the stations, M12;
│                              DisplayLinkGameTests: the four display sources, read through real display links on
│                              lecterns, nixie tubes, a display board and a sign, M14;
│                              StockKeeperGameTests, StockRuleEnforcementGameTests, StockRestockGameTests and
│                              TerminalConfirmationGameTests: the stock rules, what they do to a moving warehouse,
│                              automatic restocking with its safety stop, and the terminal's confirmation, M15)
│                              + layout builders (AisleFixture: one aisle as a player builds it;
│                              ItemCensus: per-tick item census of a test, arm claws included since M12;
│                              ConfigOverrides: in-memory server config overrides restored by an @AfterBatch hook, M5;
│                              MechanicalArmFixture: real Create arms selected and powered like a player's, M12;
│                              templates: scripts/gen_structures.py).
│                              Excluded from the release jar together with dev (build.gradle, M5 review)
├── dev                        dev-only visual smoke test, client side (ADR-014): VisualTestHarness (entry, step runner),
│                              VisualContext, VisualStep / VisualScript (step builder), VisualWorld (title screen, flat
│                              world), VisualPass (Flywheel on/off), VisualScenario / VisualScenarios, AisleVisualScenario
│                              (real jobs, cameras following the frozen crane), CranePosesVisualScenario (fixed crane poses
│                              for all four facings via StackerCraneBlockEntity#showClientPose, item frame),
│                              BlocksVisualScenario (static block close-ups, creative tab check, item screen),
│                              PonderVisualScenario (opens the real Ponder UI per item and shoots every scene, M5),
│                              RobustnessVisualScenario (chunk unload, save + quit + rejoin, blocks broken mid job)
│                              with SceneItemCensus (item census of the whole scene, M5),
│                              DisplayVisualScenario (a wall of display boards and a nixie row fed by real display
│                              links, asserted against the controller's own numbers, M14),
│                              StockKeeperVisualScenario (the keeper block and five rules in five states, its screen
│                              edited through the real payload path), StockRulesVisualScenario (the three numbers of a
│                              stock rule enforced in a real aisle, M15 part 1) and RestockVisualScenario (the warehouse
│                              ordering for itself through a real Mechanical Arm and Mechanical Crafter, the terminal's
│                              three questions and the safety stop, M15 part 2), the last two through ScreenInput (mouse
│                              and key input into an open screen) and GoggleShots (shared goggle-tooltip shots and the
│                              player reach they need),
│                              CameraView, VisualShotIndex, VisualWatchdog, VisualTestException; inactive unless the
│                              system property wareworks.visualTest is set, referenced only from WareworksClient
└── util                       WareworksLang (runtime LangBuilder helper for goggle/tooltip lines),
                               GoggleObservers (server-side, per player: notifies the block entity a
                               goggle-wearing player looks at), SyncThrottle (shared goggle sync throttle),
                               LogThrottle (shared rate limit for the storage-interop diagnostics, M5)
```

`interface` is a Java keyword, so the Warehouse Interface lives in `content.storage`.

Naming note (M2, aisle core): the design's `AisleGeometry { dock; facing; length; height }` is split. `core.address.AisleGeometry` holds only the size (pure Java). Its world mapping (dock, facing, rack positions ↔ block positions, bounds) is `content.controller.AisleLayout`, because it needs `BlockPos`/`Direction` (`stacker-crane.md` §3.1). This is the `AisleLayout` that the M1 review removed from `core`; it now lives in `content`.

Naming notes (M3, core logic):
* Crane speeds and travel time live in `core.job` (`CraneSpeeds`, `CraneKinematics`, `TravelTimeModel`), because the planner ranks with them. `core.crane` depends on `core.job` (jobs, speeds), never the other way round.
* `core.job` and `core.crane` are generic over the location type `L` and get rack coordinates through a `Function<L, RackPosition>`. The controller uses `RackPosition` as `L`, so that function is the identity.
* The crane state machine is a pure function with explicit events and effects (sealed `CraneEvent` / `CraneEffect` records). The crane block entity performs the effects on the world and feeds the real results back in the same tick (`stacker-crane.md` §4.1).
* The reservation ledger is derived from the persisted jobs (`ReservationLedger.restoreFrom`) and has no save format of its own (`warehouse-system.md` §7.4). "Persistence & sync" below still lists reservations under the controller's state in that sense.

Naming notes (M3, crane execution and dispatch):
* The crane's world side is split in two: `content.crane.StackerCraneBlockEntity` owns state, head, persistence, sync, goggles and the API for the controller; the package-private `content.crane.CraneExecution` runs the server tick (pause, location checks, state machine events, effects, reports). The controller's planning side is the package-private `content.controller.CraneDispatch` (ledger, planner input, reports, reroutes, cancellation). Both keep the block entities from becoming god classes (`stacker-crane.md` §4.2, `warehouse-system.md` §7.5).
* The design's `TransferContext` is an interface with three implementations built by `content.crane.head.TransferContexts.resolve` (storage interface, input station, output station). The same contexts serve the crane's real transfers and the controller's live simulations.
* Reservations are derived from the crane's job: the controller rebuilds its ledger from the linked dock's job every dispatch interval, which is also how a new or reloaded controller adopts a running job.

Naming notes (M2, stations):
* The design's request queue is the pure `core.job.RequestQueue<K, D>` with `RetrievalRequest` records; the controller instantiates it as `RequestQueue<ItemKey, BlockPos>` (`warehouse-system.md` §7.2).
* Input and output share `content.station.WarehouseStationBlock` / `WarehouseStationBlockEntity` (buffer, membership, goggles, persistence, drops); the item handler views live in `content.item`, because they are not station-specific.

Naming notes (M2, controller):
* `core.warehouse` is a new pure package for aisle membership: which rack positions hold which kind of member, the reconciliation of probe results, and round-robin order.
* The design's `AisleBounds` is the registered `AisleLayout`.
* The stock index location id is the aisle-local `RackPosition`.
* Member block entities implement `content.controller.WarehouseMember`, and storage members `StorageMember`, so the controller never depends on concrete member classes (`warehouse-system.md` §4).

Naming notes (M1):
* The block entity registry class is `WareworksBlockEntityTypes` (mirrors Create's `AllBlockEntityTypes`).
* Lang is split to avoid a name clash between the datagen strings and the runtime helper: `data.WareworksLangGen` holds the English datagen strings, `util.WareworksLang` is the runtime `LangBuilder` helper (relative keys such as `gui.goggles.empty` resolve to `wareworks.gui.goggles.empty`). There is no `foundation` package; shared runtime helpers go into `util`.
* Block models are hand-made Blockbench JSON under `assets/wareworks/models/block/<name>/block.json` (authored facing north, Create textures only); blockstates, item models, loot tables, tags and English lang are generated by Registrate (`runData`).
* Core class names follow `stacker-crane.md`: `core.address` (`AisleGeometry`, `StorageAddress`, `Side`), `core.job` (jobs, `ReservationLedger`, `JobPlanner`), `core.crane` (`CraneStateMachine`, `CraneMotion`). There is no separate `core.reservation` package. (Aligned after the M1 review; the tree previously listed `AisleLayout`, `MotionProfile`/`AxisTarget` and a `reservation` package.)
* Goggle data of block entities is derived state that the server keeps small, compares by value and syncs only through `write(..., clientPacket = true)` when it changed (throttled); it is not saved to disk (warehouse interface, `warehouse-system.md` §3.1.1).
  * Its size must be bounded independently of inventory contents, because the update tag is part of every chunk packet and clients read block entity tags there with a 2 MB NBT quota. Never sync item data components for display; sync item ids (item types) instead.
  * It is refreshed and synced only while a player observes the block through goggles (`util.GoggleObservers`, per player, one ray pick per interval). Block entities implement `GoggleObservers.Observable` and need no ticker for this.
* Initialisation order in the `Wareworks` constructor: `registerEventListeners` → `defaultCreativeTab(WareworksCreativeTabs.BASE_KEY)` → config → creative tab register → `WareworksDisplaySources` (M14; the block builders reference its entries, so it comes first) → `WareworksBlocks` → `WareworksBlockEntityTypes` → `WareworksMenuTypes` → `WareworksArmInteractionPoints` (M12; its `DeferredRegister` goes on the mod bus, and its types reference blocks) → capability, network and datagen listeners. No static field of `Wareworks` references a registry class.

## Key design decisions (ADR log)

### ADR-001 — ModDevGradle and the official MDK as the build base
*Context:* NeoForge 1.21.1 supports both NeoGradle and ModDevGradle. Create 6.0.10 uses MDG.
*Decision:* ModDevGradle 2.0.147 with Gradle 9.2.1, taken from `NeoForgeMDKs/MDK-1.21.1-ModDevGradle`.
*Reason:* Same toolchain as Create, simpler configuration, officially maintained template.

### ADR-002 — Create 6.0.10 (build 280), non-transitive dependencies
See `dependencies.md`. Create's POM pulls in unrelated integrations (including Architectury), so all Create artifacts are non-transitive and listed explicitly.

### ADR-003 — Own `CreateRegistrate` instance
Create's javadoc states that addons must create their own instance. Wareworks uses `CreateRegistrate.create("wareworks")` with Create's item-description and kinetic-stats tooltip modifier. That gives Create-style tooltips and goggles/kinetic stats for free.

### ADR-004 — Mod identity
No external requirements were given. Mod id `wareworks`, base package and Maven group `dev.wareworks`, artifact `create-wareworks-1.21.1`. Renaming is cheap now and expensive after the first public release, because world saves store the namespace.

### ADR-005 — Storage access only through `Capabilities.ItemHandler.BLOCK`
Interfaces hold a `BlockCapabilityCache` for the adjacent block (the side facing the interface). The cache is invalidated by NeoForge, so no polling is needed to notice that an inventory appeared or disappeared. Content changes are detected by **throttled, on-demand snapshots** (on job planning, on interaction, with a max refresh rate), never by per-tick full scans.

### ADR-006 — Controller plans, crane executes
The controller owns the stock index, reservations and job queue, but never moves items. A job is executed only by a crane, whose handling head holds the items in transit (persisted in the crane BE). Item conservation invariant: an item is always in exactly one of *source inventory*, *handling head*, *target inventory* or *dropped in world*.

### ADR-007 — Crane realisation: block entity with animated parts (DECIDED)
*Context:* The crane could be a Create contraption (moving real blocks) or a stationary block entity whose moving parts are logical state and are rendered.
*Decision:* The dock block entity simulates X/Y/Z axes deterministically (`core.crane.CraneMotion`, shared by server and client) and renders multi-part partial models via a Create `SafeBlockEntityRenderer`.
*Reason:* No contraption assembly/collision/entity lifecycle edge cases; plain BE persistence; deterministic, testable motion; the crane never leaves its aisle, so chunk handling stays predictable. Contraption-based cranes can be explored after the MVP.
*Consequences (recorded in the M4 review):* the moving crane has no collision or outline (the dock block's shape is only its low rail bed, `stacker-crane.md` §2.1). It is drawn only while the dock's chunk section is within the client's render distance: vanilla collects off-screen block entities from compiled sections only, so `shouldRenderOffScreen` and a large view distance do not help beyond that (`stacker-crane.md` §7.1 "Culling"). The default aisle length is far inside usual render distances; long aisles at low render distances would need a proxy renderer along the aisle.

### ADR-008 — Aisle defined by physical blocks, not by configuration UI
*Decision:* Aisle length = consecutive `warehouse_rail` blocks in front of the dock; height = mast height scroll value on the dock; the controller sits directly behind the dock; storage locations and stations are detected at rack positions beside the aisle line. Details: `warehouse-system.md` §1.
*Reason:* Create-first: the build itself is the configuration, and layout directly affects travel time and throughput.

### ADR-009 — Addresses carry a rack side suffix
*Decision:* `A-LL-PP` + `L`/`R` (e.g. `A-03-07R`).
*Reason:* Aisles serve racks on both sides; without the side, addresses would be ambiguous.

### ADR-010 — Membership via an in-memory registry plus bounded scans on dirty
*Decision:* Controllers register their aisle bounds in a per-level `WarehouseRegistry`; members notify it on load, placement and removal; a controller scans only its own rack positions, only in loaded chunks, and only when membership is dirty. Snapshots are reconciled round-robin (one location per interval), refreshed after each transfer, and taken for content hints, joining locations and restored counts through a queue drained at most `maxSnapshotsPerTick` per tick (M2 review).
*Reason:* Satisfies "no tick-based full scans, no permanent world searches" while staying correct after chunk loads and restarts.

### ADR-011 — Requests via output station filter + redstone (MVP)
*Decision:* The output station's Create filter slot (item + count) defines a request; a redstone rising edge submits it. The request terminal GUI comes after the MVP.
*Reason:* Create-typical interaction, zero custom GUI, automatable with Create redstone logistics.

### ADR-013 — Decisions after API research (2026-09-15)
Based on the Create 6.0.10 and NeoForge 21.1 sources, checked before implementation:
* **Crane renderer** extends `SafeBlockEntityRenderer` (not `KineticBlockEntityRenderer`, which draws nothing while Flywheel is active); no Flywheel visualizer is registered for the dock. It needs `shouldRenderOffScreen = true`, a view distance above 64 and a renderer-side render bounding box covering the aisle.
* **Partial models** are created in `WareworksPartialModels.init()`, called from the `WareworksClient` constructor.
* **Stress** is a constant impact registered via `BlockStressValues.IMPACTS.register(block, supplier)`; the supplier reads the server config through a safe getter that falls back to the default. `CStress.setImpact` is not used (it throws for addons).
* **Requests match exactly** (item + data components), i.e. `ItemKey` derived from the filter stack. The `FilteringBehaviour` count is used (≤ 1 stack per request; unstackable items request 1); repeat the redstone pulse for more. *Amended after the M2 review:* the output uses the subclass `content.station.RequestFilterBehaviour`: requests are always "up to" the count (only that board row), a clipboard with a list/attribute/package filter is refused before Create takes a filter item from the player, and one output holds at most `maxOpenRequestsPerOutput` open requests (`warehouse-system.md` §3.2.1, §7.2). *Amended in M7 (ADR-020):* repeated pulses for one item merge into that output's open request instead of taking another slot, so the cap counts the **item types** an output waits for, and the same number times the filter amount bounds the merged request.
* **Relocation**: dock, controller, interface and stations are tagged `create:non_movable` and `c:relocation_not_supported`. Contraptions must not carry warehouse state around.
* **Drops** happen only in `destroy()` (reached via `IBE.onRemove`), using `Containers.dropItemStack` / `ItemHelper.dropContents`, never `Block.popResource`. Crane and stations implement `Clearable`.
* **Registry lifecycle**: controllers register in `onLoad()` (and when their layout changes) and unregister in `invalidate()`/`remove()` (Create's `setRemoved` is final). *Amended in M2:* members notify (`WarehouseRegistry.memberChanged`) only from `onLoad()`, after a facing change and from `remove()`, **never from `invalidate()`**: `invalidate()` also runs on chunk and level unload, where a notification only probes an unloaded position and would re-create `WorldAttached` maps Create has just cleared (`warehouse-system.md` §4). Controllers **do** unregister from `invalidate()`, and that is safe: the only level access on that path is the per-level `WorldAttached` map, which catnip backs with a `WeakHashMap` keyed by the level, so a map re-created after Create cleared it is collected with the level. They do not probe the world from there (no block entity lookup at the dock position); the dock re-validates its controller link itself. (*Corrected in the M5 release audit*: this bullet claimed that controllers do not touch the level in `invalidate()` at all, contradicting both the code and the first half of this same sentence.) Per-level registry maps use catnip `WorldAttached`.
* **Item persistence** never calls `ItemStack.save` on empty stacks or counts > 99, and a save must never throw. *Amended in M3 (review):* held and buffered items are stored as a count-less `ItemKey` next to a plain count (`{Item: ItemKey, Count}`: `InventoryGrabber`, `StationBuffer`, the controller's stock counts), not as per-stack `saveOptional` entries, so amounts above one stack need no splitting; loading is bounded against crafted data (`stacker-crane.md` §6.1, `warehouse-system.md` §3.2.1).
* **Uncached capability lookups** are guarded by `level.isLoaded(pos)`; content changes are detected through round-robin snapshots plus `onNeighborChange` hints. *Implemented in the M2 review:* interfaces forward the hints to their controllers (`WarehouseRegistry.contentChanged`), which read hinted, joining and restored locations through a bounded per-tick queue, and count an inventory shared by several locations once (Create `InventoryIdentifier`; `warehouse-system.md` §5).
* **Lang**: English comes from datagen (`addRawLang` / Registrate lang), written to `src/generated/resources`; `de_de.json` is hand-written in `src/main/resources`. The data run has `--existing-mod create`.
* **GameTests** use NeoForge `@GameTestHolder(Wareworks.ID)` + `@PrefixGameTestTemplate(false)` with floor-only structure templates in `data/wareworks/structure/`, generated by `scripts/gen_structures.py`. Create's internal gametest infrastructure is not used, so that no test depends on Create-internal classes.
* **Chunk ticking**: the crane simulates in the dock block entity, so the dock chunk must be ticking. No chunk tickets in the MVP; documented as a known limitation.
* **Ponder**: block entities only tick client-side there. The crane therefore exposes a virtual/Ponder pose API (targets set via `modifyBlockEntity`), animated by the shared client motion simulation. *Implemented in M4 (review fix):* `StackerCraneBlockEntity#showClientPose(pose, target, phase, held)` on client and Ponder levels; the client tick moves the crane towards the target with `CraneMotion` at the dock's kinetic speed. The three-argument form is a fixed pose for the visual smoke test (`stacker-crane.md` §7.1). *Implemented in M5:* the scenes drive it through `client.ponder.scenes.CraneScript` (ADR-016).

### ADR-012 — Server config via NeoForge `ModConfigSpec`
*Decision:* All tuning values from `warehouse-system.md` §9 live in a server config (`wareworks-server.toml`). Stress impact is registered with Create's stress API as a supplier reading the config.
*Reason:* Server-authoritative balancing without depending on Create's internal config classes.
*Implementation (M1):* `config.WareworksConfig`. NeoForge 21.1 loads SERVER configs from `<instance>/config/wareworks-server.toml`; a copy in `<world>/serverconfig/` overrides it for that world (`ServerLifecycleHooks.handleServerAboutToStart`). All reads go through typed static getters (`WareworksConfig.maxAisleLength()` ...) that return the default while the spec is not loaded, so no caller can hit "Cannot get config value before config is loaded". `WareworksStress.configuredImpact()` registers the crane impact with such a getter.

### ADR-014 — Automated visual smoke test (dev tooling, M4)
*Context:* Rendering (M4: animated crane, Flywheel on and off) must be checked visually, and GameTests and JUnit cannot see the game window.
*Decision:* A client-side harness in `dev.wareworks.dev`, started by the Gradle run `runVisualTest` (game directory `run/visual`, 1600x900 window, system property `wareworks.visualTest=<scenario>`, default `aisle`). It drives a normal client: title screen → fresh superflat creative world `wareworks_visual` (`WorldOpenFlows#createFreshLevel`, deleted first) → world settings (noon, clear, daylight/weather/mob spawning off, peaceful, spectator camera) → the scenario builds its scene with real block placement on the integrated server thread → camera tour (server-side teleports, wait for chunk section compilation) with vanilla `Screenshot.grab` of the main render target after a rendered frame, GUI hidden → the same tour again after `/flywheel backend off` (labels `nofw-*`) → `screenshots/index.txt` → `Minecraft#stop()`. Moments of the crane (carrying, arm extended, travelling) are detected on the synced client block entity and captured with the game ticks frozen (`ServerTickRateManager#setFrozen`), so several views show the same moment.
*Isolation:* `WareworksClient` reads the property (a compile-time constant) and calls the harness from a separate method only when it is set, so normal clients and the dedicated server load no harness class. The package is the one exception to "client code under `client.*`": it is dev tooling, not a game feature, and never reached without the property. *M4 review fix (release artifact):* the property alone also worked in a production client, and the harness shipped in the release jar. Now `WareworksClient` starts it only when `FMLLoader.isProduction()` is false (a production client logs a warning and ignores the property), and the Gradle `jar` task excludes `dev/wareworks/dev/**`. Release jars therefore contain no harness class: none of its world deletion, JVM halt or Flywheel backend switch can be reached, and no accidental API surface remains. Dev runs (`runVisualTest`, `runClient`) load it from the compiled classes; a separate source set was not needed.
*Robustness:* every step has a tick timeout; a watchdog thread bounds startup (5 min) and the run (4 min) and halts the JVM if the game does not exit within 60 s after the run ended. A failure logs `[WareworksVisual] FAILED`, writes the index with the reason and stops the game through a delayed crash report (non-zero exit, so the Gradle task fails).
*Reason:* Screenshots from real client rendering are the only automated evidence for models, animation and both Flywheel paths; commands and a scripted camera keep it deterministic without input automation.
*Extension (M5, robustness):* the same harness also runs the robustness cases that GameTests cannot reach, because GameTest areas are force-loaded and a GameTest server never quits to a title screen: `RobustnessVisualScenario` (Gradle run `robustnessTest`, game directory `run/robustness`, system property `wareworks.visualTest=robustness`) builds an aisle far from the world spawn, forces its chunks to unload and load again during a job, saves the world and quits to the title screen and rejoins it (`VisualWorld#reload`), and breaks the controller and then the dock while the crane carries items. Its evidence is not a screenshot but a `dev.SceneItemCensus` of every item of the scene after each phase, logged as one `robustness PASS` or `robustness FAIL` line; a FAIL throws, so the Gradle task fails (`warehouse-system.md` §8.1). *M5 review fixes:* a census refuses to count while any chunk of its box is missing (the box spans four chunks, so skipping unloaded positions could have reported a chunk-loading race as a lost item), and the harness guards its **frame** callback as well as its tick — `Minecraft#disconnect` pumps `runTick(false)`, which renders without ticking, so a world reload delivers render frames into a step that is tearing down.
*Extension (M6, showcase world):* the same harness also builds the **showcase world**, a world that is deliberately
**kept** instead of thrown away (Gradle run `runShowcase`, game directory `run/showcase`, system property
`wareworks.visualTest=showcase`). This required one new concept: `dev.VisualWorldProfile`, which a scenario returns from
`VisualScenario#worldProfile()`. The screenshot scenarios keep the default `camera()` profile (throw-away world
`wareworks_visual`, spectator, **zero** block and entity interaction range so nothing is ever targeted and Create draws
no value box into a shot); `ShowcaseVisualScenario` returns `playable("wareworks_showcase", "Wareworks Showcase",
CREATIVE)`, which puts the player in creative, **restores the vanilla interaction ranges** from the attributes' own
defaults, and sets the world default game type. The earlier demo world was unusable precisely because it inherited the
camera profile's zero reach, so this split is the fix rather than a convenience. `VisualWorld#createWorld` now also
separates the save **folder** (first argument of `createFreshLevel`) from the **display name** (`LevelSettings#levelName`),
so the world list shows "Wareworks Showcase" for the folder `wareworks_showcase`. The run ends with
`VisualWorld#saveAndQuit` (`saveEverything`, then quit to the title screen, which stops the integrated server and
releases the session lock) so the save on disk is complete before the game stops. The scenario proves the warehouse
before saving it: it feeds stacks through the input and waits until the crane has stored them, then flips the lever and
waits until the redstone request has been delivered into the pull chest — a broken warehouse fails the Gradle task
instead of being saved into a world and handed to a player.
*Extension (M8, storage filters):* a sixth screenshot scenario `filters` (`./gradlew runVisualTest
-Pwareworks.visualTest=filters`, `dev.FiltersVisualScenario`) documents ADR-021 visually. It builds a rack row of four
storage locations, dedicates three of them to diamond, gold and redstone, feeds a mixed stream plus an item no filter
accepts through the input, and **asserts on the server that every chest then holds exactly one item type** before a
single shot is taken: a picture of a partition that did not happen fails the Gradle task instead of being committed.
Two facts of Create's own rendering shape its cameras, both verified in the sources rather than assumed: the filter item
is drawn by `SmartBlockEntityRenderer` → `FilteringRenderer#renderOnBlockEntity` for every filter behaviour of a block
entity **regardless of what the player looks at**, so the camera profile's zero interaction range hides the value box
but not the filter item; and it is drawn only within `FilteringBehaviour#getRenderDistance()`, i.e. Create's client
config `filterItemRenderDistance` (default **10** blocks), which bounds where those cameras may stand. The run ends
with a real redstone retrieval into an output at the far end of the aisle, which proves that a store filter never
blocks fetching *and* parks the crane clear of the line of sight to the rack row.

### ADR-015 — Feel and polish: recipes, sounds, creative tab (M4)
*Recipes:* hand-written JSON in `src/main/resources/data/wareworks/recipe/<item>.json` (1.21.1 format: singular folder, result `id`), one recipe per item with the item's id. The project has no recipe provider, and seven recipes do not justify one; a Registrate `.recipe(...)` for the same path would clash with the JSON (the same resource path in `src/main/resources` and `src/generated/resources` breaks `processResources`). The stacker crane is a Create `mechanical_crafting` recipe (3 x 4, mirrored accepted), everything else vanilla crafting; four of the seven recipes were rebalanced in M9 (below). GameTest `recipesloaded` fails when a recipe does not load, so a typo in an item id or tag cannot slip through.
*Recipe-unlock advancements (M5 release audit; six since the terminal arrived in M6):* the six vanilla crafting recipes also ship a hand-written
`data/wareworks/advancement/recipes/misc/<item>.json` (`minecraft:recipe_unlocked` + an `inventory_changed` criterion on
a key ingredient, `rewards.recipes`), because vanilla only lists recipes the player's `RecipeBook` knows, and nothing
else ever puts them there — without it the recipe book stayed empty for players without JEI/EMI. The stacker crane gets
none: `create:mechanical_crafting` has no vanilla recipe-book category, and Create ships no advancement for its own
mechanical crafting recipes either. `recipesloaded` asserts the pairing in both directions.
*Sounds:* short server-played cues with existing Create and vanilla sound events (`stacker-crane.md` §8.1): no custom sound events, no `sounds.json`, no client code. The decision logic is pure (`core.crane.CraneSoundCues`, JUnit), the mapping to events is `content.crane.CraneSounds`.
*Creative tab:* the stacker crane item is the icon, and the declaration order in `WareworksBlocks` follows how an aisle is built (crane, rail, controller, interface, input, output, and the terminal as the seventh entry since M6); checked by GameTest `creativetaborderandicon` and by the `blocks` visual scenario on the client.
*Reason:* Create-first feel with the smallest surface: no registration code for sounds or recipes, everything verifiable by tests.

*Recipe rebalance (M9):* the crane's grabber became visible as a Create **brass hand** (the part Create
itself uses for the deployer and the mechanical arm), the rail became a mechanical track (**shaft**) at **4** per craft
instead of 8, the terminal moved from a brass funnel to a **precision mechanism** (controller tier), and the output
became the brass member of the andesite family (**brass nuggets**). The crane's swap is not cost-neutral: Create's brass hand (` A ` / `BBB` / ` B `) *contains* the andesite alloy
it replaced, so the crane still costs three andesite alloy and **4 brass sheets more** — no new tier, because its three
iron sheets already require a press and its brass casing already requires brass. Three further consequences are decided here:
* **What actually makes two crafting recipes collide** (verified in `ShapedRecipePattern#matches` and
  `ShapelessRecipe#matches`, corrected in the M9 review): vanilla returns the *first* matching recipe of
  `RecipeType.CRAFTING`, but a **shaped** recipe matches only its own pattern at its own dimensions, plus its
  **horizontal mirror**. Two shaped recipes that merely arrange the same items differently — the pre-M9 input (`F` /
  `C`) and output (`C` / `F`), a *vertical* flip — therefore each resolve to themselves; that pair was unreadable, not
  ambiguous. A real collision needs an identical or mirrored pattern, or a **shapeless** recipe, which ignores both
  arrangement and grid size and matches every layout of its ingredient list.
* **Hard rule (correctness):** no Wareworks recipe may be reachable from another recipe's grid — no shared shaped
  pattern (mirror included) and no item multiset equal to a shapeless recipe's, ours or Create's. Both M9 adjustments
  are forced by it. The terminal takes a **second nixie tube** because swapping only the funnel would have given it the
  controller's *pattern*, not merely its parts (` N ` / `EBE` / ` P ` for both); the output takes **two** brass nuggets
  because one would have made its items exactly the shapeless interface recipe (andesite casing + andesite funnel +
  brass nugget), which matches any arrangement of them.
* **House rule (readability):** beyond that, no two Wareworks recipes *should* share an ingredient multiset, so a
  player never needs a JEI lookup to see which block a grid will make. This is a preference, not a correctness
  constraint — and it is the reason the **optional** terminal ends up one nixie tube dearer than the **mandatory**
  controller. That marker is not the cheapest available (a nixie tube is half an electron tube, so half a polished rose
  quartz plus half an iron sheet, where a brass nugget costs a ninth of a brass ingot); it was chosen because it reads
  correctly, the terminal being the block with the screen. The inversion is accepted knowingly.
* The rules are a test, not a habit: `recipesareunambiguous` crafts every recipe's own grid through the real
  `RecipeManager` (the crane through Create's `MechanicalCraftingInput`, **mirrored as well**, because `accept_mirrored`
  is live behaviour on a different code path), asserts that no 3 x 3 window of the crane's pattern makes a Wareworks
  block in a crafting table, and compares ingredient signatures **resolved to the accepted item ids**, so a tag and an
  item spelling of the same stack collide instead of slipping through. `recipesloaded` pins every ingredient slot (item
  or tag, strictly) so a later silent edit fails the build instead of shipping.

### ADR-016 — Ponder scenes (M5)
*Context:* Ponder is how a Create player learns a machine, and the MVP has six blocks whose interplay (aisle, addresses, jobs) is not obvious from tooltips alone.

*Decision:*
* **One plugin, registered twice on purpose.** `client.ponder.WareworksPonderPlugin` is handed to `PonderIndex.addPlugin` in `WareworksClient#onClientSetup` (runtime, before Ponder's `FMLLoadCompleteEvent` calls `registerAll()`) and again inside the Registrate LANG generator (`client.ponder.WareworksPonderLang`), because `FMLClientSetupEvent` does not fire during `runData`. A second *runtime* registration would list every scene twice. All Ponder code lives under `client.ponder` and is never referenced from common code.
* **Four scenes, one per teaching goal:** `stacker_crane/overview` (rails, dock, racks, controller, rotation from below, the crane travelling, lifting and reaching into a rack, mast height), `warehouse/interface` (inventories become addressable storage locations), `warehouse/storing` (input → controller plans → crane stores) and `warehouse/retrieving` (filter + amount + redstone pulse → crane fetches → funnel pulls out). Every one of the six items is a component of at least one scene, so each has "Hold [W] to Ponder".
  * *Extended in M13 to eight scenes* (terminal, requesting, production, filters), which also gave the terminal and the production station their first scene. See the M13 block below; each one has its own stage in `scripts/gen_ponder_schematics.py`.
  * *Extended in M15 to ten scenes* (`warehouse/stock_rules`, `warehouse/restocking`), the stock keeper's two. See the M15 block below.
* **Own tag `wareworks:warehouse`** (title, description, stacker-crane icon, listed in the index) holds all six blocks; the dock is additionally added to Create's `KINETIC_APPLIANCES` and interface/input/output to `LOGISTICS`. Adding to Create's tags emits no lang of our own.
  * *M13:* the warehouse terminal and the warehouse production station joined both tags, so the tag holds all **eight** blocks and every Wareworks item has "Hold [W] to Ponder". Before that the two newest blocks were in no Ponder tag at all, because a tag member without a scene shows an empty entry.
  * *M15:* the warehouse stock keeper joined both tags together with its scenes, so the tag holds all **nine** blocks. It moves nothing itself, but its three numbers gate what everything else in `LOGISTICS` may move.
* **Schematics are empty stages.** `scripts/gen_ponder_schematics.py` writes nothing but a checkerboard base plate plus an explicit `minecraft:air` entry for every position above it. A `PonderLevel`'s bounds are the bounding box of the blocks the template actually places (not the `size` tag), and instructions silently skip positions outside them, so the air entries are what lets the scenes build themselves with `setBlock`. Consequence: no Wareworks block state or block entity data lives in a `.nbt` file, so block changes never require regenerating them.
* **The crane is animated through the client pose API, not frame by frame.** `client.ponder.scenes.CraneScript` calls `StackerCraneBlockEntity#showClientPose(pose, target, phase, held)` and then idles for exactly as many ticks as the pure `CraneMotion` needs to reach that target, simulated ahead of time at the same kinetic speed the scene sets. The block entity's own client tick does the moving, so the arm retracts before travel and extends at the target exactly as in a real job, and rendering interpolates between ticks. Only blocking instructions add to a scene's total time, so these `idle` calls keep the progress bar honest.
* **Lang:** English from `provideLang` inside the LANG generator (`wareworks.ponder.*`), German hand-written; `LangConsistencyTest` enforces that they match. The `text_n` numbering follows the order of the `.text(...)` calls within a scene, so inserting a line renumbers every later key and both files must be regenerated together.

*Extended in M13 — the teaching pass for terminal, production station and storage filters (GitHub issue #5):* the four M5 scenes covered the MVP blocks; the terminal (M6), the storage filters (M8) and the production station (M11) had none, so a player who learns the mod through Ponder — the way Create teaches its own blocks — never saw them. Four scenes were added, which is **eight** in total. The rules above are unchanged; what M13 adds to the decision:
* **Four new scenes, same rule (one scene per teaching goal):** `warehouse/terminal` (placing it: the screen faces the player, the controller turns the intake port onto the aisle, the wrench walks the screen around the faces that are not the port) and `warehouse/requesting` (search, click rules, batching, the crane delivering into the terminal's own slots) in the new `client.ponder.scenes.TerminalScenes`; `warehouse/production` (a pattern's ingredients delivered, the player's own machine crafting, the product returning through an ordinary input) in the new `client.ponder.scenes.ProductionScenes`; and `warehouse/filters` (a filter item dedicates a location, dedicated locations fill first, retrieval is never blocked) as `WarehouseScenes#storageFilters`.
* **The filter subject is a scene of its own, not a beat inside `warehouse/interface`.** Inserting a `.text(...)` into an existing scene renumbers every later `text_n` key of that scene in **both** lang files (the lang bullet above), and a useful filter beat needs a second dedicated location, a second item type and two crane trips — more than the interface scene's stage and pacing have room for. The interface therefore has three scenes, which Ponder pages through with the arrow keys, and no shipped lang key moved.
* **Which faces the viewer can see, derived rather than guessed.** Ponder's camera starts at `xRotation = -35`, `yRotation = 145` (`PonderScene.SceneTransform`), which draws a block's **north** face on its left half and its **west** face on its right half; south and east point away. On the shared `PonderAisle` stage the aisle runs east, so only a station on the `Side.RIGHT` rack plane shows both its aisle-facing port and a readable second face, and the parked crane stands in front of rack position 1. Everything a scene wants read — a terminal screen, or the filter item `StorageFilterValueBox` draws on a storage location's aisle face — must be placed accordingly. The class comments of `TerminalScenes` and `WarehouseScenes` carry the rule.
* **A Wareworks screen can never be opened in Ponder.** `openScreen` needs a `ServerPlayer` and a `PonderLevel` is client-side (Create's own stock ticker scenes have the same limitation), so the terminal and production screens are *represented* with `showControls` icons plus text, and the wording of the click rules follows `wareworks.gui.terminal.amount_hint` so that scene and screen say the same thing.
* **Mechanical Arms (M12) are named in the two texts that list what feeds or empties a station** (`warehouse_storing.text_2`, `warehouse_retrieving.text_6`). Both are value-only edits inside existing `.text(...)` calls: no key was added, removed or renumbered in either shipped scene.

*Extended in M15 — the stock keeper (GitHub issue #3):* the keeper is the one block whose effect is invisible — three numbers that gate what every other block may move — so it needs Ponder more than any block before it. It got **two** scenes, which is **ten** in total:
* **`warehouse/stock_rules`** ("Stock Rules of a Warehouse"): what a rule is, then one beat per number pointing at the block that number moves — a vanilla comparator and lamp the scene builds behind the keeper for the minimum, a warehouse input that backs up for the maximum, a lever-pulsed warehouse output whose delivery stops at the reserve, and the terminal that is not stopped.
* **`warehouse/restocking`** ("A Warehouse that Restocks Itself"): the same aisle ordering for itself — a short rule, the ingredients fetched (never out of a reserve), the player's own arm and Mechanical Crafter, the product returning through an input, the lamp going out, and then the safety stop and the click that resumes it.
* **Two scenes rather than one, for pacing.** Told as one story the keeper came to about a minute in which the three numbers — the part a player needs first — were over after the first third, and a player who only wanted to look up "what does the reserve do" had to sit through a production loop. The two now run **968** and **1051** ticks (about 48 s and 53 s) and each is watchable on its own, exactly as the terminal's placing/requesting pair is (`StockRuleScenes` carries the reason). The `text_n` renumbering rule above is why the split was made *before* release rather than later.
* **The redstone in a scene is real block states.** A `PonderLevel` (`SchematicLevel`) runs no block ticks and no neighbour updates, so a comparator, a redstone lamp and a lever keep whatever state a scene sets, and `toggleRedstonePower` flips exactly their `POWERED`/`POWER`/`LIT`. The keeper is never in such a selection: its own `LIT` is a rule lamp and is written on its own, together with `PAUSED` for the safety stop — the same two block states the controller's rule tick writes in a real world.

*Verification:* `runData` runs every storyboard once with `level == null`, which is why storyboard bodies must never touch the level (only instruction callbacks may). The `ponder` visual scenario (`dev.wareworks.dev.PonderVisualScenario`, `./gradlew runVisualTest -Pwareworks.visualTest=ponder`) opens the real Ponder UI per item, asserts the registered scene count and screenshots every scene at three moments; a broken storyboard throws out of `PonderUI.of` and fails the run. *M13:* its `SUBJECTS` list holds the per-item scene counts (crane 1, rail 1, controller 2, interface 3, input 1, output 1, terminal 2, production 1), so a scene registered for the wrong item fails the run rather than being noticed by eye. *M15:* keeper 2.

*Reason:* Scenes built entirely from instructions keep the binary assets trivial and stable, and reusing the crane's own motion means the Ponder crane can never drift from how the machine really behaves.

### ADR-017 — Visual language of the release models (M5 polish)
*Context:* At MVP scope the models were correct but not yet readable: the crane looked like a thin dark pole on a rail, and input and output stations differed only in the colour of their frame, which is invisible from most angles.

*Decision:*
* **The crane gets its weight from structure, not size.** The parts stay inside the one aisle block they always occupied; what changed is that the mast is built from two 3 px columns plus a **brass flange band per segment**, the carriage is a C-bracket gripping only the mast's front half, and the base sits on two bogie frames with an axle beam (`stacker-crane.md` §7.1). Because the bands are proud only behind the plane the carriage travels in, a crane of any mast height shows a repeating mechanical rhythm without the carriage ever colliding with it.
* **Stations are told apart by material, not by a marker.** Andesite = input (the dumb intake), brass = output (the smart, filtered one), mirroring Create's own andesite/brass funnel distinction; the shapes already differ (intake on top vs. pull port at the back). A flow arrow was rejected: proud geometry clips neighbouring blocks, and the aisle face cannot carry an inlay because the crane's arm port needs it (`warehouse-system.md` §3.2.1).
* **Model coherence stays a test, not a review habit.** Every geometric claim above is asserted by `CraneModelLayoutTest` against the model JSON (overlap at five arm extensions and every lift height, shared face planes, wheel sweep, port clearance), so a later model edit cannot silently reintroduce z-fighting or a part inside another.

*Reason:* The "Create-first" design goal is about feel, and feel is decided by what a player can read at a glance from the aisle. Tying each visual decision to an automated geometric assertion keeps that readability from decaying, and using only existing Create textures keeps the addon inside Create's palette.

### ADR-018 — The warehouse terminal is an output-style aisle member, not a new kind (M6)
*Context:* The post-MVP Request Terminal gives players a GUI instead of a filter slot and a
redstone pulse. Two things must not break: **no item teleportation**, and the single request
pipeline the MVP hardened (queue, reservations, planner, reroutes, persistence).

*Decision:*
* **The terminal is a physical member of an aisle.** It stands at a rack position with its screen towards the aisle (the
  station alignment rule) and owns an extract-only buffer the crane delivers into. A request from the screen creates the
  same `RETRIEVE` job any output request creates; the controller still never moves an item.
  * *Superseded in M10 by **ADR-022**, which kept everything else and changed only which face is which:* the face
    towards the aisle is the **intake port** the crane reaches through, and the **screen** is a second, independent
    direction on one of the other three faces. Aligning the screen with the aisle — this bullet's rule — is exactly
    what play-testing rejected, because it put the screen on the one face a player cannot stand at.
* **It reports `LocationKind.OUTPUT`**, not a new location kind, and shares the new
  `content.station.WarehouseDeliveryStationBlockEntity` with the warehouse output (buffer view, `insert`, last rejection,
  request goggle lines, NBT keys). Only the *entry point* of a request differs: filter slot plus redstone edge for the
  output, screen for the terminal.
* **Requests go through `WarehouseControllerBlockEntity#request` unchanged**, with the terminal's position as
  destination, so clamping to `availableStock`, `maxOpenRequestsPerOutput`, `maxOpenRequests`, cancellation when the
  destination disappears, reroutes and the saved request format all apply without a line of new logic.
* **Two new rejection reasons are terminal-only** (`OUT_OF_REACH`, `INVALID_AMOUNT`). They are produced by the terminal's
  own validation *before* the controller is asked, so the controller's rejection set is unchanged: a redstone request
  has no player and derives its amount from a filter count, and can reach neither.
* **The server owns the item list.** The screen can only offer `stockSnapshot()`, and `requestFromTerminal` accepts an
  `ItemKey` only after matching it against the controller's live stock index, so a client can never request something the
  server does not hold. The player must also pass vanilla's own container reach check
  (`Container#stillValidBlockEntity`), the same rule a chest GUI uses.

*Reason:* A separate `LocationKind` would have to be threaded through the planner's `allowsTarget`, the reroute
fallbacks, `PlannerInput`, the reservation ledger, the transfer contexts and the persisted records — every one of them a
place where "indistinguishable from a redstone request" could silently stop being true. Reusing `OUTPUT` makes that
property structural instead of a thing to re-verify: the downstream code cannot tell the two apart because there is
nothing to tell apart.

*Consequences:* The controller's goggle line "Inputs: N, outputs: M", `outputStations()` and the per-output request cap
count terminals among the outputs. That is correct for planning (they *are* delivery targets) and is recorded as a
deviation in `warehouse-system.md` §3.4.1. A terminal request may exceed one stack (`maxTerminalRequestAmount`, default
1024) where an output request cannot; the queue and planner already serve a remaining amount above one stack with
successive jobs, so only the entry point changed.

### ADR-019 — The terminal screen is a menu with a throttled, menu-scoped delta push (M6)
*Context:* The warehouse terminal's screen needs three things block entity sync cannot give it: a list of **every**
item type of an aisle (hundreds of entries, with data components), a player-driven **request** channel, and a live
status while a crane works. Block entity update tags are the wrong carrier — they are part of every chunk packet, go to
every player who loads the chunk and are read with a 2 MB client quota (`warehouse-system.md` §3.1.1), which is exactly
why the goggle data is item **ids** only.

*Decision:*
* **A menu owns the connection.** `content.station.WarehouseTerminalMenu` (a Create `MenuBase`) is created by
  `ServerPlayer#openMenu` and exists only while one player looks at one terminal. Vanilla ticks it through
  `broadcastChanges()` once per player tick; every `REFRESH_INTERVAL_TICKS` (10) it pushes to **that player only**.
  There is no global list, no per-tick scan and nothing to clean up: when the menu closes, the pushing stops.
* **The throttle counts game ticks, not calls** (M6 review). `broadcastChanges()` is also called once per container
  click packet and once from `MenuBase#init`, so a call counter would have let a client raise the push rate tenfold.
  Every push is stamped with `Level#getGameTime()`: at most one per tick, and only every 10 unless an **accepted**
  request marked the menu dirty (a refusal changes no availability and does not). Request payloads are capped per menu
  and tick on top of that. The interval is therefore a property of the server, not of the client.
* **Only changes are sent.** The first push carries the whole stock list (pages of at most 64 entries, flagged
  `reset`), every later push only the entries whose total or available amount changed, plus item types that left the
  index as an entry with total 0 (`core.terminal.StockDiff`). The status is a fixed-size record of numbers and enum
  ordinals (`TerminalScreenStatus`) and is sent only when it changed, so an idle warehouse produces no packets.
  **"Absent" is not "gone"**: the reported list is cut off at `maxTerminalStockEntries`, so `StockDiff` is told which
  absent keys are still in stock and leaves the screen's rows for them alone (`warehouse-system.md` §3.4.2).
* **NeoForge payloads, not a channel of our own.** Four `CustomPacketPayload` records registered in
  `RegisterPayloadHandlersEvent` with `PayloadRegistrar` version `WareworksNetwork.VERSION` (`"1"` in M6, `"2"` since
  M7: the version must be bumped for every change to a payload's field layout or to an ordinal-encoded enum, or an
  older client passes the handshake and misreads the fields), each with a hand-written `StreamCodec`
  (`CustomPacketPayload.codec(...)`) that bounds what it decodes. `CustomPacketPayload.createType(String)` is never
  used: it forces the `minecraft` namespace.
* **The client is a renderer, not an authority.** A request payload carries a menu id, an `ItemKey` and an amount; the
  server resolves it against the menu that player really has open, and the terminal then validates reach, amount, aisle
  and item against its own state (ADR-018). A payload for another menu, or from a player without a terminal menu, is
  dropped silently.
* **Dist split.** The clientbound handlers are registered on both sides (the network negotiation requires it) and name
  `client.gui.TerminalScreenUpdates` inside their bodies, which never run on a dedicated server — the same pattern
  Create uses for its own clientbound packets. No other common class touches `client.*`.

*Reason:* The alternatives were worse in a way that would have shown up late: syncing the list through the block entity
would have put item components into chunk packets for every player in range (the bug class the M1 review found), and a
"request" that the client computes would have made the screen the authority over what exists. Tying both the data and
the permission to the open menu makes the screen a pure view: it can only ask for what the server last offered it, and
it stops costing anything the moment it is closed.

*Consequences:* A screen sees changes with up to half a second of delay, which is invisible next to a crane that takes
seconds to travel. A terminal request is the only place where a payload can create work on the server, and it is bounded
twice (config cap, then the available stock). The pure list, search, sort, paging and amount logic lives in
`core.terminal` and is unit tested without a game; the screen itself is verified by screenshots
(`runVisualTest -Pwareworks.visualTest=terminal`).

### ADR-020 — Repeated requests for one item and destination merge into one open request (M7)
*Context:* Play-testing the terminal showed the bug this milestone fixes: clicking the same item ten times made the crane
fetch ten times, one item per trip. Each click was a request of its own, and the planner serves the oldest request with
one job, so ten requests are ten jobs. The same holds for ten redstone pulses at a warehouse output. It is also how a
station runs into `maxOpenRequestsPerOutput` by clicking rather than by asking for more than the warehouse holds.

*Decision:*
* **The merge lives in `core.job.RequestQueue#add`**, not in the controller and not in the UI. A request whose `ItemKey`
  **and** destination match an open request grows that request (`RetrievalRequest#withAdded`) instead of queueing a
  second one. Everything downstream — planner, reservations, reroutes, persistence — sees one ordinary request that
  happens to ask for more.
* **The queue position depends on whether the request was served already** (corrected in the M7 review; the first
  version kept the position unconditionally and claimed "FIFO fairness towards other destinations and other item types
  is untouched"). A request with `delivered() == 0` keeps its position, so a burst of clicks stays where the first click
  queued it. A request that already received items moves to the **back** of the queue when it is grown. Reason: the
  planner serves strictly oldest first and a request leaves the queue only at `remaining == 0`, so an unconditional
  "keeps its position" let a destination that is topped up faster than the crane drains it hold the head of the queue
  for ever — a redstone pulse clock on one output would have starved every other station of the aisle. The guarantee is
  therefore "no station can be starved by another station's repeated requests", not "FIFO is unchanged"
  (`warehouse-system.md` §7.2 "Fairness").
* **Clamping applies to the merged total.** The availability function the controller passes (`availableStock`) already
  subtracts what all open requests promise, so the stock clamp bounds the merged amount by construction. The per-request
  cap became an argument of `add` (`maxRemainingPerRequest`), and **both** entry points pass one: the terminal
  `maxTerminalRequestAmount`, the warehouse output `maxOpenRequestsPerOutput × requestAmount()` — what its request slots
  could promise at once before pulses merged. A merge consumes no queue slot, so the two queue caps cannot refuse it —
  they now cap the **item types** a station waits for, which is what they were meant to bound, and the amount bound of
  the redstone path is the per-request cap instead. (Corrected in the M7 review: the output first passed no cap at all,
  leaving the available stock as its only bound.)
* **A running job is never changed.** The added amount is served by the next trip (`committedToRequest` keeps the
  planner from planning the in-flight part twice). That is physical: the grabber cannot be refilled halfway through a
  trip, and the alternative — cancelling and replanning the running job — would move items twice for no gain.
* **The answer names both numbers.** `RequestResult` (and `TerminalResultPayload`) carry the granted increment, the new
  pending amount and whether it merged, so the screen can say "Requested Andesite x1, now waiting for 10" while "Open
  requests" stays 1. Without that, a merged click looked identical to a fresh one.
* **The persisted format is unchanged**, and `restore` deliberately does not merge: an older world keeps the requests it
  saved (they are served one after another) and the next request merges into the oldest of them. No migration, nothing
  that can throw on load.
* **The store side is left alone.** A `STORE` job already takes the whole buffered amount of one item key per trip, so
  many small stacks of the same item are one trip; there is no request object there to merge (`warehouse-system.md`
  §7.2, GameTest `storebatchesbufferedstacks`).

*Reason:* The queue is the only place that knows every open promise, so it is the only place where "the merged total"
can be clamped without a second source of truth. Merging in the terminal would have left the redstone path broken, and
merging in the planner (one job per key and destination) would have made the queue lie about what is owed and would not
have fixed "Open requests: 10". Keeping the merge inside `add` also keeps it pure and unit-testable.

*Consequences:* A player can no longer force several parallel requests for one item at one station — repeated clicks
raise one request instead, which is what they mean. A pulse clock at one output grows one request instead of taking
`maxOpenRequestsPerOutput` slots, bounded by the same number times its filter amount, and is refused with
`REQUEST_FULL` beyond it. `RequestRejection` gained `REQUEST_FULL` for the only new refusal (the merged total would
exceed the per-request cap); it is declared **last**, because `TerminalResultPayload` encodes the reason by ordinal.
Tests that filled a station's request cap by repeating one item now need distinct item types, which is exactly the
behaviour change.

*M7 review fixes (all three recorded above and in `warehouse-system.md` §7.2):* the fairness rule for a topped-up
request, the output's own amount bound, and `WareworksNetwork.VERSION` raised to `"2"` — the payload gained a field and
the rejection enum gained a constant, while the version negotiated in the handshake still said `"1"`, so an M6 client
would have connected and then read `pending` as the refusal reason.

### ADR-021 — Storage location filters live on the warehouse interface and restrict storing only (M8)
*Context:* Play-testing asked for the obvious warehouse feature the MVP lacked: "can I make this chest hold only iron?".
Without it the planner's item-type grouping decides the layout, which is fine for a dump but useless for a warehouse a
player wants to *organise*. The request explicitly included all three Create filter items (list, attribute, package).

*Decision:*
* **The filter belongs to the storage location, i.e. to the warehouse interface**, as a Create `FilteringBehaviour`
  (`content.storage.StorageFilterBehaviour`). The interface is already the block that turns an inventory into a storage
  location, so "what may live here" is its property; no new block and no controller-side configuration UI.
* **All three filter items work through one code path.** `FilterItemStack.of(stack)` resolves a Create filter item into
  `ListFilterItemStack`, `AttributeFilterItemStack` or `PackageFilterItemStack` via `FilterItem#makeStackWrapper`, and
  `test(level, stack)` then applies that filter's own rules — so list (whitelist and blacklist), attribute rules and
  package addresses are supported by *not* special-casing any of them. The slot therefore installs **no**
  `withPredicate`, unlike the warehouse output, whose request needs one concrete item (§3.2.1).
* **Empty filter = accepts everything** (the Create convention). Every warehouse built before M8 behaves exactly as
  before, and a save without the `Filter` tag loads unchanged.
* **A filter restricts storing only.** The planner consults it in `selectStorage`, the one method every storing path
  goes through (the store plan and both reroutes into storage); retrieval never asks. Items already inside a location
  can therefore always be fetched, even after the filter stopped matching them — which is what makes re-dedicating a
  chest safe.
* **Ranking: dedicated beats unfiltered, then the existing rules.** `FilterMatch.DEDICATED` is the new *first* sort key
  of `JobPlanner`'s candidate ranking, ahead of exact-item consolidation, item-type grouping and travel time. A chest a
  player deliberately dedicated must win over a chest that merely happens to hold that item already, or "dedicated"
  would only mean "preferred when convenient".
* **A rejected location is skipped before the capacity estimate and before any live call**, so it consumes neither the
  live-simulation budget nor the refusal memory. This is load-bearing rather than an optimisation: a warehouse
  partitioned into many dedicated chests has, by construction, far more rejecting candidates than the budget (64), and
  charging them would reproduce exactly the stall the M3 review fixed with `RefusalMemory`.
* **Nothing is ever re-shuffled.** Changing or clearing a filter moves, drops or deletes nothing; the filter only
  decides where *new* items go. Re-balancing an existing warehouse is a post-MVP idea (`roadmap.md`), not a silent
  side effect of editing a slot.
* **The value box sits on the aisle face only** (`content.storage.StorageFilterValueBox`), in the lower half of the
  plate, clear of the crane's arm port. This is forced rather than chosen: `ValueSettingsInputHandler#onBlockActivated`
  **cancels a right-click that hits a value box with `SUCCESS`**, so a value box silently pre-empts block placement and
  `useItemOn` on its face. The lateral faces carry the row-building placement rule ("click the side of the previous
  interface to copy its facing"), top and bottom carry the wrench, and the `FACING` side is covered by the attached
  inventory — the aisle face is the only one left, and the only one a player can reach in a rack wall anyway.
* **No ticker.** `FilteringBehaviour` overrides neither `tick()` nor `initialize()`, and Create drives its value box
  from client events, so the interface stays tickless as §3.1.1 requires. The M1 rule ("never give the interface a
  behaviour that needs ticks") was checked against Create's sources, not assumed.
* **The filters are cached per aisle for planning** (`content.controller.AisleFilters`), refreshed where the controller
  already resolves a location (`refreshLocation`) and on an explicit `WarehouseRegistry.filterChanged` notification.
  Resolving a block entity per candidate per dispatch would be a world lookup per candidate, up to 2·(L+1)·H of them
  every `dispatchIntervalTicks`; a map lookup is what the performance rules allow.
* **An empty filter is left out of client packets.** Create writes `Filter`/`FilterAmount`/`UpTo` unconditionally, which
  cost ~215 accounting bytes in **every** interface's update tag — part of every chunk packet (§3.1.1). Since almost no
  interface carries a filter, `StorageFilterBehaviour#write` skips the block entirely for client packets with an empty
  filter, and Create's `read` reads a missing tag back as "no filter". Found by the existing
  `interfacesummarysyncisbounded` budget test, which this change restores.

*Reason:* Every alternative put the rule somewhere that could drift from the machine. A controller-side mapping would
need its own UI, its own persistence and its own rules for a location that is broken or moved; a filter on the *chest*
would depend on storage mods, which the design rules out. Attaching it to the interface means the filter is created,
moved, broken and saved together with the storage location it describes, and reusing `FilterItemStack` means the three
filter items cannot diverge from how they behave everywhere else in Create.

*M8 review fixes (all recorded in `warehouse-system.md` §3.1, §3.1.1, §7.4 and the §8 table):*
* **"Not read yet" is not "no filter".** The cache is not persisted, so after every world or chunk load it is empty
  while planning already runs; a miss answering `UNFILTERED` stored items into dedicated chests, permanently, because
  nothing is ever re-shuffled. Restored and joining locations are marked unread and resolved once on first use.
* **A deny list is not a dedication.** Create's list and attribute filters answer *true* for everything they do not
  exclude, so one deny-list chest took the top rank for every item in the warehouse. `FilterMatch` gained `ALLOWED`,
  which ranks with `UNFILTERED`.
* **A retrieve reroute may return items to a rejecting location.** Filters gate storing, and leftovers of a retrieval
  already left the warehouse; dropping those candidates could park the crane in `HOLDING` for ever in exactly the
  fully partitioned aisle this ADR encourages.
* **`NO_MATCHING_FILTER`** tells "no filter accepts these items" from "no room" in the goggles; it keeps the
  `fullBackoffTicks` back-off on purpose (reasoned in §7.4).
* **Only real players may set a filter**: Create's value-box handler bypasses the hit test for a `FakePlayer`, so a
  deployer aimed anywhere at the aisle face could re-dedicate a chest and discard the filter item that was in it. The
  "hit-tested within half its scale" rationale above holds for players only.
* **The UI no longer promises a partition that cannot happen**: a filter on a shared-inventory alias is excluded from
  "Filtered locations" and the shadowed interface says so; a Create filter item is named by its contents instead of
  "Filter: List Filter"; an empty filter item is flagged as accepting nothing.
* **Cost**: the renderer the interface gained is given Create's own `filterItemRenderDistance` as its view distance
  (the block is placed by the hundred), an unchanged filter is not re-parsed on every refresh, and the probe stack is
  built once per key instead of once per candidate.

*Consequences:* A player can partition a warehouse by item, and dedicated chests fill before general ones. An item that
matches no filter needs an unfiltered location; without one the controller reports `WAREHOUSE_FULL` and the input keeps
the items, exactly as for a genuinely full warehouse. A location that reads an inventory shared with another location
(double chest, item vault) is governed by the filter of the location that *counts* it (`sharedInventoryOf`), which is a
documented limitation. A filter a player sets is authoritative state, so a filtered interface does put its filter stack
(with components) into chunk packets — the same exposure every Create filter block and the warehouse output already
have, and only for interfaces that were actually filtered. `PlannerInput` gained `storeFilter`, and
`ControllerGoggleSummary` gained `filteredLocations` (a count, so the tag stays bounded).

### ADR-022 — The warehouse terminal has two directions: an aisle-derived intake port and a player-chosen screen (M10)
*Context:* Play-testing rejected the M6 terminal. It was a rack-position member with **one** `FACING`, aligned exactly
like an input or an output, so its screen had to point into the aisle — the one place a player cannot stand, and the
place the crane's arm needs. The requirement: the aisle side is where the crane loads the terminal, like every other
block; the player takes items out on another side; that display side is variable (left, right or back of the intake
port) and can be turned with the wrench. The block also read as a machine port rather than as a
terminal: a 10 x 6 px screen squeezed under the arm port, with the brass pull port on the back.

*Decision:*
* **Two independent horizontal directions, one of them relative.** `FACING` keeps the meaning it has on every other
  station — *towards the aisle* — and is now called the **intake port**. The screen is a **second property `display`,
  stored relative to the port** (`content.station.TerminalDisplaySide`: `back`, `left`, `right`). Storing the screen as
  a second absolute direction was rejected: it would make `display == facing` representable, a state no model can draw
  and every code path would have to check. With three relative values, "the screen is on the port" is **unrepresentable**
  — the guarantee is structural, not a validation.
* **The port follows the aisle, the screen follows the player.** `WarehouseMember#alignToAisle(controller, layout, side)`
  is a new hook the controller's membership probe calls immediately before `isAlignedWith`; the terminal is its only
  implementation. It moves the port onto the aisle side and **recomputes the relative `display` so the screen does not
  travel with it**. It writes the block state only when the port really has to move, and the probe runs only while
  membership is dirty — never per tick.
  * **Amended by the M10 review: exactly one controller may write.** Two *parallel* aisles two blocks apart share the
    rack plane between them (`warehouse-system.md` §4, §8) and want **opposite** ports there, so the hook takes the
    asking controller's position and refuses unless `WarehouseRegistry#ownsMemberState` names it — nearest dock, then
    the lower controller position, and deliberately **not** alignment, which is what the write changes. As first
    written the two controllers rewrote the block on alternating ticks for ever: a permanent per-tick `setBlock` with
    `UPDATE_CLIENTS`, membership that never settled, and a member whose owning aisle changed every tick. A contested
    terminal now keeps the owning aisle's port and is reported misaligned in the other, like every other member on a
    shared plane. GameTest `terminalportowneronasharedrackplane`.
* **Alignment is the plain station rule: the port looks into the aisle.** This ADR originally called it two-sided
  ("and the screen is not on the aisle side"), but that second conjunct can never fail — `TerminalDisplaySide` makes
  "the screen is on the port" unrepresentable — so it was dead code and the M10 review removed it. The
  screen-on-the-aisle misalignment is produced by `alignToAisle`'s *refusal* to move the port onto the screen's face,
  which leaves the port wrong; that is what the goggles report, with the hint "Turn the screen away from the aisle with
  the wrench".
* **The wrench turns the screen, never the port**, one quarter clockwise, skipping the quarter that would land on the
  port (`TerminalDisplaySide#clockwise`, `left → right → back → left`). Two deliberate deviations from the other
  stations: **every** face rotates, not only the top and bottom (the port face is inside the aisle and the screen face
  is the one a player faces, so a top-only wrench would often mean breaking the block to reach it); and it is **never
  refused while the crane works**, unlike the stacker crane dock — turning the screen does not move the port, so a
  delivery already in flight cannot be affected.
* **Multipart blockstate, four interchangeable shells.** Registrate's `horizontalBlockProvider` rotates one model and
  therefore cannot express two directions. The block is built from a core column (`block.json`, 10 x 16 x 10) plus four
  3 px shells that tile the ring around it as a **pinwheel** (each shell is 13 px wide, so its own 90° rotations tile
  the ring with no overlap and each shell owns its outer faces — full-width shells would have shared the block's corner
  faces and z-fought). `data.WareworksBlockStateGen#terminalBlockProvider` emits one part per shell per state: 12 states
  x 4 shells + the core = 49 parts, all rotations of three hand-made models (`shell_display`, `shell_intake`,
  `shell_plain`). The item model is a hand-made `item.json` showing the screen on the north face, which is the face
  vanilla's default GUI transform shows.
* **The crane side is unchanged.** The intake shell carries exactly the interface's port — an 8 x 4 px opening at
  x 4..12, y 9..13 over the full 3 px depth — so `CraneModelLayoutTest#armPassesThroughTheMemberPorts` sweeps the same
  arm volumes through it, and delivery is the same `insert` call at the rack position it always was.

*World compatibility (no migration, by construction):* a block state saved before M10 carries only `facing`, and a
missing property resolves to the block's default — `display=back`, i.e. the screen on the face **opposite** the port.
Because `facing` still means "towards the aisle", a terminal a player had built correctly therefore loads as: port into
the aisle (unchanged), screen on the far side of the rack — exactly where that player already stood to take items out.
Nothing is migrated, no block entity flag marks "old", and no code path can throw on load. A terminal that was
*misaligned* before is still misaligned (its screen lands on the aisle side) and is fixed by one wrench click, after
which `alignToAisle` moves the port. A terminal whose port merely pointed sideways is corrected by `alignToAisle` on the
next membership resolution without touching its screen. GameTest `terminalworldcompatibility` loads the pre-M10 block
state **and** a pre-M10 block entity tag and asserts the buffer, the open request and the delivery loop all survive.

*Reason:* The alternative — keeping one `FACING` and letting the renderer guess the aisle — would have made the model
depend on state the client does not have, and a second *absolute* direction would have bought a simpler blockstate at
the price of a representable-but-undrawable state that every future reader would have to defend against. Deriving the
port from the aisle rather than asking the player for it is what makes the block behave "like every other block" from
the crane's side while staying a terminal from the player's side, which is precisely what was asked for.

*Consequences:* The terminal is the only Wareworks block with a multipart blockstate and the only one whose block state
the controller writes; both are covered by tests rather than by review habit (`terminalShellsTileTheBlockAroundTheArmPort`
pins the tiling, `theTerminalItemModelIsTheSameShellsTurnedAroundTheBlock` pins the hand-baked item model against those
shells, `terminalintakefollowstheaisle` pins that the write happens once and then never again, and
`terminalportowneronasharedrackplane` pins that a second aisle over the same block does not restart it). Writing the
world from inside the membership probe also makes the probe re-enter `AisleMembership#markDirty` for the position it is
probing; that is why `reconcile` snapshots and clears its dirty set before the loop, which its javadoc and
`aProbeMayMarkTheProbedPositionDirtyAgain` now state rather than leave as an implementation detail. Its placement
rule is the one that differs from the other stations: the **screen** faces the player, so a player stands where they
want to read it, with the aisle behind the terminal. The tooltip and the German lang gained a "When turned with a
Wrench" pair, which renumbered the terminal's later tooltip keys in both lang files.

### ADR-023 — One texture of our own, generated by a committed script (M10 look pass)
*Context:* Everything Wareworks draws has so far used Create's own block textures, and that was deliberate
(**ADR-017**: "using only existing Create textures keeps the addon inside Create's palette"); `CraneModelLayoutTest`
enforced it for every model. Play-testing the redesigned terminal (ADR-022) found the one place where the rule stopped
serving the player: the terminal's display was a crop of `create:block/stock_ticker`, i.e. of the *side of Create's
stock ticker block*, and at the size the screen occupies it reads as a beige panel with a dark band rather than as a
screen. `create:block/stock_link` and `create:block/flap_display_front` were checked as alternatives and are no better
(an emitter face and a row of flaps). Create ships no texture that reads as a request terminal's screen, because Create
has no request terminal.

*Decision:*
* **Exactly one texture of our own:** `wareworks:block/terminal_screen` (16 x 16), the warehouse terminal's display.
  Every other texture in the mod stays a `create:` one, and the rule is still *enforced* rather than dropped:
  `CraneModelLayoutTest#everyPartialIsAValidBlockModel` accepts `create:block/…` **or** a `wareworks:block/…` path whose
  PNG exists under `src/main/resources/assets/wareworks/textures/block`, so a second own texture is a visible, reviewed
  change instead of a quiet one.
* **It is generated by a committed script**, `scripts/gen_textures.py`, exactly like the GameTest templates
  (`gen_structures.py`) and the Ponder schematics (`gen_ponder_schematics.py`). The palette is a named table of colours
  and the layout is arithmetic over named constants, so the texture is reviewed as code and changed by changing a
  number instead of by editing pixels no diff can explain. `--verify` re-derives every pixel and compares it with the
  committed PNG, and the Gradle `check` task runs that pass (`verifyTextures`, skipped with a warning when no Python
  interpreter is on PATH, like `verifyStructureTemplates`), so a hand-edited PNG fails the build rather than drifting
  from the script that documents it. The PNG is written by hand (`zlib` + `struct`), so the script needs no third-party
  library and the build never depends on one being installed.
* **The palette stays Create's.** Dark slate with a teal cast for the display, and muted versions of Create's own
  material colours (brass, copper, zinc, iron, redstone) for the five accent texels in the item cells. **No text and no
  glyphs:** the texture shows a search row with a magnifier and a 4 x 3 grid of item cells with a scrollbar — what the
  real screen (ADR-019) looks like — and nothing that would need translating or would age with the GUI.
* **Only the display surface is a texture.** The brass frame, the recess the screen sits in and the take-out tray below
  it are *geometry* (§3.4.3), so the one texture we author is the one thing Create cannot supply, not a whole block
  face.
* **Two texels per model pixel.** The screen face is 8 x 8 model pixels and maps the whole 16 x 16 texture
  (`uv [0, 0, 16, 16]`), the density trick Create uses for its own 32 px sheets (`redstone_requester`, `stock_link`,
  already referenced by the controller model). At 1:1 the search row would have been a single pixel.

*Reason:* The alternative was to keep cropping an unrelated Create block and accept that the terminal does not read as a
terminal, which is exactly the complaint that started M10. Generating the one texture that is missing keeps what the
Create-only rule was *for* — one coherent palette, no drifting art — and drops only the part that had stopped working.
Making it a script rather than a PNG keeps that trade-off auditable: the exception is 200 lines of readable code plus a
`--verify` gate, not an opaque binary.

*Consequences:* `src/main/resources/assets/wareworks/textures/` exists for the first time, and `docs/dependencies.md`
lists the terminal screen as the project's only own texture next to the Create textures the models reference. The
`assertValidBlockModel` rule is now two rules, and the second one is the guard rail: a model referencing
`wareworks:block/<name>` without a committed PNG fails JUnit, and a PNG that does not match the generator fails `check`.

### ADR-024 — Wareworks delivers and collects; it never crafts (M11, production patterns stage 1)
*Context:* Play-testing asked for an AE2-style production interface: define a pattern such as "1 log → 4 planks", let
the system bring the ingredients to something that makes it, then order the product. The obvious reading of that
request — a machine block that consumes ingredients and emits a result — is exactly what this addon must not build:
the addon's core is *visible, mechanical* logistics ("the machine is the feature"), and a block that
turns items into other items is a crafting mod, not a warehouse.

*Decision:*
* **Wareworks delivers and collects. The player's Create machinery crafts.** A pattern says what one run of *their*
  machine needs and makes; the crane brings the ingredients to a **warehouse production station**, their own funnel,
  chute or belt carries them into the machine, and the product comes back into the warehouse through a **normal
  warehouse input**, like any other item. Nothing in Wareworks executes a recipe, and nothing checks that a pattern
  matches one — a wrong pattern simply never produces its result and the order times out.
* **The patterns live in the station**, which is what answers "which machine gets these ingredients" without any
  further configuration: *this* station, and whatever the player attached to it. A controller-side mapping would have
  needed its own UI, its own persistence and its own rules for a station that is broken or moved.
* **A pattern is a 3 x 3 grid plus a result, and the planner gets a multiset.** The grid is for authoring and
  readability: a player already reads recipes as grids. What planning needs is one
  number per item, because the crane carries one item key per trip, so `ProductionPattern.fromGrid` merges cells that
  name the same item. Three cells of a plank are "3 planks" and **one** crane trip. Storing only the multiset would
  have been simpler and unreadable; storing only the grid would have made "3 planks" three trips.
* **`LocationKind.PRODUCTION` is a new kind, not a reused `OUTPUT`.** This is the opposite of the choice ADR-018 made
  for the terminal, and for the opposite reason. The terminal *is* a request destination and had to be
  indistinguishable downstream; a production station is **not** one, and must never be a retrieval destination nor
  receive retrieve leftovers. Reusing `OUTPUT` would have made both of those reachable by default, with nothing but
  vigilance to stop them. A new kind makes it structural: `JobType.allowsTarget` and the reroute fallbacks simply do
  not name it.
* **`SUPPLY` is a new job type that is a retrieve in everything that matters.** It takes items out of storage and
  reserves stock at its source (`JobType#reservesSourceStock`), and is planned by the *same* method as a retrieve
  (`JobPlanner#planOutOfStorage`) — same candidates, same ranking, same live validation. Only the target and the owner
  differ. It is planned **after** the retrieval requests (a waiting player first) and **before** storing. Its
  leftovers go back into **storage only**: nobody asked for them at a station.
* **Stage 1 is single level, by construction rather than by a guard.** An ingredient counts only as real, unpromised
  stock; `ProduciblePlanner` never consults a second pattern to satisfy the first. A missing ingredient is refused with
  a plain `NOT_IN_STOCK` and named to the player. Together with the construction rule "a pattern must not produce one
  of its own ingredients", an order that waits for another order is **unrepresentable**, not merely unreachable.
  Recursive production is stage 2.
* **An order observes the world; it never asserts it.** Completion counts only *increases* of the result's stock
  level, from any source. Counting only what one input delivered would tie an order to which station the machine
  happens to feed; counting the level itself would mistake a retrieval for production.

*The boundary this feature cannot cross (documented, not hidden):* a timed-out or cancelled order releases every
reservation and hands its backing request the unproduced amount back, and it never invents an item — but it also never
takes one back. Ingredients already delivered stay in the station, and ingredients the machine already swallowed are
gone from the warehouse's point of view. Wareworks does not reach into a machine and cannot know what one did with
them. The order reports the amount, the screen says so on the line itself, and two GameTests pin it
(`warehouse-system.md` §3.5.4).

*Reason:* Every alternative crossed the line the design goals draw. A station that crafted would have made the crane
decorative and the mod a crafting mod. A "virtual" crafting step that produced the result once the ingredients arrived
would have been item teleportation with extra steps. Letting the controller execute recipes would have needed a
recipe-matching layer that must track every Create processing type and would break on every Create update. Delivering
and collecting keeps the machine the feature: what a player sees is a crane carrying real logs to the sawmill they
built, and real planks coming back on their own belt.

*Consequences:* The controller gains a second kind of promise besides open requests — the ingredients open orders owe —
and `availableStock` subtracts both. `ControllerGoggleSummary` gained two counts (written into the synced tag only
while they are non-zero, the rule the store filter already follows). `WareworksNetwork.VERSION` is `"3"`: the terminal
payloads gained a field each and three production payloads were added. The terminal now offers items at **zero** stock,
so `StockCount#isGone` is no longer "total == 0" — a producible item at zero stock is *present*, which is what lets a
player order something the warehouse can make but does not have.

*Consequences of the terminal's production UI (M11, second stage of the same milestone):* what the terminal shows of
production is deliberately **derived, never owned**. The screen renders three server-computed things and can decide
none of them:
* **How much can be made.** `producibleAmount` joins `available` in the stock payload, computed in one pass over the
  aisle's patterns (`WarehouseControllerBlockEntity#producibleAmounts`). "Everything possible" is `available +
  producible`, so a **ctrl-click orders an item the warehouse does not hold**. The alternative — letting the screen
  work it out from the patterns — would have made the client the authority on what exists, the property ADR-019 exists
  to prevent, and it could not have known what other orders already promised.
* **What is being made.** `TerminalOrdersPayload` carries the aisle's orders as the very lines the production
  station's screen draws (`ProductionScreenState.OrderView`, one wire form for both). It is a payload of its own rather
  than a field of the status, because an order line carries an item with components while the status is a fixed-size
  record sent whenever a crane moves.
* **Cancelling.** `ProductionCancelPayload` now serves both screens: which orders it may reach is decided by the menu
  the player has open, not by the payload — the station resolves an id against its own orders, the terminal against its
  aisle's. Both re-check reach and refuse everything else, so a crafted id cannot reach another warehouse.

*Consequences of the M11 review fixes (same milestone, after the review):* four of them changed behaviour that the
decision above had left implicit, and each is now stated where it belongs in `warehouse-system.md` §3.5:
* **A promise is not a run.** An order records what it promised its backing request (`promisedToRequest`) beside what
  its whole run yields, because those two are different numbers whenever the asked amount is not a multiple of the
  pattern's result — and it is the *promise* a failed order gives back (§3.5.3, §3.5.4).
* **An arrival is credited once**, to the open orders for that result in creation order (§3.5.3). The alternative made
  a second order complete on the first one's batch, and a phantom-complete order can neither refund nor time out.
* **Ending an order stops the crane that serves it**, through the path a cancelled request already took
  (`CraneDispatch#onOwnersCancelled`); a finished order still counts a drop that was already in the grabber, so
  "ingredients not recovered" stays truthful (§3.5.4).
* **The terminal reserves its producible offers from the stock window's cut** (§3.4.1): an offer has no stock, so the
  cut dropped exactly them, and on a large warehouse the whole feature silently disappeared.
* `RequestRejection` gained `PRODUCTION_BUSY`, so a full order queue is no longer reported as "not in stock" — the
  cure is a different one. Enum constants are a wire format here, so this is the `VERSION` bump below.

`WareworksNetwork.VERSION` is therefore `"4"`. The visible trade is in the window: the production section is part of
the fixed layout (slot positions cannot move later), so the stock grid shows two rows instead of three — the grid
scrolls, an order line does not — and a buffer large enough to leave no room drops the section rather than the window's
size guarantee (`warehouse-system.md` §3.4.2). `TerminalSort` gained a first key (in stock before producible) because
"by name" alone would have mixed offers into the inventory.

### ADR-025 — Mechanical arms reach the stations through one interaction point type per block, with fixed modes (M12)
*Context:* A Create mechanical arm is the obvious machine for a player to put next to a station, and up to M11 it could
not use one. An arm targets a block only when a registered `ArmInteractionPointType` accepts it
(`ArmInteractionPointType#getPrimaryType` returns null otherwise), and Create has no fallback type for blocks that
merely expose an item capability. The M5 release audit documented that limitation and pinned it with GameTest
`stationsarenotarmtargets`; the workaround was a funnel between the arm and the station. Create's registry for these
types, `CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE`, is created by Create itself with NeoForge's
`RegistryBuilder` (synced, with a bake callback that sorts the types by priority). Points are created on the **client**
while a player selects targets with the arm item, cycle their mode on every right-click, and reach the server only as
NBT (`ArmPlacementPacket`, world saves, schematics), where `ArmInteractionPoint.deserialize` rebuilds them and reads
their mode back from the tag. Every station already exposes the item views funnels, chutes and hoppers use
(`WareworksCapabilities`): insert-only on the input, extract-only on the output, terminal and production station.

*Decision:*
* **Register into Create's registry with a plain `DeferredRegister`.** `registry.WareworksArmInteractionPoints` holds a
  `DeferredRegister` for `CreateRegistries.ARM_INTERACTION_POINT_TYPE` in namespace `wareworks` and registers it on the
  mod bus from the `Wareworks` constructor, after `WareworksMenuTypes`. This was verified before it was relied on:
  Create adds the registry to `BuiltInRegistries.REGISTRY` from a mixin into `BuiltInRegistries`' static initialiser,
  NeoForge's `GameData.postRegisterEvents` posts a `RegisterEvent` for every key of that registry of registries, and the
  freeze runs Create's bake callback, which rebuilds the sorted type list from the whole registry
  (`warehouse-system.md` §3.2.2). GameTest `stationarmpointtypes` checks the result at runtime, so no registration
  workaround (a direct `Registry.register` in a `RegisterEvent` listener) was needed.
* **Four type ids, one per station block:** `wareworks:warehouse_input`, `wareworks:warehouse_output`,
  `wareworks:warehouse_terminal`, `wareworks:warehouse_production`. `content.station.StationArmPointType#canCreatePoint`
  accepts exactly its block, in every block state, and nothing else, at Create's default priority. Output, terminal
  and production station behave alike today and still get separate ids, because an arm **saves the type id with every
  point**: one id for all three would tie their arm behaviour together for as long as worlds keep old arms.
* **The input is deposit only.** `WarehouseInputArmPoint` extends Create's `DepositOnlyArmInteractionPoint` (the
  base of Create's funnel point): no mode change, no slots, no extraction.
* **Output, terminal and production station are take only.** One point class, `DeliveryStationArmPoint`, serves all
  three: "take" from construction, no mode change, and `insert` returns the offered stack itself without touching the
  station. It is not final, so a station that needs different arm behaviour later gets a subclass under its existing id.
* **The fixed mode is also enforced on deserialize.** Both point classes set their mode after Create's `deserialize`
  has read `Mode` from the tag, so a hand-edited save, an old schematic or a packet built by a modified client cannot
  load an input as a source or an output as a destination.
* **The arm reaches for the centre of the top face** of all four, the formula of Create's `TopFaceArmInteractionPoint`
  (`StationArmPointType#topFaceCentre`). All four are full-block models, and the aisle face belongs to the crane.
* **No arm point for the warehouse interface**, nor for the controller, the stacker crane dock or the rail.
* **Common code only.** The type and point classes live in `content.station` with no client imports, because Create
  creates points on both sides.
* **Items still move only through a machine.** The arm is the player's own Create machine working the same capability
  views a funnel uses; inside the warehouse the crane stays the only thing that moves items.

*Reason:* A registered type is the only way Create offers to make a block an arm target, so the choice was only *how
many* types and *what they may do*. One type per block costs three extra registry entries and keeps every station free
to change without a save migration; a shared type would have been the one decision that cannot be taken back once
arms are saved in worlds. The modes follow what each station is: the input only ever receives, and the three delivery
stations only ever hand out, exactly as their capability views already say. Letting a click cycle the mode anyway
would have offered a selection that does nothing — an output selected as a destination refuses every item, and it is
then never taken from either — so the modes are fixed, and fixing them only in `cycleMode` would have left the one
path that matters on a server open: the mode a point is *loaded* with comes from a tag, not from the click. The top
face is Create's own answer for blocks an arm should reach from above, and it keeps the arm out of the crane's opening.
The interface gets no point because it has nothing to hand over: it has no inventory of its own, and the chest behind
it is the warehouse's storage, which a player's machine should not reach around the stock index through a block that
exists to *address* it. Create arms do not target a plain chest either, and a funnel on the chest remains the normal
Create way for anyone who wants that anyway. The controller, dock and rail hold nothing a machine could take or give.

*Consequences:* The four station tooltips (and the German lang) name Mechanical Arms beside funnels, chutes and hoppers.
`WarehouseStationGameTests.stationsarenotarmtargets` is gone; `gametest.MechanicalArmGameTests` replaces it with the
type, semantics and real-arm tests, `ProductionGameTests.productioningredientstakenbymechanicalarm` runs the production
loop with an arm, and `ItemCensus` counts what an arm's claw holds (read from the arm's save data, because an arm has
no item capability). The GameTests build real arms through Create's own selection calls and packet constructor but
hand the list to the arm through its saved data, because the packet's server half needs a connected player; the
client-side selection and a client joining a dedicated server are manual checks (`manual-test-checklist.md` section
P). The GameTest helpers read two keys of the arm's save format (`InteractionPoints`, `HeldItem`), which is Create
internals and has to be re-checked on a Create update (`dependencies.md`). Because the capability is asked, not the
face, an arm also works on a station under the next rack level; only the claw's animation then reaches into the block
above. Renaming or removing one of the four ids later would silently drop that point from every saved arm
(`ArmInteractionPoint.deserialize` returns null for an unknown type), which is why the ids are named after the blocks
and treated as a save format.

### ADR-026 — Stock displays are four read-only Display Link sources over state the warehouse already keeps (M14)
*Context:* Create's **Display Link** is the established way to get a machine's numbers onto nixie tubes, a display
board, a sign or a lectern, and up to M13 no Wareworks block offered anything to read. A player who wanted the stock of
an aisle on a wall had no option at all; "stock display / Display Link sources" had been on the roadmap's "After MVP"
list since the MVP. A link reads its source block through a registered `DisplaySource`
(`CreateRegistries.DISPLAY_SOURCE`, a NeoForge-built registry like the arm point registry of M12) and pulls **on its own
schedule** — every `getPassiveRefreshTicks()` while it is unpowered, once more when a redstone signal ends — so the
source decides both what is shown and what a pull costs. The hard rules are unchanged: no per-tick inventory scans, no
world searches, storage only through `Capabilities.ItemHandler.BLOCK`. A controller already keeps everything a stock
display could want (the stock index, the membership counts, the aisle letter and status), and the dock republishes its
`CraneGoggleInfo` on every state change.

*Decision:*
* **Four sources, all read-only, all answering from existing state.** `wareworks:aisle_summary` and
  `wareworks:stock_list` on the **warehouse controller** and the **warehouse terminal**, `wareworks:filtered_stock` on
  the **warehouse output** and the **warehouse interface**, `wareworks:crane_status` on the **stacker crane dock**
  (`warehouse-system.md` §10). No source starts a scan, reads an inventory or searches the world; the most expensive
  one is a single pass over the aisle's distinct item types. **Nothing is pushed** from a Wareworks tick either: a
  display link is a puller, and making the warehouse notify it would have added work to every transfer for a block
  that may not exist.
* **Block, not block entity.** The bindings go through `DisplaySource.BY_BLOCK` (Registrate transforms on the block
  builders), so a source is offered by what a player sees, in every block state.
* **Two sources on one block are bound in one callback** (`WareworksDisplaySources#bind`). Registrate defers
  `onRegisterAfter` callbacks through a `HashMultimap`, whose iteration order is not reproducible, and the link's
  screen preselects the **first** source a block offers — two separate `.transform(...)` calls could therefore
  preselect a different source after a restart.
* **Registered with Registrate, not with a `DeferredRegister`.** `DisplaySource.displaySource(...)` needs a Registrate
  `RegistryEntry`, which a `DeferredHolder` is not, so `registry.WareworksDisplaySources` builds the four entries with
  `CreateRegistrate#displaySource(name, supplier)` — the path Create's own `AllDisplaySources` uses, which also wires
  `BY_BLOCK` and `BY_BLOCK_ENTITY`. This is a deliberate deviation from ADR-025, where the arm point types went through
  a plain `DeferredRegister`; the class still lives in `registry` with the same `register()`-forces-class-loading
  shape, and the `Wareworks` constructor calls it **before** `WareworksBlocks`.
* **Stored amounts, never available ones.** The filtered stock and the stock list report what the aisle holds. The
  available amount moves with every reservation, so a display of it would count down and back up while the crane works.
* **The filter item is the configuration.** The filtered stock source adds no setting beyond Create's generic "Label"
  text box: the output's request filter and the interface's store filter already say which item a player cares about.
* **Lines are cut to `maxRows()` and never clipped to `maxColumns()`.** Clipping needs `Component#getString()` on the
  server, which resolves the line against the server's own language, so every player would be shown that one language
  instead of their own. Targets clip themselves.
* **The crane status refreshes five times as often** (20 ticks against Create's default 100), because a crane changes
  state far faster than a warehouse fills up. Create's own stopwatch source uses 20 as well.
* **Common code, server side, no client imports.** A link gathers its text on the server and sends the finished lines
  to its target.

*Reason:* Every one of these numbers already exists somewhere in the mod — on a goggle tooltip, in the terminal screen
or in the controller's own bookkeeping — so the honest shape of this feature is a *view*, not a new subsystem. That is
also what keeps the hard rules intact: a pull that only reads fields and one map cannot become a per-tick scan, however
many links a player hangs on one controller, and a source that never writes cannot move an item. The four subjects are
the four questions a warehouse wall is built to answer (how full is it, what is in it, how much of *this*, and what is
the machine doing right now), and each is bound to the block a player would naturally point a link at. Binding by block
rather than by block entity type keeps the terminal's multipart states and the output's `powered` state from mattering.
The deviation from ADR-025 on registration is forced by Create's own API: `displaySource(...)` takes a Registrate entry,
so using a `DeferredRegister` would have meant reimplementing the binding by hand for no gain. Reporting stored rather
than available amounts is the difference between a display a player can read at a glance and one that flickers whenever
the crane picks something up. The column rule is the one place where a visibly imperfect result was chosen over a broken
one: an unclipped line that a nixie row cuts is still translated on the client, while a server-clipped line would be
frozen to the server's own language for everyone.

*Consequences:* Four new registry ids that are now a save-visible format — a display link stores the id of its source,
so renaming one would silently reset every link built with it. 21 new lang keys (English generated, German
hand-written), among them the four names the Display Link screen shows. Two small additions to the pure core made the
pull O(1): `StockView#occupiedLocations()`, maintained incrementally in `StockIndex`, and a
`KeyCount#largestFirst(map, n, tieBreak)` overload, because the index's `HashSet` iteration order is not reproducible
and a display must not swap two equally stocked lines; `WarehouseControllerBlockEntity#countedStorageLocationCount()`
(the membership's storage count minus `SharedInventories#aliasCount()`) keeps "used / total" from building the location
record list **and** from mixing populations — an alias of a shared inventory is never occupied, so counting it in the
total would stop the line from ever reading full. That tie-break is `ItemKey#ORDER` and compares values, never
`ItemKey#hashCode`: the hash mixes in `Item`'s identity hash and differs after every restart. A sign flattens the component on the server, so it freezes the server's own
translation — English on a dedicated server, because NeoForge loads every mod's `en_us.json` into the default language
(`LanguageHook#loadModLanguages`); Create's own sources behave identically, and GameTest `displaylinkonsign` pins it.
Lecterns, nixie tubes and display boards keep the component and are translated per player. `gametest.DisplayLinkGameTests` and the visual scenario `display` (`dev.DisplayVisualScenario`) cover the
feature; the interface gains a second reason for its filter slot to be read, which `warehouse-system.md` §10 states
next to ADR-021's storing rule. No Ponder scene was added: the teaching pass of M13 covered the blocks, and a display
link is Create's own mechanic, taught by Create's own scene.

### ADR-027 — Stock rules live in one block with three numbers per item; the reserve protects the warehouse from itself, and the minimum drives production behind a safety stop (M15, issue #3)
*Context:* Up to M14 a Wareworks warehouse had no opinion about **how much** of anything it should hold. It stored
whatever an input was given until it ran out of room, handed out whatever a request asked for until the racks were
empty, and had no way to say "keep 256 planks" or "never let the last 32 andesite alloy go to a machine". Every
comparable mod answers this with per-item levels, and the request came in as issue #3. The pieces to build it on were
already there: a controller that keeps an exact stock index and plans every job (§3.3), a production station that turns
an order into ingredients delivered to a player's own machine (§3.5, ADR-024), and a terminal that already reports what
an aisle holds (§3.4). The hard rules are unchanged: no per-tick inventory scans, no world searches, no item
teleportation, storage only through `Capabilities.ItemHandler.BLOCK`.

*Decision:*
* **One block, a list of rules.** The **Warehouse Stock Keeper** is an aisle member that holds rows of "item +
  minimum + maximum + reserve", and an aisle may hold several keepers. It holds **no items at all** — no buffer, no
  item capability, no arm point, and the crane never visits it — so editing a policy can never consume, duplicate or
  swallow anything. The first design study put the rules in a **column of one-item blocks above the controller**; that
  is rejected, because a column is a second kind of geometry to scan, to bound, to persist and to explain, and because
  a keeper with a single rule already *is* the cheap per-item variant.
* **The three numbers govern three different directions** (in: the minimum, stored: the maximum, out to automation: the
  reserve), and nothing else. Each is either an amount or unset; `0` is a real maximum and unset means "no cap".
  Contradictions a player can express are resolved on the way in, never at the point of use.
* **The reserve protects the warehouse from its own automation, not from the player.** A redstone request at an output,
  the ingredients of a production order it starts, and automatic restocking all stop at the reserve; a player at a
  terminal is served down to the last item and the row says so. This is the **inverse of the first design study** and
  is the user's decision. The corollary matters as much: because automation is what is held back, the reserve also
  governs the **ingredients** an automatic order would spend — reaching a reserved item through a pattern is still
  automation taking it, one step removed.
* **The controller owns the copy that is enforced, and saves it.** A keeper's chunk can be unloaded while the
  controller plans, and reading a miss as "no rule" would store past a maximum (nothing is ever moved back out) and
  hand out a reserve (nothing is ever recalled). The copy is therefore persisted with the controller and never dropped
  wholesale — only a keeper that was read again, or a rack a *loaded* block proved to be no keeper, changes it.
* **The minimum drives production, as an ordinary production order with no backing request.** When a governed item is
  below its minimum and a production station of the same aisle has a pattern for it, the warehouse orders it itself:
  the same `SUPPLY` jobs, the same machine, the same return through a warehouse input. No new job kind, no new crane
  behaviour, no new reservation kind. At most one order is started per evaluation, because every order changes what
  the next one could pay with.
* **One number does the refilling, not two.** "Keep 256" is both the level that triggers an order and the level it
  refills to; there is no separate "refill to" target, no batch size and no hysteresis band to set. This is the user's
  decision, and it is what makes the feature explainable in one sentence. The hysteresis that a second number usually
  exists for comes from the **pipeline** instead: the minimum is judged against stocked + inbound + what an open order
  will bring back (`StockLevels#pipeline()`), so a rule cannot order the same thing twice while the first order runs.
  The only overshoot is the one the player's own pattern causes — a pattern makes whole runs, so a warehouse settles a
  little above its minimum — and it is bounded in both directions: never past the whole runs the rule's **own maximum**
  leaves room for (a surplus stored above a cap never leaves the warehouse again, so the alternative is a rule that
  reports `AT_MAXIMUM` for ever), and never more than `maxRestockOrderAmount` of product or
  `maxRestockIngredientItems` of ingredients per order. The product cap alone does not bound the loss: nine ingots to one
  block turns "at most 512 blocks" into 4608 ingots.
* **What one rule is asking for is not free for another rule's automation.** An automatic order never spends an
  ingredient that a governing rule of the same aisle is itself below its minimum on. Without it a pair of inverse
  patterns — ingot to block and back — converts the same items back and forth for ever, with neither minimum met.
* **A rule stops ordering the first time one of its automatic orders ends with ingredients delivered and no result.**
  The pause stops ordering and nothing else, cancels the rule's other open automatic orders, is reported on every
  surface the rule has (a third lamp colour, its own goggle line on keeper and controller, its own line on an aisle
  display, the rule's row in the keeper's screen and the terminal row's tooltip), is **saved with the controller**, and
  is lifted only by a player — by clicking the paused row's status mark, or by re-editing or clearing the rule **from the
  row that really governs the item**, never from a shadowed duplicate.
* **An automatic order is completed only by items the warehouse stored out of one of its own inputs**, and never by a
  rise of the stock index. The guard above is worth nothing otherwise: the index rises for any reason at all — a second
  farm, a barrel emptied into a rack, a player putting the product back — and an order completed that way never times
  out, so the safety stop never fires and the next dip feeds the same broken machine again. An ordinary order somebody is
  waiting for keeps being counted from the level, where "from any source" is a feature.
* **The terminal asks before a player's own click crosses a line they drew** (into a reserve, into the reserve of an
  **ingredient** a production order would spend for it, or leaving more in the racks than a maximum can hold), naming
  the number; **Alt skips the question**, and Alt does nothing else, because Shift already means "a stack" and Ctrl
  "everything available" — a skip on a key that also changes the amount is not a skip a hint can honestly describe.
* **That question is the server's, and it is asked again when it is answered.** A screen cannot decide it: only the
  server knows the aisle's patterns and what their ingredients are promised to, so only it can see that four planks
  cost a log a rule protects. A click whose cost the payload's acknowledgement does not cover is answered with the
  question and **nothing else** — nothing queued, no order started, no refusal remembered. The acknowledgement carries
  the **numbers** the question named rather than a flag, and the confirmed request is measured a second time, so a
  warehouse that moved in between (a crane promised the items, somebody raised a reserve or rewrote a pattern) is asked
  about again instead of being paid for with an old "yes". The acknowledgement is **consent, not permission**: it
  unlocks nothing a player could not reach anyway, which is exactly why a client may send the blanket form for an
  alt-click.
* **Everything that decides anything is pure.** `core.stock` has no Minecraft types: the three numbers, the rule set
  with its shadowing and its cap, the levels a rule is judged against, the availability a reserve produces, and the
  whole restocking decision. The content layer only carries the answers out.

*Reason:* The feature's real risk is not the arithmetic, it is that **both directions it gates are irreversible**.
Nothing that was stored is ever moved back out, and nothing that was handed to automation can be recalled, so a rule
that is forgotten for a single tick has already done permanent damage — which is why the enforced copy is saved with
the controller and never thrown away on a hint. Making the reserve protect against automation rather than against the
player is what keeps it usable: a reserve a player has to fight against is a reserve they delete, and the case it
really exists for is the overnight drain by a hand-built line. Building restocking on the existing production order is
what keeps ADR-024 true — *Wareworks delivers and collects; it never crafts* — and means the crane, the reservation
ledger and the save format all stay exactly as they were. The safety stop is not a convenience: §3.5.4 already states
that ingredients a machine has swallowed are gone, and an automatic loop that retries a broken machine turns that
documented one-off into an unbounded drain while a player is asleep. Stopping on the **first** loss and requiring a
human to resume is the only rule that cannot be tuned into that failure, and it costs a player one click in exactly the
case where they had to go and look at their machine anyway.

*Consequences:* One new block, one new menu, three new payloads (the two keeper ones and `TerminalConfirmPayload`), one
new field on `TerminalRequestPayload` and a network version bump to `"5"` for the whole of M15. Three new
`StockRuleStatus` values and one new `RequestRejection` (`RESERVED`) go on the wire by ordinal, so they are appended and
never reordered; the same holds for `RestockOutcome` and `StockRulePause.Cause`, which the controller saves by name. A
click at a terminal now has **three** possible endings instead of two, so the terminal answers with
`TerminalRequestOutcome` and `RequestResult` keeps meaning "accepted or refused" — a question is neither, and folding it
in would have made every reader of a rejection handle a case that is not one. The reserved ingredients of a confirmation
are re-checked as one total rather than per item, which leaves one bounded gap (a pattern rewritten between question and
answer could substitute another reserved ingredient of the same amount); it is written down in §3.6.6 rather than
guarded, because carrying up to nine item keys back for every confirmation is not worth it. `ProductionOrder` gains an
explicit `restock` flag rather than deriving it from "has no backing request": an ordinary order loses its request when
that request is served or cancelled, and deriving it would let a player's own cancellation trip the safety stop of a rule
that never ordered anything. `NoJobReason.AT_MAXIMUM` is the
one skip reason that must **not** arm the dispatcher's back-off, because a maximum is answered by a single map lookup
before any candidate walk and holding storing back for every other input would be a real fault caused by a working
rule. The pause is kept in the controller and keyed by item, not in the keeper: the keeper's chunk may be unloaded
exactly when an order fails, and at most one rule governs an item anyway. `ProductionOrder` gains a second counting
channel rather than a second kind of order: `withStored` counts an attributed arrival and `withResultStock` a level, and
which of the two an order listens to follows from its `restock` flag. The residual is written down rather than guarded —
a second source of the same product feeding the same warehouse **through an input** is indistinguishable from the ordered
machine's output, and no bookkeeping inside the warehouse can say which machine made items it really received. Seven new
config keys, four of which (`maxRestockOrders`, `maxRestockOrdersPerRule`, `maxRestockOrderAmount`,
`maxRestockIngredientItems`) bound how much the warehouse may ever have in a machine at once, with `0` in either order
count as the off switch for automatic ordering while every rule keeps capping and reserving. A warehouse from before M15 has no keeper, so every new path is guarded by "does a rule govern
this key", which answers no, and the behaviour is bit-for-bit what it was.

## Persistence & sync

* All authoritative state lives in block entities (controller: aisle layout, index cache, job queue, reservations; crane: state, axis positions, current job, head inventory) and is saved via `saveAdditional`/`loadAdditional` with registry-aware `HolderLookup.Provider` (1.21.1 signature).
* The client receives only what rendering and goggles need through the standard BE update packet. Custom payloads are added only if that is insufficient.
* After a restart, jobs resume from the persisted state (`CraneStateMachine.resume` makes a loaded state consistent). There is no `FAULT` phase: if a referenced block is missing, a job aborts with a reason before the pick, and after it the held items are rerouted (`REROUTE`), held and retried (`HOLDING`) or waited with at the requesting output (`WAITING_FOR_TARGET`). Items are dropped only when the dock itself breaks (`stacker-crane.md` §4.1, `warehouse-system.md` §8).

## Performance rules

* No per-tick inventory scans, no world searches. Aisle membership is registered by the blocks themselves on load/placement and unregistered on removal/unload.
* Index updates are incremental: the result of every transfer updates the index directly; snapshots only reconcile drift.
* Controller and crane tick only while they have work or are moving.
* Warehouse interfaces have no ticker at all (`warehouse-system.md` §3.1.1): presence comes from capability invalidation, content hints from `onNeighborChange`, goggle data from player observation.
