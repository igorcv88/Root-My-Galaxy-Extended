# Known limitations of post-root automation

- Wireless ADB pairing is a one-time interactive setup. Before the application has a saved local ADB key, the first successful root starts the pairing foreground service; the user must enable Wireless Debugging and enter the six-digit pairing code.
- `WRITE_SECURE_SETTINGS` can only be self-granted after KernelSU shell root and the first local ADB connection are available. After that one-time grant, later boots can re-enable Wireless Debugging automatically.
- The post-root pipeline is best-effort by design. Failure to enable Wireless ADB, connect, start Shizuku, or restart zygote does not change a previously verified root result to failure.
- No hardware validation of this combined branch is recorded yet. The pairing/ADB primitives and KernelSU userspace lifecycle were ported from the device-tested fork, but the integrated Root My Galaxy flow still requires an end-to-end test before release.
