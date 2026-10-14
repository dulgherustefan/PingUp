# Map data: packs, styles, fonts and sprites

Every map byte comes from a file: in the app, or downloaded once from a static host the team controls. There is no
tile server. All packs are PMTiles v3 archives in the Protomaps v4 schema, cut from one pinned Protomaps build, so one
layer template draws them all.

| Pack | Kind | Built by | Where it lives |
|---|---|---|---|
| The festival's map (Costinești, z0-15) | `EVENT` | `tools/map_extract.py` | `app/src/main/assets/maps/<buildDate>/` |
| The world overview (z0-5 or z0-6) | `OVERVIEW` | `tools/map_overview.py` | built for a release, not kept in git |
| A country or region, a county, the coast | `AREA` | `tools/map_overview.py --bbox`, or `pmtiles extract` with a polygon | the static host, listed in the catalog |

## The festival pack

```
maps/<buildDate>/
  costinesti.pmtiles        tools/map_extract.py
  manifest.json             tools/map_extract.py (the pack's manifest, below)
  style-light.json          copied from tools/map_styles by map_extract.py
  style-dark.json
  fonts/<stack>/<range>.pbf by hand, see below
  sprites/v4/light.json, light.png, light@2x.json, light@2x.png, dark.* the same
```

Without the folder the app shows the drawn venue map. With it, the Android app copies the folder to
`filesDir/maps/<buildDate>/` on first start, checks the SHA-256 of the tiles, makes the files read-only and loads
the style with `__PACK__` and `__ASSETS__` replaced by that folder. A new build date is a new folder; a folder is
never rewritten (MapLibre crashes when a PMTiles file changes under an open map, maplibre-native #3658), so an edit
to a file in an existing folder reaches only phones that install the app from scratch.

## The pack manifest

Every pack has a `manifest.json` beside it: `PackManifest` in `core/.../map/MapPacks.kt`.

| Field | |
|---|---|
| `id` | the pack's name, lowercase letters, digits and dashes |
| `kind` | `OVERVIEW`, `EVENT` or `AREA` |
| `buildDate` | yyyyMMdd, the day of the OpenStreetMap data |
| `file`, `sha256`, `bytes` | the PMTiles file beside it, its SHA-256 in hex and its size |
| `bbox` | west, south, east, north; west past east crosses the antimeridian |
| `minzoom`, `maxzoom` | the zooms the file holds |
| `attribution` | what the map credits, HTML links: OpenStreetMap's always, ESA WorldCover's too when the file holds zooms 0-7 (`attribution()` in `map_extract.py`) |
| `label` | the name the app lists |
| `source`, `etag` | only for a pack cut on the phone: the archive it came from and its ETag then |

The festival's manifest keeps the fields the old Android reader (`MapPack.kt`) reads first and adds the rest; that
reader ignores fields it doesn't know.

## The world overview

```
python tools/map_overview.py --source https://<host>/<build>.pmtiles --maxzoom 5 --dry-run
python tools/map_overview.py --source https://<host>/<build>.pmtiles --maxzoom 5
```

It reads the header and the directories with HTTP range requests (User-Agent `pingup-map-tools/1.0`), then only the
tiles up to `--maxzoom`, and writes `world.pmtiles` and its manifest to `tools/out/maps/world/<buildDate>/`.
`--dry-run` prints the tile count and the exact size of the file it would write, from the directories alone. The
tiles are copied unchanged and tiles the build stores once are stored once here too.

The budget is 10-20 MB in each app. Published sizes for the world are 17 MB at z0-5 and 46 MB at z0-6 (2023 builds,
more now): run the dry runs for z5 and z6 on the build you pin and keep the numbers with the decision.
`--source` takes a local file too.

A low-zoom pack for a region (z7-8 over Romania and its neighbours, say) is a separate `AREA` pack:
`--minzoom 7 --maxzoom 8 --bbox 20.2,43.6,29.8,48.3 --id ro-low --label România`. It can't go into the overview's
file: the overview's source stops at one zoom everywhere, and MapLibre draws nothing where a deeper tile is missing.

## Downloadable packs and the catalog

The app lists the packs it can download from a signed catalog. Packs are whole files, cut once per release from the
pinned build; the app fetches them with the same resumable range reads and checks the SHA-256 from the catalog.

```
python tools/map_catalog.py keygen          # once: the seed goes to tools/out/maps_catalog_key.json
python tools/map_catalog.py build packs.json --base-url https://<host>/packs/
python tools/map_catalog.py verify tools/out/maps_catalog.json
```

`packs.json` lists `{"file", "id", "label", "outline"}` items, `outline` a GeoJSON polygon (the country's or the
county's border); the tool reads the bbox and zooms from each file, hashes it, simplifies the outline to at most
200 points and prints where each file goes on the host. The catalog is
`{"format": "pingup-maps", "version": 1, "body", "signature"}`: Ed25519 over `SP-MAPS-v1` followed by the body's UTF-8
bytes, with the same signing code as the event packs (`gen_staff_keys.py`). Its public key goes into the app's assets
once the team settles where the seed is kept; until then nothing in the app trusts a catalog.

## Hosting

- Static files on a host the team controls (the proposal is Cloudflare R2, an EU bucket on its own domain), HTTPS
  only, answering `Range` with 206. Python's `http.server` does not do ranges.
- File names never change: `<id>-<buildDate>.pmtiles`. A new build is a new name and a new catalog.
- The app never reads `build.protomaps.com`: the Protomaps docs ask for a copy of the build, not hotlinks. Copy the
  pinned build to the host, or cut the packs from it once per release.
- The host sees the phone's IP address and the byte ranges, so the area downloaded (`PRIVACY.md`).

## Styles

`tools/map_styles/style-{light,dark}.json` are the festival's styles and the reference for the map's look. The app's
style is composed in shared code, `ui/.../map/style/MapStyleBuilder.kt`: the same layers on one source per pack, the
overview's share of them (fills, boundaries, roads to z8, names to z9), a "no data" grey where nothing has tiles, and
the overlay the screens draw. Names read `name:<app language>`, then `name:en`, then `name`. When a look changes here,
change `BaseLayers.kt` too: `MapStyleBuilderTest` compares the two, and its golden files
(`ui/src/commonTest/resources/golden/`) show the whole composed style.

Credit: "© OpenStreetMap contributors", linked to openstreetmap.org/copyright, in the styles, the manifests and the
archives `map_overview.py` writes. The landcover layer of zooms 0-7 is ESA WorldCover (CC BY 4.0), so a pack that
holds those zooms credits it as well; the composed style takes each source's credit from its manifest, and
`MapStyleBuilder.credits` lists them for "About the map". MapLibre Native may take a source's credit from the PMTiles
metadata rather than from the style; the festival pack's metadata still says Protomaps' "© OpenStreetMap" until it
is rebuilt.

## Fonts

From [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets), folder `fonts/`, SIL Open Font
License 1.1. The styles use two stacks, `Noto Sans Regular` and `Noto Sans Medium`. Romanian needs only these ranges:

- `0-255.pbf` (Latin)
- `256-511.pbf` (ă, â, î, ş, ţ)
- `512-767.pbf` (ș and ț with comma below)
- `1024-1279.pbf` (Cyrillic: a few OSM names near the site carry it; without it MapLibre logs a failed range)
- `8192-8447.pbf` (dashes, quotes, the ellipsis)

Names anywhere need five more in both stacks: `768-1023` (Greek), `1280-1535` (Armenian, Hebrew), `1536-1791`
(Arabic), `4096-4351` (Georgian) and `7680-7935` (Vietnamese). They are not in the app yet.

Copy them to `fonts/Noto Sans Regular/` and `fonts/Noto Sans Medium/`. The license is `LICENSES/OFL-1.1.txt`, and
`REUSE.toml` names the font's authors.

## Sprites

From the same repository, folder `sprites/v4/`: `light.json`, `light.png`, `light@2x.json`, `light@2x.png` and the
four `dark` files. They draw the icons of OpenStreetMap points at zoom 15 and above; a missing icon only leaves the
name without its symbol. They are MIT, drawn from Mapzen's [tangrams/icons](https://github.com/tangrams/icons), and
the tiles are OpenStreetMap data under the ODbL; `REUSE.toml` says both.

## Checking a pack

- `pmtiles show costinesti.pmtiles`: bounds and max zoom 15.
- `python tools/map_extract.py <date> --dry-run` prints the tile count and size before anything is written.
- `python tools/map_overview.py --source <file> --maxzoom 15 --dry-run` reads any local pack without the `pmtiles`
  package: 183 tiles for the festival's.
- Glyphs and sprites load through `file://`; that is documented for PMTiles only, so check the labels on a phone.
- The tool tests: `python tools/test_map_overview.py`, `python tools/test_map_catalog.py` (the signing tests need
  PyNaCl). They read only the festival pack in the repository; nothing touches the network.
