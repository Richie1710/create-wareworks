# Hand Truck (Sackkarre)

> Issue [#37](https://github.com/Richie1710/create-wareworks/issues/37). Decision and reasons: **ADR-070** in
> [architecture.md](architecture.md). Not a part of the warehouse: a tool for moving a full container by hand.

## 1. Use

* **Load:** right-click a chest, trapped chest or barrel with an **empty** hand truck. The container goes onto the truck
  with everything in it: its items, its custom name, its lock and, for a loot chest nobody has opened yet, the loot
  table and seed (the loot is rolled when someone first opens it after unloading). The chest does not open, nothing
  drops, and every screen open on it closes.
* **Unload:** right-click a surface with the **loaded** truck. The container is placed by the normal placement rules,
  facing you, and gets back exactly what it held. Where it cannot stand (no room, a block in the way, a protected
  area) the truck stays loaded and the action bar says why.
* **Double chest:** only the clicked half goes onto the truck, with its own 27 slots; the other half stays as a single
  chest. Set down next to a single chest of the same facing, a chest joins it as a placed chest would.
* The loaded truck is called "Hand Truck with &lt;container&gt;" and its tooltip names how many stacks are inside (or
  "Unopened loot"). It shows the container on its nose plate in the hand, in third person and as an icon.

Recipe (shaped): a shaft over andesite alloy over a cogwheel, an iron sheet and a cogwheel. Creative tab: last, after
the Warehouse Home Point. Ponder: `hand_truck/carrying` (load, walk slowly, set down).

## 2. What can be carried

| Tag | Default | Meaning |
|---|---|---|
| `wareworks:hand_truck_carriable` | `#c:chests`, `#c:barrels` | blocks the truck may take (chest, trapped chest, barrel, and every modded chest or barrel in those tags) |
| `wareworks:hand_truck_denied` | `#c:chests/ender`, `#minecraft:shulker_boxes`, `#c:relocation_not_supported`, `#create:non_movable` | wins over the first tag. Every Wareworks block with a block entity (all but the rail) is in the last two; the rail is neither carriable nor a container |

On top of the tags the server requires a vanilla `Container` block entity and a breakable block, so a Create vault, a
toolbox or a depot is refused even when a pack tags it. A block the truck does not take is simply used as usual: an
empty truck on an ender chest opens it.

## 3. The penalty

Only while a **loaded** truck is anywhere in the inventory (main slots, armour, off hand). An empty truck costs
nothing. Creative and spectator players are never slowed.

* **Half walking speed** (`handTruck.speedMultiplier`), applied to the movement input like sneaking, so the field of
  view does not narrow. The `handtruck` visual scenario measures 0.108 blocks per tick against a walking speed of 0.216.
* **No sprinting** (`handTruck.preventSprinting`): the forward input is capped just below what a sprint needs, so a
  sprint can neither start nor continue.
* **No gliding** (`handTruck.preventElytra`): an elytra glide is stopped every tick.

Under water a swim sprint can still start (vanilla needs only some forward input there); it is still halved. A ridden
horse is slowed with its rider, a boat is not.

## 4. A loaded truck stays with you

This is what keeps every item where it belongs and keeps a full chest out of other containers:

| Attempt | What happens |
|---|---|
| Clicking any block with it (a depot, a deployer, a station) | the truck's own click wins: it unloads, or says why it cannot |
| Putting it into a chest, crafter, anvil or any other menu slot (also the 2x2 crafting grid) | it comes straight back into your inventory, "A loaded hand truck stays with you" |
| A shulker box, a bundle, a backpack that follows the convention | refused (`canFitInsideContainerItems`) |
| The drop key | refused, "Set it down with Right-Click" |
| Throwing it out of an open screen | it comes back into your inventory; with a full inventory it tips over |
| Right-clicking an entity (item frame, armour stand, donkey, villager) | nothing happens |
| A second empty truck on a container while one is loaded | refused, "You are already carrying a loaded hand truck" |
| Dying (without `keepInventory`) | it tips over: the contents, the container as an item (with its name or unopened loot) and the empty truck |
| Dying or leaving the game with it on the cursor of an open screen | it tips over where you stand, the same way |

A loaded truck that becomes an item entity anyway tips over on its first tick, before any hopper or funnel can see it.
A Deployer or any other fake player cannot load a truck.

Setting a container down checks the load again: a creative client can send a truck with any data, so the carried block
must still be carriable by tag, its block entity a vanilla `Container`, and data only an operator may set (a command
block's) is never placed for anyone else.

Loading also needs the right to break the block there: spawn protection, adventure mode and protection mods (through
NeoForge's break event) refuse it with "You may not take this here". Mods that count broken blocks see that event as a
break.

## 5. Configuration (server config, section `handTruck`)

| Key | Default | Range | Meaning |
|---|---|---|---|
| `speedMultiplier` | 0.5 | 0.1–1.0 | walking speed while loaded, as a fraction of the normal walk; 1.0 switches it off |
| `preventSprinting` | true | | no sprint while loaded |
| `preventElytra` | true | | no gliding while loaded |
| `maxLoadKiB` | 512 | 16–1024 | largest save data one carried container may have; a fuller one is refused with "Too much inside to carry" |

## 6. Limits

* A storage-mod terminal that inserts the cursor stack through its own packets can take a loaded truck; nothing is lost,
  it is then simply stored as an item.
* If the mod of a carried block is removed, the load is lost, as that mod's chunks would be.
* `Block#setPlacedBy` is not called when a container is set down.
* A modded chest without an item renderer shows nothing on the truck (cosmetic only).
