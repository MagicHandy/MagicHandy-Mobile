# Brand assets

The MagicHandy mark is a hand that has just flicked a star into the air. It comes from the desktop app (ADR 0073, `assets/brand` at alpha.89), and these masters are byte-identical copies of its files.

| File | What it is |
|---|---|
| `mark.svg` | The master drawing: the full mark on its tile |
| `star.svg` | The small-size glyph: the star alone on the tile, for 24 px and below, where the hand isn't legible |

## Where the app uses it

| Use | Resource | How it's drawn |
|---|---|---|
| Launcher icon | `res/mipmap-anydpi/ic_launcher.xml` | An adaptive icon. The tile colour is the background, `res/drawable/ic_launcher_foreground.xml` is the foreground, and `ic_launcher_monochrome.xml` is the themed icon. |
| Splash screen | `res/values/themes.xml` | The launcher foreground on the tile colour, over the window ground |
| Notification | `res/drawable/ic_stat_star.xml` | The star alone, in white, so the system can tint it |
| Before you start | `ui/components/BrandMark.kt` | The mark on its tile, painted by the palette: the tile takes `raised`, the hand `text`, and the star and streak `accent` |

The Quick Settings tile shows the Stop square (`res/drawable/ic_tile_stop.xml`) rather than the mark, because the tile is a Stop control.

## Geometry

Every use draws the desktop's paths (`web/src/brand/paths.ts`) in a 64-unit box, and the hand uses the even-odd fill rule. Change them all together.

The launcher drawables map the box onto the adaptive icon's 66 dp safe zone: a scale of 1.03125 and an offset of 21 dp. That keeps the star inside every launcher mask, while the forearm runs off the edge as it does on the tile.

Icons can't follow the palette saved in the app, so the launcher icon, splash screen and notification keep the default Steel Azure colours from `res/values/colors.xml`: tile `#1D2024`, hand `#E9E7E2`, and star and streak `#5B9DD9`.
