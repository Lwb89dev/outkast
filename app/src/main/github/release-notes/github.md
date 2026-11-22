OUTKAST 2.6.1 streamlines switching between signed-in profiles, aligns the Android module namespace
with `com.librenostr.android`, and hardens the local signer and background service lifecycle.

**Faster profile switching.** Orbit now opens with a quick account bar at the top showing your active
profile alongside one-tap avatars for your other signed-in accounts, plus an inline Accounts panel
with no extra modal hop. When multiple profiles are signed in, the bottom portal displays the active
avatar and a long-press jumps straight to the account switcher. You can also switch directly by
tapping your display name on your own profile, from the Deck mode top bar, or inline under
Settings → Accounts.

**Source & package alignment.** The app module `namespace` in `app/build.gradle.kts` now matches
`applicationId` (`com.librenostr.android`) while keeping all runtime component class names explicit,
clearing Zapstore's "Differs from source" package warning without affecting upgrades.

**Fixes.**
- Fixed a multi-account state race where switching profiles could drop the previously active account
  from the switcher list, disabled accidental switching in edit mode, and made notifications and
  unread badge baselines follow account switches cleanly.
- Reject local signer requests when the requested signing identity does not match the event author.
- Stop media playback when the app is removed from recent apps; the remote signer stops on removal
  when notifications are disabled and is no longer automatically restarted by Android.
- Initialize the remote signer only once per service instance.

Existing accounts, databases, Keystore material, signing identity and `com.librenostr.android` upgrade
compatibility are preserved.

See [CHANGELOG.md](https://github.com/Lwb89dev/outkast/blob/main/CHANGELOG.md) for the full list of changes.
