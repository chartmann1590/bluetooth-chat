# Play Store listing copy

Copy-paste source of truth for Play Console → **Grow → Store presence → Main store listing**.
Character counts are checked against Play's limits; re-check with `wc -m` if you edit the text
below, since Play Console will hard-reject anything over the limit at save time.

## App name
_(max 30 characters)_

```
MeshTalk: Bluetooth Mesh Chat
```
(29 characters)

## Short description
_(max 80 characters — shown under the app name in search results and the listing header)_

```
Chat, talk & AI over Bluetooth mesh. No internet, no accounts, fully offline.
```
(78 characters)

## Full description
_(max 4000 characters)_

```
MeshTalk is a chat app that doesn't need the internet. Nearby phones find each
other automatically over Bluetooth Low Energy and talk directly — no Wi-Fi, no
cell signal, no account, no phone number, no server anywhere in the loop.

Messages hop phone-to-phone across the mesh, so you can reach people beyond a
single Bluetooth connection as long as someone in between is running the app.
Perfect for festivals, hiking trips, campgrounds, cruise ships, classrooms,
protests, disaster prep, or anywhere the network is slow, monitored, or gone.

WHY MESHTALK

• Zero setup — open the app, pick a nickname, you're on the mesh in seconds.
• No account, ever — no email, no phone number, no login screen.
• Works with airplane mode on — Bluetooth-only, by design.
• Your identity is a keypair generated on your own device, not a username on
  someone else's server.

FEATURES

• Public feed — a broadcast channel everyone currently on the mesh can see.
• Encrypted DMs — private messages are end-to-end encrypted (X25519 + AES-GCM)
  and signed (Ed25519), so only the person you're messaging can read them.
• Walkie-Talkie — hold-to-talk voice messages over the same offline mesh.
  Direct delivery by default, with an opt-in mesh-relay setting for longer
  range.
• Photos, files & locations — send a compressed photo, a small file, or your
  current coordinates with a map preview, all delivered over the mesh.
• Mesh relay with store-and-forward — messages hop through nearby phones and
  wait for peers who reconnect later, extending range past a single hop.
• Message reactions, edit & delete — long-press any message you sent to fix a
  typo or take it back; everyone else's copy updates too.
• Read receipts & typing indicators — know when a message lands and when
  someone's replying.
• Bluetooth radar — see everyone currently reachable at once, each with a
  live direction arrow and distance estimate, no GPS required.
• Bluetooth proximity finder — find one specific nearby contact by signal
  strength alone when GPS and data are both unavailable.
• On-device AI chat — a private, fully offline conversation with Google's
  Gemma model running entirely on your phone. Nothing you type to it, or say
  to anyone else, ever leaves your device except over the mesh you control.
• Runs in the background — a lightweight foreground service keeps you
  reachable on the mesh while you're doing other things on your phone.

PRIVACY BY DESIGN

MeshTalk has no server, no analytics you didn't opt into, and no account
system to leak in a breach. Direct messages are end-to-end encrypted before
they ever leave your phone. Your nickname and keypair live locally — nobody
but the people you talk to ever sees them.

WALKIE-TALKIE (OPTIONAL)

Push-to-talk voice messages are a paid add-on: try it free for 3 days, then
keep it with a low-cost monthly subscription or a one-time lifetime purchase.
Every other feature — mesh chat, encrypted DMs, photos, files, locations, the
Bluetooth radar, and the on-device AI chat — is completely free, forever.

WHO IT'S FOR

Campers and hikers out of cell range. Festival- and event-goers on an
overloaded network. Classrooms and offices that want a local chat channel.
Anyone who wants to talk to the people physically around them without routing
it through a company's servers first.

Requires Bluetooth Low Energy support (nearly all phones from the last decade)
and works best with two or more nearby devices — that's kind of the point.
```
(character count: run `wc -m` on the block above before pasting — trimmed to fit under 4000)

## Category
Communication

## Tags / contact
- Contact email: (set to the support email you want public on the listing)
- Website: https://chartmann1590.github.io/bluetooth-chat/
- Privacy policy: https://chartmann1590.github.io/bluetooth-chat/privacy.html

## Content rating questionnaire notes
- No user-generated content moderation exists beyond what's in the app already (block/mute is out
  of scope today) — answer the Play content-rating questionnaire's messaging/UGC questions
  accordingly (this is a manual step in Play Console, not something this repo can fill in for you).
- App does not collect any personal data on a server (no server exists for chat); the `github`
  flavor's optional Walkie-Talkie purchase flow talks to Stripe/Cloudflare — see
  `docs/privacy.html` for the exact wording already published.

## Pricing & availability
- Base app: Free
- In-app products (Play flavor only): `walkietalkie_lifetime` (one-time), `walkietalkie_monthly`
  (subscription, 3-day free trial) — see main README's "Setting up Google Play Billing" section for
  how these are created in Play Console; this listing doc doesn't duplicate that setup process.
