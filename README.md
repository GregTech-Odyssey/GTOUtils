# GTOUtils

Shared utility library for the [GregTech Odyssey](https://github.com/GregTech-Odyssey) mods (Minecraft 1.20.1, Forge, Java 21).

GregTech Odyssey 系列模组的公共算法库。

## Contents

- `BlockOctree` — sparse octree over integer block coordinates, flat-array layout, zero-allocation range queries.
- `BlockRange` — query range around a block: sphere (`sphere`, `sphereSq`) or box (`box`, per-axis `box`).
- `BlockIndex` — one level's index of objects by block position (octree + change `version`), with `query`, `findAny` and `findNearest`.
- `BlockEntityIndex` — block entities of a type (optionally filtered once when they are added), kept up to date automatically: GTOUtils hooks the chunk's block entity map, so placing, replacing, breaking and chunk unloading are all covered without code in the block entity. `BlockEntityIndex.all()` is one shared index of every block entity.
- `ProbedBlockEntityIndex` — block entities that may offer a capability: classes known to offer it are indexed directly; other classes are not stored again but taken from `BlockEntityIndex.all()` and probed once per instance when a query first covers their 16³ section; swept sections stay valid, and block entities placed into them later wait in a small tree of new arrivals until a query covers them.
- `LevelBlockIndex` — one `BlockIndex` per level that you fill yourself, for members decided by a condition (formed, joined a network).
- `LevelStore` — any per-level object stored on the level instance itself; dropped with the level and when it unloads.
- `BlockOrder` — sort results into vanilla `betweenClosed` / `withinManhattan` order, so replacing a block scan keeps "which one is found first".
- `SectionScan` / `LevelSections` — find the blocks matching a state predicate in a box, a `BlockRange` or a Manhattan-ordered box: 16³ sections whose palette cannot match are skipped without reading a block, and results come out in vanilla `betweenClosed` / `withinManhattan` order (with an optional limit that stops before farther sections).

Typical use: declare `BlockEntityIndex.create(MyBlockEntity.class)` as a static constant in a class that your mod constructor touches, then look up nearby ones with a range query instead of scanning every block around. Block entity indexes must be created during mod loading; creating one after levels start loading block entities throws.

## Usage

```groovy
repositories {
    maven { url = 'https://gtodyssey.com/maven/releases' }
}

dependencies {
    modImplementation("com.gto:gtoutils-forge-1.20.1:<version>")
}
```

Mod ID: `gtoutils`. Declare it as a required dependency in your `mods.toml`.

## Building

```bash
./gradlew test
./gradlew publishToMavenLocal
```

## License

[PolyForm Noncommercial License 1.0.0](LICENSE): noncommercial use only.
