# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[unreleased]: https://github.com/Richie1710/create-wareworks/compare/v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/Richie1710/create-wareworks/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Richie1710/create-wareworks/releases/tag/v0.1.0-alpha
