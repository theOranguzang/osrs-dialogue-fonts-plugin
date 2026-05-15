# Dialogue Fonts

A RuneLite plugin that replaces the hard-to-read OSRS dialogue font with a clean, configurable system font.

## Before / After

![img.png](img.png)

![img_1.png](img_1.png)

![img_2.png](img_2.png)

![img_3.png](img_3.png)

![img_4.png](img_4.png)

## What it does

Replaces the bitmap "Quill 8" cursive font in dialogue boxes with a readable font rendered via overlay. Covers:

- **NPC dialogue** — the main conversation box
- **Player dialogue** — your character's responses
- **Option menus** — "Select an option" multi-choice
- **Item/action dialogue** — "You light the logs", drop warnings, etc.

Everything else (chatbox, overhead text, menus) is unchanged.

## Configuration

| Setting | Description | Default |
|---|---|---|
| Font | SansSerif, Serif, Monospaced, Dialog, Dialog Input | SansSerif |
| Font Size | 10–24px | 14 |
| Bold | Bold weight toggle | Off |
| Anti-aliasing | Smooth font edges | On |
| NPC Dialogue | Enable/disable per type | On |
| Player Dialogue | Enable/disable per type | On |
| Option Menus | Enable/disable per type | On |
| Item/Action Dialogue | Enable/disable per type | On |

Fonts are Java logical font names — they map to system defaults on every OS (Arial on Windows, Helvetica on Mac, DejaVu on Linux). No bundled font files, no missing font errors.

## How it works

The game renders dialogue text through widgets using a fixed bitmap font. RuneLite's Widget API can change text content but not the font, so this plugin:

1. Detects visible dialogue widgets each tick
2. Caches the text content
3. Blanks the original widget text
4. Paints replacement text via a `Graphics2D` overlay positioned over the dialogue box

Click-to-continue and option selection work normally — click targets are widget bounds, not rendered text. Original text is restored on plugin shutdown.

## Installation

Available on the [RuneLite Plugin Hub](https://runelite.net/plugin-hub/). Search for "Dialogue Fonts" in the Plugin Hub within RuneLite.

## Building from source

Requires JDK 11+.

```
./gradlew build
```

## Acknowledgments

Inspired by [RuneLite issue #11729](https://github.com/runelite/runelite/issues/11729) — an accessibility feature request open since 2020.

## License

BSD 2-Clause
