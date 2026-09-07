# Tasker-free boot flow

After one-time Wireless ADB pairing and the one-time post-root `WRITE_SECURE_SETTINGS` grant, a normal full boot needs no Tasker profile:

1. Android sends `BOOT_COMPLETED`.
2. Root My Galaxy waits for the configured launch uptime.
3. Auto Root restores KernelSU using the offline standalone path.
4. Root My Galaxy enables Wireless Debugging itself.
5. It discovers the new dynamic local ADB port over mDNS and authenticates with its saved key.
6. It starts Shizuku using the shell-owned official `start.sh`.
7. If enabled, it applies the KernelSU userspace lifecycle and restarts zygote.

The ADB/Shizuku portion starts only after root is verified and is not part of exploit success criteria.
