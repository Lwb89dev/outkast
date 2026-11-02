OUTKAST 2.3.0 adds structured NIP-20 relay responses and fixes publishing, privacy, login, compression
and language handling.

Relay `OK` responses now retain the acceptance status, machine-readable prefix and human-readable
reason, including per-relay details when every relay rejects an event. Early publish responses are
queued instead of lost. Direct-message and mute-list requests never fall back to public relays the
user did not choose. Login profile requests are cancelled when the key changes, compressed WebSocket
frames decode correctly, and the system-language option respects Android's per-app locale. The Italian
relay-deletion confirmation also shows the relay URL again.

Existing accounts, databases, Keystore material, signing identity and `com.librenostr.android` upgrade
compatibility are preserved.

See [CHANGELOG.md](https://github.com/Lwb89dev/outkast/blob/main/CHANGELOG.md) for the full list of changes.
