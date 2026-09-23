# PowerGraph

Karoo (Hammerhead bike computer) extension app, built from the `karoo-ext` template repo. Adds two graphical ride data fields — `power-graph` and `heart-rate-graph` — both scrolling bar graphs colored by training zone, implemented as one reusable `ScrollingGraphDataType`.

## Why

Karoo has no built-in scrolling graph field for power or heart rate; this extension fills that gap.

## Structure

- `app/src/main/kotlin/.../MainActivity.kt`, `screens/MainScreen.kt` — still the unmodified template placeholder screen. All real product work so far is in the data-type/extension layer below, not this app screen.
- `app/src/main/kotlin/.../extension/PowerGraphExtension.kt` — registers the extension and wires up the two data types.
- `app/src/main/kotlin/.../extension/Extensions.kt` — `KarooSystemService` → `Flow` adapters.
- `app/src/main/kotlin/.../datatype/ScrollingGraphDataType.kt` — the core implementation: buffers samples into a rolling 2-minute window and renders the scrolling bar graph as a bitmap.
- `app/src/main/res/xml/extension_info.xml` — declares the two data types to Karoo.

## Known rough edges

`HEADER_HEIGHT_FRACTION` and `CORNER_RADIUS_DP` in `ScrollingGraphDataType.kt` are guesses awaiting on-device tuning, since `ViewConfig` doesn't expose the real header height or tile corner radius. Flag this if touching rendering/layout code.

Git history is minimal, so don't assume prior design discussion exists outside the code's own doc comments.
