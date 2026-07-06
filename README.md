# MeshTalk

MeshTalk is a native Android chat app that talks over Bluetooth Low Energy (BLE) instead of the internet. Nearby phones running the app find each other automatically — no pairing, no accounts, no server — and relay messages through each other to reach further than a single Bluetooth hop.

📄 [Privacy Policy & project site](https://chartmann1590.github.io/bluetooth-chat/)

## Features

- **No pairing required** — phones advertise and scan for each other over BLE and exchange messages over transient GATT connections.
- **Mesh relay** — messages hop through other nearby phones (TTL-limited) to extend range beyond direct Bluetooth distance, with store-and-forward for peers who are briefly out of range.
- **Public feed** — a broadcast channel visible to everyone currently on the mesh.
- **Encrypted DMs** — private messages are end-to-end encrypted (X25519 key exchange + AES-GCM) and signed (Ed25519), so only the intended recipient can read them.
- **Photos & files** — send a compressed photo or a small file inline, in both the public feed and DMs; delivered to every peer on the mesh the same way any other message is. Tap a photo to view it full screen. A per-user setting lets you turn off receiving attachments entirely (you still relay them for the rest of the mesh, you just don't store/display them locally).
- **Background operation** — a foreground service keeps the mesh alive when the app isn't in the foreground, with an optional battery-optimization exemption for reliability.
- **DM notifications** — get notified when a new direct message arrives while you're not looking at that conversation; tapping the notification opens the thread.
- **Read receipts** — sent messages show a "Read" checkmark once the recipient (DM) or any peer (public feed, listing who) has opened it.
- **Location sharing** — share your current coordinates with a best-effort static map thumbnail (fetched once at send time, then carried over the mesh like a photo — no internet needed to view it after that); falls back to coordinates + "open in Maps" if no connectivity was available when sending.
- **Typing indicators** — see "so-and-so is typing…" live in both the public feed and DM threads (ephemeral over-the-mesh signal, never stored or replayed to peers who reconnect later).
- **On-device AI chat** — a private, fully-offline chat with Google's Gemma 4 model running locally via [LiteRT-LM](https://ai.google.dev/edge/litert), Google AI Edge's on-device LLM runtime. One-time ~2.6GB download, then works with zero connectivity forever after; nothing typed here ever goes over the mesh. Uses the on-device GPU delegate when available, falling back to CPU automatically. You can attach a photo for Gemma to look at (Gemma 4 is multimodal), and past conversations are saved locally — start a new chat any time from the "+" button and pick up an old one from the history icon. A reply keeps generating even if you switch tabs mid-response.
- **Bluetooth proximity finder** — when GPS and internet are both unavailable, find a nearby contact by raw Bluetooth signal strength (RSSI): a live signal-strength gauge, a directional arrow (a rolling "which way was the signal strongest" estimate as you turn), and "Very close" → "Very far" estimate. Requires the other person to have their "Tracking beacon" enabled in Settings (which just advertises more frequently — anyone scanning can already see raw signal strength regardless).
- **Bluetooth radar tab** — an opt-in "Find" tab (Settings toggle) showing a live map/radar of everyone currently reachable over BLE at once, each plotted with an arrow (a rolling "which way was the signal strongest" estimate as you turn) and an approximate distance from RSSI. The map backdrop is your rough area only, fetched once and cached offline for reuse with no connectivity.
- **Offline map caching** — map images (for shared locations and the radar backdrop) are cached to disk the first time they're fetched, keyed by a coarse lat/lon grid cell, so viewing the same area again later works with no internet.
- **On-device AI download during onboarding** — new installs are offered a one-tap opt-in to start the Gemma 4 download right away (continues in the background regardless of which screen you're on); skip it and download later from the AI tab instead.
- **Message actions** — long-press any message (public feed or DM) to copy its text, react with an emoji, or — for your own messages — edit or delete it. Edits, deletes, and reactions all sync to everyone else on the mesh: only the original sender can edit/delete their own message (enforced both locally and by every recipient verifying the sender before applying it), and anyone can add or remove their own reaction.

## Screenshots

<table>
<tr>
<td><img src="docs/screenshots/onboarding.png" width="220" alt="Onboarding screen"><br><sub>Onboarding</sub></td>
<td><img src="docs/screenshots/onboarding-battery-prompt.png" width="220" alt="Battery optimization prompt"><br><sub>Background reliability prompt</sub></td>
<td><img src="docs/screenshots/public-feed-location-readreceipt.png" width="220" alt="Public feed with shared location and read receipt"><br><sub>Public feed: location + read receipt</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/dm-thread-read-receipt.png" width="220" alt="DM thread with read receipt"><br><sub>DM thread: read receipt</sub></td>
<td><img src="docs/screenshots/settings.png" width="220" alt="Settings screen"><br><sub>Settings</sub></td>
<td><img src="docs/screenshots/typing-indicator.png" width="220" alt="Typing indicator in public feed"><br><sub>Typing indicator</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/tracking-beacon-settings.png" width="220" alt="Tracking beacon toggle in Settings"><br><sub>Tracking beacon toggle</sub></td>
<td><img src="docs/screenshots/find-bluetooth-proximity.png" width="220" alt="Find screen showing Bluetooth signal strength"><br><sub>Find: Bluetooth proximity</sub></td>
<td><img src="docs/screenshots/ai-chat-gpu-response.png" width="220" alt="On-device AI chat with Gemma 4 response"><br><sub>On-device AI chat (Gemma 4)</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/onboarding-ai-download-prompt.png" width="220" alt="Onboarding AI download opt-in prompt"><br><sub>Onboarding: AI download opt-in</sub></td>
<td><img src="docs/screenshots/message-action-sheet.png" width="220" alt="Message action sheet with react/copy options"><br><sub>Message actions: react/copy</sub></td>
<td><img src="docs/screenshots/message-reaction.png" width="220" alt="Message with an emoji reaction"><br><sub>Emoji reaction</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/message-edited.png" width="220" alt="Edited message with (edited) tag"><br><sub>Edited message</sub></td>
<td><img src="docs/screenshots/message-deleted.png" width="220" alt="Deleted message placeholder"><br><sub>Deleted message</sub></td>
<td><img src="docs/screenshots/ai-chat-with-image.png" width="220" alt="On-device AI chat with an attached photo"><br><sub>AI chat: photo attached</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/ai-chat-history.png" width="220" alt="On-device AI chat history dialog"><br><sub>AI chat: saved history</sub></td>
<td></td>
<td></td>
</tr>
</table>

## How it works

Every phone runs both BLE roles at once:

- **Peripheral**: advertises a custom GATT service and accepts incoming connections/writes.
- **Central**: scans for other phones advertising that service and connects to them.

Packets are signed, optionally encrypted, fragmented to fit the negotiated BLE MTU, and reassembled on the other end. Each packet carries a TTL; relay nodes decrement it and re-broadcast until it reaches zero, with a dedup cache preventing loops. See `app/src/main/java/com/charles/meshtalk/app/ble/` for the protocol implementation.

Because real-world BLE throughput is roughly 1–3 KB/s in this design, photos are automatically downscaled/recompressed and generic files are capped at a small size — large transfers are much more likely to span a connection drop than short ones, and there's no resume-on-failure.

Typing indicators reuse the same signed-packet mesh transport as everything else, just with a short TTL and a separate ephemeral dedup cache — they're never persisted or replayed to a peer that reconnects later. The Bluetooth proximity finder (both the single-contact Find screen and the radar tab) reads the RSSI (signal strength) BLE already reports for each nearby advertiser and converts it to an estimated distance using the standard log-distance path-loss model (the same technique iBeacon-style proximity APIs use), rather than treating dBm as linearly proportional to distance — the latter badly overstated how far away someone standing a few feet away actually was. It doesn't use GPS at all, so it keeps working with location services and mobile data both off. Both screens' arrow direction is derived from a short rolling history of (compass heading, RSSI) samples — it's a "hunt for the strongest signal" aid, not real Bluetooth direction-finding hardware. That estimate is heavily damped (eased a small step toward the latest reading each tick rather than snapped to it), so a peer marker settles into a fixed spot on the radar and stays there — only actually moving if the underlying signal sustainedly changes (i.e. someone really did move closer or further away), not from momentary RSSI noise as you turn. The radar tab's map rotates to match whichever way you're facing (like a turn-by-turn navigation view) while peer markers stay anchored to the map itself, so the map and the peer arrows always agree on which way is "forward." The on-device AI chat is a separate, local-only feature — it downloads and runs Google's Gemma 4 model directly on the phone via LiteRT-LM, and nothing typed into it is ever sent over the mesh or the internet after the initial model download.

Map images (the radar's backdrop and the pin thumbnail for a shared location) are stitched together from real [OpenStreetMap raster tiles](https://tile.openstreetmap.org/) — the same public tile server OSM's own site uses, fetched directly with no API key and no third-party proxy in between.

Edits, deletes, and reactions are new packet types (`EDIT`/`DELETE`/`REACTION`) on the same signed mesh transport: public ones are plaintext+broadcast like a public message, DM ones are encrypted the same way the original DM was. Every recipient — not just the sender's own device — independently verifies that an edit/delete's claimed sender matches the message's actual original sender before applying it, so only the real author can change or remove their message.

## Requirements

- Android device with Bluetooth LE support (minSdk 26)
- Two or more devices to actually test mesh communication — BLE can't be emulated, so this needs real hardware

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

```
app/src/main/java/com/charles/meshtalk/app/
├── ai/             On-device Gemma 4 model manager (download, load, inference via LiteRT-LM)
├── ble/            BLE mesh transport: service, packet codec, mesh relay engine
├── crypto/         Identity keypair, signing, ECDH + AES-GCM
├── data/           Room database (contacts, messages)
├── media/          Image compression, file/location prep, offline map cache
├── notifications/  DM notification channel
├── repository/     Bridges the BLE service + Room to the UI
├── sensors/        Compass heading provider (for the Find radar tab)
└── ui/             Jetpack Compose screens
```

## Known limitations

- Peer counts can transiently show one higher than the number of physical devices right after a fresh connection, until each link's identity announcement is exchanged (BLE address rotation means one phone can briefly appear as two connections).
- No delivery confirmation or retry for failed sends — mesh messaging is best-effort.
- Attachments are capped small by design; video isn't supported.
- The static map thumbnail for a shared location requires the sender to have internet connectivity at the moment they share it; the coordinates and "open in Maps" fallback always work regardless.
- The on-device AI model is a one-time ~2.6GB download and needs a reasonably capable device (8GB+ RAM recommended) to run well; it's a separate local feature and isn't part of the mesh protocol.
- Bluetooth signal strength is a rough distance proxy, not exact — even with the path-loss-based distance estimate, walls, orientation, and interference all shift the reading by several dB. Anyone scanning nearby can see a device's raw signal strength; the "Tracking beacon" setting only controls how often that device advertises, not whether its signal is visible.
- The radar tab's map backdrop needs internet the first time an area is viewed (then it's cached offline); peer arrows are relative signal estimates, not real bearings — there's no Bluetooth AoA/direction-finding hardware involved.
- Editing is only supported for plain-text messages; photos, files, and locations can be deleted but not edited.
