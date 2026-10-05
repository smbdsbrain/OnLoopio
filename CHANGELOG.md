# Changelog

## Unreleased

- Add seven-day private partial expiration, measured artifact duration/file-average bitrate, and API17 ReplayGain with common 6 dB headroom.
- Build separately signed candidate instrumentation in GitHub Actions for reversible long-file/focus and powered playback endurance checks.

- Save playback queues, occurrence IDs, position and listen attempts; restore on pause after process or storage lifecycle changes.
- Preserve a complete offline playlist while its replacement downloads, with durable publication recovery and protected cache references.
- Separate feedback, playlist, catalog and download work; add history, held-event actions, bounded retry and battery/charging policies.
- Preserve original listening timestamps with durable clock uncertainty markers and bound retries after clock rollback.
- Add validated original Range resume and separate Compatible/Original/Compact artifacts.
- Add opt-in attenuation-only ReplayGain and experimental WAV/FLAC prepared-next playback; qualification limits and reproduction steps are recorded in [reliability notes](docs/reliability.md).
- Add API17 migration, large-queue, publication, lifecycle, TLS/Range and wired measurement fixtures. Release gates remain open.

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
