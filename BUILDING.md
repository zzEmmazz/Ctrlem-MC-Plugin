# CtrlEm mod — Minecraft 1.18.2 (Forge)

The same mod as the NeoForge 26.1.2 build (branch `neoforge-26.1.2`), for players still on 1.18.2. It talks to the same
CtrlEm "Minecraft" plugin over the same wire protocol, so nothing on the CtrlEm side changes: one
plugin, one registration, one review, two mod jars.

Built against **Forge 40.3.12** for **Minecraft 1.18.2**. Check
[files.minecraftforge.net](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.18.2.html)
for the exact build and adjust `forge_version` in `gradle.properties` if it has moved on; the mod
declares `[40,)`, so it runs on any 1.18.2 Forge.

## Building

**This build needs a JDK 17 and nothing newer.** ForgeGradle 5 caps Gradle at 7.5.1, which refuses
to start on Java 19+ — so the JDK that runs the NeoForge build next door will not run this one.
Either launch with `JAVA_HOME` pointing at a JDK 17, or set `org.gradle.java.home` in
`gradle.properties` (there's a commented line ready for it).

```sh
gradlew build        # jar lands in build/libs/ctrlem-forge-1.18.2-1.0.0.jar
gradlew runClient    # Minecraft 1.18.2 with the mod
gradlew runLeader    # second client called "Leader", for testing "Follow a player"
```

The first build decompiles Minecraft and takes a while. `gradlew --refresh-dependencies` if the
IDE loses the Minecraft classes.

## What differs from the NeoForge build

Same commands, same config keys, same defaults, same `config/ctrlem-client.toml`. Three things
are not the same, all of them forced by what 1.18.2 has:

- **The config screen is hand-written** (`ConfigScreen.java`). NeoForge generates one from the
  config spec; Forge 1.18.2 has no such thing. It covers every setting over two pages — General
  and Commands — with the same tooltips. Reached the same way: **Mods → CtrlEm → Config**.
- **`warden_emerge` plays the Wither's spawn sound.** The Warden is a 1.19 mob. Rather than fail a
  command the plugin is entitled to send, the id maps to the nastiest sound 1.18.2 has.
- **Sneak and sprint are the only keys with a toggle mode.** Attack and use gained toggle options
  later, so `KeyHolder` has nothing to check for them.

Everything else is the same behaviour reached through older API: `TickEvent.ClientTickEvent` with a
phase instead of separate `Pre`/`Post` events, `PoseStack` drawing and `OverlayRegistry` instead of
`GuiGraphics` and GUI layers, `ForgeConfigSpec` instead of `ModConfigSpec`, `TextComponent` instead
of `Component.literal`, and a hand-rolled download size cap because `BodyHandlers.limiting` is
Java 19+. `BridgeConnection.java` and `UrlSafety.java` are byte-for-byte identical to the NeoForge
build — keep them that way, and keep `UrlSafety`'s blocklist in step with the client's.

## Client-only, on a server that doesn't have it

Forge 40's `@Mod` has no `dist = Dist.CLIENT` and its `mods.toml` has no per-mod `side`, so being
client-only is declared at runtime instead — see the `DisplayTest` extension point in
`CtrlEmMod.java`. Without it, servers would show players a red X in the server list.

## Shipping

`gradlew build`, then publish `build/libs/ctrlem-forge-1.18.2-<version>.jar` alongside the NeoForge jar. Both
carry mod id `ctrlem` and the same version number; the game version keeps them apart.

`reobfJar` runs automatically as part of `build`. It has to: the source compiles against Mojang
names, which only exist in the dev environment, and an un-reobfuscated jar loads and then dies on
the first vanilla call. The NeoForge build has no equivalent step.
