# PR summary

This branch keeps Auto Root offline/standalone through KernelSU verification, then moves Wireless ADB, Shizuku startup, and optional KernelSU userspace restart into a separate post-root phase.

It ports the persistent ADB key/TLS pairing/mDNS/local-adbd implementation from the tested HyperRamzey fork, adds one-time `WRITE_SECURE_SETTINGS` self-grant after root, starts Shizuku through its official shell script, and replaces the old bootstrap-daemon soft-reboot handoff with the tested KernelSU lifecycle stages followed by zygote restart.

The payload v3 schema gains optional `rootHelper` metadata. Release builds pin the payload repository commit and embed the exact helper matching that metadata; runtime online/offline loading fails closed on helper mismatch.

No full Android build or real-device validation of this integrated branch has been run yet.
