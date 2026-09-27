# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

[unreleased]: https://github.com/Richie1710/create-wareworks/compare/v0.4.0-alpha...HEAD
[0.4.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.3.0-alpha...v0.4.0-alpha
[0.3.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.1-alpha...v0.3.0-alpha
[0.2.1-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.0-alpha...v0.2.1-alpha
[0.2.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Richie1710/create-wareworks/releases/tag/v0.1.0-alpha
