# Application rollout order

This branch depends on the payload repository publishing the v0266 root-helper metadata before an APK release is generated.

1. Merge `Root-My-Galaxy-Payloads-S938B` branch `feat/czg3-tracefs-auto-lateload`.
2. Run **Atualizar Payloads → Exploit** on payload `main` and verify that the v3 CZG3 entry contains `rootHelper` metadata pointing at the v0266 artifact directory.
3. Merge this application branch.
4. Run **Gerar APK Release**. The workflow resolves and pins the payload repository `main` commit, verifies the helper's size and SHA-256, embeds it as `libcve43499root.so`, runs unit tests/lint/assembleRelease, and only then publishes the APK.

The runtime also verifies that the APK-bundled helper matches `rootHelper` metadata before running a v0266 online/offline payload. A mismatched application build fails closed rather than executing a payload with an incompatible helper.
