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
Classes: `content.crane.StackerCraneBlock`, `StackerCraneBlockEntity`, `RailNetworkScan` (`RailScan` until M21), `WarehouseRailBlock`; `content.controller.BranchLayout` and `WarehouseLayout`. Registered as `WareworksBlocks.STACKER_CRANE` / `WareworksBlockEntityTypes.STACKER_CRANE`.

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
* `content.controller.BranchLayout(dock, facing, geometry, Optional<letter>)` — called `AisleLayout` until M21 — is the world mapping of **one straight aisle**: `rackPos(x, y, side)` (`dock + f·x + side·1 + up·y`, no bounds check), `worldToLocal(pos)` → `Optional<RackPosition>` (empty for the aisle line itself, outside the length or height, or more than one block to the side), `aislePos(x)`, `sideDirection(side)` (LEFT = `facing.getCounterClockWise()`, RIGHT = `facing.getClockWise()`), `bounds()` (full-block AABB of all rack positions, `(L+1) × 3 × H`), `address(rack)` / `addressOf(pos)` once a letter is set.
* `core.address.RackPosition(branch, x, y, side)` is the aisle-local source of truth that `warehouse-system.md` §2 refers to; `branch` is 0 unless the warehouse bends.

**Rail counting (M2) and discovery (M21):**
* Until M21, `RailScan.scan(level, dock, facing, maxLength)` walked `dock + f·1, f·2, …` with at most `maxAisleLength` block-state reads and stopped at the first position that was not a `warehouse_rail` with `AXIS == f.getAxis()`. Since M21 that walk is `RailNetworkScan` (§3.2), the axis no longer matters, and a rail closed with a wrench is what stops a run.
* Either way the scan **stops before reading a position whose chunk is not loaded** (`Level#isLoaded`; a scan never loads chunks).
* `AisleGeometry.scannedLength(counted, incomplete, previous, max)`: a complete scan is authoritative; a scan that reached an unloaded chunk keeps the last known length if that is larger (capped at `max`). Unloading part of an aisle therefore never shrinks it. For this rule to survive restarts, the length is saved (`AisleLength`) as well as synced. Since M21 the same rule holds for the **whole warehouse** rather than per aisle, and the network is saved beside the length (`Network`); a partial network would renumber positions, and renumbering must never be caused by a chunk boundary. The one thing an incomplete scan may still shrink is the aisle **at the dock**, which is resolved with the narrow flag above: the kept network is cut back to that length, which renumbers nothing and drops the rest of the chain with the corner it hung on (`warehouse-system.md` §4, M21 review fix).

**Refresh cadence (server):** at most once per `geometryRefreshTicks` (a stored game time is compared each tick; nothing is scanned in between), immediately in `onLoad()` (which also fires in loaded but non-ticking chunks) and on a mast height change, and on the next tick after a facing change (`setBlockState` override) or `requestGeometryRefresh()`. On a change the dock calls `onGeometryChanged(previous, current)` and `notifyUpdate()` (sync and save). The hook asks the controller behind the dock to re-link, and the controller then marks its membership dirty. Clients invalidate the render bounding box (`layout().bounds()` inflated by 1) when the synced geometry changes.

**Deviation: no refresh on "neighbour/rail changes near the dock"** (M2, and it **still stands after M21**). Rails lie along the whole aisle, not near the dock, and letting every rail find its dock would be a world search. Placing or breaking a rail is therefore noticed by the **periodic** refresh only, which bounds the delay to `geometryRefreshTicks` (2 s by default); mast height and facing changes still apply at once. The M21 design study proposed a rail hint (`WarehouseRailBlock#onPlace`/`#onRemove` telling every controller whose network bounds contain the position to look again, rate-limited so a piston feeding rails cannot drive the scan) and it was **not built** — `WarehouseRegistry` has no `railChanged`, and `WarehouseRailBlock#onPlace` only writes its own connection flags. A bent warehouse makes the delay more visible than a straight one did, because a corner appears the moment two runs touch, so this is the first candidate if the two seconds ever read as a bug (ADR-033 deviations).

### 3.2 The warehouse is a network (M21, issue #1)

> The whole rule set, the states of the rail and what is refused: `warehouse-system.md` §1.1 and §4. Decision: ADR-033.

What the crane's side of it needs:
* `content.controller.WarehouseLayout(dock, dockFacing, network, branches)` is the world mapping of the **whole** warehouse: a `core.address.NetworkGeometry` plus one `BranchLayout` per aisle, the first of them at the dock. A warehouse of one aisle is a network of one branch, so it answers exactly what the single `BranchLayout` answered before.
* `content.crane.RailNetworkScan` replaces `RailScan` and is still owned by the dock: a breadth-first flood from the dock, neighbours in dock-relative order, another dock a wall, never loading a chunk, bounded by `aisle.maxNetworkRails`, `aisle.maxBranches`, `aisle.maxJunctions` (M22) and `aisle.maxAisleLength`. **The chain restriction is gone since M22** (issue #2, ADR-035): a block three or four rails meet at is taken like any other and a rail leading back onto a block already taken closes a ring.
* `core.crane.CraneNetwork` is what the crane itself knows: the shape, its `RouteTable` and the **resting yaw** at the dock (what an absent saved yaw means). The dock keeps the table rather than rebuilding it — the very table its own `WarehouseLayout` holds, so the machine and the controller that plans its jobs read one set of derived passes (M22 review fix) — so a moving crane does not derive the corners once per client tick — and since M22 the table carries **this** network's `crane.turnPenaltyBlocks`, so the way the machine drives is the way the controller costed (ADR-035).
* Geometry is synced as a packed `int[]`, so the update tag of a bent warehouse stays a handful of integers.
* `aisle.maxBranches = 1` makes discovery follow the dock's facing and read nothing beside it, which is the pre-M21 behaviour exactly.

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
* `RETURNING` was sketched as a phase for "travel back to position 0 when idle". **It was never built, and M21 settled that it never will be** (§4.7, ADR-034): returning home is the resting **target** of `IDLE`, not a phase of its own, which is what makes the trip interruptible in any tick and free of every other bookkeeping. `CranePhase` has no `RETURNING` value.
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
  5. Pause reason (`CranePauseReason`): no rotation, overstressed (`isOverStressed()`), or chunk not loaded (the aisle column under the crane, or the rack position of the current stop and — whenever the stop's items live one block **behind** the rack position: a storage location's inventory, and the machine a `COLLECT` job fetches out of — that block's position too; derived from the job rather than from the location kind, because a collect source is an `OUTPUT` that reaches through the port, M18 review). A change applies `Paused` / `Resumed`.
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
  * "Chunk not loaded" checks the aisle column under the crane and the current stop (with the block behind it wherever the items live there), not every block of the path: the crane touches the world only at its stops, and the dock chunk must tick anyway.
  * **M19 (issue #10, ADR-031) changed nothing here.** `CranePauseReason` has the same five values, `CranePauseDecision` the same priority, and `CHUNK_NOT_LOADED` is still what a crane reports whenever a chunk it needs is away. Optional chunk loading lives entirely in the **controller** (`warehouse-system.md` §11): the controller may hold the chunks of its own footprint while the aisle has work, so the pause simply stops arising for those chunks. It still fires, unchanged, in every other case: on a server with `chunkLoading` switched off (the default), for an aisle a cap refused or that gave up after `maxHoldTicks`, and for the ticks between a chunk going away and the hold being complete — the controller takes at most two chunks per tick, because forcing one loads it synchronously. Every position the crane's stop check can name is inside the footprint by construction (the aisle line, the rack positions, the inventories one block behind them and a collecting port's machine; `warehouse-system.md` §11.3), so a **complete** hold leaves the check nothing to fail on. The crane itself has no knowledge of tickets at all, which is why nothing in §4.1 or in `CraneConservationPropertyTest` had to change: a held chunk is simply a chunk that is loaded.
  * `HandlingHead#drop` takes the key and the amount (§6.1).
* **GameTests** (`gametest.CraneJobGameTests`, creative motor below the dock at 128 RPM, item conservation checked every tick): `cranestoreendtoend`, `craneretrieveendtoend`, `cranestoreconsolidates`, `cranespillreroutes`, `cranenorotationpauses`, `cranepersistencemidjob`, `cranebrokenmidjobdropshead`, `cranewaitsforfulloutput`, and from the M3 review `cranereloadduringdropcountsdelivery`, `craneheadsurvivesthrowinginventory`, `craneclearcontentreleasesjob`, `cranesourceremovedbeforepickaborts`, `cranezeropickaborts`, `cranerequestcancelledbeforeandafterpick`, and from the M5 release audit `cranecraftedheadisboundedandspilled` and `cranejobfollowsacraftedhead` (crafted `Head` data: bounded loading, stray items dropped at the dock instead of kept or deleted, and a job rewritten to follow the real head) (details in `warehouse-system.md` §7.5). Player-setup scenarios with a per-tick census of every item are in `gametest.CraneScenarioGameTests` (`warehouse-system.md` §7.6).

### 4.7 Returning home, and the warehouse home point (M21, issue #1)

> Decision and reasons: **ADR-034**. The block itself — placing it, its lamps, its goggles, its membership — is
> `warehouse-system.md` §3.7; the config key is §9 there.

**What a player sees.** A crane that has had nothing to do for `crane.returnHomeIdleTicks` (default 200 ticks = 10 s)
drives back to its **home**: the warehouse's home point if it has one, otherwise position 0 of the aisle at the dock,
which is where a crane has always started. It parks with the arm retracted, facing the way its aisle runs. Then it waits.

**Three rules, in this order** (`core.crane.HomeReturn`, pure, `HomeReturnTest`):

1. **A warehouse of one straight aisle never returns.** The machine stands where its last job left it, which is what
   every version up to 0.5.0 did and what a GameTest pins by comparing the pose against a recorded one on **every** tick
   of three idle delays. A home point on such a warehouse is reported as having no effect
   (`HomePointStatus.SINGLE_AISLE`) rather than obeyed.
2. **A delay of 0 switches returning home off** everywhere — the server owner's off switch, and the same behaviour as
   rule one on every warehouse (`SWITCHED_OFF`).
3. **Home is the home point, or the dock.** Breaking the home point therefore falls back to the dock with no further
   rule.

**A crane is "waiting" while it is `IDLE`, has no job and is not paused.** A paused crane is not waiting, it is stopped:
its idle counter neither runs nor resets, so it carries on where it left off once it has rotation again. The counter is
capped (`HomeReturn.MAX_IDLE_TICKS`), so a crane that stood still for a week counts no further and still returns.

**The return is the resting target of `IDLE`, and nothing else.** `CraneExecution#returnHomeIfIdle` runs before the
`Tick` event and writes `state.withTarget(home)`; `CraneMotion` then drives, lifts and **turns** towards it exactly as
it does for a job. There is no `RETURNING` phase, no event, no effect, no `TransportJob` and no timer in the state
machine. That is what buys the three properties the feature had to have:

| Requirement | How the design gives it |
|---|---|
| **It may never delay work** | a crane driving home is `IDLE`, so `canAcceptJob()` is true and it takes a job in the tick that job is planned |
| **Interruptible at any moment, mid-turn included** | entering `TRAVEL_TO_SOURCE` computes a new target from the pose the machine really has, wherever in a quarter turn that is (§5.3). A GameTest inserts items in a tick whose yaw is strictly between the two aisle headings and asserts the job is taken, the stack stored, and the home point never reached first |
| **It must hold no chunk** | a return is not a `TransportJob`, so `ChunkKeepDecision` still sees a warehouse with nothing to do (`warehouse-system.md` §11.2). A GameTest asserts 0 held chunks and `ChunkKeepReason.NONE` on every tick of a whole trip home, with the level cap open |

**Cost.** Two cheap questions first, on every tick of every idle dock: one config read and one counter. A warehouse of
one aisle — every world built before M21 — leaves after them, with nothing built and nothing allocated
(`StackerCraneBlockEntity#aisleCount()` answers 1 without allocating a geometry). Only a crane that really returns goes
on to build its `HomeReturn`, clamp the pose into the current geometry and — **only for a trip that crosses a corner** —
ask `RouteTable#canDrive`, because a machine already named on the aisle it has to reach always can.

**A home point the crane cannot drive to is reported, not obeyed.** The target goes back to where the machine stands, so
it waits there instead of pushing against rails that are not connected, and the block's own red lamp says why. The
crane asks `canDrive` **live**, every tick, before it sets a return target; the controller asks the same question on its
refresh cadence for the lamp, so a lamp may lag the rails by up to `geometryRefreshTicks` while nothing ever acts on the
stale answer.

**What the crane knows, and what it does not save.** `StackerCraneBlockEntity` holds the home point as a nullable
`RackPosition` handed to it by its controller (`setHomePoint`), plus `aisleCount()` and `homeReturn()`. It is **neither
saved nor synced**: it says nothing about where the machine *is*, only where it would go next, and the controller hands
it over again on its first re-link after every load — until then the machine simply waits where it stands, which is what
it did before this version. `unlinkController` clears it, because a crane with no warehouse knows neither whether that
block is still there nor whether it is still the first one. Crane persistence (§4.2) is therefore unchanged.

**GameTests** (`gametest.HomePointGameTests`, 7, each with an item census over the whole trip): `homepointservedonanl`,
`homepointbrokenfallsbacktothedock`, `homepointsecondisrefused`, `homepointonanunreachableaislereported`,
`homepointreturninterruptedbyarealjob`, `homepointreturnkeepsnochunksloaded`,
`homepointonasinglestraightaisleneverreturns`. **Visual scenario:** `./gradlew runVisualTest -Pwareworks.visualTest=home`
(the companion of `corner`) photographs a home point placed on an L, the machine setting off by itself after the idle
delay, the run-up to the bend, the parked pose, the goggle tooltip, a second home point refused, a trip interrupted by
real work and the fallback to the dock. It overrides `crane.returnHomeIdleTicks` and `crane.turnPenaltyBlocks` in memory
for its run so the trip is slow enough to photograph; there is deliberately **no** mid-turn shot of a trip home, because
no sampled poll ever landed inside the return's quarter turn and a frozen world has no rotation to step forward — the
`corner` scenario owns M21's mid-turn photography, and here the crossing is asserted in numbers on the server.

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
  * With the arm in, X and Y move simultaneously. **Since M21** one precedence rule sits between those two (§5.3): the machine turns towards the heading of the route leg it is on, with X frozen while it turns and Y still moving.
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
* **Goggles**: "Stacker Crane:", "Aisle: L long, mast H high", controller link, "Status: <phase>", "Paused: <reason>" (only if paused), "Storing / Retrieving / Handing over (M17) / Collecting (M18) <item> xN" with "From A-01-00R to A-01-05L", "Holding:" with the held items or "Grabber empty", then Create's kinetic stats. Addresses use the linked controller's letter, or `LL-PPS` without controller. **M21:** on a warehouse that really bends the size line becomes the network's ("Warehouse: 41 rails, 3 aisles, mast 6 high") and one further line says where the machine is standing — "On aisle B at position 7" — because a position alone no longer says that. It **adds to** the size line rather than replacing it, deliberately: a dock without a controller is the one a player looks at when nothing works yet, and it must not be left saying nothing about how big its warehouse is.

### 5.3 Turning at a corner (M21, issue #1)

> Decision: **ADR-033**. What a player builds and the rules of the network: `warehouse-system.md` §1.1.

**Nothing was added to the machinery.** There is no new `CranePhase`, `CraneEvent`, `CraneEffect`, `CraneInterruption` or `CranePauseReason`. The turn happens inside the existing `TRAVEL_*` phases:

* `CranePose` gains `branch` — which aisle the machine is **named** on — and `yaw`, the direction the whole machine faces, in quarter turns clockwise from north, normalised to `[0, 4)`. At rest the yaw equals the aisle's heading; in between it is a turn in progress. Branch 0 and north are the defaults everywhere, so every pose a straight warehouse ever had is described by the values it always was.
* **A route is a list of legs.** `core.warehouse.RouteModel` is the one-question front door; `RouteCosts` is the search behind it (M22, ADR-035). Since the rails may **split and close on themselves** there is in general more than one route, so the cheapest one is searched for over the **junction nodes** — one per `BranchLink` per branch of it, "standing on the shared block, named on this branch" — priced in blocks driven plus `crane.turnPenaltyBlocks` per quarter turn. Between two junction nodes blocks and turns are both integers, so a cost cell is one packed `int` and nothing is rounded; the only non-integer parts of a trip are its two end stretches. A cheapest route never drives one aisle twice, which bounds its turns and is why a `CraneRoute` can always be built from one. Ties break on fewer turns, then on the **pair of junctions the trip was priced between** (the lower position on the first aisle, then on the last, then the node indices); which hand-overs the route then really takes is a second decision, because a trip priced as leaving its first aisle at one junction may be driven straight past it and hand over later at no extra turn, so the key is never phrased as "the lower aisle handed over onto first" (M22 review fix). `costBlocks` and `route` go through the **same** pick, so a controller and a crane can never mean different things by "the way there". Two points of **one** aisle cost `|Δx|` without the graph being touched at all, which is the whole of a warehouse that does not bend; the passes are derived on demand and kept, and **a table has an owner** — `WarehouseLayout` holds the table of the network it maps and the dock builds its `CraneNetwork` on that very table — so a warehouse is costed when its **rails change** and at no other time, and the controller and its crane read the same derived rows. `CraneRoute` carries the chosen route with its cost.
* **A hand-over at a corner is a rename**, not a jump: the same world block under the next aisle's name, followed by the quarter turn. Every turn is exactly ±1 quarter, because perpendicular aisles are the only ones that meet — which is why `Side.LEFT`/`RIGHT` keep their meaning everywhere.
* **One tick is one budget of `vx` blocks**, and a quarter turn spends `crane.turnPenaltyBlocks` of it (default 1.0, range 0–16; 0 turns in a single tick). So the whole route cost stays **one scalar in blocks**, `travelTicks` stays one formula, and it stays tick-exact against `CraneMotion`. At the default travel speed a turn takes 3 ticks at 128 RPM and 12 at 32 RPM: the turn runs off the same shaft as driving.
* **The planner counts turns with the same number.** `PlannerInput` gains a `TravelCost` whose builder default *is* the pre-M21 formula, so "one aisle plans exactly as it did" is a type-level property rather than a claim. The visible consequence is honest and intended: a rack round a corner ranks behind an equally distant one on the aisle the crane is already on.
* **There is exactly one definition of "it can get there", and it is asked from the machine's own point.** `RouteTable#canDrive`: already on the target's aisle always can (it drives straight along the line it stands on, which is also how it returns onto rails that became shorter under it); anything else needs a route from its own position. A branch-index question is not a substitute — a rail taken out of the middle of the aisle the crane is on leaves the aisle shorter than the crane's position while the aisle still exists and still meets the corner, and the crane would then stand still for ever with a job it never gives up. `CraneExecution` and `CraneDispatch` both ask `canDrive`; `NoJobReason.UNREACHABLE` is its refusal.
* **A crane whose aisle left the warehouse is recovered**, not fed jobs it will abort: `CraneExecution#recoverLostAisle` puts a resting machine back onto the aisle at the dock (`WarehouseLayout#parkedAtDock`) and logs it once. It is not a jump — a label naming an aisle that is gone names no line of blocks, so the renderer has been drawing it at the dock since the moment its aisle left. A crane **holding items** is recovered too, on purpose: it then plans a reroute it can really drive and puts the items away.
* **What a save holds mid-turn.** `Branch` is written only when it is not 0 and `Yaw` only when it differs from the resting yaw at the dock, so a straight warehouse saves the bytes it always did. An absent `Branch` reads as 0, an absent or unreadable `Yaw` as the yaw at the dock, and a branch outside the address format is clamped into it. A quit in the middle of a quarter turn therefore restores **exactly** the part-turned machine — and because the pose, the motion target and the job's two locations are all remapped together when the decomposition changes (`WarehouseLayout#renamedFrom`, `StackerCraneBlockEntity#remapOnto`, `TransportJob#relabelled`), a warehouse rebuilt around a parked crane never hands it a label that now names a different block.
* JUnit `CraneTurnTest`, `CraneTurnKineticsTest`, `RouteModelTest`, `RouteTableTest`, `RouteCostsTest` (a tee, a cross, both ways round a ring at four turn prices, a comb, an aisle the rails no longer reach, two identical builds answering identically, and M21's own chain walk reimplemented as an oracle over every pair of points of every chain shape), `TravelCostTest`; GameTests `CraneCornerGameTests` (a real job carried round a corner, a rail broken behind the machine, the corner breaking under a parked machine, a save in mid-turn) and `WarehouseCombGameTests` (a comb stored down and emptied out of every aisle into one block, a rack beside a tee joining the aisle it faces, a ring where the crane drives the way the planner costed, an aisle a cap cut loose, an aisle appearing and disappearing under a running job).

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
Since M30 the head has a **third** transfer, `exchange`, which is how a fluid reaches a warehouse at all (§6.2).

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
  * Output station: `WarehouseOutputBlockEntity#insert` only — **except a collecting warehouse port** (M18, issue #13, `warehouse-system.md` §3.2.4), which is the one output station the crane takes items *out of*: it resolves to the item handler of the inventory **behind** the port instead of the port's own buffer, so the arm reaches the rack position and transfers one block further exactly as it does for a storage location. `CollectContext` is the **extract half** of the storage context and nothing else: `insert` returns the stack unchanged and `simulateInsert` answers 0, so nothing the warehouse carries can ever be pushed into a player's machine, and a machine that inserts into the same inventory is never fought over. Anything that has to be spilled goes to the **port's** position. `UNLOADED` while the attached position is not loaded, `MISSING` when nothing there offers an item handler — the same two answers a storage location whose chest a player broke gives.
  * The same contexts give the controller's live simulations: `simulateExtract` sums simulated extractions per slot (a drawer-like slot counts one stack, so it never promises more than a real pick), `simulateInsert` simulates `insertItemStacked`.

### 6.2 The container exchange (M30, [issue #21](https://github.com/Richie1710/create-wareworks/issues/21))

A **third** transfer beside `pick` and `drop`, and the only one that changes *what* the head holds: at a fluid bay the
head gives up a filled container and receives the empty one back at the same stop. `warehouse-system.md` §3.9.2 carries
the whole shape and ADR-055 the decision; what belongs here is what it means for the head.

```java
int exchange(TransferContext target, ItemKey from, ItemKey to, int amount);   // containers really swapped, or 0
```

* **It cannot be an `insert`.** `InventoryGrabber.drop` computes `accepted = chunk - remainder.getCount()`, which is 0
  when a location hands one empty bucket back for one filled one, and then breaks **without putting the remainder
  anywhere**: the filled bucket stays counted as held and the empty bucket never existed in any inventory. A location
  that wants to hand back a *different* item therefore needs its own call, never the remainder.
* **The real call is the authority**, as it is for `pick` and `drop`: the head is changed only after the location has
  really taken the containers, in the same synchronous chain, so there is no tick boundary, chunk check or save point
  between the fluid moving and the head changing.
* **All or nothing**, because `TransportJob` is monotone in one key and `CraneExecution.reconcileHeadWithJob` spills
  every held key that is not the job's key at the dock. A head holding two keys throws one of them on the ground at the
  next save, chunk unload or broken block, so a partial exchange is not a smaller success but a defect.
* **A third outcome, `SALVAGE`**, for a location that contradicts the plan it has just given: the fluid is already in
  the tank, so leaving the containers on the head would create fluid from nothing. The containers the location says it
  took leave the head, what it handed back is spilled **at the location**, and the caller is told 0 — so the job
  follows the head, which is `drop`'s own discipline for a target that refuses. "Told 0" is the whole reason
  `CraneExecution.performExchange` re-reads the head on a refusal (M30 review fix): a salvage really did take the
  containers, and a job that went on claiming them would spend a reroute and a capacity reservation on items that no
  longer exist and discover it only at the next drop. It is a defensive path — this mod's own bay cannot reach it,
  because its simulated and real calls are the same arithmetic — but the answer is the one every other broken contract
  gets here.
* **A location may answer `exchangesOnly()`**, and a fluid bay is the only one that does: at such a location a drop is
  an exchange or a refusal and never an insert, so the crane never reaches an item path that by design has no caller.

In the job it is `CraneEvent.Exchanged`, the fourth legal answer to `PerformDrop` and deliberately not a `DropResult`
(§4 asserts `delivered + leftover == heldAmount()` for those, and an exchange delivers nothing of the job's key). No
`CranePhase` is added; the stop ends in `RETRACT_TARGET` and the ordinary `REROUTE` ladder shelves the empty container,
with the bay recorded as the failed target so the planner does not offer it the thing it just produced.

**All or nothing reaches into the reroute ladder, and that is the one place it is not local** (M30 review fix). A drop
always offers the *whole* carry — `CraneStateMachine.transferTick` emits `PerformDrop(job, target, heldAmount())` and
`CraneEvent.RerouteResult` carries no amount — so a reroute planner that answered "this location takes 1 of your 2"
sent the crane to a bay that then took **nothing**. With two part-full bays of one fluid, which outrank every shelf
because a container of their fluid is a dedication, and a reroute that excludes only the target that just failed, the
crane shuttled between them for ever while room stood free on a shelf. The planner therefore knows which locations
take a carry whole or not at all (`PlannerInput.allOrNothing`, wired to the controller's `takesFluidContainers`) and
skips such a location on a reroute unless it takes **all** of the carry; the store plan is untouched, because it sizes
a job by what the bay answers. It is only reachable with a carry of two or more containers, i.e. at
`crane.grabberStacks` above 1 or with a modded container that stacks — a filled vanilla bucket stacks to one.

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

> The **rack bay** draws the item it stores with the same `ItemRenderer`, the same `FIXED` context and the same 0.34 scale, so an item does not change size when the arm sets it down (M29 step 13, ADR-049, `warehouse-system.md` §3.8). It differs in two ways on purpose: a **flat item stands up facing the aisle** rather than lying down, because a bay is read face-on from the aisle while the arm's stage is a shelf seen from above, and there is only one copy however much is stored, because the bay's `FILL` silhouette already says how much. Its own geometry is pinned against this one by `CraneModelLayoutTest#theItemDrawnInARackBayStandsClearOfTheArmAndTheLoad`, which holds the drawn item under the arm floor read off `CraneModelLayout`.

* **Frame**: the pose stack is moved to the machine's own aisle block and turned once about the block centre. Until M21 that was Flywheel's `rotateToFace(facing)` with the dock's facing; **since M21** the offset comes from `WarehouseLayout#railOffset` (the block the pose names, on whichever aisle it is) and the rotation from `CranePose#yaw()` — in quarter turns clockwise from north, so a yaw between two quarters *is* a turn in progress and every part below it turns with the machine. North is still the identity, and on a warehouse of one aisle the two are the same transform. The arm assembly (stages, grabber, items) is turned 180° about the block centre for `Side.LEFT`, so its +X extension points to `facing.getCounterClockWise()`; `Side.RIGHT` points to `facing.getClockWise()` (as `BranchLayout#sideDirection`).
* **Wheels** are turned by an **odometer** — the signed blocks the machine has really driven (`CraneMotion#blocksDriven`, summed leg by leg when a tick handed over at a corner) — and not by `pose.x()`. A hand-over at a corner renames the position without moving the machine, so wheels driven by X alone spin backwards at every corner; and a returning crane's wheels spun forwards while the odometer accumulated an unsigned distance (both found by the M21 review, the second one a visible regression on every return trip).
* **Interpolation**: `StackerCraneBlockEntity#renderPose(partialTicks)` (previous and current client tick of the shared `CraneMotion` simulation, §5.2).
* **Light**: `LevelRenderer.getLightColor` at the block each part is in: the aisle block for base, wheels and cog, the crane column block for each mast segment, the cap and each belt piece, the carriage's block for the carriage. Arm stages, grabber and items take the brighter of the carriage block and the block the moved part is in (`SuperByteBuffer.maxLight`), so parts inside a solid rack block (dark inside) keep the aisle's light.
* **Buffers**: all block geometry goes into one `RenderType.cutoutMipped()` consumer; held items are rendered last (rendering an item requests other render types, which ends the shared batch of a kept `VertexConsumer`). The dock's own block model is a normal chunk model.
* **Culling**: `shouldRenderOffScreen` is true. `getViewDistance()` is `64 + maxAisleLength + maxMastHeight` from the safe config getters, which on a bent warehouse is a very loose bound — 336 blocks on the defaults, so the distance gate was effectively inert and a small L re-transformed its whole machine every frame out to twenty-one chunks (M21 review). The renderer therefore overrides `shouldRender(crane, cameraPos)` and measures against the dock's **own** network bounds, which are already cached and invalidated when the rails change; `getViewDistance()` stays as the conservative fallback for the callers that do not go through it, because a loose bound may never cull a machine that is really in view. The render bounding box is the dock's `createRenderBoundingBox()` (every rack position, inflated by one block, so up to `H + 1` above the dock), which `SafeBlockEntityRenderer#getRenderBoundingBox` forwards; the client invalidates it when a synced geometry or facing changes.
  * **Limit (M4 review):** these settings only stop frustum and distance culling. Vanilla collects off-screen block entities while it compiles their chunk section (`SectionCompiler`: `shouldRenderOffScreen` → `globalBlockEntities`), and sections are compiled only within the client's render distance, and only for chunks the server sends within its view distance. So the crane is drawn only while the **dock's** chunk section is within the client's effective render distance, even when the crane itself is close to the player: at render distance 6 (96 blocks) a player at the far end of a 128-rail aisle sees no crane. The default aisle (`maxAisleLength` 32) is far inside every usual render distance; the config comment of `maxAisleLength` names the limit. Create's pulleys and chain conveyors have the same limit. If it matters, a later milestone can add a per-chunk proxy renderer along the aisle.
* **Flywheel and Ponder**: no visualizer is registered, so the same renderer draws with a Flywheel backend and with `/flywheel backend off` (both passes of both visual scenarios render identically). Nothing checks `VisualizationManager`; light goes through `BlockAndTintGetter` and the kinetic angle through Ponder-aware `AnimationTickHolder`, so it works in Ponder levels by construction. **Confirmed in M5:** the Ponder scenes (`architecture.md` ADR-016) render the crane through this renderer, with Flywheel on and off.
* **Client poses (the Ponder pose API of ADR-013)**: `StackerCraneBlockEntity#showClientPose(pose, target, phase, held)` shows the crane at `pose` in `phase` holding `held` on a client or Ponder level (`CraneState.displayed`: no job, no timers), and the client tick moves it towards `target` with the shared `CraneMotion` at the speeds of the dock's kinetic speed (without rotation it stays put); a scene animates the crane by setting the next target. `showClientPose(pose, phase, held)` is the same with the pose as its own target, a fixed pose (the `poses` visual test). Nothing changes phase and no item moves; the next server packet takes over as usual, and on a server level it does nothing. JUnit `CraneMotionTest#displayedStateMovesTowardsItsTarget`. *M4 review fix:* the first version only had the fixed pose, so a Ponder scene could not have animated the crane. **M5:** the scenes use exactly this API through `client.ponder.scenes.CraneScript`, which sets the next target and idles for as many ticks as the same pure `CraneMotion` needs to reach it (ADR-016).

**Deviations:**
* **Dock model.** The M2 dock was a full block (steel body between andesite plates). The crane stands in the dock column at position 0, where rack positions exist beside the dock, so a full block hid the parked crane's base and carriage and the arm would have come out of its sides. The dock model is now a low rail bed that the parked base stands on. Its outline and collision are the bed as well, and the "Mast Height" value box sits on the bed top (§2.1, M4 review fix: the first version kept the full cube, which left an invisible block, a full outline and floating value boxes at the dock while the crane was away). The item gets its own `item.json`, because the bed alone would be a flat icon.
* **Mast placement.** The mast stands on the chassis (0.5 blocks above the floor) at the back of the crane block, towards the dock end, so the carriage deck and the arm are in front of it. The cap therefore sits at `H + 0.5`, inside the render bounds of `H + 1`.
* **Additional parts.** Wheels are a separate partial (turned by the travelled distance) and the hoist belt was added as visible lift mechanics; the design table names neither. Part files are named after `WareworksPartialModels` (`mast_segment`, `wheel`, `hoist_belt`).
* **Arm reach and ports.** The inner stage moves exactly one block at full extension, as designed. The retracted grabber's front face is at 15.5 px, so at full reach it stops 0.5 px before the inventory beyond the rack position. A crane only ever transfers at a member block in the rack position (interface or station), so the arm always goes into that block. **M4 review fix:** it enters through a port on the member's aisle side instead of through a solid face. Stations already had a 10 x 10 px opening recessed 3 px (y 3..13). The interface's flush andesite plate got an 8 x 4 px dark slot recessed 3 px (x 4..12, y 9..13, `warehouse-system.md` §3.1.1). The grabber was lowered to 12.5 px (plate, fingers and top jaw), so stages, grabber and flat items pass both ports without touching the block; beyond the 3 px recess they are hidden behind its dark back wall, and after a pick the items come out on the retracting arm. `CraneModelLayoutTest#armPassesThroughTheMemberPorts` sweeps every arm part and flat item through the port depth of the interface, input and output models. **M10 (ADR-022):** the warehouse terminal's port moved out of its `block.json` into `shell_intake.json`, the shell its multipart blockstate puts on the aisle side, because any of its four faces can be that side; the opening itself is unchanged (the interface's 8 x 4 px slot at x 4..12, y 9..13 over the full 3 px depth), and the same sweep runs against that shell. The M10 **look pass** (ADR-023) then touched only the other three shells — the screen, its tray and the machine panel are recesses on faces the arm never reaches — so this geometry, and the sweep that pins it, are unchanged by it. **M28 (ADR-047), reworked in M29 (ADR-050):** the **rack bay** joins the same sweep as every other member, with `aisleSouth = true`, and it is the one member with **no port recess at all**. Its aisle face is open across the whole block above the load beam, between the two uprights, so the arm's window at x 4..12 / y 9..13 is part of an opening that spans the bay rather than a slot cut into a wall — and all three tiers pass the sweep for the simplest reason there is: nothing of a bay stands in it. A bay differs from every other member in one way that needed its own test: the arm reaching a full block deep is **visible** rather than hidden behind a 3 px recess, and the load has to stay out of its way over the **whole** block rather than only inside a port — `CraneModelLayoutTest#aRackBaysLoadStaysUnderTheArmAndInsideItsWindow` reads the arm's lowest point off `CraneModelLayout` and holds every fill step under it, and `CraneModelLayoutTest#aRackBayIsAShellBetweenTwoUprights` pins that nothing but the back skin ever stands in the arm's path, over the whole depth it travels. That skin is 2 px of `create:block/andesite_casing` in every tier — a depth with something behind the goods rather than a hole in a wall — and it is in the arm's path for the last 1.5 px of its reach, which is the interface's own accepted case. **M30 (ADR-051, ADR-053):** the **fluid bay** joins the same sweep in both tiers, and it is the one member whose contents are drawn by a renderer into the arm's own block. Its aisle face is the rack bay's open front, so nothing of the frame stands in the sweep either; what had to be cut to the arm is the **tank**. The vessel's rim is the port's sill at 9 px, half a pixel under the arm's lowest point at 9.5 px, and the fluid stops with it — a tank reaching into that window would be a grabber visibly travelling through lava on its way to a container. `CraneModelLayoutTest#aFluidBaysVesselStaysUnderTheArmAndInsideItsWindow` holds the model **and** `client.render.FluidBayLayout`'s own box corners to it, which is the one piece of this geometry no model file can express. **Accepted:** block items stand 2.7 px tall on the stage (up to 14.2 px, a second copy higher) and pass the 3 px port lintel while the arm enters or leaves; shrinking them enough to fit would make them specks. The empty rack positions of the `poses` scenario show the whole reach.
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
* **Chassis chamfer (M21).** The chassis really did clip the corner. Rotating about the block centre put the front buffers 10.00 px and the chassis corners 9.60 px from the pivot, while half a block is 8 px — so a turning machine swept up to 2 px into the neighbouring blocks, which at a corner are rack positions holding chests. The chassis corners and buffers are chamfered, which is also a visual-weight win, and `CraneModelLayoutTest#chassisSweepStaysInsideItsBlock` pins it to the pixel alongside the wheel-through-chassis and arm-through-port clearances this repo already pins. `WarehouseRailModelTest` (with the shared `ModelJson` reader) does the same for the five rail models.
* **Render distance limit made worse in principle (M21).** The M4 limit above is unchanged in mechanism, but a bent warehouse can put the crane much further from its **dock** than a straight aisle could, and the dock's chunk section is what decides whether the machine is drawn at all. The per-chunk proxy renderer named above is the real fix and is not part of M21; the `maxNetworkRails` config comment says so.

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
| turn (M21) | the tick a quarter turn at a corner starts; at most every `TURN_MIN_INTERVAL_TICKS` (6) ticks | vanilla `IRON_TRAPDOOR_OPEN` | 0.4 / 0.55 (+0.05) | base |
| turn settle (M21) | the tick that same turn squares up with the aisle it turned onto | vanilla `METAL_STEP` | 0.25 / 0.5 | base |
| lift | the carriage crosses every half level; at most every 4 ticks | vanilla `CHAIN_STEP` | 0.3 · speed factor / 0.8 (+0.2) | carriage |
| arm extend / retract | an extend or retract phase starts and the arm has to move (phase hook `onPhaseChanged`) | vanilla `PISTON_EXTEND` / `PISTON_CONTRACT` | 0.1 / 1.5 (+0.1) | carriage |
| pick | a real pick moved at least one item | vanilla `ITEM_PICKUP` (as Create's mechanical arm) | 0.15 / 0.5 (+0.25) | grabber |
| drop | a real drop delivered at least one item | Create `DEPOT_PLOP` | 1.0 (the entry scales to 0.25) / 1.0 | grabber |

* **Speed factor** (`CraneSoundCues.motionVolume`): 0.3 at standstill, rising linearly to 1.0 at 0.25 blocks per tick (about 96 RPM travel at the default factor) and above. The frequency of clacks and lift cues follows the speed by construction (one per joint or half level), capped by the rate limits, so fast cranes are not spammy.
* **Silence**: a paused tick (no rotation, overstressed, chunk not loaded) plays nothing and changes no cue state, so a crane that resumes mid-travel continues without a new start cue; an idle crane plays nothing. A zero pick or a drop that delivers nothing plays no transfer sound. The cue state is not saved: after loading, a travelling crane may play one start cue. **While the machine turns, the rail clack is suppressed** (M21): it is standing still along the rails, so the whole swing reads as one movement instead of a noise per tick, and a turn whose start was rate-limited away settles silently, so the pair is never split.
* **Why the turn is not Create's `COGS` sample** (M21 review): that is the exact sample Create drones continuously at volume 1.5 for every cogwheel, large cogwheel and gearbox within 16 blocks, with the exact same subtitle. In a warehouse driven by a cogwheel the crane was masked by its own drivetrain — and the `corner` visual scenario never noticed, because it was powered by creative motors alone. The swing is now a deep iron trapdoor, the sibling of the travel-stop cue (an iron trapdoor closing) a quarter turn lower, so swing and stop belong to one machine without sounding the same; the mod still registers no sound events of its own. `CornerVisualScenario` now spins two cogwheels and a gearbox in its power train, so the run's sound census counts the cue **over a real drivetrain**.
* **Hooks**: `CraneExecution#tick` passes the pose before and after the tick; `performPick` / `performDrop` pass the real amounts; the dock's `onPhaseChanged(from, to)` hook (the state is already the new one) plays the arm cues. Ponder and virtual block entities never run `CraneExecution`, so they stay silent.
* **Deviation: no client-side loop.** A looping moving sound would need a client `TickableSoundInstance` that follows the synced pose and stops on unload; short server cues cover start, motion, arm and transfers without client code, and the kinetic hum at the dock still comes from Create's `KineticBlockEntity#tickAudio`.
* **Verification**: `CraneSoundCuesTest` (10 tests: silence while idle and paused, start once and stop at the target, start rate limit, clacks at joints with rate limit, lift every half level both ways, volume by speed, arm cues only when the arm moves, transfers only for real amounts, validation). The `aisle` visual smoke test counts every sound the client starts to play (`PlaySoundEvent`, before the volume check, so the muted test client counts too) and fails unless all eight crane events were heard. **M4 review fix:** the server-side wiring is also covered by GameTests in `gametest.CraneSoundGameTests`, which record NeoForge's `PlayLevelSoundEvent.AtPosition` (fired by `ServerLevel#playSeededSound`) inside the test area. `cranesoundsfollowthejob` runs a store job paused by losing rotation mid-travel: exactly one pick sound at the input and one drop sound at the storage location, two arm extend and two retract sounds, one travel start and one stop (no new start after the pause), rail clacks, no lift sound on level 0, and not a single sound during the 40 paused ticks. `cranezeropickissilent` checks that the arm sounds play at an emptied source but the zero pick plays no pick or drop sound. Before, only the pure cue logic and the dev-only visual test covered sounds, so a broken hook (sounds played on the client, `onPhaseChanged` not calling them, a paused crane or a zero pick making noise) would have passed `build` and `runGameTestServer`.

## 9. What the machine got done (M25, issue #16)

A crane that looks busy and a crane that *is* busy are hard to tell apart by watching it, so the dock measures itself: a **rolling minute** of what the machine was doing, shown on its own goggles. Design in **ADR-039**.

### 9.1 Six buckets, and why each is drawn where it is
`core.crane.CraneActivity` classifies **every** server tick into exactly one of six mutually exclusive buckets, in a fixed precedence. The six therefore always sum to the ticks that were observed, which is the property every share on the tooltip rests on.

| bucket | test | why this line and not another |
|---|---|---|
| `PAUSED` | `state.paused()` | the world stopped the machine. The *reason* lives in `content.crane.CranePauseReason` and has its own goggle line, so only the boolean crosses into `core.*` |
| `IDLE` | no job | **the drive home included**: `HomeReturn.isWaiting` already counts a returning crane as waiting and the `Status:` line already reads *Idle*, so the two surfaces cannot disagree. Counting the return as travel would make a warehouse that parks its crane look busier than one that does not |
| `TURNING` | the tick swung (the swing measured along the route, §9.2) | a tick that both turned and drove counts **wholly** as turning, the same approximation `CraneSoundCues` already makes when it suppresses the rail clack during a swing (§8). The turning share is therefore an upper bound, at most ~3 ticks per corner at the default speed and penalty (0.25 % of a minute), and it errs on the side that makes corners look expensive |
| `TRAVELLING` | the tick moved along the rails or lifted | the arm is **not** motion: extending and retracting happen while the machine stands at a rack |
| `AT_A_STOP` | phase in `EXTEND_SOURCE, PICK, RETRACT_SOURCE, EXTEND_TARGET, DROP, RETRACT_TARGET, COMPLETE` | deliberately the **planner's own** definition of a stop (`core.job.TravelTimeModel#stopTicks`: extend, transfer, retract), so the measured share is comparable with what the planner predicted instead of being a second, private definition |
| `BLOCKED` | a job, but nothing moved and no stop: `WAITING_FOR_TARGET`, `HOLDING`, `REROUTE`, **and a `TRAVEL_*` tick that covered no ground** | the stranded case. A crane standing still in `TRAVEL_TO_TARGET` because a rail was broken is *not* paused, so without this bucket the machine would read "travelling 100 %" while doing nothing |

The last step is an exhaustive `switch` over `CranePhase` with **no `default`**, so a phase added later is a compile error instead of a tick that quietly counts as blocked.

### 9.2 The window
`core.crane.ThroughputWindow`: 60 buckets of 20 ticks, one per second, with running sums so `snapshot()` is O(1) however often a watching player asks. `core.crane.CraneThroughput` is that snapshot — ten raw numbers (the six bucket counts, the observed ticks, trips, items, corners), never percentages, so there is one source of truth and every assertion is exact.

* **Never saved.** Game time is contiguous across a restart, so saved buckets would be arithmetically defensible and would still claim a minute of work for a machine that stood still. `CranePersistence` is untouched, so there is no migration and no new byte in any crane save.
* **A gap contributes nothing, neither idle nor working.** `record` first rolls the ring over every whole second that has elapsed, zeroing each bucket it moves onto and subtracting it from the sums — so a chunk unload shrinks the observed ticks by exactly the ticks that were in the seconds it skipped. Bounded at 60 steps; a gap of a minute or more, and a game time that moves backwards (a `/time set`), clear the ring instead.
* **The corner count is measured over exactly the ticks the turning share is.** The two are printed as one phrase —
  "turning 6 % (9 corners)" — so a count taken over a wider set of ticks than the share beside it is a contradiction
  inside one line. With the precedence above that leaves out one real case: the **drive home** has no job, so it is
  `IDLE` even while it crosses a corner, and on a bending warehouse — the only kind that prints a turning term at all
  — it crosses them regularly. "turning 0 % (11 corners)" was the shape of the reading that counted them (M25 review
  fix).
* **The swing of a tick is measured leg by leg along its route**, not as the shortest arc between the two end poses.
  One tick is one budget of `vx` blocks spent along the whole route (§5.2), so with `crane.turnPenaltyBlocks` at or
  near 0 — a documented instant turn — a tick can finish several legs, and every hand-over is a quarter turn. The arc
  answers within ±2 quarters, so two corners in opposite directions net **zero** (the tick would not even count as a
  turn), three in one direction read as one and four read as zero. `CraneMotion#quarterTurnsSwung` walks the legs
  instead, exactly as `blocksDriven` already does for the wheels and for the same reason, and the state machine
  reports it from the route it already asked for — never a second route query on the crane's tick path. At the
  default penalty the two readings are identical, because one quarter turn there already spends a whole tick's budget.
* **`observedTicks` is the denominator, never a fixed 1200.** A window that has run for ten seconds holds ten seconds of ticks; dividing those by a minute would make every crane read low for the first minute after every chunk load and teach the player the opposite of the truth. The label carries the real length instead — "of the last 23 s" — and **nothing is ever extrapolated**: nine trips in 23 s are never scaled up to a minute.
* Each of the three working shares is **computed from its own ticks** and none is derived as the remainder of the others, so nothing claims a total of exactly 100 — and the **busy headline is their sum**, so the breakdown really does add up to the number above it. Flooring the whole and each part independently is arithmetically defensible and reads as a contradiction: `floor((a+b+c)/n)` can exceed `floor(a/n) + floor(b/n) + floor(c/n)` by up to two, which is how "Busy: 85 %" came to stand over "Travel 48 % · turning 7 % · at the rack 29 %" in the shipped screenshots. Summing can only ever understate the busy share, never overstate it (M25 review fix). **One state is still short of it, knowingly:** the turning term is printed only on a warehouse that bends *right now*, while the window remembers a minute — so for up to 60 s after a player breaks the last junction of a bending warehouse the headline still contains the turning share while the line under it names travel and the rack only. It resolves itself as the ring rolls; the alternative would show a straight warehouse a term its own shape makes impossible (`CraneThroughput#busyShare`).
* Memory: 60 × 9 ints, about 2 kB per loaded dock, one dock per warehouse. Quarter turns are accumulated as integer thousandths of a turn, so the running sum stays exact under eviction and a finished corner counts exactly once.

### 9.3 The cost per tick
One call at the end of `CraneExecution#tick`, where the pose before and the state after are already in scope beside `sounds.afterTick`:

```java
CraneTickMotion motion = CraneTickMotion.of(before.pose(), after.pose());
window.record(now, CraneActivity.of(after, motion), motion.quarterTurns());
```

That is one four-field record, an exhaustive `switch`, two array writes and four adds. No collection, no map lookup, no world access, no block entity lookup, no scan, and nothing published. `CraneTickMotion.of` does the same arithmetic `CraneSoundCues#motion` does on its four method-locals, with the same `EPSILON = 1e-9`; the sound cues are **not** refactored (they are reached through a stateful class on the crane's hot path), and `CraneTickMotionTest` pins a table of pose pairs on which the two readings must agree, so the duplication is held in place by a test instead of by a signature change.

Trips and items are credited where they happen: `window.addTrip()` in the `ReportComplete` arm and `window.addItems(delivered)` in the `ReportDelivered` arm of `CraneExecution#execute`. A tick's effects run before its own classification, so a credit in the first tick of a new second lands on the second before it and is forgotten a second early — under one second of attribution, and not worth per-tick state to remove. **A resume credits nothing**: `CraneStateMachine#resume`'s sanitising can complete a job that was already delivered before the save, and a `resuming` flag around `CraneExecution#resume` keeps that out of the window, so a freshly loaded dock never opens with a trip it did not make.

### 9.4 Who sees it, and when anything goes on the wire
The numbers ride the dock's existing goggle tag (`CraneGoggleInfo`, one `int[10]`, about 44 accounting bytes) and are **omitted entirely** while the window holds nothing worth showing — which is every parked crane and every dock nobody has looked at. No `WareworksNetwork.VERSION` bump: the version gate guards custom payloads, and a missing update-tag key reads as absent.

The dock is a `GoggleObservers.Observable` with its own `SyncThrottle(20)`. This is the one thing the existing publish path cannot do: a working crane publishes several times a second anyway, but a **parked** crane publishes nothing at all, so without the observer a player would read "Busy: 85 %" off a machine that has stood still for five minutes. `onGoggleObserved()` takes a fresh snapshot, marks a sync pending only if it differs, and sends at most one packet a second. The server's cached `goggleInfo` deliberately does **not** carry the measurement — it is rebuilt several times a second for a working crane, so a measurement rebuilt with it would flicker in and out of the client's copy; it is merged in at write time instead, from one nullable field the observer owns.

The one genuinely new traffic case is a **just-parked, watched** dock: about one packet a second for the minute its window takes to decay to nothing, and then quiet of its own accord.

### 9.5 The lines on the dock
Four, on the dock, below the held-items block, and hidden entirely while the window is empty — so a parked crane's tooltip is what it was before this feature existed.

```
  Busy: 78% of the last minute                             Auslastung: 78 % der letzten Minute
  Blocked: 4%                                              Blockiert: 4 %
  Travel 54% · turning 6% (9 corners) · at the rack 18%    Fahren 54 % · Drehen 6 % (9 Ecken) · Am Regal 18 %
  Trips: 9 · items: 412                                    Fahrten: 9 · Gegenstände: 412
```

(the separator is the middle dot the display sources already use)

* **"Busy"**, not "Working" or "Utilisation": waiting for a target is working on a job and is not busy, and *busy* is the natural complement of the *Idle* the status line already prints.
* **"Blocked"**, not "Waiting", forced by the lang file rather than by taste: `display_source.crane.idle` has already spent German's *Wartet* on *Idle*, so English follows German and the pair stays 1:1. The line is gold (it is the one share a player is meant to act on) and omitted at zero.
* The **turning term exists only on a warehouse that bends** (`networkGeometry().branchCount() > 1`, the gate the size line already uses): a straight aisle structurally never yaws, so the term would be a permanent `0 %`. Two lang keys, one with it and one without.
* The breakdown and the trip counts are `detailed` lines. `CraneGoggleInfo#addGoggleLines` takes that flag, so dropping the dock to two lines later is one argument and no record, packet, lang or test change.
* Exactly **one** value in the whole mod carries a percent sign: `gui.goggles.percent` = `%1$s%%` (German `%1$s %%`, with the space German typography wants). A literal percent in a lang value is a trap rather than a crash — `TranslatableContents` accepts only `%s` and `%%`, and `decompose()` **catches** the exception a bad template throws and falls back to the raw text, so the player would silently read `Auslastung: %1$s%% der letzten Minute`. `LangConsistencyTest#everyPercentSignIsAFormatMinecraftAccepts` therefore checks **every** key of both lang files, not just this one: that is the shape of mistake nothing else would ever notice.

### 9.6 The other two surfaces
The same record is read in two more places, and each gets only what it can carry.

**The warehouse controller's goggles: two lines.** Inside the crane block it already shows, below the job and its route:

```
  Busy: 78% of the last minute                             Auslastung: 78 % der letzten Minute
  Blocked: 4%                                              Blockiert: 4 %
```

That tooltip is already 14 lines for an ordinary working warehouse and about 28 for a built-out one, so two is what it can afford — and they are the two a player walked to the controller for ("is the crane my bottleneck, and is anything holding it"). It is the `detailed = false` form of the very same `CraneGoggleInfo#addGoggleLines` the dock calls, so there is no second place that decides what a share means. The number is a **fresh** snapshot merged into the summary in `WarehouseControllerBlockEntity#createSummary`, not the dock's cached `goggleInfo`, which carries no measurement at all (§9.4): that method runs in exactly two places, neither of them a tick — an observation, throttled to one packet a second, and a naming click — so the merge is both safe and exact. `ControllerGoggleSummary` itself gains **no field**: it already carries the crane's record, which is the whole reason the measurement was put there and not on it (ADR-039).

**A Display Link: four rows.** `wareworks:crane_throughput` on the dock, the fifth display source — `warehouse-system.md` §10.1 has its rows, its `Measuring` rule and why it is a second source rather than four more lines on `Crane Status`. It reads `StackerCraneBlockEntity#throughput()` live on the server rather than the synced record, so a board reads the same whether or not anybody is wearing goggles.

### 9.7 Verification
`ThroughputWindowTest`, `CraneActivityTest` and `CraneTickMotionTest` (53 JUnit cases) pin the arithmetic, the precedence of the six buckets over all 13 phases × 6 motions × paused × hasJob, the ring's rolling, forgetting, gap and clear rules, the warm-up guard (100 ticks of pure work must read 100 %), and the agreement with `CraneSoundCues`. `gametest.CraneThroughputGameTests` pins what needs a world: the buckets adding up over a real store job with one trip and the exact items; a crane that turns at a corner booking turning and corners, and a straight one booking neither; a parked crane booking only idle and paused and therefore showing nothing; the update tag carrying no throughput at all until the dock is observed and exactly the server's record afterwards; and a resumed save crediting no trip. Goggle *lines* cannot be built in a GameTest at all — `LangBuilder#forGoggles` measures `Minecraft.getInstance().font`, which a dedicated server has not got — so `CornerVisualScenario` builds the four dock lines through the real renderer and the real lang files, in **English and then in German**, from crafted numbers whose five shares are all different: it asserts that each line is translated text with the numbers it was given (no `wareworks.…` key, no raw `%1$s` template, no unresolved `%%`), that the corner count is printed only on a warehouse that bends, that the blocked line is left out at zero, and that a window with nothing in it adds no line at all. Since the M25 evidence pass the same scenario also **photographs** the dock's own tooltip and the controller's single line, in both languages, at the end of a minute of real work that really holds corners. That camera was the hard part: the dock's outline is a three-pixel rail bed whose one large face — the top — carries the mast-height value box, which Create's overlay refuses to draw a tooltip over, so `dockView` looks **down on the north strip** of that face and lands its ray 0.12 along it, 0.38 clear of the box's 0.25-radius sphere; `GoggleShots` fails the run with that very message if the clearance is ever lost.

The two surfaces of §9.6 are pinned where they live. `gametest.DisplayLinkGameTests` covers the board: `displaysourcesregistered` holds the dock's two sources **in order** (`Crane Status` first, so no link already hung on a dock changes what it shows), `cranethroughputwhilemeasuring` holds the one-line `Measuring` and `No crane` cases, and `cranethroughputnumbersonboard` fills the window to a whole minute with the crane standing still, runs a real store job on top of it and compares all four rows against the dock's own record. `LangConsistencyTest#theThroughputBoardRowsFitADisplayBoard` holds every one of those rows inside a board row's flap count in **both** languages — German is where a row runs out of flaps first. The controller's two lines come out of the same `addGoggleLines` the dock's do and are covered by the same checks; `WarehouseControllerGameTests#controllerGoggleSummary` keeps its synced summary inside 2048 bytes with the measurement in it.
