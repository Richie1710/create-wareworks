# Stacker Crane (Regalbediengerät)

> Status: **binding design** for MVP 0.1. ADR-007 is decided: block entity with animated parts.

## 1. Concept

The block `wareworks:stacker_crane` is the crane's **dock**: its anchor block and the parking position (aisle position 0). The moving crane is not a set of blocks. It is **logical state** of the dock block entity, rendered as animated parts that travel along the aisle rails.

| Axis | State field | Range | Moved part |
|---|---|---|---|
| X | `posX` (double) | `0 … L` (aisle positions) | base, wheels, mast |
| Y | `posY` (double) | `0 … H-1` (levels) | lift carriage |
| Z | `armExtension` (double) + `armSide` | `0 … 1`, LEFT/RIGHT | telescopic arm, grabber |

## 2. Block

* `StackerCraneBlock extends HorizontalKineticBlock` (the Create 6.0.10 base class). `FACING` is the aisle direction.
* Rotation axis **Y**; `hasShaftTowards` is true only for `Direction.DOWN`.
* Stress impact is registered through Create's stress API with the config value `stressImpact`.
* `ScrollValueBehaviour` "Mast Height" (1…`maxMastHeight`).
* On removal: drop the grabber contents at the dock position and notify the controller.

### 2.1 Implementation (M2: dock)
Classes: `content.crane.StackerCraneBlock`, `StackerCraneBlockEntity`, `RailScan`, `WarehouseRailBlock`; `content.controller.AisleLayout`. Registered as `WareworksBlocks.STACKER_CRANE` / `WareworksBlockEntityTypes.STACKER_CRANE`.

* **Block**: `HorizontalKineticBlock` + `IBE`. The facing property is Create's `HORIZONTAL_FACING` (the `FACING` above) = aisle direction. Placement uses the player's look direction, because Create's default would face the player. Rotation axis Y, `hasShaftTowards` only for `DOWN` (`SHAFT_INPUT`), no `ICogWheel`, so side shafts and cogs never connect. The wrench on the top or bottom face rotates the aisle clockwise through `IWrenchable`, guarded by `canChangeAisleDirection()` (false while the crane has a job, is not idle or holds items; §4.2). `KineticBlock.onRemove` already calls `IBE.onRemove`, so `destroy()` runs on a real break.
* **Shape (M4 review fix)**: outline and collision follow the low rail bed model (§7.1): a 3 px bed (`StackerCraneBlock.BED_HEIGHT_PIXELS`, the height of the warehouse rails) plus the 4 px brass end stop at the controller side, authored north and turned with catnip `VoxelShaper.forHorizontal`. The crane has no shape anywhere in the aisle, parked at the dock or not: players and mobs walk over the bed as over the rails, and the outline never floats over an empty bed. M2 to M4 the dock was a full cube; after the M4 bed model that left an invisible full block, a full outline and floating value boxes at the dock whenever the crane was away (it stays where its last job ended, §4).
* **Registration**: stone properties, netherite sound, `noOcclusion`, pickaxe only, `create:non_movable` + `c:relocation_not_supported`, stress impact `crane.stressImpact` through `WareworksStress.configuredImpact()` (supplier with the safe config getter), horizontal blockstate over the hand-made `models/block/stacker_crane/block.json` (the low rail bed since M4, §7.1), item model `block/stacker_crane/item` (the bed with a miniature crane, M4; in M2 the item model was the block model), drops itself, Create item description (en/de).
* **Mast Height**: the block entity's only value box, a `ScrollValueBehaviour` on the top face of the rail bed (`content.crane.MastHeightValueBox`, a `ValueBoxTransform.Sided` that is active only for `UP` and sits 0.5 px below the 3 px bed top, Create's convention for centred boxes; M4 review fix). The bed's side faces are too low for a value box. While the crane is parked, its chassis stands over the box; Create still shows the hover hint for the targeted bed. (M2 to M4: a `CenteredSideValueBoxTransform` on every face except the bottom of the full cube.) Range `1…maxMastHeight`, re-applied on every refresh (server) and every read (both sides), so a changed config takes effect. Default 4, set by a field write in `addBehaviours` (the behaviour field has no initializer). The callback (server) refreshes the geometry at once. A stored value of 0 (missing tag) loads as the default; a value above the configured maximum is clamped **when it is read** (`mastHeight()`), never in the saved value, so lowering `maxMastHeight` shortens the mast only while that configuration is in force and raising it again restores the player's own height (`warehouse-system.md` §8.1).
* **Kinetics**: nothing crane-specific beyond the input; `getSpeed()` is 0 without power or when overstressed. The moving crane is drawn by `client.render.StackerCraneRenderer` (M4, §7.1); before M4 only the static block model was shown. The shaft below renders itself.
* **Goggles**: "Stacker Crane:", "Aisle: L long, mast H high", "No controller" / "Controller linked", then Create's kinetic stats. The warehouse controller behind the dock links it through `linkController(controllerPos)` and unlinks it through `unlinkController(controllerPos)`, which only the current owner can do (M2 review fix: a controller that lost the dock to another one cannot clear the new link). A controller whose chunk unloads does not notify, so the dock re-validates its owner at its geometry refresh cadence (`validateControllerLink`, one block entity lookup) and clears a lost link. Owner and flag are not saved (the flag is synced); the controller links again after loading. The dock asks that controller to re-link (`notifyControllerBehind`, one block entity lookup) on load, on a geometry change, on rotation (for the old and the new direction) and on removal (`warehouse-system.md` §3.3.1). Everything shown is synced state, so no `GoggleObservers` hook is needed.
* **Crane (M3, §4.2)**: `Clearable#clearContent()` empties the handling head without drops and ends the job (the controller releases its reservations; required against `/clone ... move` duplication), `destroy()` drops the head at the dock with `Containers.dropItemStack` and tells the controller, `canChangeAisleDirection()` refuses rotation during a job. The goggles add the crane status, pause reason, current job and held items (§5.2).
* **Model** (authored with the aisle side north): *superseded in M4* by the low rail bed of §7.1 (andesite bed with the gearbox port below, curbs, the `warehouse_rail` profile, brass end stop at the controller side). The M2 model was andesite casing base and top plates around a dark steel body (`create:block/railway_casing`) with the gearbox port on the bottom face and a rail start on the aisle side.
* **Tests** (`gametest.StackerCraneGameTests`): `cranerailcount`, `cranerailgapandaxis`, `cranerailcap`, `cranegeometryrefresh`, `cranemastheight`, `craneshapeisthebed` (M4 review: outline and collision for all four facings are the bed with the end stop at the controller side, never a full block; the real mast height behaviour answers on the bed top; the transform answers on no other face and not at the old full-cube top), `cranekineticfrombelow`, `cranenosideinput`, `aislelayoutroundtrip`, `craneplacement`, `cranepersistenceandsync`, `craneregistration`, `cranebreak`. Kinetic tests power the dock with a creative motor directly below it (`FACING = UP`); `cranenosideinput` places motors on the four sides and on top with their shafts pointing at the dock and asserts speed 0. The cap test uses the `rail_line_48x5x3` template, so the real `maxAisleLength` default (32) is tested without changing the config.

## 3. Geometry

`AisleGeometry { BlockPos dock; Direction facing; int length; int height }` is computed by the dock:
* `length` = consecutive rail count (see `warehouse-system.md` §1), refreshed every `geometryRefreshTicks` and on neighbour/rail changes near the dock.
* `height` = mast height scroll value.
* It is synced to the client (needed for rendering bounds and mast segments).
* If geometry shrinks while the crane is beyond the new bounds, the crane clamps its targets and, while holding items, reroutes.

### 3.1 Implementation (M2) and deviations
**Deviation: `AisleGeometry` is split.** The record above mixes world data (`dock`, `facing`) with the size, but `core.*` must not import Minecraft types (`architecture.md`). Therefore:
* `core.address.AisleGeometry(length, height)` is the pure size: `contains(x, y)`, `positionCount()`, `rackPositionCount()`, the rack position enumeration `rackPositions()` (unmodifiable random-access view in `RackPosition.ORDER`: position, level, LEFT before RIGHT) with `indexOf` / `rackPosition(index)`, and `scannedLength(...)` (below). Its limits follow the address format: length `0…999`, height `1…999`.
* `content.controller.AisleLayout(dock, facing, geometry, Optional<letter>)` is the world mapping: `rackPos(x, y, side)` (`dock + f·x + side·1 + up·y`, no bounds check), `worldToLocal(pos)` → `Optional<RackPosition>` (empty for the aisle line itself, outside the length or height, or more than one block to the side), `aislePos(x)`, `sideDirection(side)` (LEFT = `facing.getCounterClockWise()`, RIGHT = `facing.getClockWise()`), `bounds()` (full-block AABB of all rack positions, `(L+1) × 3 × H`), `address(rack)` / `addressOf(pos)` once a letter is set. `StackerCraneBlockEntity#layout()` builds it without a letter.
* `core.address.RackPosition(x, y, side)` is the aisle-local source of truth that `warehouse-system.md` §2 refers to.

**Rail counting:**
* `RailScan.scan(level, dock, facing, maxLength)` walks `dock + f·1, f·2, …` with at most `maxAisleLength` block-state reads. It stops at the first position that is not a `warehouse_rail` with `AXIS == f.getAxis()` (gap, other block, rail across the aisle), and it **stops before reading a position whose chunk is not loaded** (`Level#isLoaded`; a scan never loads chunks).
* `AisleGeometry.scannedLength(counted, incomplete, previous, max)`: a complete scan is authoritative; a scan that reached an unloaded chunk keeps the last known length if that is larger (capped at `max`). Unloading part of an aisle therefore never shrinks it. For this rule to survive restarts, the length is saved (`AisleLength`) as well as synced.

**Refresh cadence (server):** at most once per `geometryRefreshTicks` (a stored game time is compared each tick; nothing is scanned in between), immediately in `onLoad()` (which also fires in loaded but non-ticking chunks) and on a mast height change, and on the next tick after a facing change (`setBlockState` override) or `requestGeometryRefresh()`. On a change the dock calls `onGeometryChanged(previous, current)` and `notifyUpdate()` (sync and save). The hook asks the controller behind the dock to re-link, and the controller then marks its membership dirty. Clients invalidate the render bounding box (`layout().bounds()` inflated by 1) when the synced geometry changes.

**Deviation: no refresh on "neighbour/rail changes near the dock".** Rails lie along the whole aisle, not near the dock, and letting every rail find its dock would be a world search. The periodic refresh bounds the delay after placing or breaking a rail to `geometryRefreshTicks` (2 s by default); mast height and facing changes apply at once.

## 4. State machine

```text
            assignJob
IDLE ───────────────────▶ TRAVEL_TO_SOURCE ──▶ EXTEND_SOURCE ──▶ PICK ──▶ RETRACT_SOURCE
  ▲                                                                              │
  │                                                                              ▼
  └── COMPLETE ◀── RETRACT_TARGET ◀── DROP ◀── EXTEND_TARGET ◀── TRAVEL_TO_TARGET
                        │
                        ├─ leftovers ─▶ REROUTE ──(new target)──▶ TRAVEL_TO_TARGET
                        │                  └─(none)──▶ HOLDING ──(retry)──▶ REROUTE
                        └─ output full ─▶ WAITING_FOR_TARGET ──(retry)──▶ EXTEND_TARGET
```

Additional phases/flags:
* `RETURNING`: optional travel back to position 0 when idle for `returnHomeDelayTicks`. MVP default: stay in place, so the next job starts from the current position.
* `paused` is not a phase. It means `speed == 0`, overstressed, or the required chunk is not loaded. Time-based progress stops while paused.

Rules:
* The arm always retracts fully before X/Y motion.
* X and Y move **simultaneously** toward their targets. The arm extends only when X and Y are exactly at target.
* `PICK` and `DROP` last `transferTicks`. The real item operation happens once, at the **end** of the phase, on the server.
* Transitions are pure functions in `core.crane.CraneStateMachine`. The block entity executes world effects (extract/insert) and feeds results back.

### 4.1 Implementation (M3, core logic) and deviations
Classes in `core.crane` (pure Java, generic over key `K` and location `L`; rack coordinates through a `Function<L, RackPosition>`): `CranePhase`, `CranePose`, `CraneState`, `CraneTimings`, `CraneEvent`, `CraneEffect`, `CraneInterruption`, `AbortReason`, `CraneStateMachine`, `CraneMotion`. JUnit: `CraneMotionTest`, `CraneStateMachineTest`, `CraneConservationPropertyTest`.

* **State** (`CraneState`, immutable record): `pose`, `previousPose` and `target` (`CranePose`: x, y, arm `0..1`, side), `phase`, `phaseTicks`, `retryTicks`, `awaitingResult`, `paused`, `job` (`TransportJob`; the held items are `job.heldAmount()` of `job.key()`) and `interruption`. The canonical constructor validates single fields; `isConsistent()` checks that they fit together (a job in every phase except `IDLE`, nothing picked before `RETRACT_SOURCE`, items held from `TRAVEL_TO_TARGET` on, nothing held in `COMPLETE`). The block entity keeps the real item stacks of the head next to this state.
* **Transition function:** `CraneStateMachine.apply(state, event)` → `Transition(state, effects, changed)`.
  * Events (`CraneEvent`): `Tick(speeds)`, `JobAssigned`, `PickResult`, `DropResult(delivered, leftover)`, `RerouteResult` (target + kind, or none), `OutputFull`, `TargetMissing`, `SourceMissing`, `JobCancelled`, `Paused`, `Resumed`.
  * Effects (`CraneEffect`): `PerformPick`, `PerformDrop`, `RequestReroute`, `ReportPicked`, `ReportDelivered`, `ReportRerouted`, `ReportComplete`, `ReportAbort(reason)`, `PhaseChanged` (sync hint).
  * Events that do not apply to the current phase change nothing (`changed == false`), so stale results are harmless.
* **Timing:** a motion phase ends in the tick its target pose is reached; a phase whose target is already reached on entry ends at once (e.g. a job that starts at its source). A whole job therefore takes exactly `TravelTimeModel.tripTicks` ticks (JUnit). `PICK` and `DROP` count `transferTicks`; in the last tick the machine emits the perform effect, and the block entity answers with the real result in the same tick. An unanswered perform effect (e.g. chunk not loaded) is repeated every unpaused tick; a picked job is never picked again. An unanswered `RequestReroute` counts as "none" on the next tick (`HOLDING`).
* **Flows** (diagram above): a zero pick → `RETRACT_SOURCE` → `IDLE` with `ReportAbort(ZERO_PICK)`. Leftovers after a drop → `RETRACT_TARGET` → at the output station of the job's request `WAITING_FOR_TARGET` (after `retryTicks` back to `EXTEND_TARGET` and a new drop), at any other target `REROUTE` → `TRAVEL_TO_TARGET`, or `HOLDING` (after `holdRetryTicks` back to `REROUTE`). **M3 review fixes:** `RequestReroute` names the failed target only in the reroute right after the failure (failed drop, missing target, cancellation); a `HOLDING` retry names none, so a former target that accepts items again (emptied, or placed again at the same position) is used. Before, every retry excluded it, and a crane could hold for ever while the aisle stood still. An output station the job serves no request for (its request was lost, or the leftovers were rerouted there) is not waited for: its leftovers are rerouted like those at any other target. `COMPLETE` lasts one tick and then returns to `IDLE`; it already accepts the next `JobAssigned`.
* **Interruptions (addition):** the design names "target gone / full" only for the target side.
  * Before the pick, `SourceMissing`, `TargetMissing` and `JobCancelled` abort the job, after retracting an extended arm, with the reason in `ReportAbort`. A controller can therefore cancel a retrieve job whose request or output disappeared (M2 note, `warehouse-system.md` §7.2).
  * After the pick, `TargetMissing` and `JobCancelled` reroute the held items (after retracting). `OutputFull` (output targets only) makes the crane retract and wait (a job without request reroutes), or restarts the wait.
  * A job with held items is never aborted.
* **Pause:** the `paused` flag (`Paused` / `Resumed`) or a tick with all speeds 0 freezes motion, transfer and retry timers and emits nothing. `previousPose` is set to the pose, so rendering does not jitter.
* **Contract violations** (more picked than planned, `delivered + leftover ≠ held`, a reroute kind the job type does not allow) throw `IllegalArgumentException`, because accepting or ignoring them would lose track of real items.
* **Loading:** `resume(state)` makes a loaded state consistent and recomputes its target pose. Items are never dropped: held items in `IDLE` or `COMPLETE` go to `HOLDING`, and a picked job in a phase before the pick retracts. A job without items that cannot continue is aborted (`CANCELLED`), a finished job completes, and an awaited result or interruption in the wrong phase is cleared or handled by retracting.
* **Deviation from the originally sketched phases** (`MOVING_TO_SOURCE`, `LIFTING`, `TRANSFERRING`, `FAULT`): lifting happens during travel (rules above), and faults are the explicit `REROUTE` / `HOLDING` / `WAITING_FOR_TARGET` phases or aborts with a reason, as designed in this section.

### 4.2 Implementation (M3, crane execution) and deviations
Classes: `content.crane.StackerCraneBlockEntity` (state, head, sync, goggles, lifecycle, API for the controller), `CraneExecution` (package-private: server tick and effects), `CranePersistence`, `CraneGoggleInfo`, `CraneJobSummary`, `CranePauseReason`; the handling head in `content.crane.head` (§6.1). The controller side is `warehouse-system.md` §7.5. GameTests: `gametest.CraneJobGameTests`.

* **State**: the dock owns the authoritative `CraneState<ItemKey, RackPosition>` (with the job) and an `InventoryGrabber` with the real items. Invariant: the job's held amount equals the head's count of the job key.
* **Server tick** (`CraneExecution#tick`, after the geometry refresh; nothing runs in a chunk that does not tick, ADR-013):
  1. A crane with a job but no linked controller lets the controller directly behind the dock link it now (`WarehouseControllerBlockEntity#relinkNow`; at once after loading, then at most every 20 ticks; **M3 review fix**, `warehouse-system.md` §7.5), so no report is dropped because the dock ticked first. A loaded state is made consistent once: `reconcileHeadWithJob` (items of other keys are dropped at the dock, never deleted; a job whose held amount differs from the head is rewritten to follow the head), then `CraneStateMachine.resume` and its effects.
  2. Resting phases (`IDLE`, `HOLDING`, `REROUTE`) clamp their target into the current geometry, so a crane outside a shrunken aisle moves back (§3).
  3. Location check every `CraneExecution.LOCATION_CHECK_INTERVAL_TICKS` (20), and at once after entering a travel phase or a geometry change: before the pick the source and the target must resolve (`TransferContexts.resolve`; missing → `SourceMissing` / `TargetMissing` → abort after retracting), after it the target (missing → reroute). A location outside the geometry is missing; an unloaded one never is.
  4. Speeds: `CraneKinematics.speeds(config factors, getSpeed())` (`getSpeed()` is 0 without rotation and while overstressed).
  5. Pause reason (`CranePauseReason`): no rotation, overstressed (`isOverStressed()`), or chunk not loaded (the aisle column under the crane, or the rack position of the current stop and, for storage, its inventory position). A change applies `Paused` / `Resumed`.
  6. The `Tick` event, then every event that the effects produce, in the same tick (a queue, at most 64 events per call).
  7. Publication (goggle data, `setChanged`, `sendData`) on a phase, target, job, head or pause change and every `CraneExecution.MOVING_SYNC_INTERVAL_TICKS` (20) while moving.
* **Effects**: `PerformPick` / `PerformDrop` resolve the location: not loaded → no answer (the machine repeats the effect every unpaused tick); missing → `SourceMissing` / `TargetMissing`; available → the real transfer through the head, whose real result is the answer. At an output station a simulated insert of one item decides "full" first (`OutputFull`, the inventory is not touched). `RequestReroute` asks the linked controller (`planReroute`); without controller the answer is "none" (`HOLDING`, retried every `holdRetryTicks`). Reports go to the linked controller only: `onCranePicked`, `onCraneDelivered`, `onCraneRerouted`, `onCraneJobFinished`, `onCraneJobAborted`.
* **Requests (refinement)**: a rerouted job detaches its request (`TransportJob#withoutRequest`), because the request's destination is one output station; retrieve leftovers rerouted into storage (or, as the last resort, into another output; `warehouse-system.md` §7.4) no longer count for it, and the request is served again by a new job. A delivery that the controller does not count for an open request (request completed, cancelled or unknown to an adopting controller) detaches it as well.
* **API for the controller**: `canAcceptJob()` (dock chunk ticking, resumed, idle or just complete, head empty, not paused, powered), `assignJob(job)` (returns whether the crane took it), `cancelJob(jobId)` (abort before the pick, reroute after it; a loaded state that is not resumed yet is resumed first), `currentJob()`, `heldItems()`, `craneState()`, `currentSpeeds()`, `pauseReason()`, `goggleInfo()`, `linkedControllerEntity()`.
* **Without controller** the crane finishes a job whose target is valid and holds items it cannot deliver; a controller placed or loaded later adopts the job (`warehouse-system.md` §7.5).
* **Lifecycle**: `destroy()` drops the head at the dock (`TransferContexts.spillAt` → `Containers.dropItemStack`, split into valid stacks) and calls the controller's `onCraneJobLost`; `clearContent()` releases the job the same way and empties the head without drops; `canChangeAisleDirection()` is false unless the phase is `IDLE` without job and nothing is held (clients use the synced phase and held items). A refused wrench shows an action bar message (`wareworks.crane.rotation_locked`, sent by the server; **M3 review**: before, nothing happened at all). Sounds are M4: hook `onPhaseChanged(from, to)`.
* **Persistence** (`CranePersistence`, never throws, untrusted values clamped): `Crane {Pose {X, Y, Arm, Side}, Target, Phase, PhaseTicks, RetryTicks, Awaiting, Interruption?, Job? {Id, Type, Source {X, Y, Side}, TargetLocation, TargetKind, Item: ItemKey, Planned, Request?, Picked, PickedAmount, Delivered}}` and `Head {Items [{Item: ItemKey, Count}]}`. Rack positions are aisle-local. The pause flag is not saved (recomputed every tick). An unreadable job is dropped; its items are then dropped at the dock by the reconcile. Create's schematic "safe NBT" (`SmartBlockEntity#writeSafe`) does not call `write`, so neither job nor head leak into schematics.
* **Contract violations** (a foreign inventory reporting results that contradict the state) are logged at most once per `util.LogThrottle` interval (1200 ticks, **M5 release audit**: a one-shot flag hid every later violation of that crane); the job follows the real head and the state is resumed on the next tick. No item is created or deleted.
* **Deviations**:
  * A configured speed factor of 0 (the config range allows it) makes `currentSpeeds()` return `STOPPED`: that axis would otherwise never arrive while the others move. **M5:** the crane no longer shows "no rotation" for this, which sent players looking for a broken shaft. `CraneKinematics.Params#hasZeroFactor` / `#zeroFactorNames` (pure, JUnit `TravelTimeModelTest#zeroSpeedFactorsAreDetectedAndNamed`) name the guilty keys; `CraneExecution#pauseReasonFor` checks them before rotation and overstress and reports the new `CranePauseReason.SPEED_FACTOR_ZERO` ("a crane speed factor is 0 in the server config", en + de), and logs one `WARN` per server run naming the keys (`content.crane.CraneServerHooks` resets the rate limit on `ServerStartingEvent`, so the line appears again in the next session of a single-player client, whose JVM outlives its integrated servers). GameTest `configzerospeedfactorpausescranes`.
  * "Chunk not loaded" checks the aisle column under the crane and the current stop, not every block of the path: the crane touches the world only at its stops, and the dock chunk must tick anyway.
  * `HandlingHead#drop` takes the key and the amount (§6.1).
* **GameTests** (`gametest.CraneJobGameTests`, creative motor below the dock at 128 RPM, item conservation checked every tick): `cranestoreendtoend`, `craneretrieveendtoend`, `cranestoreconsolidates`, `cranespillreroutes`, `cranenorotationpauses`, `cranepersistencemidjob`, `cranebrokenmidjobdropshead`, `cranewaitsforfulloutput`, and from the M3 review `cranereloadduringdropcountsdelivery`, `craneheadsurvivesthrowinginventory`, `craneclearcontentreleasesjob`, `cranesourceremovedbeforepickaborts`, `cranezeropickaborts`, `cranerequestcancelledbeforeandafterpick`, and from the M5 release audit `cranecraftedheadisboundedandspilled` and `cranejobfollowsacraftedhead` (crafted `Head` data: bounded loading, stray items dropped at the dock instead of kept or deleted, and a job rewritten to follow the real head) (details in `warehouse-system.md` §7.5). Player-setup scenarios with a per-tick census of every item are in `gametest.CraneScenarioGameTests` (`warehouse-system.md` §7.6).

## 5. Motion

```text
vx = min(|rpm| · travelBlocksPerTickPerRpm, maxBlocksPerTick)
vy = min(|rpm| · liftBlocksPerTickPerRpm,  maxBlocksPerTick)
va = min(|rpm| · armExtendPerTickPerRpm,   1.0)
```

`core.crane.CraneMotion.step(state, targets, vx, vy, va)` is deterministic. It runs on both server and client, so the client can animate between sync packets. Server state is authoritative. The client snaps to synced values when they diverge by more than `0.5` block, otherwise it keeps its own smooth simulation. Previous positions are kept for partial-tick interpolation.

Sync (`sendData`) happens on phase change, job assignment/finish, target change, grabber contents change, geometry change, and every 20 ticks while moving (drift correction).

### 5.1 Implementation (M3, core logic) and deviations
* `core.job.CraneKinematics.speeds(Params, rpm)` computes `CraneSpeeds(vx, vy, va)` with the formulas above (sign ignored, NaN → 0). `core.job.TravelTimeModel` gives the tick-exact durations the planner ranks with (`warehouse-system.md` §7.4). Both live in `core.job`, because the planner needs them and `core.job` does not depend on `core.crane`.
* `core.crane.CraneMotion.step(pose, target, speeds)`, and `step(state, speeds)`, which also stores the previous pose:
  * While X or Y differ from the target, an extended arm retracts first; X and Y do not move in a tick in which the arm is not fully in.
  * With the arm in, X and Y move simultaneously.
  * The arm moves only when X and Y equal the target exactly; it retracts first when it has to change sides.
  * Each axis snaps exactly onto its target once the remaining distance is at most `speed + 1e-6` (`TravelTimeModel.SNAP_EPSILON`), so it never overshoots and "at target" is an exact comparison.
* **Signature deviation:** the design's `step(state, targets, vx, vy, va)` takes the target from the state (`CraneState.target`, set by the state machine per phase) and the speeds as one `CraneSpeeds` record.
* Server and client run the same function. JUnit checks that stepping a synced state on the "client" reproduces the server's poses exactly, and that the number of steps equals `TravelTimeModel`. The 0.5 block snap and the resync cadence are block entity work.
* `CranePose.lerp(previous, current, partialTicks)` / `CraneState.interpolatedPose` give the render pose; `CranePose.sanitized(...)` accepts untrusted saved or synced values without throwing.

### 5.2 Implementation (M3, sync and client motion)
* **Client packet** (`write(..., clientPacket = true)`): `CraneSync {Pose, Target, Phase, Paused}` and `CraneGoggles` (`CraneGoggleInfo`: phase, pause reason, job summary with the item id, amount and both rack positions, at most 4 held item types with counts, the controller's aisle letter). Never item components and never the head's stacks, so the size is bounded: a few kilobytes in NBT size accounting including Create's kinetic data (GameTest limit 8 KB; the chunk packet quota is 2 MB).
* **Server cadence**: phase, target, job, head, pause or geometry changes and every 20 ticks while moving (§4.2).
* **Client tick**: `CraneMotion.step` towards the synced target with the speeds from the synced kinetic speed and the config factors (safe getters); while paused or stopped the previous pose is set to the pose. Phase changes and transfers never happen on the client.
* **Snap**: the first packet always snaps. Later packets snap when x, y or the arm differ by more than that axis's snap distance, `max(SNAP_DISTANCE (0.5), SNAP_SPEED_TICKS (2) · axis speed per tick)`, or the arm side differs while both arms are out (**M3 review, deviation from §5's fixed 0.5 block:** above 192 RPM one tick of travel exceeds 0.5 blocks, so a packet arriving a tick early or late snapped fast cranes on most packets); otherwise the client keeps its smooth pose and only adopts phase, target and pause flag. The rule itself is the pure `core.crane.CraneResync` (`snapDistance`, `diverges`), with JUnit `CraneResyncTest` (**M5 release audit**: it was private to the block entity and had no test at all, although a regression would only have shown as a visibly jumping crane).
* **M4 hooks**: `renderPose(partialTicks)` (= `CraneState#interpolatedPose`), held items by type in `goggleInfo().held()`.
* **Goggles**: "Stacker Crane:", "Aisle: L long, mast H high", controller link, "Status: <phase>", "Paused: <reason>" (only if paused), "Storing / Retrieving <item> xN" with "From A-01-00R to A-01-05L", "Holding:" with the held items or "Grabber empty", then Create's kinetic stats. Addresses use the linked controller's letter, or `LL-PPS` without controller.

## 6. Handling head abstraction

```java
public interface HandlingHead {
    /** Maximum items of this key that one trip can carry. */
    int carryLimit(ItemKey key);
    /** Extract from the source location into the head (real operation). Returns picked amount. */
    int pick(TransferContext ctx, ItemKey key, int amount);
    /** Insert held items into the target (real operation). Returns delivered amount; leftovers stay held. */
    int drop(TransferContext ctx);
    HeldItems held();              // persisted by the crane
}
```

MVP implementation: `InventoryGrabber` (IItemHandler source/target). Future: pallet/fork handler, package handler, fluid handler.

### 6.1 Implementation (M3) and deviations
Classes in `content.crane.head`: `HandlingHead`, `HeldItems`, `InventoryGrabber`, `TransferContext`, `TransferContexts`.

* **`HandlingHead`**: `carryLimit(key)`, `pick(TransferContext, key, amount)`, `drop(TransferContext, key, amount)`, `held()` → `HeldItems` (distinct keys with `int` counts), `count(key)`, `isEmpty()`, `spill(level, pos, predicate)`, `clear()`, `save` / `load`. **Deviation:** `drop` takes the key and the amount instead of `drop(ctx)`, because the crane reports each drop for its job's key and must never deliver more than the job holds.
* **`InventoryGrabber`** (MVP head):
  * Holds `ItemKey → int`, so amounts above one stack (`grabberStacks` > 1) and above 99 need no special handling; transfers split into stacks of at most the key's max stack size.
  * `pick` extracts stack by stack with real calls until the amount is reached or the source gives nothing, verifies every stack against the key (a different item goes back to the source, or is dropped there if it does not fit), and gives back any surplus. `drop` inserts stack by stack until the target refuses; only accepted items leave the head. Both loops are bounded (1024 calls) and catch exceptions of foreign inventories. **M3 review fix:** the storage context makes one handler call per slot and catches a failure inside its slot loops: `extract` returns what the earlier slots handed out, `insert` returns what really did not go in, and a give-back that fails is spilled. Before, a throw after a partial multi-slot transfer lost the extracted items or duplicated the inserted ones. The granularity is one handler call: a single call that changes the inventory and then throws cannot be detected.
  * `carryLimitFor(key)` = `CapacityMath.carryLimit(maxStackSize, grabberStacks, grabberMaxItems)`, shared with the planner.
  * Persistence `{Items: [{Item: ItemKey, Count: int}]}`; `ItemStack.save` is never called and saving never throws. Loading never throws and is bounded against crafted data (block entity data on items, `/data`, structures, schematics): at most 16 keys and 4096 items, far above a legitimate head (the carry limit is at most 1728). GameTest `cranecraftedheadisboundedandspilled` (**M5 release audit**: both bounds and the stray-item spill of `reconcileHeadWithJob` had no test at all, although the station equivalent had one).
* **`TransferContexts.resolve(level, layout, rack, kind)`** → `AVAILABLE` (with a context), `UNLOADED` (the rack position, or for storage the inventory position, is not loaded; never loads chunks) or `MISSING` (outside the geometry, no aligned member of that kind, no attached inventory). Cost: at most two `isLoaded` checks, one block entity lookup and a capability cache read.
  * Storage: the item handler behind the interface (`StorageMember#attachedHandler`, its `BlockCapabilityCache`). Extraction iterates the slots and extracts the exact key, giving back mismatches and surplus; insertion follows `ItemHandlerHelper.insertItemStacked` (same slot order) one slot call at a time; simulations still use `insertItemStacked` and rethrow failures (the controller counts them as 0).
  * Input station: `WarehouseInputBlockEntity#extract` (one stack per call), `#insert` (rerouted store leftovers go back into the buffer, M3 addition), `#countOf`.
  * Output station: `WarehouseOutputBlockEntity#insert` only.
  * The same contexts give the controller's live simulations: `simulateExtract` sums simulated extractions per slot (a drawer-like slot counts one stack, so it never promises more than a real pick), `simulateInsert` simulates `insertItemStacked`.

## 7. Rendering

A Create `SafeBlockEntityRenderer` on the dock block entity. No Flywheel visualizer is registered, so the BER renders with and without a Flywheel backend.

| Part | Model | Transform |
|---|---|---|
| dock | normal block model (casing + rail start) | static |
| base + wheels | `wareworks:block/stacker_crane/base` | translate `f · posX` |
| cog | Create `SHAFTLESS_COGWHEEL` partial | on base, rotated by kinetic angle |
| mast | `wareworks:block/stacker_crane/mast` × H segments + `mast_top` | translate `f · posX`, `up · i` |
| lift carriage | `wareworks:block/stacker_crane/carriage` | translate `f · posX + up · posY` |
| telescopic arm | `arm_outer`, `arm_inner` | outer: `side · armExtension · 0.5`; inner: `side · armExtension` |
| grabber | `wareworks:block/stacker_crane/grabber` | at arm tip |
| held items | `ItemRenderer` | on grabber, small scale |

* Models are Blockbench-compatible JSON block models using existing Create textures (`create:block/...`).
* Partial models are created in a client-only class that is loaded from `WareworksClient` before model baking.
* Render bounding box covers the whole aisle: `dock … dock + f·L + up·(H+1)`, inflated by 1 on every side.

### 7.1 Implementation (M4, renderer) and deviations
Classes: `client.render.StackerCraneRenderer` (`SafeBlockEntityRenderer<StackerCraneBlockEntity>`), `client.render.WareworksPartialModels` (static `PartialModel.of` fields, `init()` from the `WareworksClient` constructor), `client.render.CraneModelLayout` (model dimensions and pose math, pure Java). The renderer is registered only through Registrate on `WareworksBlockEntityTypes.STACKER_CRANE` (`.renderer(() -> StackerCraneRenderer::new)`, the codebase's one path). JUnit: `CraneModelLayoutTest`. Visual smoke tests: `aisle` (real jobs) and `poses` (fixed poses for all four facings), run with `./gradlew runVisualTest`.

**Models** (`models/block/stacker_crane/`, hand-made Blockbench JSON, Create textures only; authored like the dock: aisle direction north (-Z), right rack side east (+X), y = 0 at the floor of the crane's level):

| Part | File | Look | Placement (blocks, relative to the dock) |
|---|---|---|---|
| dock | `block.json` | low rail bed: andesite bed with gearbox port below, 2 px curbs, the `warehouse_rail` profile, brass end stop at the controller side | static block model; outline and collision are the bed (§2.1) |
| base | `base.json` | andesite chassis (y 5..8 px) carried by two industrial iron bogie frames (x 1..2 / 14..15 px, y 3.5..7) with an axle beam between the wheels (y 3.25..5) and brass buffers at the front | `f · posX` |
| wheels | `wheel.json` × 2 | octagonal iron wheels (radius 2 px, 6 px wide) on the rail head (axles at y 5, z 3.5 / 12.5 px) | `f · posX`, turned `-posX · 16 / 2` rad about the lateral axis |
| drive cog | Create `AllPartialModels.SHAFTLESS_COGWHEEL` at 0.35 scale | in front of the chassis, axis along the aisle | `f · posX`, `KineticBlockEntityRenderer.getAngleForBe` + `kineticRotationTransform` (kinetic angle, overstress tint) |
| mast | `mast_segment.json` × H | two 3 px industrial iron columns (x 3..6 / 10..13 px) with a dark web and a toothed rack (`controller_rail_base`, `render_type` cutout) in the middle bay, closed by a brass flange band per segment (x 2..14 px, y 13.5..16); at the back of the crane block (z 12..15.75 px) | `f · posX + up · (0.5 + i)` |
| mast cap | `mast_top.json` | wide brass cap (x 2..14 px) with andesite cheeks and the hoist drum | `f · posX + up · (0.5 + H)` |
| hoist belt | `hoist_belt.json` | Create elevator belt (3.5 px wide) in front of the mast | from the carriage top `posY + 15/16` up to the cap: whole pieces plus one piece scaled to the remainder |
| lift carriage | `carriage.json` | C-shaped brass bracket: a front plate (x 3..13, z 11..12 px) and two 2 px guides (x 1..3 / 13..15 px, z 12..13.5) that hold the mast at its front half only, plus the deck with a raised lip and the belt clamp | `f · posX + up · posY` |
| telescopic arm | `arm_outer.json`, `arm_inner.json` | stages lying on the deck (andesite/iron, dark steel with brass edges) | outer `side · armExtension · 0.5`, inner `side · armExtension` |
| grabber | `grabber.json` | brass plate with top jaw and fingers at the inner stage tip, at most 12.5 px high (it fits the arm ports of interfaces and stations, deviations below) | with the inner stage |
| held items | `ItemRenderer`, `ItemDisplayContext.FIXED`, scale 0.34 | flat items lie on the inner stage, block items stand on it; up to 2 item types, a second turned copy from 32 items | with the inner stage |
| item | `item.json` | the dock bed with a miniature crane (opaque textures, own display transforms) | item model |

* **Frame**: the pose stack is moved `f · posX` and turned once about the block centre with Flywheel's `rotateToFace(facing)` (north is the identity). The arm assembly (stages, grabber, items) is turned 180° about the block centre for `Side.LEFT`, so its +X extension points to `facing.getCounterClockWise()`; `Side.RIGHT` points to `facing.getClockWise()` (as `AisleLayout#sideDirection`).
* **Interpolation**: `StackerCraneBlockEntity#renderPose(partialTicks)` (previous and current client tick of the shared `CraneMotion` simulation, §5.2).
* **Light**: `LevelRenderer.getLightColor` at the block each part is in: the aisle block for base, wheels and cog, the crane column block for each mast segment, the cap and each belt piece, the carriage's block for the carriage. Arm stages, grabber and items take the brighter of the carriage block and the block the moved part is in (`SuperByteBuffer.maxLight`), so parts inside a solid rack block (dark inside) keep the aisle's light.
* **Buffers**: all block geometry goes into one `RenderType.cutoutMipped()` consumer; held items are rendered last (rendering an item requests other render types, which ends the shared batch of a kept `VertexConsumer`). The dock's own block model is a normal chunk model.
* **Culling**: `shouldRenderOffScreen` is true; `getViewDistance()` is `64 + maxAisleLength + maxMastHeight` (safe config getters). The render bounding box is the dock's `createRenderBoundingBox()` (every rack position, inflated by one block, so up to `H + 1` above the dock), which `SafeBlockEntityRenderer#getRenderBoundingBox` forwards; the client invalidates it when a synced geometry or facing changes.
  * **Limit (M4 review):** these settings only stop frustum and distance culling. Vanilla collects off-screen block entities while it compiles their chunk section (`SectionCompiler`: `shouldRenderOffScreen` → `globalBlockEntities`), and sections are compiled only within the client's render distance, and only for chunks the server sends within its view distance. So the crane is drawn only while the **dock's** chunk section is within the client's effective render distance, even when the crane itself is close to the player: at render distance 6 (96 blocks) a player at the far end of a 128-rail aisle sees no crane. The default aisle (`maxAisleLength` 32) is far inside every usual render distance; the config comment of `maxAisleLength` names the limit. Create's pulleys and chain conveyors have the same limit. If it matters, a later milestone can add a per-chunk proxy renderer along the aisle.
* **Flywheel and Ponder**: no visualizer is registered, so the same renderer draws with a Flywheel backend and with `/flywheel backend off` (both passes of both visual scenarios render identically). Nothing checks `VisualizationManager`; light goes through `BlockAndTintGetter` and the kinetic angle through Ponder-aware `AnimationTickHolder`, so it works in Ponder levels by construction. **Confirmed in M5:** the Ponder scenes (`architecture.md` ADR-016) render the crane through this renderer, with Flywheel on and off.
* **Client poses (the Ponder pose API of ADR-013)**: `StackerCraneBlockEntity#showClientPose(pose, target, phase, held)` shows the crane at `pose` in `phase` holding `held` on a client or Ponder level (`CraneState.displayed`: no job, no timers), and the client tick moves it towards `target` with the shared `CraneMotion` at the speeds of the dock's kinetic speed (without rotation it stays put); a scene animates the crane by setting the next target. `showClientPose(pose, phase, held)` is the same with the pose as its own target, a fixed pose (the `poses` visual test). Nothing changes phase and no item moves; the next server packet takes over as usual, and on a server level it does nothing. JUnit `CraneMotionTest#displayedStateMovesTowardsItsTarget`. *M4 review fix:* the first version only had the fixed pose, so a Ponder scene could not have animated the crane. **M5:** the scenes use exactly this API through `client.ponder.scenes.CraneScript`, which sets the next target and idles for as many ticks as the same pure `CraneMotion` needs to reach it (ADR-016).

**Deviations:**
* **Dock model.** The M2 dock was a full block (steel body between andesite plates). The crane stands in the dock column at position 0, where rack positions exist beside the dock, so a full block hid the parked crane's base and carriage and the arm would have come out of its sides. The dock model is now a low rail bed that the parked base stands on. Its outline and collision are the bed as well, and the "Mast Height" value box sits on the bed top (§2.1, M4 review fix: the first version kept the full cube, which left an invisible block, a full outline and floating value boxes at the dock while the crane was away). The item gets its own `item.json`, because the bed alone would be a flat icon.
* **Mast placement.** The mast stands on the chassis (0.5 blocks above the floor) at the back of the crane block, towards the dock end, so the carriage deck and the arm are in front of it. The cap therefore sits at `H + 0.5`, inside the render bounds of `H + 1`.
* **Additional parts.** Wheels are a separate partial (turned by the travelled distance) and the hoist belt was added as visible lift mechanics; the design table names neither. Part files are named after `WareworksPartialModels` (`mast_segment`, `wheel`, `hoist_belt`).
* **Arm reach and ports.** The inner stage moves exactly one block at full extension, as designed. The retracted grabber's front face is at 15.5 px, so at full reach it stops 0.5 px before the inventory beyond the rack position. A crane only ever transfers at a member block in the rack position (interface or station), so the arm always goes into that block. **M4 review fix:** it enters through a port on the member's aisle side instead of through a solid face. Stations already had a 10 x 10 px opening recessed 3 px (y 3..13). The interface's flush andesite plate got an 8 x 4 px dark slot recessed 3 px (x 4..12, y 9..13, `warehouse-system.md` §3.1.1). The grabber was lowered to 12.5 px (plate, fingers and top jaw), so stages, grabber and flat items pass both ports without touching the block; beyond the 3 px recess they are hidden behind its dark back wall, and after a pick the items come out on the retracting arm. `CraneModelLayoutTest#armPassesThroughTheMemberPorts` sweeps every arm part and flat item through the port depth of the interface, input and output models. **M10 (ADR-022):** the warehouse terminal's port moved out of its `block.json` into `shell_intake.json`, the shell its multipart blockstate puts on the aisle side, because any of its four faces can be that side; the opening itself is unchanged (the interface's 8 x 4 px slot at x 4..12, y 9..13 over the full 3 px depth), and the same sweep runs against that shell. The M10 **look pass** (ADR-023) then touched only the other three shells — the screen, its tray and the machine panel are recesses on faces the arm never reaches — so this geometry, and the sweep that pins it, are unchanged by it. **Accepted:** block items stand 2.7 px tall on the stage (up to 14.2 px, a second copy higher) and pass the 3 px port lintel while the arm enters or leaves; shrinking them enough to fit would make them specks. The empty rack positions of the `poses` scenario show the whole reach.
* **Model fixes (M4 review).** The wheel radius is 2 px (was 2.5): the disc turned by 45° reaches `r·√2` from the axle, which poked about 1 px through the chassis top at every pose, and the parked plain disc shared the chassis top plane (z-fighting). The hoist belt is 3.5 px wide (was 4), because its side faces shared the planes of the carriage's belt clamp. `CraneModelLayoutTest#wheelsStayInsideTheChassisAtEveryAngle` and `#noPartsShareAFacePlane` (same-facing faces overlapping in one plane between wheels and chassis, belt and carriage, carriage and chassis or mast, and arm parts at every extension) guard both.
* **Visual weight (M5 release polish).** The first crane read as a thin dark pole on a rail. Three changes, all inside
  the existing partials and the existing test bounds: the mast columns grew from 2 to 3 px and moved apart (x 3..13
  instead of 4..12), each segment ends in a **brass flange band** 1 px proud on both sides (x 2..14, y 13.5..16), which
  gives a mast of any height a visible rhythm instead of one smooth tube; the carriage became a C-bracket with 2 px
  guides that grip only the **front half** of the mast (z 12..13.5), which is what freed the mast's back half for those
  proud bands; and the base got two iron bogie frames, an axle beam between the wheels and 6 px wide wheels. The
  interlock is tight by construction: the carriage owns x 1..3 / 13..15 at z 12..13.5 and x 3..13 at z 11..12, the mast
  owns everything else behind z 12, and the belt clamp keeps the middle bay (x 6..10) free up to z 12.
  `CraneModelLayoutTest` enforces all of it (no overlap at any lift height, no shared face planes, wheels inside the
  chassis at every angle).
* **Held items** are synced by item type only (`CraneGoggleInfo#held`, no data components, §5.2), so the carried items are drawn as plain items of that type (no enchantment glint, no custom names or models from components).

## 8. Sounds

Create-like, short, server-played at the crane's world position. Movement start/stop and pick/drop use existing Create or vanilla sound events, chosen in M4.

### 8.1 Implementation (M4) and deviations
Classes: `core.crane.CraneSoundCues` (pure Java: when a cue plays; JUnit `CraneSoundCuesTest`), `content.crane.CraneSounds` (package-private, owned by `CraneExecution`: cue → sound event, position, volume, pitch). No custom sound events, no `sounds.json`, no client code.

* **Cues and events** (all `SoundSource.BLOCKS`, played once on the server with a `null` player, so every player within the event's range hears them):

| Cue | When | Event | Base volume / pitch (+ random spread) | Position |
|---|---|---|---|---|
| travel start | X/Y travel begins; at most every 20 ticks | Create `AllSoundEvents.CONTRAPTION_ASSEMBLE` (wooden trapdoor + chest creak) | 0.6 · speed factor / 1.1 | carriage |
| travel stop | in the tick travel reaches its target (before the arm starts to extend) | Create `CONTRAPTION_DISASSEMBLE` (iron trapdoor, as Create's elevator pulley arriving) | 0.75 / 0.8 | carriage |
| rail clack | the base crosses a joint between two aisle blocks (pose x = n + 0.5); at most every 4 ticks | vanilla `METAL_STEP` | 0.2 · speed factor / 0.6 (+0.15) | base |
| lift | the carriage crosses every half level; at most every 4 ticks | vanilla `CHAIN_STEP` | 0.3 · speed factor / 0.8 (+0.2) | carriage |
| arm extend / retract | an extend or retract phase starts and the arm has to move (phase hook `onPhaseChanged`) | vanilla `PISTON_EXTEND` / `PISTON_CONTRACT` | 0.1 / 1.5 (+0.1) | carriage |
| pick | a real pick moved at least one item | vanilla `ITEM_PICKUP` (as Create's mechanical arm) | 0.15 / 0.5 (+0.25) | grabber |
| drop | a real drop delivered at least one item | Create `DEPOT_PLOP` | 1.0 (the entry scales to 0.25) / 1.0 | grabber |

* **Speed factor** (`CraneSoundCues.motionVolume`): 0.3 at standstill, rising linearly to 1.0 at 0.25 blocks per tick (about 96 RPM travel at the default factor) and above. The frequency of clacks and lift cues follows the speed by construction (one per joint or half level), capped by the rate limits, so fast cranes are not spammy.
* **Silence**: a paused tick (no rotation, overstressed, chunk not loaded) plays nothing and changes no cue state, so a crane that resumes mid-travel continues without a new start cue; an idle crane plays nothing. A zero pick or a drop that delivers nothing plays no transfer sound. The cue state is not saved: after loading, a travelling crane may play one start cue.
* **Hooks**: `CraneExecution#tick` passes the pose before and after the tick; `performPick` / `performDrop` pass the real amounts; the dock's `onPhaseChanged(from, to)` hook (the state is already the new one) plays the arm cues. Ponder and virtual block entities never run `CraneExecution`, so they stay silent.
* **Deviation: no client-side loop.** A looping moving sound would need a client `TickableSoundInstance` that follows the synced pose and stops on unload; short server cues cover start, motion, arm and transfers without client code, and the kinetic hum at the dock still comes from Create's `KineticBlockEntity#tickAudio`.
* **Verification**: `CraneSoundCuesTest` (10 tests: silence while idle and paused, start once and stop at the target, start rate limit, clacks at joints with rate limit, lift every half level both ways, volume by speed, arm cues only when the arm moves, transfers only for real amounts, validation). The `aisle` visual smoke test counts every sound the client starts to play (`PlaySoundEvent`, before the volume check, so the muted test client counts too) and fails unless all eight crane events were heard. **M4 review fix:** the server-side wiring is also covered by GameTests in `gametest.CraneSoundGameTests`, which record NeoForge's `PlayLevelSoundEvent.AtPosition` (fired by `ServerLevel#playSeededSound`) inside the test area. `cranesoundsfollowthejob` runs a store job paused by losing rotation mid-travel: exactly one pick sound at the input and one drop sound at the storage location, two arm extend and two retract sounds, one travel start and one stop (no new start after the pause), rail clacks, no lift sound on level 0, and not a single sound during the 40 paused ticks. `cranezeropickissilent` checks that the arm sounds play at an emptied source but the zero pick plays no pick or drop sound. Before, only the pure cue logic and the dev-only visual test covered sounds, so a broken hook (sounds played on the client, `onPhaseChanged` not calling them, a paused crane or a zero pick making noise) would have passed `build` and `runGameTestServer`.
