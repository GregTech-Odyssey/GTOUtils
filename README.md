# GTOUtils

Shared utility library for the [GregTech Odyssey](https://github.com/GregTech-Odyssey) mods (Minecraft 1.20.1, Forge, Java 21).

GregTech Odyssey 系列模组的公共算法库。

## Contents

- `BlockOctree` — sparse octree over integer block coordinates, flat-array layout, zero-allocation range queries.
- `BlockRange` — query range around a block: sphere (`sphere`, `sphereSq`) or box (`box`, per-axis `box`).
- `BlockIndex` — one level's index of objects by block position (octree + change `version`), with `query`, `findAny` and `findNearest`.
- `LevelBlockIndex` — one `BlockIndex` per level instance, released automatically when the level unloads.

Typical use: register a block entity in `onLoad`, unregister it in `setRemoved`, and look up nearby ones with a range query instead of scanning every block around.

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
