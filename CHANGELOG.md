# Changelog

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
