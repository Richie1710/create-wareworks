# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Your rails may turn corners now, and the crane turns with them.** Lay a run of Warehouse Rails in front of the dock
  as always, then lay a second run at right angles to it: the block they share becomes a **corner**. The crane drives out
  to it, stops, swings the whole machine a quarter turn with a deep rumble and a metal clack, and rolls on down the next
  aisle — still lifting on the way. A warehouse can follow the shape of your base instead of forcing a straight hall
  through it, and goods from every aisle reach one block
- **Every straight run of rails is an aisle with its own letter**, and one controller letters the lot: the aisle at the
  dock takes the letter from the controller's own value box, each further one the next free letter. Addresses do not
  change — `A-03-07R` still means aisle A, level 3, position 7, right side. The letters and the position numbers are
  **pinned to the rails**, so extending an aisle, shortening it or laying a new one somewhere else never reshuffles the
  addresses you have already written on a sign
- **Racks in a corner work**, which is the whole reason corners are not travel-only. The corner block carries one rack on
  **each** of its two aisles, and the rule for which is the only rule there is: **the rack belongs to the aisle its
  interface faces away from.** Point it away from the aisle you mean, and the address tells you which one you got
- **A wrench closes a rail.** A closed rail is still a rail — it grows a brass end stop you can see across the room and in
  the dark — but it is no part of any warehouse. That is how you keep a decorative rail out of your warehouse, and how you
  cut one warehouse into two
- **One controller, one terminal, one stock list and one crane for the whole warehouse.** Nothing above the rails changed:
  the same requests, the same filters and priorities, the same ports, stock rules and production chains, on a warehouse
  that now bends
- **A new block, the Warehouse Home Point: "wait here".** Place it beside the rails where your next job usually starts —
  next to your terminal, next to an input, at a corner — and the crane comes back and waits in front of it when it has
  nothing to do, instead of standing wherever its last trip ended. Its lamp burns while your crane really uses it, and
  its goggles read out its address and one sentence saying what the warehouse does with it. It costs no more than the
  andesite tier, because it holds nothing and decides nothing: a rose quartz lamp, an andesite casing and two andesite
  alloy — no electron tube and no precision mechanism
- **A warehouse has one crane, so it has one home point** — and a second one is **refused where you can see it**, not
  quietly ignored: the plate turns red and grows a crossed brass stop, and the goggles say "Without effect: this
  warehouse already has a home point". The same happens to one the crane cannot **drive to**, for instance because a
  rail between them is broken or closed — then your dock is home again, never the second home point. Break the home
  point and the **dock** is home, exactly as it always was
- **The trip home is free, and that is a promise the game keeps.** It is not a job: the moment there is real work the
  crane takes it, in that very tick, **even in the middle of a corner**, so waiting at a home point never costs you a
  single item of throughput. And it holds **no chunks** loaded — a warehouse whose only remaining activity is a machine
  rolling home still counts as idle and still lets its chunks go
- **A Ponder scene for rails around a corner.** Hold **W** over a Warehouse Rail or a Stacker Crane and the second scene
  shows it: a straight run of rails, a run laid at right angles, the block they share becoming a corner, the machine
  rolling onto it and swinging a quarter turn, items carried from one aisle into the next, the corner block's **two**
  racks served — one on each aisle, told apart by the way the interface faces — the address that names the aisle, and the
  Wrench that closes a rail
- **The controller's goggles describe the whole warehouse.** A warehouse that bends reads "Warehouse: 41 rails, 3 aisles,
  mast 6 high" and lists them underneath ("Aisles: A 16 · B 12 · C 13", at most six before "and N more"). A warehouse of
  one aisle reads exactly as it always did
- **And they say where the rails stop, and why.** "Warehouse stops at 148 64 -37: the rails split here, which needs a
  later version" — one sentence per reason (a split, a loop, a second dock, a chunk that is not loaded, and each of
  the three server limits), never merged into one message, and naming the block you have to walk to. It is
  the line that answers "why is half of what I built not in my warehouse"
- **The dock says which aisle its machine is on**: "On aisle B at position 7", beside the size of the network. Only for a
  warehouse that bends, where the position alone would not say where the machine is
- **The Aisle Summary display source lists the aisles** of a warehouse that bends ("Aisles: A B C"), and marks it "(cut
  short)" while the rails stop short of what you laid — the board says that something is wrong, the controller's goggles
  say what

### Changed

- **Rails that touch now connect — read this one before you load an old world.** A warehouse used to be the straight line
  of rails in front of the dock whose axis matched it; it is now the whole connected set of rails. Two consequences in a
  world you already built: a **decorative rail orthogonally beside your aisle line joins the warehouse** as a one-block
  aisle of its own, and a rail laid **across** the aisle, which used to *stop* the count, now connects and lets the run
  beyond it join too. Nothing is lost — the new positions are simply new, and anything that stopped being a rack keeps its
  record until the warehouse next reconciles and then leaves normally with its items still in its chest. The one-click cure
  is the **wrench**: close the rail you did not mean to include. If you would rather have none of this, set
  `aisle.maxBranches = 1` in the server config and every warehouse is the single straight aisle it was
- **The wrench on a Warehouse Rail toggles "closed" instead of turning the rail's axis.** The axis stopped meaning anything
  the moment a second rail touched the block, and it is now only the look of a lone rail with nothing attached to it
- **On a warehouse of more than one aisle an idle crane now drives back home.** After ten seconds without work
  (`crane.returnHomeIdleTicks`, and **0 switches it off**) it returns to its Warehouse Home Point, or, if you have not
  placed one, to its dock. On a warehouse of **one straight aisle nothing changed at all**: the machine stands exactly
  where its last job left it, as it always did, and a home point there says so instead of pretending to work. So this
  only ever moves a machine on a warehouse that bends — which no world had before this version
- **A rack round a corner ranks behind an equally distant one on the aisle the crane is already on.** A quarter turn costs
  travel time (`crane.turnPenaltyBlocks`, default one block's worth, and it scales with RPM like everything else the crane
  does), and the job planner counts it. So a bent warehouse is measurably slower per trip than the same number of racks in
  one straight hall — physically honest, and worth knowing before you build a very long one
- **Branching rails are not supported yet.** A T-junction, a cross or a ring stops the warehouse **before** the branching
  rail: the rails beyond it are simply not part of it, everything up to it keeps working exactly as before, and the
  controller's goggles name the block where it stopped and why. You are never left with nothing, and never with the straight
  aisle you had taken away. Splits are the next milestone
- **Optional chunk loading is now measured per warehouse, and its default cap rose from 8 to 10 chunks.** A corner turns one
  long rectangle into two shorter ones at right angles, so a warehouse that bends needs more chunks than any single aisle of
  the same length ever could: a straight aisle of 32 rails needs 8, an L of 32 + 16 needs 10, an L of two full aisles 12.
  The rule is unchanged — a warehouse over the cap holds **nothing at all** and works exactly as it always did — and the
  number it would have needed is now named in the controller's goggles **and in `/wareworks chunks`**. A server that had
  set the value itself keeps its own value, which is what a config is for; at 8 it will meet the cap at its first corner.
  The lines say **warehouse** where they used to count aisles — the goggle line for each of the two server limits, and
  every row and total of `/wareworks chunks` — because one holder is one controller and every aisle it owns
- The item descriptions of the Warehouse Rail, the Stacker Crane and the Warehouse Controller now say what all of them
  do since rails may bend: rails lay out **aisles** and turn corners where they meet at right angles, the machine drives
  round them, and one controller gives **every** aisle of its warehouse a letter — the first one from its own value
  panel, each further one the next free letter

## [0.5.0-alpha] - 2026-09-28

### Added

- **Order something whose ingredients have to be made first.** Ask a terminal for a chest when the warehouse holds nothing
  but logs, and it works: at the moment you click, the warehouse looks at the patterns your production stations hold and
  works out the **whole chain** — planks from logs at your saw, the chest from those planks at your crafter — and then
  either creates **every step at once** or refuses the click and tells you the item that is really missing ("Oak Log is
  missing"). Nothing moves until the whole chain is possible, so the crane never starts carrying logs for something it
  could never have finished
- **Every step is an ordinary production order at one of your own machines, and every intermediate travels through a real
  storage location.** The crane brings the logs to your saw, the planks come back through a warehouse input and are
  **stored in a rack**, and the crane then fetches those very planks to the next machine. No machine-to-machine shortcut,
  nothing held invisibly: Wareworks still orders, and your machines still make everything
- **A step that is waiting for an earlier step is handed nothing at all.** A machine cannot run on half a set, so a chain
  that goes wrong does not leave part-sets of ingredients sitting in three of your machines. And an order waiting for its
  own chain never times out — what takes time is the step below it
- **The terminal shows a chain as one line**, with the step that is actually working named on the right ("now: Oak
  Planks"). Click that line and you get **every step with its state and the rack address of the machine it runs at**, so
  you know which block to walk to; one button gives the whole chain up, after saying exactly what that costs — how many
  steps really end, how many ingredients were already delivered to your machines, and that the warehouse will then stop
  making the item until you resume it at the machine. A step that already has its ingredients in a machine is **left
  running**, so its product still comes back, and the panel does not count it. Giving up on any one step ends the chain,
  because the steps above it are waiting for something nobody will make now
- **A refusal finally names the item.** Ordering something the warehouse cannot make used to answer "not in stock" about
  the thing you clicked. Now the answer is "Oak Log is missing", "Making Oak Planks is stopped", "No room for Oak Planks",
  "The chain loops at Iron Ingot" — and a click the racks could only serve in part shows the same sentence beside what you
  did get
- **How long a chain may be is up to your machines, not to a depth setting.** Two server settings bound it instead:
  `maxProductionPlanSteps` (how many orders one click may create, the ordered item's own included — **set it to 1 to switch
  chains off completely**) and `maxPlanIngredientItems` (how many **ingredient** items one click may put into your
  machines, over every step — the number that really bounds what one click can lose). A click that is too large is made
  **smaller** rather than refused, and one run is always allowed. A chain that would come back to an item it has already
  made — two patterns that are inverses of each other — is refused before anything is converted
- **A redstone request can start a chain too**, one at a time per output, so a clock cannot stack chains into the same
  machines while the first one is still working. A stock rule's minimum still orders one level deep, as before
- **If a machine eats a batch, the warehouse now stops making that item whatever ordered it** — your own click, a redstone
  request, a step of a chain or the warehouse's own restocking. It also stops *planning* it, so the next click cannot
  quietly rebuild the same chain into the same broken machine, and a stop that no stock rule is behind is never forgotten
  by itself
- **And you can see that stop, and lift it, where the machine is.** The production station in front of it lights a **red
  ring** around its openings, its goggles read "Stopped products: 1 / Ingredient items not recovered: 4", and its screen
  shows a red row and tints the pattern that makes the item. **Sneak-right-click the station** (with anything at all in
  your offhand), or click that red row, and the warehouse makes the item again — and it tells you how many ingredients
  were delivered and never came back. The controller's goggles and an aisle display count the same thing, in the same
  words, on an aisle with no stock keeper at all
- A Ponder scene, **"Chains of Production Orders"**, on the warehouse production station and the warehouse terminal alike:
  a warehouse holding only logs, one click that plans the whole chain, the step that is handed nothing while an earlier one
  runs, the planks coming back and being stored in a rack, and only then the second machine making what you ordered
- **Existing worlds are unchanged.** A production order saved before this version reads back as exactly what it was, and a
  warehouse that runs no chains looks the same as it always did, down to the order lines in its screens
- **Optional chunk loading for aisles that have work.** A warehouse does things on its own now — it refills its own
  minimums, hands surplus out through a port and fetches a machine's result back into storage — and all of that used to
  stop the moment you walked away. An aisle can now hold **its own chunks** loaded while it has something to do: a crane
  job, an open request or a production order, an automatic restock included. Walk away mid job and come back to a finished
  one
- **This is a chunk loader, and it ships switched off.** Nothing about it happens until a server operator raises
  `chunkLoading.maxTicketedAislesPerLevel` in the server config, and a server that leaves it alone behaves exactly as
  before: the crane pauses while its chunks are away and continues when they come back
- **Bounded, and going over a bound costs nothing.** Three caps — how many aisles of one dimension may hold at once, how
  many chunks one aisle may hold, and how long a single hold may last (one hour by default) — and an aisle over a cap
  holds **nothing at all** and works exactly as it did before, saying which cap stopped it and with which numbers. Lower a
  cap under an aisle that is already holding and it lets go. Every aisle lets go as soon as it is idle, after a short
  linger so a busy warehouse cannot make the tickets flap
- **Held chunks grow no crops and spawn no mobs.** They tick blocks and block entities — the crane, the controller and
  your own furnaces, funnels and belts — but not random ticks, mob spawning or inhabited time. This loads a warehouse, not
  a farm
- **You can find and free every chunk the mod holds.** The warehouse controller's goggles say whether this aisle is
  holding, how many chunks and why, or what is stopping it; an aisle display shows the count while it holds; and
  `/wareworks chunks` (permission level 2, the same as `/forceload`) lists every holding aisle of every dimension with its
  reason and age, next to how many chunks block tickets of any mod hold there and how many are force-loaded in total.
  `release <x y z>` frees one aisle in the dimension you run it in, and `release all` frees every dimension and leaves
  nothing at all holding. `/forceload query` cannot see a mod's tickets, which is why this command exists
- **Nothing outlives its owner.** Break, replace or remove a warehouse controller and its chunks are released in the same
  tick. A hold deliberately survives a restart, so a job that was running is still running afterwards — as a single chunk
  until the controller has confirmed there is still work, and dropped with a line in the log if the controller is gone. An
  aisle that let go because its hold ran out of time, or because you released it, stays that way across a restart as well
- A **separate opt-in**, `chunkLoading.maxCollectHoldAislesPerLevel`, keeps a **running** collection from a machine alive
  across the gap between one output and the next. It is off by default and honest about its limit: an idle aisle with
  nothing pending unloads and will not see a machine that starts producing later
- **Existing worlds are unchanged.** The only thing Wareworks writes for this is a note on a controller that has let go of
  work it could not finish, so that it stays let go after a restart; the hold itself is not stored at all

### Changed

- The default of the server setting `maxProductionOrders` rose from **8 to 12**, because a chain holds one production order
  per step and eight slots made a three-step chain plus one automatic restock the whole aisle. A chain that does not fit
  into the **free** slots is refused with "too many production orders are running" instead of being started
- "Paused after a lost batch: 1" on the warehouse controller's goggles and "Rules paused: 1" on an aisle display are both
  **"Stopped products: 1"** now, the same line the production station shows. Since a lost batch of any order stops an item,
  that count includes items no stock rule governs at all — on an aisle without a single stock keeper the old wording was
  simply false, and one number a player meets on three surfaces has to be recognisable as one state

## [0.4.0-alpha] - 2026-09-27

### Added

- The **Warehouse Output** is now the warehouse's general **port**. It still requests the item in its filter slot, but it
  can also **accept**: hold right-click with a wrench on the port's own box to turn it around, and the crane brings it the
  items that arrived at a warehouse input and would otherwise be stored. What you build behind it — a belt, a furnace, a
  way back to a farm — decides where they go; Wareworks itself never destroys anything, and a full port simply lets the
  input back up
- An accepting port has a **rank**. A *negative* rank makes it an **overflow**: every storage location wins over it, so it
  only ever gets what the warehouse cannot keep — which is what a stock rule's maximum finally has an outlet for. A
  *positive* rank makes it a **diversion**: it takes incoming items *before* they are stored. The filter still says which
  items the port handles at all, and a port without a filter takes anything
- Every port now says **when** it acts: hold right-click on the filter slot and pick a row — on a pulse (one action per
  redstone edge, as before), while powered, or unless powered. A requesting port set to "while powered" keeps itself topped
  up with never more than one trip in flight, so a machine stays supplied **without a redstone clock**; an accepting port
  set to "unless powered" works with **no wiring at all**, and a single lever switches it off
- You can see a port's direction without goggles: an accepting one turns **andesite** around the opening the crane reaches
  into and on its back spout, and its rank is drawn on the plate on the back. Goggles add the rank, which items it
  accepts, its redstone setting, whether it is active right now and how many items it has handed over; the controller
  counts "Accepting ports", and an aisle display shows the same number
- A Create clipboard copies a port's whole policy — filter, amount, redstone behaviour and rank — onto the next port, and
  copying an unconfigured port resets a configured one
- The crane now says "Handing over" instead of "Storing" while it carries items into an accepting port, in goggles and on a
  Crane Status display
- Two Ponder scenes for the port: "Supplying a Machine from a Warehouse" and "An Overflow for a Warehouse" — the second
  one also appears on the Warehouse Stock Keeper, because a maximum is what makes an overflow useful
- **Existing worlds are unchanged.** A warehouse output placed before this version is a port that requests on a pulse,
  which is exactly what it did, and it writes nothing new into your save until you configure it
- Storage locations have a **priority**: hold the click on a warehouse interface's filter slot to set a number from 0 to
  9, and among the locations that are equally suitable the crane fills the highest one first — so a large vault filtered
  to cobblestone takes the cobblestone before the general chests do, and a rack by the door fills before the far end of
  the aisle
- A priority decides only where **new** items go. It never overrules a filter and never mixes item types: a dedicated
  location and a location that already holds the item still win. Retrieval always takes the nearest source, raising a
  priority never moves what is already stored, and a running crane job keeps its target
- The number is drawn on the block itself while it is not 0, and read by goggles on the interface ("Priority: 5") and on
  the controller ("Prioritised locations: 3"). A Create clipboard copies a location's filter and priority together — and
  copying a location that has neither clears both on the location you paste it onto, so a whole rack wall can be set up,
  and reset, in a few clicks
- A port can also **collect**: point one at the chest, barrel or buffer a machine drops its result into, set the
  **Collect** row on the port's wrench box, and the crane reaches through the port, takes the items and stores them. No
  belt, no funnel and no arm on the way back — the warehouse fetches by itself, which closes the loop for production:
  the crane brings the ingredients, your machine works, and the crane brings the result home
- A collecting port uses the settings you already know: its **filter** says what is fetched (without one it takes
  whatever that side of the machine hands out), and the **redstone** rows say when — on a pulse, while powered, or
  unless powered, so it works with no wiring at all and a lever stops it
- It stops by itself where it should: at a stock rule's **maximum** nothing is collected, a **full warehouse** simply
  leaves the items in your machine, and a port pointed at an inventory the aisle already counts as a storage location
  says so instead of shuffling one chest around the aisle for ever. Nothing is ever pushed **into** your machine, and
  collected items are only ever stored — never handed back out through another port
- Your own requests always come first: collecting is planned after everything a player or a production order is waiting
  for, and several collecting ports take turns with each other and with the warehouse inputs, so nothing starves
- You can see it from the aisle: a collecting port turns **copper** where the crane reaches in. Goggles show what it
  collects, what its last look into the machine found that it may fetch ("Ready: 24"), how many items it has fetched
  and — while the warehouse will not take them — why not, so a full warehouse or a stock rule's maximum can be read at
  your machine instead of at the controller; the controller counts "Collecting ports", an aisle display shows the same
  number, and the crane says "Collecting" while it does
- A Ponder scene, "Collecting from a Machine", which also appears on the Warehouse Production Station
- New server config key `collectPollIntervalTicks` (default 20): how often a controller looks into the inventory behind
  a collecting port when the machine does not announce its changes, e.g. a furnace

### Changed

- The controller's four "why nothing was stored" lines no longer say "the input items", because items now also arrive
  through a collecting port: they read "no storage location accepts **these** items", and the same wording in German

## [0.3.0-alpha] - 2026-09-26

### Added

- Stock rules: the new **Warehouse Stock Keeper** holds one item plus a minimum, a maximum and a reserve per row, and
  the three numbers govern three different directions — what comes in, what may be stored, and what may go out to the
  warehouse's own automation
- A warehouse keeps itself stocked: when an item falls below its minimum and a warehouse production station of the same
  aisle has a pattern for it, the warehouse orders it by itself, without ever spending what a reserve protects
- If a machine swallows a batch of ingredients and nothing comes back, that rule stops ordering and waits for you, with
  a differently coloured lamp on the keeper and a paused state in its screen, on the controller, on the terminal and on
  an aisle display. Clicking the rule's mark, or re-editing the rule, lets it order again
- The warehouse terminal asks before a click of yours goes below a reserve, spends a reserved item as the ingredient of
  something it has to make for you, or would leave more in the racks than a maximum allows — naming the number and the
  item every time; hold Alt while clicking to skip the question
- A stock keeper's row now says what automatic restocking last decided about it, and names the ingredient a rule is
  waiting for you to supply
- Two Ponder scenes for the stock keeper: "Stock Rules of a Warehouse" (what each of the three numbers governs) and
  "A Warehouse that Restocks Itself" (the warehouse ordering for itself, and what happens when a machine eats a batch)
- New server setting `maxRestockIngredientItems` (64): the most ingredient items one automatic order may spend, which is
  what bounds how much a broken machine can be given before the warehouse stops ordering for that rule

## [0.2.1-alpha] - 2026-09-25

### Added

- Stock displays through Create's Display Link: an aisle summary and a stock list on the warehouse controller and
  terminal, the stock of a filter slot's item on the warehouse output and interface, and the crane's status on the
  stacker crane dock

### Fixed

- The warehouse terminal's stock list could order two items of the same type that differ only in their data components
  (two named shulker boxes, two enchanted books) differently after a restart

## [0.2.0-alpha] - 2026-09-25

### Added

- Mechanical Arms can take items from the warehouse output, terminal and production station, and put items into the
  warehouse input
- The license file is included in the mod jar
- Ponder scenes for placing a warehouse terminal, requesting items at it, feeding your machines from a warehouse, and
  dedicating storage locations with filters
- The warehouse terminal and the production station now appear in the Ponder index, under "Automated Warehouses" and
  Create's "Item Transportation"

### Changed

- License changed from MIT to GPL-3.0-only. Version 0.1.0-alpha remains available under MIT
- The Ponder scenes for storing and retrieving now name Mechanical Arms alongside belts, funnels, chutes and hoppers
- The funnels in the Ponder scenes are now shown in the state that really does what the scene describes: attached to
  the station below them, and extracting where items are pulled out of one

## [0.1.0-alpha] - 2026-09-17

First public alpha. Please back up your world before trying it.

### Added

- Stacker crane with animated chassis, mast, lift carriage and telescopic arm
- Warehouse rail, controller, interface, input and output stations
- Warehouse terminal: searchable stock screen and click-to-request; repeated requests for the same item travel in one
  trip
- Storage location filters with Create's List Filter, Attribute Filter and Package Filter
- Production station: patterns deliver ingredients to your own Create machines and collect the result
- Ponder scenes, English and German translations

[unreleased]: https://github.com/Richie1710/create-wareworks/compare/v0.5.0-alpha...HEAD
[0.5.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.4.0-alpha...v0.5.0-alpha
[0.4.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.3.0-alpha...v0.4.0-alpha
[0.3.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.1-alpha...v0.3.0-alpha
[0.2.1-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.0-alpha...v0.2.1-alpha
[0.2.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Richie1710/create-wareworks/releases/tag/v0.1.0-alpha
