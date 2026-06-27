# NewPrinceps

Princeps mod ported to **Minecraft 26.1** (Fabric).

Forked from [cabaletta/princeps](https://github.com/cabaletta/princeps) and updated for the unobfuscated MC 26.1.

## Download

**[Latest Release](https://github.com/XMRhapsody0807/NewPrinceps/releases/latest)**

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 26.1
2. Download the jar from [Releases](https://github.com/XMRhapsody0807/NewPrinceps/releases)
3. Place the jar into your `.minecraft/mods/` folder
4. Launch the game

## Usage

In game chat, type `#` followed by a command:

| Command | Description |
|---------|-------------|
| `#goto <x> <y> <z>` | Pathfind to coordinates |
| `#mine <block>` | Auto-mine a block type |
| `#follow player <name>` | Follow a player |
| `#build <schematic>` | Auto-build from schematic |
| `#explore` | Auto-explore the world |
| `#farm` | Auto-farm crops |
| `#stop` | Stop current task |
| `#help` | Show all commands |

## Porting Changes (MC 1.21.11 -> 26.1)

- Build system: Unimined -> Fabric Loom 1.15 + Gradle 9.4.0
- MC 26.1 is unobfuscated, no intermediary mappings needed
- `ChunkPos` is now a Java record (`.x` -> `.x()`)
- `ClickType` -> `ContainerInput`
- `GuiGraphics` -> `GuiGraphicsExtractor`
- Rendering pipeline: `DepthTestFunction` -> `DepthStencilState` + `CompareOp`
- `ItemStack.item` field type: `Item` -> `Holder<Item>`
- `LevelRenderer.renderLevel` signature updated

## Building from Source

Requires JDK 21+ (JDK 25 for runtime).

```bash
./gradlew jar
```

Output: `build/libs/princeps-*.jar`

## License

LGPL-3.0 - See [LICENSE](LICENSE)

## Credits

- [cabaletta/princeps](https://github.com/cabaletta/princeps) - Original project
- leijurv, Brady - Original authors
