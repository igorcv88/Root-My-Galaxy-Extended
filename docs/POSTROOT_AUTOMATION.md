# Post-root automation

The root acquisition path and the userspace automation path are deliberately separated.

## Root acquisition

Auto Root remains offline and standalone. It does not require Wireless ADB or Shizuku to execute the exploit or to restore KernelSU.

After the exploit succeeds, the root helper may auto-late-load KernelSU from the persisted `/data/local/tmp/ksud-s25u-kdp`. If that is not ready, the existing client `--late-load` path remains the fallback. A verified root result is saved before any userspace restart is attempted.

## Wireless ADB bootstrap

The application owns a persistent ADB key and can pair directly with the device's Wireless Debugging service. Pairing is one-time.

On the first successful root where no local pairing is stored, Root My Galaxy starts the pairing foreground service. The user enables Wireless Debugging, chooses **Pair device with pairing code**, and enters the six-digit code in the notification.

After a paired local ADB session is available and KernelSU shell root works, Root My Galaxy grants itself `android.permission.WRITE_SECURE_SETTINGS` using root. That grant persists, allowing later boots to set `adb_wifi_enabled=1` directly and reconnect to the local adbd without Tasker or a PC.

## Shizuku

`Restart Shizuku after root` is enabled by default. After KernelSU is verified, the application connects to local adbd and executes the Shizuku `start.sh` as the ADB shell user, then waits for the Shizuku Binder.

Shizuku is intentionally started before the optional zygote restart. Its shell-owned server process is independent of the application's zygote process, removing the old reconnect-after-restart dependency.

## Optional userspace restart

When `Soft reboot after root` is enabled, Root My Galaxy runs the KernelSU lifecycle in the same order used by the device-tested fork:

1. `ksud post-fs-data`
2. `ksud services`
3. `ksud boot-completed`
4. restart zygote/zygote64

Failures in Wireless ADB, Shizuku, or this lifecycle are post-root failures. They do not retroactively mark a successfully verified root as failed.
