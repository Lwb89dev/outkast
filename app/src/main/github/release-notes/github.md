OUTKAST 2.6.0 adds drawing to the photo editor, custom emoji as reactions, and support for `nostr:` links, so
notification apps such as Pokey can open a note in OUTKAST.

**Draw on a photo.** The photo editor has a new Draw tool: a freehand pen, a line, an arrow, a rectangle and
a circle, each outlined or filled, with a color palette and a custom color picker, an adjustable thickness,
and undo. Drawings are painted after the filters, turn and flip with the photo, and the saved picture is
rendered from the same shapes as the screen.

**Custom emoji as a reaction.** The sheet a long press on the heart opens has a tab for each of your emoji
packs next to the system emoji. The reaction goes out as `:shortcode:` with its `emoji` tag (NIP-25 and
NIP-30), so other clients draw the image too.

**`nostr:` links.** OUTKAST now opens `nostr:` links to profiles, notes and articles, including from a cold
start. Notification apps and web pages that hand over a `nostr:` link no longer fall back to a browser page.

**Amber and bunker accounts** can reply to a comment on a Zapstore app page again.

Thanks to Zaikaboy on Nostr, who reported the Pokey and custom emoji reaction problems.

Existing accounts, databases, Keystore material, signing identity and `com.librenostr.android` upgrade
compatibility are preserved.

See [CHANGELOG.md](https://github.com/Lwb89dev/outkast/blob/main/CHANGELOG.md) for the full list of changes.
