<p align="center">
  <img src=".github/assets/root-my-galaxy-banner.svg" alt="Root My Galaxy Extended" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Extended/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/igorcv88/Root-My-Galaxy-Extended?label=release" /></a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Extended/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/igorcv88/Root-My-Galaxy-Extended/total" /></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-16%20%2F%2017-3DDC84?logo=android&amp;logoColor=white" />
  <img alt="KernelSU" src="https://img.shields.io/badge/KernelSU-3.3.0-2f81f7" />
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Extended/actions/workflows/release.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/Root-My-Galaxy-Extended/release.yml?branch=main&amp;label=build" /></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/igorcv88/Root-My-Galaxy-Extended" /></a>
</p>

<p align="center"><strong>Temporary KernelSU root for supported Samsung Galaxy S25 firmware, without unlocking the bootloader or flashing a modified boot image.</strong></p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Payloads-Extended">Payloads</a> ·
  <a href="https://github.com/BuSung-dev/Root-My-Galaxy">Upstream</a> ·
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Extended/releases/latest">Latest release</a>
</p>

## About

Root My Galaxy Extended is based on [BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy). This fork maintains firmware-specific support for selected Galaxy S25 models and adds KernelSU, Auto Root, offline payload caching, Shizuku startup, recovery controls and run history.

> [!WARNING]
> Root is temporary and uses a kernel exploit. Failed attempts can reboot or panic the device. Firmware updates may stop a previously supported profile from working. Use the project only on devices you own or are authorized to test.

## Supported firmware

| Device | Firmware | Android | Kernel | Status |
| --- | --- | --- | --- | --- |
| Galaxy S25 Ultra SM-S938B | `S938BXXSBCZG3` | 16 | 6.6.98 | Supported |
| Galaxy S25 Ultra SM-S938B | `S938BXXUCZZI4` | 17 / One UI 9 beta | 6.6.127 | Supported and hardware validated |
| Galaxy S25 SM-S931B | `S931BXXUCZZI4` | 17 / One UI 9 beta | 6.6.127 | Exact-match profile available |

Profiles are matched against the device, build and kernel identity. Check the companion [payload repository](https://github.com/igorcv88/Root-My-Galaxy-Payloads-Extended) for the current feed and exact matching data.

## Root modes

**Auto Root** uses the last verified offline payload for the current firmware and can run once after a full boot.

**Manual Online** downloads the matching payload, verifies its size and SHA-256, then runs it. A successful set can become the local offline cache.

**Manual Offline** uses the verified local cache without downloading payloads.

The app keeps exploit execution separate from post-root tasks. KernelSU is activated first; optional Shizuku startup and soft reboot run afterwards.

## KernelSU on Samsung

The maintained KernelSU 3.3.0 builds include Samsung-specific handling for KDP, RKP and DEFEX.

On One UI 9 / Android 17, the root helper stages the verified `ksud` before the KernelSU handoff and uses a DEFEX-compatible execution path for late-load. KernelSU then completes its mount and module lifecycle in the correct namespace. This is also the base required by Zygisk Next and LSPosed after root.

More detail is available in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and in the payload repository's KernelSU documentation.

## Shizuku and post-root tools

Shizuku can be started after root or at boot. When KernelSU is already active, the app prefers an existing Binder or root bridge and can fall back to local Wireless ADB when needed.

Settings also includes:

- Restart Zygote
- Reload KernelSU modules
- KernelSU soft reboot
- Reboot & unroot

A full reboot clears the temporary root session. Changes made by root apps or modules can persist independently.

## History and logs

Manual and automatic runs are stored in History with their result and logs. Individual logs can be exported as `.log` files, and completed runs can be exported together as a ZIP archive.

## Payloads and integrity

Firmware profiles and binaries are maintained in [Root-My-Galaxy-Payloads-Extended](https://github.com/igorcv88/Root-My-Galaxy-Payloads-Extended). The app verifies artifact metadata before use and keeps its offline cache tied to the complete target profile.

## Building

The release workflow runs tests, Android lint and release assembly, verifies the bundled root helper against the pinned payload feed, signs the APK and publishes release artifacts.

Technical notes for contributors are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits

This fork uses or adapts work from:

- [BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)
- [BuSung-dev/Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads)
- [mitschud](https://github.com/mitschud) and [payload PR #300](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads/pull/300)
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia/tree/main/IonStack/CVE-2026-43499/exploit)
- [KernelSU](https://github.com/tiann/KernelSU)
- [HyperRamzey/Root-My-Galaxy](https://github.com/HyperRamzey/Root-My-Galaxy)
- [Shizuku](https://github.com/RikkaApps/Shizuku) and [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)
- [RipperHybrid/meta-overlayfsx](https://github.com/RipperHybrid/meta-overlayfsx)

See [LICENSE](LICENSE) and the upstream projects for their respective license and copyright terms.
