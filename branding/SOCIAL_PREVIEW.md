# GitHub Social Preview — SceneView

**Size**: 1280x640 PNG (2:1 ratio, required by GitHub)
**Location**: Repo Settings > General > Social preview

The rules are those of [`DESIGN.md`](../DESIGN.md): flat colour from the M3 role table,
the system font, nothing from its Forbidden list (no gradient, no glass, no decorative
pills, no shadow).

## Layout

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│   Background: solid surface (dark scheme) #111318              │
│                                                                │
│         Flat cube (DESIGN.md § Logo), ~200x200px,              │
│         centred horizontally, upper third                      │
│                                                                │
│            SceneView                                           │
│            system font, bold, 64px, on-surface #E2E2E9         │
│                                                                │
│            3D & AR for Compose — Android, iOS, Web             │
│            system font, regular, 28px, on-surface-variant      │
│            #C4C6D0                                             │
│                                                                │
│            Android · iOS · Web · visionOS                      │
│            plain text, 20px, on-surface-variant #C4C6D0        │
│                                                                │
│                                     github.com/sceneview       │
│                                     16px, outline #8E9099      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

A light variant, if one is needed, uses the light roles: `surface` #F9F9FF,
`on-surface` #1A1B20, `on-surface-variant` #44474F, `outline` #74777F.

## Colours

| Element | Role (dark scheme) | Hex |
|---|---|---|
| Background | `surface` | `#111318` |
| Title | `on-surface` | `#E2E2E9` |
| Tagline, platform line | `on-surface-variant` | `#C4C6D0` |
| URL | `outline` | `#8E9099` |
| Cube | primary palette tones, flat | see `DESIGN.md` § *Logo* |

## Export

Draw it in Figma (or any vector tool) with the system font of the machine
(SF Pro on macOS, Roboto on Android-made exports) and export a PNG at exactly 1280x640.

## Checklist

- [ ] Export at exactly 1280x640px
- [ ] Upload at: github.com/sceneview/sceneview > Settings > Social preview
- [ ] Verify preview on: https://www.opengraph.xyz/
- [ ] Text is legible at thumbnail sizes (~600px wide)
- [ ] Passes the `DESIGN.md` review test on the exported image
