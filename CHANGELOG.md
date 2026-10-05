# Changelog

## 0.11.0

- Save playback queues, repeat/shuffle order, position and listen attempts; restore the session paused after process or storage lifecycle changes.
- Preserve a complete offline playlist while its replacement downloads, with recoverable file publication and protected cache references.
- Separate feedback, playlist, catalog and download work; add listening history, held-event actions, bounded retries and battery/charging policies.
- Preserve original listening timestamps with durable clock uncertainty markers and bound retries after clock rollback.
- Resume Original downloads with validated strong-ETag Range requests; keep separate Compatible/Original/Compact artifacts, show measured file-average bitrate and expire inactive private partials after seven days.
- Add opt-in Track/Album ReplayGain with common 6 dB headroom and EQ/peak compensation. Positive gain is relative to that shared reserve and is limited to 6 dB; Off retains the original level.
- Add experimental prepared-next playback for local/completed WAV and FLAC with Repeat Off and EQ off. It is disabled by default and falls back to sequential playback.
- Validate API-17 migrations, large queues, publication recovery, TLS/Range, native playback/focus and navigation. The signed implementation passed a two-hour powered offline playback/focus/navigation run with cleanup and state restoration; wired WAV/FLAC boundary and gain measurements are documented in [reliability notes](https://github.com/smbdsbrain/OnLoopio/blob/v0.11.0/docs/reliability.md).

### Qualification and upgrade limits

- The Y1's native decoder can seek to an incorrect position in long FLAC files without a usable SEEKTABLE. Two-hour VBR MP3 and FLAC with seek points passed the middle/end seek and focus checks; user audio is not rewritten.
- Gapless streaming, MP3 and Bluetooth are outside the qualified feature scope. Wired microphone measurements are reference projections, not calibrated analog gain guarantees.
- Physical SD removal, USB ownership transitions, power loss, reboot/shutdown, Bluetooth and comparative battery tests were excluded from this release qualification. The two-hour powered offline run does not establish those properties or eight-hour endurance.
- The main database upgrades additively from 9 to 20 and the audio ownership index from 1 to 2. Downgrading requires a matching private pre-upgrade backup; the newer databases must not be opened by an older APK.
- Android 4.2.2/API 17, ARMv7, verified Y1 Type A, signing, TLS and public firmware security defaults are retained.

## 0.10.1

- Fixed background HTTPS requests retaining sockets in Android 4.2's connection pool, eventually exhausting file descriptors and crashing SQLite reads when returning to the player.
- Release active streaming connections when stopping the localhost audio proxy.
- Use scalar SQLite statements for flags, counters and single IDs, avoiding unnecessary 2 MiB cursor windows on the Y1.
- Share one SQLite connection pool per database and use WAL with full write synchronization so background updates do not block readers or fail when another component opens the database.
- Obtain Wi-Fi managers from the application context so settings and sync services can be released on the legacy firmware.
- Added explicit API-17 resource probes with a disposable keep-alive TLS fixture and an eight-hour background/resume endurance check.

## 0.10.0

- Added Like / Remove like actions, hearts in track lists and a Favorite tracks library section. Double pressing the lower Play button toggles a like while keeping playback unchanged.
- Added persistent offline listening events and Navidrome scrobble uploads through the playlist synchronization worker, including when downloads are paused.
- Added bidirectional Navidrome likes, account-scoped feedback queues and retry handling that preserves pending local changes. Likes and listening statistics for local Music files stay on the player.
- Fixed a disabled automatic startup trigger canceling an accepted manual playlist sync during its debounce window.
- Fixed context menus transferring their selection to parent lists. Back now restores the original item and scroll position, follows reordered items and handles removed playlists/favorites. Download menus follow the selected track when queue order changes.
- Validated feedback and navigation on a physical Y1 Type A, with API-17 compilation, JVM fixtures and instrumented regression checks.

## 0.9.0

- First public alternative firmware-player package for the verified Y1 Type A profile.
- Added USB first-run setup for Wi-Fi, Navidrome and optional private CA.
- Moved firmware assembly and signing to GitHub Actions with upstream archive verification.
- Added a Windows installer with Type A profile check, private backup and BOOT/SYSTEM-only flashing.
- Published product and recovery documentation with real Y1 screenshots.
