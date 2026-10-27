# Lapse Paint

A drawing and animation app for Android tablets (built for the Xiaomi Pad 8 + Focus Pen), inspired by the workflow of iPad drawing apps, with automatic time-lapse recording. All code, icons and artwork are original.

## What works (v1.4)

Procreate-style layout (own icons and code, nothing taken from Procreate):

- **Top bar:** Gallery, Actions, Adjustments, Selection, Transform on the left;
  Brush, Eraser, Layers and the colour dot on the right
- **Sidebar:** brush size and opacity sliders, undo, redo
- **Colour popup:** saturation/brightness square, hue bar, recent colours, palette
- **Eyedropper:** press and hold a finger on the canvas, tap the sidebar square, or
  long-press the colour dot; a loupe shows new (top) vs current (bottom) colour
- **Actions menu:** time-lapse replay (play/pause/scrub in the app), export
  time-lapse (15 / 30 / 60 s MP4), insert a photo (crop step with Free/Square/4:3/3:4/16:9, then
  straight into Transform, on a new layer), save PNG,
  pen-only toggle, clear canvas
- **Gallery:** every artwork auto-saves (layers and full stroke history) with a
  thumbnail; long-press a card to rename or delete
- **Layers panel:** add (up to 10), select, show/hide, rename, opacity,
  move up/down, clear, delete
- **Animation (flipbook):** Actions -> Animation. Each layer is a frame (up to 30);
  timeline with thumbnails, Play/Pause, + Frame, Duplicate/Delete, onion skin
  (earlier frames red, later green), 1-30 fps, loop or ping-pong, MP4 or looping GIF export,
  background frame (frame 1, always visible), per-frame hold (x1-x13)
- **Selection (lasso):** draw around an area; Invert, Clear, Fill, Copy & Paste,
  Cut & Paste (onto a new layer, then straight into Transform), Deselect
- **Transform (arrow):** move, resize and rotate the selected layer (drag, corner
  handles, rotate handle, or two-finger pinch/twist); Flip, Rotate 90°, Reset, Cancel, Done
- **Adjustments (magic wand):** Hue/Saturation/Brightness/Contrast, Gaussian Blur,
  Invert, on the selected layer, with live preview, undo and time-lapse support
- **Smudge (finger icon):** drags paint already on the layer; opacity slider = strength
- **Brush engine:** textured dabs stamped along the stroke, following pressure and tilt
- **Brush Library:** categories (Sketching, Inking, Painting, Airbrushing) beside the
  brushes (tap the brush/eraser icon again to open it)
- **Brush Studio:** tap the selected brush to edit spacing, streamline, scatter,
  size jitter, softness, grain, flow, and pressure/tilt -> size/opacity, with a live preview
- **Smooth strokes:** curves between pen samples plus streamline smoothing
- **QuickShape:** draw and hold still -> snaps to a straight line or ellipse
  (keep holding and move to adjust a line's end)
- Pressure-sensitive brush (Focus Pen), the pen's eraser end, brush opacity
- **Canvas navigation:** two-finger pinch to zoom (20%-3000%), drag to pan, twist to
  rotate (snaps straight near 0/90/180/270°); quick pinch inwards = fit to screen
- Two-finger tap = undo, three-finger tap = redo
- Palm rejection: once the pen is detected, fingers stop drawing
- Fullscreen canvas (system bars hidden; swipe from the edge to show them)



## How timelapse works

Every stroke is stored as a list of points (x, y, pressure). On export,
`TimelapseExporter` replays those points onto an offscreen canvas, spreads
them evenly across the frames, holds the final image for 2 seconds, and
encodes it with the hardware H.264 encoder (`MediaCodec` + `MediaMuxer`).

## Code map

| File | What it does |
|---|---|
| `CropPhoto.kt` | Crop screen shown after picking a photo |
| `Animation.kt` | Animation timeline, settings, MP4 export |
| `GifWriter.kt` | Animated GIF encoder (median-cut palette, LZW) |
| `Adjustments.kt` | Layer adjustments, blur, Adjustments panel |
| `Brushes.kt` | `Brush` settings, built-in brushes, saved edits |
| `BrushPanels.kt` | Brush Library and Brush Studio popups |
| `Model.kt` | `Artwork`, `Layer`, `Stroke` + save format |
| `ArtworkStore.kt` | Saving/loading artworks for the Gallery |
| `Stroke.kt` | `StrokePainter` (dab brush engine on smooth curves), `PaintSurface` (per-layer bitmaps, shared with export) |
| `GalleryActivity.kt` | Gallery screen (app home) |
| `LayersPanel.kt` | Layers popup |
| `VerticalSlider.kt` | Sidebar size/opacity slider |
| `ColorPickerView.kt` | Colour square + hue bar |
| `DrawingView.kt` | Canvas, pen input, gestures, QuickShape, undo/redo, layer edits |
| `TimelapseReplay.kt` | In-app time-lapse player |
| `TimelapseExporter.kt` | Stroke replay -> MP4 |
| `MainActivity.kt` | Top bar, sidebar, colour popup, Actions menu |

## Run it

1. Install Android Studio and open this folder (or build with `./gradlew assembleDebug`).
2. On the tablet: Settings -> About -> tap "OS version" 7 times, then enable
   USB debugging (and Install via USB on Xiaomi) in Developer options.
3. Plug in and press Run.
4. Optional, for usage analytics: download `google-services.json` for package
   `com.lapsepaint.app` from your Firebase project and put it in `app/`. It is not in the
   repository on purpose; without it everything works and analytics stays off.

## Roadmap ideas (vibe-code these next)

- Layers (with opacity and blend modes)
- Pinch zoom / rotate the canvas
- Textured brushes (pencil, ink, airbrush) and a brush editor
- Colour wheel picker + eyedropper (long-press)
- Save / reopen projects (persist strokes so timelapse survives app restarts)
- Lower pen latency with `androidx.graphics` front-buffered rendering or the `androidx.ink` library
- Record undo/redo history in the timelapse, like Procreate
- Timelapse in 1080x1920 vertical format for Reels/Shorts

## Usage analytics (Google Analytics for Firebase)

- Firebase project: `lapse-paint`; config in `app/google-services.json` (without it the
  app builds and runs with analytics off).
- Off until the user taps **Allow** on the first-launch prompt; Actions has an on/off switch.
- Sent: screen time (automatic), `drawing_session` (seconds, strokes_added, layers,
  animation), `artwork_created`, `tool_selected`, `brush_selected`, `feature_used`, `export`.
  Never drawings, photos or personal details; ad ID and ad personalisation are off.
- Devices using ad-blocking DNS (e.g. AdGuard) block Analytics, so they aren't counted.
- Test live events: `adb shell setprop debug.firebase.analytics.app com.lapsepaint.app`,
  then Firebase console -> Analytics -> DebugView. Turn off with `.none.`
