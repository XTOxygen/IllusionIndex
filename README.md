# IllusionIndex

**A Minecraft 1.8.9 (Forge) client-side mod that reveals invisible players.**

Author: **EndWithMe** · MC 1.8.9 · Forge `1.8.9-11.15.1.2318` · mappings `stable_20`

---

## Features

### Core
* **Reveal invisible players.** An invisible player is rendered as a
  *semi-transparent silhouette*, **without any skin colour**: the entity's own
  skin texture is never sampled. The whole body is forced to one configurable
  **base colour** (default pure black `#000000`) at a configurable **opacity**
  (default **80%**).
* **Afterimages.** While an invisible player moves, fading afterimage frames of
  their previous positions are drawn. Frame **count** and **linger time** are
  configurable.
* **Independent "observe" toggle.** Turning *Observe invisible players* off
  stops the live silhouette — afterimages keep working (useful to keep the
  screen clean while still tracking invisible movement).
* **Chat spotting.** When an invisible player is inside **attack range**, the
  mod announces them in chat as **red bold** text:

  ```
  [IIndex]:Spotted an invisible player! ID:<player>
  ```

### Config GUI (shader driven, opens with `I`)
* Semi-transparent dark **cards** with **white 1 px borders** and **rounded
  corners** (GLSL rounded-box SDF) over a blurred, dimmed copy of the world,
  with a **pop-up open/close animation** and scrolling on small screens.
* Three cards: **Render Settings**, **Afterimage Settings**, **General
  Settings**.
* Controls:
  * *Switches* — ON: white fill + black knob · OFF: transparent fill, white
    border, black knob (animated knob).
  * *Sliders* — semi-transparent white track + white rounded-square handle with
    a live value readout.
  * *Colour picker* — 16 preset swatches + `#RRGGBB` hex input with a live
    preview chip.
* Text is **shadowed/outlined** and anti-aliased by rendering the whole UI into
  a supersampled framebuffer which is then composited down; the glyph sheet is
  bilinear-filtered while the UI is on screen.
* Full **keyboard navigation**: `Tab` / `Shift+Tab` cycles focus (visible focus
  ring), `←` `→` adjust the focused slider, `Enter` confirms/activates,
  `Esc` closes.
* Every setting — including the key binding — is stored in
  `config/IllusionIndex.cfg` and applied instantly.

---

## Technical notes

### Rendering technique
1. `RenderPlayerEvent.Pre` is cancelled for invisible players, so **no** skin,
   spectator-ghost pass, armour layers, cape or name tag is ever drawn.
2. The player is re-rendered from scratch with a faithful replica of the
   1.8.9 pose pipeline (`RendererLivingEntity.doRender` /
   `RenderPlayer.doRender`): interpolated `renderYawOffset` / `rotationYawHead`
   / pitch, riding clamps, sneaking offsets, the `scale(-1,-1,1)` mirror, the
   player `0.9375` scale, the `-1.5078125` model offset, `Dinnerbone/Grumm`
   flips, held-item/bow arm poses and the `ModelPlayer` outer-wear layer —
   driven by the player's own limb-swing fields.
3. The model is drawn with a **1×1 dynamic texture** tinted to the configured
   base colour at the configured alpha, so no skin data can leak through.
4. **Afterimages** are pose snapshots (interpolated world position, body/head
   yaw, pitch, limb values, sneak/ride flags, item pose) recorded a few times
   per second per invisible player and re-rendered with an age-based fade in
   `RenderWorldLastEvent`, oldest first.
5. Chat alerts run on the client tick with per-player edge detection, so each
   (re-)entry into attack range produces exactly one message.

### GUI technique
No third-party rendering libraries. The GUI uses plain **GLSL 1.20** shaders
(loaded at runtime from `assets/illusionindex/shaders/`) plus
LWJGL/vanilla GL helpers:

* world backdrop capture (`glCopyTexSubImage2D` of the Minecraft framebuffer)
  → two-pass separable **Gaussian blur** in quarter-resolution FBOs →
  composited dimmed behind the cards;
* **rounded rectangles / pills / sliders / swatches** from a signed-distance
  rounded-box fragment shader with `smoothstep` anti-aliasing;
* the whole UI is rendered into a **supersampled** (2×) framebuffer and
  downsampled — that is what anti-aliases the vanilla font glyphs;
* fixed-function immediate-mode quads feed the shaders through the GLSL 1.20
  compatibility built-ins, so everything is **OptiFine compatible** (OptiFine's
  own GUI targets the same pipeline).

If a driver cannot compile the shaders, the GUI automatically falls back to
plain rectangles (functionality unchanged).

### Class layout
```
com.endwithme.illusionindex
├── IllusionIndex.java     @Mod entry point, instance wiring
├── Config.java            Forge Configuration wrapper (all settings + keys)
├── KeyBindings.java       key bindings, GUI/toggle hotkeys, runtime rebind
├── RenderHandler.java     RenderPlayerEvent.Pre cancel + silhouette pipeline,
│                          RenderWorldLastEvent afterimages, chat spotting
└── gui
    ├── GuiConfig.java     the settings screen (cards, widgets, keyboard nav)
    └── UiRenderer.java    GLSL/FBO engine: blur, rounded rects, supersampling
```
Resources: `assets/illusionindex/shaders/*.vert|*.frag` (GLSL 1.20).

---

## Build

Requirements: JDK 8 and Gradle (wrapper included; ForgeGradle 2.1).

```
gradlew.bat setupDecompWorkspace   # optional: generate an IDE workspace
gradlew.bat build                  # -> build/libs/IllusionIndex-1.0.0.jar
gradlew.bat runClient              # dev client
```

Install the jar into the `mods/` folder of a Forge 1.8.9 client. The mod is
**client-side only** and does not need to be installed on the server.

## Verification status

* Compiles against MCP `stable_20` (Minecraft 1.8.9 + Forge
  `11.15.1.2318`); `reobfJar` produces a production-ready, SRG-remapped jar
  (verified: the packaged classes reference `func_*`/`field_*` names).
* The mod was launched in a real 1.8.9 dev client: FML discovers and loads
  `IllusionIndex`, `config/illusionindex.cfg` is generated with the documented
  defaults (`opacity=0.8`, `baseColor=0`, `openGuiKey=23` = `I`), and the
  shader GUI was exercised end to end on a real GL driver — blurred backdrop
  capture, rounded cards with 1 px white borders, both switch states, sliders,
  the 16 preset swatches, hex field, outlined text and the pop animation all
  render correctly (frames stored in `run/screenshots/`, produced by a
  temporary harness that is **not** part of the released sources).
* Not verified: the in-world silhouettes/afterimages themselves, which need a
  server with an actual invisible player. The pose pipeline is a line-by-line
  port of the 1.8.9 renderer code, but treat first in-game use as a visual
  smoke test. Known cosmetic limitation: sleeping players are drawn standing.

## License / disclaimer

Provided as-is, for use on servers that allow it. Built against Minecraft Forge
(Minecraft Forge is licensed separately; see the Forge distribution). Not
affiliated with Mojang. PvP servers may consider client-side player reveal
mods unfair — ask before using it there.
