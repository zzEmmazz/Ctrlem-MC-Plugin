# CtrlEm Minecraft Bridge

Client-side Minecraft mod that connects your game to the CtrlEm desktop client,
so people you allow on [CtrlEm](https://ctrlem.com) can trigger in-game actions.

## Versions

| Branch                                          | Loader | Minecraft | Settings |
|-------------------------------------------------| --- |-----------| --- |
| [`neoforge-26.1.2`](../../tree/neoforge-26.1.2) | NeoForge 26.1.2.106+ | 26.1.x    | Mods → CtrlEm → Config |
| [`fabric-1.21.11`](../../tree/fabric-1.21.11)   | Fabric + Fabric API | 1.21.11   | F8 in game |
| [`neoforge-1.21.1`](../../tree/forge-1.20.1)    | NeoForge 21.1.0+ | 1.21.1    | F8 in game |
| [`forge-1.20.1`](../../tree/forge-1.20.1)       | Forge 47+ | 1.20.1    | F8 in game |
| [`forge-1.18.2`](../../tree/forge-1.18.2)       | Forge 40+ | 1.18.2    | Mods → CtrlEm → Config |

Download builds from **Releases**. Only use builds from this repository or
ctrlem.com. All versions are client-side only and work on any server.

## How it works

- The mod only talks to the CtrlEm desktop client on your own computer
  (`127.0.0.1`), using the port and token the client writes to
  `%LOCALAPPDATA%\ctrlem-mcbridge\bridge.json`. The CtrlEm "Minecraft"
  plugin must be running in the CtrlEm client.
- In the settings you can turn commands on or off, pick which commands are
  allowed, and set cooldowns and limits. NeoForge 26.1.2 and Forge 1.18.2 save
  them to `config/ctrlem-client.toml`; Forge 1.20.1, NeoForge 1.21.1 and Fabric 1.21.11 save
  them to `config/ctrlem-client.json`.
- Sending chat messages is **off by default**. Chat commands starting with `/`
  are always refused.

## Commands

| Command | What it does |
| --- | --- |
| `jump` | Jump |
| `hold_key` | Hold forward/back/left/right/jump/sneak/sprint/attack/use for up to 10 s |
| `turn` | Turn the player |
| `hotbar` | Switch to a hotbar slot (1–9 or random) |
| `drop_item` | Drop the held item or stack |
| `drop_inventory` | Throw the whole inventory (survival only) |
| `play_sound` | Play a scary sound behind the player |
| `show_title` | Show a title message |
| `chat` | Send a chat message (off by default) |
| `follow` | Walk after a nearby player |
| `show_image` | Show an image overlay from an allowed link |

## Contributing

Pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Source-available, all rights reserved. You can read it, fork it and send pull
requests, but you can't redistribute it. See [LICENSE](LICENSE).
