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
│                              binds two of them to one block in a fixed order, M14, ADR-026);
│                              WareworksAttachments (the mod's NeoForge data attachment types, i.e. state that belongs
│                              to a **player** and not to a block: today only TERMINAL_PREFERENCES, saved inside that
│                              player's own playerdata, copied on death and never synced, M24, issue #17, ADR-037)
│                              (WareworksItems only if plain items are ever added)
├── command                    WareworksCommands (`/wareworks chunks`, permission 2: what the mod holds force-loaded per
│                              dimension, and the release valve — a hard requirement of M19, because nothing outside
│                              NeoForge's own validation callback can enumerate a mod's chunk tickets, ADR-031)
├── config                     WareworksConfig (SERVER ModConfigSpec + safe typed getters)
├── core                       ── pure logic, no Minecraft world access ──
│   ├── address                AisleGeometry (size only), StorageAddress (A-LL-PP + side), Side, RackPosition (M2;
│   │                          since M21 it carries the **branch** it belongs to as its first key, absent value 0, so
│   │                          a warehouse of one aisle is described by exactly the values it always was, ADR-033);
│   │                          the pure geometry of a rail network (M21, issue #1, ADR-033): Heading (the four
│   │                          horizontal headings and the quarter turns between them), BranchGeometry (one straight
│   │                          branch: origin offset, heading, length), NetworkGeometry (every branch of one warehouse
│   │                          and the links where they meet), BranchLink (two branches and the one block they share),
│   │                          RackSpace / RackCandidate (a block's possible owners, one per neighbouring aisle block
│   │                          of a perpendicular branch — the data the ownership rule resolves with the member's own
│   │                          facing)
│   ├── inventory              SlotView, InventorySnapshot, InventorySummary, KeyCount, CapacityMath;
│   │                          StockIndex, StockView, LocationCount, SnapshotQueue, SharedInventories (M2)
│   ├── warehouse              aisle membership: LocationKind, LocationRecord, RackProbe, AisleMembership,
│   │                          MembershipChanges (M2);
│   │                          the rail network as pure integer maths (M21/M22, issues #1 and #2, ADR-033, ADR-035):
│   │                          RailGraph (a set of dock-relative rail offsets flooded from the dock and decomposed into
│   │                          maximal straight branches; the rails may split and close on themselves since M22),
│   │                          RailNetwork (the result: a NetworkGeometry plus where and why the scan stopped),
│   │                          NetworkStop (the eight reasons a scan ends — END and CLOSED are real ends, the other six
│   │                          are faults a player can see and fix, and every configured maximum names the key to
│   │                          raise), RouteModel (the one-question facade), RouteCosts (the cheapest route over the
│   │                          junction nodes, priced in blocks plus turns, derived one single-source pass at a time and
│   │                          kept; also componentOf / reachable / canDrive), CraneRoute (a route with its cost in
│   │                          blocks, turns priced by turnPenaltyBlocks), RouteTable (a shape with its links and its
│   │                          costs per turn price, held by whoever holds the shape) and SnapshotCadence (how many storage locations
│   │                          the round robin reads per interval, so its cycle time is bounded, M22)
│   ├── job                    RequestQueue, RetrievalRequest (M2 stations); TransportJob, JobType, Reservation,
│   │                          ReservationLedger / ReservationView, CraneSpeeds, CraneKinematics, TravelTimeModel,
│   │                          JobPlanner, PlannerInput, PlanResult, PlannedJob, RerouteTarget, NoJobReason, RefusalMemory (M3);
│   │                          LocationAvailability (M22: the world asked once per location per planning pass, and the
│   │                          available storage list derived once, each location keeping its index in the full list);
│   │                          FilterMatch (what a location's store filter says about a key, M8, ADR-021);
│   │                          since M17 (ADR-029) PlannerInput also carries ports / portRank and a STORE job may target
│   │                          an OUTPUT (TransportJob.storeToPort, NoJobReason.PORT_FULL);
│   │                          since M18 (ADR-030) JobType.COLLECT (a collecting warehouse port → storage, never a port
│   │                          as its target) with TransportJob.collect, bringsItemsIn / waitsAtAFullTarget,
│   │                          PlannerInput.collectSources / collectBuffers, PlanResult.nextArrivalCursor and
│   │                          JobPlanner.planCollect / MAX_COLLECT_CANDIDATES
│   ├── crane                  CranePhase, CranePose, CraneState, CraneTimings, CraneEvent, CraneEffect,
│   │                          CraneInterruption, AbortReason, CraneStateMachine, CraneMotion (M3); CraneSoundCues
│   │                          (when the crane makes which sound, M4; the TURN cue, M21); CraneResync (when a client
│   │                          snaps to a synced pose, M5); CraneNetwork (what the crane knows about its own rails:
│   │                          the shape, its route table and the resting yaw at the dock, M21, ADR-033);
│   │                          HomeReturn (where a crane with nothing to do waits and when it drives there: the whole
│   │                          rule as one pure value, M21, ADR-034). Since M21
│   │                          CranePose carries the branch it stands on and a continuous yaw in quarter turns, and
│   │                          CraneMotion gains exactly one precedence rule — turn towards the leg's heading with X
│   │                          frozen — between "retract the arm" and "move X and Y"
│   ├── terminal               the terminal screen's pure logic (M6): StockCount / StockLine (one item line),
│   │                          StockListModel (list, search, sort, paging), TerminalSearch, TerminalSort,
│   │                          TerminalAmounts (click → amount), CountFormat (compact cell amounts),
│   │                          StockDiff (what a terminal still has to send); RequestConfirmation (what a click would
│   │                          cross: the item's reserve, a reserved ingredient, the maximum; since M20 also #ofPlan,
│   │                          measured over a whole chain's leaf demand) and
│   │                          RequestAcknowledgement (what the player accepted, and whether it covers a question)
│   │                          (M15 part 2, ADR-027); PlanMember / PlanLine / PlanLines (M20: the order rows a payload
│   │                          carries folded into one line per chain, head plus frontier — pure, so every degenerate
│   │                          payload has a test) and PlanCancelCost (what giving up on such a line really costs:
│   │                          the orders failPlan would end and the batch it would abandon, in one tested place so
│   │                          the panel and the server cannot disagree); the clipboard list (M23, issue #19,
│   │                          ADR-036): RequestScope (click or list portion — the two things a scope changes),
│   │                          ListEntry (what one clipboard entry is worth believing), ListLine (the order's own
│   │                          record of an entry: the line is the truth, the tick mark the receipt), ListOrder (the
│   │                          whole order: which entries, which line next, how big a portion, how a delivery maps
│   │                          back), ListPortion / ListPass / ListCredit / ListOrderState and
│   │                          ListOrderConfirmation (what pressing Fetch would really mean, measured over the whole
│   │                          list); the "most used" order (M24, issue #17, ADR-037): TerminalUsage (the bounded
│   │                          per-player store — one count per item type, one request counted once, the weakest
│   │                          entry evicted when it is full, counts fading by use and never by a clock, and a
│   │                          save/restore surface that re-applies every bound) and TerminalUsageCounts (the
│   │                          read-only half a comparator and a screen need)
│   ├── production             production patterns and orders (M11, ADR-024): ProductionEntry, ProductionPattern
│   │                          (3x3 grid → ingredient multiset via fromGrid), SupplyLine (one ingredient an order
│   │                          owes), ProductionOrder / ProductionOrderState (the order state machine),
│   │                          ProductionOrders (a controller's orders), ProduciblePlanner (what an aisle could make);
│   │                          the chain planner (M20, issue #4, ADR-032): ProductionPlanner (the whole chain worked
│   │                          out at the click, or a refusal that names the item), ProductionPlan / PlanNode (the
│   │                          steps in dependency order, root last), PlanBudget (the one availability snapshot),
│   │                          ProductionPlanInput / ProductionPlanResult, PlanLimits (steps, ingredient items — no
│   │                          depth key), PlanRefusal (eight reasons, each naming an item), StationPattern
│   ├── port                   the policy of one warehouse port (M17, issue #12, ADR-029): PortDirection (request /
│   │                          accept, derived from the sign of the rank; since M18 also collect, from one sentinel
│   │                          rank), PortRedstone (on a pulse / while powered /
│   │                          unless powered; its ordinal is the board row, its name the save name) and PortSettings
│   │                          (the signed rank plus the redstone behaviour, and gateOpen; COLLECT_RANK, COLLECT_ROW,
│   │                          directionOf / clampRank, M18, ADR-030)
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
│   │                          (goggle summary by item type), InsertOnlyItemHandler / ExtractOnlyItemHandler (views),
│   │                          ClipboardList (M23, ADR-036: the only place Create's clipboard is read or ticked off)
│   ├── storage                WarehouseInterfaceBlock / BlockEntity, AttachedInventorySummary (storage location);
│   │                          StorageFilterBehaviour (the store filter slot, empty filters not synced; since M16 the
│   │                          same box also carries the storage priority as a hold-to-edit board row, written only
│   │                          while it is not 0, ADR-028),
│   │                          StorageFilterValueBox (its slot on the aisle face) (M8, ADR-021);
│   │                          AttachedInventoryCache (the BlockCapabilityCache lifecycle of one neighbouring
│   │                          inventory, extracted here in M18 and shared with the collecting warehouse port,
│   │                          ADR-030)
│   ├── controller             BranchLayout (world mapping of ONE straight aisle — the M2 AisleLayout, renamed in M21
│   │                          and unchanged in shape) and WarehouseLayout (the whole warehouse: dock, NetworkGeometry
│   │                          and one BranchLayout per branch; it owns candidates(), the corner-ownership rule, the
│   │                          remap through world positions, canDriveTo and the shape's RouteTable), BranchTable (the aisle letters and origin
│   │                          ends a warehouse pins to its rails, keyed by a branch's line, so ordinary building never
│   │                          reshuffles a player's addresses) and NetworkGoggleInfo (the network's size, its aisle
│   │                          list and where the rails stop — absent from the synced tag for a warehouse of one aisle
│   │                          whose rails simply end) (M21, issue #1, ADR-033);
│   │                          WarehouseControllerBlock / BlockEntity,
│   │                          AisleLetterBehaviour, WarehouseRegistry, WarehouseMember / StorageMember,
│   │                          ControllerStatus, ControllerGoggleSummary, AisleAssignment, ControllerPersistence,
│   │                          RequestRejection, RequestResult (M2); CraneDispatch (M3: planning, ledger, reroutes);
│   │                          LocationReservationSummary (bounded goggle data of a location's reservations, M4);
│   │                          WarehouseRegistry.StorageObservation (assignment + reservations in one scan, M4 review);
│   │                          AisleFilters (store filters **and** storage priorities of the aisle's storage locations,
│   │                          cached for planning, both read in one lookup per location, M8 + M16, ADR-028; the name
│   │                          was kept on purpose);
│   │                          AisleStockRules (the controller's own, saved copy of its keepers' stock rules, M15,
│   │                          ADR-027; the pauses of the safety stop live in the controller beside it, and since M20
│   │                          they are armed by every kind of order and are keyed by item alone, ADR-032, next to the
│   │                          derived stoppedStations set that keeps a station's stopped lamp from outliving its aisle);
│   │                          AislePorts (the port policies and filter items of the aisle's warehouse ports, cached for
│   │                          planning and for the continuous pass, not saved, unread until read, M17, ADR-029);
│   │                          AisleCollections (what the inventories behind the aisle's collecting ports held at their
│   │                          last read — a cache of its own, deliberately not the stock index, not saved, M18,
│   │                          ADR-030);
│   │                          optional chunk loading (M19, issue #10, ADR-031): AisleChunkSpan (the aisle's chunk
│   │                          footprint as pure integer maths, no Minecraft imports), ChunkKeepDecision (the pure
│   │                          take / keep / release / refuse / give-up table), ChunkKeepReason (goggle reasons and
│   │                          their lang keys) and AisleChunkTickets (the one NeoForge TicketController
│   │                          `wareworks:aisle`, the per-level hold record, the load-path validation with its testable
│   │                          `validate` seam, the seed watchdog and the queries the command uses) — server only,
│   │                          server thread only, nothing of it saved by the mod
│   ├── crane                  StackerCraneBlock / BlockEntity, WarehouseRailBlock (since M21 four derived cosmetic
│   │   │                      connection booleans and a CLOSED state the topology really reads; the wrench toggles
│   │   │                      CLOSED instead of turning the now cosmetic AXIS), RailNetworkScan (M2 as RailScan,
│   │   │                      renamed in M21: a breadth-first flood from the dock in dock-relative order, another
│   │   │                      dock a wall, never loading a chunk; since M22 the rails may split and close on
│   │   │                      themselves, ADR-033, ADR-035); CraneExecution,
│   │   │                      CranePersistence, CraneGoggleInfo, CraneJobSummary, CranePauseReason, CranePauseDecision
│   │                      (M3; the pause priority became a pure, unit-tested function in M5); CraneSounds
│   │   │                      (server-played crane sounds, M4); MastHeightValueBox (value box on the rail bed, M4 review);
│   │   │                      CraneServerHooks (resets the once-per-server warning state on ServerStartingEvent, M5)
│   │   └── head               HandlingHead (API), HeldItems, InventoryGrabber (MVP impl), TransferContext /
│   │                          TransferContexts (storage interface, input, output; since M18 a fourth kind, the
│   │                          extract-only CollectContext over the inventory behind a collecting port, ADR-030) (M3)
│   ├── station                WarehouseStationBlock / BlockEntity (base), WarehouseInputBlock / BE,
│                              WarehouseOutputBlock / BE, RequestFilterBehaviour, StationBuffer, StationGoggleSummary (M2);
│                              PortRankBehaviour (the warehouse port's direction and rank on a wrench-only value
│                              box) and PortRankValueBox (its position: the filter slot's own faces, because only
│                              one of the two boxes is ever eligible per hand state) (M17, ADR-029; the port's
│                              redstone behaviour rides on the filter slot's board rows in RequestFilterBehaviour);
│                              PortCollectSummary (what a collecting port's goggles say about the inventory behind it:
│                              collected, ready, the attached block and whether it is already a storage location —
│                              all server-only knowledge, written into the goggle packet only for such a port,
│                              M18, ADR-030);
│                              WarehouseDeliveryStationBlockEntity (shared base of the stations a crane delivers into),
│                              WarehouseTerminalBlock / BE, TerminalStockEntry, TerminalStatus (M6, ADR-018);
│                              WarehouseTerminalMenu, TerminalMenuLayout (window geometry both sides need),
│                              TerminalScreenStatus (the bounded status a screen gets) (M6 screen, ADR-019);
│                              TerminalDisplaySide (where the terminal's screen sits relative to its intake port,
│                              M10, ADR-022);
│                              WarehouseProductionBlock / BlockEntity (the station the crane delivers a production
│                              order's ingredients into), ProductionPatterns (its editable 3x3 pattern slots and their
│                              NBT), ProductionGoggleSummary, ProductionMenu, ProductionMenuLayout,
│                              ProductionScreenState (M11, ADR-024); StoppedProduct (one product of this station the
│                              safety stop holds, with its cause and what it cost — the one server answer behind the
│                              station's screen row, its goggle numbers, its STOPPED block state and its resume
│                              message, M20, ADR-032), ProductionStationHooks (the interaction event that makes the
│                              station's sneak-click resume reach the block with an occupied offhand, M20);
│                              StationArmPointType (the arm interaction point type of exactly one station block),
│                              WarehouseInputArmPoint (deposit only), DeliveryStationArmPoint (take only: output,
│                              terminal, production station) (M12, ADR-025);
│                              WarehouseStockKeeperBlock / BlockEntity (the aisle member that holds the stock rules;
│                              no items, no capability, no arm point), StockKeeperRules (its editable rows and their
│                              NBT), StockKeeperGoggleSummary, StockKeeperScreenState, StockKeeperMenu,
│                              StockKeeperMenuLayout (M15, ADR-027);
│                              TerminalRequestOutcome (the third ending of a terminal click: accepted, refused, or
│                              asked about — kept out of RequestResult on purpose) (M15 part 2, ADR-027);
│                              WarehouseHomePointBlock / BlockEntity (the rack position a crane with nothing to do
│                              waits at; no items, no capability, no arm point, no ticker and nothing saved),
│                              HomePointStatus (what the warehouse does with it, and the sentence for every way it can
│                              fail to be used), HomePointGoggleSummary (M21, issue #1, ADR-034);
│                              the clipboard order of a terminal (M23, issue #19, ADR-036): TerminalListPersistence
│                              (its NBT form), TerminalListState (the bounded state the screen and the goggles get),
│                              TerminalListResult / TerminalListOutcome (what a list button press answered);
│                              TerminalPreferences (M24, issue #17, ADR-037: one player's chosen TerminalSort and
│                              their TerminalUsage store, the two counting calls a player's request goes through, and
│                              the versioned compound WareworksAttachments saves them as — no block owns it)
│   └── display                the four Create display link sources a player may read off a Wareworks block
│                              (M14, ADR-026): WarehouseDisplays (shared plumbing: the controller behind a source
│                              block, the row limit), AisleSummaryDisplaySource, StockListDisplaySource,
│                              FilteredStockDisplaySource, CraneStatusDisplaySource. Common code, server side only
├── network                    WareworksNetwork (payload registration), the terminal screen payloads:
│                              TerminalStockPayload, TerminalStatusPayload (server → client),
│                              TerminalRequestPayload (client → server), TerminalResultPayload (M6, ADR-019),
│                              TerminalOrdersPayload (server → client: the aisle's production orders, M11),
│                              and the three production station payloads: ProductionScreenPayload (server → client),
│                              ProductionPatternPayload, ProductionCancelPayload (client → server) (M11, ADR-024),
│                              plus ProductionResumePayload (client → server: lifts the safety stop at this station and
│                              carries nothing but the container id, so the station's own patterns decide what may be
│                              resumed, M20, ADR-032);
│                              the two stock keeper payloads: StockKeeperScreenPayload (server → client),
│                              StockKeeperRulePayload (client → server, moves no item) (M15, ADR-027);
│                              TerminalConfirmPayload (server → client: what a click would cross, and nothing was
│                              requested) (M15 part 2, ADR-027; since M23 it carries the RequestScope, so the screen
│                              knows whether the answer belongs to the click or to the clipboard order);
│                              the three clipboard-order payloads (M23, issue #19, ADR-036): TerminalListPayload and
│                              TerminalListAnswerPayload (server → client), TerminalListActionPayload (client →
│                              server: fetch, answer, resume or cancel; moves no item);
│                              the two sorting payloads (M24, issue #17, ADR-037): TerminalUsagePayload (server →
│                              client: the stored order and one count per item type, without the recency stamps only
│                              eviction needs) and TerminalSortPayload (client → server: the player pressed the
│                              button, read back by name).
│                              Everything else syncs through block entity update packets
├── client
│   ├── gui                    WarehouseTerminalScreen (the terminal's screen) and TerminalScreenUpdates (where the
│   │                          terminal payloads land on the client) (M6, ADR-019); WarehouseProductionScreen (the
│   │                          pattern grid), ClientProductionStations, ProductionScreenUpdates (M11, ADR-024);
│   │                          WarehouseStockKeeperScreen (the rule rows), ClientStockKeepers,
│   │                          StockKeeperScreenUpdates (M15, ADR-027)
│   ├── render                 StackerCraneRenderer (animated crane, SafeBlockEntityRenderer without Flywheel visual),
│   │                          WareworksPartialModels (crane partials), CraneModelLayout (model dimensions, pose math) (M4);
│   │                          WarehouseInterfaceRenderer (Create's filter renderer with the view distance the mod's
│   │                          most mass-placed block needs, M8 review; since M16 it also draws the storage priority
│   │                          digit on the plate, skipped at 0, ADR-028);
│   │                          WarehouseOutputRenderer (the port's filter item plus, for an accepting port, its signed
│   │                          rank on the plate on the back, M17, ADR-029; nothing for a collecting one, which has no
│   │                          rank magnitude, M18)
│   └── ponder                 WareworksPonderPlugin (the one PonderPlugin), WareworksPonderScenes (which scene belongs
│       │                      to which item), WareworksPonderTags (own tag wareworks:warehouse + Create tags),
│       │                      WareworksPonderLang (ponder lang inside the Registrate LANG generator) (M5)
│       └── scenes             PonderAisle (shared stage layout), CraneScript (crane animation through the client pose
│                              API), CraneScenes (stacker_crane/overview), WarehouseScenes (interface, storing,
│                              retrieving, M5; filters, M13), TerminalScenes (terminal, requesting),
│                              ProductionScenes (production, M13; production_chain: a whole chain ordered and run,
│                              registered for the production station and the terminal, M20), StockRuleScenes (stock_rules: the three
│                              numbers; restocking: the minimum ordering by itself and the safety stop) (M15) and
│                              PortScenes (port_requesting: feeding a machine without a clock; port_accepting: an
│                              overflow, also registered for the stock keeper) (M17; port_collecting: the crane
│                              fetching a machine's result, also registered for the production station, M18);
│                              NetworkScenes (warehouse/corner: rails that meet at right angles, the machine's quarter
│                              turn, the corner block's two racks and the wrench that closes a rail, M21;
│                              warehouse/junction: rails splitting, one letter per straight run through every junction,
│                              the machine driving straight over one and turning off at another, all three aisles
│                              delivering into one port and the twin racks beside a junction, M22 — both registered
│                              last on the rail and the crane, so both blocks still show stacker_crane/overview first)
│                              with PonderNetwork, which builds the very WarehouseLayout a controller would hold for
│                              the same rails, asserts its own twin claim against it and writes the rails' connection
│                              flags itself, because a SchematicLevel runs no neighbour updates (M21, ADR-033; M22,
│                              ADR-035)
├── data                       WareworksDatagen (GatherDataEvent hooks), WareworksLangGen (English lang),
│                              WareworksBlockStateGen (the blockstate generators Create's BlockStateGen does not cover:
│                              the terminal's multipart state, M10, ADR-022; the stock keeper's three lamp models over
│                              LIT and PAUSED, with paused winning, M15, ADR-027; the warehouse rail's state over its
│                              four connection booleans and CLOSED — one of six hand-made shapes, turned onto the sides
│                              it is connected on, M21, ADR-033)
├── gametest                   @GameTestHolder classes (WareworksItemGameTests: recipes and creative tab, M4;
│                              CraneSoundGameTests: server-played crane sounds through PlayLevelSoundEvent, M4 review;
│                              RobustnessGameTests: aisle shrink, two aisles, config extremes, M5;
│                              WarehouseTerminalGameTests: terminal block, membership and screen API, M6;
│                              StorageFilterGameTests: one test per Create filter item plus the sorting, full,
│                              re-dedication, persistence and goggle cases, M8;
│                              StoragePriorityGameTests: the storage priority in the world — the crane driving past a
│                              nearer rack, the fallback when the preferred one is full, filters and grouping still
│                              winning, a change mid job, persistence, the cold cache, goggles, aliases and the
│                              clipboard, M16;
│                              ProductionGameTests: patterns, supply jobs, the full loop with the test playing the
│                              player's machine, refusal, cancel, timeout and persistence, M11;
│                              MechanicalArmGameTests: arm interaction point types, their fixed modes and real
│                              powered arms at the stations, M12;
│                              DisplayLinkGameTests: the four display sources, read through real display links on
│                              lecterns, nixie tubes, a display board and a sign, M14;
│                              StockKeeperGameTests, StockRuleEnforcementGameTests, StockRestockGameTests and
│                              TerminalConfirmationGameTests: the stock rules, what they do to a moving warehouse,
│                              automatic restocking with its safety stop, and the terminal's confirmation, M15;
│                              WarehousePortGameTests: the warehouse port's two directions and three redstone
│                              behaviours, its two value boxes and the clipboard, the ranking against storage, a full
│                              port, an old world's output, persistence and the cold cache, M17;
│                              WarehouseCollectGameTests: the port's third direction — fetching out of a chest, a
│                              furnace and a Create depot, the three loop guards, fairness between ports and against
│                              an input, the degraded cases, the production loop closing, persistence, the cold cache
│                              and the poll config, M18;
│                              AisleChunkLoadingGameTests: optional chunk loading — off by default, held while a job
│                              runs, released when idle, released when the controller is broken or replaced, both caps
│                              refused, the give-up bound, a shrinking aisle, the setting switched off mid hold, the
│                              load path driven through AisleChunkTickets.validate, and the collect opt-in; every test
│                              with a per-tick leak probe against NeoForge's own ticket count, M19;
│                              RailNetworkGameTests, RackBranchGameTests, WarehouseNetworkGameTests and
│                              CraneCornerGameTests: the rail network in the world — the connection states and the
│                              wrench, discovery around a bend, the corner block's two racks
│                              resolved by facing, the pinned letters and origins, the remap of records, orders, rules
│                              and the crane's own pose, the goggles' network and stop lines, a real job carried round
│                              a corner, a rail broken behind a driving machine and a save in mid-turn, M21;
│                              since M22 a tee, a cross and a ring are followed as ordinary networks and an aisle
│                              growing past its origin is really renumbered);
│                              WarehouseCombGameTests and SnapshotCadenceGameTests: a comb that is one warehouse,
│                              stored down and emptied out of every aisle into one block, the tee's ownership rule,
│                              the cheaper way round a ring, an aisle a maximum cut loose and reported, a rebuild
│                              under a running job, and the round robin's cycle time measured in a running world
│                              (M22, issue #2, ADR-035);
│                              TerminalListGameTests: a clipboard order worked off, ticked off, asked about, waited
│                              for, swapped and reloaded (M23, issue #19, ADR-036);
│                              TerminalUsageGameTests: what teaches a player's terminal and what may not — an
│                              accepted click counted once whatever amount it asked for, a refusal and the very call
│                              a redstone port makes counting nothing, the cap evicting the weakest entry and not the
│                              favourite, the save-and-load path a world load takes (crafted data included), two
│                              players keeping separate counts and orders, the payload round trip and the attachment
│                              itself (M24, issue #17, ADR-037)
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
│                              RobustnessVisualScenario (chunk unload, save + quit + rejoin, blocks broken mid job; since
│                              M19 also a job that finishes with nobody near the aisle, and the release afterwards)
│                              with SceneItemCensus (item census of the whole scene, M5),
│                              ChunkLoadingVisualScenario (optional chunk loading in a running game, M19, ADR-031: one
│                              aisle far outside the spawn chunks whose work it drives itself, run with the setting off
│                              and on with the player 1405 blocks away, a real quit-and-rejoin restart, the refused
│                              aisle, /wareworks chunks read back out of the chat, the controller broken mid hold and
│                              the setting switched off mid hold - every tick with a leak probe against NeoForge's own
│                              ticket count, every item move bracketed by a census),
│                              DisplayVisualScenario (a wall of display boards and a nixie row fed by real display
│                              links, asserted against the controller's own numbers, M14),
│                              StockKeeperVisualScenario (the keeper block and five rules in five states, its screen
│                              edited through the real payload path), StockRulesVisualScenario (the three numbers of a
│                              stock rule enforced in a real aisle, M15 part 1) and RestockVisualScenario (the warehouse
│                              ordering for itself through a real Mechanical Arm and Mechanical Crafter, the terminal's
│                              three questions and the safety stop, M15 part 2), the last two through ScreenInput (mouse
│                              and key input into an open screen) and GoggleShots (shared goggle-tooltip shots and the
│                              player reach they need);
│                              FiltersVisualScenario (a rack row dedicated by filters, M8) and
│                              PrioritiesVisualScenario (the priority digit on the block and the delivery order it
│                              causes, fed by a real Create belt, M16) and
│                              PortsVisualScenario (the five rows of issue #12's own table in one aisle: an unwired
│                              overflow, a filtered one, a diversion, a pulse and a machine fed through a Mechanical
│                              Press, each phase asserted on the server before the shot that claims it, M17),
│                              CollectVisualScenario (the third direction end to end on a stocked aisle: the crane
│                              reaching through the port, the counter and both goggle tooltips, M18) and
│                              CollectLoopVisualScenario (a real Create factory the mod knows nothing about — an arm
│                              feeding a Mechanical Crafter out of a production station, a collecting port fetching
│                              the planks back, and the maximum, the overflow and the full warehouse in four asserted
│                              acts, M18),
│                              ChainVisualScenario (recursive production end to end in five chapters: the whole chain
│                              planned by one click, a step handed nothing while an earlier one runs, the intermediate
│                              through a real rack, the chain's line and step panel in the terminal, a machine that
│                              swallows the batch, the four surfaces of the safety stop and the click that lifts it,
│                              and the chain run again — with a display board pulled on demand so a board shot cannot
│                              race the crane, M20),
│                              CornerVisualScenario (an L-shaped warehouse in a running game, M21, ADR-033: the rail
│                              models around the bend, the machine photographed at every stage of a quarter turn with
│                              the ticks frozen, a real job carried out of one aisle into the other, the corner block's
│                              two racks, the goggles of controller and dock, and a sound census taken over a real
│                              cogwheel drivetrain, so the turn cue is counted against what masks it),
│                              CombVisualScenario (a warehouse whose rails split, M22, issue #2: a main run with three
│                              side aisles, the machine turning off at a junction and driving straight over one, the
│                              rack at a junction served from the shared block, both halves of the mirror pair beside a
│                              junction, the goggles naming the aisles and their letters, one terminal for the whole
│                              comb, a retrieval across two turns, and the two ways an aisle stops working - a rail
│                              closed with a wrench and aisle.maxAisleLength lowered under the running warehouse),
│                              TerminalVisualScenario (the terminal's own screen, driven through ScreenInput: the
│                              stock list, the search typed character by character, a request and its delivery, the
│                              clipboard order of M23, and since M24 the three sort orders — each shot with the
│                              expected first rows named by the **server**, "most used" asserted equal to the amount
│                              order while the history is empty, every tooltip line measured against the window's row
│                              in English and in German, and the chosen order carried through a real save, quit to
│                              the title screen and rejoin, which is the only automated place that shows it),
│                              CameraView, VisualShotIndex, VisualWatchdog, VisualTestException; inactive unless the
│                              system property wareworks.visualTest is set, referenced only from WareworksClient
└── util                       WareworksLang (runtime LangBuilder helper for goggle/tooltip lines),
                               Headings (the one conversion between core.address.Heading and Minecraft's Direction,
                               M21, so no pure class has to import a world type),
                               GoggleObservers (server-side, per player: notifies the block entity a
                               goggle-wearing player looks at), SyncThrottle (shared goggle sync throttle),
                               LogThrottle (shared rate limit for the storage-interop diagnostics, M5)
```

`interface` is a Java keyword, so the Warehouse Interface lives in `content.storage`.

Naming note (M2, aisle core): the design's `AisleGeometry { dock; facing; length; height }` is split. `core.address.AisleGeometry` holds only the size (pure Java). Its world mapping (dock, facing, rack positions ↔ block positions, bounds) is `content.controller.BranchLayout`, because it needs `BlockPos`/`Direction` (`stacker-crane.md` §3.1). This is the `AisleLayout` that the M1 review removed from `core`; it now lives in `content`.

Naming note (M21, the rail network): `AisleLayout` is now **`BranchLayout`** and maps one *straight* aisle, unchanged in shape — so `WarehouseMember#isAlignedWith` and `alignToAisle` were untouched by the milestone. The warehouse a player builds is `content.controller.WarehouseLayout`: a dock, a `core.address.NetworkGeometry` and one `BranchLayout` per branch. The player-facing word for a straight run of rails stays **aisle**; the code word is **branch**, and a warehouse of one aisle is a network of one branch, so `WarehouseLayout` answers exactly what the single `AisleLayout` answered before (ADR-033). `core.warehouse` correspondingly gained the network's pure maths beside aisle membership, which is why it is no longer only about membership.

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
  * M19 adds `AisleChunkTickets.register(modEventBus)` (the `TicketController`, registered **unconditionally** — an unregistered controller has its saved tickets stripped from the level), `AisleChunkTickets.registerHooks(NeoForge.EVENT_BUS)` and `WareworksCommands.register(NeoForge.EVENT_BUS)` to the event-listener step (ADR-031).

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
* **Chunk ticking**: the crane simulates in the dock block entity, so the dock chunk must be ticking. No chunk tickets in the MVP; documented as a known limitation. *Qualified in M19 (**ADR-031**):* where the crane simulates is unchanged, but a controller may now hold the chunks of its own aisle while the aisle has work — bounded, enumerable through `/wareworks chunks`, and **off in the shipped config**, so the limitation still describes the default configuration exactly.
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
*Extension (M16, storage priorities):* a scenario `priorities` (`./gradlew runVisualTest
-Pwareworks.visualTest=priorities`, `dev.PrioritiesVisualScenario`) does the same for ADR-028, and needs two things the
older screenshot scenarios did not. It runs with `VisualWorldProfile.playable`, because Create draws a goggle tooltip
only for a non-spectator within reach, and it sets the reach back to **0** for the world shots, because a crosshair
inside a value box's 4 px sphere makes Create draw that box's highlight and its checkered face texture even with the GUI
hidden, right over the very digit those shots are about. (Before the M16 review fix it drew a second, smaller copy of the
number there as well, for *any* targeted block — see ADR-028. That half is gone, so the goggle shots, which need the real
reach, now show the digit exactly as a player sees it.) What it asserts on the server before any shot is a **sequence**, not a total: five deliveries into the prioritised
rack past two nearer empty ones, a live simulated insert refusing a sixth, and only then the overflow into the
*farthest* rack of the row because that one is prioritised 1 — plus a dedicated location prioritised 7 that receives
none of the item its filter rejects, a number raised from 0 to 9 between two shots of the same camera, and a retrieval
served out of the cheaper source although the other one is prioritised higher. Both goggle lines are read back from the
client block entity and compared, because a screenshot cannot tell a right number from a wrong one. Two harness lessons
came out of it and are fixed in it: a creative player that touches the ground switches flying off by itself, so a
playable-profile scenario must re-assert flight before every camera group; and `BeltInventory#addItem` queues an
inserted stack for the belt's own next tick, so "everything has arrived" must compare the delivered sum and not only
look at an apparently empty belt.

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
  * *Extended in M17 to twelve scenes* (`warehouse/port_requesting`, `warehouse/port_accepting`), the warehouse port's two directions; the accepting one is registered for the **stock keeper** as well, because a maximum is what makes an overflow useful (ADR-029).
  * *Extended in M18 to thirteen scenes* (`warehouse/port_collecting`), the port's third direction; it is registered for the **production station** as well, because closing the loop — the crane brings the ingredients and takes the product back — is exactly what that block's story needs, and a player who read `warehouse/production` has to find it (ADR-030).
  * *Extended in M20 to fourteen scenes* (`warehouse/production_chain`, "Chains of Production Orders"), recursive production; it is registered for the **production station and the terminal**, because a chain is planned by a click at one and run at the other, and **last** in both cases so each block still opens on the scene it always did (ADR-032). It is a storyboard of its own rather than beats added to `warehouse/production`: inserting a text into a shipped scene renumbers every later `text_n` key of it in both lang files, and the chain needs a second station, a second machine and four crane trips — more than that scene's stage and pacing hold. Its stage is a new 9x7x9 empty aisle in `scripts/gen_ponder_schematics.py`.
  * *Extended in M21 to fifteen scenes* (`warehouse/corner`, "Rails Around a Corner"), rails that bend; registered for the **warehouse rail and the stacker crane**, and last in both cases, so each block still opens on the scene it always did (ADR-033). Its stage is `client.ponder.scenes.PonderNetwork`, which asks the real `WarehouseLayout` for every position and address rather than computing them itself.
  * *Extended in M22 to sixteen scenes* (`warehouse/junction`, "Rails That Split"), rails that split; registered for the **same two components** and last again, so the rail and the crane now show overview → corner → junction. It is a storyboard of its own rather than beats added to `warehouse/corner` for the reason M20 gave: inserting a text into a shipped scene renumbers every later `text_n` key of it in both lang files, and a comb needs a third aisle, a second junction and three crane trips — more than the corner's stage and pacing hold. Its stage is `PonderNetwork#COMB` on the same square nine as the corner's, and `PonderNetwork#requireTwinOf` asserts the ownership claim its closing beats make against the layout itself, so the storyboard fails to compile rather than captioning a picture that stopped being true. Its captions each idle **longer** than they are shown (`NetworkScenes#SAY_IDLE`): `showText` does not block, and a scene whose captions all point at the same junction otherwise draws the next one on top of the one still fading out (found by looking at the shots of the first cut).
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

*Verification:* `runData` runs every storyboard once with `level == null`, which is why storyboard bodies must never touch the level (only instruction callbacks may). The `ponder` visual scenario (`dev.wareworks.dev.PonderVisualScenario`, `./gradlew runVisualTest -Pwareworks.visualTest=ponder`) opens the real Ponder UI per item, asserts the registered scene count and screenshots every scene at three moments; a broken storyboard throws out of `PonderUI.of` and fails the run. *M13:* its `SUBJECTS` list holds the per-item scene counts (crane 1, rail 1, controller 2, interface 3, input 1, output 1, terminal 2, production 1), so a scene registered for the wrong item fails the run rather than being noticed by eye. *M15:* keeper 2. *M17:* output 3. *M18:* output 4 and production 2, which is what pins that the collecting scene really reaches both of its subjects. *M20:* terminal 3 and production 3, which pins the same thing for the chain scene. It is also the only automated check that a storyboard compiles at all, which is why the chain scene was iterated three times against it: the first machine became a Mechanical Saw (two Mechanical Crafters made the rack row six identical brown boxes, because a crafter has to face away from the aisle to be fed from it), the two closing beats were lengthened so the 90 % shot always carries one of them, and the opening was merged into one beat.

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

*Extended by ADR-028 (M16, issue #11):* the same slot carries a second setting, the location's **storage priority**, as
a hold-to-edit board row — and the ranking this ADR made the first sort key gained a fourth one below it. Nothing decided
here is reversed: a priority orders only the locations the filter, consolidation and item-type grouping left equal, and
retrieval still never asks either of the two.

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
  Recursive production is stage 2. *(Shipped in M20, issue #4: **ADR-032**. `ProduciblePlanner` is still exactly this and
  every number it reports is still one level deep; what plans a chain is the click. An order that waits for another order
  is now representable by exactly one field, and a chain is nothing but ordinary orders at the player's own machines.)*
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
  row that really governs the item**, never from a shadowed duplicate. *(Widened in M20 by ADR-032: the first loss of
  **any** order stops that item — a click, a redstone request and a step of a chain included — the pause blocks planning as
  well as ordering, it is forgotten with a deleted rule only when a rule armed it, and it can also be lifted at the
  production station in front of the machine, which is the only place that exists for an item no rule governs.)*
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

### ADR-028 — A storage location's priority is a board row on its filter slot, and the fourth ranking key (M16, issue #11)
*Context:* GitHub issue #11, from play-testing: "a large vault with a cobblestone filter should take the cobblestone
before the general chests do; a rack by the door should fill before the far end of the aisle." Since M8 a player can say
*what* may live in a location (ADR-021); there was no way to say *which* of the suitable locations should fill first. The
planner's own answer is travel time, which is the right default and the wrong one as soon as a player has built a layout
with intent.

*Decision:*
* **The priority is a property of the storage location**, i.e. of the warehouse interface, exactly like its store filter
  (ADR-021). Everything that argument bought stays bought: the number is created, moved, wrenched, broken and saved
  together with the location it describes, and no controller-side mapping can drift from the machine.
* **It applies only when storing.** `PlannerInput#storePriority` is typed `ToIntFunction<? super L>` — a function of the
  **location** and of nothing else — and it is read in exactly one method, `JobPlanner#selectStorage`. The two paths that
  do not store, `planOutOfStorage` (RETRIEVE and SUPPLY) and `selectStation`, pass the constant `NEUTRAL_PRIORITY`
  **literally**. So "a high priority must never send the crane past a nearer location that holds the same item" holds
  **structurally**: there is no code path on which a retrieval could consult a priority, and no future edit can break it
  by forgetting a check. A `BiFunction<L, K, Integer>` like `storeFilter`'s was rejected for the same reason — it would
  have made "the priority cannot depend on the item" a convention instead of a type.
* **It is the fourth sort key, not the first.** The ranking reads: **hard rules** (the store filter, `FilterMatch`) →
  **automatic tidiness** (exact-item consolidation, then item-type grouping) → **explicit player preference** (the
  priority) → **cost** (travel time) → **stability** (index order). A priority therefore orders only what the rules
  above it left equal. It must not outrank the filter, or "dedicated" would stop meaning dedicated (ADR-021 made the
  same argument for `DEDICATED` over consolidation); and it must not outrank grouping, or one prioritised location would
  collect every item type in the warehouse — the exact mixing M3 added grouping to prevent, and which the M8 review
  already had to undo once for deny lists. Travel time is *below* it because a preference that loses to distance is not
  a preference.
* **Default 0, higher wins, and "no priority set" is provably the old behaviour.** `PlannerInput.NO_PRIORITY` (0 for
  every location) is the builder's default, so an input built without a priority is *literally* the input the planner
  received before M16; the new key answers 0 for every pair and `thenComparing` consults the next key exactly then. The
  whole pre-M16 JUnit and GameTest suite is therefore the regression proof, and
  `JobPlannerTest#priorityZeroEverywhereReproducesTheOldOrder` pins it directly: twelve seeded layouts planned with and
  without an all-zero priority function produce the identical job, reasons, cursor and live-call sequence.
* **Where the number lives: a board row on the existing filter slot, not a second value box.** This is the question the
  milestone was opened on, and the answer is forced by three measurements rather than chosen. The aisle face is a 3 px
  brass frame around a plate that is only **6 px** tall (x 3..13, y 3..9), with the crane's arm port directly above it
  (y 9..13, `stacker-crane.md` §7.1). A Create value box is hit-tested within half its scale, so two boxes need their
  centres **8 px** apart — more than the plate is tall. Any second position either reaches into the arm's path, leaves
  the block, or overlaps the first box's sphere, in which case the behaviour that comes earlier in
  `SmartBlockEntity#getAllBehaviours` silently swallows the other. Every other face is already taken, and putting a
  setting on one of them is exactly the ADR-022 mistake M10 had to undo: `FACING` touches the attached inventory, the
  lateral faces carry the row-building placement rule, and top and bottom are the wrench faces and are covered by the
  neighbours in a rack wall. So the priority becomes a **hold-to-edit board row** on the box that is already there,
  which is also Create's most common idiom (funnel, saw, deployer, belt tunnel): a **short click** still sets or clears
  the filter, **holding** the click opens `ValueSettingsScreen` with one row, "Priority", 0..9. A player on a rack wall
  reaches it exactly where they already reach the filter, at any height, with no new face and no new block.
* **`acceptsValueSettings()` becomes a constant `true`** — deliberately **not** Create's own `isCountVisible()`, whose
  `getMaxStackSize() > 1` term would make the board unreachable as soon as a player used a non-stackable item as a plain
  filter. The one behaviour change this brings is that a filter click no longer takes `ValueSettingsInputHandler`'s
  immediate server-side `onShortInteract` branch but goes through Create's client warmup and fires on **release**. That
  is a change in feel, recorded rather than hidden. The M8 fake-player protection is untouched, because
  `ValueSettingsInputHandler` asks `mayInteract` *before* its fake-player branch.
* **Range 0..9, no negative numbers.** `ValueSettingsScreen#getClosestCoordinate` scans board columns from 0, so a
  negative value cannot be picked on a board at all, and an offset encoding would make the saved number differ from the
  shown one. A single digit also keeps the digit on the block one glyph wide. Consequence,
  accepted: **"fill last" is not directly expressible** — a player raises the others instead. The range is 0-based so it
  can be widened later without a save migration, and the value is clamped **on read** (like the crane's mast height), so
  a lowered maximum never rewrites a player's number.
* **The number is readable in the world, without goggles.** `client.render.WarehouseInterfaceRenderer` draws the digit
  on the plate itself, skipped entirely at 0 and cut off at the same `filterItemRenderDistance` Create cuts the filter
  item off at. Anything Create draws for a value box exists only for the block under `mc.hitResult`, so without the
  digit the number would be invisible from two steps away — and the visual harness, whose camera profile has zero
  interaction range, could not photograph it at all. The digit stays inside the plate and never enters the arm port; it
  moves to the plate's upper right while a filter item occupies the middle of the box. Goggles add "Priority: N" on the
  interface and "Prioritised locations: N" on the controller, both only while they apply.
* **It is drawn there and nowhere else** (M16 review fix). The first version also returned the number from
  `getCountLabelForValueBox()`, for parity with a funnel's amount, on the assumption that Create shows that label only
  for a *hit* of the box's 4 px sphere. It does not: `FilteringRenderer#tick` builds the `ItemValueBox` with the label and
  hands it to the `Outliner` **before** its `if (!hit) continue;`, and `ValueBox#render` guards only the outline *icon*
  with `if (!isPassive)` before calling `renderContents` unconditionally — so `passive(!hit)` suppresses the icon, not the
  label. For an empty filter slot, which is the default and the common case for a prioritised location, Create's
  `isEmpty` branch then lands its glyph *inside* the renderer's own digit, at 55 % of its size and with a dark outline
  that shows through the open counters of the big glyph: the number was garbled at exactly the moment a player aims at
  the block to read it. `getCountLabelForValueBox()` is therefore always empty — it cannot simply be dropped, because
  Create's own implementation would put the *filter amount* there — while `isCountVisible()` stays `true`, which is what
  adds the "Hold to set the priority" hover line. The lesson is general and belongs with ADR-013: a Create hook that
  *looks* like it is scoped to the hovered box may be scoped to the targeted **block**, and the difference is only
  visible in `FilteringRenderer`'s statement order.
* **Persistence, sync and the clipboard cost as little as the filter does.** `StorePriority` is written **only while it
  is not 0**, so a pre-M16 world reads back 0 with no migration, an unprioritised interface still syncs nothing at all,
  and a prioritised but **unfiltered** one adds one int (~66 accounting bytes) while still skipping Create's ~215-byte
  filter block (§3.1.1). `writeSafe` carries it into schematics and it costs no `getRequiredItems()`. The clipboard keeps
  Create's `"Filtering"` key so funnel → interface keeps working, but strips Create's generic `Value`/`Row` pair and
  writes `StorePriority` instead: that pair means *extracted amount* to every other filter block, and carrying the
  priority in it would swap a funnel's amount and a location's priority in both directions. Into a clipboard the number is
  written **unconditionally** (M16 review fix): the byte budget is about update tags, while an omitted key in a clipboard
  makes "no priority" unsayable. Create writes its own `Filter` entry unconditionally and pastes an empty stack back as
  "no filter", so a copy of a neutral interface really does clear the target's filter — with the key omitted, that same
  paste would silently keep the target's number, and the ranking input the player believed they had overwritten would
  still decide where the crane stores. A funnel writes no `StorePriority` at all, so a missing key stays an unambiguous
  "not copied from an interface".
* **The controller cache carries both settings.** `AisleFilters` caches filter *and* priority per rack, and the M8
  one-shot resolve of an unread rack reads both in the **same** block entity lookup, so the first plan after a world
  load already uses the right preference. Still one lookup per location *ever*, no new per-tick work and no world
  search. The asymmetry to the filter is deliberate: an unread *filter* defaulting to "no filter" was permanently wrong
  (items entered a chest the player had forbidden, and nothing is ever re-shuffled), so an unresolvable filter counts as
  `REJECTED`; a priority has no forbidding answer, so an unresolvable one counts as 0 and costs only the old
  travel-time order for a few hundred ticks. The dangerous default would be "assume high", which is never taken.

*Reason:* Every alternative to the board row was either unreachable or a lie about the block. A second value box does
not fit on the plate, measured rather than guessed. A wrench scroll would have put an invisible, unlabelled number on a
face that already rotates the block. A controller-side list of preferred locations would need its own UI and its own
rules for a location that moved or broke — the argument ADR-021 already settled. And every alternative to the fourth
ranking key was a rule a player cannot reason about: above the filter it breaks dedication, above grouping it re-creates
the mixed chest, below travel time it does nothing.

*Consequences:* A player can say which of the equally suitable locations fills first, and a vault dedicated to
cobblestone can also be the *first* place cobblestone goes. Retrieval is unchanged and provably so. Nothing is ever
re-shuffled: raising a priority moves no item that is already stored, exactly as changing a filter does not (ADR-021). A
running job keeps its target; only the next trip goes to the new preferred rack. `PlannerInput` gained `storePriority`
and `ControllerGoggleSummary` gained `prioritisedLocations` (a count, so the tag stays bounded, and left out of the tag
while it is 0). `ControllerGoggleSummary.counts(...)` therefore has one more int parameter, which is the hazard its own
javadoc already warns about. The ranking now has a **documented shape** — hard rules, tidiness, preference, cost,
stability — into which issue #12 (the warehouse output as a port) can slot a preference of its own without touching the
other keys. **M17 did exactly that** (ADR-029): an accepting warehouse port became one further key, the *target class*,
above the filter, and a port's own strength reuses this key rather than adding one — so the milestone that ADR-028 was
written to make possible cost the comparator a single key in total. Two names were **not** changed although they now carry both settings: `AisleFilters` and
`WarehouseRegistry.filterChanged`; their javadoc says so, and renaming them is a separate, mechanical change.

### ADR-029 — The warehouse output became the warehouse **port**: one signed rank carries direction and order, on the block that was already there (M17, issue #12)
*Context:* GitHub issue #12, from the user, with a table of the combinations they wanted. Until M17 a warehouse output
could do exactly one thing: hand out what its filter named, once per redstone rising edge. Two things were therefore
impossible. A warehouse could not **give up** items it was not allowed to keep — M15's maximum (ADR-027) simply let the
warehouse input back up, which is correct but leaves the player with a jammed belt and no outlet — and a machine could
only be **fed** by building a redstone clock, which produces one trip per pulse and an unbounded pile of promises. The
milestone's job was to make the output the warehouse's general port: a **direction**, a **rank** and a **redstone
condition**, with the filter it already had.

*Decision:*
* **The output block was extended; no second block was added.** This was the **user's** decision and is recorded here
  because the reasoning is not obvious. A separate "warehouse export" block would have doubled the surface for one
  difference in behaviour: the same buffer, the same extract-only capability, the same M12 arm point, the same
  membership, the same drops, the same goggle header, the same display source, the same recipe slot in the creative tab —
  and a player would have had to decide *before placing* which of two nearly identical blocks a rack position should
  hold, then break and replace it to change their mind. It would also have needed a new `LocationKind`, which reaches
  `RackProbe`, membership, persistence, the arm points and `TransferContexts`. Extending the block instead means an
  existing world keeps working by construction, one recipe and one Ponder subject cover both directions, and switching a
  port around is a wrench click rather than a rebuild. The cost, accepted: the block's **name** no longer describes
  everything it does. It is still "Warehouse Output" and still `wareworks:warehouse_output`, because renaming a
  registered block is a user-visible change nobody asked for; the docs, the tooltip and the goggles call it a *port*.
* **One signed number is the direction.** `core.port.PortSettings(rank, redstone)`: `rank == 0` requests, `rank < 0` is
  an **overflow** (every storage location wins over it), `rank > 0` a **diversion** (it wins over every storage
  location). `PortDirection` is *derived* from the sign, not stored, so "what the port does" and "where it ranks among
  the others" can never disagree — the failure mode of a separate direction flag plus a priority. The magnitude is offset
  by one (`±(v + 1)`), which makes every accepting rank non-zero: `0` then means "requests" **and** "is not a store
  target" **and** "the class storage itself sits at" with no extra flag, and the board's default magnitude 0 is already a
  usable overflow (`−1`) rather than a neutral that would tie with storage. The magnitude keeps M16's range `0..9` and
  its milestones, so "priority" means one thing across the mod and one digit stays one glyph wide.
* **Two value boxes on the same faces, exactly one eligible per hand state.** M16 had to prove that a second box does not
  fit on a warehouse interface's 6 px plate (ADR-028). The output's faces are less crowded — its plate is y 5.5..13 px —
  but the answer here is better than measuring: the port box is `onlyVisibleWithWrench()` and the filter slot's
  `mayInteract` **refuses Create's wrench**, so Create's input handler and both renderers skip exactly one of them at any
  moment. Nothing has to be measured, the filter slot does not move a pixel, and the rule is one sentence a player can
  hold: *hold the Wrench to configure the port, anything else to set the filter.* It is Create's own **item** and not the
  `c:tools/wrench` tag, because Create splits the two predicates itself — `ScrollValueRenderer` draws a `needsWrench` box
  only for `AllItems.WRENCH` while `ValueSettingsInputHandler` accepts the tag — so matching the tag here would leave a
  player holding another mod's wrench with **no** box drawn at all. Both boxes refuse a `FakePlayer`,
  because Create's input handler skips its 4 px hit test for one and exporting items is irreversible.
* **The redstone behaviour goes on the board's free row axis.** M2 dropped Create's "Exactly" row from this slot (the
  controller always clamps to the available stock), so the rows had been unused ever since. Create's own idiom for a
  board whose rows are a *unit* and whose column is a *value* is the brass diode, and that is what this is: row =
  when the port acts, column = the requested amount. `upTo` stays `true` for ever, so the amount path, M7's merge cap
  and `maxRequestAmount()` are untouched, and in the accepting direction the column is simply not read (every cell shows
  an em dash) instead of being removed — `ValueSettingsScreen` divides by `board.maxValue()` for its click sound.
* **The ranking gained exactly one key**, which is the shape ADR-028 promised: **target class** → store filter →
  consolidation → item-type grouping → priority *or* a port's strength → travel time → index order. The class is the
  first key because "a diversion takes items before they are stored" and "storage always wins over an overflow" are
  statements about *which kind of place* the items go to and must hold against every other rule — a diversion beats even
  a `DEDICATED` location, an overflow loses to every location that may take the item. Three values, with storage in the
  middle: `0` diversion, `1` storage, `2` overflow. Every path that does not store passes the storage class
  **literally**, so the key answers 0 for every pair there and the comparator is the function it was before M17.
* **The content layer applies the whole policy; the planner only ranks.** `PlannerInput#ports` is the list of ports that
  will take items *now* — direction, redstone gate, pulse token and availability already applied — and `#portRank` their
  signed rank. So "off means off" is a property of the list, `JobPlanner` contains no redstone, and the two defaults
  (`List.of()` and `NO_PORT_RANK`) make an input built without ports *literally* the pre-M17 input. A rank of `0` drops
  a candidate inside the planner as well, because that is also what a controller answers for a port it could not read:
  **"not read yet" must never mean "assume it accepts"**, since an export cannot be undone. That asymmetry is the same
  one ADR-028 recorded for filters and priorities, with the dangerous default on the other side.
* **A stock rule's maximum is what makes an overflow necessary, so it may not gate the ports.** A candidate carries its
  own limit: `portLimit` is what one trip can carry, `storageLimit` is that bounded by the headroom. A key at its maximum
  contributes **no storage candidate at all** — no estimate, no live call, no remembered refusal, exactly as before M17 —
  and still reaches the ports. This is the interaction the feature exists for, and it is why #12 needed #3 first.
* **A port candidate consults neither the capacity estimate nor the refusal memory.** A station buffer has no index
  snapshot, so an estimate could only answer "unknown"; and a port has no snapshot round robin that would ever forget a
  remembered refusal, so a refusal entry would ignore the port long after a funnel emptied it. The reservation is still
  subtracted like for every candidate. The cost is therefore one live call per *offered* port per key per run, and
  `JobPlanner.MAX_PORT_CANDIDATES` (12) of them at most: "the handful of ports an aisle has" is not something a player is
  bound by — an aisle has up to `maxAisleLength × maxMastHeight × 2` rack positions against a live budget of 64 — so the
  bound is **enforced** instead of assumed. Above the cap `collectPorts` ranks the port candidates with the same
  comparator and offers only the best, which drops the weakest ports rather than an arbitrary window, and dropping a port
  is the safe direction anyway: the items go to storage or back up instead of leaving. At or below the cap nothing is
  ranked and nothing changes. A port whose filter rejects the key is dropped before the live call, so a rack wall of
  filtered ports costs nothing at all, exactly as a partitioned warehouse does not (ADR-021).
* **No new location kind and no new job type.** A store into a port is a `STORE` job with a `LocationKind.OUTPUT`
  target (`TransportJob.storeToPort`), so nothing downstream had to learn a new concept: nobody asked for the items, they
  are never counted as stored, and the crane uses the delivery context it already uses for a retrieve. `JobType`'s
  allowed targets became a **set** (`STORE = {STORAGE, INPUT, OUTPUT}`) instead of two named kinds.
* **Such a job reserves capacity, not transit.** `TRANSIT` means "items that have left the indexed stock and are owed to
  whoever asked for them", so the ledger's delivery-target branch excludes a `STORE` however delivery-like its target
  is. What a store job holds at a port is **room**, which is also what stops two jobs planning into the same port slots.
* **Ports are the last reroute stage, and only for store leftovers.** Storage, then input buffers, then an accepting
  port — last, whatever its rank, because putting items back into an input is reversible and exporting them is not, and
  it converges anyway (the next store plan offers them to the port again). Retrieve and supply leftovers **never** reach
  a port, in both layers: the planner skips them and `CraneDispatch#rerouteOutputs` keeps them out of the output
  list altogether, so a port that is currently gated *shut* is not a retrieve-reroute target either. A store reroute that
  does land in a port spends its **pulse token**, decided from the kind of the target that was *chosen*: the job still
  names the target that failed, because the crane's state machine re-targets it only when the answer comes back. A player can therefore reason
  that whatever comes out of a port is surplus, and the mod never quietly feeds a shredder with items somebody
  requested.
* **The crane never waits at a full store target.** `CraneExecution`'s "wait at a full delivery target" pre-check is
  scoped to jobs that are not a `STORE`: waiting is right when somebody is waiting for the items and wrong for a store
  into an overflow, where the crane would park in front of a full port and block the aisle. Scoped by the **type**
  rather than by "has a request id", so every `RETRIEVE` and `SUPPLY` is byte for byte as it was.
* **`NoJobReason.PORT_FULL` is declared before `AT_MAXIMUM` and does arm the back-off.** A maximum is not a fault; an
  overflow that cannot get rid of its items is the thing to go and fix, so it is the more specific and the more
  actionable answer. And unlike a maximum it is reached only after a full candidate walk with an estimate and a live
  simulation per candidate — exactly the work `fullBackoffTicks` exists to protect (the M15 argument, turned around
  because the cost is the other way round).
* **The direction is readable in the world, from both sides, without goggles.** From **inside the aisle** — where no
  value box and no drawn digit may go, because the crane's arm port owns that face — an accepting port turns
  **andesite** around the aisle opening, and the same accent on the back spout: ADR-017's material language (andesite =
  the dumb intake,
  brass = the smart filtered output), and "whatever the warehouse cannot keep" is the dumb direction. A second hand-made
  model `block_accept.json` differs from `block.json` in exactly that one texture, selected by the derived blockstate
  property `accepting`, which the block entity re-asserts from the rank on every change and once on load (so a
  `/setblock` with the wrong value is corrected rather than believed) and writes with `UPDATE_CLIENTS` alone — a
  direction is something a player reads, not something a neighbour reacts to (the stock keeper's lamp argument). From
  **outside**, where the wiring is, `WarehouseOutputRenderer` paints the signed rank on the back plate, for accepting
  ports only: the ADR-028 lesson that anything Create draws for a value box exists only for the block under the
  crosshair.
* **Every surface says the same thing.** The port's goggles name the direction, the rank, what it accepts, its redstone
  behaviour and how many items it has handed over; the controller counts "Accepting ports: N"; the aisle display gains
  "Ports: N accepting"; and the crane says **"Handing over"** instead of "Storing" while it carries items into a port,
  which needed `CraneJobSummary` to carry its `targetKind` in the client packet, because the job type alone cannot tell
  a store from an export. Two Ponder scenes teach the two directions, and the accepting one is registered for the
  **stock keeper** as well, because a maximum that makes an input back up on purpose is exactly what it answers.
* **Four save keys, every one of them conditional.** `PortRank` (only while it is not 0, under the behaviour's own key
  instead of Create's generic `ScrollValue`), `RedstoneMode` (only while it is not `PULSE`), `PortArmed` (only while an
  accepting pulse port holds an unused edge) and `PortExported` (only while it is above 0). An unconfigured port
  therefore writes **none** of them, a pre-M17 output's tag stays byte for byte its own, a missing key reads as the
  default, and no migration exists. The clipboard keeps Create's `Value` (so a port still sets a funnel's amount), forces
  `Row` to the "up to" row — it means "exactly" to every funnel and the redstone behaviour here — and writes the mode
  and the rank **unconditionally** under keys of their own, because in a clipboard an omitted key makes a default
  unsayable and a paste of a plain port could then never undo a configured one (the M16 clipboard lesson).

*Reason:* Every alternative was either a second concept or a lie about the block. A separate export block doubled the
surface and forced a decision before placement, for one difference in behaviour (the user's own argument, above). A
direction flag *plus* a priority could disagree with itself, and the planner would have had to read two functions where
one is enough. A controller-side list of "where surplus goes" would need its own UI and its own rules for a port that
moved or broke — the argument ADR-021 settled once and ADR-028 settled again. A new `LocationKind` would have reached
five subsystems for a station that behaves exactly like an output in all of them. And every alternative to the target
class as the *first* ranking key was a rule a player cannot reason about: below the filter, "storage always wins over an
overflow" would break the moment somebody dedicated a port; below grouping or the priority, a diversion would stop being
a diversion as soon as a rack happened to hold the item already.

*Consequences:* A warehouse can now **route** instead of only storing: everything arrives at one input and the system
decides whether it goes into a rack, straight on to a machine, or back out as surplus — with the crane doing the
carrying, so a player can always see why a chest is full. A stock rule's maximum became useful rather than merely
correct: the surplus leaves instead of jamming the belt. A machine can be fed with no clock and with at most one open
request in flight. Nothing is destroyed and nothing teleports: a full port backs up like a full warehouse, and what
stands in a port is station buffer — never re-stored, never counted as stock, and never fetched back, which is
structural because retrieval only ever iterates the stock index. `PlannerInput` gained two components and
`ControllerGoggleSummary` one more int parameter, the hazard its own javadoc warns about. The ranking key is one `int`
with storage at the **middle** value, so a further class — a collecting source (issue #13) — slots in without touching
any other key, and `AislePorts` already caches a policy plus a filter per member and gates it per tick. Two risks are
recorded rather than guarded: a **diversion** can swallow what a rule's minimum needs (which is precisely what
"everything incoming is diverted out" asks for, and ADR-027's safety stop bounds the restocking side of it), and a
player who pipes a port's chest back into an input makes an item at its maximum churn for ever (undetectable — the mod
cannot see belts — bounded, because retrievals and supplies are planned before stores, and readable on the port's
"Handed over: N").

### ADR-030 — Collecting is the port's third direction: one sentinel rank, one job type, and three structural loop guards (M18, issue #13)

*Context:* GitHub issue #13, from the user, and the last step of the routing picture ADR-029 sketched. Until M18 items
entered a warehouse only when something **pushed** them into a warehouse input, so every machine needed its own belt or
funnel run back to the aisle — the return path a player has to build again for each machine, and the one part of the
production loop (ADR-024) the warehouse did not carry. The milestone's job was the opposite direction: the warehouse
**fetches** out of an inventory a player points a port at, with the same filter, the same redstone condition and the
same crane.

*Decision:*
* **The third direction of the M17 port, not a fourth block and not a new `LocationKind`.** The same argument as
  ADR-029, one milestone later and now with evidence: the port already has the three settings collecting needs, and a
  collecting port is a member of the aisle the crane reaches like any other. It is the same block, the same buffer
  (unused in this direction), the same arm point, the same recipe, the same membership, the same Ponder subject. The
  cost, accepted: a player must set the direction with a wrench before a port collects, and "Warehouse Output" now names
  a block that also takes items *in* — the same naming debt M17 took on knowingly.
* **The rank carries it as a sentinel, `COLLECT_RANK = MAX_RANK + 1`, not as a band.** A collecting port is only ever a
  **source**, so it needs no magnitude: there is no candidate list it competes in. Ordering against other work is
  *where the arrival stage sits in dispatch*, and ordering among several collecting ports is a **round robin**, because
  a priority there would starve the weaker ports — exactly the reason input stations have never had one. The sentinel
  keeps "which direction" and "in which order" one number (`direction()` stays derived), adds a fourth board row whose
  column means nothing (an em dash, like the request row), and **cannot appear in an old save**, because the pre-M18
  constructor clamped to ±`MAX_RANK`. One real trap had to be paid for: Create's `ScrollValueBehaviour#setValue` clamps
  to the behaviour's own range, so `PortRankBehaviour` had to be widened to the sentinel — otherwise a collecting port
  would silently have become the strongest diversion. No input path reaches `setValue` unguarded: `ValueSettingsPacket`
  only calls `setValueSettings`, which composes the rank from row and column, and `setRank` clamps with
  `PortSettings.clampRank` first (M18 review), so an out-of-band number lands in the accept band exactly as it does when
  it is read from a save.
* **A job type of its own, `COLLECT`, whose allowed targets exclude `OUTPUT`.** The alternative — a `STORE` job with an
  `OUTPUT` source — would have made the leftovers of a collect droppable where the leftovers of a store may go, which is
  precisely what `SUPPLY` was split off from `RETRIEVE` for (ADR-024). It also made two questions that were asked as
  `type != STORE` wrong in both places, so they became the positive `bringsItemsIn()` (a `STORE` and a `COLLECT`: the
  arrivals that may complete a production order) and `waitsAtAFullTarget()` (a `RETRIEVE` and a `SUPPLY`: waiting is
  right when somebody is waiting for the items).
* **A player's request always wins.** Collecting is planned **after** the retrieval requests and the production supplies
  and **inside** the store stage, which became the **arrival** stage: one round robin from one cursor over the input
  stations followed by the collecting ports, as a single virtual list. A stage of its own after storing was rejected: it
  would starve collecting for as long as any input is permanently non-empty, which is exactly the situation collecting
  exists for. The mirror consequence is accepted — an input can be delayed one trip behind a collecting port, which is
  how two inputs already treat each other — and the walk is bounded by `MAX_COLLECT_CANDIDATES` (12, as
  `MAX_PORT_CANDIDATES`), with the cursor left at the first source a run had to skip so that nothing starves.
* **The loop is answered structurally, three times over**, rather than by a rule that says "do not do that":
  `planCollect` honours `storeHeadroom`, so an item at its maximum yields **no collect job at all** — the exact opposite
  of an accepting port, which ignores the headroom, so collecting and overflow are mutually exclusive by construction;
  `JobType.COLLECT` allows no `OUTPUT` target and `TransferContexts.CollectContext` refuses **every** insert, so a
  collected item can never leave through a port and nothing the warehouse carries can ever be pushed into a player's
  machine; and a port pointed at an inventory this aisle already indexes (same Create `InventoryIdentifier`) is refused
  and says so on its goggles, which is what stops the crane shuffling one chest around its own aisle. Each guard was
  broken on purpose during the build, and each one has tests that failed while it was broken.
* **Collected items are not stock until they are stored.** The reads live in `AisleCollections`, a cache of its own;
  putting a machine's result chest into the stock index would make a terminal offer items the crane has not fetched yet
  and a stock rule count them towards its maximum. Nothing of it is persisted, and an unread port collects nothing.
* **Presence without polling, contents on demand.** The capability cache (`AttachedInventoryCache`, extracted from the
  warehouse interface so that the two blocks share one lifecycle instead of two copies of it) answers whether an
  inventory is there; the port block's neighbour hints queue an urgent read through the channel the interface has always
  used; and a **background poll** every `collectPollIntervalTicks` (default 20) covers the machines that change their
  inventory without telling anybody — a furnace's result slot, several Create blocks. Both queues share the one
  `maxSnapshotsPerTick` budget, so the per-tick cost bound of the stock index is unchanged, and a gated-shut port is not
  read at all. A per-tick scan was never an option: the hard rules forbid it.
* **Extraction goes through the item capability only**, slot by slot with real results, spilling at the **port's**
  position and never inside the machine. The crane never inserts there, so a Create machine feeding the same inventory
  is never fought over.

*Review addendum (M18 review, same milestone):* four decisions the first build got wrong, each fixed at the root rather
than papered over:
* **A collecting port that hands nothing over is not a full warehouse.** `planCollect`'s two cheap fall-throughs — the
  port's own filter rejecting a key, and a live extract that gives nothing — left the survey untouched, so the run
  defaulted to `WAREHOUSE_FULL`: the strongest reason of all, which masked every honest answer of the same run *and*
  armed `fullBackoffTicks`, suspending storing from **every** input station of the aisle for as long as a machine was
  empty — the resting state of a production loop. They now report `NoJobReason.COLLECT_SOURCE_EMPTY`
  (`StoreSurvey#collectReason()`), declared last but one and, like `AT_MAXIMUM`, kept out of the back-off set with the
  M15 argument: it is answered by a map lookup and at most one live extract per item type, before any candidate is
  ranked, so there is no expensive scan to protect.
* **The port's filter belongs in the gate, not only in the plan.** `collectSources()` applies it now (one map lookup,
  because a port's filter is a single item key), so a port whose machine holds only items it does not name is no source
  at all instead of a candidate that spends a live simulation on a certain refusal every dispatch — and the same
  filtered number is what its "Ready: N" line shows, which could otherwise contradict the "Collects: X" line above it.
* **The refusal is carried to the port a player is standing at.** A collecting port could explain the two mistakes it
  can see by itself but not the one a player hits most often — full, at a maximum, no storage filter — because that
  lives on the controller. `PortCollectSummary` now carries the aisle's last planning reason, narrowed by
  `NoJobReason#refusesCollecting()` and only while the port has something ready, and the goggles print it in gold. This
  is the "why is this not moving?" idea of the M15 review, scoped to the one block where the answer is unambiguous.
* **Cost and fairness details:** the gated source list is built **once** per dispatch instead of twice (it costs world
  lookups per port); the content hint at the block is gated on the `collecting` block state, so an ordinary requesting
  port's neighbour no longer walks a hint into every controller of the level on every `setChanged`; the cap's cursor is
  honoured on every return of the arrival walk, so an input station with permanent work can no longer keep the sources
  beyond the cap from ever being examined; a collect pick queues the re-read whatever it picked, and for every port
  reading the same inventory, rather than trusting a foreign block to notify anybody; the crane's stop check asks for
  the machine's chunk of a collect source as it does for a storage location's inventory, derived from the job instead of
  the location kind; and `PortRankBehaviour#setRank` clamps with `PortSettings.clampRank` before Create's `setValue`
  sees the number, so the widened range can no longer turn an out-of-band rank into a collecting port.

*Reason:* This is the smallest teachable thing that closes the loop. Everything a player already learned about the port
carries over — filter, redstone row, wrench box, clipboard — and the one new idea is the direction itself, which the
copper accent says from inside the aisle and one Ponder scene teaches. Everything else is a consequence rather than a
feature: no new block, no new location kind, no new comparator key, no migration.

*Correction to ADR-029:* that decision predicted a "further ranking class — a collecting source (issue #13)". It was
wrong in a useful way: M18 adds **zero** comparator keys, because a collecting port is never a *target*, so it never
enters the store plan's candidate list at all. What it needed instead was a place in the **dispatch order**, which cost
no key and one cursor.

*Consequences:* A machine's result chest needs no return path, and the warehouse's own restocking (ADR-027) closes
without a belt: a collect arrival credits a production order exactly as a store from an input does. `PlannerInput` gained
two more components and `ControllerGoggleSummary` one more `int` parameter — the hazard its javadoc warns about, now one
parameter worse. `AislePorts#rankAt` had to start answering 0 for a collecting port and `acceptingPorts()` had to select
by direction instead of `!isRequesting()`: with a third direction the old predicate would have made every collecting
port an export target, the one regression of this milestone that could have destroyed a player's setup. Two behaviours
are documented rather than guarded: an **unfiltered** collecting port takes whatever the queried side hands out (a
furnace offers its fuel slot to a horizontal face, which the GameTest pins rather than hides), and one loop remains
bounded rather than impossible — collect → leftovers into an input → a diversion exports them into the same chest —
which needs a diversion *and* a collecting port on one aisle with the drain wired back, moves one trip at a time, and is
readable on two counters. A collect can lag by up to `collectPollIntervalTicks + dispatchIntervalTicks` behind a silent
machine, which is the price of not scanning.

### ADR-031 — Chunk tickets are tied to an aisle's **work**, not to its existence, and the whole feature is off by default (M19, issue #10)

*Context:* GitHub issue #10, from the user, and the oldest known limitation of the mod: ADR-013 decided the crane
simulates inside the dock's block entity, so a dock in a chunk that does not tick does not run at all. That was
defensible for three milestones, because a warehouse only ever acted on a player's request — a redstone pulse, a click
in a terminal, items pushed into an input — and nobody was standing there to be kept waiting. **M15** (a warehouse that
restocks its own minimums), **M17** (a warehouse that hands surplus out) and **M18** (a warehouse that fetches out of a
machine) each added behaviour that runs *without* a player, and therefore stops the moment the player walks away. Three
features that only work while somebody watches them are three features that do not work.

The obvious implementation — a chunk loader block, or a ticket per aisle for as long as the aisle exists — is the thing
server operators have learned to distrust, and rightly: a ticket that outlives its owner is a chunk nobody can find and
nobody can free.

*Decision:*
* **The ticket is tied to work, never to existence.** An aisle takes tickets when it has a crane job, an open retrieval
  request or an open production order (an automatic restock order is one of those), and it releases them as soon as it
  is idle. This is the decision the whole design hangs from, and it is what makes every other bound checkable: a hold
  has an end condition that the mod's own state already knows, so nothing has to be reaped, timed out or reference
  counted. The alternative, "an aisle holds while it exists", turns every built warehouse into a permanent loader and
  makes the cap the only thing standing between a player and a stalled server.
  * **Buffered items in a warehouse input are deliberately not work.** A buffer cannot change while its own chunk does
    not tick, so it can never *appear* while the aisle is unloaded, and the moment it is planned it becomes a crane job.
    As a hold reason it would let one forgotten item hold four chunks for ever — exactly the failure the first bullet
    exists to prevent.
* **Off by default (`chunkLoading.maxTicketedAislesPerLevel = 0`), and documented as what it is.** A chunk loader is a
  server-wide performance decision and a Wareworks player did not ask for one by placing a controller. Off by default
  means a server that does not want this **pays nothing** — no ticket, no listener body, no scheduled re-check, one
  boolean and one long compare per controller tick — and it means the shipped behaviour of every existing world is
  byte-for-byte what it was. The same integer is the master switch *and* the bound (the "0 means off" shape
  `maxRestockOrders` already uses), so an operator cannot switch it on without choosing a number. The config comment
  says "THIS IS A CHUNK LOADER" in as many words, names what a held chunk does and does not tick, and says which
  settings turn a bound off.
* **Bounded three ways, and a refusal is all or nothing.** `maxTicketedAislesPerLevel` per dimension,
  `maxChunksPerAisle` per aisle, `maxHoldTicks` per hold. An aisle over a cap holds **nothing** — a partial hold would
  load the controller's chunk and leave the far end of the aisle away, so the crane would run and pause at every stop
  it cannot reach, which is worse than not holding. `maxHoldTicks` with a re-arm on a changed **work fingerprint** (the
  crane job id plus the open request and order ids) is what makes "never a permanent loader" true rather than
  aspirational: an unservable request, or a crane stuck in `HOLDING` with items nothing accepts, is bounded by the clock
  and then refused until something really changes. `releaseDelayTicks` is the fourth number and the only one that is
  not a cap: a linger, so a burst of jobs cannot make the tickets thrash.
  * **A cap bounds a hold that exists, not only one being taken** (M19 review). Checking it at take time alone made two
    bounds nominal: a cap lowered while an aisle held changed nothing, and an aisle extended while it held grew its hold
    past `maxChunksPerAisle` with no refusal and no line in the log. The decision table now releases in both cases, with
    the cap's own reason. A holder counts itself out of the level cap, so a level merely *at* its cap never evicts its own
    holders — only a genuinely lowered one does.
  * **Every cap reports.** The collect opt-in's own cap got a reason of its own (`AT_COLLECT_LIMIT`), because an aisle
    queued behind it was byte-for-byte an idle aisle: no goggle line, no log line, no row in `/wareworks chunks`. A
    setting that changes what an aisle does and says nothing is invisible to the operator who set it (M19 review).
  * **The give-up bound is saved** (`ChunkKeep`), the one thing this feature writes to disk. The work it refuses is
    saved, so a bound that lived only as long as one block entity instance would let every reload and every restart take
    the whole footprint again for another `maxHoldTicks`, for work that had already proved unservable. An aisle that
    becomes idle drops the flag, which is both the escape hatch and the reason a saved flag can never strand an aisle
    (M19 review).
* **A non-ticking block ticket, and the reason is in the sources.** `TicketController#forceChunk(..., ticking = false)`
  adds a region ticket at distance 2, i.e. chunk level 31 (`ENTITY_TICKING_LEVEL`), in both trackers. Block entities,
  entities and scheduled block ticks all run at that level (`Level#tickBlockEntities` →
  `DistanceManager#inBlockTickingRange`), while the `ticking` flag feeds **only** `shouldForceTicks`, which gates the one
  branch of `ServerChunkCache#tickChunks` that does inhabited time, natural mob spawning and `Level#tickChunk` (random
  ticks). So a held chunk runs the crane, the controller and the player's own furnaces, funnels and belts, and grows no
  crops and spawns no mobs. `ticking = true` would have shipped a farm loader behind a warehouse setting. Every step of
  that chain was read in `build/api-src` before the first line was written, because the feature is worthless if the
  assumption is wrong and dangerous if it is wrong in the other direction.
* **The safety lives in the load path, not in a release on shutdown.** NeoForge persists these tickets whether the mod
  wants it or not, and that persistence *is* the feature — a restart must not throw away an in-progress job. Releasing
  on the way down could not work anyway: `saveAllChunks` runs before `LevelEvent.Unload`, and a crash writes nothing at
  all. So the validation callback keeps exactly **one seed chunk** per owner (the owner's own) and drops everything
  else, or drops everything while the feature is off; loading that one chunk loads the controller, whose first tick
  either grows the hold to the full footprint or releases the seed. A **watchdog** covers the case the callback
  structurally cannot see — a controller removed while the server was down — by releasing, with a `WARN`, any seed no
  controller claimed within 100 ticks. `remove()` releases in the same tick; `invalidate()` releases unless the server
  or level is going down. The ticket controller is registered **unconditionally**, whatever the config says, because an
  unregistered controller has its saved tickets stripped from the level — which would silently destroy the in-progress
  work of a server that turned the setting off for one start.
* **Event-driven, and only the tick may touch NeoForge.** Every place that changes what an aisle has to do sets one
  boolean (`chunkKeepDirty`); `WarehouseControllerBlockEntity#tick()` is the only caller of the chunk API. This is not
  style: forcing a chunk loads its block entities synchronously, so taking a ticket from `onLoad()` would re-enter the
  fresh-block-entity pass of `Level#tickBlockEntities`. Taking is additionally capped at two chunks per tick for the
  same reason. Beyond the hooks a **holding** aisle re-decides at most every 20 ticks — the bounded safety net that
  makes a changed config, a freed level slot and an unhinted work change take effect, and that keeps the release from
  depending on every single hook being perfect. An aisle that holds nothing schedules nothing.
* **The operator command is part of the feature, not a convenience.** `/wareworks chunks` (permission 2, as
  `/forceload`) lists every holding aisle per dimension with its reason and age, the totals, and the dimension's whole
  block-ticket count next to its whole force-loaded count (three independent stores in `ForcedChunksSavedData`, so the
  first is not a total and the line must not claim it is — M19 review), and can release one aisle in the sender's
  dimension, as vanilla `/forceload` scopes its own subcommands, or all of them in every dimension, so the scope of the
  word `all` matches the scope of the listing it answers. `release all` also tells the aisles a cap had refused to give
  up, because otherwise the freed slots went to the next aisles in the queue within a tick or two. It exists because
  nothing else can answer
  the question: `/forceload query` reads only the level's own vanilla set, and a mod's tickets live in a tracker whose
  owner type is package-private, so outside NeoForge's validation callback nothing can name their owners. An operator
  who cannot enumerate a mod's tickets cannot tell a working feature from a leak — and the same gap is why the
  GameTests' leak probe compares the mod's own union against the level's block-ticket **count** instead of a per-owner
  delta.
* **The pause path stays, untouched.** `CranePauseReason` is unchanged, `CranePauseDecision` is unchanged, and
  `CHUNK_NOT_LOADED` is still what carries a switched-off server, a refused aisle, an aisle that gave up, and the ticks
  before a hold is complete (`stacker-crane.md` §4.2). Optional chunk loading is a controller feature; the crane knows
  nothing about tickets, which is why no crane test had to change.

*Reason:* The feature a server operator can trust is the one whose worst case they can state. Here it is one sentence:
at most `maxTicketedAislesPerLevel` aisles per dimension, at most `maxChunksPerAisle` chunks each, at most
`maxHoldTicks` at a time, only while the mod's own records say there is work, released in the same tick the owner
disappears, enumerable and killable from the console, and switched off unless somebody turned it on. Nothing in that
sentence depends on a player noticing anything.

*Consequences:* A `command` package exists for the first time, and one class in it is load-bearing rather than
diagnostic. The controller gained one saved NBT key (`ChunkKeep`), so "M19 writes nothing to disk" — true of the first
implementation — is no longer true; the hold itself is still derived state. `ControllerGoggleSummary` gained a reason and one `int` — the hazard its own javadoc warns about, now two
parameters worse than M18 left it — and the number means "held" while holding and "would be needed" while refused
rather than being two fields, which is what keeps the synced tag bounded. `GAVE_UP` conflates the timeout and an
operator's release: the goggle text was rewritten to be true of both, and the cause is left to the log and to the
command's answer, so an aisle never claims a cause that did not happen (found by the `chunks` visual scenario, which
photographed the earlier wording). `maxChunksPerAisle` was 8 while a warehouse was always one straight aisle, which is
exactly the worst case of `aisle.maxAisleLength = 32`, so a player who raised the length cap got `TOO_MANY_CHUNKS`; the
goggle line names both numbers and the config comment carries the table, but the two keys are not linked in code. **Since
M21 the number no longer follows from the length cap at all** (ADR-033): a corner turns one long rectangle into two
shorter ones at right angles, so a bend costs more chunks than any single aisle of the same length cap can. The default
is 10 — every straight aisle of the default length plus a first corner — and is deliberately *not* the worst case any
more; the config comment carries the shape table (8 straight, 10 for an L of 32 + 16, 12 for an L of two full aisles, 16
for a U of three, 36 for the widest chain `aisle.maxNetworkRails = 256` allows) and `NetworkChunkSpanTest` pins exactly
those numbers, so the comment cannot drift from the arithmetic. Two aisles that share a rack plane each ticket the same
chunks — one chunk loaded, two tickets, each paying its own per-aisle cap and both counting against the level cap:
conservative, never under-counted, but it means the level cap is reached sooner than a player might expect. Cap
arbitration is first come, first served and therefore not deterministic across restarts; only the load-path seeding is,
by sorted owner, and `/wareworks chunks` shows who holds. One question is left open on purpose: whether a crane in
`HOLDING` should count as work at all. It does here, because items are in the head and the conservation invariant must
stay finishable, bounded by `maxHoldTicks`; the alternative never leaks but can leave items in a head in an unloaded
aisle indefinitely.

*Correction to ADR-013:* that decision's chunk-ticking bullet said "No chunk tickets in the MVP; documented as a known
limitation". The limitation stands for the default configuration and the bullet is now qualified rather than removed:
the crane still only simulates in a ticking chunk, and M19 changes who may keep that chunk ticking, not where the
simulation lives.

### ADR-032 — A recursive order is one **plan of ordinary production orders**, created atomically and bounded by cost rather than by depth; the safety stop grows to cover every kind of order (M20, issue #4)

*Context:* M11 shipped production as a single level, by construction rather than by a guard (ADR-024): an ingredient
counts only as real, unpromised stock, `ProduciblePlanner` never consults a second pattern to satisfy the first, and an
order that waits for another order is therefore *unrepresentable*. GitHub issue #4, from the user, asks for the missing
half — order a chest when the aisle has logs and a pattern for planks.

The obvious readings are all wrong for this addon. A crafting tree executed by the controller would be AE2 with a crane
painted on it. A "virtual" intermediate held by the controller would be item teleportation with a detour (hard rule §1.3).
And the framing the feature request itself suggested — *a recursive order is a set of temporary stock rules* — is the right
intuition about the engine and the wrong object, for six reasons that are all checkable in the code: `StockRules` is
first-wins, so a temporary row beside a player's own row is `SHADOWED` and applies nothing; `StockRule`'s canonical
constructor resolves a minimum above a maximum by **raising the player's maximum**, and a surplus stored above a cap never
leaves the warehouse again; `maxStockRules` is a shared budget, so temporary rows would push real rules to `INERT` and
silently stop their maxima capping and their reserves reserving; `pruneStockPauses` forgets a pause whose item no rule
governs, so a temporary rule would take ADR-027's safety stop with it when it was removed; `NO_ROOM` would report the
player's own cap for a click on a chest; and two orders needing 8 planks each would need the temporary minimum to carry a
refcount, which is a claim ledger wearing a rule's clothes.

The design study `run/m20-design-synthesis.md` compared three framings and recommended plan-first. **Three decisions were
then taken by the project owner and are binding**; where they differ from the study, they win.

*Decision:*
* **A recursive order is a plan, and a plan is nothing but ordinary production orders that name each other.** Ordering a
  producible item walks the aisle's patterns **once**, at the click, from the snapshot `request(...)` already builds. If
  the plan holds, every node becomes an ordinary production order in the **same tick**, children first; if it does not,
  the click is refused with a reason that **names the item**. There is no plan object with a lifetime, no re-planning, no
  scheduler and no crafting tree to keep consistent with the world.
* **The claim on an intermediate is the parent order's own supply line.** `availableStock` already subtracts
  `ProductionOrders#outstandingIngredient`, so the moment a parent exists its 8 planks are promised — to nobody else, not
  another order, not a rule, not a player at a terminal. It persists, it shows on two screens, and it is released on
  failure. **The parent order is the temporary minimum**, which is why the temporary rule was not needed.
* **Acceptance is the reservation.** Because the plan becomes ordinary ledger entries in the tick it is made, it never has
  to stay valid; there is no window in which it is a hope rather than a promise. `addAll` is therefore all-or-nothing.
* **A plan executes itself bottom-up, with no new mechanism at all.** A `SUPPLY` job for an item that is not in the racks
  finds no candidate and is not planned, so the leaf runs first and the parent waits. The parent can then fetch the very
  intermediate its own line made unavailable to everybody else, because `SUPPLY` is planned by
  `JobPlanner#planOutOfStorage` out of `stock().locationsOf(key)` minus reservations and **never** out of `availableStock`.
  `CraneDispatch`, `JobPlanner`, `JobType`, `LocationKind`, the reservation kinds and the crane state machine are
  untouched, no new `ProductionOrderState` is added, and no crane test had to change.
* **One field, and the plan is derived.** `ProductionOrder` gains `Optional<UUID> parentLine` — the parent's `SupplyLine`
  id, which `ProductionOrders#byLine` already resolves and which names exactly *which* ingredient of the parent is being
  made; a (parent, key) pair could disagree with the parent's own lines, a line id cannot. The plan id is the root's own
  order id and the depth is a bounded walk, so no two stored numbers can disagree. Saved as `Parent?`, written only when
  present, so an order saved before M20 reads back as a plain single-level order and there is no migration.
* **A parent with an open child fetches nothing at all**, and its deadline does not run. Not one line, not even the
  payable ones: a machine cannot run on a partial set, a funnel or an arm will push half a run into it, and that is the one
  thing that turns a deep chain into a deep loss. It also makes the blocked state truthful, makes two steps of one plan at
  one station strictly sequential (which removes the M11 showcase crafter hazard rather than documenting it), and leaves
  the invariant that makes a plan collapse instead of hanging: *the deepest open order of a plan always has a running
  deadline*.
* **A step is counted by arrivals only.** `countsStockLevels()` becomes `!restock && !isStep()`, and `countsArrivals()` is
  phrased over it so the ADR-027 gate covers a step too. That argument holds one level down and is now load-bearing: a
  second plank farm must not complete a step whose sawmill swallowed the batch, because the parent then *acts* on that
  completion by handing its own ingredients to the next machine.
* **Every intermediate goes through a real rack.** Machine → warehouse input or an M18 collecting port → `STORE`/`COLLECT`
  → rack → the parent's `SUPPLY`. Two extra crane trips per level, and that is the feature: it is the stock index where an
  arrival is observed, where a maximum applies, and what every surface reads. An input→station shortcut is an explicit
  non-goal.
* **Owner's decision 1 — no depth limit.** `PlanLimits` has two numbers and not three: `maxProductionPlanSteps` (production
  orders one plan may create, the ordered item's own included — it bounds the depth too, because every level costs at least
  one step, and it is what bounds the recursion, hard-capped at 1024) and `maxPlanIngredientItems` (ingredient items one
  plan may hand to machines over all its steps). `TOO_DEEP` does not exist. **Termination comes from the cycle check
  instead**, which holds whatever those numbers are: the result keys of the current path are carried down, and a pattern
  whose result *or any of whose ingredients* is already a result on the path is `LOOP`, refused before anything is
  converted. Iron ingot ↔ iron block therefore terminates by construction, and the pair stays perfectly legal to author.
  * **A bound clamps before it refuses.** A plan that does not fit is tried again with fewer runs of the ordered item and
    only refused when not even one run fits. That is M11 parity rather than gold-plating: `startProductionOrder` already
    bounded its runs by the ingredients that were there and told the request what it was granted. Cost is monotone in the
    root's runs, so the largest plan that fits is found by a binary search of at most 31 walks over the same snapshot, and
    the refusal a player sees is always the **one-run** walk's — the one no smaller order can escape.
  * **Every refusal names an item.** Eight reasons (`NO_PATTERN`, `MISSING_INGREDIENT`, `PAUSED`, `NO_ROOM`, `LOOP`,
    `TOO_MANY_STEPS`, `TOO_MANY_INGREDIENT_ITEMS`, `ORDERS_BUSY`), derived and never serialized, travelling as a lang key
    plus the `ItemKey` — the `RestockOutcome` rule. A reason without an item is the status line M11 already had.
* **Owner's decision 2 — the safety stop covers every order kind, and is lifted only by a deliberate click.** Every order
  that ends with ingredients handed over and no result arms the pause for its own result item: a player's click, a redstone
  request, a step of a chain and a rule's own refill alike. Three extensions of ADR-027 and nothing else: a pause blocks
  **planning** as well as ordering (`RequestRejection.PRODUCTION_PAUSED`), so the next click cannot rebuild the same chain
  into the same broken machine; `Cause` gains `ORDER_TIMED_OUT` / `ORDER_CANCELLED` (appended, travelling by name) and
  `pruneStockPauses` may forget only a **rule-born** pause, because the item is regularly an intermediate no rule governs;
  and the stop gets a way back **where the machine is** — a sneak-click on the warehouse production station, or the red
  stopped row in its screen. Pausing an item also cancels every open order making it, for the same reason
  `cancelOpenRestockOrders` existed.
* **Owner's decision 3 — redstone may start a chain, with one open plan per port.** The study would have kept a port
  single level. A port may build a plan, and the M17 "one open request at a time" rule is extended to plans instead: a plan
  counts as open while **any** of its orders is open, and a plan whose root has lost its backing request counts as one open
  plan for every port. The stock part of such a request is served as usual and the second chain is simply not started,
  which the port reads as `PRODUCTION_BUSY`. That keeps a clock from stacking chains into the same machines — committing
  `maxPlanIngredientItems` per *chain* instead of per port — without denying automation the feature.
* **Failure fails upward and cancels downward only what has cost nothing.** A node that ends badly cancels every open
  ancestor in the same tick; a descendant that has delivered nothing is cancelled (the crane aborts before the pick and
  reroutes), and one that has delivered is **detached** and left running, so its product lands in stock as unpromised items
  the player keeps. The root refunds its backing request its `unfulfilledPromise()` once. Cancelling **any** node ends its
  whole plan, and the terminal says what that would cost before the click — counting only the orders the cancellation would
  end.
* **M15 restocking is not touched.** `RestockPlanner`, `RestockDecision`, `RestockOutcome` and the `calledFor` interlock
  are unchanged, and `StockRestockGameTests`' inverse-pair answer stays as it is. Relaxing `calledFor` is only safe when
  the called-for item's own pattern closes the loop; with an unrelated pattern a plan would make what another rule is
  asking for.

*Reason:* Plan-first is the only frame in which a refusal can be **actionable**. Deciding at the click, from one snapshot,
means the answer is "Oak Log is missing" instead of an accepted order whose crane starts, whose logs go into a
sawmill, and whose status line four minutes later says the same thing after the batch is gone. Lazy, one-step-ahead growth
is cheaper and has a cleaner termination argument, but it commits items before it knows the chain can finish — and today's
`startProductionOrder` cannot even create the root order it would grow from (`bestProductionPattern` →
`producibleAmount` → `runsPossible` returns 0 the moment an ingredient is absent). Building the plan out of the orders
that already exist, rather than out of a new object, is what keeps the whole feature inside mechanisms that are already
tested: promises, reservations, the arrival channel, the timeout, the retention, the screens and the persistence.

*The boundary this feature cannot cross (documented, not hidden):* a plan **widens** `warehouse-system.md` §3.5.4. One
click can now hand ingredients to several machines, and what each of them swallowed is gone. Three things bound it and
none removes it: `maxPlanIngredientItems` caps what one click may spend over the whole plan; a parent with an open child
fetches nothing, so in practice only the node that failed has lost anything; and the pause keeps the next click from
rebuilding the same plan. The plan reports its whole unrecovered total as one number and each node's own on its own line.
That paragraph is in §3.5.4 itself, not only in §3.5.6, and in the README's limitations.

*Consequences:* `ProductionOrder` gains one field and two derived predicates; `ProductionOrders` gains a parent-line index
and the tree queries, and its pruning keeps a finished node while any node of its plan is open, so no state is ever read
off a pruned node. `ControllerPersistence`'s order record gains `Parent?` and a restore-time `validatePlans` that ends an
orphan **without** arming a pause — a save-integrity failure is not evidence about a machine and must not wedge an aisle's
production. `StockRulePause.Cause` gains two values by name and `pruneStockPauses` learns to keep a pause that is not
rule-born. `RequestRejection` gains `PRODUCTION_PAUSED`; `RequestResult` and `TerminalResultPayload` carry a `PlanRefusal`
and the item it names, on an **accepted** result too, because a click the racks served only in part is the commonest way a
player meets a refusal at all. `ProductionScreenState.OrderView` gains four append-only fields (`plan` — the root order id
— `depth`, `address`, `waitingForStep`), `MAX_ORDERS` rises 8 → 16, and the screen state also carries up to
`MAX_STOPPED` stopped products plus the new `ProductionResumePayload`; enum constants and payload fields are a wire format
here, so **`WareworksNetwork.VERSION` is `"6"`** for the whole milestone. Two config keys are added in the `controller`
section and `maxProductionOrders`' default rises 8 → 12, because a plan holds one order per step. The production station
gains a block state (`STOPPED`) and therefore a hand-made model and a blockstate provider, and the controller gains a
derived `stoppedStations` set so a lit lamp can never outlive the warehouse that lit it — the counterpart M15 had to add
for the keeper's comparator, and it matters more here because a block state survives every save. One Ponder scene
(`warehouse/production_chain`) is registered for **two** blocks. Three surfaces were renamed on purpose:
`gui.terminal.plan.waiting_for_step` → `gui.production.waiting_for_step` (one sentence, three surfaces) and the released
`gui.goggles.rules_paused` → `gui.goggles.production_stopped`, which the aisle display now shares — since M20 the count
includes items no rule governs, so "Paused rules: 1" was false on an aisle with no stock keeper.
`WarehouseControllerBlockEntity#pausedStockRuleCount()` is now a misnomer (it counts stopped items); nothing
player-facing says "rules" any more, and the rename of the method and its call sites is left as a separate, purely
mechanical change. Two things are deliberately **not** built: a graph-wide cycle detector or authoring-time cycle
detection across stations, and any bound counted in ticks — every bound here is counted in patterns, steps and items,
which is what a player can see.

*Correction to ADR-024:* that decision's bullet "Recursive production is stage 2" is now satisfied rather than pending.
Nothing else in ADR-024 changes: Wareworks still crafts nothing, a production station is still not a retrieval
destination, and a chain is still made of ordinary orders at the player's own machines.

*Correction to ADR-027:* the safety stop is no longer a property of *automatic* orders. The rule is now "the first loss of
**any** order stops that item", and the cause records which kind of order it was, because that is what decides whether a
pause may ever be forgotten without a player.

### ADR-033 — A warehouse is one connected rail network of straight aisles joined at shared corner blocks; the crane turns there, and a rack belongs to the aisle it faces (M21, issue #1; step two shipped in M22, ADR-035)

*Context:* ADR-008 decided that an aisle is defined by physical blocks, and every layer built since assumed those blocks
lie on **one straight line**: `RailScan` counted rails along one axis, `AisleLayout` mapped a position by walking that
axis, an address was "position along that line", `TravelTimeModel` was a distance along one axis, `CraneMotion` moved on
X and Y with one horizontal axis, `StackerCraneRenderer` drew a chassis that never turned, and every GameTest template,
visual scenario and Ponder schematic built a straight hall. GitHub issues #1 (rails around corners) and #2 (multi-aisle
warehouses) are **one** feature: a connected rail network is one warehouse, and corners are what make it possible at all
— without them goods from several aisles can never reach one block. The project owner also overruled "corners are travel
only", because that leaves dead corners: a rack standing at a bend has to be servable.

*Decision:*

* **Connectivity is plain orthogonal adjacency of rail blocks, and the only block state the topology reads is
  `CLOSED`.** `AXIS` is kept — a property removed from a block that exists in every shipped world is a migration hole,
  and it was 51 call sites — and becomes cosmetic: it picks the model of a rail with no connections at all. Four derived
  `NORTH`/`EAST`/`SOUTH`/`WEST` booleans pick one of five hand-made shapes (the plain `block`, `end`, `corner`, `tee`,
  `cross`), turned onto the sides the rail is connected on, with `closed` as a sixth whatever the neighbours do. A
  **variant** blockstate, deliberately not a multipart one: a corner and a tee are not the sum of independent arms, they
  are their own shapes. Those four are **cosmetic on purpose**: a stale one in an untouched chunk is a
  wrong *picture* that the first neighbour update repairs, never a wrong warehouse. `CLOSED` is allowed to be logic
  precisely because its failure direction is *less network, visibly marked, one wrench click to undo*, while a derived
  connectivity state's failure direction is *more network, invisibly*. The dock offers a connection only towards its own
  facing, so a rail beside the dock is still a rack position; **another dock is a wall**, which is literally what two
  opposing straight aisles already did.

* **A branch is a maximal straight chain of rails, and two collinear touching rails are always the same branch.** At
  most one branch per axis therefore passes through any block — which is what makes the ownership rule below total — and
  a junction block has a legal, in-range name on **both** branches, so the crane's hand-over from one aisle to the next
  is a pure rename of one world block rather than a sentinel coordinate. The alternative (end an aisle at every junction
  and start a new one past it) would fragment a comb's main run into a new letter per tooth, which is what forces
  two-letter addresses and a cap reached on day one.

* **A rack at a corner belongs to the aisle it faces, and the rule is total.** `WarehouseLayout#candidates(BlockPos)`
  answers with at most four `(branch, x, side)` triples — one per horizontal neighbour that is an aisle block of a
  perpendicular branch — and the member's own `FACING` selects: a storage interface faces away from its aisle, a station
  faces towards it. A block that is **itself** an aisle block of the warehouse is no candidate of anything, whatever
  runs past it: at every corner the rail before the turn is laterally beside the perpendicular branch, so without that
  clause that rail and the whole column above it — the column the mast travels through — were offered as storage
  locations of the other aisle, and a chain that turns at position 1 offered the **dock** block (M21 review fix). The
  rule only ever removes candidates, so the totality argument is untouched. Each candidate has a **distinct** neighbour
  and therefore requires a **distinct** facing, so exactly zero or one can be satisfied. This is the load-bearing
  decision of the feature and the only one of the three proposed rules that survives the case that actually bites: the
  inner corner of an L is laterally beside a *straight* rail of both aisles and is not a neighbour of the corner block
  at all, so every enumeration of "the corner block's free faces" misses it. The same rule resolves two parallel aisles
  two blocks apart inside one network (the `twoaislessharearackplane` case) at no extra cost and with no change to
  `WarehouseMember`.

* **`RackPosition` gains a `branch` field whose absent value is 0, and no player-visible label is ever a map key.** The
  field keeps all 314 construction sites across 78 files compiling and meaning the same thing, and it writes a
  byte-identical save for a warehouse of one aisle. Re-basing `RackPosition` on a dock-relative `(dx, dy, dz)` offset —
  so renumbering became structurally impossible — was rejected on price: it is a semantic rewrite of those 314 sites,
  none of them rewritable without the dock facing in scope, and its failure mode is stock silently attributed to the
  wrong location rather than a crash. Its *principle* is kept and paid for differently: whenever the decomposition
  changes, every saved record, count, misaligned position, stock rule, production order **and the crane's own pose,
  motion target and job** are remapped through their world positions (`world = oldLayout.rackPos(rec)` →
  `newLayout.candidates(world)` resolved by the member's facing), and a record whose world position is no longer a rack
  position is dropped exactly as a member that left the aisle is dropped. **No item moves during a remap.**

* **Addresses are pinned to the rails, not to discovery order.** `BranchTable` remembers a letter and an origin end per
  branch *line* (its axis plus the one fixed coordinate along it, dock-relative), because a line survives exactly the
  things a player does — extending an aisle at either end, shortening it, a junction appearing in its middle. Branch 0
  always takes the controller's own "Aisle" value box, so a warehouse built before M21 reads exactly as it did; every
  further aisle takes its pinned letter if nothing nearer has claimed it, otherwise the lowest free one. A saved origin
  is kept whenever it is still one of the branch's two ends (if it became the far end the branch is flipped rather than
  renumbered); an origin that is no longer an end at all forces the remap above.

* **The crane turns inside `TRAVEL_*`, and the turn is priced in blocks.** No new `CranePhase`, `CraneEvent`,
  `CraneEffect`, `CraneInterruption` or `CranePauseReason`. `CranePose` gains `branch` and a continuous `yaw` in quarter
  turns, and `CraneMotion.step` gains one precedence rule between "retract the arm" and "move X and Y": turn towards the
  current leg's heading, freezing X while Y keeps moving. Every turn is exactly ±1 quarter, because perpendicular
  branches are the only ones that meet, so `Side.LEFT`/`RIGHT` keep their meaning everywhere. One new config key,
  `crane.turnPenaltyBlocks`: a quarter turn costs that many blocks of travel — no new speed constant, and **the whole
  route cost stays one scalar in blocks**, which is what keeps `travelTicks` one formula and tick-exact against
  `CraneMotion`.

* **`JobPlanner` learns nothing about graphs, and one aisle plans bit for bit as 0.5.0 did.** `PlannerInput` gains a
  `TravelCost` whose builder default *is* the old formula, so the identity is a type-level property rather than a claim —
  the shape M16 used for `NO_PRIORITY` and M17/M18 for the empty port lists. Reachability needed no planner change:
  `PlannerInput#available` already means "usable now", and the controller adds "and reachable from where the crane
  really stands".

* **There is exactly one definition of "it can get there", and it is asked from the machine's own point.**
  `RouteTable#canDrive` is it: a machine already named on the branch it has to reach always can (it drives straight at
  the target along the line it stands on, which is also how it comes back onto rails that became shorter under it), and
  every other case needs a route from its own position. A branch-index question is not a substitute — a rail taken out of
  the middle of the aisle the crane is on leaves the aisle shorter than the crane's own position, and the branch still
  exists and still meets the corner. `CraneExecution` and `CraneDispatch` both ask it, and a crane whose aisle left the
  warehouse is put back onto the aisle at the dock and logged once rather than handed jobs it will abort for ever.

* **At a cap the network is kept, never truncated.** `aisle.maxNetworkRails` and `aisle.maxBranches` keep the last valid
  network and report the stop, because truncation renumbers and a deterministic walk order is not *stable* under a rail
  added in the middle. `aisle.maxAisleLength` keeps its key and still truncates one branch at its **far** end, which
  renumbers nothing because a branch is numbered from its near end outwards. Two rules, each with its own reason.

* **An unloaded chunk keeps the network, but not a length a loaded block has disproved.** The same argument decides the
  third case: a scan that reached an unloaded chunk may have found *less* than there is, so the controller keeps the
  branches it knows instead of adopting the shorter scan — but it cuts branch 0 back to the length the dock resolved with
  its own narrow flag. Truncation at a far end renumbers nothing, which is the whole reason the length cap is allowed to
  do it. Keeping the branch verbatim meant one unloaded block anywhere beside the surviving rails — a rack column is
  enough — froze the aisle's old length for as long as it stayed away, and the crane then routed, answered `canDrive`
  and parked against rails that were gone (M21 review fix; the decision is pure, in
  `NetworkGeometry#withFirstBranchLength`, because a GameTest area is force-loaded and cannot produce the unloaded
  neighbour). **In M21 it took every other branch with it**, on the argument that a chain hangs off the far end of
  branch 0; M22 made that false — a comb's teeth hang off positions the shortened run still has — so it now shortens
  that one aisle and leaves every other at its own index, and a tooth the shorter run really disconnects is answered by
  reachability (`AisleAssignment.State.UNREACHABLE`) instead of being deleted (M22 review fix).

* **Shipped in two steps, cut at the topology and not at the machinery.** Step one (this milestone) restricts the network
  to a **chain** — every aisle block has at most two connections and there are no loops — and ships everything else,
  including the whole migration and the turning machine. In a chain there is exactly one route between any two points,
  so `RouteModel` is a walk: no shortest-path search, no all-pairs matrix, no junction-entry ambiguity. A player who
  lays a T gets a **shorter valid warehouse** with the branching rail named on the controller's goggles, never nothing
  and never the straight aisle they had taken away. Step two (issue #2) lifts the restriction, adds real routing and
  deletes the `BRANCHED` and `LOOPED` stops. **Step two shipped in M22** exactly as cut here — ADR-035 — and the cut
  held: discovery and routing were replaced while the machinery (phases, events, the pose, the migration, the remap)
  was not touched at all.

* **A 0.5.0 world must behave identically, and the off switch has to prove it.** `aisle.maxBranches = 1` reduces the
  whole feature to what 0.5.0 did — discovery then follows the dock's facing and reads nothing beside it — which is both
  the escape hatch for a server owner and the regression oracle, the trick M16 used for `NO_PRIORITY` and M20 for
  `maxProductionPlanSteps = 1`. A player has to do nothing at all: the `Network` tag is written beside the old length
  tag, an absent branch reads as 0, an absent yaw reads as the yaw at the dock, and a straight warehouse with no pinned
  line saves the bytes it always did.

*Reason:* The feature is one sentence — a connected rail network is one warehouse — and the design that is safe to build
is the one that changes what a warehouse *is* without changing what anything above the rail layer *does*. Here that is
literal: one controller, one crane, one stock index, one reservation ledger, one request queue, one terminal, one address
format, one state machine, one planner ranking, all keyed by an opaque location id that gained one integer. What changed
is the shape of the position list, the mapping from a position to a block, and one precedence rule in the motion
function.

*Consequences:* `content.controller.AisleLayout` is renamed `BranchLayout` (unchanged in shape) and the new
`WarehouseLayout` holds the network; `content.crane.RailScan` becomes `RailNetworkScan`. The rail hint the study
proposed (`WarehouseRegistry.railChanged`) was **not built**, so the deviation recorded in `stacker-crane.md` §3.1 ("no
refresh on rail changes near the dock") stands and a new or broken rail is still picked up by the periodic scan within
`geometryRefreshTicks`. The wrench on a rail toggles `CLOSED` instead of rotating `AXIS`. **There is one
genuine behaviour change in an existing world, and the CHANGELOG leads with it:** under adjacency a decorative rail
orthogonally beside an aisle line now joins the network as a one-block aisle, and a wrong-axis rail that used to *stop*
the scan now connects and lets the run beyond it join. Nothing is lost — the positions are new, and a position that
stopped being a rack keeps its record until the next reconcile and then leaves normally with its stock still in its chest
— and the `CLOSED` wrench is the one-click cure. ADR-007's known culling limit gets materially worse in principle,
because a bent network can put the crane much further from its dock than a straight aisle could; the renderer therefore
measures against the dock's own cached network bounds rather than the config ceiling, and the real fix is still the
per-chunk proxy renderer named in `stacker-crane.md` §7.1. M19's `chunkLoading.maxChunksPerAisle` no longer follows from
`aisle.maxAisleLength`, so its default is raised from 8 to 10 (a straight aisle of the default length plus a first
corner), the all-or-nothing rule is kept, and the number a warehouse would need is named in the goggles, in
`/wareworks chunks` and in the config comment. Finally, the turn adds a stop where none existed: a bent warehouse is
measurably slower per trip than the same rack count in one straight hall, and the planner's travel-time key will quietly
prefer racks on the aisle the crane is already on — physically honest, probably desirable, and a change players notice.
**A one-crane comb is slow**, which is what makes a multi-crane milestone necessary rather than optional; that sentence
belongs in step two's changelog, before somebody builds one — and it is there, in as many words, under M22's `Changed`,
beside manual check 164, which asks for the number of teeth at which it stopped feeling like a warehouse.

*Deviations from the design study* (`run/m21-design-synthesis.md`), recorded rather than silent:
* `AisleChunkSpan` was **not** renamed to `NetworkChunkSpan`. It gained `networkChunks`, its unit tests are
  `NetworkChunkSpanTest`, and the rename was not worth touching M19's surface for.
* **`/wareworks network` does not exist yet.** The study puts the branch table and the list of rejected rails there;
  until it exists the controller's goggles and the log carry the same facts, and `/wareworks chunks` carries the chunk
  shortfall. With seven aisles or more the goggles list only the first six, so the command is where the full table
  belongs.
* The GameTest template `corner_16x10x16` was not added; the corner tests run on the existing `aisle_pair_16x10x13`.
* **The rail hint was not built.** The study has `WarehouseRailBlock` tell every controller whose network bounds contain
  the position to look again, which would have closed the M2 deviation "no refresh on rail changes near the dock"
  (`stacker-crane.md` §3.1). `WarehouseRegistry` has no `railChanged`: a new or broken rail is still noticed by the
  periodic scan within `geometryRefreshTicks` (2 s by default). A corner appears the moment two runs touch, so the delay
  is more visible than it was on a straight aisle, and this is the first thing to build if it ever reads as a bug.
* A **home point** block and **returning home after an idle delay** were asked for late in the milestone and **are
  built**, as the closing part of M21 — beside the rails rather than on them, one per crane, and only on a warehouse of
  more than one aisle. They have their own decision: **ADR-034**.

*Correction to ADR-008:* "an aisle is defined by physical blocks" stands and is strengthened. What changes is that the
blocks no longer have to lie on one line, and that "rails that touch, connect" is the whole topology — there is no
configuration UI, no junction block and no branch marker.

### ADR-034 — A home point is an ordinary rack member beside the rails, and returning home is the resting target of `IDLE` (M21, issue #1)

*Context:* Two things were asked for late in M21, after the rail network itself was working. First a block a player
places to say **"wait here"** — beside the terminal, near the input, wherever the next job usually starts — because
until now a crane's home was its dock and nothing else. Second, **returning home**: a crane that has had nothing to do
for a while should drive back there instead of standing wherever its last job left it, which is what every version up to
0.5.0 did. The real payoff of the first is the multi-crane milestone: one home point per crane is how a player will
assign areas without a zone editor, so this has to be built in a way that makes that step small. The hard part is not
the driving, it is that a return must never cost anything — it may not delay a job by a tick, and it may not keep a
warehouse's chunks loaded that would otherwise be idle (ADR-031 releases on an **idle** warehouse).

*Decision:*

* **The home point sits beside the rails, not on them**, as an ordinary rack member with its own kind
  (`LocationKind.HOME`). It therefore joins its warehouse through the membership machinery every other member uses, and
  it already **has an address** that names the aisle and the position the crane has to drive to. A block on the line
  would either break the chain the discovery walks (§1.1 of `warehouse-system.md`: at most two connections per aisle
  block) or need connection rules of its own — and a player could then never mark a **corner**, which is the one place
  a "wait here" is worth most. Nothing in `RailNetworkScan`, in connectivity or in branch counting sees anything new.
* **Home is the home point, or the dock.** A warehouse without one parks its crane at position 0 of the aisle at the
  dock, which is where a crane has always started, so breaking the home point falls back to the dock with no further
  rule to learn.
* **Only a warehouse of more than one aisle returns.** A single straight aisle keeps 0.5.0 behaviour exactly — the
  machine stands where its last job left it — and a home point there reports "without effect" rather than pretending to
  work. The alternative is a silent behaviour change in every world built before this version.
* **The whole return is one line of state: the resting target of `CranePhase.IDLE`.** No new phase, event, effect, job,
  interruption, pause reason or timer in the state machine; `CraneExecution#returnHomeIfIdle` writes the target and
  `CraneMotion` drives and turns towards it exactly as it does for a job. That buys all three required properties for
  free: a crane driving home **is idle**, so it takes a job in the tick that job is planned — mid-turn included, because
  the next phase simply computes another target from the pose the machine really has — and a return is no
  `TransportJob`, so `ChunkKeepDecision` still sees a warehouse with nothing to do and keeps letting its chunks go.
* **The decision itself is pure:** `core.crane.HomeReturn` (the configured delay, the aisle count, the optional home
  rack) answers when a crane counts as waiting, how the idle counter runs, when it returns and which pose it parks in
  (arm retracted, facing the way its aisle runs, so the next job starts without an extra turn). Everything a player can
  argue about is a JUnit test rather than a world test.
* **At most one per crane, and every refusal is visible.** A warehouse has one crane, so it has one home: the **first**
  of its home points in `RackPosition.ORDER`, which is the same block on every tick and after every restart. Every
  other one lights a **red** lamp, grows a crossed brass stop over its plate and says so in its own goggle sentence
  (`HomePointStatus.SECOND`). A home point on an aisle the crane cannot drive to is **reported, not obeyed**
  (`UNREACHABLE`), and then the **dock** is home again — never the second home point, because otherwise "at most one
  per crane" would depend on the rails.
* **One config key**, `crane.returnHomeIdleTicks` (default 200 = 10 s, range 0–72000). **0 switches returning home off
  everywhere**, which is the server owner's off switch and reproduces the single-aisle rule on every warehouse; the
  block says that too (`SWITCHED_OFF`).
* **The controller owns the answer, the block only shows it.** `WarehouseControllerBlockEntity#refreshHomePoints` runs
  on the first tick after a load, at the re-link cadence (`geometryRefreshTicks`) and whenever a home point joins or
  leaves — never per tick — hands the serving rack to the dock and writes every home point's lamps. It tracks the
  blocks it has lit by **world** position, because that is the one thing that still means the right block after a
  player has broken the aisle a rack position was named on (the exact class of bug the review found on the stock
  keeper's lamp). The crane's home point is neither saved nor synced: it says nothing about where the machine *is*,
  only where it would go next, and the controller hands it over again on its first re-link after every load — until
  then the machine simply waits where it stands. Crane and controller persistence formats are unchanged.

*Reason:* The feature is one sentence — "a crane with nothing to do waits where I put this block" — and every hard
requirement on it is a requirement about *not* doing something: not delaying work, not holding a chunk, not changing
what a single-aisle warehouse does, not ignoring a block a player placed. Modelling the return as a job or a phase would
have had to re-earn each of those with its own rule and its own test; making it the resting target of the phase that
already means "nothing to do" gets them from the shape of the design. Making the block a rack member rather than a rail
does the same thing one layer down: it inherits addressing, membership, alignment, the misaligned hint, the remap and
persistence, and it leaves the topology of step one untouched — which is what keeps the multi-crane step small, because
"one home point per crane" is then a map from dock to rack position and not a new kind of world state.

*Consequences:* A tenth block and creative-tab slot (`wareworks:warehouse_home_point`, `content.station`), the cheapest
recipe of the newer blocks because it holds nothing and decides nothing (a rose quartz lamp, an andesite casing and two
andesite alloy — no electron tube, no precision mechanism; only the input and the interface are cheaper), two lamp states over a **multipart** blockstate (the plain plate, the lit
twin, the refused twin and the stop as a separate part), eighteen lang keys per language, and a `home` visual scenario
beside `corner`. `LocationKind.HOME` is the **second** kind with no inventory at all, which is why `isStation()` is an
explicit list rather than "everything that is not storage" (M15 made it one for the stock keeper; a fourth aisle-facing
kind would have fallen into the same trap). `AisleMembership` gains a home-point counter so a warehouse without one
pays a single read per refresh, and `TransferContexts` resolves a `HOME` location as **missing**, so no job can ever
name it. Nothing in `core.job`, in the reservation ledger, in the state machine or in `ChunkKeepDecision` changed. There
is **no Ponder scene** for the home point yet and it is not in the Ponder tag, so `PonderVisualScenario.SUBJECTS` is
unchanged. Tests: `HomeReturnTest` (12 JUnit) for the rule and `gametest.HomePointGameTests` (7) for the world — served
round an L, broken falling back to the dock, a second one refused, an unreachable one reported, a return interrupted
mid-turn by a real job, a whole trip home asserting zero held chunks on every tick, and a single straight aisle whose
pose is compared against the recorded one on every tick for three idle delays.

*Deviations and things worth knowing:*
* **A warehouse of more than one aisle and no home point returns to its dock.** That follows from "the dock stays the
  home" plus "a warehouse with more than one aisle returns", and it is a behaviour change — but only for multi-aisle
  warehouses, which do not exist before this version, so nothing anybody has already built starts moving.
* **`returnHomeIdleTicks = 0` means off, not "return immediately".** Not asked for; it is what a server owner needs and
  it makes the single-aisle rule available everywhere.
* **A home point on a warehouse of one straight aisle has no effect at all** and says so, rather than working "only on
  bends". This is decision three read literally, and it is what keeps the pre-M21 behaviour provable.
* **The refused state got a crossed brass stop as well as the red lamp.** `create:block/rose_quartz_lamp` and its
  powered twin are only a shade apart in a screenshot — acceptable on a stock keeper, where both states are warnings,
  wrong here, where they mean "your crane's home" and "this block does nothing". The stop reads across a room and
  without colour, the standard the closed rail already set (ADR-033).
* **The controller judges reachability on its refresh cadence**, so a red lamp can lag the rails by up to
  `geometryRefreshTicks`. Nothing acts on the stale answer: the crane asks `RouteTable#canDrive` itself, live, before it
  sets a return target — the same single definition of "it can get there" that ADR-033 introduced.
* **`UNREACHABLE` is deliberately defensive.** On a chain a broken or closed rail usually takes the far aisle out of the
  warehouse altogether, so the status mostly appears in the one case its GameTest builds (an aisle shorter than the
  machine standing on it). **M22 gave it a second real case** (ADR-035): a warehouse's rails are connected by
  construction, but `aisle.maxAisleLength` can truncate a run and cut an aisle loose, and such an aisle is kept and
  marked rather than deleted.
* **Neither the controller's nor the crane's goggles name the home point.** The block's own goggles carry the address
  and the status sentence, which is where a player looks after placing it.

### ADR-035 — A route over a warehouse whose rails split is the cheapest path over junction nodes, priced in blocks plus turns and derived one single-source pass at a time (M22, issue #2)

*Context:* ADR-033 made a warehouse a connected set of rails but restricted it to a **chain**, where exactly one route
joins any two points, so `RouteModel` was a walk and needed no search. A T, a cross, a ring or a comb offers several
routes, and which of them is cheapest depends on what a quarter turn is worth (`crane.turnPenaltyBlocks`). The
planner's travel key is read once per candidate of every planning pass, so the answer has to be **exact** (not an
estimate — the cost is one scalar in blocks, so exactness is achievable), **the same every time** (or the same build
would plan a different job on every reload) and **cheap per candidate**.

*Decision:*

* **The search space is junction nodes**, one per `BranchLink` **per branch of that link** — "standing on the shared
  block, named on this branch" — so at most `2 ×` the junction count. The only decision on a trip is *where to hand
  over*; a hand-over costs one turn and moves the machine by nothing (it is the same world block), and driving between
  two consecutive junctions of one aisle costs the blocks between them and no turn. Both edges are symmetric, so the
  cost matrix is.
* **Nothing is rounded.** Between two junction nodes both blocks and turns are integers, so a matrix cell is one packed
  `int`. The only non-integer parts of a trip are its two end stretches, which are added once.
* **A cheapest route never drives one aisle twice** (the stretch between two visits can be replaced by driving straight
  along that aisle: no further travel, at least two turns fewer), which bounds the turns of a route and is what lets a
  `CraneRoute` — which refuses two legs in a row on one branch — always be built from one.
* **Ties break deterministically:** cheaper, then fewer turns, then the **pair of junctions the trip was priced
  between** — the lower position on the first aisle, then on the last, then the node indices. Which hand-overs the
  route then really takes is a second decision (`nodePath`: of several steps that keep the cost, the one onto the
  lower aisle, then the one at the lower position), and the two are deliberately not the same key: a trip priced as
  leaving its first aisle at one junction may be driven straight past it and hand over later at no extra turn, so a
  rule phrased as "hands over onto the lower-numbered aisle first" would name an aisle the route never touches
  (M22 review fix). `costBlocks` and `route` both go through the **same** pick, so the cost a controller ranks and
  the way a machine drives are one decision rather than two that can drift — which is why
  `CraneNetwork.Discovered` and `WarehouseLayout#route` both ask at the server's own turn price.
* **Passes are derived on demand and kept**, one per junction node, and **a table has an owner**: `WarehouseLayout`
  holds the table of the network it maps and the dock builds its `CraneNetwork` on that very table, so the shape and
  everything derived from it have one lifetime. A warehouse is therefore costed when its **rails change** and at no
  other time, and the controller and its crane read the same derived rows. Two points of one aisle cost `|Δx|` with
  no graph touched at all, which is the whole of a warehouse that does not bend and most questions on one that does.
  (M22 review fix: `RouteTable.of` first did this with a static eight-slot cache evicting by a round-robin counter.
  Nine live shapes asked in turn hit it **never**, so a server with a handful of warehouses re-derived a whole
  warehouse per planning pass, and an evicted shape silently split a controller and its own dock onto two tables.
  An owner cannot miss, and no shared mutable state is left in `core.*`.)
* **Reachability is a branch-component lookup**, penalty-free, so an aisle the rails no longer join can be marked on
  its own members' goggles and `NoJobReason.UNREACHABLE` has real cases.
* **Discovery is a breadth-first flood**, so a discovered warehouse is **connected by construction**: every block it
  reached has a connected neighbour, so it lies on a run of at least two blocks, and the block a branch was first
  reached *from* lies on a perpendicular run through the block it was reached *at*. The caps keep that invariant by
  keeping a **prefix** of the branch order (a connector always sorts before what it connects) — all except
  `aisle.maxAisleLength`, which truncates a run and may cut an aisle loose. Such an aisle is **kept and reported**
  rather than deleted, because deleting it would take a player's chests out of the address space because a number in a
  config file is too small.
* **`aisle.maxJunctions` is the bound that matters**, because junctions are what the search is priced in: at the
  default 32 a fully explored matrix is about 260 000 integer operations, at the ceiling of 128 about 17 million —
  paid once per change to the rails and spread over every pass until the next one. A 26-aisle grid could structurally reach 169 junctions, so the cap does real work.

*Consequences:*

* `RouteModel` became a documented one-question façade over `RouteTable`/`RouteCosts` for a shape nobody holds; the
  chain walk is gone. `RouteTable` stopped being a record (it holds lazily derived costs) and stopped carrying a turn
  price, so one warehouse needs one table rather than one per price; and `WarehouseLayout` stopped being a record so
  that it can hold that table for the life of the shape it maps.
* `NetworkStop.BRANCHED` and `LOOPED` are **deleted**: they only ever said "this version cannot follow that shape".
  `MAX_JUNCTIONS` took their place, and every maximum names the key to raise.
* **Three- and four-way ownership needed no new rule.** At most one aisle per axis passes through a block, so
  `NetworkGeometry#candidates` plus the member's own facing decides a T and a cross exactly as it decides a corner.
  That was test surface, not code (`RailGraphTest`, `RailNetworkGameTests#networkfollowsatee`).
* `AisleAssignment` gained an `UNREACHABLE` state that **keeps its address**, because a member on such an aisle really
  is that location — a player who read only "unreachable" would go looking for a misplaced block.
* **The two scaling items the design made due here were built**, and both are **measured** rather than asserted: the
  round-robin reconciliation now scales its per-interval count to meet `controller.snapshotCycleTicks`, so the *cycle
  time* is bounded instead of the per-tick count (`SnapshotCadence`: 16 512 locations come round in 41 280 ticks
  instead of 165 120, while every warehouse up to ~1200 locations reads exactly one per interval as before); and the
  planner derives the available storage locations **once per pass** (`LocationAvailability`) instead of asking the
  world per (station × item type), which matters more now that "available" includes a graph search — a measured
  worst-case dispatch run over 64 locations with four input stations holding three item types each puts **68**
  questions to the world where it used to put **772**, and derives the candidate list **once**. The counters
  (`questions()`, `probes()`, `listWalks()`) are on the type for exactly that purpose, and a pass's cache is built
  fresh per `PlannerInput`, so no pass can ever read what another pass was told.

*Alternatives rejected:* an **eager all-pairs** build, which is `O(nodes³)` and would have cost ~262 000 integer
operations per planning pass at the default cap and ~17 million at its ceiling, because the content layer builds a
table per pass; a **1-D distance transform per branch with a candidate**, which the design study proposed and which was
not needed — `junctionsOn` is a handful on every shape a player builds, and a measured whole planning pass over a
16-aisle comb (640 travel questions) reads 1944 integers; and an **estimate** such as Manhattan distance, which would
have made the planner prefer racks it cannot actually reach soonest and would have been unprovable.


### ADR-036 — A clipboard list is a stream of ordinary requests with one owner, and the tick marks are written into Create's own item through the one path its read-only flag leaves open (M23, issue #19)

*Context:* Issue #19 asks for a clipboard with a list of items to be handed to a terminal and worked off, with the
delivered entries **ticked off on the clipboard**. The obvious source is Create's Schematicannon, whose material
checklist can already be written onto a clipboard (`MaterialChecklist#createWrittenClipboard`), but a hand-written one
has to work just as well. Three things made this a decision rather than an implementation: a whole schematic's worth of
material cannot fit anywhere at once, so the order has to run over minutes and survive a reload with nobody at the
terminal; the clipboard belongs to **another mod** and the checklist it writes is `readOnly = true`; and the warehouse
already has a request queue with batching (ADR-020), reserves and maxima (ADR-027), production chains (ADR-032) and a
safety stop, none of which may be duplicated or bypassed.

*Decision:*

* **A list order is not a new kind of request.** `core.terminal.ListOrder` resolves the clipboard into lines once and
  then keeps a small number of **ordinary** retrieval requests open, each made through the very call a click goes
  through (`WarehouseControllerBlockEntity#request`). No new job kind, reservation kind, queue or network of its own;
  merging, reserves, maxima, filters, priorities, chains, the safety stop and the full-destination back-off cannot tell
  a portion from a click. Nothing teleports: every item is fetched physically by the crane.
* **`RequestScope` is the whole difference between a click and a portion**, and it changes exactly two things, both in
  `core.terminal`: whether a request that would have items **made** must be agreed to first
  (`RequestConfirmation#required(scope)`) and whether an answer has to name that number
  (`RequestAcknowledgement#covers(…, scope)`). A click starts production under the player's eyes; a list order starts it
  while nobody is there, which is the issue's "producible items ask too". `required()` and `covers()` without a scope
  mean `CLICK`, so every caller from before M23 asks and answers byte for byte what it did.
* **The clipboard goes into a slot of the terminal, not into a hand.** That is what makes the feature
  server-authoritative and what lets the ticks be written where the clipboard lies. The slot is not part of the item
  capability, so automation can neither feed a list in nor pull the receipt out.
* **The order's lines are the truth; the clipboard is its receipt.** A tick mark cannot express "1300 of 2000", so
  partial progress lives in `ListLine` and a mark is written only when a line is complete. The clipboard is re-read only
  to check that it is still the same list (by the entries' **icons**) and to write marks; taken out, swapped or edited
  mid-run ends the order and cancels its requests, and a partly delivered entry is never ticked.
* **Writing `checked` on a read-only clipboard is sanctioned, not a workaround.** Verified in Create 6.0.10's own
  sources: the list is one data component (`AllDataComponents.CLIPBOARD_CONTENT`), `ClipboardEntry#readAll` hands out
  **fresh, mutable page lists** (Create's own comment says so), and `ClipboardScreen` reads `readOnly` in exactly
  three places — the text cursor, the "next page" button past the last page and entering text-edit mode. The
  **checkbox** path has no such guard, and neither has `ClipboardEditPacket`, so a player may tick a checklist off by
  hand and Create persists it. `content.item.ClipboardList` writes that same field, through `stack.get`/`stack.set`,
  with no mixin, no reflection and no packet of ours, and leaves the flag and everything else about the clipboard as it
  found it. The entry's **text** is never read: it is a foreign `Component` that a checklist hangs
  `HoverEvent.SHOW_ITEM` on, so the item comes from the icon and the amount from `itemAmount`. Only the two **list**
  levels are re-created by `readAll`, though, so the entry objects are shared with the old `ClipboardContent` and with
  every copy of the stack; `tickOff` therefore puts a **ticked copy** in the entry's place rather than changing it,
  which keeps the mark on the one clipboard it was earned on and makes the new component compare unequal, so an open
  screen is really sent the receipt (M23 review fix, §3.4.4).
* **One confirmation mechanism, used twice.** Fetch raises one dialog measured over the **whole** list
  (`ListOrderConfirmation`: what the racks fall short of, what would be produced, what the entry cap left behind), and a
  Yes becomes a consent **budget** that the portions spend down. A portion that costs more than the remainder covers
  stops the order and raises the **existing** per-item panel (§3.6.6) through the **existing** `TerminalConfirmPayload`,
  which gained a `scope` field so the screen routes the answer back to the order. The list-wide budget is production
  only, because a reserve and a maximum cannot be measured over a list without walking a plan per entry — and they are
  exactly the ones a player must not sign away blindly.
* **Consent carries no item, so a list order has to supply one.** A `RequestAcknowledgement` is four numbers; kept as a
  single budget, a Yes about one item's reserve paid for the next item's. A portion's answer is therefore kept whole
  under the key its question named (`ListOrder#budgetFor`/`#spend`) and only the Fetch dialog's production total — the
  one consent really measured over the whole list — stays list-wide; the answered portion is the first one offered
  again, and a player's **no** parks the order instead of leaving it in `ASKING` with nothing on screen to refuse it
  (M23 review fix).
* **Waiting is the answer to a full destination, never a refusal.** A full buffer is already a planner skip with a
  back-off, so the request stays open and the next delivery makes the order due at once. Only an order with **nothing**
  in flight that has made no progress for `terminalListStallTicks` parks, and then a player's click resumes it.
* **The controller keeps no saved list state.** An unsaved, server-only set of terminals worth looking in on, rebuilt
  on any membership change and bounded at 64, is the whole registration; the order itself lives in the terminal beside
  the clipboard it belongs to. A set that is wrong is self-healing and can lose no order, whatever order block entities
  load in. Both the rebuild and the walk check `Level#isLoaded` before they resolve a block entity, like every other
  lookup in the controller: a member record survives a chunk unload on purpose, so an unguarded lookup would force-load
  every unloaded output-station chunk of the warehouse on any membership change (M23 review fix).

*Consequences:* the terminal menu has one slot more (`listSlotIndex() == bufferSlots`), which is a protocol change for
any client of the menu and is why the network `VERSION` went to `7` together with the three list payloads. One
pre-existing bug had to be fixed for the production question to work at all: `confirmationFrom` short-circuited for an
aisle with no governing stock rule and therefore never reported `made()`, so the question would have been dead in the
common case of an aisle with patterns and no keeper; it now short-circuits only when nothing would be produced either,
and a click's question is unchanged because `required(CLICK)` never looks at that number. `TerminalRequestOutcome`
gained an optional `cost`, the measurement the request path makes anyway, so a portion can spend the budget by what it
really cost instead of measuring a second time.

A list order's per-portion consent and the question it answers are deliberately **not** saved, for the same reason every
confirmation is measured fresh: both are statements about the warehouse as it was, and a restored order comes back
`RUNNING` and asks again.

*Alternatives rejected:* **right-clicking a block with the clipboard in hand** (the issue's own first option), which
cannot show progress, cannot be read by the screen and loses the order the moment the player puts the clipboard in a
chest; **a fresh clipboard carrying what is left** instead of ticking the original, which the read-only check proved
unnecessary and which would have thrown away the checklist a player printed; **one request per stack**, which buys
nothing because the planner already splits a large request into successive jobs and a full destination is already a
skip, and would cost a round of measuring per stack; **a second queue or a "list job" kind**, which is exactly the
duplication the issue forbids and would have had to re-implement batching, reserves and the safety stop; **asking per
entry**, which a 128-entry checklist turns into a dialog storm; and **a mixin on `ClipboardScreen` or
`ClipboardContent`**, which was never needed once the checkbox path was read.

### ADR-037 — A player's terminal order and their request counts are a data attachment on the player; the server owns the numbers and the client does the sorting (M24, issue #17)

*Context:* Issue #17 asks for a terminal whose stock grid a player can reorder, with a **"most used"** order — what
*this player* requests most often — and the choice **remembered per player**. The build before M24 had two of the three
orders and a button that cycled them, but the choice lived in a `static` field of `WarehouseTerminalScreen`: lost on
every restart, and meaningless on a server, where one client's field would have decided for whoever opened a terminal
next. The issue's own open questions were left for this decision: where "most used" is counted (per player, per
terminal or per warehouse) and whether it is kept across a restart. Two constraints come from the hard rules: `core.*`
must stay pure Java, and nothing may be scanned per tick.

*Decision:*

* **Per player, on the server, nowhere else.** The counts answer "what does *this* player keep fetching", so a
  per-terminal or per-warehouse store would answer a different question — and a per-terminal one would make the order
  change as a player walks from one terminal to the next. `content.station.TerminalPreferences` holds one player's
  chosen order and their `core.terminal.TerminalUsage` store; it is a NeoForge **data attachment**
  (`registry.WareworksAttachments.TERMINAL_PREFERENCES`), so NeoForge saves it inside that player's own
  `playerdata/<uuid>.dat` (`Entity#saveWithoutId` → `AttachmentHolder.ATTACHMENTS_NBT_KEY`) and the lifetime is exactly
  a player's. `copyOnDeath()` keeps it across a respawn: dying is not a reason to forget what somebody uses.
* **Counting happens in exactly two calls, both where a player asks.** An accepted click
  (`WarehouseTerminalBlockEntity#requestFromTerminal` → `TerminalPreferences#countRequest`) and the item types of a
  clipboard order at the moment the player starts it (`#fetchList` → `#countListOrder`). One request counts **once**,
  whatever amount it asked for, so a ctrl-click on a thousand cobblestone cannot outrank a hundred deliberate
  requests; a refusal and a question count nothing. A **redstone** request at a warehouse port reaches neither call —
  a port is not a player, and its requests carry `StockAccess.AUTOMATION` and no `Player` at all — so automation can
  never teach a player's terminal anything. The cost is one map write per click: no tick, no scan, no pass over the
  warehouse.
* **The store is bounded four times and fades by use, never by time** (`TerminalUsage`, and `warehouse-system.md`
  §3.4.2 for the whole rule set): a capacity in item types (`maxTerminalUsageEntries`, default 64, hard-capped at
  256), a ceiling on a count, recency stamps renumbered before they could run away, and — added by the M24 review —
  a bound on the **size** of what is remembered. The first three are all counts of *things*, and an `ItemKey` carries
  the item's whole data-component patch, so 64 keys can be 64 shulker boxes with a `container` component or 64 written
  books, i.e. kilobytes each in every player's save file. The size bound therefore lives in the content layer, where
  an item is visible at all (`TerminalPreferences.MAX_KEY_SIZE` refuses to remember an oversized key, so it reaches
  neither the store, the save nor the payload, while `MAX_USED_SIZE` bounds the whole written list), and the request
  itself is never affected. When the capacity is reached the **weakest** entry goes — lowest count, among equal counts
  the one asked for longest ago — so a new item type is always learned and a passing click never pushes a favourite
  out. A time-based decay was rejected: it needs a clock the pure class deliberately does not have, it would punish a
  player who merely took a break from the world, and it could reorder a list while they were looking at it.
* **One player action is counted as one action, however many item types it names** (`TerminalUsage#recordAll`, M24
  review fix). A clipboard order may name more item types than the store holds — `maxTerminalListEntries` is 128
  against 64 entries by default and 1024 against 256 at the extremes — and counting it key by key made the later keys
  of one list evict its earlier ones, because a brand new entry is the weakest entry there is. A repeated identical
  order was then a fixed point at count 1 that could never raise anything, so the one action a building player repeats
  most taught the terminal nothing. A batch therefore raises what the store already knows **first** and never evicts
  what the same action counted; a list that does not fit keeps the item types it named first and reports how many were
  counted. The eviction order among *older* entries is unchanged, so a list still behaves exactly like the clicks it
  replaces.
* **The client sorts; the server owns the data.** The screen is sent the counts and the stored order
  (`TerminalUsagePayload`) and sorts with the same pure comparator the server would have used. It is not the client
  deciding: it counts nothing, bounds nothing, evicts nothing and invents no order — it receives numbers it may only
  read, and its one message back is "the player pressed the button" (`TerminalSortPayload`), which the server
  validates by **name** and stores. Sorting on the server was rejected because the screen must already hold the whole
  bounded stock list — the search narrows it per keystroke and the grid pages through it per scroll, neither of which
  may cost a round trip — so a server-side sort would mean re-sending the list for every keystroke, or sending a rank
  per item, which is the same payload with more bytes. Network version `8`; the counts travel without their recency
  stamps, which only eviction needs.
* **Save data is read by name and never rejected.** The order is saved as `TerminalSort#name()` so the cycle order
  stays a presentation decision, the compound carries a version field, `save` writes **nothing at all** for a player
  who never touched a terminal, and `load` skips an entry whose item cannot be decoded and re-applies every bound —
  including a capacity a config change has lowered since, which then keeps the strongest entries. An NBT serializer is
  used rather than a `Codec`, because the codec-based attachment builder throws on a parse error and one removed mod
  would otherwise fail a whole player's load.
* **The attachment is never synced by NeoForge.** Its `sync` handler would send a player's numbers to every client
  that can see them. The one player with a screen open is sent what that screen needs, and only while it is open.
* **A press is applied at once and reconciled, not waited for** (the client half). The screen sorts with numbers it
  already has, so waiting a round trip would only make the button feel slow — but a push the server had already built
  carries the **old** order and would put the list back into it for two or three ticks. The screen therefore keeps the
  order it has chosen and not yet seen confirmed (`WarehouseTerminalScreen#pendingSort`), ignores a pushed order while
  one is outstanding, takes the counts from that same push regardless, and settles on the first push that agrees with
  what is on screen — which also covers the player cycling right back to the stored order, for which the server has no
  new payload to send. This is the standard shape for client-side prediction of a server-owned value, and it is the
  only place in the mod that needs it: every other screen state is either the server's alone (pushed, never guessed)
  or the client's alone (the search text, the filter).
* **New counts take effect at the next list the player asks for, not the moment they arrive** (the client half, M24
  review fix). Under "most used" the count is the *first* sort key, so the first click on any row gives it a count it
  did not have and pulls it in front of every row with none — and the push carrying that count arrives about a tick
  after the click, while the cursor is still over the cell. The grid's hit test is positional, so a player clicking a
  cell twice (how one request is grown, §7.2) would have asked for whatever slid into that cell, and a crane job is a
  real consequence. The screen therefore keeps the newest counts (`WarehouseTerminalScreen#latestUsage`) and hands
  them to the list on the first push of a screen, on every push that cannot reorder it, and otherwise at the next
  moment the player asks for a different list: a press of the sort button, a keystroke in the search, the filter. The
  alternatives were worse: suppressing the re-sort only while the pointer happens to be over the grid makes the
  behaviour depend on where a mouse rests, and re-anchoring the scroll on the clicked row moves every other row
  instead. Nothing is lost — the store is the server's, and every list the player asks for is built from the newest
  numbers.
* **The server is what checks the client's sorting, in the visual run.** Because the sorting itself happens on the
  client, a client-side assertion over the list the client would draw proves nothing about the numbers behind it — it
  would agree with a client that sorted by the wrong counts. So the `terminal` scenario hands the server the rows the
  screen shows and lets it rebuild the expected list from `WarehouseTerminalMenu#stockCounts` and the player's own
  `TerminalPreferences` before every sort shot (`TerminalVisualScenario#checkRowsOnServer`), and the counts are
  checked against the clicks the scenario made, size included. The same scenario carries the chosen order through a
  real save, quit to the title screen and rejoin: no GameTest server can leave a world, so that is the only automated
  place where "it remembered" is shown on the screen rather than in the data.

*Alternatives rejected:* **a `SavedData` of the level keyed by player UUID**, which would have to be swept for players
who never return and would duplicate what an entity's own save file does for free; **fields on the terminal block
entity**, which makes a player's own order a property of whichever terminal they stood at and would be copied by
`/clone`; **keeping the client static and only adding the third order**, which leaves the issue's "remembered per
player" unanswered and is wrong on any multiplayer server; **counting items rather than requests**, which would let
one bulk click bury a hundred deliberate ones; **counting refusals**, which would let mis-clicks reorder the list;
**counting a clipboard order line by line as it runs**, which has no player to count for and would weight an item by
how many portions it happened to take; **letting the attachment type build its own copy handler**, which writes the
attachment and reads it back on every respawn and would drop an entry whose key a codec happens not to encode, so
`TerminalPreferences#copy` is handed over explicitly instead (M24 review fix); and **a fourth "recently requested"
order**, which the issue raises as a
possibility — the store already records recency, so it is a comparator and a lang key away, but three orders still fit
a cycling button and a fourth would want a menu (open point in `docs/roadmap.md`).

## Persistence & sync

* All authoritative state lives in block entities (controller: aisle layout, index cache, job queue, reservations; crane: state, axis positions, current job, head inventory) and is saved via `saveAdditional`/`loadAdditional` with registry-aware `HolderLookup.Provider` (1.21.1 signature).
* The client receives only what rendering and goggles need through the standard BE update packet. Custom payloads are added only if that is insufficient.
* The mod's chunk holds (M19, ADR-031) are **not** part of this: the hold is derived state and the tickets themselves are NeoForge's own saved data, reconciled against the mod's in-memory record by the validation callback at load. The controller gained exactly **one** NBT key for it, `ChunkKeep` (`{GaveUp, Work}`), written only while the aisle has given up on its work: that bound refuses work which *is* saved, so a flag living only as long as one block entity instance would let every reload take the whole footprint again for work that had already proved unservable (M19 review). An aisle that becomes idle drops the flag, so it can never strand one.
* A terminal's **clipboard order** (M23, ADR-036) is saved in the terminal, beside the clipboard it belongs to: the
  slot under `ListSlot` and the order under `ListOrder` (lines, dropped entries, state, consent budget, the two tick
  stamps restarted from the world's current time). The controller saves **nothing** about it — its set of terminals
  worth looking in on is rebuilt from the world — and a line's request id either still exists in the controller's own
  saved queue or is forgotten on the next pass, which gives nothing back because what a line is missing does not depend
  on it. The question an order was asking is never saved: like every confirmation it is measured against the warehouse
  as it is now, so such an order comes back `RUNNING` and asks again.
* A player's **terminal preferences** (M24, ADR-037) are the one piece of authoritative state that lives on a
  **player** and not in a block entity: the chosen order and the request counts, as a data attachment NeoForge saves
  inside `playerdata/<uuid>.dat` and copies onto the respawned player on death. The copy is
  `TerminalPreferences#copy`, passed to the builder as an explicit `copyHandler`: a type that provides none is given
  one that serializes the attachment and parses it again (`AttachmentType.defaultCopyHandler`), which is a codec
  round trip per respawn and would silently drop an entry a save is right to leave out. Nothing is written for a
  player who never touched a terminal, and a load skips what it cannot read and re-applies every bound — including
  the size bound, so save data from a build without it loses its oversized entries — so neither crafted nor truncated
  data can fail a player's load. The client is sent the numbers for the one screen it has open and owns none of
  them.
* After a restart, jobs resume from the persisted state (`CraneStateMachine.resume` makes a loaded state consistent). There is no `FAULT` phase: if a referenced block is missing, a job aborts with a reason before the pick, and after it the held items are rerouted (`REROUTE`), held and retried (`HOLDING`) or waited with at the requesting output (`WAITING_FOR_TARGET`). Items are dropped only when the dock itself breaks (`stacker-crane.md` §4.1, `warehouse-system.md` §8).

## Performance rules

* No per-tick inventory scans, no world searches. Aisle membership is registered by the blocks themselves on load/placement and unregistered on removal/unload.
* Index updates are incremental: the result of every transfer updates the index directly; snapshots only reconcile drift.
* Controller and crane tick only while they have work or are moving.
* Warehouse interfaces have no ticker at all (`warehouse-system.md` §3.1.1): presence comes from capability invalidation, content hints from `onNeighborChange`, goggle data from player observation.
* Optional chunk loading (M19, ADR-031) is event-driven for the same reason: every place that changes what an aisle has to do sets one boolean, and only the controller's tick may call NeoForge's chunk API. An aisle that holds nothing schedules no re-check at all, so a server with the feature off — the default — pays one boolean and one long compare per controller tick, and the evaluation those ~18 hooks trigger returns after two map lookups while the feature is off (M19 review). Taking is capped at two chunks per tick, because forcing a chunk loads it synchronously.
