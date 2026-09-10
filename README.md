# Root My Galaxy Extended

Root My Galaxy Extended is a community fork of [BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy), the original Samsung KernelSU installer. It combines that app with maintained Galaxy S25 Ultra payloads, Samsung-specific KernelSU integration, boot automation, Shizuku startup and recovery controls. The companion [Payloads Extended](https://github.com/igorcv88/Root-My-Galaxy-Payloads-Extended) repository contains the firmware profiles and supporting binaries.

The original BuSung project is the upstream reference. Later contributions from other forks are credited below; this fork does not claim their work as its own.

## What this fork includes

- Auto Root, online and offline modes, and a verified local payload cache.
- Samsung-specific KernelSU 3.3.0 integration and maintained firmware profiles.
- Optional Shizuku startup at boot and after root, with local Wireless ADB support.
- An optional KernelSU soft reboot after root.
- Recovery controls: Restart Zygote, Reload KernelSU modules, KernelSU soft reboot, and Reboot & unroot.
- Settings for payload source, boot timing and post-root behavior.
- History for manual and automatic runs, individual log export and bulk ZIP export.

These are the features assembled in this fork, including adapted upstream work; they are not a claim that every feature originated here or is absent from every other fork.

## Compatibility and limitations

The maintained scope is Galaxy S25 Ultra **SM-S938B**. “Extended” describes the project's additions, not support for every Galaxy device.

| Firmware | Android | Kernel series | Repository status |
| --- | --- | --- | --- |
| S938BXXSBCZG3 | 16 | 6.6.98 | Maintained earlier profile |
| S938BXXUCZZI4 | 17 / One UI 9 beta | 6.6.127 | Profile with reported on-device validation |

Compatibility depends on the exact firmware and kernel build, not just the model or Android version. A system update can make a previously compatible device unsupported. Reported validation is not a success-rate measurement or a guarantee for another device.

The integration documented here uses **KernelSU 3.3.0**. KernelSU 3.4 compatibility is not established by this README.

> [!WARNING]
> This software uses a kernel exploit and provides temporary root. A failed run can freeze or reboot the phone and lose unsaved work. Keep backups and use it only on devices you own or are authorized to test. Recovery controls require a working Android/root session; they are not an unbrick tool.

A full device reboot clears the temporary root session. A soft reboot restarts Android userspace while keeping the kernel running. Neither operation should be described as undoing every change made by a root app or module.

## Behavior to understand

Offline mode uses a previously verified local set and does not silently download missing files. Auto Root is limited to once per full kernel boot; a soft reboot does not count as a new kernel boot. Manual and automatic boot-timing preferences are separate.

Shizuku startup and the soft reboot are optional post-root features. Root acquisition does not require Wireless ADB or Shizuku. A failure in post-root automation is separate from the result of the root session.

The recovery controls interrupt apps or module state and require active KernelSU. Reboot & unroot disables Auto Root before performing a full reboot, so automatic restoration does not immediately reverse that choice.

History records the profile, result and logs for each run. Review exported logs for personal or device information before sharing them publicly.

## Project maintenance

The app checks payload size and SHA-256 against the companion repository's metadata. These checks detect mismatched files; they do not establish that a payload or device configuration is safe.

The release workflow includes unit tests, Android lint, release assembly, signing and a check that the bundled helper matches the pinned payload metadata. Build checks do not replace testing on the exact firmware.

## Credits and license

- [BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) and [Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads): original app, payload structure and Samsung integration.
- [mitschud](https://github.com/mitschud): later Galaxy S25 firmware work adapted by this fork.
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia): published exploit lineage.
- [KernelSU](https://github.com/tiann/KernelSU): root framework and userspace lifecycle.
- [HyperRamzey/Root-My-Galaxy](https://github.com/HyperRamzey/Root-My-Galaxy): local ADB/Shizuku automation and post-root coordination references.
- [Meta-Overlayfsx-ViPER-safe](https://github.com/igorcv88/Meta-Overlayfsx-ViPER-safe), based on [RipperHybrid/meta-overlayfsx](https://github.com/RipperHybrid/meta-overlayfsx): module mount integration references.
- [Shizuku](https://github.com/RikkaApps/Shizuku) and the thedjchi/Shizuku fork: API/provider and startup references.
- Android Open Source Project, BoringSSL and Bouncy Castle: Wireless ADB pairing and cryptographic implementation references.

Distributed under [LICENSE](LICENSE). Derived components retain their own licenses and copyright notices.
