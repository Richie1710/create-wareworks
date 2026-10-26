# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Warehouse Crafter Feeder: a vanilla Crafter can now be the machine behind a production station**
  ([#34](https://github.com/Richie1710/create-wareworks/issues/34)). Put the feeder on the back of a Warehouse
  Production Station, a Crafter in front of the feeder and a Warehouse Input in front of the Crafter's output, and give
  the feeder rotation. When the crane has delivered an order's ingredients, the feeder lays them into the Crafter's slots
  exactly as the pattern's 3 × 3 grid shows them, several runs at once, and gives the Crafter one redstone pulse per
  run. The Crafter crafts, the result goes into the input and the warehouse stores it like any other product. Wareworks
  itself still crafts nothing. The feeder serves one order at a time and only starts the next one when the Crafter is
  empty, so two orders never mix. While a pattern is loaded, the Crafter slots it leaves empty are switched off and are
  switched back on afterwards. Remainders such as the buckets of a cake leave with the result. The feeder holds no items
  itself. A feeder can face any way except into its station: facing along the rack wall puts the Crafter behind the
  next rack position, where the input stands in front of it. A feeder broken and placed again in the middle of a large
  order lays only the runs that are left
- **Patterns are checked against real crafting recipes while a Crafter Feeder is attached.** A pattern that is no
  crafting recipe, that has more than one item in a cell, or whose result differs from what the recipe makes is drawn
  red in the station's screen with the reason ("Not a crafting recipe", "Each cell must hold exactly 1 item", "The recipe
  makes Torch x4"). The warehouse then never offers or orders it, so no ingredient is fetched for nothing. Without a
  feeder nothing is checked, as before
- **The feeder says why it waits.** Its goggles show the item and the runs still to craft, or the reason it is stopped:
  no Crafter, a full or missing output, no rotation, a Crafter grid changed by hand, another redstone signal on the
  Crafter, missing ingredients, or a recipe that a reload changed so it no longer fits the pattern (then nothing more is
  laid). It never pulses while the Crafter could not put the whole result and every remainder
  into the block in front of it, so the vanilla Crafter never drops a result on the floor. The station's goggles show
  that a feeder is attached and how many patterns it skips
- **Server config:** `crafterFeederRequiresRotation` (default `true`) and `crafterFeederStressImpact` (default 2.0, like
  Create's Mechanical Crafter). At 32 RPM and above the feeder crafts one run every 8 ticks
- Recipe: a precision mechanism over a brass casing over a vanilla Crafter, in one column. A Ponder scene, "Crafting
  with a Vanilla Crafter", shows the build for the feeder and the production station

### Changed

- **Server and players have to update together.** A client with this version cannot join a server with an older one,
  and the other way round, because the production station's screen carries the pattern checks now

## [0.13.1-beta] - 2026-10-26

### Changed

- **The crafting panel's amount field now counts the items you want, not crafts**
  ([#33](https://github.com/Richie1710/create-wareworks/issues/33)). Asking for 4 Stone Bricks fetches one craft's
  Stone (4 Stone for 4 bricks) instead of four crafts' worth. An amount that is not a whole number of crafts is rounded
  up: 5 Stone Bricks fetch for 2 crafts, 8 bricks. The Fetch tooltip, the question, the status row and the result say
  how many items you will get, with a "Crafts: 2 (4 each)" line for recipes that make more than one. The "Set x…"
  number in the question asks for exactly what it offers, and a recipe laid with JEI's "+" follows the same rule.
  Recipes that make one item work exactly as before. The largest fetch is now the amount field's maximum in items,
  rounded up to a whole craft (1024 planks by default instead of 4096)

## [0.13.0-beta] - 2026-10-17

### Added

- **A Display Link on the Warehouse Controller or the Warehouse Terminal can show one number about the warehouse.** The
  new source "Warehouse Number" has a selector in the Display Link screen for what it shows: items stored, item types,
  fluid stored, locations in use, locations in total, locations in use as a percentage, open requests, production
  orders, rules below their minimum, rules at their maximum, stopped products, accepting ports, collecting ports and
  misaligned blocks. Nixie Tubes can now show "Items 176" instead of only "Warehouse A: Ready", and open requests and
  production orders, which only the controller's goggles showed, can go on a display too. Locations in use count a
  Fluid Bay that holds fluid, as the Warehouse Summary now does. The "Label" box puts your own word in front of the
  number, and a display board gives the label and the number columns of their own. A block that belongs to no
  warehouse, and a controller whose crane dock is missing, shows "No warehouse". The Display Link screen still opens on
  the Warehouse Summary, and existing links keep the source they had

### Changed

- **Server and players have to update together.** A client with this version cannot join a server with an older one,
  and an older client cannot join a server with this one, because a click with an item on a storage filter slot is now
  applied by the server (see Fixed below)
- **The hover tip of a storage location's filter slot now says how to reach the priority and which way it ranks.** It
  reads "Hold with an empty hand to set the priority; higher fills first", and the Ponder scene "Rack Bays in a
  Warehouse Aisle" says the same
- **The server config now says what the crane's speed cap and stress setting really do.** In `wareworks-server.toml`,
  `crane.maxBlocksPerTick` read as a cap for "each crane axis"; it caps travel and lift (and with travel the turns at
  corners), not the arm, and with the default factors it is never reached up to Create's default top speed of 256 RPM,
  so it has to be lowered to have an effect. `crane.stressImpact` now says that a kinetic network that is already
  running counts a change only once the crane joins it again (break and place the shaft under the dock, or change the
  speed at the source), because Create keeps the stress it read when the crane joined the network, and that after a
  restart the network's total follows only at its next change. `crane.armExtendPerTickPerRpm` now says that the arm
  never extends faster than fully in one tick. The values themselves and their defaults are unchanged; the file picks up
  the new comments the next time a world or server starts

### Fixed

- **A click with an item on the filter slot of a Warehouse Interface, a Rack Bay or a Fluid Bay now sets the filter at
  once.** The filter was only set when the button was released: holding the click a moment too long opened the
  priority board instead, which a low frame rate made more likely, and moving the mouse off the slot before letting go
  lost the click without a word. Now the filter is set the moment you press, and holding the button or moving the mouse
  changes nothing; a button held down elsewhere that slides onto the slot sets nothing either. A Create filter item is
  still taken from your hand and the one it replaces given back. The priority board opens when you hold the click with
  an empty hand or a wrench, and a short click with an empty hand still clears the filter
- **The Warehouse Summary's "Locations" line counts a Fluid Bay that holds fluid as in use, so a warehouse with fluid
  bays can read full.** A fluid bay was counted in the total but never as in use, however full it was, so a chest of
  items and two full bays read "Locations: 1 / 3". An empty bay still counts as free
- **The German texts call the filter slot "Filterfeld" everywhere.** The Warehouse Interface's tooltip and two Ponder
  lines said "Filterplatz", while every other text said "Filterfeld"

## [0.12.1-beta] - 2026-10-17

### Fixed

- **Create Toolboxes that have held items can now be requested and retrieved, and toolboxes with different contents are
  no longer one stock line.** A toolbox compartment remembers the first item put into it, and a toolbox with such a
  compartment could be stored, but every request for it answered "not in stock", also after a restart; one picked up
  from the world answered so until the next restart even if it had never held anything. A request now delivers exactly
  the toolbox it names, holding what it held when it was stored, also after a restart and on a server. Toolboxes given
  by a command without any remembered item all shared one stock line whatever they held, so a request could deliver one
  holding another one's contents, and the warehouse could stack an empty one onto a full one, making it a second full
  one. Now each content is a line of its own, and neither the crane nor a warehouse station stacks two toolboxes with
  different contents into one slot. Every toolbox that has been placed keeps an id of its own, so two of them stay two
  lines even when they hold the same, as before. A Sand Paper put away in the middle of polishing an item could be
  stored but never requested, and each one was a stock line of its own; it can now be requested like any other item, and
  finishing the polish still gives the polished item. A toolbox that holds a Sand Paper paused in the middle of
  polishing still cannot be requested
- **A Wand of Symmetry set to Rectangular, Orthogonal and one set to Octagonal, centred on the same block, are no longer
  one stock line.** The warehouse took them for the same item, so a request for one could deliver a wand with the other
  mirror. Each mirror is a line of its own now, and a request delivers the wand that was stored
- **Two Create Toolboxes of one colour, and items carrying several changes each, now keep their order in the stock list
  after a restart.** Two Create Toolboxes of one colour could swap places in the terminal's stock list and on a stock
  list display at every start, and two items carrying several changes each, such as two renamed and enchanted swords,
  could swap places once the chest holding them had been saved and loaded again. On the first start after updating, such
  lines may change their order once; after that they keep it. Items whose contents are a container's, such as two
  Shulker Boxes of one colour holding different things, can still swap places

## [0.12.0-beta] - 2026-10-16

### Added

- **Order goods to a package door from the warehouse terminal.** A requesting warehouse output with a Create Packager at
  its back is a door, and the sign on the Packager is its name. A new button left of your inventory in the terminal's
  screen chooses where a click in the stock list delivers: here, or one of the warehouse's doors. Click it for the next
  door, scroll over it, or right-click it to go back to here; its tooltip lists the doors and says which of them need a
  look, such as a door without a sign or one whose port is full. While a door is chosen, its name stands in gold in the
  title row, the item you point at is tinted gold and its tooltip names the door, and a question the terminal asks says
  where a Yes delivers. The crane carries the goods into the door's port and your Packager boxes them with the sign's
  address, so Create's belts, chain conveyors and trains take them on. The status line names the door when it takes an
  order, "Iron Ingot x64 to Base North", and then says what the door is still waiting for. The screen opens on "Here"
  every time, a door whose Packager carries a Stock Link cannot be chosen, and a door that goes away while it is chosen
  sends your clicks back here and says so. Clipboard lists and the crafting panel's Fetch still always come to the
  terminal, and their tooltips say so. Orders to a door share it with the door's own redstone request, because requests
  have no owner: a door set to *while powered* or *unless powered* does not top itself up while an order to it is open.
  There is no cancel button for an order to a door: turning the door out of *Request* with a wrench cancels what it
  waits for, its own request included, and sends what the crane is carrying to it back into storage. In a warehouse with
  very many outputs the door list looks at no more than 64 of them, in rack order and leaving out accepting and
  collecting ports, and the door button's tooltip says so when it stopped before the last one; a warehouse with no door
  among those 64 shows no door button at all. The button offers at most 16 doors, the first in rack order; any further
  doors are only counted in its tooltip's "+N more" and cannot be chosen. A new Ponder scene on the terminal and the
  output, **"Delivering to a Package Door"**, shows the whole build
- **A package door now says when a box has been waiting at its Packager for a while.** A Packager that holds a box makes
  no other, so a door whose funnel was forgotten, or whose belt stopped, stands still with its port filling up behind
  it. Once a box has been seen waiting in the Packager's tray for ten seconds, the door's entry in the terminal's door
  list reads "box waiting" in gold, and the door's goggles show the gold line "A box has been waiting at the Packager:
  is anything taking it?". Nothing watches a door while nobody looks: the wait is counted from the first time an open
  terminal screen of the warehouse or a player's goggles see the box, so a box that has been stuck unseen is reported
  about ten seconds after you open the screen or start pointing your goggles at the port. Ten seconds is longer than a
  working belt funnel waits for a belt turning at 3 RPM or more, so a door that ships normally does not show it. The
  door can still be chosen, and both go away as soon as something takes the box

### Changed

- **A crane no longer waits in front of a warehouse port it cannot fill.** It now carries only as much as the port can
  take at that moment, and sets off once the port has room for the whole trip, or for a full stack of a larger request.
  While the port is short of room the crane serves the rest of the warehouse, and the remaining items follow as soon as
  something empties the port. Before, a port that was emptied slowly or not at all, for example one whose Packager was
  never triggered, could hold the crane with the rest of a trip in its claw until somebody emptied, broke or turned the
  port. A crane that was already waiting in front of a port when the world was saved, or one carrying a trip that no
  longer fits because `outputBufferSlots` was lowered, still drops what the port takes at each try, and takes the rest
  back into storage at the first try where the port takes nothing; the rest of the request follows once the port has
  room. A warehouse terminal still keeps the crane waiting with what does not fit, as before
- **A request only ever goes to a warehouse port that requests.** A request for an accepting or a collecting port is now
  refused, and a port that was turned away from requesting while its warehouse was not loaded loses its open requests
  the next time the warehouse checks its layout, as a port turned in a loaded warehouse always did. Before, an accepting
  port could still be handed the items that had been requested for it, and a collecting port kept a request that nothing
  could ever serve

### Fixed

- **Scrolling while the terminal asks a question or shows a chain's steps no longer moves anything behind it.** Before,
  the mouse wheel scrolled the stock list or changed the amount field under the dialog

## [0.11.1-beta] - 2026-10-16

### Changed

- **JEI's "+" weighs at most four variants of one modded item per recipe when they differ only in that mod's own
  data.** Where a JEI slot lists more such variants, for example one tool part in many materials, the terminal now
  chooses among the first four in JEI's order: a later one is no longer preferred because it was delivered or is in
  stock, and the cell shows the first one as missing instead. Potions, tipped arrows, enchanted books, rockets,
  banners, dyed and trimmed armour and the other vanilla variants are all weighed as before, and so is mod data whose
  component holds nothing but NBT (custom data included), a number, a string, an enum or a registry id. Data a mod
  wraps in its own record or class still counts as that mod's own data, even when all it holds is such a value, for
  example a material id
- **Many variants of one modded item that differ only in that mod's own data take longer to keep apart.** The
  warehouse now tells such variants apart by comparing them one by one, which is what makes items like Quark's Seed
  Pouches retrievable. A storage location holding hundreds of them, such as a large vault full of spell scrolls or
  tool parts in many materials, takes noticeably longer every time it is read, which happens in the background and
  after every crane trip into or out of it, and so does a terminal listing them. A chest or barrel never holds enough
  of them to notice. Vanilla and Create variants, and mod data kept as NBT, numbers, text or registry ids, are as fast
  as before

### Fixed

- **Items whose mod data compares by its contents but is hashed by identity, such as Quark's filled Seed Pouches, can
  now be retrieved.** They could be stored, but every request for one at the terminal answered "not in stock", and each
  one stored showed up in the stock list on its own. They now appear once per content — two pouches holding the same
  seeds as one entry with 2, a pouch of chestnuts as another — and can be requested and retrieved, also after a restart,
  on a server and when they sit inside another item, such as a pouch in a shulker box. A fluid carrying such data is no
  longer split into several fluids either. Every other item is stored, listed and requested as before, only slower when
  a mod keeps many variants of it (see Changed). A Create Toolbox that has held items still cannot be requested back:
  Create's toolbox data compares its compartment filters by identity, so it never equals the copy a request carries

## [0.11.0-beta] - 2026-10-15

### Added

- **Craft at the warehouse terminal.** A new button beside your inventory in the terminal's screen folds out a
  crafting grid. Lay a recipe in it and craft it **by hand from what the crane delivered** into the terminal, without
  carrying everything to a crafting table first. Click the result to craft once, **Shift**-click to craft as many as
  the delivered items allow. The result goes into your inventory, or into the terminal when your inventory is full,
  and leftovers like a cake's empty buckets go into the terminal, or into your inventory when the terminal is full.
  Any crafting recipe works, including the special ones
  such as fireworks and dyeing, and with the `doLimitedCrafting` game rule on, a recipe you have not unlocked stays
  locked. Wareworks still never crafts on its own: a recipe runs only when you click it
- **The grid holds ghosts, not items.** Right-click an item in the stock list to pick it up as a ghost, then
  left-click grid cells to stamp it in — one pick fills a whole ring of cells, and **Esc** puts it down.
  Shift + right-click drops an item straight into the next free cell. Laying a recipe orders nothing and moves
  nothing; a plain left click in the list still requests, exactly as before. The result appears under the grid with
  the number of crafts the delivered items allow, cells you already have are drawn solid, and a cell's tooltip says
  how many of that item the warehouse has. Each player has their own grid, and the last 16 terminals you used keep
  theirs until you leave the world or server
- **Nothing is ever half-crafted.** If an ingredient is missing from the terminal, or there is no room for the result
  or for what the recipe leaves behind, nothing is used up, and the status line names the item. Two players crafting
  from one terminal at the same moment never share an ingredient: the second one is told it is gone
- **Fetch the ingredients with one button.** Set how many crafts you want in the amount field and press **Fetch**
  under the grid: the crane brings exactly what the terminal is still missing for them. What was already delivered
  counts, so a second Fetch for the same recipe brings nothing more, and the button's tooltip lists what will be
  fetched of each item before you press it. A bar under the grid counts the deliveries, and the red cross at its end
  gives the fetch up at any time: what arrived stays in the terminal, the rest stays in the racks, and your recipe
  stays in the grid. A fetch carries on with nobody at the terminal, survives a save, a chunk unload and a restart, and
  can be started while a clipboard list runs: the two run side by side and share the terminal's open requests,
  so one of them may wait for a request the other frees. Like a clipboard list, a fetch pauses ("Fetch for Chest x4 is
  paused") when it can make no progress at all for a minute, or when you answer a question about a reserve or maximum
  with No, until someone at the terminal presses "Try the rest of the fetch again". The server settings
  `terminalListOpenRequests`, `terminalListIntervalTicks` and `terminalListStallTicks` now bound each fetch on its own,
  as they bound each clipboard list. While a fetch is open, Engineer's Goggles on the terminal show its progress, for
  example "Crafting fetch: 6 of 16 (fetching)"
- **Whole crafts, and one question up front.** Before anything is ordered the terminal works out how many crafts the
  warehouse can really supply, and how many fit into the terminal once everything already on its way there has
  arrived. When that is fewer than you asked for it asks once — for example "Only x2 of x4 fit here", or which
  ingredient is short — and Yes fetches exactly that many; when not even one more craft is possible it says why and
  orders nothing. A stock rule's reserve or maximum is still asked about item by item while the fetch runs, exactly
  as for a click. While a fetch runs, everyone at that terminal sees its recipe in their grid, locked until the fetch
  is done or given up. The recipe then stays in the grid of everyone who had the screen open, in place of the one
  they had laid there
- **Missing ingredients can be made first.** A grid cell whose item the warehouse is short of shows the stock list's
  blue **+** when one of your production stations has a pattern for it ("Can be made here: Fetch asks first"), and turns
  red with a red **!** when the safety stop holds that item ("Making Oak Planks is stopped"); a cell nothing can make
  says so in its tooltip. Fetch then offers to have such ingredients made at your own machines before they are brought —
  "Oak Planks x4 will be made first." — and says how many crafts the racks alone could serve ("Without making: x1").
  Nothing is ordered before you say Yes, and a No orders nothing. The chain runs like one you order with a click: it
  shows in the terminal's production section, what your machines make beyond what the fetch needs stays in the racks,
  and two missing ingredients made from the same logs are never promised the same logs. When no chain can help, Fetch
  names the reason, for example "Oak Log is missing". Only the ingredients are made: the recipe in the grid is never
  made by the warehouse, and you still craft it by hand
- **JEI's "+" lays a recipe into the grid.** With JEI installed, look a crafting recipe up in JEI while the terminal's
  screen is open and press its **+**: the recipe is laid into the crafting grid, the grid folds out, and the status
  line says "Laid the recipe for Chest". Nothing is ordered and nothing moves; press Fetch for the ingredients. Where an
  ingredient may be any of several items, such as any planks, the terminal picks one: first what has already been
  delivered into it, then what the warehouse holds most of, then what your machines can make, and never a damaged,
  enchanted or renamed item while a plain one would do. Every cell of one ingredient gets the same item, so a chest is
  eight planks of one kind. A missing ingredient never stops the **+**: the grid shows what is missing, as for a recipe
  you lay by hand. While a fetch runs, the **+** is greyed out and says why, and Shift on it does the same as a plain
  click. JEI is optional and only needed on the client: without it, everything works exactly as before. Every JEI
  for Minecraft 1.21.1 works (19.8.4.110 or newer). EMI is not supported directly: without JEI, EMI's **+** does not
  work at the terminal
- **A note for servers running Create: Schematic Checker.** In mod-whitelist mode the checker removes Wareworks blocks
  that carry block data from uploaded schematics — every rack bay, in the report — so they show in the hologram but
  are missing from the Schematicannon's checklist and from the print. Whitelist mode is the checker's default. The
  README now has the line that fixes it (`"wareworks"` appended to `WhiteListModList` in `config/CSC/config.toml`,
  keeping every entry already there) and why that is safe: Create prints a Wareworks block with its settings and never
  with what it holds. Tests save and print every Wareworks block, configured and stocked, through Create's own
  schematic code to keep it that way

### Changed

- **A clipboard list that is stuck says why.** When the warehouse refused the list's last portion and nothing else of
  it is on its way, the terminal's status line now names the reason in gold — for example "Glass is missing" —
  instead of the list's progress, and the clipboard slot's tooltip names it too. While the list waits for an answer to
  a question, the status line asks for that answer instead
- **A Wareworks block at its default settings prints without block data of its own.** An unfiltered rack bay, fluid
  bay or warehouse interface, a plain warehouse port, a controller on aisle A, a dock at the default mast height and a
  stock keeper without rules no longer hand a Schematicannon any block data of their own, and saves and chunk packets
  carry a little less. Worlds and schematics from earlier versions load unchanged. The schematic file still stores
  every block's full data, so a server running Create: Schematic Checker still needs the `wareworks` whitelist line
- **A filter slot stores a single item as its sample.** Setting the filter of a storage location or a port with a
  whole stack in hand used to store the whole stack as the sample, so a schematic of the block named items that do not
  exist. Nothing is taken from you either way, the filter works exactly as before, and a Create filter item is kept as
  it is

### Fixed

- **The terminal, the production station and the stock keeper open with anything in your hand**, like a chest.
  Right-clicking one while holding a block used to place the block against it, and food was eaten in front of it;
  now the screen opens and the item stays where it is. To place a block against one of them (a chute, a hopper, a
  sign) or to use the item you hold, sneak while you click. The items that work on these blocks keep doing so: the
  wrench turns them (in adventure mode, where it cannot, it opens the screen), the Mechanical Arm selects the terminal
  and the production station, the clipboard opens its own editor, the Display Link selects its source, goggles are put
  on, and sneaking with an empty hand on a production station still restarts what the safety stop held. Funnels and
  Deployers place against them as before
- **A non-operator's creative instant print no longer duplicates a pallet.** A Schematic and Quill saves every entity
  in its area, and a pallet — the load of a broken rack bay — went into the schematic with its whole load; printing it
  in creative then added a second pallet beside the first. Create now leaves pallets out of every print except an
  operator's creative instant print, which keeps every saved entity. A single-player world with cheats on (the default
  for a new creative world) makes you an operator, so there a saved pallet still comes along with its load: leave
  pallets out of the area you save
- **A schematic printed over a standing crane, terminal, production station, port or stock keeper no longer deletes
  what it holds.** A print lands on a standing block when one of its block states differs (a dock turned another way,
  a terminal's screen on another side, a port that is powered now): in creative, and at a Schematicannon with *Replace
  Block Entities* on. It used to empty the crane's handling head without dropping anything, take the clipboard out of
  a terminal's list slot and cancel its order, wipe a production station's patterns, disarm a port a redstone pulse
  had armed (losing the export it promised) and zero its counters and its last refusal, and wipe a stock keeper's
  rules when the schematic carried none, which is every ordinary Schematic and Quill save, because it is taken on the
  client. They now stay, as a rack bay's goods and a station's buffer already did
- **Giving up a clipboard list no longer cancels a click of yours that joined it.** A click for an item the list was
  fetching at that moment merges into the list's request, as repeated requests always do, and cancelling the list (or
  taking its clipboard out) cancelled the whole merged request, your click included. Now only the list's own share is
  given back: your click keeps its amount and the crane brings exactly that, while the list's share stays in the racks,
  even when the crane had already planned one trip for both, and even when the list's share was being made at one of
  your machines. Items the crane is already carrying still arrive
- **A clipboard list no longer takes a click of yours when its items cannot be made after all.** When the list had
  part of an entry made at one of your machines, a click of yours for the same item joined its request, and the
  machine's order then ended without making it all — a jammed machine, or the order cancelled — the list was credited
  your click's items when they arrived, and you got none. The list now takes what was not made off its own entry and
  fetches it again, and your click gets its items
- **Two players answering a clipboard list's question no longer mix it up.** A list stops on a question when an entry
  would take reserved items or go past a maximum, and every screen at the terminal shows it. When one player answered
  and the list at once asked about its next entry, the other player's screen still showed the first question, and a
  Yes there was taken as a Yes to the new one. Every screen now gets the new question, and a Yes only ever counts for
  the question it was given to
- **The terminal's screen stays up to date while JEI covers it.** With JEI installed, opening a recipe in JEI from the
  terminal's screen and coming back could leave the stock list and the status line showing what they showed when JEI
  opened, until each of them changed again. The terminal now keeps receiving the warehouse's updates behind JEI's
  recipe screen

## [0.10.0-beta] - 2026-10-14

### Changed

- **Wareworks is now in beta.** The warehouse is complete enough to build on: worlds saved with one beta load in the
  next, every change to saved data comes with a tested migration, and an item lost or duplicated by the warehouse is
  treated as the most serious kind of bug. Requesting fluids at the terminal and ordering packages to a door are still
  to come before 1.0. On CurseForge the file is now listed as a beta instead of an alpha

## [0.9.2-alpha] - 2026-10-14

### Fixed

- **The Shift descriptions fit the screen again.** They had grown to as much as 86 lines, and on a 1440 px screen
  (2560x1440 or an ultrawide 5120x1440, where the automatic GUI scale is 6) the top of a long one was cut off. Every
  item now says in one sentence what it is, plus at most one thing the Ponder scenes do not show — like Create's own
  blocks; how a block works in depth is in its Ponder scenes and its goggle lines. A test measures every description
  with the game's real font, so none grows past the screen again
- **A Schematicannon that is stopped and started again no longer prints a warehouse rail twice.** The last rail it had
  printed before the stop was redrawn as an open end, so after the restart the cannon took it for a wrong block,
  printed it again over the one standing there and asked for an extra rail from its chest. A rail now keeps the
  connections its schematic gives it. Tests print a whole warehouse with a real cannon from exactly the items it
  needs, once straight through and once stopped half way: the chest ends empty, every block stands, nothing is lost
  and nothing is used twice

## [0.9.1-alpha] - 2026-10-13

### Changed

- **Built against NeoForge 21.1.256**, the version *All of Create* 2.5 ships. The mod still loads on **21.1.250 and
  newer**, so a server or a pack that has not moved yet keeps working with it

## [0.9.0-alpha] - 2026-10-08

### Added

- **A rack bay is the storage location itself, with no interface in front of it.** Every storage location this mod had
  was an inventory plus a **warehouse interface** carrying its address, its filter and its priority, so a rack wall
  was a wall of chests with interfaces in their faces — fifty locations, a hundred blocks. A bay is one block that
  **is** the location: it carries its own **address**, its own **store filter** and its own **storage priority**, and
  the crane reaches into it through its open front. The interface is unchanged and still the right answer for the long
  tail you own three of — swords, enchanted books, anything a whole bay per type would waste — and both kinds of
  location live side by side in one aisle. One thing is different from a chest, and it is the point of the block: a
  bay holds **one item type at a time**. Give it a filter and that is what belongs there; leave it unfiltered and it
  takes the first type that arrives and keeps it until the bay is empty again. A second type is simply refused — your
  hand keeps the item, a funnel backs up instead of hammering the block, and the warehouse plans that delivery
  somewhere else
- **Material decides how much a bay holds, and a server can move the whole curve.** A wooden bay holds **64 stacks**
  of its one item type, an andesite one **256** and a brass one **1,024** — 4,096, 16,384 and 65,536 cobblestone.
  They are counted in stacks rather than items, the way Create counts an Item Vault, so one number stands on the block
  whatever is in it. All three live in a new **`storage`** section of `wareworks-server.toml` — `woodBayStacks`,
  `andesiteBayStacks` and `brassBayStacks` — beside the `aisle`, `crane`, `stations`, `controller` and `chunkLoading`
  sections a modpack already tunes. Lowering one below what a bay already holds never destroys anything: that bay
  keeps every item and accepts nothing more until it has drained
- **Fill and empty a rack bay with your own hands.** Right-click it with an item and **one** goes in; hold **Shift**
  and a whole **stack** goes in. Right-click with an **empty hand** and one comes back out; Shift and you get a stack.
  The same two amounts in both directions, exactly what a plain click and a Shift click already mean at a warehouse
  terminal. There is deliberately no "take everything": a bay holds up to a thousand stacks and your pockets do not,
  so emptying one in a single move is what breaking it is for. Nothing is ever lost on the way — you only ever get
  what fits, and what the bay really took is what leaves your hand. The little filter box in the middle of the face
  still keeps every click that hits it, a **wrench** still turns the bay, a **clipboard** still copies its filter and
  priority onto the next bay, the **Mechanical Arm** item still places an arm, and **any bay** — rack or fluid — is
  always placed against the one you clicked rather than stored in it, which is how a wall grows. Two things to know:
  while a bay would accept what you are holding, clicking it — plain or with **Shift** — puts the item in instead of
  placing a block against it, so aim at a neighbouring block for that; and a bay you have renamed an item for will
  tell you that aisles are named at a **controller** or an **interface**, rather than swallowing the item
- **The goggles read a rack bay out loud.** Look at one through Engineer's Goggles and it names itself —
  **"Brass Rack Bay"** — then tells you where it stands, what may go in, and last what is in it:
  **"Cobblestone 4,096 / 65,536"** and **"Capacity: 1,024 stacks"**. A bay nobody has filtered says
  **"Takes the first item that arrives"** while it is empty and **"Holds Cobblestone until it is empty"** once
  something has landed in it, so you can always tell the item the bay decided on from the one you set with a filter. A
  bay carrying a stronger one above it says in gold that the rack above it is overloaded, which is why the warehouse
  stores nothing there — and it still hands out everything that is already inside
- **Break a full rack bay and nothing is lost: the load lands on a pallet.** A bay holding a thousand stacks cannot
  simply spray them across the floor — that would be thousands of item entities for one block, and a whole wall of
  them would bring a server to its knees. So breaking a bay **resets** it: you get an empty bay back and the whole
  load lying in front of you as a single **pallet** — wooden boards over three runners with the goods standing on them
  — with the item and the count written above it so you can see at a glance that everything is still there. You refill by hand, stack by stack, with the very gesture the bay uses:
  right-click the pallet with an **empty hand** for one item, **Shift** for a whole stack. A pallet cannot be picked
  up, pocketed or put back as a filled bay — carrying a thousand stacks in one slot is exactly what this is not — but
  you can **shove it across the floor** to wherever you want it
- **A pallet does not evaporate, burn or drown.** It never despawns, however long you leave it; it shrugs off fire,
  lava, an explosion and anything else that could damage it; it floats in water; and it survives saving, reloading and
  the chunk around it being unloaded with its load intact. The one way to lose one is to push it into the void, which
  writes a line into the server log saying exactly what fell and where. A **vanilla hopper** underneath will drain a
  pallet for you, item by item, and so will a **Deployer** — but Create's belts, chutes, funnels, depots and ejectors
  cannot see a pallet at all, which also means none of them can ever make one disappear
- **A funnel, a chute, a belt or a hopper fills a rack bay directly — and that is new.** Until now the only automated
  way into a warehouse was a **warehouse input** and the crane, because an interface deliberately offers a machine
  nothing at all. A rack bay offers its contents on every face, so a Create **funnel**, a **chute**, a **belt** or a
  plain vanilla **hopper** fills it and empties it where it stands, with no input station and no warehouse anywhere
  near it. Nothing about the rules bends for that: a machine moves items one real handler call at a time into the
  block in front of it, nothing teleports, and the warehouse is merely **told** that the bay changed — in the same
  tick, which is a better answer than a chest behind an interface has ever had. A **Mechanical Arm** is the one
  machine that cannot reach a rack bay: clicking one with the arm item places an arm, as it does on a rail
- **A bay refuses to be placed where it would carry something stronger.** The rule is that a bay may carry nothing
  stronger **anywhere** above it, and you meet it while building rather than in a tooltip: try to put a wooden bay
  under an andesite one and the block is not placed, the item stays in your hand, and the action bar says
  **"A rack bay may carry nothing stronger above it"**. That is what makes upgrading a wall a rebuild from the bottom
  up. A column that a command or a world edit put in the wrong order is not broken either — the bay underneath keeps
  every item in it, still hands them back out, says in gold through goggles that the rack above it is overloaded, and
  only stops being offered new goods
- **Rack bays you can craft, hold and look at.** All three stand in the Wareworks creative tab, directly after the
  warehouse interface, and the **wooden** one is the cheapest thing this mod makes after a rail: six planks and two
  andesite alloy, with no machine anywhere in its chain, so you can put a wall of them up the same evening you make
  your first andesite alloy — a better barrel, long before there is a warehouse. The ladder is the same frame one
  material up: six **andesite alloy** and two andesite casings for the andesite bay, six **brass sheets** and two
  brass casings for the brass one, for four and sixteen times the capacity
- **A wall of rack bays looks like pallet racking, not like a stack of crates.** A bay standing on its own is a rack
  frame: an upright at each end, a load beam across the front and back, the pallet on it, and the bay open towards the
  aisle so you can see what is in it. Put two side by side and they share the upright between them instead of each
  bringing one, so a wall grows into a single structure the way real pallet racking does — uprights at an even pitch,
  the beam lines running unbroken from one end of the wall to the other, and nothing in the middle of it that reads as
  the edge of a box. It works in every direction a bay can face, up every column, and around a corner where two runs
  meet: two runs that look different ways are two racks, and they keep their own ends. A bay that carries something
  stronger above it still looks exactly like one that does not
- **And you can see how full one is from across the room.** The goods on the pallet grow in four steps as the bay
  fills: a single carton when there is anything at all in it, cartons across the whole pallet when it is nearly full,
  one more stacked on top when it is full. It is part of the block itself rather than something drawn on top of it, so
  it is still there at any distance — walk past a rack wall and you can read which bays are still worth filling
  without goggles and without opening anything. The level is a fraction of that bay's own capacity, so a wooden bay's
  worth of cobblestone fills a wooden bay and barely covers the pallet of a brass one. The three materials are the
  same rack in three colours — wood, andesite, brass — so a wall says what it is made of at a glance
- **And up close a rack bay says *what* is in it, not only how much.** Stand in the aisle and the item a bay stores is
  there at the mouth of the bay, in front of the goods: a block of cobblestone, a sheet of paper standing up facing
  you, an iron ingot, whatever that location is for. Walk an aisle and you can read what every bay holds
  without goggles, without a terminal and without clicking anything. Flat things stand up so you can see them, blocks
  stand the way they do in the crane's grabber and at the same size, so nothing changes size when the crane sets it
  down — and a furnace faces you rather than showing you its back. Step away and the items stop being drawn while the
  cartons on the pallet stay, which is the whole idea: from across the warehouse the shape says how full every bay is,
  and from the aisle the item says what it is. That cut-off is Create's own **filter item render distance** in the
  client settings, ten blocks by default, and it is shared with the warehouse interface's filter for a reason — a
  warehouse places bays by the hundred, and drawing a hundred items from sixty blocks away is frames better spent on
  the crane
- **A rack bay that no warehouse serves says so, and says it is fine.** Standing on its own it reads
  **"Not part of an aisle"** and, under it, **"A rack bay works by hand with no warehouse"** — because that is the
  whole point of the block: it is worth building long before there is a crane, and nothing about it is broken until
  one arrives. Build the wall first and the aisle later; the moment a crane can reach it, the same block shows its
  address instead
- **Three Ponder scenes teach the rack bay, and they teach the block before the warehouse.** Hold **W** over any of
  the three bays and the first scene shows one bay standing on its own with no crane, no controller and no rails
  anywhere: how a click puts one item in and Shift a whole stack, how an empty hand takes them back out, how the load
  on the pallet grows as it fills, how a bay keeps the first item type that lands in it until it is empty, and what
  breaking a full one really does — the empty block back and the whole load on one pallet, with the count written
  above it. The second builds a rack wall out of all three materials and shows the rule that decides how you upgrade
  one: a bay carries nothing stronger above it, so a wall is rebuilt from the bottom up, and a column that a command
  put in the wrong order keeps every item in it and only stops being offered new ones. Only the third brings a
  warehouse: rails laid in front of a bay somebody had already filled by hand, what each bay is called afterwards, the
  crane reaching in through the open front of the empty one beside it, and why a chest behind an interface is still
  the right answer for the things you own three of. All three scenes have real goods in them, so the cobblestone a bay
  keeps is something you watch arrive and watch being refused rather than something a caption claims: the first bay
  shows it the moment the first one goes in, the wall is a wall with stock in it, and in the aisle the hand-filled bay
  shows what a hand put there while the bay beside it stays empty until the crane delivers into it. The order is
  deliberate — a wooden bay costs six planks and two andesite alloy and needs no machine at all, so most players will
  meet this block long before they own a crane. The scene also says the one thing you would otherwise find out the
  hard way: a **vanilla hopper** underneath a pallet drains it and a **Deployer** can, while Create's belts, chutes
  and funnels cannot see a pallet at all
- **A fluid bay is the same block for fluids: a storage location that is a tank.** One fluid bay holds **one fluid**,
  **64 buckets** in copper and **256** in brass, with its own address, its own filter and its own storage priority —
  so a tank wall is one block per 64 buckets instead of a multiblock. For scale: a Create Fluid Tank holds 8 buckets
  per block, so a copper bay is eight of them and a brass bay holds more than a 3×3×3 tower of them. There is
  deliberately no wooden and no andesite one: in Create fluids are copper, and a wooden barrel of lava is an
  explanation nobody should owe. The two stand in the creative tab right after the three rack bays, and they are the
  rack bay's own frame with its open middle filled in: **seven copper sheets and two copper casings** for the copper
  bay, **seven brass sheets** and again **two copper casings** for the brass one — the brass bay takes **copper**
  casings rather than brass ones, which follows the vessel inside it: it is copper in both tiers, as a tank in Create
  is. A bay behaves like its item sibling everywhere it can — it carries nothing stronger above it, so a tank wall is
  rebuilt from the bottom up when you upgrade it, and where a tank stands beside a rack of the same facing the two
  **share the upright between them**, because a wall is a wall. Both numbers are server config in the same `storage`
  section as the rack bays' (`copperFluidBayBuckets`, `brassFluidBayBuckets`), and lowering one below what a bay
  already holds never destroys anything: that bay keeps its fluid and accepts nothing more until it has been drained
- **Create's pipes connect to a fluid bay on every face but the one towards the aisle.** That is the bulk route into
  one and the thing a tank wall is really for: one Mechanical Pump within range fills a brass bay from a lava lake,
  and a second one on the other side feeds a machine from it. The aisle face carries no pipe connection at all, so a
  pipe can never end up in the crane's lane and the face you click keeps its own meaning. Whether a pipe may also
  **draw fluid off** a bay is a new key in the same section, **`fluidBayPipeExtraction`**, on by default — set it to
  false for a one-way tank that pipes may only fill. Filling is never affected by it, and neither is anything you do
  by hand. One thing is different from a rack bay, and it follows from how containers work: a **Funnel, a Chute, a
  Belt or a Hopper cannot fill a fluid bay**. A funnel handing a bay a lava bucket would have to be given the empty
  bucket back, which a funnel does not read — it would take the lava and destroy your bucket — so a container reaches
  a fluid bay only through a pipe, your own hand, or the crane's own handling head. A bay also takes **one fluid at
  a time**, the way a rack bay takes one item type: put a bucket of something in its filter slot and the **fluid**
  inside that bucket is what belongs there, or leave it unfiltered and it takes the first fluid that arrives and keeps
  it until it is empty again
- **You read a fluid bay by looking at it: the level is the readout.** A fluid bay is the rack bay's own frame — the
  same beam at every level, the same posts, and the same shared upright where a tank stands next to a rack — with a
  copper **vessel** where a rack bay carries its pallet, open towards the aisle. The fluid stands in that vessel and
  is drawn in the fluid's own colour, so lava glows, water is blue, and a tank that holds a quarter looks like a tank
  that holds a quarter. A bay with a single millibucket in it still shows a film, because "is there anything in this
  one at all" is the question you ask walking past, and an empty one shows a bare copper floor. A gas hangs from the
  rim instead of lying on the bottom. The vessel is copper in both materials and the frame around it tells you which
  bay it is, exactly the way a rack bay's pallet is wood in all three — in Create a tank is copper, so a brass rack
  holding a copper vessel reads as what it is. The tank stops just under the slot the crane's arm reaches through, so
  a crane fetching from a full bay passes over the fluid rather than through it
- **A tank wall is readable from across the room.** The item drawn in a rack bay and the filter item on a warehouse
  interface fade out after ten blocks, because a warehouse places those by the hundred and each one costs a frame
  something whether you can see it or not. A fluid bay's level does **not** fade: it is the whole readout rather than
  a detail of a block you can see anyway, and a tank you have to walk up to in order to tell full from empty is not a
  gauge. So the fluid is drawn as far as any other block entity in the game, 64 blocks, and what that costs is
  bounded by how many **fluids** a warehouse holds — three or four — rather than by how many item types
- **Fill and empty a fluid bay with your own bucket.** Right-click a bay with a **filled** bucket and it empties into
  the bay; right-click with an **empty** one and it fills from the bay. A container always moves **whole** — it is
  emptied completely or refused, never half — so a bay with less than a bucket of room left takes nothing rather
  than swallowing part of it, and a bay that holds another fluid simply says no. Hold a stack of sixteen empty buckets
  and one of them is filled and tucked into your inventory, which is how every tank in the game behaves. The face
  works exactly as a rack bay's does — the filter box, the wrench, the clipboard, the arm item, and a bay placed
  against the one you clicked. Two things differ: **Shift** has no meaning here, because a bucket is a bucket, so a
  sneaking click still places a block against the face as it always did; and a bay you have renamed an item for will
  tell you to name aisles at a controller instead rather than quietly empty your renamed bucket into itself. One
  surprise is deliberately gone: a **plain** right-click with a bucket of lava at a bay that cannot take it does
  **nothing at all** instead of pouring the lava against the front of your warehouse. That holds for a bucket in
  either hand — your off hand fills and empties a bay exactly as your main hand does. It does not hold for a
  **sneaking** click, which the game never offers a block at all: Shift keeps its own meaning here, so a sneaking
  click with a bucket pours it, the same way it does at any other wall
- **Goggles read a fluid bay out in full.** Its material, where it stands in the warehouse — or that no warehouse
  serves it, which for this block is perfectly normal — which **fluid** belongs here, what is in it in buckets
  ("Lava 37.25 / 64 buckets"), its capacity, and whether **pipes may draw from the back** or only fill, which is
  stated either way so you never have to guess which it is. A bay holding less than a hundredth of a bucket says the
  millibuckets instead, because a tank that is being filled must not read as empty. A filter slot that names no fluid
  at all — a list filter, an attribute filter, an empty bucket — says so in gold and then says what the bay
  really does, so a bay you thought you had dedicated cannot look dedicated
- **A fluid bay warns you three times before you break it.** Breaking one **loses what is in it**, exactly as breaking
  a Create Fluid Tank does: a fluid has no form it could drop as, so there is nothing to put the contents into. So the
  **item description** says it before you ever place the block, the **goggles** say it in gold while there is anything
  in it, and the **first hit** on the block puts it in your action bar, naming the fluid you are about to lose. None
  of the three stops you — a block you cannot break is worse than one that tells you the price — and the empty
  bay always comes back. Taking a bay away with a **wrench** names the fluid too, though a wrench is one click, so
  there that line arrives as the bay goes rather than before it. For a server owner the loss is also a single line in
  the log, with the fluid, the amount and the position, so an emptied tank wall is never a mystery. Commands that
  replace a block (`/setblock`, `/fill`, `/clone`, a structure) empty a bay silently, exactly as they empty a chest
- **The crane fills a fluid bay for you: a filled bucket goes in, an empty bucket comes back out as stock.** Put a
  bucket of lava into a **warehouse input** and the warehouse carries it to a fluid bay that takes lava, the bay
  **drains it**, and the crane shelves the now-empty bucket in an ordinary chest or rack bay like any other item. The
  warehouse then holds **lava as a fluid** and **a bucket as stock**, with nothing pretending one is the other. A
  container is always moved **whole** — emptied completely or refused, never half — so you never end up with a
  quarter-full bucket nobody can stack, and the empty buckets you get back are ordinary stock you can pick up, craft
  with or set a stock-keeper minimum on. Nothing teleports: the crane really drives to the bay, really empties the
  bucket into it and really carries the empty one away, and the bucket is in exactly one place at every moment of the
  trip
- **A fluid bay takes a container of its fluid before any shelf does — and this one will surprise you.** A bay that is
  dedicated to a fluid outranks **every** chest and rack bay for a container of that fluid, including one standing
  much closer to the input. So while a lava bay has room, a bucket of lava arriving at an input always goes **into the
  bay** and can no longer be kept on a shelf: if you wanted a crate of lava buckets for building, keep them out of the
  warehouse, or fill the bay to the brim first. The flip side is the part you want: a wall of fluid bays fills itself
  from one input without a single filter or priority anywhere else
- **Everything that is not a container of a bay's fluid stays ordinary stock.** A bucket of **water** at a lava bay, an
  **empty** bucket, a bucket of milk, a block of cobblestone — all of them are shelved in a chest or a rack bay as the
  items they are, whatever room the bay has and whatever priority you gave it. An **unfiltered** bay is the one to
  watch: it takes a container of **whichever fluid reaches it first** and only that fluid afterwards, so put a filled
  container in its filter slot if you care which one it is
- **A bay with less than a bucket of room is never sent one, and the goggles say why.** A container is emptied whole or
  refused, so a bay with 999 millibuckets free takes **nothing** from a bucket — and because its readout says
  "Lava 63.00 / 64 buckets", that refusal would look exactly like a lost bucket. Such a bay now says in gold
  **"Less than a bucket of room: a whole bucket does not fit"** while that is really the case. The warehouse never
  sends the bucket there in the first place: it is shelved as an item instead, and a pipe topping the bay up or
  drawing it down changes the answer the moment it happens
- **The warehouse now says how much of what fluid it holds.** Until now the fluid was only on the bays themselves, so
  a tank wall was something you had to walk along and count. Look at the **warehouse controller** through Engineer's
  Goggles and two new lines stand under the item ones: **"Fluid types: 2"** and **"Fluid stored: 60.00 buckets"**. They
  are a second pair beside **"Item types"** and **"Items stored"** and never mixed into them, because a warehouse that
  holds **lava: 60 buckets** and **bucket: 17** has to be able to say both without either number pretending to be the
  other. A warehouse with no fluid bay shows exactly the tooltip it always showed — the two lines appear only once
  there is fluid to report — and a bay drained down to a few drops says **"Fluid stored: 7 mB"** rather than
  rounding itself down to nothing
- **A new Display Link source, "Fluid Stock", puts the fluid on a board.** Point a Display Link at a **warehouse
  controller** or a **warehouse terminal**, pick it from the list beside "Warehouse Summary" and "Stock List", and the
  board writes one row per fluid, most first: **"48.0B Lava"**, **"12.0B Water"**. The unit is Create's own, so the
  Display Link's **"shortened / full number"** switch is what decides between buckets and millibuckets, exactly as it
  does for a Smart Observer on a tank. It is a source of its own rather than more rows on "Stock List" for the reason
  above: one number column cannot carry 17 buckets and 48,000 millibuckets and be read as one scale
- **"Warehouse Summary" gained a fluid line.** Under the four rows every board shows — the warehouse and its status,
  the locations in use, the item types and the items stored — a warehouse that holds fluid now adds
  **"Fluids: 2 · 60.0 B"**. It is the first of that source's optional rows, so a short board keeps it ahead of the
  aisle letters, the ports and the names, and a warehouse without a fluid bay writes the same four rows it always did
- **A Ponder scene teaches what becomes of the bucket.** Hold **W** over either fluid bay — or over a **warehouse
  terminal**, where it is also listed — and **"Fluids Travel in Containers"** shows the whole of it in one scene,
  because this is the part of the mod you could not guess by looking at it: a bay filled by hand with a bucket, a
  **pipe at the back** doing the bulk work, the aisle that turns it into an ordinary storage location whose filter is
  a **fluid**, and then the loop — a bucket of lava dropped into a warehouse input, the crane carrying the whole
  container, the bay draining it, and the **empty bucket coming back as ordinary stock** in a rack bay beside it,
  with the warehouse counting the lava as a fluid and the bucket as an item. It ends on the two things a fluid bay is
  deliberately worse at than its item sibling, so you meet both before they cost you anything: a **funnel, a chute, a
  belt or a hopper cannot fill one** — it would keep your empty bucket — and **breaking a full one loses the fluid**.
  Both languages, as always
- **What a warehouse that holds fluid cannot do yet.** You cannot **ask** for a fluid at a terminal or a port: fluid
  leaves a warehouse through a pipe at the back of a bay, or through your own bucket. That half — the request, the
  empty-container economy and the terminal's fluid rows — comes next in this series

### Changed

- **The goggle line for a dead store filter now names a storage location instead of an interface.** A filter or a
  priority does nothing when another location already counts the same inventory, and the gold line saying so read
  "Without effect: another interface counts this inventory". The location doing the counting can now be a **rack
  bay**, which is not an interface, so the line reads **"Without effect: another storage location counts this
  inventory"**
- **"No matching filter" no longer blames a filter for a refusal no filter caused.** An aisle that could place an
  input's items nowhere used to report "no storage location has a filter that accepts these items". A **rack bay**
  also refuses items because it already holds another type, or because the column rule has taken it out of service,
  and neither of those is a filter — so the line now reads **"no storage location takes these items, whatever room it
  has"**, which still tells the case apart from a warehouse that is merely full
- **A terminal with nothing to list no longer says the warehouse holds nothing.** A warehouse can hold **fluid** now,
  while a terminal lists **items** — so a wall of lava bays read as **"The warehouse holds nothing"** on the terminal
  while the controller's goggles counted every bucket of it. The empty grid now says **"The warehouse holds no
  items"**, which is true whichever way a warehouse is stocked. Asking a terminal for a fluid is still what comes next
  in this series

### Fixed

- **Two captions no longer overlap in the Ponder scene "Packages at a Warehouse Door".** While the crane delivered
  into the out door, the sentence about that trip stayed on screen for about a second and a half after the next one
  had appeared — two lines of text drawn through each other on two neighbouring blocks, both unreadable

- **The warehouse stock keeper's front panel is a panel again instead of half a panel and half black.** Its display
  stretched the whole of Create's factory-panel texture sheet over one face, and nearly half of that sheet is empty
  space — which a block drawn in the solid layer paints pure black. So the stock keeper has stood there since the
  stock rules arrived with black rectangles inside its frame and a black band down its right-hand side and along its
  bottom edge. It now shows the same light display plate Create's own factory gauge uses, and nothing about the block
  moved

- **A drawer behind a warehouse interface is filled past one stack again.** Any inventory that holds more than a stack
  per slot — a drawer, a barrel from a storage mod, a crate — was handed exactly one stack and then written off as
  full for the rest of the world's life: the warehouse judged the slot by the stack size of the item in it and never
  asked the drawer itself, and because it needed to see more than a stack before it would believe the drawer, nothing
  could ever get it there. Such a place is now offered to the crane and the drawer answers for itself, so it keeps
  filling to its real capacity. Ordinary chests, barrels and machines are untouched: a chest slot holding a full stack
  is still full, and still costs the warehouse nothing to pass over

## [0.8.0-alpha] - 2026-10-04

### Added

- **Give an aisle a name, with an item you already renamed.** Right-click a **warehouse controller** with an item you
  have named in an anvil and the aisle in front of it is called that; right-click a **warehouse interface** and the
  aisle *it stands in* is called that — so naming aisle B is a matter of clicking something that stands in aisle B,
  with no syntax to remember and nothing to type. A plain **name tag** takes the name off again. The action bar answers
  every click with exactly one line: which aisle is called what now, that the aisle has no name any more, or that the
  block belongs to no aisle yet — and a name longer than 16 characters is shortened in that same line, so it still
  tells you which aisle it went on. The item is never used up
- **A name belongs to its aisle and stays there.** It survives saving and reloading, a chunk unload, a dock you break
  and build again, and the aisle letter being scrolled from A to D — the name follows the letter, and scrolling back
  puts everything where it was. Only breaking the controller takes the names away, together with everything else it
  knows. Click the **face** of the block, a little away from the value box in the middle: that box still keeps every
  click that hits it, a **wrench** still turns the block however you named it, the clipboard still copies the aisle
  letter, and sneaking still places a renamed block the way it always did
- **The name then stands wherever the letter stands.** With goggles on, the controller reads **"Warehouse A — Ores"**,
  and its list of aisles gives each named one its name where it used to give a rail count: **"Aisles: A Ores · B
  Metals · C 14"**. Standing at any block of the warehouse — an interface, an input, an output, a port, a terminal, a
  production station, a stock keeper, a waiting place — its address now reads **"Address: B-03-07R (Ores)"**, which is
  the line you see without looking anything up. A **Warehouse Summary** display board gets one more row,
  **"Names: A Ores · B Metals"**, as the **last** row of all — under every count and every warning it already showed,
  so naming an aisle can never push something you asked for off the board. The row also fits itself to the board it is
  on: a narrow one names as many aisles as there is room for and counts the rest, **"Names: A Ores (+2)"**, instead of
  being cut off in the middle of a word. The address itself never changes: the name stands beside it, because the
  address is what the terminal and every report speak
- **Until you have named something, the controller tells you how.** A quiet grey line at the end of its goggle text
  says to right-click with a renamed item — and it goes as soon as that tooltip has a name of its own to show you
- **The goggles say what the crane got done.** Look at a **stacker crane dock** through Engineer's Goggles and the
  machine reports the last minute of its own work: **"Busy: 78% of the last minute"**, and under it **"Blocked: 4%"**
  whenever something really held it up — a full output, a target it cannot reach, a rail somebody broke behind it.
  Then where that time went: **"Travel 54% · turning 6% (9 corners) · at the rack 18%"** — the corners counted are
  the ones it turned while working, so the count and the share beside it are about the same minute — and what came of
  it,
  **"Trips: 9 · items: 412"**. It answers the question you cannot answer by watching: is the crane the bottleneck,
  or has it simply got nothing to do
- **The numbers are honest about themselves.** A crane that has just been loaded, or whose chunks were away, says
  **"of the last 23 s"** and never scales nine trips up to a minute. The minute a chunk spent unloaded counts as
  neither work nor waiting, nothing is remembered across a restart — a machine that stood still all night does not
  open the morning claiming it was busy — and the breakdown adds up to the busy share printed above it, instead of
  the two being rounded apart. A crane with nothing to show shows nothing: a parked machine's tooltip reads
  exactly as it did before. The turning share and the corner count only appear on a warehouse that bends, because a
  straight aisle never turns
- **The controller carries the two numbers you came for.** Look at a **warehouse controller** through goggles and its
  crane block now ends with **"Busy: 78% of the last minute"** and, above zero, **"Blocked: 4%"** — enough to tell
  whether the crane is your bottleneck without walking to the dock. The long breakdown stays on the dock, where the
  machine is
- **A display board can watch the crane's output.** A **Display Link** on a stacker crane dock offers a second source,
  **"Crane Throughput"**: four rows with **"Trips: 9"**, **"Items: 412"**, **"Busy: 78%"** and **"Turning: 6%"**. The
  rows never move, so a four-row board never loses one, and while the crane has not yet run a whole minute the board
  says **"Measuring"** instead of showing a number about ten seconds as though it were about a minute. "Crane Status"
  is still the source a link offers first, so every display you have already built keeps showing what it showed
- **The in door for Create packages says that it is one.** Put a **Create Packager** against a **warehouse input** —
  back of the Packager to the block, which is how it faces itself when you place it — and addressed packages fed to it
  are taken apart into the warehouse. That already worked; now the input says so. With goggles on it reads **"Takes
  packages apart"** and counts them, **"Packages opened: 12"**, so you can see at a glance whether the door is doing
  anything at all
- **And it says why a package was turned away.** Create opens a package **whole or not at all**, so an input that has
  room for two stacks refuses a package of three kinds of item — and until now nothing anywhere in the game said a word
  about it: the box simply sat in the funnel. The input now reads **"Last package refused: stacks in it 3, free slots
  2"** in gold, which is the one line that tells you to wait for the crane rather than to rebuild the door. "Room"
  rather than "a free slot": a stack also goes into a slot that already holds that very item. The refusal goes as soon
  as a package fits again, and nothing of a refused package is ever lost — it waits where it is
- **A world from 0.7.0-alpha opens unchanged.** Nothing in this release writes anything new into your save until
  you use it: a controller grows its list of aisle names the moment you name one and loses it again when you clear
  the name, and everything else these two features show — the crane's busy minute, what a door says about packages —
  is worked out while you look at it and never stored
- **The out door for Create packages says that it is one.** Put a **Create Packager** against a **warehouse port**
  — back of the Packager to the block, which is how it faces itself when you place it — and a redstone pulse, or a
  **Smart Observer** facing the port that keeps emptying it by itself, packs whatever the warehouse put there into an
  addressed package. That already worked; now the port says so. With goggles on it reads **"Hands over as a
  package"**, and an **overflow** port says it too, so what your warehouse cannot keep can leave your base in boxes
- **The address is a sign, and the port reads it out to you.** Hang a plain **sign** on that Packager and the port's
  goggle text says **"Addressed to: Base North"** — read by Create's own rule, off the signs that are there right
  now, so what you are shown is what the next box the door sends will carry rather than what the last one did. Without
  a sign the line turns gold and says **"No address — hang a sign on the Packager"**, which matters more than it
  looks: Create only ever delivers an **unaddressed** box to a Package Port that has no name of its own, or to one
  named `*`, so a chain conveyor carries it past every other named one
- **And it names the one mistake nothing else in the game mentions.** A **Stock Link** on that Packager puts it on a
  logistics network, and from that moment it ignores **every** redstone signal — no pulse, no lever, no message, no
  particle, nothing anywhere to read, and the door simply never opens again. The port now says it in gold, **"The
  Packager is linked to a logistics network and ignores redstone"**, so one block you put down for another reason
  cannot quietly stop your deliveries for ever. While the link is on, that line stands **instead of** the address
  line: a linked Packager never reads the sign, so an address there would be a promise no box keeps
- **And Ponder shows you how to build both doors.** Hold **W** over a **warehouse port** or a **warehouse input** and a
  new scene, **"Packages at a Warehouse Door"**, builds the whole thing in front of you: the Packager's **back** against
  the station — which is the only thing that decides whether the door sends or receives, and the reason you cannot wire
  one the wrong way round — the sign that is the address, a **Smart Observer** over the port that sends a box whenever
  something is waiting there, about **one box a second** with up to nine stacks in it, and the warning that a package is
  opened whole or not at all. In English and German, like every scene before it

## [0.7.0-alpha] - 2026-10-03

### Added

- **Hand the warehouse a shopping list.** The Warehouse Terminal has one more slot, for a **clipboard**. Put a
  clipboard with a list of items in it, press the **list button**, and the warehouse works the whole list off: it
  fetches what it can, **ticks each entry off on the clipboard as it delivers it**, and waits whenever the terminal is
  full instead of refusing — so a whole building's worth of material arrives in portions while you carry the crates to
  the site. There is no "your output cannot hold 40 stacks"
- **The clipboard is the order and its receipt in one.** Pick it up at any time and the ticks tell you what arrived and
  what is still outstanding. An entry that arrived only partly stays **unticked**, because the warehouse never ticks a
  line it did not finish, and putting the clipboard back in carries on where it left off. The ticks belong to that one
  clipboard: a copy of it made with Create's own copying recipe stays as it was
- **The row under the delivery slots keeps saying what the terminal is doing.** While the list runs it counts the
  entries down; when the list is finished it says so for a moment and then goes back to the crane, and the finished
  receipt stays on the clipboard's own tooltip. A terminal that has lost its aisle or its crane says **that** first,
  because it is the reason the list is not moving
- **A Schematicannon's material checklist works as it comes.** Print the checklist onto a clipboard at the cannon and
  carry it to the terminal — "build this schematic" becomes "hand the warehouse the shopping list". A hand-written
  clipboard works exactly the same, and a read-only checklist is still ticked off
- **It asks before it starts, if it has to.** If the list wants more than the warehouse holds, the terminal says how
  much is missing, names the entries it is short of and asks whether to fetch what there is. If something on the list
  would have to be **produced** first, it asks about that too — it never starts your machines for a list without
  being told to. A clipboard so long that part of it was left out says that in the same dialog, before anything is
  fetched. A list a stocked warehouse covers starts with no questions at all
- **A question that comes up while the list runs names what it is about.** The warehouse asks again, item by item,
  whenever one portion would reach into a reserve, store something above a maximum or start a production run bigger than
  you agreed to — and that panel names the item, how many of it, and what would be made. Saying **yes** pays for that
  one item and nothing else, saying **no** leaves the rest of the list for later instead of stalling it, and the list
  button brings the question back if you closed the panel
- **An entry the warehouse can neither stock nor produce never blocks the rest.** It is stepped over, left unticked and
  counted in the dialog; everything behind it is still fetched. A list that cannot be served at all stops trying after
  a minute and waits for you to click it again, instead of leaving the crane running
- **Nothing new happens underneath.** Every entry becomes an **ordinary** request, so batching, storage filters,
  priorities, reserves, maxima, production chains and the safety stop all apply unchanged, no item teleports, and the
  crane physically fetches every single one. Four new server config keys in the `stations` section
  (`maxTerminalListEntries`, `terminalListOpenRequests`, `terminalListIntervalTicks`, `terminalListStallTicks`)
- The terminal's goggles say how many entries of a clipboard order are done and how much is still to fetch, and its
  Ponder scene "Requesting Items at a Terminal" ends on the new beat
- **The terminal can sort by what you actually use.** The sort button has a third order, **"most used"**: the items you
  request most often come first, so a warehouse holding hundreds of item types opens on the twenty you keep fetching
  instead of on the twenty it has the most of. Each request counts once, whatever amount it asked for, so one
  ctrl-click on a thousand cobblestone does not outrank a hundred deliberate requests. The terminal remembers a
  bounded number of item types per player and lets an old habit **fade** as a new one grows, so the order follows what
  you are building now rather than what you built in your first week. Until you have requested anything it is simply
  "most available first", and the search, the "only what is available" filter and the offers a production station
  makes all behave exactly as before. Handing the terminal a **clipboard list** counts every item type on it once, so
  the lists you fetch again and again are what the order learns from most
- **What the warehouse actually holds always comes first.** A favourite the warehouse has just run out of does not jump
  to the top of "most used" — not an item a production station could make, and not one a Warehouse Stock Keeper is
  calling for: both keep their row, behind everything you can really have right now. The top of the list is never a
  row that cannot be ordered
- **The order you pick is yours, and it stays picked.** The terminal remembers which of the three orders you chose and
  how often you have asked for each item — **per player, on the server**. So it is the same at every terminal of the
  world, it survives closing the game and rejoining, and on a server everybody has their own order and their own list
  of favourites
- **One button, three orders you can tell apart.** Each order has its own icon — chevrons for *most available first*,
  a target for *most used*, a list for *by name* — so the button says which one is on without being hovered. Hovering
  it names the order, says in a sentence what it does and tells you which order the **next** press would give, so the
  cycle does not have to be learned by pressing it; while you have requested nothing yet, it also says why "most used"
  looks like "most available first". Changing the order takes you back to the top of the list, because that is where
  the answer to the new order is, and nothing else ever moves the list under you
- **Clicking the same cell twice asks for the same item twice.** Under "most used", requesting something raises its
  count, which would otherwise pull that row to the front of the list about a tick after you clicked it — so a second
  click on the same spot would have ordered whatever had slid into it. The new counts therefore take effect the next
  time *you* ask for a list: when you press the sort button, type in the search or flip the filter. The list never
  reshuffles under the cursor that is using it, and the next terminal you open is in the full order
- Only a **player's** request teaches the terminal anything: clicking an item, and handing it a clipboard list. A
  redstone request at a **Warehouse Output** — the warehouse's port — counts for nobody, because a port is not a
  player. One new server config key,
  `maxTerminalUsageEntries` in the `stations` section, says how many item types the terminal remembers per player
  (64 by default). What is remembered is bounded in size as well as in number: an item carrying a whole inventory with
  it — a filled shulker box, a written book — is not remembered at all, so nobody's save file can grow by kilobytes
  per favourite. Requesting such an item works exactly as before; it simply never joins the order

### Changed

- The Warehouse Terminal's window has **one slot more** (the clipboard slot, at the right-hand end of the delivery
  row); the window is the same size, because the delivery row gives up a column for it. A shift-click moves a clipboard
  in and out. Delivery slots, the stock list and every other click work exactly as before

### Fixed

- **The terminal's status row no longer cuts off what the crane is doing.** The row said "Crane: …" and then ran out of
  space, so in German the most ordinary line of all — "Regalbediengerät: Wartet auf einen Auftrag" — was drawn with its
  end missing, and several of the longer English phases came within a few pixels of the same thing. The row now drops
  the "Crane:" label whenever keeping it would cut the text, and shows what the crane is doing in full instead; nothing
  changes for the lines that already fitted. Two kinds of text are still longer than the row and end in an ellipsis:
  the line that says a crane speed factor is 0 in the server config, and the longest refusal reasons — four of the
  eleven in English and seven in German, among them the one that says production has stopped. Both are readable in
  full through engineer's goggles, which have no fixed row to cut
- **The clipboard order's progress line is no longer cut off.** The row under the delivery slots counts a list down as
  "List 2/9, 320 left (…)", and the word in brackets used to be a whole sentence — "waiting for your answer", in
  German "wartet auf deine Antwort" — which did not fit beside three numbers and was drawn with its end missing, in
  both languages. Each state is one word now (fetching, asking, paused, done), everywhere it appears: the status row,
  the clipboard's own tooltip and the terminal's goggle line. What to do about it is on the list button right beside
  the row, which changes its face and its tooltip with the same state
- **Three German answers of the clipboard order ran past the end of their row.** Pressing the list button told you
  that the question was back, asked you to answer it, or said the rest of the list would be tried again — and each of
  those sentences was drawn with its end missing. They say the same thing in fewer words now
- **A production station's order line cut away the state instead of the item's name.** The line reads
  "Oak Planks x128 - waiting for the result", and because it was trimmed from the end, a long item name pushed the
  half that actually changes off the row; the note that an ended order's ingredients are not coming back did not fit in either language
  even with no name in the line at all. The item and its amount now stand on the left and the state on the right, so
  the state always survives and a long name is what gives way — and the whole sentence is one hover away, in the
  line's tooltip. In the terminal, where the same note appears, the German wording now says plainly that it is the
  ingredients that are gone, which is what the English always said
- **A production station's red "stopped" row was cut off in both languages.** It reads "Stopped: Diamond - click to
  resume" now; the old wording did not fit the row even before the item's name was put into it. What stopped it and
  what it cost are still in that row's tooltip
- **A refused request shows the reason instead of running out of room.** The status row said "Request refused:" and
  then ran out of space for the reason itself. It drops that label now whenever keeping it would cut the text — the
  row is red already, and there is nothing else in the window it could be about — so seven of the eleven reasons are
  readable in full in English and four in German, where it was three and one. The longest ones still end in an
  ellipsis; a glance through engineer's goggles at the terminal shows them whole

## [0.6.0-alpha] - 2026-10-02

### Added

- **Your rails may turn corners now, and the crane turns with them.** Lay a run of Warehouse Rails in front of the dock
  as always, then lay a second run at right angles to it: the block they share becomes a **corner**. The crane drives out
  to it, stops, swings the whole machine a quarter turn with a deep rumble and a metal clack, and rolls on down the next
  aisle — still lifting on the way. A warehouse can follow the shape of your base instead of forcing a straight hall
  through it, and goods from every aisle reach one block
- **And they may split.** A T-junction, a cross, a ring, a comb of side aisles off one main run — lay the rails and it
  is one warehouse. The crane turns at every junction, a straight run through a junction stays **one** aisle with one
  letter however many aisles cross it, and goods from every aisle of the shape reach the **one** block you put your
  output station on. One controller, one terminal, one stock list, one address space
- **The warehouse works out the cheapest way there, and the crane drives that one.** With junctions there is more than
  one route from A to B, so the planner measures them in blocks driven **plus** quarter turns and takes the cheapest —
  round a ring it will take the short way round even when the long way has fewer corners, or the other way about if you
  have made turns expensive (`crane.turnPenaltyBlocks`). The same number decides the plan and the drive, so the machine
  can never take a route nobody costed, and the same warehouse always plans the same job
- **An aisle your crane cannot get to says so on its own blocks.** If a server limit cuts the rails that joined an aisle
  to the rest, its racks keep their addresses and their stock, their goggles read "The crane cannot reach this aisle" in
  gold, and the controller answers "cannot be reached" instead of inventing a job for it. Raise the number and the next
  scan joins it again, with every address and every item exactly as it was
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
- **And a Ponder scene for rails that split**, the third one on those same two blocks: "Rails That Split" builds a main
  run with two side aisles leaving the **middle** of it, names the block three rails meet at as a junction belonging to
  both aisles, outlines the whole run as **one** aisle through both junctions and each side aisle as its own letter,
  then runs the machine — straight over one junction, round the bend at another — until an item from **every** aisle has
  arrived at the one output beside the dock. It ends on the two racks beside a junction that lie next to both aisles at
  once, with the address each of them really has
- **The controller's goggles describe the whole warehouse.** A warehouse that bends reads "Warehouse: 41 rails, 3 aisles,
  mast 6 high" and lists them underneath ("Aisles: A 16 · B 12 · C 13", at most six before "and N more"). A warehouse of
  one aisle reads exactly as it always did
- **And they say where the rails stop, and why.** "Warehouse stops at 148 64 -37: another stacker crane dock stands
  here, and a dock is a wall" — one sentence per reason (a second dock, a rail closed with a wrench, a chunk that is
  not loaded, and each of the four server limits, every one of which names the number to raise), never merged into one
  message, and naming the block you have to walk to. It is the line that answers "why is half of what I built not in my
  warehouse"
- **The dock says which aisle its machine is on**: "On aisle B at position 7", beside the size of the network. Only for a
  warehouse that bends, where the position alone would not say where the machine is
- **The Warehouse Summary display source lists the aisles** of a warehouse that bends ("Aisles: A B C"), and marks it
  "(cut short)" whenever the rails stop short of what you laid — on a single straight aisle too — so the board says that
  something is wrong and the controller's goggles say what. It is the source that used to be called "Aisle Summary"
- **Two new server settings, both bounding what a big warehouse costs to run.** `aisle.maxJunctions` (default 32) caps
  the junctions of one warehouse, because junctions are what a route search is priced in — an aisle over the cap is not
  part of the warehouse and the controller says so, and **0 keeps every warehouse the single aisle at its dock** without
  switching the rest of the version off. `controller.snapshotCycleTicks` (default 10 minutes) bounds how long the
  controller may take to re-read every storage location once; it reads as many per interval as it takes to meet that,
  capped by `controller.maxSnapshotsPerTick`. At the defaults a warehouse of up to about 1200 locations reads exactly
  one location per interval, which is what every earlier version did

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
- **A rail that used to stop your warehouse now grows it — the second thing to read before loading an old world.** A
  T-junction, a cross or a ring used to stop the warehouse before the branching rail; all of it is now part of the
  warehouse. So a world where something you built was cut off there will find it **joined**, with new aisles under new
  letters and new addresses. Nothing is lost and nothing moves: the positions are simply new, and the `aisle.maxBranches
  = 1` or `aisle.maxJunctions = 0` setting, or one wrench click on a rail, is still the way to say no
- **A comb with one crane is slow, and you want to know that before you build one.** One crane serves the whole
  warehouse, so it drives out and back along every side aisle in turn and two trips can never overlap. The planner's
  travel key will prefer a rack on the aisle the machine is already on, and prefer near aisles to far ones, but it
  cannot make a far aisle cheap: a comb of five long teeth has the throughput of one crane, not of five. Several cranes
  on one warehouse is the next milestone, and this is why
- **A big warehouse notices a chest you emptied by hand as quickly as a small one does.** Nothing reports a change like
  that, so the controller finds it by re-reading storage locations in turn — and it used to read exactly one every half
  second whatever the size, so coming round to all of them took longer the more you built: about nine minutes for a
  full single aisle, 40 for 4800 locations, over two hours for the largest warehouse the settings allow. It now reads as
  many per turn as it takes to come round within `controller.snapshotCycleTicks` (ten minutes by default, and 0 switches
  the scaling off): 4800 locations come round in ten minutes instead of 40, and the biggest possible warehouse in 34
  minutes instead of 2.3 hours. **A warehouse of up to about 1200 locations reads exactly one location per turn, which is
  precisely what every earlier version did** — that is every warehouse at the default aisle limits. If you raised
  `aisle.maxAisleLength` or `aisle.maxMastHeight`, your warehouse may be bigger than that already, and it will now read
  up to `controller.maxSnapshotsPerTick` locations per turn instead of one; `controller.snapshotCycleTicks = 0` keeps
  the old cadence exactly
- **And it spends less time working out what to do.** Before every job the controller ranks every storage
  location for every item type waiting at every input, and the most expensive question in that ranking — "can the
  crane use this location right now", which now includes a route over the junctions — is asked **once per location per
  planning pass** instead of once per location per item type, with the list of usable locations worked out once as
  well. Measured on a 64-location warehouse with four inputs holding three item types each: **68** questions put to
  the world where there used to be 772, and the number no longer grows with the item types an input holds. What the
  planner *decides* is unchanged — the ranking order is identical, which its tests pin
- **An aisle is renumbered when the rails grow past its origin, and your addresses follow.** An aisle is numbered from
  the end nearer the dock, so extending it at its **far** end still changes nothing. Laying rails at its **near** end
  now makes it longer there instead of refusing to follow, and then every position on it moves along by that much —
  carried through the world blocks, so each chest keeps its filter, its priority, its stock and its reservations, and
  open production orders keep pointing at the same machines. A sign you wrote by hand is the one thing that goes stale
- **Optional chunk loading is now measured per warehouse, and its default cap rose from 8 to 10 chunks.** A corner turns one
  long rectangle into two shorter ones at right angles, so a warehouse that bends needs more chunks than any single aisle of
  the same length ever could: a straight aisle of 32 rails needs 8, an L of 32 + 16 needs 10, an L of two full aisles 12.
  The rule is unchanged — a warehouse over the cap holds **nothing at all** and works exactly as it always did — and the
  number it would have needed is now named in the controller's goggles **and in `/wareworks chunks`**. A server that had
  set the value itself keeps its own value, which is what a config is for; at 8 it will meet the cap at its first corner.
  The lines say **warehouse** where they used to count aisles — the goggle line for each of the two server limits, and
  every row and total of `/wareworks chunks` — because one holder is one controller and every aisle it owns
- The item descriptions of the Warehouse Rail, the Stacker Crane and the Warehouse Controller now say what all of them
  do since rails may bend and split: rails lay out **aisles** that may bend, split into side aisles and close into a
  ring, every straight run of them keeps **one letter** however many junctions it passes through, the machine turns at
  a corner or a junction and takes the cheapest way where the rails offer more than one, and one controller gives
  **every** aisle of its warehouse a letter — the first one from its own value panel, each further one the next free
  letter
- **The screens that speak for the whole warehouse now say "warehouse" instead of "aisle".** Before rails could bend,
  an aisle *was* the warehouse and every one of these lines was true; on a comb of four aisles they were not. So the
  controller's goggles and the terminal's screen head their warehouse "Warehouse A" rather than "Aisle A" — the letter
  is still the one your controller's value panel sets, and it is still the letter the first aisle carries — the
  terminal says "The warehouse holds nothing" and "Showing everything the warehouse holds", a stock keeper's rule that
  is over the limit says "this warehouse already applies its limit of rules", and a request from a block with no
  controller is refused with "not part of a warehouse with a controller". And because "the crane cannot drive to the
  aisle these items belong on" is a real answer now, the older one that read "an output is not reachable" says what it
  always meant instead: "an output station is not loaded or no longer there". The addresses, the aisle list and the
  dock's "On aisle B at position 7" still say **aisle**, because those really do name one. The two lines a block that
  belongs to no warehouse at all shows went with the warehouse ones, because they are drawn in the very slot that
  otherwise names it: a terminal says "Not part of a warehouse" and a Warehouse Summary display "No warehouse"
- **The German text calls the machine by one name.** It is "Regalbediengerät" in every line now; a handful still said
  "Kran", among them three captions of the "Collecting" Ponder scene

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

[unreleased]: https://github.com/Richie1710/create-wareworks/compare/v0.13.1-beta...HEAD
[0.13.1-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.13.0-beta...v0.13.1-beta
[0.13.0-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.12.1-beta...v0.13.0-beta
[0.12.1-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.12.0-beta...v0.12.1-beta
[0.12.0-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.11.1-beta...v0.12.0-beta
[0.11.1-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.11.0-beta...v0.11.1-beta
[0.11.0-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.10.0-beta...v0.11.0-beta
[0.10.0-beta]: https://github.com/Richie1710/create-wareworks/compare/v0.9.2-alpha...v0.10.0-beta
[0.9.2-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.9.1-alpha...v0.9.2-alpha
[0.9.1-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.9.0-alpha...v0.9.1-alpha
[0.9.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.8.0-alpha...v0.9.0-alpha
[0.8.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.7.0-alpha...v0.8.0-alpha
[0.7.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.6.0-alpha...v0.7.0-alpha
[0.6.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.5.0-alpha...v0.6.0-alpha
[0.5.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.4.0-alpha...v0.5.0-alpha
[0.4.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.3.0-alpha...v0.4.0-alpha
[0.3.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.1-alpha...v0.3.0-alpha
[0.2.1-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.0-alpha...v0.2.1-alpha
[0.2.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Richie1710/create-wareworks/releases/tag/v0.1.0-alpha
