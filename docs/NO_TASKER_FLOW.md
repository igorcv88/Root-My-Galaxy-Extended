# Tasker-free boot flow

After one-time Wireless ADB pairing and the one-time `WRITE_SECURE_SETTINGS` grant, a normal full boot needs no Tasker profile:

1. Android sends `BOOT_COMPLETED`.
2. Root My Galaxy immediately probes the Shizuku Binder. If it is absent, RMG enables Wireless Debugging as needed, discovers the new dynamic local ADB port over mDNS, and authenticates with its saved RMG ADB key.
3. RMG starts the installed `thedjchi/Shizuku` fork through the same command used by its `AdbStarter`: `libshizuku.so --apk=<sourceDir>`.
4. RMG waits for the Shizuku Binder, closes its local ADB session, and restores Wireless Debugging to the state it had before the bootstrap.
5. Independently, Auto Root waits for its configured kernel-uptime floor and restores KernelSU using the offline standalone path.
6. After root is verified, the post-root path treats an already-running Shizuku Binder as the preferred bridge. Root/helper or local-ADB startup remains recovery-only if the early boot bootstrap failed.
7. If enabled, the detached module keeper performs the one-time module/zygote refresh without replaying KernelSU lifecycle stages.

Shizuku startup is therefore an early boot service and is not gated on exploit success. Auto Root and Shizuku share `BOOT_COMPLETED` as a trigger but have independent lifecycles.
