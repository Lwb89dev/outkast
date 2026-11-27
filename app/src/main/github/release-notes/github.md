OUTKAST 2.7.0 adds Nostr Wallet Connect (NIP-47), X-style search with operators and NIP-50 relays,
NIP-13 proof of work, and optional filtering for replies and mentions from strangers.

**Nostr Wallet Connect (NIP-47).** Connect a Lightning wallet in Settings, Wallet (NWC) to pay zaps
and invoices inside OUTKAST, with the connection string encrypted by Android Keystore and explicit
confirmation before any payment.

**X-style search.** Top, Latest, People and Media tabs, follow-scope toggle, live suggestions, recent
searches, search operators (`"exact phrase"`, `OR`, `-word`, `from:`, `to:`, `since:`, `until:`,
`filter:`, `min_*`), and full-text NIP-50 relay queries.

**Proof of work (NIP-13).** Mines outgoing notes, comments, polls, reactions and direct messages by
default, with configurable difficulties in Settings, Proof of work.

**Stranger filtering.** Optional Web of Trust and Proof of Work filters in Settings, Web of Trust to
hide replies, quotes and mentions from outside your network.

See [CHANGELOG.md](https://github.com/Lwb89dev/outkast/blob/main/CHANGELOG.md) for the full list of changes.
