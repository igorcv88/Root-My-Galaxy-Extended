<p align="center">
  <img src=".github/assets/root-my-galaxy-banner.svg" alt="Root My Galaxy" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/igorcv88/Root-My-Galaxy-S938B?label=release" /></a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/igorcv88/Root-My-Galaxy-S938B/total" /></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-16-3DDC84?logo=android&amp;logoColor=white" />
  <img alt="KernelSU" src="https://img.shields.io/badge/KernelSU-3.3.0-2f81f7" />
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/actions/workflows/release.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/Root-My-Galaxy-S938B/release.yml?branch=main&amp;label=build" /></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/igorcv88/Root-My-Galaxy-S938B" /></a>
</p>

<p align="center">
  <strong>Temporary KernelSU root for the maintained Samsung Galaxy S25 Ultra firmware without unlocking the bootloader or flashing a modified boot image.</strong>
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Payloads-S938B">Payloads</a>
  ·
  <a href="https://github.com/BuSung-dev/Root-My-Galaxy">Upstream app</a>
  ·
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest">Latest release</a>
</p>

> [!WARNING]
> This software uses a kernel exploit. A failed run can panic/reboot the device. Use it only on a device you own or are explicitly authorized to test.

## Maintained target

| | Current profile |
| --- | --- |
| Device | Galaxy S25 Ultra `SM-S938B` (`pa3q`) |
| Firmware | `S938BXXSBCZG3` |
| Build display | `BP4A.251205.006.S938BXXSBCZG3` |
| Android | Android 16 / API 36 |
| Kernel | `6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k` |
| ABI / page size | `arm64-v8a` / 4K |

Firmware or kernel updates can invalidate this exact profile.

## What this fork adds

Compared with the base Root My Galaxy app, this fork currently adds or changes:

- exact CZG3 identity matching and SHA-256-verified payload/helper coupling;
- KernelSU 3.3.0 / 32601;
- explicit **Manual Online** and **Manual Offline** modes;
- a last-known-good offline payload set published only after a successful verified Manual Online root;
- **Auto Root** that is always Offline + Standalone and never depends on Shizuku or network access to acquire root;
- a foreground boot gate plus a fresh `:autoroot_exec` process for the exploit handoff;
- configurable total boot-uptime launch gate (default 120 s on CZG3);
- v0266 root-helper auto-late-load support with app-side late-load fallback;
- persistent local Wireless ADB pairing and automatic Shizuku restart after root;
- a KernelSU userspace lifecycle soft reboot route (`post-fs-data → services → boot-completed → zygote restart`);
- installation History with captured logs and per-run export.

The exploit race itself remains deliberately small: the app does not reintroduce the former External Observer, pselect gate, SIGRETURN interception, syscall wrappers or race telemetry into the critical path.

## Root path

The current intended flow is:

```text
Manual Online / Manual Offline / Auto Root
                  ↓
          verified v0266 set
                  ↓
       CVE-2026-43499 exploit
                  ↓
          bootstrap UID 0
                  ↓
 root helper auto-late-loads KernelSU
                  ↓
     KernelSU control verification
                  ↓
       root result is checkpointed
                  ↓
  optional post-root userspace actions
```

The v0266 helper can late-load KernelSU immediately after root lands, avoiding a second client round trip. If that path is not ready, the app retains the explicit client `--late-load` fallback.

## Manual Online and Offline

**Manual Online** resolves the exact support feed, downloads the exploit and KernelSU payload from a commit-pinned revision, verifies size/SHA-256 and verifies that the APK-bundled root helper matches the feed's `rootHelper` metadata.

Only after exploit success and KernelSU verification does the app publish that exact set as the new last-known-good cache.

**Manual Offline** uses only that cache. It performs no hidden network fallback. Auto Root uses the same verified offline set.

Caches created before v0266 root-helper metadata are intentionally rejected once the new helper is shipped; run Manual Online once to establish a helper-bound cache.

## Auto Root

Auto Root is intentionally different from ordinary manual execution in only the ways needed for boot reliability:

```text
BOOT_COMPLETED
      ↓
foreground gate service
      ↓
wait for configured total boot uptime
      ↓
bind fresh :autoroot_exec process
      ↓
Offline + Standalone exploit
      ↓
KernelSU verification
      ↓
History result
      ↓
post-root automation
```

It never chooses Shizuku automatically, never downloads a payload and runs at most once per full kernel boot. A soft/userspace reboot keeps the same kernel boot and does not schedule another exploit attempt.

## Launch uptime

For exact CZG3, **Diagnostic Launch Time** is now only a historical UI name. It performs no diagnostics. It is a minimum total boot uptime measured with `SystemClock.elapsedRealtime()`.

Available values: `0 / 30 / 60 / 90 / 120 / 180 / 300 / 600` seconds. Default: **120 s**.

## Wireless ADB and Shizuku without Tasker

Root acquisition never depends on Wireless ADB or Shizuku. They are post-root features only.

The app now contains a local ADB client and a persistent ADB key. One-time setup uses Android Wireless Debugging pairing (TLS + SPAKE2). On Android 13+, the app first requests notification access from a visible activity because the six-digit pairing code is entered through the pairing foreground-service notification.

After the first successful pairing/root bootstrap, the app uses KernelSU shell root to grant itself `WRITE_SECURE_SETTINGS`. Future boots can then:

1. enable `adb_wifi_enabled` locally;
2. discover the dynamic Wireless Debugging port through mDNS;
3. authenticate to `127.0.0.1` with the saved key;
4. verify `su -c id` through KernelSU `--allow-shell`;
5. execute Shizuku's official `start.sh`;
6. wait for the Shizuku Binder to become available.

If pairing is missing during Auto Root, root still succeeds; the post-root step records that pairing is required instead of turning the exploit result into failure.

## Soft reboot after root

The previous bootstrap-socket soft-reboot handoff has been replaced. When enabled, and only after root is already verified, the app uses the authenticated local ADB/KernelSU shell to run the userspace lifecycle proven by the HyperRamzey fork:

```text
ksud post-fs-data
      ↓
ksud services
      ↓
ksud boot-completed
      ↓
restart zygote / zygote64
```

The root result is checkpointed before this phase. A Wireless ADB, Shizuku or soft-reboot failure is therefore logged as a post-root failure and does not retroactively mark a verified root as failed.

## v0266 payload/helper binding

The v3 feed can declare a `rootHelper` artifact alongside `exploit` and `kernelsu`. The release workflow resolves the payload repository `main` to an immutable commit, verifies the helper size/SHA-256, embeds exactly that helper into the APK, and records provenance in the release build.

Offline cache IDs include the exploit, KernelSU and helper digests. Legacy caches without helper metadata are fail-closed and must be refreshed by Manual Online.

## History and logs

History records manual and automatic runs, selected profile, result and captured runtime log. The critical exploit path does not continuously fsync History during the race; terminal state is persisted outside the sensitive race window.

Open an individual run to export its log. Bulk selected/all-log export is being restored separately and is not required for root execution.

## Building

The release workflow runs unit tests, Android lint and release assembly before signing/publishing the APK. The workflow also verifies that the helper embedded in the APK matches the current commit-pinned payload feed.

## Credits and provenance

This fork combines work from several projects and contributors. Credit is explicit because substantial parts of the implementation are derived or adapted rather than newly invented here.

- **[BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)** — upstream application architecture, UI, installer flow, Shizuku integration, History and the original Root My Galaxy project.
- **[BuSung-dev/Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads)** — upstream payload/feed architecture and Samsung exploit integration used by the companion payload repository.
- **[HyperRamzey/Root-My-Galaxy](https://github.com/HyperRamzey/Root-My-Galaxy)** — source for the persistent local Wireless ADB key/pairing stack, mDNS discovery, local ADB client, post-root Shizuku automation and the KernelSU userspace lifecycle/zygote-restart approach adapted in this fork.
- **[mitschud](https://github.com/mitschud)** / **[BuSung payload PR #300](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads/pull/300)** — hardware-tested Tracefs KASLR route and the root-helper auto-late-load design (`--allow-shell`, DEFEX-safe bind execution, daemon-stay and late-load markers) adapted to CZG3 v0266.
- **[NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia/tree/main/IonStack/CVE-2026-43499/exploit)** — published CVE-2026-43499 exploit source on which the payload lineage is based.
- **[KernelSU](https://github.com/tiann/KernelSU)** by tiann and contributors — kernel root framework, manager and `ksud` lifecycle used after bootstrap root.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** by RikkaApps and contributors — Shizuku API/provider and official `start.sh` integration.
- **Android Open Source Project / BoringSSL** — protocol reference for ADB authentication, Wireless Debugging TLS pairing and the SPAKE2/pairing-auth behavior mirrored by the local pairing implementation.
- **[Bouncy Castle](https://www.bouncycastle.org/)** — cryptographic provider used by the local ADB key/certificate and pairing implementation.

Each upstream project remains subject to its own license and copyright notices. This repository is distributed under the license in [LICENSE](LICENSE).
