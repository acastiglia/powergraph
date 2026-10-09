# PowerGraph

Karoo (Hammerhead bike computer) extension app, built from the `karoo-ext` template repo. Adds two graphical ride data fields — `power-graph` and `heart-rate-graph` — both scrolling bar graphs colored by training zone, implemented as one reusable `ScrollingGraphDataType`.

## Why

Karoo has no built-in scrolling graph field for power or heart rate; this extension fills that gap.

## Structure

- `app/src/main/kotlin/.../SettingsActivity.kt`, `screens/SettingsScreen.kt` — settings screen for the data fields, opened from the Karoo's app drawer (it's the launcher activity). One section per `GRAPH_FIELDS` entry, reading and writing straight through to `PowerGraphSettings`. See `docs/projects/settings-activity.md`.
- `app/src/main/kotlin/.../data/GraphField.kt` — `GRAPH_FIELDS`: one `GraphField` per data field, holding everything that differs between fields (Karoo stream ids, zones, aggregations, smoothing options, labels). The extension and the settings screen both build from this list. Adding a field also needs an entry in `extension_info.xml`.
- `app/src/main/kotlin/.../data/PowerGraphSettings.kt` — `SharedPreferences`-backed settings store; `forField(field)` gives each field's settings as `StateFlow`s so a change applies live to a running field.
- `app/src/main/kotlin/.../extension/PowerGraphExtension.kt` — registers the extension, maps `GRAPH_FIELDS` to data types and wires each field's settings into its data type.
- `app/src/main/kotlin/.../extension/Extensions.kt` — `KarooSystemService` → `Flow` adapters.
- `app/src/main/kotlin/.../datatype/ScrollingGraphDataType.kt` — the core implementation: renders the scrolling bar graph as a bitmap inside `RemoteViews`, with full and compact layouts chosen by tile size.
- `app/src/main/kotlin/.../data/BufferedDataStream.kt` — buffers samples into the rolling 2-minute window the graph draws from. Not yet settings-driven; see the open questions in `docs/projects/settings-activity.md`.
- `app/src/main/res/xml/extension_info.xml` — declares the two data types to Karoo.

## Known rough edges

`HEADER_HEIGHT_PX`, `WIDTH_INSET_PX` and `COMPACT_ROW_SPAN_THRESHOLD` in `ScrollingGraphDataType.kt` are estimates awaiting on-device tuning, since `ViewConfig` doesn't expose the real header height or laid-out tile size. Flag this if touching rendering/layout code.

Git history is minimal, so don't assume prior design discussion exists outside the code's own doc comments.
