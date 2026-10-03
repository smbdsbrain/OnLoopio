# Build and release

Pull requests run the secret-free CI workflow: public-tree checks, Java compilation, and unit tests. No signing credentials are available to pull request jobs.

The release workflow runs in GitHub Actions for a `v*` tag that points at `main`. It builds an unsigned APK with JDK 17 and Android SDK 35, signs it with the encrypted GitHub Actions keystore, checks API 17 v1 signing and APK contents, then downloads two pinned upstream archives. Their SHA-256 values are checked before any image processing. The Linux image tool applies the verified Type A radio payloads to stock 3.1.2, embeds OnLoopio as the HOME app, checks the boot ramdisk and system image for private configuration, then publishes the firmware ZIP, APK and `SHA256SUMS.txt`.

The private release key is stored outside Git and only a base64 copy is kept as an encrypted Actions secret. Local builds are for development and **never become release files**. A candidate can be built by the GitHub workflow before a tag; it must be tested on a Y1 Type A before the first public tag. See [Contributing](../CONTRIBUTING.md).

Sources: [stock 3.1.2](https://github.com/y1-community/y1-stock-rom/releases/tag/Latest-3.1.2), [ATA Type A 0.1](https://github.com/y1-community/y1-ata-rom/releases/tag/0.1). Their binaries have separate provenance from the MIT licensed OnLoopio code; see [third-party notices](../THIRD_PARTY.md).

`FeedbackDeviceProbe` is a separate, explicitly selected instrumentation entry point for a configured physical player. The application and test APK must have matching signatures. Its `snapshot`, `offline`, `sync`, `remote`, and `restore` operations verify offline playback, durable feedback across processes, uploads through the playlist worker with downloads paused, and server-side like changes. A downloaded MP3/FLAC longer than 65 seconds is required. The probe restores preferences and the fixture's original like; its one actual qualifying listen remains in Navidrome history. Always finish with `restore`, including after a failed operation.

The optional `native` operation waits for screen-off and two native Play key presses while audio is paused. `manual-race` checks that a disabled automatic trigger cannot cancel an accepted manual check during its debounce window. Select operations with `adb shell am instrument -w -e operation OPERATION io.onloopio.tests/io.onloopio.FeedbackDeviceProbe`; ordinary test discovery does not run them.

The snapshot ZIP contains private application data and must stay outside Git. If vendor storage permissions prevent pulling it directly, `export` writes only to an existing empty `/data/local/tmp/onloopio-feedback-device.zip` prepared by ADB. Mode `622` lets the application write while only the shell owner can read. Change it to `600` before pulling, then remove that temporary file; `restore` also removes the probe's private state and original ZIP.
