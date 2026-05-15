# GitHub Copilot Instructions — Dialogue Fonts Plugin

## What this repo is

A **RuneLite plugin** that replaces the hard-to-read OSRS bitmap cursive font
(Quill 8 / "Freehand") with a clean, user-configurable font. It works
by blanking original widget text each frame and painting replacement text on top
via a `Graphics2D` overlay — the only way to change fonts inside RuneLite since
`Widget.setText()` cannot change the rendering font.

**Language:** Java 11  
**Build system:** Gradle (standard RuneLite plugin-hub layout)  
**Key dependencies:** `net.runelite:client` (compileOnly), Lombok, JUnit 4

---

## Docs — read these first

| File | What it covers |
|------|---------------|
| [`docs/architecture.md`](docs/architecture.md) | End-to-end design: why overlay, rendering pipeline, capture-then-blank pattern, config-toggle detection gating, font system, edge cases |
| [`docs/widget-reference.md`](docs/widget-reference.md) | All widget group/child IDs (including the dynamic-children approach for option menus), how to use the in-game Widget Inspector, update procedure after game patches |

---

## Source file map

All plugin source lives in `src/main/java/com/betterdialogue/`.

| File | Role |
|------|------|
| [`BetterDialoguePlugin.java`](src/main/java/com/betterdialogue/BetterDialoguePlugin.java) | Main plugin class. Registers/removes the overlay, subscribes to `ClientTick`, calls `DialogueWidgetManager` each frame, provides config via Guice. |
| [`BetterDialogueConfig.java`](src/main/java/com/betterdialogue/BetterDialogueConfig.java) | RuneLite config interface. Font family (5 Java logical fonts), font size (10–24), bold toggle, anti-aliasing toggle, and four per-type enable toggles grouped under a `@ConfigSection`. No color pickers or custom file paths. |
| [`BetterDialogueOverlay.java`](src/main/java/com/betterdialogue/BetterDialogueOverlay.java) | `OverlayLayer.ABOVE_WIDGETS` overlay. Re-blanks widgets in-frame, fills background rect, renders NPC/player name in dark blue (`NAME_COLOR = 0x000080`) above the body text, delegates body text rendering to `FontRenderer`. Vanilla colors are hardcoded constants (no config). |
| [`DialogueWidgetManager.java`](src/main/java/com/betterdialogue/DialogueWidgetManager.java) | Detects active dialogue each tick (gated on per-type config toggles), implements the **capture-then-blank** text cache pattern for both name and body widgets, parses `<col>`/`<br>` tags, restores widget text on shutdown. |
| [`FontRenderer.java`](src/main/java/com/betterdialogue/FontRenderer.java) | Creates fonts from Java logical names (`new Font(javaName, style, size)`), caches by `"family_size_bold"` key, applies rendering hints, word-wraps via `FontMetrics`, draws centred lines. No TTF file loading. |
| [`DialogueState.java`](src/main/java/com/betterdialogue/DialogueState.java) | Immutable per-frame snapshot: dialogue type, cached text segments, option strings, live widget references for bounds. |
| [`DialogueType.java`](src/main/java/com/betterdialogue/DialogueType.java) | Enum of supported dialogue types: `NPC_DIALOGUE`, `PLAYER_DIALOGUE`, `OPTION_DIALOGUE`, `SPRITE_DIALOGUE`, `LEVEL_UP`, `QUEST_COMPLETE`. |
| [`TextSegment.java`](src/main/java/com/betterdialogue/TextSegment.java) | Immutable `(text, color)` pair. One segment per colour run after parsing inline tags. |
| [`FontChoice.java`](src/main/java/com/betterdialogue/FontChoice.java) | Enum of five Java logical font choices (`SANS_SERIF`, `SERIF`, `MONOSPACED`, `DIALOG`, `DIALOG_INPUT`). Each entry holds the `javaName` string passed to `new Font(...)`. No TTF files needed — always resolves on every OS. |

Test runner: [`src/test/java/com/betterdialogue/BetterDialoguePluginTest.java`](src/test/java/com/betterdialogue/BetterDialoguePluginTest.java) — launches RuneLite with the plugin loaded via `./gradlew run`.

---

## Key design decisions (do not break these)

### 1. Capture-then-blank — never parse a widget you've already blanked

`DialogueWidgetManager` updates its segment caches **only** when
`widget.getText()` returns a non-empty string. It then calls `widget.setText("")`
**every frame** regardless. The overlay always reads from the cache, never from
the live widget. See [`docs/architecture.md`](docs/architecture.md#the-capture-then-blank-pattern).

```
getText() non-empty  →  update cache (body + name)  →  blank widget (text + name)
getText() empty      →  leave cache                 →  blank widget (no-op)
render()             →  read cache                  →  paint name then body
```

### 2. `ClientTick` not `GameTick`

The plugin subscribes to `ClientTick` (~50 fps) rather than `GameTick` (~1.67 fps).
Swapping this back to `GameTick` reintroduces a visible flash of the original
Quill font on the first ~600 ms of every new dialogue.

### 3. Double-blank: tick + render

`BetterDialogueOverlay.render()` calls `reBlankWidgets()` again at the very top
(before painting). This closes the narrow window between the `ClientTick` handler
and the overlay draw call where the engine could reset widget text. Do not remove
this call.

### 4. Cache lifetime tracking

`DialogueWidgetManager.lastSeenType` detects when the active dialogue type
changes and calls `clearCacheFor(previousType)`. This prevents stale text from
one NPC bleeding into the first frame of a new NPC's dialogue. Do not bypass
this with direct cache mutation.

### 5. Config toggles gate detection, not just rendering

Each per-type toggle (`replaceNpc`, `replacePlayer`, `replaceOptions`,
`replaceSprite`) is checked in **`DialogueWidgetManager.detectAndBuild()`**,
before the widget is touched. When a type is disabled, its widgets are neither
blanked nor rendered — the original font shows normally. If you only gate in
`render()`, the widget gets blanked but nothing gets painted: empty dialogue box.

---

## Getting started quickly

### Build and run

```bash
./gradlew compileJava          # compile only
./gradlew run                  # launch RuneLite with the plugin loaded (dev mode)
./gradlew build                # compile + test
./gradlew shadowJar            # fat JAR for distribution
```

Requires **JDK 11**. Do **not** use the IntelliJ bundled JDR (JBR) — it ships
with Java 25 which is incompatible with Gradle 8.10.

#### Setting JAVA_HOME (Windows PowerShell)

IntelliJ automatically downloads a Temurin JDK 11 into `~/.jdks/` when you
first open the project. Use that JDK for all terminal builds:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-11.0.30"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat clean build
.\gradlew.bat run
```

> **Why not just use `JAVA_HOME` from the system?**  
> The system `JAVA_HOME` on this machine points to the IntelliJ JBR (Java 25),
> which Gradle 8.10 rejects with `Unsupported class file major version 69`.
> IntelliJ's managed Temurin 11 at `~/.jdks/temurin-11.0.30` is the correct
> runtime. If the folder name differs (e.g. after an IntelliJ update), run
> `Get-ChildItem "$env:USERPROFILE\.jdks"` to find the current name.

### Verify widget indices after a game update

See [`docs/widget-reference.md`](docs/widget-reference.md#updating-indices-after-a-game-update).
Indices change when Jagex updates the dialogue interface. The process takes
~5 minutes with the in-game Widget Inspector.

### Add a new dialogue type

1. Add a value to `DialogueType`.
2. Add a `buildXxxState()` method to `DialogueWidgetManager` following the
   capture-then-blank pattern. Add per-type cache fields and a `clearCacheFor`
   case.
3. Add the config toggle to `BetterDialogueConfig` (under the `dialogueTypes`
   section) and wire it up in `DialogueWidgetManager.detectAndBuild()` — the
   toggle check must be here, not just in the overlay.
4. Add a `renderXxxDialogue()` method to `BetterDialogueOverlay` and call it
   from the `render()` switch (also guarded by the config toggle).
5. Update [`docs/widget-reference.md`](docs/widget-reference.md) with the new
   widget group and child indices.
6. Update [`docs/architecture.md`](docs/architecture.md) if the new type
   introduces any new rendering or caching behaviour.

---

## Repo maintenance and best practices

### Always update docs with code changes

Stale docs are treated as bugs. After every code change, update the relevant
documentation **in the same response / commit**:

| What changed | Which doc to update |
|---|---|
| Widget indices (any constant in `DialogueWidgetManager`) | `docs/widget-reference.md` |
| Font loading, caching, or rendering approach | `docs/architecture.md` → Font system section |
| Config schema (add/remove/rename items) | `docs/architecture.md` if it affects design; `copilot-instructions.md` source file map |
| New dialogue type | Both `docs/` files + `copilot-instructions.md` source file map |
| Source file roles change | `copilot-instructions.md` source file map |
| Key design decisions change | `copilot-instructions.md` Key design decisions section |

### Widget indices drift — treat as high priority

Widget child indices are the most likely thing to break after a game update. If
players report blank dialogue boxes (text disappears but nothing appears), the
first thing to check is whether the indices in `DialogueWidgetManager` still
match the live widget tree. Use the Widget Inspector and fix the constants.
Always update `docs/widget-reference.md` at the same time.

### Never store widget text references across ticks

`Widget` objects returned by `client.getWidget()` are live references into the
game engine's memory. Their text content can change between ticks. Always read
`.getText()` inside `onClientTick`, never cache a widget's text string between
calls — only cache the parsed `List<TextSegment>` result.

### Restoring widget text on shutdown is mandatory

`DialogueWidgetManager.restoreAll()` is called from `BetterDialoguePlugin.shutDown()`.
If you add a new widget-blanking site elsewhere in the codebase, you must ensure
those widgets are also covered by `restoreAll()`. Failure to do so leaves the
game in a broken state with empty dialogue boxes after the plugin is disabled.

### One source of truth for the config group key

The config group name `"betterdialogue"` in `@ConfigGroup` must match anywhere
a config key is read by string (e.g. if you ever use `ConfigManager.getConfiguration()`
directly). There is currently only one place — `BetterDialogueConfig` — keep it
that way.

### Keep `FontRenderer` stateless except for the font cache

`FontRenderer` caches only the derived `Font` object (invalidated when config
changes). Do not add mutable rendering state to it. All layout information
(bounds, segments) is passed in per call and must not be stored on the instance.

### Commit hygiene

- Prefix commits with the component they touch:
  `overlay:`, `widget-manager:`, `font:`, `config:`, `docs:`, `build:`
- Always run `./gradlew compileJava` before pushing.
- **Always update the relevant `docs/` file** when changing widget indices,
  adding dialogue types, changing the config schema, or changing any design
  described in `docs/architecture.md`. Docs must be updated in the same commit
  as the code change — never leave them out-of-date.

### Dependency updates

- Keep `net.runelite:client` on `latest.release` — this tracks RuneLite's
  published releases and is correct for plugin-hub submissions.
- Lombok version can be updated freely; the only risk is annotation-processor
  API changes.
- JUnit is test-only and does not affect the distributed plugin.

