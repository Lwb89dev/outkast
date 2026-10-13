# OUTKAST migration and regression plan

## Navigation model

OUTKAST has no persistent navigation bar. Its four content worlds are ordered as Feed, Discover,
Long reads and Inbox. An inward swipe from either screen edge moves to the adjacent world with a
directional transition. The compact bottom portal can be tapped or pulled upward to open Orbit,
an accessible control surface for every world plus Algorithms, Bookmarks, Profile and Settings.
Selecting Home opens the explicit Recent / Recent with replies choice; selecting Algorithms opens
the complete feed picker and advanced-search editing inside Orbit. Compose remains globally
available as a floating action. The legacy side drawer, Home feed chip and header avatar are no
longer part of navigation.

## Compatibility contract

OUTKAST is an in-place product upgrade, not a new Android application. The following values are intentionally stable:

| Area | Stable value / strategy | Reason |
|---|---|---|
| Android identity | `applicationId = com.librenostr.android` | Existing installs and release signatures remain upgrade-compatible. |
| Kotlin namespace | `net.primal.android` for this phase | Renaming packages is source-only work but touches manifests, generated code and reflection; it is isolated from the user-facing rebrand. |
| FileProvider and signer authorities | Derived from `${applicationId}` | Existing integrations continue resolving the same authorities. |
| Credentials and account stores | Existing DataStore filenames and serializers | Keys, account lists and active identity survive the upgrade. |
| Android Keystore | Existing alias and package identity | Encrypted release credentials remain decryptable. |
| Room databases | Existing names, entities and schema history | No destructive data migration is introduced by the UI redesign. |
| Theme preference | Existing `active_theme.txt` | Old theme values migrate to `outkast-dark` or `outkast-light`; an empty value still means follow system. |
| Deep links | Existing `nostrich.org` routes retained | Previously shared profile, post, article and app links keep opening. |
| Signing | Existing `altRelease` configuration | Release-signing compatibility is unchanged. |

## Feature-preservation matrix

| User intent | OUTKAST location | Existing implementation retained |
|---|---|---|
| Read followed accounts and replies | Feed | Relay-backed feed repositories, paging, threads, reactions, reposts and bookmarks |
| Find people, posts, feeds and media | Discover | Explore landing, unified search, follow packs, feeds, zaps and media |
| Review personal activity | Inbox → Activity | Existing notification groups and relay refresh |
| Continue conversations | Inbox → Messages | Existing DM conversation list and NIP-17/NIP-04 transport paths |
| Publish | Global Create action | Existing editor, attachments, polls, drafts/signing and relay publishing |
| Manage identity | You | Existing profile, account switcher, key/export controls and settings |
| Zap and connect a wallet | Post actions / You → Settings | Existing NIP-47 and NIP-57 paths; no custodial behavior added |
| Configure network privacy | You → Settings → Network/Tor | Existing relay controls, Orbot/Arti selection and fail-closed routing |
| Work on tablets | Existing responsive/deck routes | Deck storage and column workflows remain available |

## Visual migration

- The only selectable identities are OUTKAST Dark and OUTKAST Light; system-following remains available.
- Persisted legacy themes map by luminance so an upgrade does not unexpectedly flip light/dark preference.
- The supplied O/K artwork is the canonical launcher and store icon.
- Adaptive launcher layers use the midnight background, canonical foreground and a derived one-color themed icon.
- Existing typography files are retained to avoid a new font dependency; Ubuntu Sans is the readable social typeface and Courier Prime is reserved for small identity/technical accents.

## Verification checklist

- Compile debug Kotlin and Android resources.
- Run unit tests, theme migration tests and lint/detekt where available.
- Build `altRelease` with the production signing configuration and verify its certificate matches the previous release.
- Install the previous signed LibreNostr build, create/import an identity and configure relays/NWC; install OUTKAST over it without uninstalling.
- Confirm identity, Keystore-backed credentials, relay list, wallet connection, bookmarks and preferences survive.
- Exercise Feed, publish/reply, Discover/search, Inbox activity/messages, profile, zaps and Tor modes.
- Verify Tor-for-everything still fails closed when its proxy/engine is unavailable.
- Check deep links, share intents, camera/file providers and remote signer authorities.
- Inspect dark/light/system themes on compact phones, large phones and tablet/deck layouts.
- Run TalkBack, font scaling, contrast and reduced-motion checks on physical or emulated devices.

The signing-certificate comparison, real upgrade installation, device accessibility pass and live relay/NWC/Tor checks require external credentials or device infrastructure and must not be reported as complete from a source-only environment.
