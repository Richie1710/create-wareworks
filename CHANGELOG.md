# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

[unreleased]: https://github.com/Richie1710/create-wareworks/compare/v0.3.0-alpha...HEAD
[0.3.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.1-alpha...v0.3.0-alpha
[0.2.1-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.2.0-alpha...v0.2.1-alpha
[0.2.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Richie1710/create-wareworks/releases/tag/v0.1.0-alpha
