# Architecture

OnLoopio is the HOME application embedded in a Y1 Type A BOOT/SYSTEM image. The stock 3.1.2 kernel and Android framework are retained; a small set of verified ATA Type A radio files supplies WLAN support. The public boot image keeps ADB disabled and exposes USB mass storage.

The application uses a private settings store for Navidrome credentials and home SSID. A temporary USB-visible JSON file is imported after storage returns to the player, then deleted. Server requests use TLS 1.2 via bundled Conscrypt on Android 4.2.2, with hostname verification and an optional user-supplied CA. The account password is not logged.

MetadataStore keeps catalog, playlist, local-music and download state in SQLite. The UI stays usable offline with saved audio and local files. Playback uses Android MediaPlayer; online playback streams through a localhost proxy with a random in-memory token so native decoding does not receive server credentials. Audio is downloaded into temporary files and published to the cache only after validation.

The release build validates upstream archive hashes, APK signature/API level, boot security settings, absence of a configuration seed and the final artifact checksums. See [build](build.md) and [firmware](firmware.md).

Durable playback, generation/publication contracts, feedback delivery, energy policy and experimental audio gates are described in [reliability notes](reliability.md).
