# Map data: fonts and sprites

The base map pack lives in `app/src/main/assets/maps/<buildDate>/` and is not in the repository until someone builds it:

```
maps/<buildDate>/
  costinesti.pmtiles        tools/map_extract.py
  manifest.json             tools/map_extract.py (build date, SHA-256, bbox, max zoom)
  style-light.json          copied from tools/map_styles by map_extract.py
  style-dark.json
  fonts/<stack>/<range>.pbf by hand, see below
  sprites/v4/light.json, light.png, light@2x.json, light@2x.png, dark.* the same
```

Without the folder the app shows the drawn venue map. With it, the app copies the folder to
`filesDir/maps/<buildDate>/` on first start, checks the SHA-256 of the tiles, makes the files read-only and loads
the style with `__PACK__` and `__ASSETS__` replaced by that folder. A new build date is a new folder; a folder is
never rewritten (MapLibre crashes when a PMTiles file changes under an open map, maplibre-native #3658).

## Fonts

From [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets), folder `fonts/`, SIL Open Font
License 1.1. The styles use two stacks, `Noto Sans Regular` and `Noto Sans Medium`. Romanian needs only these ranges:

- `0-255.pbf` (Latin)
- `256-511.pbf` (ă, â, î, ş, ţ)
- `512-767.pbf` (ș and ț with comma below)
- `8192-8447.pbf` (dashes, quotes, the ellipsis)

Copy them to `fonts/Noto Sans Regular/` and `fonts/Noto Sans Medium/`, and put the font's `OFL.txt` in `licenses/`
as `OFL-Noto-Sans.txt`.

## Sprites

From the same repository, folder `sprites/v4/`: `light.json`, `light.png`, `light@2x.json`, `light@2x.png` and the
four `dark` files. They draw the icons of OpenStreetMap points at zoom 15 and above; a missing icon only leaves the
name without its symbol.

## Checking a pack

- `pmtiles show costinesti.pmtiles`: bounds and max zoom 15.
- `python tools/map_extract.py <date> --dry-run` prints the tile count and size before anything is written.
- Glyphs and sprites load through `file://`; that is documented for PMTiles only, so check the labels on a phone.
