# Architecture notes

This document keeps implementation details out of the main README while preserving the current production design.

## Root flow

Manual Online, Manual Offline and Auto Root resolve an exact target profile before execution. The exploit obtains bootstrap UID 0, then the target root helper performs the KernelSU handoff. A run is considered rooted only after KernelSU reaches global readiness.

Auto Root is boot-scoped and uses the verified last-known-good offline payload. A userspace or KernelSU soft reboot keeps the same kernel boot ID, so it does not start another exploit run.

## Exact target policy

Firmware-specific exploit choices live in the payload feed. The app consumes the target's route policy instead of hardcoding one policy for every firmware.

CZG3 and ZZI4 remain separate profiles. Their offsets, KernelSU artifacts and exploit behavior can differ without changing the other target.

## One UI 9, DEFEX and KernelSU

The Android 17 / One UI 9 path uses a Samsung-specific KernelSU 3.3.0 build with KDP, RKP and DEFEX handling.

For the current ZZI4 path, a verified `ksud` is staged before the late-load security transition. The helper uses a DEFEX-compatible bind execution path over `/system/bin/logcat`, then starts `ksud late-load --allow-shell`.

The late-load implementation serializes callers, moves into PID1's mount namespace before owning systemless/module mounts, installs the daemon under `/data/adb`, and publishes readiness after the blocking mount stages finish. Duplicate callers in the same kernel boot do not replay the lifecycle.

Zygisk Next and LSPosed depend on a working KernelSU userspace/module lifecycle. The DEFEX handling is therefore part of making those post-root components usable on the supported One UI 9 firmware.

## ZZI4 exploit route

ZZI4 uses Tracefs for KASLR slide discovery when the selected transport can read it. Kernel data writes use the physical-load alias on the validated S938B profile. The production payload also keeps the physical P0 oracle available and uses bounded FOPS retries with shared completion state.

Detailed target constants and build-specific behavior belong in the payload repository.

## Payload cache

Manual Online verifies the exact exploit, KernelSU and root-helper metadata before execution. A successful set can be saved as the last-known-good cache.

Manual Offline and Auto Root use that verified cache. Route policy is part of the cached identity, so policy-only feed changes can invalidate an older cache even when binary hashes are unchanged.

## Post-root lifecycle

Post-root automation starts after KernelSU readiness. It may start Shizuku or request a native KernelSU soft reboot.

The soft-reboot keeper calls `/data/adb/ksud soft-reboot`. KernelSU owns the userspace restart and module lifecycle. The keeper is keyed to the kernel boot ID to prevent repeated execution during the same boot.

Shizuku startup is Binder-first and root-first once KernelSU exists. Local Wireless ADB remains a compatibility fallback.

## Recovery

The app exposes Restart Zygote, Reload KernelSU modules, KernelSU soft reboot, and Reboot & unroot. These controls require active KernelSU and are intended for userspace/module recovery rather than exploit recovery.
