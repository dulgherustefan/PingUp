# The organiser's site plan over the map

OpenStreetMap at Costinești has no toilets, parking or medical points, so the plan the organiser draws is what
people need to see. The event pack carries it as one PNG with the four points of the ground its corners lie on
(`plan` in the pack, PROTOCOL.md section 16). The app lays it over the real map and over the drawn one, and the
map's "Planul organizatorului" chip switches it between solid, see-through and hidden.

## Steps

1. **Export the plan as a PNG**, north roughly up, at most 2 MiB. A 2 × 1 km site at 2,000 px across is
   about 1 m a pixel, enough to read a toilet block. Fewer colours (an indexed PNG) keep it small. Crop to the
   site: whatever is in the image covers the map under it.
2. **Pick 5 to 8 control points** you can find both on the plan and on the ground: gate posts, corners of a
   stage or a tent, a toilet block, a fence corner. Spread them out, one near each corner of the plan and one
   in the middle; points on or near one line are refused.
3. **Read each point's pixel** on the plan (any image editor shows the cursor's x and y, from the top-left
   corner).
4. **Measure each point on the ground**: stand on it with a phone and wait for the GPS to settle (accuracy
   5 m or better), or read it off a satellite map at full zoom. Write the latitude and longitude with at
   least 5 decimals.
5. **Write the points file**, one point per line, `x, y, lat, lon`; `#` starts a comment:

   ```
   # x, y, lat, lon
   412, 96, 43.95091, 28.63402     # main gate, west post
   1630, 120, 43.95085, 28.63761   # fence corner, north-east
   1598, 1012, 43.94822, 28.63749  # toilets camping
   380, 990, 43.94830, 28.63395    # medical tent, south-west corner
   1010, 540, 43.94960, 28.63580   # main stage, front left
   ```

6. **Fit it**:

   ```
   python3 tools/plan_corners.py plan.png points.csv --out plan.json
   ```

   It prints each point's residual, how far the fit puts it from where it was measured. With any residual over
   5 m it refuses and writes nothing: the point it names is wrong (swapped x and y, the wrong corner, a GPS
   reading taken too early) or the plan isn't drawn to scale. Check that point, measure it again, or add more
   points. It also leaves each point out in turn and predicts it from the others; a point that lands over 10 m
   from where it was measured is refused the same way, since a fit bends toward one wrong point. A drawing that
   isn't to scale needs redrawing; no fit puts it right.
7. **Put it in the pack**:

   ```
   python3 tools/gen_staff_keys.py pack --id ... --version ... --venue venue.json --plan plan.json
   ```

   The pack takes the stages' schedule the same way, `--schedule schedule.json`, a list of
   `{"stage": "<zone id>", "title": "...", "start": <unix seconds>, "end": <unix seconds>}`. Before signing,
   `pack` checks both files (tools/check_pack.py): every stage is a zone of the venue, a title has at most 80
   characters, a set ends after it starts and lasts at most 12 hours, there are at most 1000 sets, the plan has four
   different corners and a PNG of at most 2 MiB.
8. **Check it on the ground** before the gates open: walk to two or three points the fit did not use and see
   that the plan lies within 5 m of where you stand on the map.

## Service points

The layers under the map come from the venue's points, by `type`: `water`, `wc`, `medical` (a zone of type
`medical` counts too), `dea`, `info`, `charge`, `shade`, `safe` (Safe Zone, welfare), `phone` (a landline),
`exit`, `lockers`, `chill` (quiet zones; a zone of that type counts too), `earplugs`. A numbered point of type
`exit`, `wc`, `water` or `charge` shows the status staff report for it, on the phones that hear those reports.
