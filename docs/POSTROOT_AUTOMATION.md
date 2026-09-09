# Post-root automation

The root-acquisition path and the userspace-automation path are deliberately separated.

## Root acquisition

Manual Online, Manual Offline and Auto Root acquire bootstrap root through the target payload and then restore KernelSU. Auto Root remains offline and standalone: it does not require Wireless ADB or Shizuku to execute the exploit or restore KernelSU.

The root helper may auto-late-load KernelSU from the persisted target `ksud`. If global readiness is not reached, the app retains the serialized client `--late-load` fallback. A successful root result is checkpointed before any optional userspace restart.

The post-root layer never stages or replaces `ksud`, never calls `late-load`, and never replays the bootstrap handoff. Those invariants are important on the S938B Samsung KDP/RKP/DEFEX builds, where daemon staging and mount-namespace ordering are firmware-sensitive.

## Shizuku

When Shizuku is already running, its Binder is preferred and Wireless ADB is not touched.

After a full cold boot without active ephemeral KernelSU, the optional Shizuku boot coordinator retains the existing event-driven fallback: wait for another starter/Binder, then use paired local Wireless ADB only when needed.

After a KernelSU soft/userspace reboot, the kernel `boot_id` and KernelSU root remain active. On the resulting framework `BOOT_COMPLETED`, the coordinator now tries an already-authorized root bridge first (RMG root helper, then direct KernelSU `su`). If that starts Shizuku, the service skips Wi-Fi, mDNS and Wireless ADB entirely. If no non-interactive root bridge is available, the legacy ADB fallback remains unchanged.

## Optional soft reboot

`Soft reboot after root` is a post-root operation only. Once KernelSU control and PID1 mount readiness have been verified, the app launches one detached, boot-scoped keeper through an already-working root transport.

The keeper does not wait for Meta-Overlayfsx or ViPER mounts before requesting the restart. Those mounts are part of the userspace lifecycle that the soft reboot itself must recreate; gating on them before the restart can deadlock the operation when modules are disabled or not currently mounted.

The keeper also does not restart zygote directly. It delegates the transition to the installed KernelSU userspace binary:

```text
verified KernelSU
      ↓
boot_id / single-owner guard
      ↓
/data/adb/ksud soft-reboot
      ↓
KernelSU daemonizes in init context
      ↓
reset sys.boot_completed
      ↓
stop
      ↓
post-fs-data / metamodule / mounts
      ↓
start
      ↓
services
      ↓
wait for boot completed
      ↓
boot-completed
```

This preserves KernelSU's native lifecycle ordering, including metamodules and ordinary modules, without re-entering the exploit or late-load paths.

The keeper runtime log is `/data/local/tmp/rmg-postroot-keeper.log`. The accepted-request marker is `/data/local/tmp/.rmg-soft-reboot-accepted`, keyed to the current kernel `boot_id` so duplicate framework boot events cannot request another soft reboot for the same kernel boot.
