# Minimal end-to-end test plan

The first hardware validation should minimize reboot count and isolate regressions.

## Test 1 — Manual Online, no userspace restart

- `Soft reboot after root`: off
- `Restart Shizuku after root`: default on
- Use the exact CZG3 profile and the normal configured launch uptime.
- Expected: exploit succeeds, KernelSU verifies, the successful History record is checkpointed, pairing is requested if needed, then Shizuku starts through local Wireless ADB. No zygote restart occurs.

If pairing is required, complete it once, then repeat Manual Online/Offline without reboot only if the exploit state is known safe. The pairing itself does not require another exploit attempt.

## Test 2 — Auto Root after full reboot

After Test 1 has produced a valid offline cache and persistent local ADB key:

- Enable Auto Root.
- Keep soft reboot off for this first boot validation.
- Full reboot once.
- Expected: Auto Root remains standalone/offline, KernelSU restores, Wireless Debugging is enabled through the persisted `WRITE_SECURE_SETTINGS` grant, local ADB reconnects, and Shizuku starts without Tasker.

## Test 3 — Userspace restart

Only after Test 2 passes:

- Enable `Soft reboot after root`.
- Full reboot once.
- Expected: root and History success are committed first; Shizuku starts; then `post-fs-data`, `services`, `boot-completed`, and zygote restart run. Zygisk/LSPosed modules should be active when userspace returns.

Do not vary launch uptime, exploit attempts, FOPS timing, and post-root options in the same validation run. A failure after the `KernelSU control channel verified` line is a post-root automation failure, not an exploit failure.
