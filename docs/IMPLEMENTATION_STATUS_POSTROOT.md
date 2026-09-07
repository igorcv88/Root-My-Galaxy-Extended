# Implementation status

Implemented:

- persistent local ADB key and Wireless Debugging pairing;
- mDNS discovery and local TLS ADB connection;
- one-time post-root `WRITE_SECURE_SETTINGS` self-grant;
- automatic Shizuku startup after verified root (enabled by default);
- KernelSU lifecycle + zygote restart as the soft-reboot path;
- root-success checkpoint before post-root work;
- v3 `rootHelper` metadata parsing/serialization and runtime fail-closed validation;
- release workflow pinning and embedding of the matching payload root helper;
- backward compatibility with v0265 feeds lacking `rootHelper` metadata;
- unit test for optional root-helper manifest roundtrip.

Pending validation:

- Gradle unit tests/lint/assembleRelease;
- payload NDK build and v0266 artifact publication;
- real-device Manual sentinel;
- real-device Auto Root sentinel;
- real-device post-root Shizuku and optional zygote restart.
