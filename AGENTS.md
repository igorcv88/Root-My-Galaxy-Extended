# AGENTS.md — Root My Galaxy project directives

This file contains durable project rules for both `igorcv88/RMGLabs` and `igorcv88/Root-My-Galaxy-Extended`.

It is intentionally conservative and should change rarely. Current experiments, commit pins, release numbers, known bugs, pending work and temporary hypotheses belong in `HANDOFF.md`, not here.

When starting work in either repository, read this file first, then read `HANDOFF.md`, then inspect the current source and payload provenance before changing anything.

## 1. Repository roles

The project family has four distinct repositories with different trust levels.

- `igorcv88/RMGLabs`: experimental application repository. New routing, orchestration, firmware ports and reliability work are validated here first.
- `igorcv88/RMGLabs-Payloads`: experimental payload/artifact repository used by RMGLabs.
- `igorcv88/Root-My-Galaxy-Extended`: production application repository.
- `igorcv88/Root-My-Galaxy-Payloads-Extended`: production payload/artifact repository.

Do not silently mix LAB and production artifacts, URLs, release workflows or assumptions.

A change being newer in LAB does not make it production-ready.

Production promotion must be deliberate, reviewable and based on hardware validation.

## 2. Source-of-truth hierarchy

For implementation decisions, use this order:

1. `AGENTS.md` for durable invariants.
2. `HANDOFF.md` for current state, known failures, roadmap and temporary decisions.
3. Current executable source code.
4. Current payload feed, exact target files and artifact provenance.
5. Current tests and release workflows.
6. Older handoffs and historical logs as evidence, not as current truth.

If an old handoff conflicts with the current code or current `HANDOFF.md`, investigate why before copying the old behavior.

Do not revive historical experimental settings merely because they appear in an older document.

## 3. Exact firmware identity is a hard safety boundary

Firmware-sensitive execution must fail closed.

A supported target must match the exact identity required by its profile. Depending on schema and target, that can include model, device, build display, build fingerprint, kernel release, kernel version information, ABI, SDK and page size.

A similar phone, nearby build number, same Android version or same kernel major/minor is not an acceptable substitute.

Never silently downgrade an exact match to a fuzzy or “close enough” match.

## 4. Firmware-specific artifacts remain isolated

Related firmware profiles may share Kotlin orchestration code, but firmware-specific native identity must remain separate.

Do not alias one firmware to another for convenience.

Keep firmware-correct:

- target offsets;
- P0 fingerprint/oracle data;
- build labels;
- exact kernel identity;
- exploit artifacts;
- exact standalone physical-P0 artifacts;
- KernelSU release identity where firmware-specific;
- feed metadata;
- SHA-256/size metadata;
- provenance.

A new firmware port should normally add a new exact target instead of reusing another target's binary identity.

## 5. Keep the layers conceptually separate

Do not conflate these layers:

- transport selection;
- exploit route selection;
- native race execution;
- bootstrap root;
- KernelSU handoff;
- KernelSU verification;
- post-root automation.

Shizuku and Local ADB are transports.

Tracefs, physical P0 discovery, physical aliasing and FOPS strategy are exploit-route choices.

A working Local ADB session does not prove the exploit works.

An exploit failure does not prove Local ADB is broken.

A KernelSU handoff failure after bootstrap root does not mean the kernel exploit should be replayed.

Logs and state transitions must make these distinctions visible.

## 6. Manual and Auto Root are separate products sharing primitives

Manual and Auto Root may share payloads, transport helpers and post-root components, but their orchestration must remain independently understandable.

Do not change Manual merely to make an Auto Root experiment easier.

Do not change Auto Root merely because Manual has a convenient UI path.

When a change intentionally affects both, state that explicitly and test both paths.

## 7. Transport selection rules

Transport must be selected and verified before entering the scheduler-sensitive exploit window.

For a shell-capable route, a pairing record alone is not proof of a usable Local ADB transport. The connection must actually open and provide the expected shell execution identity.

A Shizuku preference is not automatically a hard dependency. If the design allows equivalent shell execution through paired Local ADB, Shizuku being stopped must not unnecessarily block Local ADB.

Transport preparation can include Shizuku readiness/permission, mDNS discovery, ADB TLS/authentication and artifact staging. Finish this work before the race whenever practical.

Do not keep transport discovery or unrelated boot work running concurrently with a sensitive standalone race.

## 8. Do not force one native envelope onto every transport

The existence of a standalone fallback must never degrade a better shell route.

If a shell transport gives access to a more reliable target route, use the target's shell-capable payload and route policy.

If no usable shell exists and a validated app-local fallback exists, use the separate fallback envelope.

Never assume that a payload which is correct for app-local execution is also the correct payload for Shizuku or Local ADB.

Never assume the reverse.

## 9. Preserve validated race behavior unless the experiment is about the race

Do not casually change:

- target offsets;
- P0 tables;
- KernelSnitch behavior;
- pselect timing;
- FOPS timing;
- reclaim geometry;
- pipe geometry;
- retry spacing;
- thread scheduling;
- helper staging order.

When debugging transport, packaging, release, UI or post-root behavior, keep the native race unchanged.

When debugging the native race, change one meaningful variable at a time and preserve enough evidence to compare runs.

Avoid “cleanup” refactors in the hot path unless they are independently justified and tested.

## 10. Keep the pre-root hot path quiet

Do not add unrelated work immediately before or during exploit execution.

Avoid unnecessary:

- network requests;
- package scans;
- ART/dex optimization;
- hashing that can be completed earlier;
- UI churn;
- Binder churn;
- telemetry;
- file-system work;
- provisioning;
- background startup.

Progress UI should be driven by coarse stage transitions rather than every log line.

Anything not required for the primitive should be moved outside the critical window when possible.

## 11. Retry only when state is known to be safe

Retries are not a generic error-recovery mechanism.

Classify failure before replaying the exploit.

A dirty or uncertain P0/oracle state is terminal for that kernel boot unless a specifically validated recovery mechanism says otherwise.

Do not relaunch the kernel exploit after bootstrap root has already landed.

Once native evidence establishes root acquisition, close the exploit retry budget for that run/boot. Any later KernelSU or post-root failure is a later-stage failure.

Do not trade safety for a higher apparent success rate.

## 12. FOPS retry state must mean FOPS state

A successful physical write during P0 discovery or oracle work is not proof that a later FOPS overwrite landed.

Any `write_landed`, retry-suppression or equivalent latch used to control FOPS retries must represent the FOPS shot itself, not arbitrary earlier physical writes.

Do not let slide discovery poison the FOPS retry budget.

## 13. Bootstrap-root identity matters during handoff

The process/transport that obtains bootstrap root can matter to the helper handoff because peer credentials and SELinux context are part of the contract.

Do not arbitrarily switch principals between exploit success and bootstrap handoff.

Keep transport-aware handoff behavior explicit.

Once KernelSU is globally ready, do not assume the temporary bootstrap helper socket remains the authoritative root interface forever.

A later inability to use the bootstrap bridge is not sufficient evidence that KernelSU failed to load.

## 14. Boot identity is the Auto Root boundary

Auto Root is kernel-boot-scoped.

Use the kernel boot identifier or another equally strong boot identity for once-per-boot decisions.

A zygote restart, userspace restart or KernelSU soft reboot is not a new kernel boot and must not automatically trigger another exploit run.

Record terminal state before optional post-root restarts.

## 15. Auto Root must remain boot-safe and offline-capable

The boot-critical Auto Root path must use a bundled, integrity-verified exact payload snapshot.

Do not depend on live GitHub/network access to obtain the exploit during boot.

Network-dependent update checks belong outside the boot-critical path.

The receiver should remain lightweight. Heavy initialization belongs in the service/background execution path.

## 16. Artifact integrity and provenance are first-class

Release builds must pin the application source and payload snapshot used to build the APK.

Verify artifacts with size and SHA-256.

Exact standalone assets must also verify firmware identity/provenance.

Do not accept a stale hardcoded digest in application source as stronger truth than the release's pinned payload provenance when the artifact is intentionally rebuilt from current native source.

Tests should validate the correct contract: identity, provenance, separation and integrity.

Avoid tests that freeze an obsolete binary forever and then fail every legitimate rebuild.

## 17. KernelSU policy

Do not silently replace a validated custom KernelSU build with “latest”, another fork, or an experimental Next build.

KernelSU version/flavor changes are separate experiments and must not be bundled into unrelated exploit work.

KernelSU staging, late-load, verification and module lifecycle must remain explicit stages.

Do not duplicate lifecycle operations that the validated KernelSU path already owns.

## 18. Post-root changes must not contaminate exploit validation

Shizuku startup, Wireless Debugging cleanup, module lifecycle, soft reboot and UI post-root behavior should be tested separately from native exploit reliability whenever possible.

Do not conclude that a kernel primitive regressed from a post-root-only failure.

Do not replay the exploit because optional post-root automation failed.

## 19. LAB-to-production promotion discipline

Never bulk-copy RMGLabs into production.

Before promotion:

- identify exactly which LAB behaviors are validated;
- identify which production behaviors must remain untouched;
- compare app and payload repositories independently;
- port the smallest coherent change set;
- update production tests to the intended production architecture;
- run production preflight/release checks;
- validate on hardware again.

A production branch can legitimately lag LAB while a new route is under investigation.

## 20. Test philosophy

Tests should protect architectural invariants and safety boundaries.

Good contract tests answer questions such as:

- does firmware identity remain exact?
- can Shizuku fall back to Local ADB when intended?
- is standalone isolated from shell routing?
- are LAB and production payload repositories isolated?
- is dirty-oracle state terminal?
- can post-root failure accidentally replay the exploit?
- does the release bundle match pinned provenance?

Do not make source-string assertions more authoritative than behavior or provenance.

When a legitimate implementation changes wording or a rebuilt binary changes digest, update tests to preserve the invariant rather than preserve an obsolete incidental detail.

## 21. CI and release discipline

Prefer CI runs that produce something worth testing.

Do not burn Actions repeatedly for tiny intermediate edits when source inspection and local/static reasoning are sufficient.

Before a release, run the complete verification chain appropriate to the repository: tests, lint, feed validation, artifact integrity checks, build, signing and release publication.

When native payload bytes change, rebuild every artifact that embeds the affected native source, including dedicated standalone variants, before producing the APK.

Refresh validation receipts/provenance after the relevant artifacts change.

## 22. Documentation discipline

`AGENTS.md` is durable. Change it only when a project-wide rule or architectural invariant intentionally changes.

`HANDOFF.md` is living state. Update it whenever a meaningful change affects:

- current release/pins;
- target support;
- known-good behavior;
- unresolved failures;
- experiment status;
- roadmap;
- promotion status;
- important new evidence.

Both `AGENTS.md` and `HANDOFF.md` are mirrored in RMGLabs and Root-My-Galaxy-Extended so an agent entering either repository receives the same project map. Keep the mirrored copies synchronized.

## 23. Default behavior for future agents

Before implementing:

1. Read `AGENTS.md`.
2. Read `HANDOFF.md`.
3. Inspect current repository HEAD and relevant payload HEAD.
4. Verify the exact target/profile involved.
5. Identify which layer is actually failing.
6. Preserve unrelated validated behavior.
7. Make the smallest coherent change.
8. Update tests for the invariant being changed.
9. Update `HANDOFF.md` with the resulting state.
10. Promote to production only after explicit hardware validation and an intentional promotion decision.

When evidence is incomplete, prefer a diagnostic change or a narrowly scoped experiment over a broad architectural rewrite.
