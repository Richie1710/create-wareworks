# Warehouse System

> Status: **binding design** since MVP 0.1. Changes to the design are recorded here with a reason.

## 1. Physical layout of an aisle

```text
Top view, aisle direction f = facing of the Stacker Crane (here: east →)

 side LEFT  (f.getCounterClockWise())      z-1: [intf][intf][intf][intf][intf]      ← rack plane LEFT
                                                 [chest] behind each interface (z-2)
            [CTRL] [DOCK] [rail][rail][rail][rail]   ← aisle line (crane travels here)
 side RIGHT (f.getClockWise())             z+1: [in  ][intf][intf][intf][out ]      ← rack plane RIGHT
                                                 [chest] behind each interface (z+2)
  position:   -1     0     1     2     3     4
```

| Element | Rule |
|---|---|
| **Stacker Crane** (dock) | Block `wareworks:stacker_crane`, horizontal `FACING` = aisle direction `f`. Position index **0**. Kinetic input from **below** (rotation axis Y). |
| **Warehouse Rail** | Block `wareworks:warehouse_rail`, horizontal axis property. The aisle **length L** = number of consecutive rails at `dock + f·1 … dock + f·L` whose axis equals `f.getAxis()`, capped at `maxAisleLength` (config, default 32). |
| **Mast height H** | Scroll value on the crane, `1 … maxMastHeight` (config, default 16), default 4. Levels `0 … H-1` are reachable. |
| **Controller** | Block `wareworks:warehouse_controller`, must be directly **behind** the dock (`dock − f`) and face the dock. |
| **Rack positions** | For position `x ∈ [0, L]`, level `y ∈ [0, H-1]`, side `s ∈ {LEFT, RIGHT}`: `rackPos = dock + f·x + s·1 + up·y`. |
| **Storage location** | A `warehouse_interface` at a rack position whose `FACING` points **away from the aisle** (`FACING == s`). Its attached inventory is at `rackPos + s`. |
| **Stations** | `warehouse_input` / `warehouse_output` at any rack position; `FACING` points towards the aisle (`FACING == s.getOpposite()`). Typical place: position 0 next to the dock. |

Blocks at rack positions that do not satisfy the facing rule are ignored and reported in the controller's goggle tooltip ("misaligned").

**Implementation (M2, aisle core):** (M4 polish: the rail model has a `display.gui` scale of 0.85 lifted by 5 px, so the thin rail reads as an inventory icon.) `content.crane.WarehouseRailBlock` is a thin 3 px block with `HORIZONTAL_AXIS` from the player's look direction. It is waterloggable, the wrench toggles its axis, and it has no block entity; pickaxe only, drops itself. Rails are not relocation-protected, because moving one only changes the next rail count. The dock is described in `stacker-crane.md` §2.1. The rack position formula lives in `content.controller.AisleLayout`, and rail counting in `content.crane.RailScan` (`stacker-crane.md` §3.1). GameTest `aislelayoutroundtrip` checks the mapping for all four facings.

**Implementation (M2, controller):** `content.controller.WarehouseControllerBlock` faces the player's horizontal look direction on placement (standing behind the dock, looking at it). The controller links only to a dock at `pos + FACING` whose `HORIZONTAL_FACING` equals its own. Facing rules per kind are in `core.warehouse.LocationKind` and `content.controller.WarehouseMember#isAlignedWith`. Details: §3.3.1 and §4.

## 2. Addresses

```text
A-03-07R    aisle A · level 03 · position 07 · rack side R
```

* **Aisle letter**: scroll value on the controller (A–Z), default `A`.
* **Level** = `y + 1`, two digits. Level 01 is the dock's height.
* **Position** = `x`, two digits. Position 00 is the dock column, 01 the first rail.
* **Side**: `L` or `R`, relative to the aisle direction. It is appended because racks are two-sided and `A-LL-PP` alone would be ambiguous.
* Addresses are derived from geometry and never stored as the source of truth. The source of truth is `(x, y, side)` relative to the dock.

**Implementation (M2):** `core.address.StorageAddress(aisle, level, position, side)` with `format()`, a strict `parse()` (throws `IllegalArgumentException`) and `tryParse()` (`Optional`); `Side` (`letter()`, `fromLetter`, `lateralOffset` −1/+1, `fromLateralOffset`); `RackPosition(x, y, side)`. `StorageAddress.of(letter, rack)` and `rackPosition()` convert between them; `aisleLetter(index)` / `aisleIndex()` map the controller's scroll value to the letter.
* **Refinement:** level and position are written with at least two digits (`%02d`), so values from 100 on get three digits (`B-100-128L`). Both are limited to 999, which also bounds `AisleGeometry`.
* `parse` accepts only the canonical form that `format` produces (upper-case letter, no extra leading zeros, no whitespace), so every address has exactly one spelling.
* JUnit: `StorageAddressTest` (round trip over all letters, sides and boundary numbers; invalid inputs), `AisleGeometryTest`.

## 3. Components

### 3.1 Warehouse Interface (`content.storage`)
* A block with a block entity. It has no own inventory and exposes no item capability.
* It holds a `BlockCapabilityCache<IItemHandler, Direction>` for `pos + FACING` with context side `FACING.getOpposite()`, created lazily on the server.
* `snapshot()` reads the live handler and returns an `InventorySnapshot` (per slot: item key, count, slot limit). Reading happens only on request, never per tick.
* It notifies the `WarehouseRegistry` on load, placement and removal.
* **Store filter (M8, ADR-021).** A Create filter slot on its aisle face decides which items may be **stored** at this location, so a player can partition a warehouse by item.
  * **Empty = accepts everything** (the Create convention), so a warehouse built before M8 behaves exactly as before.
  * All three Create filter items work: **list** filter (as whitelist and as blacklist), **attribute** filter (its rules) and **package** filter (Create packages by address).
  * A filter restricts **storing only**. Retrieval is never restricted: what is already inside can always be fetched, even when the filter no longer matches it.
  * **Ranking:** a location whose filter **selects** the item ranks **above** every unfiltered location, so dedicated chests fill first; the existing rules (exact-item consolidation, item-type grouping, travel time) decide within that (§7.1, §7.4). A **deny** list (or a deny-mode attribute filter) merely does not exclude the item, which is not a dedication: such a location ranks like an unfiltered one (`FilterMatch.ALLOWED`, **M8 review fix** — before, one "anything but iron" chest outranked consolidation and item-type grouping for *every* item in the warehouse and filled with a mix of everything, the exact mixing item-type grouping was added in M3 to prevent).
  * An item that matches no filter needs an unfiltered location. Without one the input keeps the items, exactly as for a full warehouse, and the controller reports **`NO_MATCHING_FILTER`** — its own planning reason since the **M8 review**, because a retrieval frees space but never makes a filter match: the player's fix is an unfiltered location, not more room, and a stuck "no storage location accepts the input items" must not mask a later, genuine full warehouse. It arms the same `fullBackoffTicks` back-off as `WAREHOUSE_FULL` (§7.4).
  * **Changing or clearing a filter never moves, drops or deletes stored items**, and nothing is re-shuffled retroactively. Re-balancing an existing warehouse is a post-MVP idea.
* Goggles: address (or "not part of an aisle"), the store filter (or "Accepts everything"), attached block name, used/total slots, top 3 item types with counts, reserved amounts. Goggle data is kept fresh only while a player looks at the interface through goggles (§3.1.1).

#### 3.1.1 Implementation (M1)
Classes: `content.storage.WarehouseInterfaceBlock`, `WarehouseInterfaceBlockEntity`, `AttachedInventorySummary`; `core.inventory.InventorySummary`; `util.GoggleObservers`. Registered in `WareworksBlocks.WAREHOUSE_INTERFACE` / `WareworksBlockEntityTypes.WAREHOUSE_INTERFACE`.

* **Block**: `HorizontalDirectionalBlock` (own `CODEC = simpleCodec(...)`) + `IBE` + `IWrenchable` (Create default: wrenching the top or bottom face rotates clockwise). `onRemove` delegates to `IBE.onRemove`. Andesite properties (`SharedProperties::stone`), netherite sound, pickaxe-only, tagged `create:non_movable` and `c:relocation_not_supported`, drops itself.
* **No ticker (either side):** `getTicker` returns `null` (overriding `IBE`'s `SmartBlockEntityTicker`). All interface work is event-driven: capability invalidation, `onNeighborChange`, goggle observation and `snapshot()` calls. Consequence: `SmartBlockEntity.initialize()`/`lazyTick()` never run, so the interface must never get behaviours that need ticks (re-add a ticker if that changes). A rack wall of thousands of interfaces costs nothing per tick.
* **Placement (not the look direction alone):** for a click on a *side* face, `placementFacing` inspects the clicked block state:
  * another warehouse interface: copy its `FACING`, so a rack row is built by clicking the side of the previous interface (all interfaces of a row face their inventories);
  * a block with a block entity (`BlockState#hasBlockEntity`, e.g. chest, barrel, vault): face that block, so placing against an inventory attaches to it from any standing position;
  * anything else (stone, rack frames, rails): the player's horizontal look direction.

  Top/bottom clicks, and clicks that replace the clicked block (grass, light), use the look direction (standing in the aisle looking at the rack). Reason: attaching to the clicked inventory is what players expect, and the block-entity/interface checks avoid misaligned interfaces when a row is extended sideways. Only block states are inspected (no capability query), so client prediction and server always agree. Known limitation: inventories without a block entity (composter) fall back to the look direction; the wrench fixes any wrong facing.
* **Attached handler**: `attachedHandler()` returns `Optional<IItemHandler>`. Server: `BlockCapabilityCache` with `isValid = !isRemoved()` and a listener that only sets the summary dirty flag; the cache is dropped in `invalidate()` and whenever `FACING` changes (`setBlockState` override, plus a facing check on every query). Client/Ponder: uncached `level.getCapability`, guarded by `level.isLoaded`.
* **Snapshot**: `snapshot()` reads every slot once via `ItemHandlerSnapshots.capture`. It returns `InventorySnapshot.empty()` (zero slots) when no inventory is attached **or** the attached position is not loaded; `hasAttachedInventory()` distinguishes "no inventory" from "empty inventory". On the server every loaded read also refreshes the goggle summary (counts as a refresh and clears the dirty flag); clients receive a change on the next goggle observation.
* **Goggle summary** (derived state, never saved): `AttachedInventorySummary(hasInventory, attachedBlock id, InventorySummary<Item>)` with used/total slots, the number of distinct **item types** and the top 3 item types (`InventorySummary.of(snapshot, 3, ItemKey::getItem)`). It is written only into client packets (`write(..., clientPacket = true)`), so goggles work on remote clients.
  * **Bounded sync (review fix):** the update tag is also part of every chunk packet, whose block entity tags clients read with a 2 MB NBT quota (`FriendlyByteBuf.readNbt`, `NbtAccounter.create(2097152)`). The first version synced full `ItemKey`s (id + all data components), so a shulker box of written books or an item with a huge name as a top entry could exceed the quota and disconnect everyone loading the chunk. The summary is therefore grouped by `Item` and synced as registry ids and counts only: under 2 KB in NBT size accounting whatever the inventory holds (`Tag#sizeInBytes`, which counts 36 + 2 bytes per character for every string and 28 + 36 bytes per compound entry, several times what is sent), and no component data (container contents, book pages, names) leaves the server. Side effect: items that differ only in components (damaged or enchanted tools, enchanted books) share one line, shown with the item's generic name (`Item#getDescription`, e.g. "Potion", not "Uncraftable Potion"). This matches §3.1 ("top 3 item types"). GameTest `interfacesummarysyncisbounded` checks the worst-case tag (shulker box of written books, a 30 000 character name, damaged tools) against `WareworksGameTests.MAX_INTERFACE_SYNC_BYTES` (2048), and the same tag with the largest reservation summary (below) against `MAX_RESERVED_INTERFACE_SYNC_BYTES` (4096). *M4 review fix:* the reservations were never part of a budget test before, and the reservation test compared the interface tag with the crane's 8 KB bound.
  * **Refresh (review fix, observer-driven):** the summary is only read for someone who looks. Dirty sources (load, facing change, capability cache invalidation for inventory placed/removed/replaced or chunk load, and `Block#onNeighborChange` from the attached position, which vanilla containers and Create's item vault call on content changes) only set the dirty flag; nothing is read while nobody observes. `util.GoggleObservers` runs per server player (`PlayerTickEvent.Post`, every `SCAN_INTERVAL_TICKS` = 5, staggered by entity id): a non-spectator player wearing goggles ray-picks the looked-at block (`Entity#pick`, block interaction range + 1, fluids ignored) and notifies its block entity if it implements `GoggleObservers.Observable`. Cost: one short ray per goggle-wearing player per interval, independent of the number of interfaces (the first version polled players from every interface, interfaces x players). On `onGoggleObserved()` the interface re-reads a dirty summary at most once per `OBSERVED_DIRTY_REFRESH_MIN_INTERVAL_TICKS` (5) and a clean one once per `OBSERVED_REFRESH_INTERVAL_TICKS` (20), which catches inventories that change silently. Trade-off: after a change or chunk load, a player who starts looking sees the old summary until the next check, usually at most about 5 ticks plus network delay (up to `SUMMARY_SYNC_MIN_INTERVAL_TICKS` if a sync was sent just before).
  * Sync: `sendData()` only from an observation, only when the summary changed, at most once per `GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS` (20); a throttled or unobserved change stays pending and is sent on a later observation. The pending flag and throttle are `util.SyncThrottle`, shared by interface, stations and controller (M2 review fix; before, each block entity had its own copy and constant). Summaries refreshed by `snapshot()` calls (controller from M2 on) are therefore never broadcast unless someone looks.
  * A foreign inventory that throws while being read keeps the last summary instead of crashing the tick. The failure is logged at most once per `util.LogThrottle` interval (1200 ticks) per interface (**M5 release audit**: a one-shot flag was used before, which silenced every later failure of that interface for good, including a different inventory attached after a wrench).
* **Goggle lines**: "Storage Location:", then the aisle assignment ("Address: A-03-07R", or "Misaligned" with the hint "Turn the brass port away from the aisle", or "Not part of an aisle"), the reserved amounts (below), "Inventory: <block>" or "No inventory attached", "Slots: used / total", top 3 item types "name xN" and "...and N more" / "Empty".
* **Reserved amounts (M4, carried over from M3)**: "Incoming: Iron Ingot x32" for items a crane job brings (capacity reservations; transit at stations) and "Reserved for pickup: Diamond x10" for items reserved inside the location for a pick (stock reservations), at most 2 item types per direction, only while something is reserved.
  * On every goggle observation, `WarehouseRegistry.observeStorage` resolves the assignment and, for an assigned interface, finds the controller of the aisle in which it is aligned in the same registry scan (`StorageObservation`; M4 review fix: before, the assignment and the reservations each scanned the registry) and asks `WarehouseControllerBlockEntity#reservationSummaryAt(rack)`, which reads `ReservationView#reservationsAt` of the location that counts the inventory (`sharedInventoryOf`, where the planner reserves; interfaces sharing a double chest show the same reservations). Cost per observation: the registry containment tests plus one pass over the ledger's reservations, which are derived from the crane's job (at most three per job).
  * **Bounded sync**: `content.controller.LocationReservationSummary` holds at most 2 item ids and counts per direction, compares by value and is written into the client packet with the other goggle data through the same `SyncThrottle` (sent only when it changed, at most once per 20 ticks); it is never saved and never carries item components. Reading never throws and reads at most 2 entries per direction. Size: about 540 accounting bytes for one entry, and about 1.7 KB for the largest summary (2 + 2 entries with ids of 34 to 44 characters). Only the locations of running jobs carry it, so the 2 KB budget of the other interfaces is unaffected; the tag with the largest summary stays within 4096 bytes (GameTest above).
  * GameTest `interfacegogglesshowreservations` (`gametest.CraneJobGameTests`): nothing before a job; incoming iron x32 at the store target from the assignment to the drop, also in the update tag (within `MAX_RESERVED_INTERFACE_SYNC_BYTES`) and on a client copy; nothing after the drop; reserved iron x10 at the retrieve source before the pick; nothing after the delivery. JUnit `ReservationLedgerTest#reservationsAtALocation`.
* **Aisle membership (M2, controller)**: the interface is a `content.controller.StorageMember` (`WarehouseMember` of kind `STORAGE`, plus `attachedPos()` and `snapshot()`). `onMembershipRelevantChange()` (server: load/placement, rotation, removal in `remove()`) calls `WarehouseRegistry.memberChanged`. `invalidate()` does not notify (§4). On every goggle observation the interface resolves its `AisleAssignment` through the registry, a few containment tests (`WarehouseRegistry.observeStorage` since M4, together with its reservations; stations use `assignmentOf`). It syncs a change together with the summary: a state name and an address string of at most a dozen characters, so the size is bounded, and nothing is saved. The assignment is derived from the registered layout and the interface's own facing, so it is right even before the controller's next membership scan. GameTests: `controllerfullaisle`, `controllermembershipchanges`, `controllerregistrationandremoval` (address, misaligned, not part of an aisle, client sync).
* **Stock hints (M2 review fix, ADR-013)**: the neighbour-change hint (`onNeighborChange` from the attached position) and a block update from the attached position (`neighborChanged`: inventory placed, removed or replaced) also call `WarehouseRegistry.contentChanged`, so the controllers containing the interface re-read the location within a few ticks (§5). Before, the hint only marked the goggle summary dirty and the controller relied on the round robin alone, which takes up to 2·(L+1)·H·`snapshotIntervalTicks`: about **9 minutes at the default caps** (32 × 16 → 1056 locations × 10 ticks) and about **2.3 hours at the largest geometry the config allows** (128 × 64 → 16 512 locations). Scaling the round robin with the aisle size, so the cycle time is bounded instead of the per-tick count, is a post-MVP item (**M5 release audit**: this sentence used to call the default figure "the maximum geometry"). The capability cache listener still only sets the dirty flag, because it can run while chunks unload.
* **Model**: hand-made `assets/wareworks/models/block/warehouse_interface/block.json` (Blockbench format, authored facing north): andesite casing body, brass casing port frame on the `FACING` side with a recessed dark opening (`create:block/chute_hole`), and (review fix) a brass-framed flush andesite plate on the aisle side, so a rack wall of interfaces is recognisable from the aisle and not mistaken for plain andesite casing. **M4 review fix:** the plate has a dark arm port where the crane's telescopic arm enters: an 8 x 4 px slot (x 4..12, y 9..13 under the brass frame) recessed 3 px with `chute_hole` reveals and back wall (`stacker-crane.md` §7.1). Before, the arm went through the solid plate. The plate around the narrow slot keeps the aisle side distinct from the fully open brass port on the inventory side, which the "Turn the brass port away from the aisle" hint refers to. The boundary is fully covered except the two closed recesses, so the block keeps full-cube occlusion. Blockstate (`BlockStateGen.horizontalBlockProvider(true)`) and item model (`ModelGen.customItemModel("_", "block")`, parent = block model) are generated.
* **Store filter (M8, ADR-021)**: `content.storage.StorageFilterBehaviour` (a Create `FilteringBehaviour`) with `content.storage.StorageFilterValueBox`; the controller side is `content.controller.AisleFilters` and `core.job.FilterMatch`.
  * **One code path for all three filter items.** `FilterItemStack.of(stack)` resolves a Create filter item into `ListFilterItemStack`, `AttributeFilterItemStack` or `PackageFilterItemStack` (`FilterItem#makeStackWrapper`), and `test(level, stack)` applies that filter's own rules. The slot installs **no** `withPredicate` — unlike the warehouse output, whose request needs one concrete item (§3.2.1) — so list, attribute and package filters are supported by not special-casing any of them. Two verified subtleties: `FilterItemStack.of` only wraps a filter item whose **component patch is non-empty**, so an *unconfigured* filter item matches by item like a plain stack (Create's own behaviour everywhere); and it **trims** enchantments and attribute modifiers on the stack **in place**, which is why `StorageMember#storeFilter()` returns copies. A plain item filter compares the `Item` only and ignores components (`ItemHelper.sameItem`), so an "iron ingot" filter accepts every iron ingot.
  * **No count.** The slot has no `showCount()`, so `acceptsValueSettings()` is false: a click sets or clears the filter and no hold-to-edit board opens. An amount would be meaningless for a storage location.
  * **Face (binding, ADR-021):** the **aisle face only**, centred at x 8 px, y 5 px, i.e. the lower half of the framed plate and clear of the arm port at y 9..13 px (`stacker-crane.md` §7.1). Create's `ValueSettingsInputHandler` **cancels a right-click that hits a value box with `SUCCESS`**, so a value box pre-empts block placement on its face: the lateral faces carry the row-building placement rule (above), top and bottom carry the wrench, and the `FACING` side is covered by the attached inventory. The box is hit-tested within half its scale (4 px), so it reaches neither the arm port nor the rest of the face, which still places blocks normally.
  * **No ticker needed**: `FilteringBehaviour` overrides neither `tick()` nor `initialize()`, and Create drives its value box and hover rendering from client events, so the "no ticker" rule above still holds unchanged. The filter item is drawn in the world by a `SmartBlockEntityRenderer`, now registered for the interface's block entity type as it already was for the output (since the M8 review the small subclass `client.render.WarehouseInterfaceRenderer`, see "Renderer view distance" below).
  * **Persistence** is Create's: `Filter` / `FilterAmount` / `UpTo` in the block entity's own tag (`isSafeNBT`, so a schematic keeps it). A save without those keys — every world from before M8 — reads back as an empty filter, i.e. unchanged behaviour. Create writes all three keys together and its `read` takes a migration branch when the **count** is missing, so a test for this path must remove all three, not just `Filter` (M8 review fix in `filterpersistenceroundtrip`, which removed only `Filter` and therefore exercised the post-M8 path).
  * **Bounded sync (important):** Create writes those three keys into **every** client packet, which costs about 215 accounting bytes in the update tag of every interface — and that tag is part of every chunk packet (the budget above). `StorageFilterBehaviour#write` therefore skips the block entirely for client packets while the filter is empty, and Create's `read` reads a missing tag back as "no filter". Only interfaces a player actually filtered carry their filter stack, which is the same exposure every Create filter block and the warehouse output already have (§3.2.1). Found by `interfacesummarysyncisbounded`, which now also asserts that an unfiltered interface writes no filter at all.
  * **Controller cache.** The planner asks about every storage candidate of every run (up to 2·(L+1)·H, every `dispatchIntervalTicks`), so the filters live in `AisleFilters` (rack position → `FilterItemStack`): one map lookup per candidate instead of one block entity lookup. It is filled where the controller already resolves a location (`refreshLocation`: joins, content hints, the round robin, after every transfer, load verification) and by `WarehouseRegistry.filterChanged`, which an interface fires from its filter callback so the next planning run already honours a change. Nothing is persisted here — the filter itself lives in the interface. A filter that throws while being evaluated counts as *rejected*, not as "accepts everything", so a broken filter never quietly fills a chest a player set aside; the failure is logged at most once per `util.LogThrottle` interval.
    * **"Not read yet" is a third state, not "no filter" (M8 review fix, binding).** Because nothing is persisted, the cache is **empty** after every world or chunk load while the restored locations are still queued for their background snapshots, which drain at only `maxSnapshotsPerTick` (default 4) per tick — and `CraneDispatch` plans on the very first tick. A plain cache miss answered `UNFILTERED`, so for about ceil(n/4) ticks (≈ 264 at the default caps of 1056 locations) every not-yet-read location looked like general storage and the crane stored into chests the player had dedicated. Because a filter change never re-shuffles anything, that misplacement is **permanent**. Restored records (`read`) and joining locations (`processMembership`) are therefore marked **unread**, and the first `match` for such a rack resolves its interface once (`WarehouseControllerBlockEntity#readStoreFilterAt`: one `getBlockEntity` plus `storeFilter()`, no inventory read) and caches the answer — one block entity lookup per location *ever*, which the performance rules allow. A rack that cannot be resolved at that moment counts as **rejected**, never as unfiltered: the planner has other candidates, and storing into a location whose rule cannot be checked is exactly what this prevents. GameTest `filtercoldcacheafterreload`.
    * **An unchanged filter is not rebuilt** (M8 review fix): `refreshLocation` runs on every refresh path, while a filter only changes when a player clicks it, and resolving a Create list filter allocates an 18-slot handler plus a nested wrapper per entry. The cache keeps the **untrimmed** source stack next to the wrapper and compares with `ItemStack.isSameItemSameComponents` before rebuilding — untrimmed, because `FilterItemStack.of` removes enchantments and attribute modifiers *in place* and a removal is recorded in the component patch, so the wrapper's own stack never compares equal to what the behaviour hands out.
    * **One probe stack per key** (M8 review fix): `match` used to build `key.toStack()` (a copy) per candidate. `FilterItemStack#test` treats the tested stack as read-only in every Create wrapper — the same assumption Create itself makes wherever it passes a caller's stack — so one probe per key serves a whole planning run. A per-(location, key) memo was considered and **rejected**: `plan` iterates input × key, so the key changes on every `selectStorage` call and a tick-scoped memo would never hit, while a full memo would hold up to (keys × locations) entries for one tick — more garbage than the copies it saves.
  * **Known limitation:** entries are keyed by rack position and the planner only asks about the location that *counts* an inventory (`sharedInventoryOf`), so for an inventory read by several interfaces (double chest, item vault) the filter of that counting location is the one that applies. **The UI no longer contradicts that** (M8 review fix): `filteredLocationCount()` and `isStorageFiltered(...)` skip shared-inventory aliases, so the controller never counts a filter that cannot do anything, and the shadowed interface itself shows a gold hint ("Without effect: another interface counts this inventory"), resolved in the same registry scan as its address (`WarehouseRegistry.StorageObservation#filterShadowed`) and synced as one flag written only when it is set. Which of the two interfaces is canonical depends on the order `SharedInventories.assign` saw them, so the hint is the only way a player can tell. GameTest `filteronsharedaliasisnotcounted`.
  * **Goggles:** the interface shows "Filter: <item>" or "Accepts everything"; the controller shows "Filtered locations: N" under its storage count, and only when N > 0. The controller line is a number, so its summary stays bounded.
    * **A Create filter item is named by its contents** (M8 review fix): all three filter items share one generic name and one icon, so several chests dedicated with List Filters all read "Filter: List Filter" — useless for the headline use case of this feature. Under the filter line the interface therefore shows Create's own `FilterItem#makeSummary` lines (allow or deny list and its first entries, the attribute rules, a package address), already translated by Create and cut to `MAX_FILTER_DETAIL_LINES` (4). A plain item in the slot needs none: its own line already names it.
    * **An empty filter item is a warning, not a filter.** `FilterItemStack.of` only wraps a filter item whose component patch is non-empty, so a freshly crafted filter matches *by item* and accepts nothing but other filter items; a list filter whose entries were all removed matches nothing either. Both turn the location into dead storage while "Filter: List Filter" looks perfectly normal. Such a filter (an empty `makeSummary`) gets a gold "Empty filter: this location accepts nothing" line instead of detail lines. The behaviour itself is Create's own and is deliberately **not** changed — treating a blank filter as "accepts everything" would diverge from Create everywhere else; only the misleading presentation is fixed.
  * **Only a real player may set a filter** (M8 review fix): Create's `ValueSettingsInputHandler` skips the 4 px hit test entirely for a `FakePlayer` and interacts immediately, so *any* right-click a deployer aims at the aisle face — the one face in-aisle automation can reach — would re-dedicate the chest wherever it hit, and `onShortInteract` hands a previously set filter item to that fake player's inventory, where it is discarded. `StorageFilterBehaviour#mayInteract` refuses fake players; it is checked before that shortcut and covers the clipboard path as well. The "Face" note above ("hit-tested within half its scale") holds for real players only.
  * **Renderer view distance** (M8 review fix): vanilla only puts a block entity into its chunk section's per-frame list when its type has a renderer, so registering one put the interface — the block a warehouse places by the hundred — into that list with the vanilla default view distance of 64 blocks. What it can draw is cut off at Create's client config `filterItemRenderDistance` (default 10), and only *after* iterating the behaviours and allocating a centre vector. `client.render.WarehouseInterfaceRenderer` is `SmartBlockEntityRenderer` with `getViewDistance()` returning that config value, so a rack wall is culled before the renderer is entered. The warehouse output keeps the stock renderer: an aisle has a handful of them, not a wall.
  * **Tests**: `gametest.StorageFilterGameTests` — `filterlistwhitelist`, `filterlistblacklist`, `filterattributerule`, `filterpackageaddress` (one per Create filter item), `filtermixedstreamperchest` (three item types into three dedicated chests, an unmatched item into the unfiltered one), `filterunmatcheditemisnotstored` (`NO_MATCHING_FILTER`, the input keeps the items), `filterchangedkeepsstock` (a re-dedicated location keeps its stock and stays retrievable), `filterpersistenceroundtrip` (a save without **any** of Create's three filter keys — what a pre-M8 world really looks like — loads unchanged; a filter round-trips), and the four tests the M8 review added: `filterdenylistdoesnotoutrankconsolidation` (a deny-list chest nearer to the input than a chest that already holds the incoming item: the item must consolidate, because a deny list is not a dedication — `filterlistblacklist` uses two empty chests and cannot see a ranking), `filtercoldcacheafterreload` (six dedicated locations, more than `maxSnapshotsPerTick`, the controller replaced by a copy loaded from its save in the same tick the items arrive: nothing may enter a dedicated chest before its filter was read back), `filterdropswhenbroken` (breaking a filtered interface drops the configured filter item, still selecting what it selected — the §8 invariant for the slot itself, which nothing pinned before) and `filteronsharedaliasisnotcounted` (a double chest: the alias's filter is neither counted nor reported as active, and its interface shows the hint); plus `filtergoggles` (both goggle summaries and the bounded sync in both directions). Each test that moves items asserts the item conservation invariant on every tick. JUnit: `JobPlannerTest` (dedicated ranking, deny lists ranking like unfiltered locations, rejected candidates costing no live call or budget, `NO_MATCHING_FILTER` vs `WAREHOUSE_FULL`, retrieval ignoring filters, store reroutes respecting them and retrieve reroutes returning leftovers to a rejecting source).
* **Tests**: `gametest.WarehouseInterfaceGameTests` (`interfacechestsnapshot`, `interfacenoinventory`, `interfaceinventoryremoved`, `interfacebarrelandvault`, `interfacerotation`, `interfaceplacement`, `interfacegoggleobserver`, `interfacesummarysyncisbounded`, `interfacesummarymalformedtags`, `interfacebreak`); `InventorySummaryTest` (JUnit). The two hint tests assert the dirty flag directly after the content change, so they fail when the `onNeighborChange` hint is disabled. `interfacegoggleobserver` covers the ray pick with a mock player (goggles, spectator, non-observable block, silent change). Not covered by a GameTest: an attached position in an unloaded chunk (GameTest areas are force-loaded, and an unloaded chunk directly next to a test structure cannot be set up reliably; the branch is a plain `level.isLoaded` guard).

### 3.2 Stations (`content.station`)
**Warehouse Input**
* `ItemStackHandler` buffer with `inputBufferSlots` slots (config, default 9).
* External item capability: **insert-only** view (extraction returns empty).
* `DirectBeltInputBehaviour`: belts, funnels, chutes and depots can insert.
* Create mechanical arms can put items in, and only put them in (a deposit-only arm interaction point, §3.2.2).
* Non-empty buffer means store work is available. The crane extracts through the internal handler.

**Warehouse Output**
* `ItemStackHandler` buffer with `outputBufferSlots` slots (config, default 9).
* External item capability: **extract-only** view, so funnels and chutes can pull from it.
* Create mechanical arms can take items out, and only take them out (a take-only arm interaction point, §3.2.2).
* `FilteringBehaviour` with count: item and amount to request.
* A redstone **rising edge** creates a retrieval request `(item, amount)` at the controller.
* Goggles: buffer contents, pending request with remaining amount, last rejection reason.

#### 3.2.1 Implementation (M2, stations)
Classes:
* `content.station`: `WarehouseStationBlock` / `WarehouseStationBlockEntity` (shared base), `WarehouseInputBlock` / `WarehouseInputBlockEntity`, `WarehouseOutputBlock` / `WarehouseOutputBlockEntity`, `StationBuffer`, `StationGoggleSummary`.
* `content.item`: `InsertOnlyItemHandler`, `ExtractOnlyItemHandler`, `ItemTypeSummaries` (the item-type goggle summary NBT form, now shared with `AttachedInventorySummary`).
* `content.controller`: `RequestRejection`, `RequestResult`; `core.job`: `RequestQueue`, `RetrievalRequest` (§7.2).

Registered as `WareworksBlocks.WAREHOUSE_INPUT` / `WAREHOUSE_OUTPUT` and `WareworksBlockEntityTypes.WAREHOUSE_INPUT` / `WAREHOUSE_OUTPUT`; the capabilities are listed in `WareworksCapabilities`.

* **Block** (`WarehouseStationBlock`): `HorizontalDirectionalBlock` + `IBE` + `IWrenchable`.
  * `FACING` points towards the aisle. **Placement faces the player** (`getHorizontalDirection().getOpposite()`): standing in the aisle and placing into the rack gives the aligned facing, whatever face is clicked. The wrench on the top or bottom face rotates clockwise.
  * Stone properties, netherite sound, pickaxe only, `create:non_movable` + `c:relocation_not_supported`, drops itself (Registrate default loot), not `create:safe_nbt`.
  * **No redstone conduction** (`isRedstoneConductor` false), so a signal for one output never powers a neighbouring station through a station.
  * **No ticker** on either side: stations work only on insertions, extractions, redstone, goggle observation and lifecycle events. No behaviour they use ticks (`FilteringBehaviour` and `DirectBeltInputBehaviour` have no `tick`/`initialize`).
  * `onRemove` delegates to `IBE.onRemove`, so `destroy()` runs on a real break.
* **Output block** (`WarehouseOutputBlock`): extra `POWERED` blockstate. `getStateForPlacement` initialises it from `hasNeighborSignal`, so placing next to a powered block requests nothing. Placements without a player (`/setblock`, structures, Create's schematicannon) keep a default or stored state, so `onPlace` (new block only, `!oldState.is(this)`) stores the signal with `UPDATE_ALL` and requests nothing (vanilla `HopperBlock` pattern; M2 review fix, before the next unrelated neighbour update fired a request). `neighborChanged` (server) compares `hasNeighborSignal` with `POWERED`, stores a change with `UPDATE_CLIENTS` and submits a request only on false → true. Holding the signal, further neighbour updates and chunk reloads never repeat a request. All `powered` variants use the same model (`BlockStateGen.horizontalBlockProvider`).
* **Buffer** (`StationBuffer extends ItemStackHandler`):
  * Size from `inputBufferSlots` / `outputBufferSlots`, read once in the block entity constructor through the safe getter.
  * **No aliasing:** `insertItem` and `setStackInSlot` store copies (`ItemStackHandler` would keep the caller's instance in an empty slot); `insert` and `extract` never hand out or keep a caller's stack.
  * **Persistence:** `Buffer {Size, Items [{Slot, Item: ItemKey, Count: int}]}`; `ItemStack.save` is never called and saving never throws. Loading never throws and never drops a readable item: the buffer gets at least the configured slots and more if the save used higher slots (a lowered config keeps everything; trailing empty slots shrink back). Entries with duplicate, negative or out-of-range slots (≥ `MAX_SAVED_SLOT_INDEX`) or counts above one stack are split into stacks and placed into free slots, growing the buffer if needed. Undecodable items (removed mod) are lost, as in vanilla containers.
  * **Bounded loading (M2 review fix, critical):** save data is untrusted. Block entity data on items (`BlockItem.updateCustomBlockEntityTag`, which any creative player can set because `onlyOpCanSetNbt()` is false), `/data merge`, structures and schematics uploaded to a Schematicannon all reach `load`. The first version expanded a saved `Count` of `Integer.MAX_VALUE` into billions of stacks (server `OutOfMemoryError`). A load now creates at most `StationBuffer.MAX_LOADED_STACKS` (1024) stacks and slots in total; the excess of a crafted entry is skipped with one warning, and entries beyond the budget are not even decoded. Legitimate saves (at most one stack per slot, `≤ 27` slots) are never affected.
  * A load that changes the slot count on a live block entity calls `invalidateCapabilities()`: the view object stays the same, but cached consumers should re-read the slot count. Otherwise NeoForge's automatic invalidation (placement, removal, chunk load/unload) is enough, because the registered views are final fields.
* **Input** (`WarehouseInputBlockEntity`):
  * Capability (every side, stable instance): `InsertOnlyItemHandler` over the buffer. Funnels, chutes and hoppers insert; extraction returns empty. Since **M12** a Create **mechanical arm** can target the input as well, through its own arm interaction point type `wareworks:warehouse_input`: a **deposit-only** point that inserts through this same view, whatever mode a click or a saved tag asks for (§3.2.2). Until then no Wareworks block was an arm target (M5 release audit).
  * `DirectBeltInputBehaviour` with an insertion handler that inserts a copy into the buffer and returns the remainder; the direction Create passes is ignored (inconsistent across callers). Belt funnels on top are not enabled.
  * Crane API (M3): `bufferedItems()` (`InventorySnapshot<ItemKey>`), `hasBufferedItems()`, `extract(ItemKey, amount, simulate)` (at most one stack per call, simulate equals the real result in the same tick), `countOf(ItemKey)` (live count without a snapshot), `insert(ItemStack, simulate)` (rerouted store leftovers go back into the buffer, §8).
* **Output** (`WarehouseOutputBlockEntity`):
  * Capability (every side, stable instance): `ExtractOnlyItemHandler` over the buffer; insertion returns the stack unchanged and `isItemValid` is false. No belt input. Since **M12** a Create mechanical arm takes items out through the arm interaction point type `wareworks:warehouse_output`, a **take-only** point (§3.2.2).
  * Crane API (M3): `insert(ItemStack, simulate)` (matching stacks first, returns the remainder, never modifies the caller's stack), `bufferedItems()`.
  * Request definition: `content.station.RequestFilterBehaviour`, a subclass of Create's `FilteringBehaviour` with `showCount()` and label "Requested Item". List, attribute and package filter items are refused (the `requestable` predicate), because a request needs one concrete item. Amount = filter count clamped to `1..maxStackSize` of the filter item (ADR-013: one stack per request, unstackable items request 1).
    * **Faces (M2 review fix):** top, back (pull port) and both sides; not the bottom and not the aisle side, where the value box floated inside the crane opening (M4: the arm reaches in there). Faces covered by rack neighbours or a funnel cannot be clicked; a Create clipboard pastes onto any face.
    * **Clipboard (M2 review fix, item loss):** Create's `FilteringBehaviour#readFromClipboard` takes a filter item of the pasted type from a survival player's inventory before `setFilter` asks the predicate, and the refused item is never returned. `readFromClipboard` therefore returns false for a clipboard whose `Filter` is not requestable, also in simulation (no paste offered), before calling Create's code. Any other clipboard pastes as usual.
    * **"Up to" only (M2 review fix, deviation from the stock board):** the controller always clamps to the available stock (§7.2), so the hold-to-edit board ("Requested Amount") offers only Create's "Up to" row; `setValueSettings` and `read` store every row as up to, so an "Exactly" clipboard or old save cannot promise something the request does not do. The value box shows the number (Create shows "*" for a full stack).
  * The filter item is drawn by Create's `SmartBlockEntityRenderer`, registered with Registrate's `.renderer(...)` only. The filter stack (with its components) is part of the client packet, as for every Create filter slot.
  * `submitRequest()` on a rising edge: empty filter → `NO_FILTER`; no controller from `WarehouseRegistry.findController` → `NO_CONTROLLER`; otherwise `controller.request(pos, ItemKey.of(filter), amount, maxRequestAmount())` (§7.2; since M7 the output passes its own amount bound, because repeated pulses for one item merge into the open request instead of taking another queue slot). A refusal is saved as `LastRejection` and cleared by the next accepted request.
* **Membership**: both block entities implement `WarehouseMember` (kinds `INPUT` / `OUTPUT`, aligned when `FACING == side.getOpposite()`). They notify `WarehouseRegistry.memberChanged` from `onLoad()`, after a facing change (`setBlockState`) and from `remove()`, like the interface (§4). The controller counts them without further changes.
* **Drops and `Clearable`**: `destroy()` (server, real break or replacement) drops every buffered stack with `Containers.dropItemStack`, which ignores `doTileDrops`; `clearContent()` empties the buffer for `/clone ... move`, `/setblock` and structure placement.
* **Goggles** (observer-driven through `GoggleObservers`; nothing is read while nobody looks): "Warehouse Input:" / "Warehouse Output:", the aisle assignment ("Address: A-01-00R", "Misaligned" with the hint "Turn the opening towards the aisle", or "Not part of an aisle"), for outputs "Items requested: N (requests: M)" with "Delivered so far: D" (M3: items of the open requests already dropped into the buffer) or "No pending request" and "Last request refused: reason", then "Buffer:" with slots and the top 3 item types.
  * Synced as a `StationGoggleSummary`: assignment, buffer summary by item type (ids only), two numbers and a reason name, so its size is bounded; the buffer itself is never in client packets. Sent only when changed, at most once per `GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS` (20, `SyncThrottle`).
  * The assignment lines are shared with the interface (`AisleAssignment#addGoggleLines`).
* **Model**: hand-made, authored with the aisle side facing north, Create textures only; checked by script (bounds, UVs, 16 px textures exist, outer faces covered except the closed recesses).
  * Input: andesite casing with a framed 10 × 10 opening towards the aisle and a framed intake opening on top (`create:block/chute_hole` recesses). **M5 polish:** a stepped feed throat (x 3..13, y 3..5.5) sits in the bottom of the aisle opening, so the opening reads as a chute mouth.
  * Output: **M5 polish:** the whole body and both frames are `create:block/brass_casing` (before: andesite body with brass frames), with the dark openings towards the aisle and on the back (the pull port) and a brass spout in the bottom of the pull port.
  * **Material language (M5 polish):** andesite = the dumb intake, brass = the smart, filtered output — the same distinction Create makes between andesite and brass funnels. Together with the top intake (input only) and the back pull port (output only) the two stations are told apart at a glance from any side, which a frame colour alone did not achieve. An arrow-shaped flow marker was considered and dropped: a proud arrow would clip blocks placed against the station, and an inlaid one cannot go into the aisle face, which has to stay clear for the crane's arm port (`stacker-crane.md` §7.1).
* **Tests**:
  * GameTests (`gametest.WarehouseStationGameTests`): `stationregistration` (also: `POWERED` after a placement without player), `inputacceptsitems`, `inputhopperfeed`, `outputextractonly`, `stationdropsbuffer`, `stationpersistence` (also: bounded loading of crafted counts and entry floods), `outputclipboardfilter` (review: no filter item taken, "up to" board), `stationmembership`, `outputrequestclampedtostock`, `outputrequestrejections` (also: per-output cap), `outputrequestorphancancelled` (review), `stationrequestpersistence`. The M5 release audit's `stationsarenotarmtargets`, which pinned that no Wareworks block was a mechanical arm target, was removed in M12 together with that limitation; the arm tests are in `gametest.MechanicalArmGameTests` (§3.2.2).
  * JUnit: `RequestQueueTest`.
  * Not covered: a real belt line (it needs a powered belt); the belt path is tested through `DirectBeltInputBehaviour#handleInsertion`, the call belts, tunnels and ejectors make. Real hoppers cover insertion into the input and extraction from the output.

#### 3.2.2 Mechanical arm interaction points (M12)

> Decision and reasons: **ADR-025**.

Classes: `registry.WareworksArmInteractionPoints` (the four registered types), `content.station.StationArmPointType`,
`WarehouseInputArmPoint`, `DeliveryStationArmPoint`.

* **Why an item capability is not enough.** A Create mechanical arm only targets a block that a registered
  `ArmInteractionPointType` accepts: `ArmInteractionPointType#getPrimaryType` walks the registered types and returns
  null when none accepts the block, both selection paths (the arm item and `ArmInteractionPointHandler`) go through it,
  and Create ships no fallback type for "anything with an item capability". Up to M11 no Wareworks block was an arm
  target (M5 release audit); a player had to put a funnel between the arm and the station.
* **One type per station block**, each named after its block:

  | Type id | Block | Point class | The arm may |
  |---|---|---|---|
  | `wareworks:warehouse_input` | warehouse input | `WarehouseInputArmPoint` | only put items in |
  | `wareworks:warehouse_output` | warehouse output | `DeliveryStationArmPoint` | only take items out |
  | `wareworks:warehouse_terminal` | warehouse terminal | `DeliveryStationArmPoint` | only take items out |
  | `wareworks:warehouse_production` | warehouse production station | `DeliveryStationArmPoint` | only take items out |

  * `StationArmPointType#canCreatePoint` accepts exactly its station block, in every block state (facing, the output's
    `powered`, the terminal's `display`), and nothing else; it reads only the block state, never the level. Default
    priority: no Create type accepts a Wareworks block, so there is nothing to win against.
  * **Separate ids even where stations behave alike.** An arm saves the type id with every point, so the arm behaviour
    of one station can change later (a subclass of `DeliveryStationArmPoint`, say) without breaking arms that are
    already saved in worlds.
* **Registration.** A NeoForge `DeferredRegister` for Create's registry key
  `CreateRegistries.ARM_INTERACTION_POINT_TYPE` in namespace `wareworks`, registered on the mod bus from the `Wareworks`
  constructor right after `WareworksMenuTypes` (the types reference the blocks). This works like a vanilla registry because Create creates the
  registry early enough: `CreateBuiltInRegistries` builds it with NeoForge's `RegistryBuilder` (`sync(true)`, a bake
  callback) and adds it to `BuiltInRegistries.REGISTRY` from a mixin into `BuiltInRegistries`' static initialiser, and
  NeoForge posts a `RegisterEvent` for every key of that registry of registries. When the registry freezes, its bake
  callback `ArmInteractionPointType.init` rebuilds the priority-sorted type list from the whole registry, ours included.
  The registry is synced, so a client joining a dedicated server knows the same ids.
* **Deposit only at the input.** `WarehouseInputArmPoint` extends Create's `DepositOnlyArmInteractionPoint`, the base
  of Create's own funnel point: `cycleMode` does nothing, `extract` returns empty and `getSlotCount` is 0. A right-click with
  the arm item selects the input for "Deposit items to" and every further click keeps that.
* **Take only at the output, terminal and production station.** `DeliveryStationArmPoint` is "take" from construction,
  its `cycleMode` does nothing, and `insert` returns the offered stack itself without asking the capability. The
  capability view refuses insertion anyway; the override makes the refusal the point's own rule rather than an accident
  of the view behind it.
* **The fixed mode is enforced on load too.** A point reaches the server only as NBT: from `ArmPlacementPacket`, whose
  list the client builds and the server stores unchecked, from a world save and from a schematic. Create's `deserialize`
  reads `Mode` from that tag, so both point classes override `deserialize` and set their mode after it. Without that, a
  tag saying "deposit" for an output would load a point the arm tries to *deliver* to: nothing would go in, and nothing
  would ever be taken out either. The input forces "deposit" the same way.
* **Same item path as a funnel.** Create's default point transfer asks `Capabilities.ItemHandler.BLOCK` with
  `Direction.UP`, which reaches the views of `WareworksCapabilities`: insert-only on the input, extract-only on the
  other three. The buffer rules therefore apply unchanged: an arm keeps what does not fit, and an arm emptying a
  production station moves the order on to "waiting for the result" exactly like a funnel would (§3.5.3).
* **The arm reaches for the centre of the top face** of all four (`StationArmPointType#topFaceCentre`, the formula of
  Create's `TopFaceArmInteractionPoint`). Every station is a full block, and the aisle face is where the crane reaches
  in, so an arm standing beside or behind a rack aims at the top instead. Create does not check whether that face is
  free (`ArmInteractionPoint#getHandler` asks the capability, not the block above), so a station under the next rack
  level still works; only the claw's animation then reaches into the block above.
* **The output's filter slot lets the arm item through.** The request filter slot (a Create value box) sits in the
  centre of the output's top, back and side faces, exactly where a player clicks to select the output. Create's
  `ValueSettingsInputHandler` took that right-click and cancelled it before `ArmInteractionPointHandler` saw it, and
  Create's `FilteringBehaviour#canShortInteract` refuses the arm item as a filter, so the click did nothing: no
  selection, no message (found by the `arm` visual scenario). `RequestFilterBehaviour#bypassesInput` returns true for
  the Mechanical Arm item, so the click selects the output wherever it lands; with any other item or an empty hand the
  slot works as before, and holding an arm only means the amount board cannot be opened with the arm in hand. While the
  arm item hovers the slot, Create would still draw its value box and the hint "Click with item to set / Click and hold
  for amount", although the click selects the output; `RequestFilterBehaviour#mayInteract` is therefore false for a
  player holding the arm item, and Create's `FilteringRenderer` skips the slot entirely (found by the same scenario).
* **No arm point for the warehouse interface, controller, stacker crane dock or rail.** The interface has no inventory
  of its own: the chest behind it is warehouse storage, which the crane fills and empties, and Create arms do not
  target a plain chest either (a funnel on the chest is the normal Create way to reach one). The controller, dock and
  rail hold no items a player's machine should take or give.
* **Items still never teleport.** An arm is the player's own Create machine, moving items between blocks within its
  reach through the same capability views funnels, chutes and hoppers use. Inside the warehouse the crane remains the
  only thing that moves items.
* **Client safety.** The type and point classes are common code without client imports: Create creates points on the
  client while a player selects targets with the arm item, and again on the server from the placement packet and from
  saves.
* **Tests** (`gametest.MechanicalArmGameTests`, `empty_7x5x7`):
  * `stationarmpointtypes`: each station is registered as `wareworks:<block>`, is in
    `ArmInteractionPointType.SORTED_TYPES_VIEW` (so the deferred registration really reached Create's sorted list),
    every block state resolves to its own type, the arm item selects the station instead of being placed against it,
    and no type accepts another station. The interface, controller, dock and rail resolve to no type; a composter still
    resolves to `create:composter`, so a passing test cannot just mean that the lookup broke.
  * `stationarmpointsemantics`: the points without a running arm, through the API an arm calls. The input selects as
    "deposit" and stays so, a simulated insert changes nothing, a real one lands in the buffer, it offers no slot and
    extracts nothing. Output, terminal and production station select as "take" and stay so (also after the two clicks
    that make a depot a destination), an insert returns the same stack instance and leaves the buffer alone, and a
    simulated and a real extraction take the buffered gold. Every point survives a save and load with its type id,
    position and mode; a tag rewritten to the opposite mode still loads with the forced mode; a tag whose type names
    the input does not load on an output.
  * `mechanicalarmsfeedandemptystations`: three real arms at 256 RPM on one cogwheel and creative motor. One moves 32
    iron from a depot into a warehouse input, one 20 gold from a warehouse output onto a depot, one 12 diamonds from a
    warehouse terminal onto a depot. The terminal arm's saved point list names the terminal with mode "deposit"; the
    arm still empties the terminal and saves the resolved point as "take". The item census holds on every tick, each
    arm is seen holding items on at least one of those ticks (so the census really counted a loaded claw), every claw
    ends empty, and nothing moves during 60 settle ticks.
  * `ProductionGameTests.productioningredientstakenbymechanicalarm` (§3.5.5): a real arm empties the production
    station in the full production loop, with the census on every tick and the claw seen holding the logs.
  * Both real-arm tests fail when `ItemCensus` leaves the claw out: the census of the tick in which the arms hold the
    items then misses exactly those items.
  * `gametest.MechanicalArmFixture` builds real arms the way a player does: a point per selected block with
    `ArmInteractionPoint.create` and one `cycleMode` per right-click, serialized with `ArmPlacementPacket`'s own
    constructor. The server half of that packet needs a connected player, so the list is loaded into the arm's saved
    data instead, the way a schematicannon places a configured arm; the arm resolves it with
    `ArmInteractionPoint.deserialize` on its next tick either way. `ItemCensus` counts what an arm holds, read from the
    arm's save data (an arm has no item capability and no accessor for its claw).
  * `outputfilterslotletsarmselectionthrough`: Create's `ValueSettingsInputHandler` is handed a right-click on the
    centre of the output's top face, as the game posts it on the server: with an iron ingot the filter slot takes the
    click, with the Mechanical Arm item the event stays uncancelled and the filter is unchanged. Fails without
    `RequestFilterBehaviour#bypassesInput`.
  * `outputfilterslotoffersnointeractiontothearm`: the request filter's `mayInteract` is false for a player holding
    the Mechanical Arm item and true with an iron ingot or an empty hand, so the renderer shows no hint for the arm.
    Fails without `RequestFilterBehaviour#mayInteract`.
  * The client side runs in the visual scenario `arm` (`./gradlew runVisualTest -Pwareworks.visualTest=arm`): real
    right- and left-clicks with the arm item on every station, the depot comparison, the interface, controller, dock and
    rail, the selection outline colours and action bar messages, the output's filter slot showing no value box and no
    hint under the arm item (and both under an empty hand), real arm placement through `ArmPlacementPacket`, the claws
    frozen at the stations with the claw's aim measured through the arm renderer's own transforms (the end pose's axis
    through the top face centre, the frozen frame's claw tip nearest to the top face), requests and a crafter loop
    ordered in the terminal screen with mouse input (`dev.ScreenInput`: the handler methods GLFW's callbacks call), a
    save, quit and rejoin, and the tooltips and selection message in German.
  * The dedicated-server side runs in the visual scenario `arm-dedicated` (`manual-test-checklist.md` section P, check
    92): a client that joins a running `runServer` over TCP (the dev player must be an operator there) builds the aisle
    of the `arm` scenario with commands, selects all four stations, two depots, a basin and a Mechanical Crafter with
    real clicks, and places four arms, so the synced arm point type registry, `ArmPlacementPacket`, Create's value
    settings packet and the Wareworks screen payloads really cross the network. It writes the production pattern in the
    production station's screen and orders planks in the terminal screen by mouse input, feeds a depot and requests at
    the output (filter slot click, redstone block) by hand. It reads the server's block entity data back through
    `/data get block`: each arm's saved `InteractionPoints` (type id, mode, position; arm A's list only reaches the
    order "inputs, then outputs" once the server has resolved both points of its packet), the saved pattern, and the
    item counts in depots, claws, station buffers, crafter, basin and chests after each arm has worked. It then stops
    the server with `/stop`, probes the server's port until it has been started again and joins, checks the saved
    points and the client arms' resolved point classes, and moves a second batch through all four arms. The server
    restart is started outside the client; the scenario's class comment describes the setup.

### 3.3 Warehouse Controller (`content.controller`)
Owns the logical warehouse state of one aisle:
* **Geometry** read from the dock (`AisleGeometry`) plus the aisle letter.
* **Locations**: membership list `List<LocationRecord>` with `(x, y, side, kind)` and the last snapshot. Persisted.
* **StockIndex**: aggregate item key → location → count. Derived from snapshots, rebuilt on load.
* **ReservationLedger**: persisted.
* **Request queue**: retrieval requests (output pos, item, remaining amount). Persisted.
* **Job dispatch**: plans jobs and assigns them to the crane. It never moves items.

#### 3.3.1 Implementation (M2)
Classes:
* `content.controller`: `WarehouseControllerBlock` / `WarehouseControllerBlockEntity`, `AisleLetterBehaviour`, `WarehouseRegistry`, `WarehouseMember` / `StorageMember`, `ControllerStatus`, `ControllerGoggleSummary`, `AisleAssignment`, and the package-private `ControllerPersistence`.
* `core.warehouse`: `LocationKind`, `LocationRecord`, `RackProbe`, `AisleMembership`, `MembershipChanges`.
* `core.inventory.StockView`.

Registered as `WareworksBlocks.WAREHOUSE_CONTROLLER` / `WareworksBlockEntityTypes.WAREHOUSE_CONTROLLER`.

* **Block**:
  * `HorizontalDirectionalBlock` + `IBE` + `IWrenchable`. `FACING` points at the dock and comes from the player's horizontal look direction; the wrench on the top or bottom face rotates clockwise.
  * Soft metal properties, netherite sound, pickaxe only, `create:non_movable` + `c:relocation_not_supported`, drops itself.
  * `neighborChanged` from the block in front (dock placed, broken or rotated) requests a re-link.
  * The block entity ticks through Create's default `SmartBlockEntityTicker`.
* **Model**: hand-made `models/block/warehouse_controller/block.json`, authored with the dock side facing north.
  * Brass casing body.
  * On the side that faces the player, a brass-framed recessed display (`create:block/redstone_requester` screen in a `create:block/railway_casing` recess) and a thin status lamp strip (`create:block/stock_link`).
  * The boundary is fully covered except the closed recess. Checked by script: element bounds, UVs, every texture exists.
  * **M4 polish:** the display plane used to sit 0.01 px in front of the body's south face, a z-fighting risk at distance. That face was only visible behind the display, so it is removed and the display plane sits at z = 13. The item model (the block model) has its own `display.gui` rotation (30, 45, 0), so the inventory icon shows the display side (visual scenario `blocks`).
* **Aisle letter**: `AisleLetterBehaviour extends ScrollValueBehaviour`.
  * Range 0..25. `withFormatter` shows `A`..`Z` in the value box, and the `createBoard` override shows letters on the hold-to-edit board, with a milestone every 5 letters. Own clipboard key `AisleLetter`.
  * Active on every face except the one touching the dock and the bottom.
  * Default `A` by a field write in `addBehaviours` (the field has no initializer).
  * The callback re-registers the layout with the new letter at once, so addresses change immediately.
* **Link and status (`ControllerStatus`)**:
  * `NO_DOCK`: no dock at `pos + FACING`.
  * `DOCK_MISALIGNED`: a dock stands at `pos + FACING`, but faces another direction, so it belongs to no controller on this side. The state is the same as `NO_DOCK` (no aisle, nothing registered, no records, no requests), but it has its own goggle line. **M5 release audit:** both cases shared the message "No stacker crane in front", which a player reads while looking straight at a stacker crane; a dock placed or wrenched the wrong way round is the most likely build mistake, because the controller takes its facing from the player's look direction.
  * `NO_RAILS`: the dock has length 0. The rack positions at position 0 still work.
  * `READY`.

  A re-link costs one block entity lookup. It runs on the next tick after a hint and at the latest every `geometryRefreshTicks`. Hints: a block update in front, a dock geometry change, the dock's load, rotation or removal (`StackerCraneBlockEntity#notifyControllerBehind`), the controller's own rotation, and load. While the dock position is not loaded, the known layout is kept.
  * **Dock link (M2 review fix, owner-aware):** the controller links the dock with `linkController(controllerPos)` and unlinks it with `unlinkController(controllerPos)`, which is ignored unless that controller is the dock's current owner. Before, a bare flag let a controller that lost the dock (dock rotated between two controllers) clear the new controller's link, depending on ticker order. A controller whose chunk unloads unregisters its aisle in `invalidate()`, and the only level access on that path is the per-level `WorldAttached` registry map, which catnip backs with a `WeakHashMap` keyed by the level (safe while a level unloads, §4). It does **not** probe the world there; the dock re-validates its owner at its geometry refresh cadence (`validateControllerLink`: loaded, a controller, `isLinkedTo(dock)`; one block entity lookup) and clears a lost link.
* **Layout changes**: the `AisleLayout` with its letter is registered in `WarehouseRegistry`.
  * A letter change only re-registers.
  * A geometry change marks all positions dirty; unloaded records are kept.
  * A changed dock position or aisle direction clears records, stock counts, pending snapshots, shared inventories and requests, then rebuilds the list with a full scan (unloaded positions are not kept), so every member joins again and is snapshotted. **M2 review fix:** before, only the list was invalidated, and a record of the same kind at the same aisle-local position (for example after wrenching the controller from one dock straight to a dock on an adjacent face) kept the old aisle's per-location counts without a snapshot.
  * `NO_DOCK` unregisters the aisle and clears the same state. A new dock rebuilds it with a full scan; joining storage locations are snapshotted through the bounded queue (§5). There is no grace period for a transient `NO_DOCK` (a dock rotated and rotated back): the throttled rebuild bounds the cost, and requests of the old layout could not be served anyway.
* **Tick (server)**: re-link if due, reconcile dirty membership, read at most `maxSnapshotsPerTick` queued storage locations (§5), and every `snapshotIntervalTicks` take one round-robin snapshot of a storage location that counts its own inventory. From M3 on the tick ends with a dispatch attempt every `dispatchIntervalTicks` (§7.5). Nothing else.
* **API for M3/M4**: `layout()`, `status()`, `aisleLetter()`, `isLinkedTo(BlockPos)`, `locations()`, `storageLocations()`, `inputStations()`, `outputStations()`, `misalignedCount()`, `stockIndex()` (read-only `StockView<ItemKey, RackPosition>`), `countOf(ItemKey)`, `isSnapshotPending(RackPosition)`, `pendingSnapshotCount()`, `sharedInventoryOf(RackPosition)` (the location whose index entry counts the inventory; M3 plans capacity and extraction there), `locationsSharingInventory(RackPosition)`, `addressOf(BlockPos)`, `locationAt(BlockPos)`, `worldPosOf(RackPosition)`, `refreshLocation(RackPosition)`, `requestRelink()`.
  * The location id is the aisle-local `RackPosition`: stable across restarts and independent of world coordinates.
  * Requests (stations task, §7.2): `request(outputPos, ItemKey, amount)` → `RequestResult`, `availableStock(ItemKey)`, `reservedStock(ItemKey)` (M3: the ledger formula, §7.5), `deliveredFor(BlockPos)` (M3), `openRequests()`, `openRequestCount()`, `oldestOpenRequest()`, `requestsFor(BlockPos)`, `requestedFor(BlockPos)`, `deliverRequest(UUID, amount)`, `cancelRequest(UUID)`.
* **Goggles** (observer-driven through `GoggleObservers`): "Warehouse Controller:", "Aisle X", the status, "Aisle: L long, mast H high", "Storage locations: N", "Inputs: N, outputs: M", "Misaligned blocks: N" (only if > 0), "Item types: N", "Items stored: N", "Open requests: N"; from M3 on also the linked crane's status and job and the last planning result (§7.5).
  * Synced as a `ControllerGoggleSummary`: a status name and numbers only, so it has a fixed size.
  * Recomputed in O(1) on observation and sent only when changed, at most once per `GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS` (20, `SyncThrottle`).
* **Persistence** (`ControllerPersistence`): `Layout {Facing, Length, Height}`, `Locations [{X, Y, Side, Kind, Stock [{Item: ItemKey, Count: long}]}]`, `Misaligned` (int array of x, y, side ordinal), `Requests [{Id: UUID, Item: ItemKey, Requested, Remaining, Destination: int[3] offset from the controller}]` in queue order.
  * Request destinations are relative to the controller, so they survive moving a structure without rotation. Requests are restored only together with the saved layout (like the records); invalid entries (no id, no item, bad destination, no remaining amount, repeated id) are skipped and `Remaining` is clamped to `Requested`.
  * Items are count-less `ItemKey`s next to `long` counts, so `ItemStack.save` never sees a count.
  * Writing skips a record that fails. Reading skips invalid entries, merges duplicate stock entries (saturating) and drops non-positive counts.
  * A saved layout whose facing differs from the block state (e.g. a rotated structure) is dropped together with its records.
  * Loading rebuilds the stock index from the counts (`StockIndex#restore`) and schedules a full verification scan. Every restored storage location is also queued for a **background snapshot** (M2 review fix): restored counts are unverified, because inventories may have changed while the controller was unloaded, and a structure mirrored across the aisle axis keeps the facing but swaps the rack sides. Background snapshots run only when no urgent snapshot waits, within `maxSnapshotsPerTick`. `onLoad()` registers the saved layout at once, so addresses resolve before the first tick and in chunks that do not tick.
  * Client packets carry only the goggle summary.
* **Tests**:
  * GameTests (`gametest.WarehouseControllerGameTests`): `controllerfullaisle` (also: content hint queued and read, silent change found by the round robin), `aislefulllayout` (dock with rails, controller, interfaces, one input and one output; geometry, member kinds, addresses of every kind, stock totals without station buffers, controller goggle summary), `controllersharedinventory` (review: double chest and vault behind two interfaces each), `controllerdockswitchrebuildsstock` (review), `controllerdocklinkowner` (review), `controllermembershipchanges`, `controllerdockstatus`, `controllerpersistence` (also: background verification queued), `controllerregistrationandremoval`, `controllergogglesummary`.
  * JUnit: `AisleMembershipTest`, `StockIndexTest` (restore, read-only view), `SnapshotQueueTest`, `SharedInventoriesTest`.

### 3.4 Warehouse Terminal (`content.station`, M6)

> Status: **binding design** since M6 (the post-MVP "Request Terminal"). Decision and reasons: ADR-018.

The player-facing request station of an aisle: a screen instead of a filter slot and a redstone pulse.

* **It is an output-style member of an aisle.** It stands at a rack position with its **intake port** towards the aisle
  (`FACING == side.getOpposite()`, the rule input and output follow) and owns an **extract-only buffer**, so the crane
  physically delivers into it. Nothing is teleported: a request creates a normal `RETRIEVE` job that the crane serves.
* **Since M10 it has two directions, not one** (§3.4.3, ADR-022): the intake port the crane reaches through, which the
  controller derives from the aisle, and the **screen** the player reads and empties, which the player chooses at
  placement and turns with the wrench. The M6 terminal had only the first, so its screen pointed into the aisle — the
  one place a player cannot stand and the place the crane's arm needs.
* **Its automation surface is the warehouse output's**: the item capability is extract-only for every side, so funnels,
  chutes and hoppers pull delivered items out and nothing can be pushed in. Since M12 a Create mechanical arm takes them
  out as well, through the take-only arm interaction point type `wareworks:warehouse_terminal` (§3.2.2). Player access
  comes through the screen.
* **Requests go through the existing machinery.** `WarehouseControllerBlockEntity#request` with the terminal's position
  as destination, the same `RequestQueue`, the same `ReservationLedger`, the same clamping to `availableStock` and the
  same rejection reasons. A terminal request is **indistinguishable downstream** from a redstone request: the planner,
  the reroute rules, the crane's transfer contexts and the persistence formats see only `LocationKind.OUTPUT`.

#### 3.4.1 Implementation (M6, terminal block and server API)
Classes: `content.station.WarehouseTerminalBlock` / `WarehouseTerminalBlockEntity`, `TerminalStockEntry`,
`TerminalStatus`, and the new shared base `WarehouseDeliveryStationBlockEntity`. Registered as
`WareworksBlocks.WAREHOUSE_TERMINAL` / `WareworksBlockEntityTypes.WAREHOUSE_TERMINAL`; the capability is listed in
`WareworksCapabilities`.

* **Shared base (refactor, no behaviour change for the output).** `WarehouseDeliveryStationBlockEntity extends
  WarehouseStationBlockEntity` now owns what both delivery stations need: the extract-only view over the station buffer,
  the crane's `insert(ItemStack, boolean)`, `locationKind() == OUTPUT`, the last rejection (`LastRejection`, same NBT key
  as before) and the request lines of the goggle tooltip. `WarehouseOutputBlockEntity` keeps only its filter slot and the
  redstone path, which is untouched; `WarehouseTerminalBlockEntity` adds only the screen API. `TransferContexts.resolve`
  therefore resolves an `OUTPUT` location to the base class, so the crane delivers into either station with the same
  code.
* **Block**: `WarehouseStationBlock` like input and output (no ticker, no redstone conduction, `create:non_movable` +
  `c:relocation_not_supported`, pickaxe only, drops itself, buffer dropped in `destroy()` and emptied by `Clearable`).
  It has **no** `POWERED` state and no `neighborChanged` handling: a terminal is operated through its screen, never by
  redstone. Its placement, its second block state property and its wrench behaviour differ from the other stations and
  are described in §3.4.3.
* **Server API for the screen** (the GUI itself is the next task; all of it is called on demand, never per tick):
  * `stockSnapshot()` → `List<TerminalStockEntry>` (item key, total, available; `reserved()` is the difference), ordered
    by `TerminalStockEntry.ORDER` and cut off at `maxTerminalStockEntries`.
    * **Order** (M6 review): the largest **stored** amount first, then the most available, then the item id, then the
      key's component hash — language independent and the same for the same warehouse. The stored amount leads because
      this order decides the **cut**, not the display (the screen sorts what it shows itself): `available` moves as soon
      as anything is promised to a request, so leading with it made item types drop out of the reported window and back
      in while the crane worked. The last tie-break is the hash rather than the key's text, which would render the whole
      component patch on every comparison.
    * **The cut is not a removal.** `holdsInStock(key)` answers whether the index still holds an item type that is not
      in the snapshot, and the menu asks it before it tells a screen that one is gone (§3.4.2).
    * **Cost:** one pass over the stock index's item types plus one over the open requests, whose promised amounts are
      collected once (`WarehouseControllerBlockEntity#remainingRequestedByKey`, from `RequestQueue#remainingByKey`)
      instead of scanning the queue again per key; each item type then costs two map lookups. `maxTerminalStockEntries`
      bounds the **list**, not that pass — which is why it is documented as a payload bound in §9.
  * `bufferedItems()` (inherited) — what already arrived here.
  * `status()` → `TerminalStatus` (controller link status, aisle letter, item types, items stored, open requests of the
    aisle and of this terminal, delivered so far, and the crane's bounded `CraneGoggleInfo`). `TerminalStatus.NONE`
    without an aisle.
  * `requestFromTerminal(Player, ItemKey, int)` → `RequestResult`, validated in this order: server side and not removed
    (`NO_CONTROLLER`); the player may use the block (`NO_CONTROLLER`'s sibling `OUT_OF_REACH`, checked with vanilla's
    own `Container#stillValidBlockEntity`, i.e. block interaction range + 4); a positive amount (`INVALID_AMOUNT`),
    then clamped to `maxTerminalRequestAmount`; an aisle with a loaded controller where this terminal is an aligned
    output-style member (`NO_CONTROLLER`, which also covers a misaligned terminal and one outside every aisle); and the
    item must be held by **the server's own stock index** (`NOT_IN_STOCK`). The controller then clamps to
    `availableStock` and applies the queue caps (`NOT_IN_STOCK`, `OUTPUT_FULL`, `QUEUE_FULL`).
  * **Never trust a client-sent stack:** a request is only ever *matched* against the controller's stock index
    (`countOf(key) > 0`), never taken as a description of what exists. `ItemKey` compares item **and** components, so an
    accepted key is value-identical to the indexed one.
  * New rejection reasons `OUT_OF_REACH` and `INVALID_AMOUNT` exist only for terminals: a redstone request has no player
    and derives its amount from the filter slot. They are the two refusals about the **asking player** rather than about
    the station, so (M6 review) they are answered through the `RequestResult` only and are *not* stored as
    `lastRejection()`: that field is saved and shown to every goggle wearer, and "you are too far away from the
    terminal" is not a statement about the terminal. The reasons a goggle reader can act on (`NO_CONTROLLER`,
    `NOT_IN_STOCK`, `OUTPUT_FULL`, `QUEUE_FULL`) are remembered as before.
* **Request size.** Unlike an output (at most one stack per pulse, because the amount comes from a Create filter count),
  a terminal request may ask for up to `maxTerminalRequestAmount` (default 1024) items. The queue and the planner already
  handle a remaining amount above one stack by planning successive `RETRIEVE` jobs; only the entry point differs.
* **Goggles**: "Warehouse Terminal:", the aisle assignment (with the hint "Turn the screen away from the aisle with the
  wrench" when misaligned, M10), the pending request with the items delivered so far or "No pending request", the last
  rejection, then the buffer — the same bounded `StationGoggleSummary` the output syncs.
* **Model** (M10, §3.4.3): hand-made models in `models/block/warehouse_terminal/`, all authored on the **north** face.
  A core column (`block.json`) plus three interchangeable 3 px shells — `shell_display` (a brass frame around a
  recessed screen, with the take-out tray under it), `shell_intake` (the crane's arm port) and `shell_plain` (brass
  bands over a 1 px recessed ribbed `industrial_iron_block` panel; **no cog** — one was tried and rejected, see the
  "Look" bullet of §3.4.3) — which a multipart blockstate puts on the four horizontal faces. Every opening is centred
  on the 16 px block face, so all four faces of the block read alike (M10 review fix: the plain panel used to sit
  1.5 px off-centre).
  Textures are Create's (`brass_casing`, `railway_casing`, `chute_hole`, `industrial_iron_block`) plus the project's **only** own
  texture, `wareworks:block/terminal_screen`, generated by `scripts/gen_textures.py` (**ADR-023**): Create ships
  nothing that reads as a terminal screen, and the crop of `stock_ticker` the block used until now is what made it look
  like a machine port rather than a terminal. The arm-port clearance is asserted by
  `CraneModelLayoutTest#armPassesThroughTheMemberPorts` against `shell_intake` like the other members, and
  `#terminalShellsTileTheBlockAroundTheArmPort` asserts that the shells tile the block around every opening they
  declare, so neither the screen, the tray nor a future part can grow into the arm's path or leave a hole.
  `#everyTerminalOpeningIsCentredOnTheBlockFace` pins the face centring above, and
  `#theTerminalItemModelIsTheSameShellsTurnedAroundTheBlock` re-derives the hand-baked `item.json` from those same
  shells, so the held item can no longer drift from the placed block (both M10 review fixes).
  * The M6 model was a single `block.json` with the screen squeezed into the lower half of the aisle face under the arm
    port and the brass pull port on the back. It is what play-testing rejected: the screen was tiny, and it was on the
    face nobody can stand at. M10 first split the two directions (ADR-022) and then gave the screen side its own
    geometry and texture (§3.4.3, "Look").
* **Deviation from the "one kind per block" reading of §1**: the terminal reports `LocationKind.OUTPUT` instead of a new
  kind. Reason and consequence in ADR-018; the visible consequence is that the controller's goggle line "Inputs: N,
  outputs: M" and `outputStations()` count terminals among the outputs, which is also what they are for planning.
* **Tests**: `gametest.WarehouseTerminalGameTests` (`terminalregistration`, `terminalextractonly`,
  `terminaldropsbuffer`, `terminalpersistence`, `terminalmembership`, `terminalrequestendtoend`,
  `terminalrequestclampedtostock`, `terminalrequestrejections`, and the M10 additions
  `terminalintakefollowstheaisle`, `terminalmisalignedscreen`, `terminalwrenchduringdelivery`,
  `terminaldeliveryfrombothaislesides`, `terminalworldcompatibility` and the M10 review fix
  `terminalportowneronasharedrackplane`); `CraneModelLayoutTest` covers the models.
  `terminalrequestendtoend`, `terminalwrenchduringdelivery` and `terminaldeliveryfrombothaislesides` assert the item
  conservation invariant on every tick of the job with `gametest.ItemCensus`.

#### 3.4.2 Implementation (M6, the terminal screen)

Classes: `content.station.WarehouseTerminalMenu`, `TerminalMenuLayout`, `TerminalScreenStatus`;
`client.gui.WarehouseTerminalScreen` and `TerminalScreenUpdates`; `network.WareworksNetwork` with
`TerminalStockPayload`, `TerminalStatusPayload`, `TerminalRequestPayload` and `TerminalResultPayload`; the pure list,
search, sort, paging, amount and delta logic in `core.terminal`. Registered as
`WareworksMenuTypes.WAREHOUSE_TERMINAL` (Registrate `.menu(...)`, which registers the screen on the client only).
Networking decision and reasons: **ADR-019**.

* **Opening.** Right-clicking the terminal with an **empty hand** opens the screen
  (`WarehouseTerminalBlock#useItemOn` → `WarehouseTerminalBlockEntity#openScreen`, which is a plain
  `ServerPlayer#openMenu` with the block position and the buffer slot count as extra data). Anything held passes the
  interaction on (`PASS_TO_DEFAULT_BLOCK_INTERACTION`), so the wrench still rotates the block and a future item can
  still be used on it; the terminal registers no behaviours, so unlike the warehouse output it has no value box that
  could swallow the click. Goggles are worn, not held, and are unaffected.
* **Window** (`TerminalMenuLayout`, shared by both sides because `Slot#x`/`Slot#y` are final and a screen can never
  move a slot): one brass-framed panel of 230 × 220 pixels holding the title row, the search row, a 12 × 3 stock grid,
  the terminal's buffer slots with the label "Delivered here", one status line and the player inventory. Vanilla clamps
  the GUI scale so that the scaled screen is at least 320 × 240 pixels (`Window#calculateScale`), so the window fits at
  **every** GUI scale on a 1280 × 720 window.
  * A bigger buffer adds a slot row per twelve slots, and (M6 review) **the stock grid gives those rows back**
    (`gridRows()`, 3 down to 1): the grid scrolls anyway, while a buffer slot that is not drawn could not be reached by
    hand. Every buffer size `terminalBufferSlots` allows (1–27) therefore still fits — a 27-slot buffer used to make the
    window 256 pixels tall. GameTest `terminalmenuslots` asserts the width and the height for **every** value of that
    range, not only for the configured one. Only a buffer grown far past the range by save data can still exceed the
    floor and then needs a smaller GUI scale.
  * **Slot count** (M6 review): the number of buffer slots is the one the server writes into the menu's extra data, on
    both sides, and the handler behind the slots is wrapped in a `content.item.FixedSlotsItemHandler` of exactly that
    size. A terminal's own buffer may have another one — grown past the config by save data (`StationBuffer`), or shrunk
    by `/data merge` while a screen is open — and a client menu with fewer slots than the server's throws an
    `IndexOutOfBoundsException` out of `AbstractContainerMenu#initializeContents` while it reads the first content
    packet, i.e. disconnects the player. GameTest `terminalmenugrownbuffer` pins both directions.
* **List.** The grid shows `12 × gridRows()` item types at a time (24 with the default buffer since M11, see the window
  bullet), scrolled with the mouse wheel over the grid (a slim scrollbar in the right margin shows the position). Every
  cell draws the item with Create's `GuiGameElement` and its **total** amount, compacted to at most four characters
  (`core.terminal.CountFormat`: `999`, `1.2K`, `12K`, `1.5M`) and drawn at three quarters size, because four
  normal-size characters are wider than a cell and would run into the neighbour. The exact numbers are in the cell's
  tooltip ("In stock", "Available", "Promised to other requests").
* **Producible items are offered at zero stock** (M11, ADR-024, §3.5.2). An item type a production station of the aisle
  can make is in the list even when the warehouse holds none of it: its cell shows a blue **`+`** instead of a count and
  its tooltip says "Can be produced here", plus "Can be made now: N" while the ingredients for a run are there.
  * **They sort behind everything that is really in stock, in both orders** (`core.terminal.TerminalSort`). An offer is
    not inventory, and under "by name" it would otherwise land between two items a player can have right now. It is the
    first sort key rather than a side effect of the amounts, because only the amount order would have produced it by
    itself.
  * **"How many" is a server number** (`WarehouseControllerBlockEntity#producibleAmounts`, one pass over the aisle's
    patterns): the screen knows neither the patterns nor what their ingredients are already promised to, so it may
    never derive it. It travels in the stock payload next to `available` and is what a **ctrl-click** asks for
    (`TerminalAmounts`: `available + producible`, clamped to `maxTerminalRequestAmount`). A plain click and a
    shift-click are clamped to the same total, so a player can order a stack of something that is only producible.
  * An item whose pattern exists but whose ingredients are missing is still *offered* (the `+` and the first tooltip
    line) and reports 0 as the amount — "can be produced here" and "can be made now" are different statements, and the
    refusal a player then gets names the missing item (§3.5.2).
* **Search** (`core.terminal.TerminalSearch`): the box is focused when the screen opens, so a player can type
  immediately. A blank query keeps everything; the query is split at whitespace and every token must match (AND); a
  token matches the display name case-insensitively; a token starting with `@` matches the **mod id** instead
  (`@create`), which is cheap because the id is already part of every line.
* **Sorting and filter**: one button cycles the order (`TerminalSort`: most available first, or by name) and one toggles
  "only what is available". Both, and the search text, are remembered while the client runs, so reopening a terminal
  shows the same view.
* **Requesting** (`core.terminal.TerminalAmounts`): the amount input is a Create `ScrollInput` (scroll to modify, shift
  scrolls faster) limited to `maxTerminalRequestAmount`; **click** requests that amount, **shift-click** one stack and
  **ctrl-click** everything that is possible — available plus producible since M11 (bullet above). Every amount is
  clamped to that total, unless nothing at all is orderable — then the raw amount is sent so the *server* answers with
  the real reason instead of the screen inventing one. The answer appears in the status line for four seconds ("Requested Andesite x64" in green, or
  "Request refused: …" in red, with the same reasons a warehouse output shows in its goggles), and while requests are
  open the line shows "Waiting for N, delivered M".
* **Width budget of the status row** (M7, binding): the status line is the only text of the window whose width
  nothing bounds — it carries an item name, up to two amounts and a translation — so it gets **the whole row**
  (`WIDTH − 2 × MARGIN` = 216 pixels), and "Open requests: N" moved one row down, right-aligned beside the player
  inventory's title, which is short and leaves it ~130 pixels. Before this, both shared the status row and the M7
  merged answer ("Requested Andesite x1, waiting for 74", ~215 px against a ~134 px budget) was drawn straight through
  the right-aligned count: two texts of overlapping glyphs, for the whole four-second feedback window. The pre-M7
  "Waiting for N, delivered M" (~145 px) had the same defect and was simply never caught in a screenshot.
  * Two guards keep it true for **every** language and item name, because no layout number can bound those: an accepted
    answer shortens the **item name** first (`WarehouseTerminalScreen#acceptedLine`), so the amounts a merged answer is
    about always survive, and `fitToRow` then cuts whatever is still too wide with an ellipsis. A cut name stands in
    full in the grid's tooltip.
  * The dev harness asserts the widths instead of trusting a screenshot: `statusTextsFit()` (status line ≤ row, count
    clear of the inventory title) is checked at the request, merged and delivered steps of the `terminal` scenario and
    is logged per shot in `index.txt`.
* **Networking** (ADR-019): the server pushes to the one player whose menu is open, at most every 10 ticks and only
  what changed. The first push after opening carries the whole list (pages of at most 64 entries,
  `reset = true`); later pushes carry only entries whose amounts changed plus item types that left the index (total 0),
  computed by `core.terminal.StockDiff`. The status is a fixed-size record of numbers and enum ordinals
  (`TerminalScreenStatus`) and is sent only when it changed. A warehouse in which nothing moves therefore costs no
  packet at all.
  * **The 10 ticks are game ticks** (M6 review). `broadcastChanges()` is not tick-scoped — vanilla also calls it once
    per container click packet and once from `MenuBase#init` — so counting calls let a client choose how often the
    server built a snapshot. Every push is stamped with `Level#getGameTime()`, which bounds it to one push per tick;
    an accepted request may ask for an early one (`markDirty()`), a refused one may not, because a refusal changes no
    availability. Incoming request payloads are additionally capped at `MAX_REQUESTS_PER_TICK` (8) per menu, and the
    excess is dropped like a crafted payload. The push from inside the super constructor is skipped as well, because
    the player does not have the menu open yet and the payload would arrive before the screen exists.
  * **A cut-off list never deletes rows** (M6 review). "Not in this snapshot" means either "left the index" or "outside
    the reported window" (`maxTerminalStockEntries`), and only the first may be sent as `gone` — the screen deletes such
    a row, so a stocked item would vanish from the grid and could not be requested. `StockDiff#commit` therefore takes a
    predicate (`WarehouseTerminalBlockEntity#holdsInStock`); a key it accepts keeps the amounts last sent for it. The
    screen also says how many item types it was not told about ("+N not shown", next to "Delivered here").
* **Production orders have their own section** (M11, ADR-024): a label and up to
  `TerminalMenuLayout.MAX_ORDER_LINES` (2) lines under the status line, listing the **aisle's** orders newest first
  with the item, the amount and the state ("waiting for ingredients", "delivered to the machine", "waiting for the
  result", "complete", "timed out", "cancelled"). Without this a player would order something producible and then watch
  an empty status line for as long as their machine takes.
  * **The scope is the aisle, not the terminal.** An order is started by a request *here* but runs at a production
    station elsewhere in the aisle, and orders have no per-player owner to scope them by. The terminal keeps the
    **newest** when there are more than fit (the ones just placed); the station's own screen keeps the oldest, which is
    its backlog.
  * **The section is part of the fixed layout, not conditional.** Slot positions are decided when the menu is built, so
    a section that appeared with the first order could not move them. It is paid for by the stock grid — the grid
    scrolls, an order line does not — which is why the grid shows 2 rows instead of 3 with the default buffer, and for
    a buffer large enough to leave no room the **lines are dropped first** (2 → 0, the pre-M11 layout).
  * **A line is drawn in two parts, and the state is the right-aligned one** (a fix from the first screenshots of this
    screen): "Oak Planks x128" on the left, the state on the right, the cancel mark last. Drawn as one sentence, a long
    item name cut off the state — the one thing the section exists to show, and the one thing that changes. The item
    text is trimmed into what is left instead, and the tooltip carries both in full.
  * **Cancelling is one click on the line**, with a red `x` at its end as the affordance and the tooltip hint the
    production station's screen uses. Only an open order can be cancelled; a finished line stays for a while so a
    player can read that their order timed out. An order that ended after ingredients had already been handed over
    reads "… cancelled, not recovered" in gold — on the line itself, not only in a tooltip (§3.5.4).
* **Nothing from the client is trusted.** A request payload only names the menu id; the server resolves it against the
  menu the sending player *really* has open (`WarehouseTerminalMenu#submitRequest`), and that terminal then applies the
  §3.4.1 validation (reach, positive amount, aligned member of a loaded aisle, and the item matched against the
  server's own stock index). A payload for another menu, for a player without a terminal menu or from a player who
  walked away is dropped or refused.
  * **A cancellation is the same rule** (`WarehouseTerminalMenu#submitCancel` →
    `WarehouseTerminalBlockEntity#cancelProductionOrder`): the payload carries nothing but an order id, and the
    terminal re-validates reach, its own aisle and that the id names an **open order of that aisle**, so a crafted id
    cannot reach another warehouse's orders. Cancellations share the request budget (`MAX_REQUESTS_PER_TICK`), because
    they are the same kind of click. One payload type serves both screens (`ProductionCancelPayload`): which orders it
    may reach is decided by the menu the player has open, not by the payload.
* **Closing safely.** The menu is a Create `MenuBase`, so `stillValid` asks the terminal's `canPlayerUse`
  (`IInteractionChecker`): a block that was broken, replaced or unloaded, or a player who walked out of range, closes
  the screen on the next tick without any extra bookkeeping. The client closes the screen itself when its block entity
  is missing.
* **Slots.** The terminal's buffer is shown as real slots: a player can take delivered items out by hand or shift-click
  them into their inventory, but nothing can be put in — the same rule automation sees.
* **Tests**: JUnit `StockListModelTest`, `TerminalSearchTest`, `TerminalAmountsTest`, `CountFormatTest`,
  `StockDiffTest` (the pure list, search, sort, paging, amount and delta logic, including the M11 producible marking:
  an item kept at zero stock, offers sorting behind stock in **both** orders, the producible amount in a delta, and
  "everything possible" being available plus producible); GameTests `gametest.WarehouseTerminalMenuGameTests`
  (`terminalmenuslots`, `terminalmenurequestpath`, `terminalmenuhostilerequests`, `terminalpayloadcodecs`, and the M11
  pair `terminalproducibleitems` — the snapshot's producible entry and amount, an order beyond what the ingredients
  allow being clamped, and a second one refused because they are promised — and `terminalproductionorders`, the order
  list plus every hostile cancellation: no menu, another menu, no player, an unknown id, an out-of-reach player, a
  terminal outside every aisle, and a finished order); the screen itself is checked by the `terminal` scenario of the
  dev harness (`./gradlew runVisualTest -Pwareworks.visualTest=terminal`), which builds a stocked aisle **with a
  production station**, opens the real screen, types into the search box, requests a stack, clicks the same item ten
  more times (one click per tick, as `MAX_REQUESTS_PER_TICK` allows) and shoots the list, the search, the accepted
  request, the **merged** answer, the delivery, the **producible** marking, and the order as it is placed, delivered
  and cancelled — asserting the status text widths at the request, merge and delivery steps and, before the producible
  shot, that the item really is offered at zero stock with an amount behind it (a screenshot cannot tell a marked cell
  from an empty one).

#### 3.4.3 Implementation (M10, the two directions)

> Status: **binding design**. Decision, alternatives and the world-compatibility argument: **ADR-022**.

Classes: `content.station.TerminalDisplaySide` (the relative screen side), `WarehouseTerminalBlock` (the `DISPLAY`
property, placement and wrench), `WarehouseTerminalBlockEntity` (`displaySide()`, `isAlignedWith`, `alignToAisle`),
`content.controller.WarehouseMember#alignToAisle` (the hook), `data.WareworksBlockStateGen#terminalBlockProvider`.

* **`FACING` is the intake port.** It means exactly what it means on a warehouse input or output — *towards the aisle* —
  so nothing downstream changed and a world built before M10 keeps its ports.
* **`display` is the screen, stored relative to the port**: `back`, `left` or `right` (`TerminalDisplaySide`). Three
  relative values instead of a second absolute direction, because "the screen is on the port" is then not a state that
  exists rather than a state every reader has to reject. 4 x 3 = 12 block states.
* **The port follows the aisle.** `WarehouseMember#alignToAisle(layout, side)` is called by the controller's membership
  probe (`WarehouseControllerBlockEntity#probe`) immediately before `isAlignedWith`, so the same probe already
  classifies the corrected state. The terminal moves its port onto the aisle side and **recomputes the relative
  `display` so the screen keeps its world direction**.
  * It writes the block state (`Block.UPDATE_CLIENTS`, no neighbour updates) **only when the port really has to move**,
    and the probe runs only while membership is dirty — never per tick. `IBE.onRemove` returns early for a
    property-only change on the same block, so the buffer is not dropped and the block entity is kept.
  * It does **not** write while the screen occupies the aisle side: the port would need that face. Such a terminal is
    reported misaligned, and the wrench is the fix the goggle hint names.
  * **Exactly one controller may write** (M10 review fix). Two *parallel* aisles two blocks apart share the rack plane
    between them (§4, §8) and want **opposite** ports there, so `alignToAisle` refuses unless this controller is the
    one `WarehouseRegistry#ownsMemberState(level, member, controller)` names: nearest dock, then the lower controller
    position, and **never** alignment — alignment is what the write changes, so an alignment-first tie-break would hand
    ownership to whichever controller wrote last and the two would rewrite the block on alternating ticks for ever. A
    contested terminal keeps the port of its owning aisle and is reported misaligned in the other one, exactly like
    every other member on a shared plane; the goggles still show its address, because a member aligned in *some* aisle
    is assigned (§4). GameTest `terminalportowneronasharedrackplane`.
* **Alignment is the plain station rule:** `facing() == towardsAisle`. ADR-022 called this two-sided
  (`&& displaySide() != towardsAisle`), but that second half can never fail — `TerminalDisplaySide` makes "the screen
  is on the port" unrepresentable — so it was dead code and was removed (M10 review fix). What actually produces the
  screen-on-the-aisle misalignment is `alignToAisle`'s **refusal** to move the port onto the screen's face, which
  leaves the port wrong; that is what the goggles report, with the wrench hint. The hint is therefore precise for the
  case a player can act on and approximate for the two it cannot distinguish (a port not corrected yet because the
  controller's chunk does not tick, or one written by a command) — both transient, and neither reachable on a shared
  plane, where the terminal reads as assigned.
* **Placement:** the **screen** faces the player (`FACING = context.getHorizontalDirection()`, `display = back`). A
  player stands where they want to read the terminal, with the aisle on the far side of the rack. This is the one
  placement rule that differs from `WarehouseStationBlock#placementFacing`, where `FACING` *is* the face the player
  looks at.
* **Wrench:** `getRotatedBlockState` turns the screen one quarter clockwise and skips the quarter that would land on
  the port (`left → right → back → left`); the port never moves. Two deviations from the other stations, both
  deliberate: **every** face rotates (the port face is inside the aisle, so a top-only wrench would often mean breaking
  the block), and it is **never refused while the crane works** — turning the screen cannot affect a delivery in
  flight, unlike the dock's rotation, which would turn the whole aisle (`stacker-crane.md` §2.1). The block entity
  notifies the registry on a screen change as well as on a port change, because the screen decides alignment too.
* **Blockstate: multipart.** Registrate's `horizontalBlockProvider` rotates one model and cannot express two
  directions. The block is a core column plus four 3 px shells that tile the ring around it as a **pinwheel** (each
  shell is 13 px wide, so its own rotations tile the ring without overlapping and each owns its outer faces; full-width
  shells would have shared the block's corner faces and z-fought). One part per shell per state: 12 x 4 + the core =
  **49 parts**, from three models and their rotations. The item model is a hand-made `item.json` with the screen on the
  north face, the face vanilla's default GUI transform shows; because vanilla element rotations only allow 22.5 and 45
  degrees, its four shells are written out at their rotated positions rather than rotated in the file.
* **Look** (M10 look pass, **ADR-023**). The screen side is a brass frame around a **recessed** display (8 x 8 px, one
  pixel deep, at x 4..12, y 6..14, carrying the generated `terminal_screen` texture at two texels per model pixel) with
  a 2 px deep **take-out tray** below it (x 4..12, y 2..5, dark `chute_hole` back wall) and a 1 px brass shelf between
  them. The two plain sides are brass bands top and bottom over a 1 px recessed machine panel of Create's ribbed
  `industrial_iron_block` plate. The intake side is untouched.
  * **The requested cog detail was tried and dropped**, with the evidence in the first run of this pass. Every Create
    texture that contains a cog draws it as a *hole*: `gearbox` and `brass_gearbox` are a black circle inside a casing,
    `brass_encased_cogwheel_side` and `andesite_encased_cogwheel_side` are black bands, and `cogwheel`, `pinion`,
    `bullwheel`, `fan_blades` and `waterwheel_metal` are largely transparent and would need a cutout render type. On a
    flat panel next to the terminal's real arm port, the `gearbox` version read as a **second opening** rather than as
    a cog, so the panel became a plain ribbed plate. Drawing a cog as *geometry* is the only faithful way and it would
    have to stand proud of the face, which ADR-017 rules out for a block that lives in a rack wall.
  * **Deviation from the request** ("a framed screen at roughly x 2..14, y 6..14 and a tray at x 4..12, y 2..6"): the
    display is 8 px wide instead of 12 and the tray 3 px tall instead of 4. Both are forced by the pinwheel, not
    chosen. A shell spans only x 0..13 of its face — the remaining 3 px are the *next* shell, whose inward side carries
    no face — so a recess that reached x = 13 would be a hole; with a 1 px post at x 12..13 a display centred on the
    face (x = 8) can be at most 8 px wide. The face still reads symmetrically: 4 px of frame on one side, 1 px of post
    plus the neighbour's 3 px on the other. A *flush* screen could be 10 px wide, which is exactly what the block had
    before this pass, and that is the trade: a recess that reads as a display is worth two pixels of width.
  * **Full-cube collision and occlusion are unchanged.** Every opening is a recess *into* the block, never a hole
    through it, and the block keeps the default full-cube shape it inherits from `WarehouseStationBlock`, so placement,
    targeting and the neighbours' face culling behave exactly as before.
* **The crane side is unchanged.** `shell_intake` carries the interface's port unchanged (8 x 4 px at x 4..12, y 9..13
  over the full 3 px depth), delivery is the same `insert` at the rack position, and GameTest
  `terminaldeliveryfrombothaislesides` proves it from a LEFT and a RIGHT rack position.
* **World compatibility, with no migration.** A block state saved before M10 carries only `facing`, and a missing
  property resolves to the block's default `display=back`: port unchanged, screen on the face opposite the aisle —
  where that terminal's player already stood. A pre-M10 terminal that was *misaligned* stays misaligned (its screen
  lands on the aisle side) and one wrench click fixes it; one whose port merely pointed sideways is corrected by
  `alignToAisle`. No block entity key was added, so no save can be too old to read and nothing can throw on load.
  GameTest `terminalworldcompatibility` reads the pre-M10 block state through `NbtUtils.readBlockState` and runs the
  whole request-and-delivery loop on it.

### 3.5 Warehouse Production Station (`content.station`, M11)

> Status: **binding design** for M11 (stage 1 of "production patterns"). Decision, alternatives and the boundaries:
> **ADR-024**.

The station a player's machines are fed from. **Wareworks does not craft.** It *delivers* the ingredients a pattern
names and *collects* the result that comes back; the crafting itself is done by the player's Create machinery
(sawmill, press, mixer, mechanical crafter, anything), and nothing in Wareworks checks that a pattern matches a real
recipe.

```text
terminal order ─▶ production order ─▶ SUPPLY jobs ─▶ production station ─▶ (player's funnel) ─▶ machine
                                                                                                   │
        output station ◀── RETRIEVE job ◀── storage ◀── STORE job ◀── warehouse input ◀────────────┘
```

* **It is an aisle member of its own kind.** It stands at a rack position with its opening towards the aisle
  (`FACING == side.getOpposite()`, the rule input and output follow) and owns an **extract-only** buffer: the crane
  inserts, and the player's funnel, chute, belt or (since M12) mechanical arm pulls out; the arm through the take-only
  arm interaction point type `wareworks:warehouse_production` (§3.2.2). It reports `LocationKind.PRODUCTION`,
  **not** `OUTPUT` (ADR-024): a production station is never the destination of a retrieval request and never receives
  retrieve leftovers, and reusing `OUTPUT` is exactly what would have allowed both.
* **The patterns live in the station**, which is what answers "which machine gets these ingredients": *this* one — the
  machinery the player hooked up to *this* block. No controller-side mapping, no second configuration surface.
* **Nothing is teleported and nothing is crafted**: every item movement is a real crane job through the handling head,
  and the result is only ever *observed* arriving in the stock index.

#### 3.5.1 Patterns

A pattern is **a 3 x 3 grid of ingredient cells plus one result**, authored like a vanilla crafting recipe because
that is how a player already reads one. A station holds `maxProductionPatterns` of them (config, default 4).

* **The grid is for reading; the planner gets a multiset.** `ProductionPattern.fromGrid` merges the cells that name
  the same item into one ingredient, so three cells of one plank are "3 planks". That merge is not cosmetic: the crane
  carries **one item key per trip**, so without it three cells would be three crane trips for three planks.
* **Counts.** One cell (and the result) holds 1..64 items per run (`ProductionEntry.MAX_PER_CELL`); a *merged*
  ingredient may therefore reach 9 x 64 = 576 (`ProductionEntry.MAX_COUNT`). Repeats **between cells** are allowed and
  are the point; the merged ingredient list has distinct keys by construction.
* **One rule is enforced at construction: a pattern must not produce one of its own ingredients.** Such a pattern
  would let the producible computation promise an item out of itself, and stage 1 has no recursion that could resolve
  it. Refusing it in `ProductionPatterns#setEntry` (in both directions) keeps the loop *unrepresentable* instead of
  something every reader has to defend against.
* **Editing consumes nothing.** The cells are **ghost items**: only an `ItemKey` and a count are stored, set from what
  the player carries or clicks. A pattern is a description, not an inventory.
* **A slot is a draft.** A half-written slot (cells but no result) is simply not a pattern yet: `patterns()` returns
  only the complete ones, so the strict, pure `ProductionPattern` never has to represent a draft.

#### 3.5.2 Producible items (stage 1 is single level)

The controller knows from **all patterns of all its production stations** which item keys its aisle can make
(`producibleKeys()`, one block entity lookup per production station, asked when a request arrives or a terminal screen
refreshes — never per tick and never per planner candidate).

* **The terminal marks those keys producible even at zero stock** and offers them; their cell shows a `+` instead of a
  count and the tooltip says "Can be produced here". `StockCount#isGone` therefore treats a producible item at zero
  stock as *present* — that is what separates "there is none and never will be" from "there is none yet".
* **An ingredient counts only as real, unpromised stock.** `ProduciblePlanner` never looks at a second pattern to
  satisfy the first, so an order can never be created that waits for another order. **That is the whole stage-1 rule.**
  If an ingredient is itself only producible, the order is refused with `NOT_IN_STOCK` and the player supplies that
  item themselves. Recursive production is stage 2 and is deliberately not started here.
* **Two patterns for one item do not add up.** `producibleAmount` takes the **best single pattern**, because two
  patterns usually compete for the same ingredients and adding them would promise items twice.

#### 3.5.3 Production orders

Ordering a producible item that is not (fully) in stock creates a **production order**.

* **Partial stock is allowed and is the normal case**: the request is clamped to `available + producible`, the part
  that is in stock is served by ordinary `RETRIEVE` jobs at once, and only the remainder starts an order. A pattern
  makes whole runs, so an order may yield *more* than was asked for; the surplus simply lands in stock, and the
  request is never told it waits for more than it asked for.
  * **The order records that promise** (`ProductionOrder#promisedToRequest`), separately from its own
    `resultAmount`, because the promise is also what a *failed* order has to give back (§3.5.4). Refunding the whole
    run instead would take items off the request that production never owed it — with "1 log → 4 planks", 50 planks
    in stock and a request for 100, one run of 64 is started for the 50 that are missing, and a timeout that refunded
    64 would silently strip the request of 14 planks the aisle really holds, or delete it outright once the shortfall
    reached its remaining amount.
* **One arrival is credited once.** Several open orders may wait for the same result; each sees the increase of the
  same stock level, so the observation hands it out to them **in creation order** — the oldest takes what it still
  waits for, the next only what is left (`ProductionOrders#observeResult`). Crediting every order with the full
  increase would complete an order nothing was made for, and such a phantom-completed order never gives its backing
  request the amount back and can never time out either, so that request would wait for ever.
* **Batching, not one order per craft.** The order runs `pattern.runsFor(amount)` runs, and each ingredient becomes
  **one `SupplyLine` of `count × runs` items** — ten planks from "1 log → 4 planks" are 3 runs, i.e. one line of 3
  logs and therefore **one crane trip**, not three.
* **Each ingredient line has its own id**, which the `SUPPLY` job carries. That is what lets the reservation ledger
  track each ingredient separately and `committedToRequest` answer "how much of this ingredient is already on its
  way", so the planner never plans a second trip for items already in the grabber.
* **Open orders promise their ingredients.** `availableStock` subtracts what the open orders still owe, exactly as it
  subtracts what open requests promise, so a second order sees them as taken and refuses instead of promising the same
  items twice.

**States** (`ProductionOrderState`, a pure and unit-tested state machine):

| State | Meaning | Leaves it by |
|---|---|---|
| `WAITING_FOR_INGREDIENTS` | the crane is still bringing ingredients | every line served → `DELIVERED` |
| `DELIVERED` | everything is in the station's buffer; the machine has not taken it | the buffer no longer holds them → `WAITING_FOR_RESULT` |
| `WAITING_FOR_RESULT` | the machine took them; the product is expected through a warehouse input | enough result seen → `COMPLETE` |
| `COMPLETE` | the expected amount of the result arrived in the warehouse | — |
| `TIMED_OUT` | no progress for `productionOrderTimeoutTicks` | — |
| `CANCELLED` | a player (or a removed station) gave up on it | — |

* **Only *increases* of the result's stock count.** The level also falls when the crane serves a request out of the
  same stock, and a falling level says nothing about production. Items are counted **from any source**, deliberately:
  a player who puts the product in by hand has satisfied the order just as well, and the alternative (counting only
  what one particular input delivered) would tie the order to which station the machine happens to feed.
  * **The level is therefore sampled on every controller tick**, not at the dispatch cadence. Reading a level rather
    than counting an event has one blind spot: anything that returns the level to the previous sample *between* two
    observations is invisible, and the order records no progress at all — the crane storing a batch and a `RETRIEVE`
    taking exactly it back out again for the very request that started the order, or a funnel emptying a chest. At the
    dispatch cadence (up to 200 ticks) that window is wide enough to time an order out although the machine worked;
    one tick is narrow enough not to be. The cost is one stock-index lookup per distinct result key of the open
    orders, bounded by `maxProductionOrders`; the expensive half of the order tick (station lookups, timeouts,
    pruning) stays on the dispatch cadence.
* **"The machine took the ingredients" is decided per order, not per station.** A station runs as many orders as it
  has patterns, and the buffer it shares between them says nothing about an order whose own ingredients are still
  lying in it (`ProductionOrders#ingredientsTaken(UUID, …)`). Advancing every delivered order of a station would
  report one as "at the machine" while the player can see its ingredients in the buffer, and would push its timeout
  out by a step that never happened to it.
* **Every step pushes the deadline out** (a delivery, the machine taking the ingredients, a result arriving), so a
  slow machine is never killed mid-run while a genuinely stuck order still ends.
* **Deadlines are not persisted.** A world that was closed for an hour would otherwise time out every order the moment
  it loads; a restored *open* order starts its timeout over, and a restored *finished* one ages from `now` so the
  retention below stays honest.
* **Finished orders are kept for `FINISHED_ORDER_RETENTION_TICKS` (600)** and then forgotten. A player has to be able
  to come back and read that their order timed out; one that vanished the moment it failed would look exactly like one
  that was never placed. A finished order promises nothing, so keeping it costs only a line.

#### 3.5.4 What cannot be undone (the documented boundary)

A timed-out or cancelled order **releases every reservation and gives its backing request what it promised it and
never delivered** (`ProductionOrder#unfulfilledPromise` through `RequestQueue#reduce`, which is deliberately *not*
`deliver`: nothing arrived, so "delivered so far" must not grow). It **never invents an item**, and it never takes
more off the request than production owed it (§3.5.3).

**The crane stops with the order.** Ending an order cancels a crane job that is fetching one of its ingredients
(`CraneDispatch#onOwnersCancelled`, the same path a cancelled retrieval request takes): before the pick the job
aborts, after it the held items are rerouted back into storage. Without this the crane finished the trip of an order
that had already ended and dropped the ingredients into the machine's buffer, from where the player's funnel fed them
to a machine for an order nobody was waiting on.

But it also **never takes an item back**: ingredients the crane already dropped into the production station stay
there, and ingredients the player's machine already swallowed are gone from the warehouse's point of view. Wareworks
does not reach into a machine, and it cannot know what a machine did with them. `ProductionOrder#deliveredIngredients`
reports the amount, the screen says so on the order line itself ("…, ingredients not recovered", in gold) rather than
only in a tooltip, and GameTests `productionordercancelled` and `productionordertimeout` assert exactly this: the
delivered ingredients are still in the station afterwards and the item census is unchanged.

A **finished** order still counts a delivery that reaches it (`ProductionOrder#withDelivered`), without moving its
state or its deadline. A drop that was already in the crane's grabber when the order ended really does land in the
station, and `deliveredIngredients` is the number the screen prints: leaving such a drop uncounted would understate
the loss by exactly the amount that was in flight and report "nothing handed over" while the items lie in the buffer.

#### 3.5.5 Implementation

Classes:
* `core.production` (pure, JUnit-tested): `ProductionEntry`, `ProductionPattern` (+ `fromGrid`), `SupplyLine`,
  `ProductionOrder`, `ProductionOrderState`, `ProductionOrders`, `ProduciblePlanner`.
* `content.station`: `WarehouseProductionBlock` / `WarehouseProductionBlockEntity`, `ProductionPatterns` (the editable
  grid slots and their NBT), `ProductionGoggleSummary`, `ProductionMenu`, `ProductionMenuLayout`,
  `ProductionScreenState`.
* `content.controller`: the production API of `WarehouseControllerBlockEntity` (patterns, producible keys and amounts,
  orders, `supplyNeeds()`, the order tick) and the `ProductionOrders` persistence in `ControllerPersistence`.
* `core.job`: `JobType.SUPPLY`, `PlannerInput.SupplyNeed`, `NoJobReason.PRODUCTION_FULL`,
  `LocationKind.PRODUCTION` / `isDeliveryTarget()`, `RequestQueue#reduce`, `RetrievalRequest#withRemoved`.
* `client.gui`: `WarehouseProductionScreen`, `ClientProductionStations`, `ProductionScreenUpdates`;
  `network`: `ProductionScreenPayload`, `ProductionPatternPayload`, `ProductionCancelPayload`.

* **Planning.** `SUPPLY` is planned **exactly like a `RETRIEVE`** — same candidates, same ranking, same live
  validation — by the shared `JobPlanner#planOutOfStorage`; only the target and the owner differ. It runs **after** the
  retrieval requests (a player waiting at a terminal is served first) and **before** storing, so ingredients move while
  new items are still arriving. A production station that accepts nothing reports `PRODUCTION_FULL`, told apart from
  `OUTPUT_FULL` because the cure is different: the player's machine has to take what is already in the buffer.
* **Reservations.** `SUPPLY` reserves **stock at its source** before the pick (like a retrieve, `reservesSourceStock()`)
  and **transit** afterwards (`LocationKind#isDeliveryTarget`), so its items are never double-counted while they are
  in the grabber.
* **Leftovers go back into storage, never to an output** (`JobPlanner#planReroute`): nobody requested them at a
  station, and putting them into an output would hand a player ingredients they never asked for. A rejecting store
  filter is advisory here for the same reason as on a retrieve reroute — the items already left the warehouse.
* **The station's block** is a `WarehouseStationBlock` like input and output (no ticker, no redstone conduction, no
  value box, `create:non_movable` + `c:relocation_not_supported`, pickaxe only, buffer dropped in `destroy()`, emptied
  by `Clearable`). A right-click with an **empty hand** opens the pattern screen; anything held passes the interaction
  on, so the wrench still rotates it.
* **The screen** (`ProductionMenu`, the terminal's conventions): a menu-scoped, throttled push to the one player who
  has it open (`REFRESH_INTERVAL_TICKS` 10, nothing sent while nothing changed), real slots for the buffer (take out
  only), and **bounded payloads** validated entirely on the server. Pattern entries are `ItemKey`s with data
  components and therefore travel **only** in the menu payload, never in the block entity's update tag, which is part
  of every chunk packet (§3.1.1). The goggles get numbers and one enum name (`ProductionGoggleSummary`).
  * The window shows **one pattern at a time** with a tab strip beside the grid; showing every pattern's grid at once
    would need `maxProductionPatterns × 54` pixels and could not fit the height vanilla guarantees at every GUI scale.
    The **order lines** are the flexible part of the layout (2 down to 0), the trade the terminal makes with its stock
    grid.
  * A client may only *ask*: `submitPattern` and `submitCancel` resolve the payload against the menu that player
    really has open, check reach, clamp indices and counts, and answer at most `MAX_EDITS_PER_TICK` (8) per tick. A
    cancellation is refused for an order that does not run at *this* station, so a crafted payload cannot reach into
    another aisle.
* **Persistence.** Patterns are saved with the station (`Patterns: [{Slot, Entries: [{Entry, Item: ItemKey, Count}]}]`,
  bounded loading, never throws); orders are saved with the controller
  (`ProductionOrders: [{Id, Station, Result, ResultAmount, State, Produced, StockSeen, Request?, Lines: [...]}]`,
  at most `MAX_SAVED_ORDERS` (256), invalid entries skipped, deadlines recomputed on load).
* **Goggles.** The station shows its patterns, the orders running there, the oldest order's state, ingredients still
  to fetch and results still awaited; the controller adds "Production stations: N" and "Production orders: N" (both
  left out of the synced tag while they are 0, so the aisles without a production station pay nothing).
* **Two worked examples in the showcase world** (`dev.ShowcaseVisualScenario`, M11 visual refresh). Both are the same
  shape with a different machine of the player's in the middle. The scenario is *built* to prove them before the world
  is saved — it orders the product of each loop at the terminal and waits until the production order reads `COMPLETE`
  and the crane has delivered, so a loop that did not work fails the Gradle task instead of being handed to a player.
  * **The first complete showcase run found both loops broken, for two different reasons.** Both are fixed; both
    are worth keeping written down, because each one looked like a perfectly healthy production order:
    * **Sawmill — the saw cut the wrong item.** `CRecipes.allowStonecuttingOnSaw` defaults to **true**, so
      `SawBlockEntity#getRecipes()` returns the `create:cutting` recipe *and* every `minecraft:stonecutting` recipe
      whose first ingredient matches — and 6.0.10 ships four of those for andesite alloy (bars, ladder, scaffolding,
      table cloth). `SawBlockEntity#start` then **cycles** through the five candidates (`recipeIndex++`), so only one
      run in five yields shafts: the saw pushed andesite **ladders** into the warehouse input, the crane dutifully
      stored them, stock stayed `alloy=31 shaft=0` and the order timed out. The fix is the one a player makes — the
      scenario sets the saw's own `FilteringBehaviour` to Shaft, which `getRecipes` applies through
      `RecipeConditions.outputMatchesFilter` and which collapses the five candidates to the one cutting recipe.
    * **Mechanical crafter — the loop worked exactly once.** A crafter refuses every insertion while its group is
      working (`MechanicalCrafterBlockEntity.Inventory#insertItem` hands the stack back unless `phase == IDLE`), so the
      second run's ingredients rode past all three busy funnels to the end of the belt — where the loop's catch chest
      was no longer standing, because the loop's own label sign had been placed on exactly that block. A belt end that
      is neither an inventory nor a solid face **ejects** what reaches it (`BeltInventory#resolveEnding`), so one full
      ingredient set became item entities on the floor while the order waited for a result no machine would make. Two
      fixes: a **hopper** between each funnel and its crafter buffers one ingredient until that crafter is idle again,
      so the funnel can always clear the belt; and `placeSign` now refuses to overwrite a non-air block instead of
      silently replacing machinery.
    * **What the failure did *not* touch:** the mod itself behaved correctly throughout. Ingredients were delivered,
      the orders tracked their state, the timed-out order released its reservations, and the item-conservation
      invariant (§8) held — the loose items were Create's crafters ejecting their grid, not items the warehouse lost.
      Both defects were in the showcase world's machine wiring (`dev.ShowcaseVisualScenario`), not in `core` or
      `content`.
    * Because an order of a **single** run cannot show either failure, the scenario now also asserts after production
      that the chest at the end of the crafter belt is empty and that no items lie loose in the scene.
  * **Sawmill**: station → hopper → **feed belt** → a **Mechanical Saw** standing face up → warehouse input. The saw
    takes items only into slot 0 of its `ProcessingInventory` and only while it is empty (`isItemValid`), so it can
    never be overrun, and it hands its results to whatever offers a `DirectBeltInputBehaviour` in its item movement
    direction — which is the warehouse input one block away. The feed is a **belt** rather than hoppers because that is
    the path Create designs for a saw (`DirectBeltInputBehaviour().allowingBeltFunnelsWhen(canProcess)`); a hopper feed
    made the ingredient disappear from every inventory without ever being cut. The saw also carries a **recipe filter
    set to Shaft**, without which it cuts something else entirely (see the sawmill failure above).
  * **Mechanical crafter**: station → hopper → **belt** → three **brass belt funnels**, each filtered to one
    ingredient → three **hoppers** → three **Mechanical Crafters** → warehouse input. Neither the filters nor the
    hoppers are decoration:
    * the **filters** because a crafter slot holds exactly **one** item, so an unfiltered feed would put two of the
      same ingredient into two crafters as soon as an order runs the pattern twice, and the group would then try to
      craft a recipe that does not exist;
    * the **hoppers** because a crafter accepts nothing while its group is working, and a belt funnel that cannot
      insert leaves the item on the belt to ride past every funnel. The hopper is the buffer that waits.
  * **Deviation from the feature request, on purpose**: the sawmill cuts **andesite alloy into shafts**, not a log into
    planks. Create 6.0.10 ships no `create:cutting` recipe for vanilla logs — the only non-compat **`create:cutting`**
    recipes in the jar are `andesite_alloy → 6 shaft` and `bamboo_planks → bamboo_mosaic` — so a "log → planks" loop
    would deliver ingredients that the saw silently ignores and the order would only ever time out. Note that
    `create:cutting` is *not* the whole set of recipes a saw performs: stonecutting counts too, which is the sawmill
    failure described above.

**Tests**:
* JUnit: `ProductionPatternTest` (the grid-to-multiset merge, the two construction rules, the batching maths),
  `ProductionOrderTest` (the state machine, runs → one line per ingredient, what an ended order handed over, and —
  from the M11 review — that a failed order gives back its **promise** rather than its run, that an order nobody
  waits for promises nothing, that only the *credited* part of an arrival counts, and that a finished order still
  counts a drop that was already in the grabber), `ProductionOrdersTest` (promises, line ids, retention, restore,
  that two orders for one result **share** one arrival, that only the named order advances when a machine empties a
  shared station buffer, and the one-pass ingredient map), `ProduciblePlannerTest` (producible keys, the best
  pattern, and that an only-producible ingredient counts as missing). `CraneStateMachineTest` pins that a full
  production station is waited for exactly like an output.
* GameTests (`gametest.ProductionGameTests`): `productionregistration`, `productionextractonly`,
  `productionpatternediting`, `productionpatternpersistence` (a **full nine-cell** pattern through a save and load),
  `productionsupplyjob`, `productionsupplyjobtype`, `productionfullloop` and `productionfulllooptwoingredients`
  (the test **plays the machine**), `productionmissingingredient`, `productionordercancelled`,
  `productionscreeneditsandcancels` (the payload path and hostile payloads), `productionordertimeout` (own config
  batch), `productionorderpersistence`, and from the M11 review `productionrefundisonlythepromise` (5 in stock, 7
  ordered, a run of 4 — the request keeps its stock-backed part), `productioncancelstopsthecranemidtrip`,
  `productionstationbrokencancelsitsorders` and `productionpatternssurvivealoweredcap` (own config batch), and from M12
  `productioningredientstakenbymechanicalarm` (a real, powered mechanical arm takes the delivered ingredients out onto
  a depot, and the order moves on to "waiting for the result" exactly as when a funnel pulls them; §3.2.2).
  `WarehouseTerminalMenuGameTests.terminalproducibleitemssurvivethecut` pins that an offer is not the first thing the
  stock window drops. Every test in which the crane or a mechanical arm moves items (`productionsupplyjob`,
  `productionsupplyjobtype`, both full loops, `productioningredientstakenbymechanicalarm`, `productionordercancelled`,
  `productionordertimeout`, `productioncancelstopsthecranemidtrip`, `productionorderpersistence`) asserts the item
  conservation invariant on every tick with `gametest.ItemCensus`, registered with `onEachTick`; the expectation
  changes only in the step where the test plays the machine. (Until the M12 review the loop tests checked the census
  inside their `thenWaitUntil` conditions, where a failing check is only retried on the next tick, so it counted only
  once the items had arrived.)

## 4. Discovery and membership (no permanent world searches)

* **`WarehouseRegistry`** (server-side, per `ServerLevel` via catnip `WorldAttached`, in-memory) maps controller position to `AisleBounds`. A controller registers in `onLoad()` (bounds become known after its first tick) and whenever its geometry changes. It unregisters in `invalidate()`/`remove()` (Create's `setRemoved` is final).
* **Members** (interface, input, output) call `WarehouseRegistry.memberChanged(level, pos)` in `onLoad()`, on placement and on removal. Every controller whose bounds contain `pos` marks its membership dirty.
* **Geometry refresh**: the dock re-counts rails at most every `geometryRefreshTicks` (default 40, ≤ `maxAisleLength` block-state reads) and immediately when the mast height changes. If the geometry changed, the controller marks membership dirty. Implemented in M2 (`stacker-crane.md` §3.1), including on load, after a facing change, and the rule that a scan reaching an unloaded chunk keeps the known length.
* **Membership scan** runs only when dirty. It iterates the rack positions of the bounds (≤ 2 · (L+1) · H), **only in loaded chunks**. Records in unloaded chunks are kept from the persisted list.

**Implementation (M2, controller) and deviations:**
* **Registry**: `WarehouseRegistry` maps controller position → registered `AisleLayout`. This is the design's `AisleBounds`; containment is `AisleLayout#worldToLocal`, exact rack positions instead of an AABB.
  * `memberChanged(level, pos)` passes the aisle-local `RackPosition` to every loaded controller whose layout contains `pos`.
  * `contentChanged(level, pos)` (M2 review fix) does the same for a content hint of a storage member; the controller queues the location for an urgent snapshot if it is a storage record (§5).
  * `findController(level, pos)` prefers the aisle in which the member there is aligned, because two aisles can share a rack plane. A position can lie in several aisles, so the winner is decided by a fixed rule and never by map iteration order (**M5 review fix**): aligned beats not aligned, then the **closest dock** wins, then the lower controller position. A redstone request at an output that two opposing aisles reach is therefore always served by the same aisle (GameTest `twoaislessharearackplane`).
  * `assignmentOf(level, pos, member)` resolves the goggle address.
  * Only `ServerLevel`s are accepted. The per-level maps live in a catnip `WorldAttached`.
* **Refinement: per-position dirty marks.** A notification marks only its own rack position dirty, and the next tick probes only those positions.
  * More than `AisleMembership.DEFAULT_MAX_DIRTY_POSITIONS` (64) pending positions escalate to a full scan.
  * Full scans run after load, geometry changes and layout changes.
  * Reason: otherwise, building a rack wall one interface at a time would rescan up to 2 · (L+1) · H positions per placement.
* **Members** implement `content.controller.WarehouseMember`: `locationKind()`, `facing()`, `isAlignedWith(layout, side)` and, since M10, `alignToAisle(controller, layout, side)` — a hook the probe calls immediately before `isAlignedWith` for a member whose block state depends on *where the aisle is*. Its only implementation is the warehouse terminal's intake port (§3.4.3, ADR-022); the contract is that it writes the block state only when it really changes **and only when `WarehouseRegistry#ownsMemberState(level, member, controller)` names this controller**, so the probe stays a read for every other member and two aisles sharing a rack plane cannot fight over one block state (M10 review fix). A write from inside the probe marks that position dirty again; `core.warehouse.AisleMembership#reconcile` snapshots and clears its dirty set *before* the probe loop, so the mark is kept for the next pass rather than lost or probed twice, and the cost is one further reconcile in which nothing is written. Storage members also implement `StorageMember` (`attachedPos()`, `snapshot()`).
  * `core.warehouse.LocationKind`: `STORAGE` faces away from the aisle; `INPUT`, `OUTPUT` and (since M11)
    `PRODUCTION` face the aisle. `OUTPUT` and `PRODUCTION` are the two **delivery targets** a crane drops at on behalf
    of something that asked (`isDeliveryTarget()`, §3.5, ADR-024); only `OUTPUT` is a retrieval destination.
  * The warehouse input and output implement `WarehouseMember` (kinds `INPUT` / `OUTPUT`, §3.2.1); the controller needed no change for them. A `STORAGE` member that is not a `StorageMember` is ignored.
* **Deviation: members notify in `remove()`, not in `invalidate()`.**
  * `SmartBlockEntity#setRemoved` calls `remove()` for every real removal, while `invalidate()` also runs on chunk unload.
  * A notification on unload would only probe an unloaded position, which keeps its record anyway. During level shutdown it would also re-create registry maps that Create has just cleared.
  * Controllers still unregister in both `invalidate()` and `remove()`.
* **Unloaded chunks**: probes check `Level#isLoaded` and never load a chunk. An unloaded position keeps its record and misaligned flag, also across restarts since records are saved, unless the layout changed.
* **Limitation**: members in loaded chunks are not tracked while their controller's chunk is unloaded; the controller scans fully when it loads. GameTests cannot cover unloaded chunks (test areas are force-loaded); the rules are covered by `AisleMembershipTest`.

## 5. Snapshots and stock index

* Snapshot sources, in order of freshness:
  1. **After every crane transfer**, the touched location is re-snapshotted immediately.
  2. **When a member is added**, it is snapshotted immediately.
  3. **Round-robin reconciliation**: one location every `snapshotIntervalTicks` (default 10). This catches external changes, e.g. a player putting items into a chest.
* `StockIndex` updates incrementally by diffing old and new snapshots per location.
  * **Implementation (M2):** `core.inventory.StockIndex<K, L>`. `update(location, snapshot)` stores the snapshot, applies only the per-key difference between the old and new totals (cost proportional to the keys of those two snapshots, never to the index size) and returns whether any count changed. Further API: `remove(location)`, `count(key)`, `countAt(key, location)`, `locationsOf(key)` → `List<LocationCount<L>>`, `locations()`, `totalItems()`, `distinctKeys()`, `keys()`, `snapshotOf(location)`, `contains`, `locationCount`, `clear()`.
  * Zero counts are pruned. Counts are `long`.
  * Order of `locationsOf` and `locations()`: a comparator given to the constructor, otherwise the order in which locations were first added. That order is stable when a key leaves and returns; a removed and re-added location goes last.
  * Not thread-safe (server thread only). `StockIndexTest` includes a seeded randomized comparison against full recomputation (400 locations, 5000 updates and removals).
  * **Controller additions (M2):**
    * `restore(location, counts)` sets counts without slot information, for loading a save. `snapshotOf` stays empty until the next snapshot.
    * `countsAt(location)` returns the per-key counts of one location, for saving.
    * `readOnlyView()` returns a `StockView` that cannot be cast back to the index.
* **Implementation (M2, controller):**
  * **Snapshot queue (M2 review fix, refinement of source 2).** Storage locations waiting for a read are kept in `core.inventory.SnapshotQueue` (de-duplicated, FIFO, urgent before background), and the controller reads at most `maxSnapshotsPerTick` (config, default 4) of them per tick. Urgent: a location that joins (indexed empty at once), a content hint (below), a location that takes over another one's counts. Background: counts restored from a save (§3.3.1). Reason: the design rule "scans only on demand and throttled". The first version read every joining location in the same tick, i.e. up to 2·(L+1)·H full inventory reads in one tick after a dock link, a mast height increase or a dock rotated and back. A single joining location is still read in the tick it joins. **M8 review fix:** a polled location whose rack position is not loaded is put **back** into the queue. `SnapshotQueue#poll` has already removed it, so dropping it left its restored counts to the round robin alone, which needs up to 2·(L+1)·H·`snapshotIntervalTicks` to come round (about 9 minutes at the default caps).
  * **Content hints (M2 review fix, ADR-013).** The warehouse interface reports content changes of its attached inventory (`onNeighborChange`) and block updates at the attached position (`neighborChanged`) through `WarehouseRegistry#contentChanged`; the controller queues that location as urgent. A hopper line feeding a chest therefore costs at most one read per tick for that location (de-duplication), not one per item. Inventories that change silently (no `setChanged`) are still found by the round robin.
  * **Shared inventories (M2 review fix).** A double chest exposes all 54 slots from both halves (NeoForge `InvWrapper(ChestBlock.getContainer(..., true))`), and every block of a Create item vault exposes the whole vault. Each snapshot therefore also resolves the inventory identity with Create's `api.packager.InventoryIdentifier.get(level, BlockFace(attachedPos, face towards the interface))` (the packager uses it for the same purpose; `Pair` for double chests, `Bounds` for vaults), falling back to the attached position. `core.inventory.SharedInventories` makes the first location of an identity canonical: it holds the counts, the others (aliases) keep empty counts, and a snapshot read through an alias is stored for the canonical location. When the canonical location leaves (removed, or its identity changed), the oldest alias takes over the counts and is read urgently. When a location's identity changes to a multi-block inventory, the other storage locations attached to blocks of it are queued, so a merged double chest is not counted twice until the round robin arrives. The scan walks **the aisle's own storage records** (at most 2·(L+1)·H) and asks `InventoryIdentifier#contains` about each one's inventory face. **M5 release audit:** it used to enumerate the *inventory's* blocks instead, capped at 4096, and tried both rack sides per block, so a large Create vault cost up to 8192 containment tests with two fresh `BlockPos` allocations each, in a single tick, for every location that reported a new identity. Walking the aisle instead is bounded by the aisle, needs no cap, and now also covers single-block and multi-face identities. The round robin skips aliases. Limitation: modded multi-block inventories that do not register a Create `InventoryIdentifier` are counted once per location, and inventories that re-form without any block update (a vault growing) are corrected by the next hint or round robin.
  * `refreshLocation(RackPosition)` re-reads one location on demand; M3 calls it after each transfer. It satisfies a pending queue entry for that location.
  * The round robin (`AisleMembership#nextStorageLocation`) reads one location per `snapshotIntervalTicks`. A location or attached inventory that is not loaded is skipped, and its counts are kept.
  * A foreign inventory that throws keeps its last counts; the failure is logged at most once per `util.LogThrottle` interval (1200 ticks) per controller, not once for the controller's lifetime (**M5 release audit**).
  * A storage location whose block entity vanished without a notification is re-probed.
* **Planning uses the index to rank candidates, but validates candidates against the live handler** with `simulate = true` before a job is created: in rank order, falling through to the next candidate when the live simulate accepts (or yields) less than needed, never only the top one. Snapshot estimates are upper bounds (§5.1), so a restricted inventory (e.g. a shulker box offered a shulker box) can rank high and still reject the item. At transfer time the live handler is authoritative, with real extract and insert results.

### 5.1 Snapshot model and capacity estimate (implemented in M1)
* `core.inventory.InventorySnapshot<K>` is an immutable list of `SlotView<K>(key|null, count, slotLimit, maxStackSize)` with per-key `long` totals (first-appearance order), `usedSlots`, `totalSlots`, `count(K)` and `topEntries(n)` (largest first, stable ties). `insertable(key, maxStackSize)` is O(1) (**M3 review:** the free space per key in occupied slots is computed with the snapshot, and the empty slots usually share one limit; mixed limits fall back to one pass over the slots), because the planner estimates every storage candidate; JUnit compares it with the slot-by-slot `CapacityMath` estimate. `content.item.ItemHandlerSnapshots.capture(IItemHandler)` builds one from a live handler; it reads each slot once and is only called on demand.
* `core.inventory.CapacityMath` estimates capacity per slot as `min(slotLimit, maxStackSize)`. **Deviation/refinement:** if a slot already holds more than the key's max stack size, the inventory evidently ignores stack sizes (drawer-like), so that slot's capacity is its `slotLimit`. Empty slots use the key's max stack size. This stays an estimate for ranking; the live simulate above remains authoritative. **The insert estimate is an upper bound (review correction):** `SlotView` carries no acceptance rules, so slot filters (`IItemHandler.isItemValid`, `Container.canPlaceItem`) and sided insertion rules (`SidedInvWrapper` → `canPlaceItemThroughFace`) are not modelled. It equals a simulated `ItemHandlerHelper.insertItem` only for unrestricted inventories such as chest, barrel and plain `ItemStackHandler`s (GameTest `chestsnapshot`); it overestimates for restricted ones, e.g. a shulker box rejects shulker boxes (GameTest `shulkercapacityestimate`: estimate 27, live 0), a furnace or brewing stand queried from a side exposes only its fuel/ingredient slots, a chiseled bookshelf accepts only books. Capturing `isItemValid` would not fix this, because `SidedInvWrapper.isItemValid` does not check `canPlaceItemThroughFace`; only the live simulate with fall-through (§5) does.
* `CapacityMath.carryLimit(maxStackSize, grabberStacks, grabberMaxItems)` implements the §7.1 formula.
* `core.inventory.InventorySummary<K>(usedSlots, totalSlots, distinctKeys, topEntries)` is the compact, value-comparable digest of a snapshot (`of(snapshot, n)`, or `of(snapshot, n, grouping)` to merge keys into coarser groups such as item types); `sanitized(...)` clamps untrusted (network/NBT) values, including negative counts, instead of throwing. Its size is bounded only by its key type, so anything synced to clients uses a small group key. The warehouse interface syncs it by item type for goggles (§3.1.1).

## 6. Item keys

`ItemKey` = immutable copy of an `ItemStack` with count 1. Equality uses `ItemStack.isSameItemSameComponents`, the hash uses `ItemStack.hashItemAndComponents`. Core logic is generic over the key type (`K`), so it can be unit tested without Minecraft bootstrap.

Implementation (`content.item.ItemKey`): the wrapped stack is never exposed (`toStack(count)` returns copies), so keys are safe map keys. `CODEC` is `ItemStack.SINGLE_ITEM_CODEC` (id + components), `STREAM_CODEC` wraps `ItemStack.STREAM_CODEC`. `save(provider)` / `load(provider, tag)` and `saveTo`/`loadFrom` never throw: an unencodable key saves as an empty tag, an unknown or invalid tag loads as `Optional.empty()` with a warning. Because `ItemKey` needs registries, it is tested in the GameTest `itemkeypersistence`, not in JUnit.

## 7. Jobs

```text
TransportJob { UUID id; JobType type; LocationRef source; LocationRef target;
               ItemKey item; int amount; UUID requestId (retrieve only) }
LocationRef  { BlockPos pos; int x; int y; Side side; LocationKind kind }
JobType      STORE (input station → storage location) | RETRIEVE (storage location → output station)
             | SUPPLY (storage location → production station, M11: one ingredient of a production order, §3.5)
```

### 7.1 Dispatch (controller, every `dispatchIntervalTicks`, default 5)
Dispatch runs only when a crane exists, is idle, has no held items and has speed ≠ 0.
1. **RETRIEVE first** (a player is waiting), for the oldest request with `remaining > 0`:
   * Candidates: locations holding the item per index, loaded, not fully reserved.
   * Amount = `min(remaining, carryLimit(item), liveExtractable(source) − reserved)`.
   * The output buffer must accept at least 1 (live simulate). Otherwise skip, since output is full.
   * Ranking: minimal travel time `crane → source → output`.
2. **STORE**, otherwise, for the first input station with a non-empty buffer (round-robin over inputs):
   * Item = first non-empty slot. Amount = `min(buffered count of item, carryLimit(item))`.
   * Candidates: locations accepting the item (live simulate insert > 0), ranked by (a) already contains the item (consolidation), then (b) travel time `crane → input → location`. (M3 refinement, §7.4: between (a) and (b), locations that are empty or hold only the item's type rank before locations holding other item types.)
   * Amount = `min(amount, liveInsertable(target) − reservedCapacity(target))`.
   * No target: set status `WAREHOUSE_FULL` and back off `fullBackoffTicks` (default 40).
3. Create the job, reserve (STORE reserves capacity at the target, RETRIEVE reserves stock at the source), assign to the crane.

`carryLimit(item) = min(grabberStacks · item.getMaxStackSize(), grabberMaxItems)` (config: `grabberStacks` default 1, `grabberMaxItems` default 64).

**Travel time** = `max(|Δx| / vx, |Δy| / vy)`, because X and Y move simultaneously, plus arm extend/retract time and transfer time. Speeds derive from the crane RPM (see `stacker-crane.md`). A badly planned layout is therefore measurably slower.

### 7.2 Requests
* On a rising edge, the output station calls `controller.request(outputPos, item, amount)`.
* The amount is clamped to `available = indexCount(item) − reserved`. If 0, the request is rejected, and the station shows "not in stock".
* Requests are persisted, served by successive RETRIEVE jobs, and removed when `remaining == 0`. Delivered amounts count only after a successful drop into the output buffer.
* Max open requests per controller: `maxOpenRequests` (default 16).

**Implementation (M2, stations):**
* `core.job.RequestQueue<K, D>` (pure Java, JUnit `RequestQueueTest`): FIFO of immutable `RetrievalRequest {UUID id, K key, int requested, int remaining, D destination}`.
  * `add(key, requested, destination, availability[, maxRemainingPerRequest])` clamps the amount to `availability(key)` and to the per-request cap; 0 available → `NOTHING_AVAILABLE`, the open request for that key and destination already at the cap → `REQUEST_FULL`, `maxOpenRequestsPerDestination` open for that destination → `DESTINATION_FULL`, `maxOpenRequests` open → `QUEUE_FULL` (checked in this order). The accepted amount becomes `requested` and `remaining` of a new request, or is **added** to the open one (merging, below). `AddResult` carries the resulting request, the amount this call accepted and whether it merged; `openFor(destination, key)` is the request a further request would grow.
  * `deliver(id, amount)` counts down (never below 0) and removes a finished request; `cancel(id)`, `cancelFor(destination)`, `clear()`.
  * Queries: `oldestOpen()`, `requests()`, `requestsFor(destination)`, `remainingFor(destination)`, `remainingOf(key)`, `openCount()`, `isFull()`.
  * `restore(list)` for loading: skips finished and repeated entries and ignores the cap, so a lowered `maxOpenRequests` never deletes saved requests; it only rejects new ones until enough are done.

**Merging repeated requests (M7, binding; ADR-020).** A request for an item a destination already waits for does **not** queue a second request — it grows that one. Clicking the same item ten times at a terminal is one request the crane serves in **one** trip, not ten trips of one item.

* **Rule:** same `ItemKey` **and** same destination (warehouse output or terminal) → the accepted amount is added to the **oldest** matching open request (`RequestQueue#openFor`, `RetrievalRequest#withAdded`: `requested` and `remaining` both grow, `delivered()` is unchanged). A different key or a different destination is always a request of its own. Merging happens in `add` only: `restore` never merges (below).
* **Fairness — what a merge does to the queue position (M7 review fix).** The planner serves the open requests strictly oldest first (§7.1, `CraneDispatch#openRequests` passes them in queue order) and a request leaves the queue only when `remaining` reaches 0, so "keeps its position" and "is topped up for ever" together mean starvation. The rule is therefore split by whether the request was served already:
  * **nothing delivered yet** (`delivered() == 0`) → the grown request **keeps its queue position**. A burst of clicks stays where the first click queued it, which is what a player means, and the GameTest `twoterminalskeeptheirownrequests` pins it.
  * **already served** (`delivered() > 0`) → the grown request moves **behind every request that is open now** (`open.remove(id)` before the `put`). A destination that keeps topping its request up takes its turn again instead of holding the head of the queue: after each trip the next top-up sends it to the back, so every other station is served in between.
  * A `deliver` never moves a request, and no other request is ever moved. **The guarantee is therefore:** a station cannot be starved by another station's repeated requests; it is *not* "FIFO is untouched" (an earlier draft of this section and of ADR-020 claimed that, which was wrong the moment a merged request was topped up faster than the crane drained it).
* **Clamping applies to the merged total, never to the increment alone:**
  * **available stock** — `availableStock(key)` already subtracts what every open request still promises (the no-double-subtraction rule of the M2 note below), so `min(asked, available)` bounds the *merged* amount by the real stock. Ten clicks can never promise more than one click could.
  * **the per-request cap** — the `maxRemainingPerRequest` argument limits the request's **remaining** amount. Nothing fits any more → `REQUEST_FULL` → `RequestRejection.REQUEST_FULL` ("the open request here already asks for the largest allowed amount"); delivered items free room again. **Both** entry points pass one, because a merge takes no queue slot and the queue caps can therefore not bound it:
    * a **terminal** passes `maxTerminalRequestAmount` (default 1024), so repeated clicks grow one request only up to that;
    * a **warehouse output** passes `maxOpenRequestsPerOutput × requestAmount()` (`WarehouseOutputBlockEntity#maxRequestAmount`, 4 × at most one stack by default) — exactly what its request slots could promise at once before pulses merged, so a pulse clock is refused with `REQUEST_FULL` where `OUTPUT_FULL` refused it before. **M7 review fix:** the output first passed `RequestQueue.NO_AMOUNT_LIMIT`, which left the available stock as its only bound, and this section justified that with "it could promise the same amount before, spread over `maxOpenRequestsPerOutput` requests". That was wrong: before merging, four pulses of at most one stack promised at most 4 × 64 items, not the aisle's whole stock of that item, and since `availableStock` subtracts every open request's remaining amount, one clocked output could make every other station read "not in stock" for that item until its buffer drained.
  * **the queue caps** — a merge takes **no** new queue slot, so `maxOpenRequests` and `maxOpenRequestsPerOutput` can never refuse it. They are caps on the **item types** one station waits for, not on how often a player may click.
* **One trip, and what "in flight" means.** The planner serves one request with successive `RETRIEVE` jobs of up to `carryLimit` items (§7.1), so a merged request of 10 is **one** job carrying 10 when it is planned after the clicks. A job that is **already running** is never changed: its items are in the grabber, and `ReservationView#committedToRequest` tells the planner how much of the request that job already covers. An amount added while the crane is on its way is therefore served by the **next** trip — expected and physical, and at most one extra trip.
* **Persistence is unchanged** (§3.3.1): a merged request is a normal request with larger numbers, so the saved format (`Requests [{Id, Item, Requested, Remaining, Destination}]`) is untouched and no migration is needed. A world saved before M7 loads with its several requests for one item and destination (`restore` keeps every valid entry and never merges); the next request merges into the oldest of them, and the others are served one after another. Loading never throws.
* **Feedback:** `RequestResult` carries what this call granted (`granted()`), what the request waits for now (`pending()`) and whether it merged. `TerminalResultPayload` carries both numbers, so the terminal screen shows "Requested Andesite x1, waiting for 10" while the request grows, while "Open requests" stays **1** (§3.4.2, which also holds the width budget that line needs — it did not fit the row it first shared with the request count). Both numbers are on the wire, so a change to the payload's field order or to the `RequestRejection` ordinals needs a bump of `WareworksNetwork.VERSION` (M7 review fix: the version stayed "1" although both had changed, so an M6 client passed the handshake and then read `pending` as the refusal reason).
* **Goggles read "Items requested: 10 (requests: 1)"** — the lines themselves are unchanged, but "Delivered so far" now counts over the **whole life of the merged request** instead of restarting per click: a request that is topped up before it empties never leaves the queue, and `delivered() = requested − remaining` grows with it. That is correct for the request ("delivered towards what this station asked for"), and it is the number the terminal's "Waiting for N, delivered M" shows as well; it is no longer "delivered towards the amount I clicked for last". Left as is on purpose: the alternative (a second counter per top-up) would add saved state for a display line.
* **The input side needs no merging and never had this bug:** a `STORE` job takes `min(buffered count of the key, carryLimit)` from the live input buffer (§7.1), i.e. *everything* of that item key in the buffer, whatever number of slots or stacks it arrived in. Eight stacks of four iron are one trip of 32 (GameTest `storebatchesbufferedstacks`). Only separate item **types** need separate trips, because the handling head carries one key per job (§7.4). There is no request object on the store side, so there is nothing to merge: the amount is read from the world at planning time.
* **Tests:** JUnit `RequestQueueTest` (merging semantics, both clamps, queue position in both fairness cases, caps that a merge bypasses, merging into the oldest of several restored duplicates, deliveries and cancellations, overflow); GameTests `gametest.RequestBatchingGameTests`: `terminalclicksmergeintoonetrip` (ten clicks → one open request, one trip, ten items), `terminalinflightrequestmerges` (added while a job runs → at most two trips, nothing lost), `twoterminalskeeptheirownrequests` (per destination, older request first), `outputpulsesmergeintoonerequest` (redstone path, another item stays its own request), `outputpulsesstaybounded` (the output's own amount bound: pulses beyond it are refused with `REQUEST_FULL` and another station still gets the item), `storebatchesbufferedstacks` (the input side above). Each of the merge tests asserts the item conservation invariant on every tick. The wire form of the answer is covered by `terminalpayloadcodecs` (an accepted payload whose `pending` differs from its `amount`, and the last `RequestRejection` constant).
* The controller owns a `RequestQueue<ItemKey, BlockPos>`; destinations are world positions of output stations. `request(outputPos, key, amount)`:
  * `NO_CONTROLLER` unless the controller has an aisle and `outputPos` holds an **aligned warehouse output at one of its rack positions**. The check is live (one block entity lookup), so an output placed in the same tick already counts, and inputs, misaligned outputs or positions outside the aisle are refused.
  * Exact item identity: `ItemKey` of the filter stack (item + components), ADR-013.
  * **Always "up to" (M2 review fix):** the amount is clamped to the available stock, so the request asks for up to the filter amount. The output's board offers only that row (§3.2.1); there is no "exactly" request.
  * `NOT_IN_STOCK` / `OUTPUT_FULL` / `QUEUE_FULL` map the queue's rejections.
  * **Per-output cap (M2 review fix, addition):** at most `maxOpenRequestsPerOutput` (config, default 4) open requests per output station. Reason: a pulse clock on one output otherwise fills the whole queue (`maxOpenRequests`), and every other output of the aisle is refused until the crane works through the backlog. **Since M7** it caps the **item types** one output waits for (repeated requests for one item merge instead of taking a slot), and the same number bounds the merged amount: an output request may wait for at most `maxOpenRequestsPerOutput × requestAmount()` items (§7.2), so the protection against a pulse clock is unchanged in size.
* **Refinement: available = indexed count − reserved − remaining of the open requests for the item.** The design subtracts only reservations. Reason: reservations are made when a job is planned (M3), so without the open requests a second rising edge could promise the same stock again, and such a request could never be served and would block a queue slot. Now the second edge reports "not in stock" until new stock arrives. **Note for M3** (core answer: §7.4, `ReservationView#availableStock`): `reservedStock(ItemKey)` (the ledger formula since M3, §7.5) only returns reservations that do **not** back an open request's remaining amount, e.g. RETRIEVE reservations must not be counted twice.
* **Cancellation (addition):** the requests of an output station are cancelled when its record leaves the membership (broken, rotated away, replaced by another member). All requests are cleared when the aisle is lost (`NO_DOCK`) or the dock position or aisle direction changes, because their destinations belong to the old layout. **M2 review fix:** requests are also validated against the world after every membership reconcile and at every periodic re-link check: a request whose destination is loaded but no aligned output of the aisle (or outside it) is cancelled, one block entity lookup per distinct destination; unloaded destinations are kept. This covers an output that was placed, pulsed and removed before the controller recorded it (the live check in `request` accepts it, but no record is ever removed), and restored requests whose output record was unreadable. Reason: nothing can ever be delivered there, and dead requests would block the queue. Since M3 every such cancellation also aborts (before the pick) or reroutes (after it) a crane job serving the request and releases its reservations (§7.5, Cancellation).
* Requests are persisted with the controller (§3.3.1) and are not synced to clients; goggles show counts only (controller: open requests; output: its requests and requested items).

### 7.3 Completion and failure reports (crane → controller)
* `onPicked(job, pickedAmount)`: if picked < planned, release the reservation surplus.
* `onDelivered(job, deliveredAmount)`: release reservations, update request remaining, re-snapshot the touched locations.
* `onJobAborted(job, reason)`: release reservations. A request keeps its remaining amount.
* `planReroute(crane, item, amount, failedTarget)`: find a new target for items in the grabber (§8).

### 7.4 Implementation (M3, core logic)
Pure Java in `core.job`, generic over the key `K` and the location `L` (the controller uses `ItemKey` and `RackPosition`; rack coordinates come from a `Function<L, RackPosition>`, the identity there). JUnit: `TransportJobTest`, `ReservationLedgerTest`, `TravelTimeModelTest`, `JobPlannerTest`.

* **`TransportJob<K, L>`** (record): `id`, `type` (`JobType` `STORE` / `RETRIEVE`), `source`, `target`, `targetKind` (`core.warehouse.LocationKind`), `key`, `plannedAmount`, `requestId` (retrieve only), `picked`, `pickedAmount`, `deliveredAmount`. Progress creates copies (`withPicked`, `plusDelivered`, `withTarget`, `withoutRequest`); `heldAmount()` = picked − delivered.
  * **Deviation:** the design's `LocationRef {pos, x, y, side, kind}` is replaced by the generic `L` plus `targetKind`. The source kind follows from the type (`JobType.sourceKind()`); world positions and rack coordinates come from the controller's layout. The target kind is stored because reroutes change it (`JobType.allowsTarget`: store → storage or input, retrieve → output or storage).
  * All fields are plain values (UUIDs, enums, ints), so the job persists as is. The constructor validates and throws for inconsistent data; loaders skip such entries.
* **`ReservationLedger<K, L>`** (read-only: `ReservationView`): at most one `Reservation` per job and kind — `CAPACITY` at a target, `STOCK` inside a source, `TRANSIT` for held items on their way to an output station. O(1) aggregate queries; no amount is ever negative; releasing twice (`release`, `releaseJob`) is harmless; `adjust(jobId, amount)` sets every reservation of a job (0 releases). A seeded randomized test compares every aggregate with a recomputation.
  * **Derived from jobs:** `reservationsFor(job)` defines what a job reserves in each stage, `track(job)` applies it. Store before the pick: capacity (planned) at the target. Retrieve before the pick: stock (planned) at the source, with the request. After the pick: the held amount as transit (output target, with the request) or as capacity (any other target). Nothing held: nothing. `restoreFrom(jobs)` rebuilds the ledger from the persisted crane job(s). **Refinement of §3.3 ("ReservationLedger: persisted"):** the ledger is derived state of the persisted jobs and needs no save format of its own; a controller that adopts a crane's job rebuilds it (§8).
  * **Refinement of §7.3 `onPicked`:** after the pick the stock reservation ends entirely instead of only its surplus; the held amount continues as transit or capacity. Reason: the picked items have left the source, which is re-snapshotted after the transfer (§5); a stock reservation for them would subtract them a second time from both the location and the availability.
  * **M2 note (double subtraction):** `availableStock(key, indexed, openRemaining)` = `indexed − reservedStockNotBackingRequests − max(0, openRemaining − inTransitBackingRequests)`. Stock reservations that back a request are inside the request's remaining amount and are not subtracted again; items of a request that are in the head are inside the remaining amount but no longer indexed. The controller adopts this formula in its `availableStock` and returns `reservedStockNotBackingRequests` from `reservedStock(ItemKey)` (M3 content work). `detachRequest(requestId)` turns a cancelled request's reservations into unbacked ones; `committedToRequest(requestId)` tells the planner how much of a request its jobs already cover.
* **`CraneKinematics`, `CraneSpeeds`, `TravelTimeModel`:** `CraneKinematics.speeds(Params, rpm)` implements the formulas of `stacker-crane.md` §5 (sign ignored, NaN rpm → 0). `TravelTimeModel.ticksToCover(d, v)` = `max(1, ceil((d − ε) / v))` (0 without distance, `UNAVAILABLE` = `Long.MAX_VALUE` without speed) is exactly the number of `CraneMotion` steps (JUnit). `travelTicks` = the larger of X and Y, `armTicks` = `ceil(1 / va)`, `stopTicks` = 2 · arm + `transferTicks`, `tripTicks` = travel to the source + stop + travel to the target + stop. Sums saturate.
* **`JobPlanner<K, L>`:** `plan(PlannerInput)` → `PlanResult` (optional `PlannedJob` = job + estimated ticks, the `NoJobReason`s, the next input cursor); `planReroute(input, key, amount, type, failedTarget)` → optional `RerouteTarget`. The planner is pure: the controller supplies index, ledger view, locations, an `InsertEstimate` (default `fromSnapshots`) and simulated item handler calls (`LiveExtract`, `LiveInsert`), then reserves and assigns the returned job itself (§7.1 step 3). §7.1 is implemented as written (retrieve first for the oldest request, candidates and ranking, amount formulas, live validation with fall-through, store consolidation before travel time, round robin over inputs), with these **refinements**:
  * A request's need is `remaining − committedToRequest`, so a request that its jobs already cover is skipped.
  * A request that cannot be served now (output full or unavailable, not in stock) does not block younger requests; they are tried in age order. Reason: a full output would otherwise stall every other output and all store work.
  * If the first item type of an input fits nowhere, the input's other item types (slot order) and then the next inputs are tried; the cursor moves past the input that got the job. Reason: one unstorable item (e.g. a shulker box when all storage locations are shulker boxes) would otherwise block the input forever.
  * Reserved capacity is subtracted per location over all keys (conservative), as §7.1 writes `reservedCapacity(target)`.
  * **Grouping (M3 scenario hardening):** between consolidation and travel time, a storage location that holds nothing, or only keys of the same item type, ranks before one that holds other item types. The item type comes from `PlannerInput#itemType`; the controller passes `ItemKey::getItem`, so the item without its components. Reason: with only (a) and (b), every new item type went into the nearest location with room, so a hopper feeding cobblestone, ender pearls and a sword filled one chest with all three while eleven others stayed empty (`scenariofullloop`, §7.6). Grouping by item type rather than exact key keeps variants together (damaged tools, potions, enchanted books), so they do not take one empty location each. Locations holding other types are used once no other location accepts the item (live fall-through); consolidation of the exact key still comes first. Cost per candidate: one index lookup plus one type comparison per key stored there.
  * One run makes at most `PlannerInput.liveSimulationBudget` live calls (default `JobPlanner.DEFAULT_LIVE_SIMULATION_BUDGET` = 64); when the budget runs out, the result reports `BUDGET_EXHAUSTED` and the next run continues at the next input. Reason: bounded work per dispatch, e.g. a full warehouse of restricted inventories.
  * **Known refusals (M3 review fix):** with more refusing locations ranked ahead of an accepting one than the budget (e.g. 64 empty shulker boxes offered a shulker box, or locked modded storage), every run spent its budget on the same refusals, and a single input stalled for ever (its "next input" is itself). `PlannerInput#insertRefused` / `#extractRefused` name locations known to refuse a key right now; the planner skips them before the estimate, without a live call. The controller fills them from a `core.job.RefusalMemory` (§7.5), so every run gets past up to a budget of new refusals. The reroute's station fallback has a budget of its own, so storage candidates that used up the budget never hide an input or output that accepts the items.
  * **Store filters (M8, ADR-021):** `PlannerInput#storeFilter` answers `core.job.FilterMatch` (`DEDICATED` / `ALLOWED` / `UNFILTERED` / `REJECTED`) for a location and key. `selectStorage` — the one method every storing path uses, i.e. the store plan and both reroutes into storage — skips a `REJECTED` location **before** the capacity estimate and before any live call, so it consumes neither the live simulation budget nor a remembered refusal; `FilterMatch#storeRank()` is the **first** sort key of the candidate ranking, ahead of consolidation, item-type grouping and travel time. Retrieval never consults it. Skipping before the budget is load-bearing rather than an optimisation: a warehouse partitioned into many dedicated chests has by construction far more rejecting candidates than the budget (64), so charging them would reproduce the stall the "known refusals" fix above removed. Three **M8 review fixes**:
    * `ALLOWED` exists because Create's deny modes answer *true* for everything they do not list. Such a location accepts the item but was not dedicated to it, so it shares rank 1 with `UNFILTERED` instead of beating consolidation and grouping (§3.1 "Ranking").
    * The cheap `insertRefused` test now runs **before** the filter evaluation. Both still decide before the estimate and before any live call, so the ADR-021 property is unchanged; a location that is simply full no longer pays for a filter walk first.
    * `NO_MATCHING_FILTER` is reported instead of `WAREHOUSE_FULL` when **every** skip for that input was a filter mismatch (`JobPlanner.StoreSurvey`: nothing entered the ranking, at least one location was filter-rejected, and nothing was skipped for another reason). A location that may take the item but has no room still reports `WAREHOUSE_FULL`. **Deviation from the review's suggestion, recorded on purpose:** it arms the same `fullBackoffTicks` back-off. The review proposed not backing off ("a filter mismatch is not relieved by a retrieval, so backing off buys nothing"), but the opposite follows: it is *more* permanent than a full warehouse, and without the back-off the full candidate scan — longest exactly in the heavily partitioned warehouse that produces this reason — would run every `dispatchIntervalTicks` (5) instead of every 40 for ever. The defect the reason fixes is the misleading, masking goggle line, and that is fixed without the cost.
  * `NoJobReason` in priority order: `WAREHOUSE_FULL` (back off `fullBackoffTicks`), `OUTPUT_FULL`, `NOT_IN_STOCK`, `LOCATION_UNAVAILABLE`, `BUDGET_EXHAUSTED`, `NO_WORK`.
  * Reroute (§8 table): store leftovers → another storage location (consolidation, then travel from the crane) → an input buffer (travel) → none; retrieve leftovers → a storage location (same ranking) → another output station (travel) → none. **Deviation from the §8 table as first written (M3 scenario hardening):** storage comes before outputs. Retrieve leftovers only exist when their request was lost (output broken, turned or its request cancelled), and another output never requested them; it received them on top of its own request (`scenariotwooutputsfirstremoved`, §7.6). An output stays the last resort, so a full warehouse does not block the crane. A candidate must accept at least one item in the live simulation; the failed target is excluded only in the reroute right after it failed (**M3 review fix:** a `HOLDING` retry passes none, so a former target that was emptied or placed again at the same position is used; before, every retry excluded it and the crane could hold for ever, blocking the aisle); the reroute amount is what the target accepted (the rest is rerouted again after the drop).

### 7.5 Implementation (M3, dispatch and crane reports)
Classes: `content.controller.CraneDispatch` (package-private, owned by the controller), `WarehouseControllerBlockEntity` (reports, cancellation, goggles), `ControllerGoggleSummary`; the crane side is `stacker-crane.md` §4.2 and §6.1.

* **Dispatch** (§7.1) every `dispatchIntervalTicks`, after re-link, membership and snapshots. Skipped without a loaded linked dock and while `StackerCraneBlockEntity#canAcceptJob()` is false (dock chunk not ticking, loaded state not resumed yet, not idle, head not empty, paused, or no rotation). **M3 review fixes:** during the `fullBackoffTicks` back-off after `WAREHOUSE_FULL` only retrieves are planned (the planner gets no inputs; the round-robin cursor and the last planning result stay), because a waiting player's request also frees space and §7.1 puts RETRIEVE first; before, the whole dispatch was skipped (`scenarioretrieveduringfullbackoff`). A dock whose chunk is loaded but does not tick (a border chunk) accepts no job, because it would never run it and its reservations would stay frozen (`Level#shouldTickBlocksAt`). Cheap pre-check: without open requests and without a loaded input holding items, the result is `NO_WORK` and no planner input is built (one block entity lookup per input station).
* **Planner input**: crane pose and `currentSpeeds()`, `transferTicks`, `InventoryGrabber.carryLimitFor`, the read-only stock index and ledger, the open requests whose destination is a recorded output station (queue order), the storage records without shared-inventory aliases, input and output records, the buffers of the examined inputs, the round-robin cursor, `available` = rack position (for storage also the inventory position) loaded, `InsertEstimate.fromSnapshots`, and live simulations through `TransferContexts` (interface capability, input count, output insert). A failing foreign inventory simulates 0 and is logged at most once per `util.LogThrottle` interval (1200 ticks), so a second, different failing inventory is still reported (**M5 release audit**).
* **Refusal memory (M3 review fix, §7.4):** a storage location whose live simulation gives nothing (full, restricted, gone or failing; not an unloaded one) is remembered per key and direction (two `RefusalMemory` instances, insert and extract) and passed to the planner as `insertRefused` / `extractRefused`. An entry ends when the location is read again (`refreshLocation`, also for the location that counts a shared inventory), when it leaves the aisle, after `CraneDispatch.REFUSAL_MEMORY_TICKS` (1200, for inventories that change silently), or when `MAX_REMEMBERED_REFUSALS` (4096) is reached (the memory starts over). O(1) per candidate.
* **Result**: the input cursor advances. A job is tracked in the ledger and assigned (released again if the crane refuses it). Without a job the primary `NoJobReason` becomes the last planning result, and `WAREHOUSE_FULL` starts the back-off.
* **Reservations follow the crane's job**: every report re-tracks the reported job, and every dispatch interval and every re-link rebuilds the ledger from the linked dock's current job (`CraneDispatch#adopt` → `ReservationLedger.restoreFrom`). This adopts the job of a crane in front of a new or reloaded controller (§8) and corrects any drift; the ledger has no save format (§7.4). Reservations for requests the queue does not contain are detached, so they never count as backing a request.
* **Reports** (accepted from the linked dock only):
  * `onCranePicked(job)`: track (the stock reservation ends, the held amount continues as transit or capacity) and `refreshLocation(source)` for a storage source, so the index is right in the same tick.
  * `onCraneDelivered(job, target, delivered)`: a drop into the output station that is the request's destination counts (`deliverRequest`; only real drops count, §7.2); track; `refreshLocation(target)` for storage. Returns whether the request is still open; the crane detaches it otherwise.
  * `onCraneRerouted(job)`: track (capacity or transit moves to the new target).
  * `onCraneJobFinished`, `onCraneJobAborted`, `onCraneJobLost` (dock broken or cleared by a command): release the job; a request keeps its remaining amount.
  * `planReroute(job, failedTarget, amount)`: `JobPlanner#planReroute` with the same planner input from the crane's position; `failedTarget` is null for a hold retry. The job's own reservation is released while planning and tracked again afterwards (**M3 review fix:** it stands for the held items themselves, so a former target with room for exactly them never qualified again, `scenarioholdingtargetfreedagain`).
* **Controller link before reports (M3 review fix):** the link between dock and controller is not saved. A crane with a job but no linked controller lets the controller directly behind the dock link it at once (`WarehouseControllerBlockEntity#relinkNow`; on the crane's first tick after loading, then at most every 20 ticks). Before, a dock that ticked before its controller after a restart (ticker order is arbitrary), or whose controller sat in a chunk that is loaded but does not tick, delivered without reporting: the request kept its amount and was served again, and the index missed the transfer (`cranereloadduringdropcountsdelivery`). A cancellation that reaches a crane whose loaded state is not resumed yet resumes it first.
* **Availability** (M2 note, §7.4): `availableStock(key)` = `ReservationView#availableStock(key, index count, remaining of the open requests)`; `reservedStock(key)` = `reservedStockNotBackingRequests(key)`. GameTest `craneretrieveendtoend` asserts it every tick while a request is served, before and after the pick.
* **Cancellation**: every path that removes requests (output record removed, orphan pruning, `cancelRequest(id)`) calls `CraneDispatch#onRequestsCancelled`: `detachRequest` for each, and a crane job serving one of them gets `cancelJob` (abort before the pick; after it the held items are rerouted: back into storage, else another output station). Losing the aisle (`NO_DOCK`, dock moved or turned) clears requests and reservations; a crane that is no longer linked keeps its job and items and continues without controller.
* **Goggles**: the controller summary adds the linked crane's `CraneGoggleInfo` ("Stacker Crane:", "Status: …", "Paused: …", "Storing Iron Ingot x32", "From A-01-00R to A-01-05L"; no held list) and "Last planning: <reason>". The summary stays bounded (enum names, one item id, rack positions). Output stations add "Delivered so far: N".
* **Reserved amounts in interface goggles** (moved to M4, done): §3.1.1.
* **GameTests** (`gametest.CraneJobGameTests`; aisle on `aisle_16x10x7`, creative motor below the dock at 128 RPM, item conservation over chests, station buffers, head and dropped items checked every tick where noted):
  * `cranestoreendtoend` (a): 32 iron ingots from the input end in the chest behind the interface; input empty, crane idle with an empty head, no reservation left, stock index updated at once.
  * `craneretrieveendtoend` (b): filter diamond x10 and a redstone pulse at the output; 10 diamonds in the output buffer, 10 left in the chest, request removed; availability checked every tick.
  * `cranestoreconsolidates` (c): the farther location that already holds iron wins over the nearer empty one.
  * `cranespillreroutes` (d): the target fills up while the crane travels; 10 fit, 22 are rerouted to the other location; totals conserved.
  * `cranenorotationpauses` (e): without rotation no job starts and nothing moves; losing rotation mid-job freezes pose, phase, timers and head, and no items move; with rotation the job completes.
  * `cranepersistencemidjob` (f): dock and controller saved during `TRAVEL_TO_TARGET` with items in the head; detached copies restore job, head, phase and pose, and a controller copy rebuilds the reservations; the client packet stays bounded; then both block entities are replaced in the world by copies loaded from the saves (`Level#setBlockEntity`), the new controller adopts the job and it completes.
  * `cranebrokenmidjobdropshead` (g): breaking the dock while carrying requested diamonds drops exactly the head contents at the dock, the reservations are released and the request keeps its remaining amount.
  * `cranewaitsforfulloutput` (h): the output fills up while the crane travels; what fits is dropped and counted, the crane waits and retries without moving items, and delivers the rest after automation empties the buffer.
  * M3 review fixes and gaps (i):
    * `cranereloadduringdropcountsdelivery`: dock and controller are replaced by copies from their saves one tick before the drop; the dock ticks first, links the controller, the delivery counts, and nothing is retrieved twice.
    * `craneheadsurvivesthrowinginventory`: a test inventory throws for one slot; a pick keeps what the earlier slots handed out, a drop counts what went in before the failure; nothing is lost, duplicated or spilled.
    * `craneclearcontentreleasesjob`: `clearContent` on a carrying dock empties the head without drops, ends the job, releases the reservations; the request keeps its amount.
    * `cranesourceremovedbeforepickaborts`: the source interface is broken while the crane travels to it; the job aborts with nothing held, its reservations are released, and a new job serves the request from the other location.
    * `cranezeropickaborts`: a player empties the source during the trip; the zero pick aborts, no job starts while nothing is in stock (`NOT_IN_STOCK`), and the request is served after the refill.
    * `cranerequestcancelledbeforeandafterpick`: cancelling before the pick aborts the job with nothing reserved; cancelling while carrying puts the items back into storage.
    * The overstressed pause has no GameTest of its own: it takes the same stopped-speed path as `cranenorotationpauses` (only the goggle reason differs), and one crane cannot overstress a creative motor.

### 7.6 Scenario GameTests (M3 hardening)
`gametest.CraneScenarioGameTests` plays real player setups end to end, plus the §8 cases a player runs into. Every scenario checks **exact item conservation every tick** with `gametest.ItemCensus`: every item in the test area by exact identity (item and components), summed over all inventories with an item capability (chests, hoppers), all station buffers, all crane heads and all item entities. The expectation only changes in the step where the test plays the player (filling a hopper or input, taking a stack out of a chest). Layouts come from `gametest.AisleFixture`, one aisle built like a player builds it (creative motor, dock, rails, controller, chests behind aligned interfaces, stations); `CraneJobGameTests` uses it too. Template `aisle_pair_16x10x13` holds two parallel aisles for side-by-side comparisons.

* `scenariofullloop` (1): a hopper feeds 64 cobblestone, 16 ender pearls and two iron swords with different damage into the input of an aisle with twelve storage locations (positions 2-4, two levels, both sides). Everything is stored: each key is consolidated in one location, each item type has a location of its own, both swords share theirs, and the stock index agrees with the chests. Three filter + redstone pulses at an output one level up request cobblestone, pearls and one of the swords. Exactly those items arrive (that sword with its damage component), and a hopper below the output moves them into a chest. The other sword stays stored and indexed.
* `scenariothroughputrpm` / `scenariothroughputlayout` (2): the same store job in two identical aisles takes fewer ticks at 256 RPM than at 32 RPM. A location at position 12, level 4 takes longer than one at position 1, level 1. Every measured job matches `TravelTimeModel.tripTicks`, plus at most the one-tick `COMPLETE` phase.
* `scenarioretrieveduringfullbackoff` (3b, M3 review): while the controller backs off after `WAREHOUSE_FULL`, a request for stocked cobblestone is planned and served at once; the input keeps its iron (still no empty slot).
* `scenariowarehousefull` (3): both storage locations are full. The input keeps its items, the last planning result is `WAREHOUSE_FULL`, the controller backs off, and for 130 ticks no job starts and the crane does not move. A player takes one stack out of a chest; exactly one job then stores the items.
* `scenariorequestclampedandlatestock` (4): a request for 32 of 10 diamonds is clamped to 10 and served. Requests are refused with "not in stock" while nothing is in stock, while new stock waits in the input, and while it is in the crane's head (§7.2: only indexed stock is available). Refused requests are not remembered, so nothing is retrieved when the stock arrives. A pulse after the arrival is accepted and clamped to the new stock.
* `scenariotargetremovedreroutes`, `scenariotargetremovedreturnstoinput`, `scenariotargetremovedholdsuntillocation` (5): the target interface is broken while the crane carries items to it.
  * With another location, the same job delivers there.
  * Without one, the items go back into the input buffer and no job starts again (`WAREHOUSE_FULL`).
  * With the input broken too, the crane holds the items: hold retries move nothing and the wrench cannot turn the dock. The interface placed again at the failed target's position is used by the next retry (M3 review: before, every retry excluded that position).
* `scenarioholdingtargetfreedagain` (5b, M3 review): the only storage location fills up during the trip and the input is broken, so the crane holds the iron; a player frees room for exactly the held amount there, and the next hold retry stores it (no exclusion on retries, the crane's own reservation not counted).
* `scenariocontrollerbrokenmidjob` (6): with the controller broken mid-job, the crane finishes the job and starts nothing new; a new controller plans again. When that controller is broken together with the target, the crane holds. A third controller adopts the job (reservations equal `ReservationLedger.reservationsFor(job)`) and reroutes the items to the other location.
* `scenariodockrotationblocked` (7): a survival player's wrench (Create `WrenchItem` through `GameTestHelper#useBlock`) cannot turn the dock before the pick or while carrying. Once the crane is idle and empty the wrench turns it, and the controller loses the dock (`DOCK_MISALIGNED`: it still stands in front, only turned).
* `scenariotwooutputsfifo`, `scenariotwooutputsfirstremoved` (8): two outputs request diamonds: first 20 of 24 spread over two locations, then 10, clamped to 4 while the first job carries diamonds. The older request is served completely first (two jobs), then the younger one, and no output ever holds more than it requested. In the second variant the first output is broken while the crane carries its diamonds: its request is cancelled, the diamonds go back into storage, and the second output receives exactly its 4.

**Found and fixed:**
* **Over-delivery to another output (bug).** Retrieve leftovers whose request was lost (output broken or turned, request cancelled) were rerouted to another output station first. That output got items it never requested, and its own request was then served on top (`scenariotwooutputsfirstremoved`: 12 diamonds into an output that had requested 4). Fix: `JobPlanner#planReroute` puts retrieve leftovers back into storage and uses another output only as the last resort (§7.4, §8 table).
* **Item types mixed in one location (planning refinement).** With the §7.1 ranking (consolidation, then travel time), every new item type went into the nearest location with room. `scenariofullloop` stored cobblestone, pearls and the sword in one chest while eleven stayed empty. Store candidates now rank locations that are empty or hold only the item's type before locations holding other item types (§7.4).

Checked without a change: clamping and refusal of requests (§7.2), the `WAREHOUSE_FULL` back-off without oscillation, reroutes to storage or input and holding (§8), finishing and holding without a controller and adoption by a new one, the rotation guard, FIFO over several outputs, and the travel time model against real job durations.

## 8. Robustness rules (item conservation)

**Invariant:** every item is in exactly one of: source inventory, crane grabber (persisted), target inventory, or an `ItemEntity` in the world.

| Situation | Behaviour |
|---|---|
| Source empty / less than planned at pick time | Pick what is there (real extract). If 0, abort the job after retracting. |
| Target full / partially full at drop time | Insert what fits. The remainder stays in the grabber, then **reroute**. |
| Target interface or inventory removed | Reroute. |
| Output station full | Wait at the output (`WAITING_FOR_TARGET`, retry every `retryTicks`, default 20). A job that serves no request at that output (its request was lost, or it was rerouted there) reroutes instead (M3 review: otherwise it could wait for ever). |
| Reroute for STORE leftovers | Another storage location **whose filter accepts the items**, else any input station buffer, else `HOLDING`. Storing leftovers is still storing, so a dedicated location never receives what it rejects. `HOLDING` is the documented, recoverable outcome when nothing accepts them (the crane's goggles read "Holding items, no target found" and list the items, and every hold retry re-tries every location with no exclusion); M8 adds one more way to reach it, and that is deliberate — ignoring filters as a last resort would break the binding rule that a dedicated location never gets what its filter rejects. |
| Reroute for RETRIEVE leftovers | A storage location, else another output station, else `HOLDING` (M3 scenario hardening: storage first, §7.4). **Store filters are advisory here (M8 review fix):** a location whose filter rejects the items is ranked **last** instead of dropped. These items already came *out* of the warehouse, so putting them back is not choosing where new items live — and without this a location re-dedicated while its stock was inside could not take that stock back, so a fully partitioned aisle whose output failed mid-job would hold for ever, re-opening the failure the M3 review fix above closed. JUnit `aRetrieveRerouteReturnsLeftoversToTheLocationTheyCameFrom`. |
| `HOLDING` | Keep the items, show them in goggles, retry reroute every `holdRetryTicks` (default 40). A retry may choose the target that failed before (M3 review). |
| Controller removed during a job | The crane finishes the job if the target is valid, otherwise it holds. A new controller adopts the crane's job and rebuilds its reservations. |
| Crane (dock) broken | Grabber contents drop at the dock position. The controller aborts the job and releases reservations. |
| Needed chunk not loaded | The crane waits in the current phase and retries. |
| No rotation / overstressed | Motion and transfer timers pause. |
| Server restart | Controller (locations, ledger, requests) and crane (phase, positions, job, grabber) are persisted and resume. |
| Aisle shrinks while the crane is beyond the new length | A location outside the new geometry counts as missing: before the pick the job aborts, after it the held items are rerouted into what is left of the aisle. A resting crane (`IDLE`, `HOLDING`, `REROUTE`) clamps its target back into the aisle and drives home (`stacker-crane.md` §3). The inventory left outside keeps its items and leaves the index. |
| Dock turned or replaced by a command while the crane carries items | The controller loses its aisle (`DOCK_MISALIGNED` while the turned dock still stands in front, `NO_DOCK` when it is gone) and clears records, requests and reservations; the crane keeps its job and its items and holds them, because no controller answers a reroute. Once an aisle exists again, a controller adopts the job and it finishes. A survival wrench refuses the rotation in the first place (`scenariodockrotationblocked`). |
| Two aisles sharing a rack plane | Allowed (§4). Both controllers record the shared members and both index the inventory, so together they can promise more than it holds. The real extract results are authoritative: the second crane simply picks less, and nothing is duplicated or lost. A shared station always resolves to the same controller (nearest dock, §4). **Block state on a shared plane has one owner** (M10 review fix): the warehouse terminal is the only member whose state a controller writes, and two *parallel* aisles want opposite intake ports there, so only the aisle `WarehouseRegistry#ownsMemberState` names — nearest dock, then the lower controller position, never alignment — may write it. The terminal keeps that aisle's port, is a normal output member of it, and is counted under "Misaligned blocks" by the other one, like every other member on a shared plane; its goggles still show the address of the owning aisle. Without the owner rule the two controllers rewrote the block on alternating ticks for ever (GameTest `terminalportowneronasharedrackplane`). |
| A crane speed factor is 0 in the server config | Every crane of the server is held still with the pause reason `SPEED_FACTOR_ZERO` and one `WARN` naming the keys, instead of one axis stalling mid-trip while the others move (`stacker-crane.md` §4.2). |
| Reroute for SUPPLY leftovers (M11) | Back into **storage only**, never to an output station: nobody requested those ingredients at a station, and an output would hand a player items they never asked for. A rejecting store filter is advisory here, as for a retrieve reroute — the items already left the warehouse. Else `HOLDING`, as always. |
| Production station broken, or a production order cancelled or timed out (M11) | The order ends, releases every reservation and gives its backing request the unproduced amount back (`RequestQueue#reduce`, which is **not** `deliver`: nothing arrived, so "delivered so far" must not grow). **No item is invented and none is taken back**: ingredients already dropped into the station stay there, and ingredients the machine already took are gone from the warehouse's point of view (§3.5.4). |
| The result of a production order never arrives | The order times out after `productionOrderTimeoutTicks` of no progress; its ingredients stop being promised and its request stops waiting. The items the machine swallowed are not recovered, which the order, the screen and §3.5.4 all state. |

Every extract and insert uses the **real result** of the `IItemHandler` call. Never assume that a simulated result will happen.

**Implementation (M3, core logic):** the crane's side of this table is `core.crane.CraneStateMachine` (`stacker-crane.md` §4.1): a zero pick aborts after retracting; leftovers at a storage location or input buffer are rerouted, then held with a retry every `holdRetryTicks`; a full output is waited for with a retry every `retryTicks`; a missing target reroutes held items (before the pick it aborts the job); a pause freezes motion and timers. Held amounts change only through real pick and drop results, and a job with held items is never aborted. `CraneConservationPropertyTest` drives seeded random event sequences (partial and failed transfers, unanswered effects, reroutes, cancellations, pauses, players moving items) and checks after every step that inventories + head (± player moves) stay constant and that the machine's held amount equals the real head.

**Implementation (M3, content):** the world side of this table (`stacker-crane.md` §4.2, §7.5):
* *Source empty or short*: the real pick result counts; 0 aborts after retracting (`ZERO_PICK`) and the controller releases the job.
* *Foreign inventory fails* (M3 review): storage transfers make one handler call per slot and stop at a failure, keeping exactly what earlier calls moved (`craneheadsurvivesthrowinginventory`).
* *Target full*: `InventoryGrabber#drop` inserts what fits, the rest stays held and is rerouted through `planReroute` (`cranespillreroutes`). At an output station a simulated insert of one item decides "full" before anything is touched; partial drops count for the request (`cranewaitsforfulloutput`).
* *Target or source removed*: the periodic location check or the transfer itself reports it; before the pick the job is aborted, after it the items are rerouted.
* *Controller removed*: the crane continues; a reroute has no answer, so it holds and retries. A new controller adopts the job and rebuilds the reservations (`cranepersistencemidjob` replaces the controller block entity in the world).
* *Dock broken*: `destroy()` drops the head at the dock with `Containers.dropItemStack`, the controller releases the job, requests keep their remaining amount (`cranebrokenmidjobdropshead`). Commands that replace the dock (`Clearable`) empty the head without drops and release the job (`craneclearcontentreleasesjob`).
* *Chunk not loaded*: the crane pauses (`CHUNK_NOT_LOADED`: the aisle column under it or its current stop); transfers at unloaded locations get no answer and are repeated. A dock chunk that does not tick does not run at all (ADR-013) and gets no job.
* *No rotation / overstressed*: pause with frozen motion and timers (`cranenorotationpauses`).
* *Server restart*: the dock saves state, job and head; the controller rebuilds the reservations from the job on re-link. A loaded job always follows the real head; items of other keys are dropped at the dock. The crane links its controller before it reports anything (§7.5).

### 8.1 Implementation and verification (M5, robustness)

The table above is covered by automated tests. What only a running game can reach is covered by the dev harness, because GameTest areas are force-loaded and a GameTest server never quits to a title screen.

* **GameTests** (`gametest.RobustnessGameTests`, each with an `ItemCensus` of the whole test area **on every tick**):
  * `craneaisleshrinkswithhelditems`: the rails are removed while the crane is beyond the new length carrying 32 iron. The target outside the geometry becomes missing, the items are rerouted to the location that is still inside, the crane comes back inside the aisle, the chest outside keeps its single seed item and leaves the index.
  * `cranedockturnedawaywhileholding`: the dock is turned by a command while the crane carries items. The block entity and its items survive the state change, the controller goes to `DOCK_MISALIGNED` (the dock is still in front, only turned) with no records and no reservations, the crane keeps its job and holds the items for at least 100 ticks (more than two hold retries), and turning the dock back lets the controller adopt the job and store the items.
  * `twoaislesstayindependent` (template `aisle_pair_16x10x13`): neither controller records the other's storage location, neither indexes the other's items, `WarehouseRegistry.findController` resolves each interface to its own controller, a request for an item only the neighbour stocks is refused with `NOT_IN_STOCK`, and two requests submitted in the same tick are each served from their own aisle.
  * `twoaislessharearackplane`: two cranes facing each other on one rail line reach the same storage location. Both controllers record it and both index its 40 iron, and both accept a request for 32. The test asserts what must never break: the census stays at 40 items, the chest ends empty, and the two outputs together hold exactly 40.
  * `cranetargetinventorybrokenmidjob`: the **inventory** behind the target interface is broken while the crane carries 32 iron (the interface itself stays, so the location resolves as missing through the empty attached handler, not through a missing member). With the input broken as well the crane holds the items, the interface stays a storage location, and an inventory placed behind it again is used by the next hold retry. Added in M5: the design names crane, interface **and** inventory, and only the first two were covered.
  * Config extremes (§9): `configzerospeedfactorpausescranes`, `configtinystationbuffers` (one buffer slot per station), `configdispatchintervalextremes` (interval 1 and 200), `configaisleandmastlimits` (`maxAisleLength` and `maxMastHeight` at 1, `maxMastHeight` at its maximum of 64, and the proof that a lowered `maxMastHeight` is reversible). The default rail cap is covered by `cranerailcap` on the 48-rail template; the top of the `maxAisleLength` range (128) is not exercised by any test.
  * **Config tests get one batch each.** The tests of a batch run at the same time, so a test that changes a global config value would change it for its neighbours; batches run one after another. `gametest.ConfigOverrides` applies an override with `ModConfigSpec.ConfigValue#set` plus `clearCache()` (needed because `set` does not update the cache of `worldRestart` values) and an `@AfterBatch` method per batch restores every override, also after a failure.
* **Dev harness scenario** (`dev.wareworks.dev.RobustnessVisualScenario`, `./gradlew runRobustnessTest`, ADR-014): one aisle built 512 blocks from the world spawn (the spawn keeps about 11 chunks permanently loaded, so an aisle at spawn could never unload), with a creative motor, 6 rails, an input and ten storage locations. After the first job starts it runs three phases, and after **every** phase it counts every item of the scene (`dev.SceneItemCensus`: inventories, station buffers, handling head, dropped item entities) and logs one `robustness PASS` or `robustness FAIL` line. A census runs only once **every chunk the census box touches** is loaded and refuses to count otherwise (**M5 review fix**: the box is inflated around the aisle and spans four chunks, so skipping unloaded positions could have reported a chunk-loading race as a lost item):
  1. *chunk round trip*: the camera flies from (512, 512) to (1500, 1500), about 1400 blocks — far beyond the client's 8-chunk view distance, but close enough that the trip does not generate and save a large amount of new terrain. It waits until the dock's block entity is really **removed** and the position is no longer loaded (`isLoaded` alone would flip while the same block entity still waits in the unload queue, which would prove nothing), stays away 100 ticks and comes back. The phase then asserts that the dock is a **different** block entity, read from the save; the controller becomes ready again and the interrupted job finishes.
  2. *save, quit and rejoin*: `saveEverything` mid job, then back to the title screen (which stops the integrated server) and `WorldOpenFlows#openWorld` on the same world; the resumed job finishes.
  3. *blocks broken at defined moments*: the controller is broken while the crane carries items (the crane finishes the job without it), a controller is placed again, and then the **dock** is broken while the crane carries items, which drops the head at the dock as item entities.
  A FAIL throws, so the harness writes a crash report and the Gradle task exits non-zero. The logs are the evidence; the single screenshot per pass only documents the end state.
* **Intentional behaviour that surprises players** (repeated in the manual checklist):
  * Lowering `aisle.maxMastHeight` shortens the mast of every crane at once, because the height is clamped **when it is read**. The stored value is not touched, so raising the limit again brings the player's own height back (**M5 review fix**: the clamped number used to be written back into the saved scroll value, which lost the setting for good, on every dock in a loaded chunk, within one geometry refresh).
  * The "Mast Height" value box takes its range from the config at a geometry refresh, so a raised `maxMastHeight` becomes settable only after the next refresh (at most `geometryRefreshTicks`, 2 s by default).
  * Two aisles that share a rack plane both count the shared inventory in their own "Items stored". That is not a duplication of items, only of bookkeeping; every transfer uses the real handler result.
  * An inventory that falls outside a shrunken aisle keeps its items but disappears from the controller's index and from every address, until the aisle reaches it again.
  * A crane whose aisle is gone holds its items indefinitely ("Holding items, no target found") instead of dropping them. They come back with the aisle, or drop at the dock when the dock is broken.

## 9. Configuration (server config)

| Key | Default | Meaning |
|---|---|---|
| `maxAisleLength` | 32 | rail count cap |
| `maxMastHeight` | 16 | mast height cap |
| `stressImpact` | 4.0 | SU per RPM of the crane |
| `travelBlocksPerTickPerRpm` | 1/384 | X speed factor |
| `liftBlocksPerTickPerRpm` | 1/512 | Y speed factor |
| `armExtendPerTickPerRpm` | 1/192 | arm speed factor (fraction of full extension) |
| `maxBlocksPerTick` | 1.0 | hard speed cap per axis |
| `transferTicks` | 10 | duration of pick/drop animation |
| `grabberStacks` / `grabberMaxItems` | 1 / 64 | carry limit |
| `inputBufferSlots` / `outputBufferSlots` | 9 / 9 | station buffers |
| `terminalBufferSlots` | 9 | buffer of a warehouse terminal (M6) |
| `maxTerminalRequestAmount` | 1024 | largest amount one terminal request may wait for, before the controller clamps it to the available stock (M6). Since M7 it bounds the **merged** amount of repeated clicks for one item, not a single click (§7.2) |
| `maxTerminalStockEntries` | 512 | item types a terminal reports in one stock snapshot, so a huge warehouse cannot produce an unbounded list for the screen (M6). It bounds the **payload**, not the pass over the index (§3.4.1); the types with the most items are reported and the screen shows how many were left out |
| `snapshotIntervalTicks` | 10 | round-robin reconciliation |
| `dispatchIntervalTicks` | 5 | controller planning cadence |
| `geometryRefreshTicks` | 40 | rail recount cadence |
| `retryTicks` / `holdRetryTicks` / `fullBackoffTicks` | 20 / 40 / 40 | retry cadences |
| `maxOpenRequests` | 16 | request queue cap |
| `maxOpenRequestsPerOutput` | 4 | open requests per output station (M2 review addition), one per item type it waits for. Since M7 it also bounds the merged amount of repeated pulses: one output request may wait for at most this many times its filter amount (§7.2) |
| `maxSnapshotsPerTick` | 4 | queued storage location reads per tick: joins, content hints, load verification (M2 review addition) |
| `productionBufferSlots` | 9 | buffer of a warehouse production station (M11, world restart) |
| `maxProductionPatterns` | 4 | pattern slots of one production station; a pattern is a 3x3 grid plus one result (M11) |
| `maxProductionOrders` | 8 | production orders one controller runs at the same time; each promises its ingredients (M11) |
| `productionOrderTimeoutTicks` | 6000 | ticks an order may make no progress before it gives up (5 minutes). Every delivery, state change and arriving result pushes the deadline out, so only a genuinely stuck order times out (§3.5.3) |

Implementation (M1, `config.WareworksConfig`): fractions are stored as doubles (`1/384 = 0.0026041666…`). The TOML file groups the keys into sections, so the full path of a key is `<section>.<key>`:

| Section | Keys | Ranges |
|---|---|---|
| `aisle` | `maxAisleLength`, `maxMastHeight`, `geometryRefreshTicks` | 1–128, 1–64, 1–1200 |
| `crane` | `stressImpact`, `travelBlocksPerTickPerRpm`, `liftBlocksPerTickPerRpm`, `armExtendPerTickPerRpm`, `maxBlocksPerTick`, `transferTicks`, `grabberStacks`, `grabberMaxItems` | 0–1024, 0–1, 0–1, 0–1, 0.01–4, 1–200, 1–27, 1–1728 |
| `stations` | `inputBufferSlots`, `outputBufferSlots`, `terminalBufferSlots`, `productionBufferSlots` (all world restart), `maxTerminalRequestAmount`, `maxTerminalStockEntries`, `maxProductionPatterns` | 1–27 each; 1–65536; 16–4096; 1–8 |
| `controller` | `snapshotIntervalTicks`, `dispatchIntervalTicks`, `retryTicks`, `holdRetryTicks`, `fullBackoffTicks`, `maxOpenRequests`, `maxOpenRequestsPerOutput`, `maxSnapshotsPerTick`, `maxProductionOrders`, `productionOrderTimeoutTicks` | 1–1200, 1–200, 1–1200, 1–1200, 1–1200, 1–256, 1–256, 1–64, 1–64, 200–72000 |

File: `<instance>/config/wareworks-server.toml`, overridable per world in `<world>/serverconfig/`. Read values only through the typed getters, which fall back to the defaults while the config is not loaded.
