# Online and offline behavior

OnLoopio goes online only on the configured home Wi-Fi after an authenticated Navidrome check, unless Force offline is enabled. Leaving that network closes an active online stream. Cached downloads and local music continue to work without server access.

Metadata and playlist updates are applied as complete SQLite transactions. Failed checks preserve the last good snapshot. Offline views show only tracks with a complete playable file on the device; a catalog entry alone does not count as an offline download. Download jobs persist across restarts and are processed independently of playback.

Likes synchronize in both directions. A local like/unlike takes effect immediately and remains authoritative until sent; refreshing server favorites cannot overwrite a pending edit. Only the latest desired state per track is queued. Likes changed on other clients appear on the next successful synchronization.

A listen is recorded after 30 seconds or half the track, whichever comes first (minimum one second; unknown duration uses 30 seconds). Only actual audio progress counts: pauses, buffering and seeking are excluded. Pause/resume keeps the same session; a repeat or new playback starts another. Qualifying listens update local statistics and enter a durable SQLite queue in one transaction. Their original start timestamps are sent to Navidrome with `scrobble` and `submission=true`, updating server play counts and external scrobbling configured there.

Online changes trigger feedback-only work in the existing playlist sync service. Offline events are sent during its regular home Wi-Fi, charging, timer or manual checks, even with no audio downloads pending or downloads paused. Force offline prevents network requests. Disabling automatic playlist sync also disables automatic feedback uploads; **Check now** still sends them. Failed feedback requests remain queued and do not prevent playlist checks or audio downloads. Sync settings show pending events and partial results.

Pending events and server likes are scoped to the server/account. Switching accounts keeps the old outbox and resumes it when that account returns. Local `Music` likes and statistics are independent and are never sent to Navidrome. Existing aggregate play counts are not uploaded retroactively because they have no individual timestamps. Confirmed batches are removed; after a lost server acknowledgement, retrying a scrobble can count it again because the API has no idempotency key.

An optional user-provided CA allows HTTPS to a private Navidrome server while retaining normal chain and hostname verification. See [initial setup](setup.md) and [API connection](navidrome-api.md).
