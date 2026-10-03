# OnLoopio

**A different music player for the Innioasis Y1.** OnLoopio is an alternative firmware-player for the **verified Y1 Type A** hardware profile. It turns the little wheel-controlled player into a Navidrome companion while keeping local music and offline listening close at hand. The release is a complete BOOT + SYSTEM firmware package, with a Windows installer and checksums; the APK is supplied separately for developers.

> **Hardware:** Y1 Type A only. Type B needs its own hardware validation. Installing firmware always carries a recovery risk; [read the installation guide](docs/install.md) and keep the backup the installer creates.

| Now playing | Library | Playlists |
|:--:|:--:|:--:|
| ![Now playing with cover art](docs/screenshots/now-playing.png) | ![Library on the Y1](docs/screenshots/library.png) | ![Navidrome playlists](docs/screenshots/playlists.png) |

| Playlist tracks | Offline downloads | Settings |
|:--:|:--:|:--:|
| ![Playlist tracks](docs/screenshots/playlist-detail.png) | ![Saved offline track](docs/screenshots/offline-downloads.png) | ![Device settings](docs/screenshots/settings.png) |

Screens are real Y1 captures, including an offline download completed on v0.9.0.

## What it does

- Browse a Navidrome library by playlists, artists, albums, tracks and genres.
- Play over Wi-Fi or keep selected music on the Y1 for offline listening.
- Use the wheel and hardware buttons for playback, menus, volume and seeking.
- Keep music in the USB-visible `Music` folder and play it alongside server music.
- Like tracks from their menu or with a double Play press; browse Favorite tracks and sync likes and offline listens with Navidrome.
- Adjust device settings directly on the player.

## Install in brief

1. Download the latest **OnLoopio Y1 Type A firmware ZIP** and `SHA256SUMS.txt` from [Releases](https://github.com/smbdsbrain/OnLoopio/releases). Check the checksum and extract the ZIP on a Windows PC.
2. Follow [Install and recovery](docs/install.md). The installer checks the hardware profile and makes a private backup before it writes BOOT and SYSTEM.
3. Start the Y1. Connect its USB storage to your PC and run `setup-usb.ps1` with the Y1 drive letter to enter your Wi-Fi and Navidrome details. Safely eject and disconnect the player; OnLoopio imports the setup. See [First setup](docs/setup.md).
4. Choose playlists or tracks to keep offline. Music is downloaded only when you select it.

**More:** [Русский README](docs/README.ru.md) · [Controls](docs/controls.md) · [Public documentation](docs/index.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Third-party notices](THIRD_PARTY.md)

The OnLoopio source is MIT licensed. The firmware package incorporates upstream stock and ATA binary components with separate provenance and rights; see [third-party notices](THIRD_PARTY.md).
