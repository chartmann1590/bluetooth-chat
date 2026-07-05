# MeshTalk

MeshTalk is a native Android chat app that talks over Bluetooth Low Energy (BLE) instead of the internet. Nearby phones running the app find each other automatically — no pairing, no accounts, no server — and relay messages through each other to reach further than a single Bluetooth hop.

📄 [Privacy Policy & project site](https://chartmann1590.github.io/bluetooth-chat/)

## Features

- **No pairing required** — phones advertise and scan for each other over BLE and exchange messages over transient GATT connections.
- **Mesh relay** — messages hop through other nearby phones (TTL-limited) to extend range beyond direct Bluetooth distance, with store-and-forward for peers who are briefly out of range.
- **Public feed** — a broadcast channel visible to everyone currently on the mesh.
- **Encrypted DMs** — private messages are end-to-end encrypted (X25519 key exchange + AES-GCM) and signed (Ed25519), so only the intended recipient can read them.
- **Photos & files** — send a compressed photo or a small file inline, in both the public feed and DMs. A per-user setting lets you turn off receiving attachments entirely (you still relay them for the rest of the mesh, you just don't store/display them locally).
- **Background operation** — a foreground service keeps the mesh alive when the app isn't in the foreground, with an optional battery-optimization exemption for reliability.
- **DM notifications** — get notified when a new direct message arrives while you're not looking at that conversation; tapping the notification opens the thread.
- **Read receipts** — sent messages show a "Read" checkmark once the recipient (DM) or any peer (public feed, listing who) has opened it.
- **Location sharing** — share your current coordinates with a best-effort static map thumbnail (fetched once at send time, then carried over the mesh like a photo — no internet needed to view it after that); falls back to coordinates + "open in Maps" if no connectivity was available when sending.

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
<td></td>
</tr>
</table>

## How it works

Every phone runs both BLE roles at once:

- **Peripheral**: advertises a custom GATT service and accepts incoming connections/writes.
- **Central**: scans for other phones advertising that service and connects to them.

Packets are signed, optionally encrypted, fragmented to fit the negotiated BLE MTU, and reassembled on the other end. Each packet carries a TTL; relay nodes decrement it and re-broadcast until it reaches zero, with a dedup cache preventing loops. See `app/src/main/java/com/charles/meshtalk/app/ble/` for the protocol implementation.

Because real-world BLE throughput is roughly 1–3 KB/s in this design, photos are automatically downscaled/recompressed and generic files are capped at a small size — large transfers are much more likely to span a connection drop than short ones, and there's no resume-on-failure.

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
├── ble/            BLE mesh transport: service, packet codec, mesh relay engine
├── crypto/         Identity keypair, signing, ECDH + AES-GCM
├── data/           Room database (contacts, messages)
├── media/          Image compression, file size/type validation
├── notifications/  DM notification channel
├── repository/     Bridges the BLE service + Room to the UI
└── ui/             Jetpack Compose screens
```

## Known limitations

- Peer counts can transiently show one higher than the number of physical devices right after a fresh connection, until each link's identity announcement is exchanged (BLE address rotation means one phone can briefly appear as two connections).
- No delivery confirmation or retry for failed sends — mesh messaging is best-effort.
- Attachments are capped small by design; video isn't supported.
- The static map thumbnail for a shared location requires the sender to have internet connectivity at the moment they share it; the coordinates and "open in Maps" fallback always work regardless.
