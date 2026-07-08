# Play Store assets

Everything needed for the Play Console listing lives here, organized to match Play Console's own
upload sections.

```
play-store/
├── listing/
│   └── store-listing.md        Title, short description, full description, category — copy-paste
│                                source for Grow → Store presence → Main store listing.
├── graphics/
│   ├── icon/
│   │   ├── ic_launcher_512.png             512×512 hi-res icon (opaque, no rounding — Play
│   │   │                                    applies its own mask).
│   │   └── ic_launcher_foreground_512.png  Transparent-background foreground layer, matches the
│   │                                        app's actual adaptive icon (see app/src/main/res/
│   │                                        mipmap-anydpi-v26/ic_launcher.xml).
│   ├── feature-graphic/
│   │   └── feature-graphic-1024x500.png    Store listing header banner.
│   └── screenshots/
│       ├── phone/           1080×1920, 6 screenshots
│       ├── tablet-7in/      1200×1920, 6 screenshots
│       └── tablet-10in/     1600×2560, 6 screenshots
└── promo-video/
    └── meshtalk-promo.mp4   32.5s vertical (1080×1920) promo video, narrated with burned-in
                              captions. Play Console wants a YouTube URL, not a direct upload —
                              upload this file to YouTube (unlisted is fine) and paste that URL
                              into Grow → Store presence → Main store listing → Video.
```

## What's real vs. composited

The screenshots are real, unedited captures of the running app (Pixel 8 Pro, `play` flavor)
composited into a branded frame with a headline caption — the app content itself is not staged
or mocked up. The 7in/10in tablet versions reuse the same phone-UI captures (this app doesn't
have a distinct tablet layout yet) scaled into correctly-sized canvases, which is standard
practice and satisfies Play's per-device-class screenshot requirement.

The app icon (`app/src/main/res/mipmap-anydpi-v26/`) didn't exist before this pass — the app was
shipping with the default Android launcher icon. The icon here is the same one now wired into the
actual app.

## Regenerating

The HTML/CSS templates and generation notes used to build these (screenshot device-frame
compositor, feature graphic, title/end cards for the video) aren't checked into the repo — they
were built as scratch files during a Claude Code session. If you need to regenerate or tweak any
of these assets, the easiest path is to ask Claude Code to rebuild them from the source
screenshots in `docs/screenshots/` following the same style (dark gradient background, teal
`#5eedb0` accent, mesh-node decorative lines, phone device-frame mockup).
