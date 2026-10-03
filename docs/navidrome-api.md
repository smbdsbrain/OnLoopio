# Navidrome connection

OnLoopio talks to Navidrome through its Subsonic API. The account is supplied during [USB setup](setup.md), never embedded in the public image. Each API request uses a fresh salt and token. The app uses Conscrypt for TLS 1.2 on Android 4.2.2, validates the hostname and certificate chain, and does not follow redirects. An explicitly supplied CA can be used for a private HTTPS server; it does not disable certificate validation.

Online access is restricted to the configured home Wi-Fi and a successful authenticated server check. Away from that Wi-Fi, the client shows saved audio and local music. See [sync behavior](sync-model.md).

Track likes use `star` / `unstar` and complete `getStarred2` snapshots (song entries only). Qualified listens use `scrobble` with `submission=true`, repeated `id` / `time` pairs and original UTC epoch timestamps in milliseconds, in batches of up to 25. Navidrome updates its play counts and forwards scrobbles to external services configured for the account/player. Successful XML responses acknowledge uploads; malformed, failed or interrupted responses leave the affected events queued.
