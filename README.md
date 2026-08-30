# OneBlock Princeps source

This repository is the complete corresponding source for the Princeps build
distributed with OneBlock for **Minecraft 26.1.2** (Fabric).

Forked from [cabaletta/princeps](https://github.com/cabaletta/princeps) and updated for the unobfuscated MC 26.1.

The compiled module is delivered by the OneBlock installer. This repository is
public so every recipient can inspect, modify, and rebuild the LGPL-covered
pathfinding module independently of the proprietary OneBlock client.

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

Requires JDK 25.

```bash
./gradlew jar
```

Output: `build/libs/princeps-*.jar`

## License

LGPL-3.0 - See [LICENSE](LICENSE)

## Credits

- [cabaletta/princeps](https://github.com/cabaletta/princeps) - Original project
- leijurv, Brady - Original authors
