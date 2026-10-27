# Computers (CC: Tweaked)

> Issue [#9](https://github.com/Richie1710/create-wareworks/issues/9). Decision and reasons: **ADR-074** in
> [architecture.md](architecture.md); what a computer request is for the warehouse: §7.7 of
> [warehouse-system.md](warehouse-system.md). Optional: without CC: Tweaked nothing here exists and nothing changes.

## 1. Setup

* Install **CC: Tweaked** for Minecraft 1.21.1 NeoForge, **1.116.1 or newer** (1.116.1 is the version Wareworks is built
  against and the one Create 6.0.10 is built against; the API it uses is unchanged in 1.120.2, whose signatures were
  compared, but only 1.116.1 has been run). Wareworks never requires it, on
  the client or the server.
* Place a computer next to a **Warehouse Terminal**, or connect a terminal to a computer through a wired modem (right-
  click the modem on the terminal to attach it). Any terminal of any warehouse works, as far away as the cable reaches.
* In Lua the terminal is a peripheral of type **`wareworks_terminal`**:

  ```lua
  local terminal = peripheral.find("wareworks_terminal")
  ```

* The server config switch `computers.computerAccess` (default `true`, `wareworks-server.toml`) turns the peripheral's
  methods off: every call then fails with `computer access is disabled on this server`.

Only the terminal is a peripheral. The warehouse controller is not (a request needs a destination, and the terminal is
one), and every other Wareworks block keeps CC: Tweaked's own behaviour: a block with an item inventory, such as a rack
bay or a station, is a generic `inventory` peripheral as before. CC's `pushItems`/`pullItems` on those are CC moving
items between neighbouring inventories, not the warehouse; the warehouse's stock index notices such changes like any
other change made from outside.

## 2. What a computer can and cannot do

* **Read** the terminal's stock list (what the warehouse holds and can make), the open requests to this terminal and
  its package doors, the package doors and the warehouse's status.
* **Request** items, delivered into the terminal's buffer or through one of its package doors. A computer request is
  an ordinary **automation** request, exactly like a warehouse output's redstone pulse: the controller plans it and the
  crane carries it. Nothing is teleported.
* **Not** cancel a request, answer a question, take items out of the terminal's buffer or receive events (see §6).

## 3. The item argument and the fingerprint

Methods that take an `item` accept:

* a **string id**: `"minecraft:oak_log"`, or `"oak_log"` for the `minecraft` namespace;
* a **table** `{ name = "minecraft:enchanted_book", fingerprint = "1a2b3c4d" }` to pick one **variant** of an item.

Every stock row carries a `fingerprint`: the first 8 hex digits of a SHA-1 over the item id and the item's saved
components (with every compound's keys sorted). It is the same on every start, server and Java version, so a script may
store it. Two variants of one item (an enchanted book with Mending and one with Unbreaking, a named sword) have
different fingerprints; comparison ignores case.

The item is looked up among the rows the terminal's stock list holds (the same list the screen shows, cut to
`maxTerminalStockEntries`, default 512):

1. A malformed id or one that names no registered item is a **Lua error**: `unknown item: <id>`.
2. With a `fingerprint`: the row of that item and fingerprint. None: the item is **not in stock** (a refusal or `nil`,
   not an error; the variant may simply have run out).
3. With the id alone: the row of that item **without components**; otherwise the only variant if there is exactly one;
   otherwise a **Lua error**: `ambiguous item <id>: <n> variants, pass {name=..., fingerprint=...}`. No row at all:
   not in stock.

## 4. Methods

All results are fresh tables; lists are 1-based arrays. Every method runs on the server thread (CC: Tweaked queues it
for the next server tick), and every method first fails with a Lua error when computer access is off on the server
(`computer access is disabled on this server`) or the terminal has been broken or unloaded (`terminal is gone`).

### Stock row

The table `getStock` lists and `getItem` returns:

| Field | Type | Meaning |
|---|---|---|
| `name` | string | item id, e.g. `minecraft:oak_log` |
| `fingerprint` | string | 8 hex digits, see §3 |
| `displayName` | string | the item's name as the server sees it (English on a dedicated server) |
| `count` | number | items stored in the warehouse |
| `available` | number | what a computer can still be given from the racks: not promised to an open request and not held back by a stock rule's reserve |
| `heldBack` | number | the part of the unpromised stock a stock rule's reserve keeps from computers (and from redstone requests); a player at the terminal can still take it |
| `producible` | boolean | the warehouse has a production pattern for it |
| `producibleAmount` | number | how many it could make right now from ingredients in stock |
| `maxCount` | number | stack size |
| `details` | table | only with `detailed`: CC: Tweaked's own item details (`item.getItemDetail` shape: enchantments, damage, tags, ...) |

### `getStatus()`

Returns a table:

| Field | Type | Meaning |
|---|---|---|
| `connected` | boolean | the terminal belongs to a warehouse whose controller is loaded |
| `status` | string | `no_controller`, or the controller's state: `no_dock`, `dock_misaligned`, `no_rails`, `ready` |
| `aisle` | string | the terminal's aisle letter (only while `connected`) |
| `itemTypes` | number | item types in stock |
| `totalItems` | number | items in stock |
| `openRequests` | number | open requests of the whole aisle |
| `requestsHere` | number | open requests delivered into this terminal |
| `maxRequest` | number | the largest `amount` a `request` accepts (`maxTerminalRequestAmount`, default 1024) |
| `pageSize` | number | the largest page `getStock` returns (256) |

### `getStock([options])`

Returns `entries, nextStart`: one page of the stock list as stock rows, in the screen's order, and the `start` of the
next page, or `nil` when this page reaches the end. `options` is an optional table:

| Option | Default | Meaning |
|---|---|---|
| `start` | 1 | first row, 1-based. Past the end: an empty page and no `nextStart` |
| `limit` | 256 | rows on the page, 1 to 256 |
| `name` | none | an item id: only that item's variants |
| `detailed` | `false` | add `details` to every row |

Errors: `start must be at least 1, got <n>`, `limit must be between 1 and 256, got <n>`, `start must be a number`,
`start must be a whole number` (the same for `limit`), `name must be an item id string`, `unknown item: <id>`,
`detailed must be a boolean`.

### `getItem(item [, detailed])`

Returns the stock row of one item (§3), or `nil` when the terminal neither holds nor can make it. Errors: those of the
item argument.

### `request(item, amount [, door])`

Asks the warehouse for `amount` of `item`, delivered into this terminal's buffer, or through the package door at
`door = { x = ..., y = ..., z = ... }` (a position `getDoors()` lists; the terminal's own position means the terminal).

* `amount` is a whole number from 1 to `maxRequest` (`getStatus()`), otherwise a Lua error:
  `amount must be a number` (missing or not a number), `amount must be a whole number` (a fraction such as `2.5`, or a
  value beyond the 32-bit range: it is never rounded or wrapped) or `amount must be between 1 and <max>, got <n>`.
* A malformed door is a Lua error: `door must be a table {x=..., y=..., z=...}`, `door x must be a number`,
  `door x must be a whole number` (and so on for `y` and `z`).

**Accepted**, it returns `true, request` with:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | the request id (a UUID) for `getRequest` |
| `granted` | number | items this call added; may be less than `amount` when the stock (minus the reserve) is smaller, or `maxRequest` caps the merged request |
| `pending` | number | what the request waits for now, after this call |
| `producing` | number | items of `granted` that are not in stock and are being made by a production order |
| `merged` | boolean | this destination already waited for the item, and the call grew that open request instead of queueing a new one |

**Refused**, it returns `false, reason`, and a third value, an item id, when a production chain was refused because
of that item (often an ingredient, not the item asked for). A refusal is not an error: check the first value.

| Reason | Meaning |
|---|---|
| `no_controller` | the terminal belongs to no warehouse with a loaded controller |
| `not_in_stock` | nothing of the item is left that is not promised to another request, and it cannot be made |
| `reserved` | the items are there, but a stock rule's reserve holds them back from computers (a player at the terminal could take them) |
| `queue_full` | the warehouse has `maxOpenRequests` open requests (default 16) |
| `output_full` | the destination has `maxOpenRequestsPerOutput` open requests (default 4), one per item it waits for |
| `request_full` | the destination's open request for this item already waits for `maxRequest` items |
| `production_busy` | the item can only be made, and the warehouse already runs `maxProductionOrders` orders |
| `production_paused` | the warehouse has stopped making the item or an ingredient on its way (the production safety stop); a player has to look at the machine |
| `not_a_door` | `door` is not a package door of this terminal's warehouse |
| `door_ignores_redstone` | the door's Packager has a Stock Link, so it would never send the items |

The words are the lowercase names of Wareworks' request refusals. Three more exist there and never reach a computer:
`no_filter` (a warehouse output only), `out_of_reach` (a player only) and `invalid_amount` (a computer's amount is a
Lua error first).

### `getRequests()`

Returns the open requests delivered to this terminal or to one of its package doors (the ones `getDoors()` lists),
oldest first, at most 256:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | request id |
| `name`, `fingerprint` | string | the item |
| `requested` | number | items the request asked for in total (merged amounts included) |
| `remaining` | number | items still to deliver |
| `delivered` | number | items already handed over |
| `destination` | string or table | `"here"` for this terminal, else the door's `{ x, y, z }` |

The list includes players' requests: a terminal's requests are not owned by whoever made them.

### `getRequest(id)`

Returns that request's table (as in `getRequests()`), or `nil` once it is no longer open. Delivered and cancelled
cannot be told apart in this version. It finds any open request of the terminal's warehouse by its id, wherever it
goes: a request to a package door the screen does not list (more than 16 doors) stays visible here until it is
through, although `getRequests()` lists only this terminal and the doors `getDoors()` returns. A malformed id is a
Lua error: `not a request id: <id>`.

### `getDoors()`

Returns the package doors the terminal's screen lists (at most 16):

| Field | Type | Meaning |
|---|---|---|
| `x`, `y`, `z` | number | the door's position, the value `request` takes as `door` |
| `label` | string | the sign address at its Packager (empty without a sign) |
| `rack` | string | the door's rack address, e.g. `A-12-03R` |
| `state` | string | `ready`, `no_address`, `port_full`, `box_waiting`, `ignores_redstone` or `not_loaded` |
| `waiting` | number | items the open requests to this door still wait for |
| `delivered` | number | items of those requests already in the door's buffer |

## 5. Limits and staleness

* **Bounded results.** A stock page holds at most 256 rows; `getRequests` at most 256; `getDoors` at most 16. The
  stock list itself is cut to `maxTerminalStockEntries` (default 512), like the screen's, so an item beyond that
  window answers `nil` and `not_in_stock`.
* **Request limits** are the warehouse's own: `maxTerminalRequestAmount` per call and per merged open request,
  `maxOpenRequests` per warehouse and `maxOpenRequestsPerOutput` per destination. A computer that requests in a loop
  is refused with `queue_full`, `output_full` or `request_full` until the crane catches up.
* **Reserves.** A stock rule's reserve holds items back from computers as it does from redstone requests (`reserved`,
  `heldBack`). Players at the terminal are not held back.
* **Staleness.** The stock a computer reads is cached for 10 ticks (half a second), the rate an open terminal screen is
  refreshed at, so a computer reading it every tick costs no more than one open screen. The `item` argument of
  `request` is resolved against the same cached list, so an item that arrived in the last half second may still
  answer `not_in_stock`; the amount, the reserves and the caps are then checked against the live warehouse. Requests,
  doors and the status are read live.
* **Merging.** A request for an item the destination already waits for grows that open request, also when a player
  made it; both then share one id, and `getRequest` reports the merged totals.

## 6. Not in this version

* **No events.** Poll `getRequest(id)` until it returns `nil` to learn that a request is through. A push event is
  possible later without changing the methods above.
* **No cancel.** Merged requests share one id, so a computer cancelling "its" request would cancel players' items too.
  Cancel at the terminal's screen instead.
* **No controller peripheral**, and no access to the terminal's buffer: take delivered items out with a hopper, a
  funnel or CC: Tweaked's generic inventory methods on a chest the items are moved into.

## 7. Examples

List everything with fewer than 64 items in stock:

```lua
local terminal = peripheral.find("wareworks_terminal")
if not terminal then error("no warehouse terminal attached", 0) end

local start = 1
repeat
  local entries, nextStart = terminal.getStock({ start = start })
  for _, row in ipairs(entries) do
    if row.count < 64 then
      print(("%-30s %6d"):format(row.displayName, row.count))
    end
  end
  start = nextStart
until start == nil
```

Request 16 iron ingots into the terminal and wait until they have arrived:

```lua
local terminal = peripheral.find("wareworks_terminal")
local ok, result, about = terminal.request("minecraft:iron_ingot", 16)
if not ok then
  print("refused: " .. result .. (about and (" (" .. about .. ")") or ""))
  return
end
print(("requested %d, %d being made"):format(result.granted, result.producing))
while terminal.getRequest(result.id) do
  sleep(1)
end
print("delivered")
```

Send 64 torches through the terminal's first package door, one batch after the other:

```lua
local terminal = peripheral.find("wareworks_terminal")
local door = terminal.getDoors()[1]
if not door then error("this terminal has no package door", 0) end
local target = { x = door.x, y = door.y, z = door.z }

local open
while true do
  if open == nil or terminal.getRequest(open) == nil then
    local ok, result = terminal.request("minecraft:torch", 64, target)
    if ok then
      open = result.id
    else
      open = nil
      print("waiting: " .. result)
    end
  end
  sleep(5)
end
```

Pick one variant of an item by its fingerprint:

```lua
local terminal = peripheral.find("wareworks_terminal")
local books = terminal.getStock({ name = "minecraft:enchanted_book", detailed = true })
for _, book in ipairs(books) do
  local enchantment = book.details.enchantments and book.details.enchantments[1]
  if enchantment and enchantment.name == "minecraft:mending" then
    print(terminal.request({ name = book.name, fingerprint = book.fingerprint }, 1))
    break
  end
end
```
