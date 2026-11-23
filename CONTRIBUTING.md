# Contributing

Thanks for helping with the CtrlEm Minecraft Bridge.

## Branches

Each mod loader / Minecraft version lives on its own branch:

| Branch | Loader | Minecraft | Java |
| --- | --- | --- | --- |
| `forge-1.20.1` | Forge 47+ | 1.20.1 | 17 |
| `fabric-1.21.11` | Fabric Loader 0.18.1+ with Fabric API | 1.21.11 | 21 |

`main` only holds the README, license and this guide. Open pull requests
against the branch for the version you changed. If a fix applies to both,
open one PR per branch.

## Building

Each loader branch is a normal Gradle project with the wrapper included:

```
gradlew build
```

The jar ends up in `build/libs/`. The Forge branch needs Java 17 to 22 to run
Gradle (Java 17 is downloaded automatically for compiling). The Fabric branch
needs Java 25 to run Gradle, and the mod itself is compiled for Java 21.
Every push and pull request is also built by GitHub Actions.

## How to contribute

1. Fork the repository and create a branch from the loader branch you're changing.
2. Make your change and test it in a real client.
3. Open a pull request describing what changed and how you tested it.

## Rules for changes

- Keep the bridge protocol (`protocol: 1`, `127.0.0.1` + `bridge.json`
  discovery) compatible with the CtrlEm desktop client.
- Don't weaken the safety checks: the per-command permission toggles, the
  cooldown, `UrlSafety` (blocklist and private-address checks) and the
  "no `/` commands in chat" rule.
- New commands must be off-by-default or clearly opt-in in the F8 settings.

## License

This project is source-available, not open source. See [LICENSE](LICENSE).
By opening a pull request you agree your contribution is licensed to the
project owner under section 3 of the license. You may not redistribute
builds or modified versions; official builds are only published in this
repository's Releases.
