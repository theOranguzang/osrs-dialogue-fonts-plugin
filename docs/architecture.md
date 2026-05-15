# Architecture — Dialogue Fonts Plugin

## Motivation

Old-School RuneScape renders all dialogue text using a fixed bitmap font called
**Quill 8** (also called "Freehand 521" or colloquially the "cursive font"). It
is stylistically authentic but hard to read at small sizes, inaccessible for
users with reading difficulties, and visually jarring on high-DPI displays.

RuneLite's `Widget.setText()` API can change _what_ a widget says but it cannot
change _how_ it is drawn — the engine always uses its own internal bitmap
renderer. The only way to substitute a different font is to paint outside the
engine's rendering pipeline through RuneLite's **Overlay** system, which
provides a full `Graphics2D` context drawn after the engine finishes a frame.

---

## Core approach

```
┌──────────────────────────────────────┐
│             OSRS game engine         │
│                                      │
│  Widget system                       │
│    DialogueWidget                    │
│      ├─ [child 4] name               │  ← we blank this text
│      ├─ [child 5] continue prompt    │  ← we blank this text
│      └─ [child 6] body text          │  ← we blank this text
│                                      │
│  (engine renders — but widgets       │
│   now contain empty strings,         │
│   parchment background still drawn)  │
└──────────────────────────────────────┘
          ↓  frame handed to overlay system
┌──────────────────────────────────────┐
│     BetterDialogueOverlay.render()   │
│                                      │
│  1. drawCenteredString() for name    │
│     (dark blue, NAME_COLOR)          │
│  2. drawWrappedText() for body       │
│     using configured system font     │
│                                      │
│  No background fill — the game's     │
│  native parchment texture shows      │
│  through naturally.                  │
└──────────────────────────────────────┘
```

---

## Rendering pipeline

### Per-frame sequence

```
onClientTick  (~50 fps, every client frame)
  └─ DialogueWidgetManager.getCurrentDialogue()
      ├─ Check each dialogue root widget for visibility
      │   AND check the per-type config toggle (replaceNpc, replacePlayer, etc.)
      ├─ If visible AND enabled:
      │   ├─ read widget.getText()
      │   ├─ if non-empty → parse colour/break tags → update text cache
      │   ├─ widget.setText("")     ← blank the widget EVERY frame
      │   └─ return DialogueState (type + cached segments + live widget refs)
      └─ overlay.setState(state)

BetterDialogueOverlay.render(Graphics2D)  (called every rendered frame)
  ├─ reBlankWidgets(state)   ← belt-and-suspenders blank before any pixels written
  ├─ fontRenderer.applyRenderingHints()
  └─ switch(state.getType())  [each case also guarded by its config toggle]
       ├─ NPC / Player  → drawCenteredString() for name (dark blue, NAME_COLOR)
       │                  + drawWrappedText() for body (no fillRect — native parchment shows through)
       ├─ Options       → per-option drawCenteredString() (no fillRect — camouflaged ghost
       │                  text is parchment-on-parchment, overlay text covers it directly)
       │                  + hover colour detection via mouse position
       └─ Sprite        → drawWrappedText() (no fillRect)
```

### Config toggles gate detection, not just rendering

Each dialogue type toggle (`replaceNpc`, `replacePlayer`, `replaceOptions`,
`replaceSprite`) is checked **in `DialogueWidgetManager.detectAndBuild()`**
before the widget is ever touched. When a type is disabled the plugin neither
blanks its widgets nor renders anything — the original bitmap font stays
visible. Checking only in `render()` would blank the widget but paint nothing,
showing an empty dialogue box.

### Why `ClientTick` instead of `GameTick`

`GameTick` fires every **600 ms** (one server tick). When a new dialogue opens
the engine sets widget text immediately, but `GameTick` may not fire for another
~600 ms, leaving several rendered frames where the original Quill font is
visible.

`ClientTick` fires every **client frame** (~20 ms). The window between the
engine writing text and us blanking it is at most a single frame.

---

## The capture-then-blank pattern

This is the most important design invariant in the codebase.

### The problem

```
Frame 1  onClientTick:  widget.getText() = "Hello"  → parse → blank (setText(""))
Frame 2  onClientTick:  widget.getText() = ""        → parse → empty list
Frame 2  render():      state.getBodySegments() == [] → nothing painted  ← blank screen
```

After the first blank, every subsequent `onClientTick` call sees an empty widget
and produces a `DialogueState` with no segments. The overlay has nothing to
paint.

### The fix — per-type text caches

```java
String raw = textWidget.getText();
if (raw != null && !raw.isEmpty()) {
    // Only update the cache when the engine gives us real text
    cachedNpcBody = parseSegments(raw, Color.BLACK);
    cachedNpcName = nameWidget != null ? stripTags(nameWidget.getText()) : "";
}
// Always blank BOTH the text and name widgets every frame
blankWidget(textWidget);
blankWidget(nameWidget);
// Always build the state from the cache, not the (now empty) widget
return new DialogueState(..., cachedNpcName, cachedNpcBody, ...);
```

The cache is populated **only when `getText()` returns non-empty text**. On
every subsequent frame the widget is already blank, so the cache is left
untouched and the overlay continues to render the last good snapshot.

### Cache lifetime and `lastSeenType`

A stale cache from NPC A could bleed into the opening frame of NPC B's dialogue
(before B's non-empty text has been captured). `DialogueWidgetManager` tracks
the `lastSeenType` and calls `clearCacheFor(previousType)` whenever the active
dialogue type changes or no dialogue is open.

---

## Text hiding strategy

Three options were considered:

| Option | Approach | Risk |
|--------|----------|------|
| **A (implemented — NPC/player/sprite)** | `widget.setText("")` on text children only; no background fill | Minimal — click handlers are bound to widget bounds, not text content; native parchment shows through cleanly |
| **A2 (implemented — options)** | `widget.setTextColor(0xD6CCAF)` to camouflage text against parchment; no fillRect | Preserves text content so the engine's 1–5 key handler works; ghost text is parchment-on-parchment (near-invisible); overlay text paints on top |
| B | Paint opaque background over text, leave widget untouched | Hard to colour-match the parchment gradient exactly |
| C | `widget.setHidden(true)` | Breaks click-to-continue and option selection |

**Option A** is used for NPC, player, and sprite dialogue: widget text is blanked so the engine cannot render it; the game's native parchment texture shows through.

**Option A2** is used for option dialogue: widget text colour is set to the parchment colour (`#D6CCAF`) rather than blanking the text. The engine's key handler reads widget text content (not colour), so 1–5 keyboard selection continues to work. No fillRect is needed — ghost text rendered in parchment-on-parchment is nearly invisible, and the overlay's replacement text paints directly on top. Original text colours are restored via `DialogueWidgetManager.restoreAll()` on shutdown.

---

## Widget system overview

OSRS UIs are composed of **Widgets** arranged in a tree. Each widget has a
numeric group ID and child ID. The RuneLite API exposes them via:

```java
Widget widget = client.getWidget(int groupId, int childId);
```

Dialogue boxes each have their own group (identified by `InterfaceID` constants)
and a fixed set of children for the name, body text, and continue prompt.

### Widget groups used

| Dialogue type | `InterfaceID` constant | Group ID |
|---------------|------------------------|----------|
| NPC speaking  | `DIALOG_NPC`           | 231      |
| Player speaking | `DIALOG_PLAYER`      | 217      |
| Option menu   | `DIALOG_OPTION`        | 219      |
| Sprite / item | `DIALOG_SPRITE`        | 193      |

Child indices within each group are documented in
[`widget-reference.md`](widget-reference.md) and in
`DialogueWidgetManager` source constants.

---

## Font system

`FontRenderer` creates a `java.awt.Font` using **Java logical font names**:

```java
int style = config.boldText() ? Font.BOLD : Font.PLAIN;
new Font(config.fontFamily().getJavaName(), style, config.fontSize())
```

Java logical fonts (`SansSerif`, `Serif`, `Monospaced`, `Dialog`, `DialogInput`)
always resolve to system fonts — no bundled TTF files are required and there is
no risk of a missing-font error on any OS.

The font is cached as a single `Font` instance. It is only rebuilt when the
family, size, or bold flag changes, using a string key:

```java
String key = config.fontFamily().getJavaName() + "_" + config.fontSize() + "_" + config.boldText();
```

`FontRenderer.getOptionFont()` returns the same font as `getFont()` — one font
for everything keeps the config panel simple.

`FontRenderer.drawWrappedText()` implements word-wrapping using
`FontMetrics.stringWidth()` and centres each line horizontally within the
widget bounds.

---

## Inline markup tags

OSRS dialogue text uses a small set of HTML-like tags. `DialogueWidgetManager`
handles:

| Tag | Meaning | Handling |
|-----|---------|----------|
| `<col=RRGGBB>` | Set text colour | Parsed; drives `TextSegment.color` |
| `</col>` | Reset to default colour | Resets `currentColor` |
| `<br>` | Line break (baked at Quill 8 character widths) | **Collapsed to a space.** The game's break-points are meaningless for other fonts; `FontRenderer` re-wraps from scratch using its own `FontMetrics`. |
| `<lt>` / `<gt>` | Literal `<` / `>` | Replaced before tag scanning |
| `<shad>`, `<str>`, `<u>`, etc. | Shadow, strikethrough, underline | Silently ignored |

---

## Edge cases

| # | Scenario | Current behaviour |
|---|----------|-------------------|
| 1 | **Click-to-continue** — continue prompt is a separate child widget; we blank its text | Click handler is widget-bounds-based; continues to work correctly |
| 2 | **Option hover highlights** — game normally changes text colour on hover | Overlay detects mouse position vs option bounds and renders white text on hover |
| 2a | **1–5 key shortcuts in option menus** — engine reads widget text content to map keypresses | Option widgets are camouflaged (`setTextColor(0xD6CCAF)`) not blanked, preserving text content for the key handler |
| 3 | **Scrolling quest dialogue** — widget text updates each scroll | Re-captured each `onClientTick` via the non-empty check |
| 4 | **Widget child index drift** — game updates can change child indices | All indices are constants in `DialogueWidgetManager`; verify with Widget Inspector after updates |
| 5 | **Alternative interface styles** | No background fill is painted so the native parchment/texture always shows through; name/title colours are hardcoded vanilla values (`NAME_COLOR = 0x000080`, `OPTION_TITLE_COLOR = 0x800000`) |
| 6 | **GPU plugin** | Overlay paints in screen-space; should be unaffected. Verify layering in production. |

