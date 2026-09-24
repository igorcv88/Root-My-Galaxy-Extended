# DFReroot feasibility and non-invasive integration design (LAB only)

Date: 2026-09-23
Status: static feasibility analysis and architecture proposal ONLY
Scope: `igorcv88/RMGLabs`; production frozen; no runtime changes and no release requested
Upstream assessed: `polygraphene/DFReroot` at `9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77` (v2.0.1)
RMG LAB baseline at investigation: `ab3c9b42b8b79398c131459853e7b459fc2aab84` (main); most recent CI-published APK `lab-v0.1.35`
LAB payload snapshot: `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`

Read `AGENTS.md` and the root `HANDOFF.md` before using this document.

## 1. Objective and constraints

Investigate BOTH:
1. a second-stage method to regain root on later full boots after an initial RMG root;
2. whether DFReroot can contribute any truly independent first-stage root path.

Only test device available to the user: their main Galaxy S25 Ultra. Consequently this stage is **read-only/off-device only**: no `packages.xml` changes, signature injection, system-UID registration, SELinux changes, module loading, exploit execution, APK deployment or release. No production repository changes. Any future state-changing experiment requires a new explicit decision and a verified recovery plan.

Treat DFReroot as untrusted third-party source and artifacts until audited; do not silently transplant bundled APKs, `ksud` or kernel modules into RMG.

## 2. Exact RMG target and current baseline

Primary target: Samsung SM-S938B / `pa3q`, build `CP2A.260605.016.S938BXXUCZZIC`, fingerprint `samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OXMCZZIC:user/release-keys`, kernel `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`, ARM64, 4 KiB page size. These values are the RMG exact profile and must be re-confirmed on any device before conclusions apply to it.

As of `lab-v0.1.35`, Manual/Auto Root have their own policy and orchestration, exact shell-first routes, an exact standalone fallback, and a custom firmware-matched KernelSU lifecycle. Preserve them. A DFReroot experiment is a separate post-root research track, not a replacement for current FOPS/transport investigations.

Recent RMG logs demonstrate at least one successful exact ZZIC standalone native root. They do **not** show DFReroot compatibility or a stable root-recovery path. The post-root `su: connect daemon: Permission denied` warnings must not be confused with failure to acquire bootstrap root.

## 3. What the upstream code actually provides

Source reviewed:
- https://github.com/polygraphene/DFReroot/tree/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77
- upstream `README.md`, `app/src/main/jni/exp.c`, `app/src/main/jni/stage1.S`, `app/src/main/java/com/polygraphene/df/reroot/StageHop.kt`, `StageReceiver.kt`, `MainActivity.kt`, `KsudStage.kt`, `installer/.../PackagesXml.kt`, `AndroidManifest.xml`, `dirtyfrag-lkm`.

DFReroot is a **second-stage system-UID-assisted Dirty Frag chain**. It assumes temporary root from something else so that its installer can modify Android's package-signature state. The system-UID app survives subsequent boots; when invoked it attempts the separate Dirty Frag kernel path and starts its own bundled `ksud`. The root state itself does not survive a kernel reboot; the installed app provides the recovery opportunity.

The checked-in Android manifest exposes a launcher and stage receiver, **not an automatic boot receiver**. A future RMG orchestration design cannot assume DFReroot already offers hands-free boot recovery.

The project includes an `android15-6.6` kernel-module *candidate*. `exp.c` selects a bundled module using parsed Android-common kernel generation and kernel major/minor, with fallback to the first matching major/minor entry. This selection is **not equivalent to RMG exact firmware matching** and is **not evidence of exploitability or module-load compatibility** on ZZIC.

Unlike RMG's current kernel race, Dirty Frag is based on a different primitive. It is not a fix for the existing RMG FOPS miss pattern or its already-separate transport readiness and KernelSU principal/readiness concerns.

The installer edits `/data/system/packages.xml` signature history and keeps a one-time backup. Its writeback tries direct overwrite then rename-swap, with best-effort owner/mode restoration. This is an unusually consequential Android package-manager mutation, not a harmless user-space setting. The payload also modifies important runtime/vendor file page cache contents and attempts to make SELinux permissive before its late-load stage. Treat failure, rollback and security impact as project blockers.

## 4. Compatibility evidence: confirmed, compatible-in-principle, unknown

Confirmed from source and RMG exact feed:
- ZZIC identifies as kernel `6.6.127-android15-8-...`, so DFReroot's *generic* selector would have an `android15-6.6` module candidate.
- DFReroot is ARM64-capable and its UI/backend includes a network-stack handoff design.
- DFReroot requires previous root for its persistence installer. It provides **no independent first-stage root entry** by itself.

Unknown/unverified and blocking:
- whether Samsung's exact ZZIC kernel has or has not incorporated the xfrm/ESP fix for CVE-2026-43284 (mainline reference `f4c50a4034e6`);
- whether the exact loaded kernel supports the required Dirty Frag capability, independent of version-string matching;
- module ABI/symbol/CFI compatibility with the exact vendor/GKI build and its security configuration;
- ability to cross the system-server -> network-stack handoff under current Samsung One UI 9;
- vendor/runtime targets' identity and whether the upstream implementation's assumptions hold on exact ZZIC;
- interaction with RMG's existing custom KernelSU, bootstrap helper, and SELinux security guarantees;
- robust transactional recovery from a failed signature-database or page-cache modification;
- confirmed licensing and provenance of each proposed upstream-derived file/embedded binary.

No exact ZZIC source tree or verified matching stock kernel image was identified during this session. The Samsung Open Source portal offers SM-S938B source packages, but an exact matching `S938BXXUCZZIC` source package was not verified accessible. A similar model release or generic GKI release must not stand in as proof of the exact kernel's patch state.

The vendor patch is not established by a security patch date or a mainline/GKI version alone; vendor backports may diverge.

Upstream evidence reviewed as of 2026-09-23:
- README reports testing on a Galaxy S26 One UI 8.5 configuration, not exact ZZIC.
- Open issue #2: S24 Ultra passed system-UID setup and network-stack stage but failed a later Dirty Frag stage even on v2.0.1.
- Open issue #3: another S26 variant reached early stages but failed later.
- Open issue #1: an S22 tester reported abnormal terminal/other system behavior following experimentation; causation is unverified, but risk warrants investigation.
- Open issue #4: a porter reports package-database permission damage, outside the declared primary supported platform. Confirm exact scope before relying on the installer.

References:
- https://github.com/polygraphene/DFReroot/issues/1
- https://github.com/polygraphene/DFReroot/issues/2
- https://github.com/polygraphene/DFReroot/issues/3
- https://github.com/polygraphene/DFReroot/issues/4
- https://github.com/V4bel/dirtyfrag
- https://source.android.com/docs/core/architecture/kernel/gki-android15-6_6-release-builds
- https://opensource.samsung.com/

## 5. Two proposed branches; different feasibility

### A. Post-first-root recovery (potential architectural research)

Concept: keep RMG's existing validated initial root path independent. A future optional, separately authorized companion could *theoretically* provide a second-stage recovery pathway at a subsequent boot. The presence of the companion must not change RMG's normal native exploit, transport policy, boot guard or KernelSU choice.

Before designing any persistent implementation, establish exact ZZIC vulnerability and prerequisites. Then perform an isolated architecture/security review, including whether system-UID installation is acceptable at all. Investigate lower-risk designs first. With the user's only device being their primary phone, do not run a live experiment that can corrupt package state or disrupt its boot.

Do not label this "persistent root": it is a persistent recovery *app* with a repeat execution requirement and currently no upstream boot automation.

### B. Root without any previous RMG root (not supplied by DFReroot)

DFReroot's installer cannot run without earlier root. Its separate userspace entry prerequisite is a missing dependency, not an implementation detail that the current repository solves.

Investigating an independent first-stage path is an entirely separate research project with its own vulnerability/patch-state analysis, threat model and platform compatibility. Do not misrepresent DFReroot alone as a new initial-root method. Do not merge speculative userspace chains or exploit binaries into RMG.

## 6. Non-invasive LAB architecture sketch (documentation only)

Treat the research as a new optional, opt-in feature family isolated from existing Manual and Auto Root. No code changes in this phase.

Potential responsibility boundaries:

- `AlternativeRootCompatibilityReport` (pure data): exact firmware identity and independently sourced evidence for upstream vulnerability/patch status, prerequisite availability, module provenance, licensing and risk blockers. All unknowns remain unknown. **Never infer vulnerability from kernel major/minor or Android release alone.**
- `AlternativeRootResearchStatus`: NOT_ASSESSED, SOURCE_REVIEWED, SOURCE_MATCH_PENDING, PATCH_STATUS_UNKNOWN, UNSUPPORTED, REQUIRES_ISOLATED_TEST, HARDWARE_VALIDATED; no implicit promotion to a runnable state.
- `AlternativeRootExperimentPlan`: a human-reviewed document only, identifying scope, reversibility, telemetry, failure classification and abort conditions.
- No automatic fallback from RMG native failures to Dirty Frag, no same-boot cross-exploit replay, no staging of third-party kernel modules/ksud, no firmware-unverified routing.
- Existing RMG manual/auto transport selection and KernelSU ownership remain unchanged.
- Production contains no reference to the experimental mechanism until independent validation and explicit promotion authorization.

This is a **design interface proposal**, not a request to implement a runnable third-party privilege-escalation module.

## 7. Off-device verification checklist and decision gates

Gate 0 — source provenance:
- Pin upstream DFReroot source commit.
- Record third-party component provenance and check license status before any code copying.
- Independently examine bundled executable and prebuilt module origins; do not rely on upstream release labels as artifact verification.

Gate 1 — exact kernel patch assessment:
- Obtain the precise stock SM-S938B ZZIC open-source kernel package when available, or a legally obtained exact stock image for offline inspection.
- Compare the relevant xfrm/ESP implementation against the published fixed upstream behavior and record actual matching evidence.
- Reject any conclusion drawn solely from GKI family, generic model, version substring or security patch date.
- If fixed or not provably present, mark this exploit route ineligible.

Gate 2 — compatibility and consequences:
- Independently establish applicability of userspace process restrictions and module compatibility without running the exploit on the primary phone.
- Audit the package-manager persistence write/recovery path and security consequences of system-UID registration and SELinux permissive.
- Audit interaction with pinned RMG KernelSU separately.
- Define a documented recovery path compatible with OEM constraints before considering any on-device experiment.

Gate 3 — software-only testing:
- Test status parsing, exact-identity gate, provenance validation, policy decisions, no auto-execution defaults and terminal/unknown-state handling without kernel operations.
- Use synthetic fixtures for each firmware/patch-state condition.
- No native payload or live exploit compilation/integration in this stage.

Gate 4 — hardware validation:
- Defer all potentially state-changing and privilege-escalating trials while only the primary phone is available.
- A later isolated device experiment must be explicitly approved, have a tested recovery strategy and produce attributable logs before any reliability claim.

## 8. Non-invasive data needed from the user, only if exact source cannot be obtained elsewhere

The current exact RMG feed already records build/fingerprint/kernel identity. Remaining optionally useful **read-only** data are:

- `ro.build.version.security_patch` and `ro.vendor.build.security_patch`;
- `uname -a` or the full `/proc/version` (for source provenance comparison only);
- the exact matching source-release name/link from Samsung Open Source, or an official exact stock firmware package to inspect offline;
- if available from an existing approved root session, existing *read-only* exported kernel config/build provenance (do not require the user to gain root or enable risky settings solely to collect it).

Do not ask the user to run DFReroot, inject signatures, make SELinux permissive, load modules or reboot for this research phase.

## 9. Current conclusion and next task

Code-family matching: YES, there is a generic DFReroot `android15-6.6` module candidate.

Exact ZZIC exploitation compatibility: UNKNOWN.

Proven independent first-stage root path from DFReroot itself: NO (prior root is required).

Safe-to-deploy root recovery on the user's primary S25 Ultra: NOT ESTABLISHED.

What to do next: locate exact ZZIC kernel source/stock image and document patch-state evidence; complete third-party source/embedded-binary provenance and licensing audit; then decide whether *non-executable* LAB compatibility reporting warrants implementation. Do not build or release a DFReroot-integrated APK based on present evidence.


## 10. User-provided read-only ZZIC kernel evidence (2026-09-23)

Evidence received: private user-provided archive `RMG_DFReroot_20260923_202335.tar.gz`. Analyzed offline in the session; **do not upload or commit the archive or any original partition images**.

Archive contained `RELATORIO.txt`, `SHA256SUMS.txt`, `kernel/config.gz`, `kernel/config.txt`, and copies of the active B-slot `boot.img`, `vendor_boot.img`, and `init_boot.img`. The locally computed SHA-256 of all five collected kernel/config/image files matched the user archive's own manifest, which establishes internal archive consistency only, NOT independent OEM image authenticity.

Reported exact build and kernel:
- Model `SM-S938B`, device `pa3q`, Android 17 / SDK 37;
- build `CP2A.260605.016.S938BXXUCZZIC`;
- kernel `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`;
- system and vendor security patch properties both `2026-08-05`;
- reported verified boot green, bootloader locked; active slot `_b`;
- existing root was available to collect the images; no root/exploit was invoked by this research.

Kernel config obtained through `/proc/config.gz` confirms:
- `CONFIG_XFRM=y`
- `CONFIG_XFRM_ALGO=y`
- `CONFIG_XFRM_USER=y`
- `CONFIG_XFRM_ESP=y`
- `CONFIG_INET_ESP=y`
- `CONFIG_INET6_ESP=y`
- `CONFIG_MODULES=y`, `CONFIG_MODULE_SIG=y`, `CONFIG_MODULE_SIG_PROTECT=y`
- `CONFIG_KALLSYMS=y`, `CONFIG_KALLSYMS_ALL=y`
- `CONFIG_DEBUG_INFO_BTF=y`, `CONFIG_DEBUG_INFO_DWARF5=y`

`/sys/module/esp4`, `/sys/module/esp6` etc. were absent in the unprivileged report, but the authoritative collected kernel config declares the relevant XFRM/ESP features built in (`=y`). Do NOT interpret their absence from module listings as lack of functionality.

The uploaded `boot.img` is an Android boot-header-v4 image whose kernel payload begins at the standard 4096-byte-aligned offset. The kernel is a directly readable ARM64/EFI-style Image, not an opaque lz4/gzip wrapper at that offset. Embedded Linux build strings corroborate the exact ZZIC kernel release from the report. String presence in the binary is NOT proof of vulnerable or patched native instructions.

These data establish **kernel feature availability and exact-image material for offline analysis**. They do NOT establish whether Samsung incorporated, adapted, or omitted the CVE-2026-43284 fixes; they do NOT establish the safety or success of the DFReroot chain. The Android 2026-08 security-patch property alone cannot substitute for checking the vendor's actual source/compiled behavior.

The public Samsung Open Source Release Center lists SM-S938B source packages, but an exact ZZIC 17-beta source snapshot or verified matching source tag was not established through the accessible public listings. Other publicly available third-party S25 kernels have different releases and cannot serve as an exact patch-status match.

Remaining off-device gate: acquire the exact Samsung ZZIC source package (or sufficiently strong independent matched source provenance); review the relevant network-source changes and applicable Samsung backports. Where exact source is unavailable, offline binary research may narrow uncertainty but must not be presented as definitive without independently supported instruction-level equivalence. Do not execute the Dirty Frag chain or modify package signatures on the user's only primary phone. Production remains unchanged.


## 11. New upstream patch evidence and working read-only LAB auditor (2026-09-23)

Additional public upstream evidence:
- Official Linux CVE announcement dated 2026-05-08 reports the 6.6 stable fix in 6.6.138 (Linux mainline/stable numbering) and explicitly identifies the four affected IPv4/IPv6 ESP/UDP source files. Source: https://lists.openwall.net/linux-cve-announce/2026/05/08/1 .
- Android common/GKI carried a backport of the same shared-frag fix on its android15-6.6 release family with a recorded 2026-05-26 integration (Android kernel commit ea866a84e8b71f1eb3c97344b4e7aa77d1cfe4f1). Source: https://android.googlesource.com/kernel/common/+/ea866a84e8b71f1eb3c97344b4e7aa77d1cfe4f1 .
- The user's kernel string contains 6.6.127 but was built later, on 2026-09-16. Do NOT conclude that it is vulnerable just because the base version is earlier than 6.6.138. Likewise, the presence of the fix on upstream Android common does NOT prove Samsung included it in the exact ZZIC image; the exact vendor tree remains unverified.
- A cached Samsung Open Source catalog confirms that some SM-S938B kernel source packages exist but did not establish an exact published S938BXXUCZZIC / One UI 9 beta source match. Source: https://opensource.samsung.com/ .

Implementation completed **only** in LAB:
- New source: tools/dfreroot/readonly_compatibility.py .
- New synthetic off-device tests: tests/test_dfreroot_readonly_compatibility.py .
- Operator instructions: tools/dfreroot/README.md .
- The script accepts the private previously generated Termux tar.gz **locally**; it checks exact ZZIC report identity, independently computes and compares the supplied archive SHA-256 manifest, verifies critical kernel config flags, recognizes the Android boot image header and optionally reports textual patch markers from a separately supplied source tree. It streams images in bounded chunks without extracting or uploading them.
- Unverified source never becomes proof of a kernel fix. The tool deliberately reports Dirty Frag patch status UNKNOWN and executable compatibility UNVERIFIED. It contains NO exploitation, signature injection, device shell, Android system mutation, module loading or release triggering.
- Local standard-library unit tests passed (five of five): valid synthetic image/identity, mismatched image hash, wrong firmware identity, candidate-source uncertainty and unexpected archive member handling. Python compile check also passed.
- The exact published GitHub Python/test source blob hashes were cross-checked against the tested local files. No untested source reformat/refactor was made after that comparison.
- The private user diagnostic archive was processed offline with this tool: exact ZZIC report identity PASS; all five collected config/partition SHA-256 manifest checks MATCH; XFRM/ESP feature checks available (=y); Android boot header v4; Dirty Frag fix status UNKNOWN. The private archive, extracted images and complete device-specific diagnostic JSON remain OFF GitHub.
- No APK, active root path, custom KernelSU, payload source/artifact, manual/auto runtime or production repository changed.

Next decision: locate exact Samsung ZZIC vendor source and verify source-to-image provenance before interpreting the static patch indicators. In parallel, review bundled DFReroot third-party binary provenance and licensing; do not transplant upstream binaries into RMG. Since the only available phone is the user's primary device, defer state-changing or persistent device experiments. If exact provenance cannot be established, maintain UNKNOWN and focus on non-destructive RMG reliability issues already identified in HANDOFF sections 18–21.

CI status: the isolated synthetic-only workflow `DFReroot offline compatibility tests` was added under `.github/workflows/dfreroot-offline-audit.yml` and its first run completed successfully (https://github.com/igorcv88/RMGLabs/actions/runs/35896719152). Its checkout, Python setup, syntax check and synthetic unit-test steps all succeeded. This was NOT a root-device test or an Android APK build.


## 12. Revised evidence strategy: exact device images plus complete beta OTA chain

2026-09-23 user clarification: The same user has the entire OTA transition chain available: One UI 8.5 -> One UI 9 Beta 1 -> Beta 2 -> Beta 3 (ZZIC) on their primary S25 Ultra. Do not treat an unreleased beta source drop from Samsung Open Source as an ordinary prerequisite or force the user to search for it.

Correction of terminology: firmware packages and the installed device contain **compiled binaries**, not the original source repository, comments or full compile-time provenance. A valid assessment can instead analyze exact firmware binaries plus OTA binary differences and appropriate independently sourced GKI references. This can provide stronger *build-specific* evidence than a mismatched public Samsung source package; recovery of original C source is not automatic, and absence of an easily found binary pattern cannot certify a vulnerability either way.

The active ZZIC boot/vendor_boot/init_boot images and `/proc/config.gz` were **already collected and privately verified** in the earlier diagnostic archive. Do NOT ask the user to collect them again, rerun `su` or read live partitions to prepare this phase.

Evidence pipeline (read-only, OFF device):
1. Inventory **local OTA container format**, firmware revision metadata, direct boot/vendor_boot candidates and `payload.bin` markers without extracting, applying or uploading full images. New LAB tool: `tools/dfreroot/ota_inventory.py`, run with Python 3.10+ on individual locally saved OTA/firmware files. It accepts ZIP, Samsung AP TAR(.md5), raw `payload.bin`, and reports unknown binaries as unknown.
2. Confirm chronological source/target build identities for 8.5 -> Beta 1 -> Beta 2 -> Beta 3. Never infer order merely from filenames; read package metadata when present and request release metadata otherwise. Distinguish full OTA (self-contained target) from incremental/delta OTA (requires **the exact source partition image** to reconstruct). Confirm OTA `payload.bin` is readable; do not assume Samsung delivers A/B payload.bin rather than an OEM-specific binary format.
3. Identify only required boot-related partitions and manifests. Extracting/directly comparing those images off-device is preferable to transferring the entire device partitions and userdata. For the final transition Beta 2 -> Beta 3, an exact Beta 2 base image may be needed for applying a binary delta. If its package is itself incremental, work backward along the available OTA chain, starting from an authenticated 8.5 full base.
4. Use independently verified current ZZIC boot.img already held privately as **reference hash and version check** for any reconstructed Beta 3 kernel target. A binary mismatch means the reconstructed chain and live installed build might differ and must not be silently equated. Treat OTA signatures, vendor build identity and hashes separately; user archive SHA-256 agreement only proves internal consistency.
5. If relevant prior boot images are recovered, compare exact kernel payloads and function-level changes offline. Recover symbols/BTF only when present; build strings and `CONFIG_DEBUG_INFO_BTF=y` alone do not guarantee that stripped partition images contain matching standalone BTF or `vmlinux`.
6. Compare observable code behavior against the documented Linux/Android GKI Dirty Frag fix using independent control references. A 2026 GKI backport and the installed kernel's 6.6.127 version number do NOT determine the Samsung beta patch result. Accept a patch-state conclusion only if binary/source provenance and actual compiled behavior support it. If insufficient, retain UNKNOWN and do not deploy a runnable alternative exploit.

New implementation and CI:
- `tools/dfreroot/ota_inventory.py` performs metadata inventory without extraction, flashing, privileged commands or raw-image publication.
- `tests/test_dfreroot_ota_inventory.py` exercises synthetic OTA ZIP, Samsung AP TAR, standalone payload and unknown binary cases.
- `.github/workflows/dfreroot-offline-audit.yml` compiles and runs both the existing compatibility auditor and the new OTA inventory test suite; the workflow completed successfully: https://github.com/igorcv88/RMGLabs/actions/runs/35898608691 .
- All changes are LAB-only software/research; production, all RMG execution paths, native payload bytes, kernel modules and live device were untouched.

The practical next input from the user is the **small text/JSON inventory** of the four local OTA packages (plus any full 8.5 AP package), not an unsolicited multi-GB firmware upload. After identifying the real container type, decide which exact boot-related file(s) or images would actually help; never request credentials, userdata or unrestricted full-partition dumps. Do not deploy a DFReroot stage while the vulnerability/patch status remains unverified and the only available hardware is the user's primary phone.


## 13. Independent ZZIC boot/BTF verification and direct ZZI4 comparison route

Following handoff from the parallel Beta 1/Beta 2 reverse-engineering instance, previous research is confirmed to have addressed primarily CVE-2026-43499, **not** CVE-2026-43284 (Dirty Frag). Thus no earlier Dirty Frag verdict exists to inherit for CZG3, ZZHL or ZZI4.

The prior ZZI4-to-ZZIC comparison report was recovered from the user's prior library artifact `Markdown(6).md colado`. It recorded **the raw ZZI4 Image SHA-256** `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`, size `39,115,264` bytes, and **the extracted ZZI4 BTF SHA-256** `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`. These are historical analysis claims; the ZZI4 binary was **not** mounted in the current research container and has not been independently reconfirmed in this session.

The existing user-provided private exact ZZIC diagnostic tar.gz **was independently re-opened and rehashed** in the current offline environment:
- Android boot image: `101,122,048` bytes, SHA-256 `a49046afa23e357b2c843edb45f6b31ece558e0e749d619ee9bbf4aafaec7f06`, boot header v4.
- Extracted raw ARM64 kernel Image: `39,115,264` bytes, SHA-256 `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`. This reproduces the earlier report's kernel size and digest independently.
- An embedded, structurally valid BTF record starts at raw Image offset `0x18aca6c`; size `6,425,607` bytes; SHA-256 `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`. Its type metadata was fully parsed locally (147,790 BTF type records). The BTF enumerates function metadata for `esp_input`, `esp6_input`, `__ip_append_data`, `__ip6_append_data`, and type metadata for `sk_buff` and `skb_shared_info`. These names establish metadata availability for offline research, **not addresses or a patch-state determination**.
- The independently computed ZZIC BTF digest matches the earlier **reported** ZZI4 BTF digest. Consequently there is no reason to redo the generic BTF/layout research first. Identical BTF is **not** identical instruction bytes and cannot determine whether CVE-2026-43284's compiled control flow is patched. The earlier claim of ZZI4/ZZIC BTF identity remains conditional on actual ZZI4 image integrity until that file becomes available here.

New LAB-only deliverables:
- `tools/dfreroot/kernel_pair_inventory.py`: accepts an Android boot v3/v4 or raw ARM64 Image; checks an optional exact Image SHA-256, extracts the Image in memory, identifies structurally plausible embedded BTF metadata, and computes whole-image/BTF equality and overall changed-byte counts. Explicitly leaves Dirty Frag patch status `UNKNOWN` and exploit compatibility `UNVERIFIED`.
- `tests/test_dfreroot_kernel_pair_inventory.py`: five synthetic off-device unit tests.
- `.github/workflows/dfreroot-offline-audit.yml`: compile and run all three relevant offline-audit synthetic test suites. Confirmed successful run https://github.com/igorcv88/RMGLabs/actions/runs/35900442286 .

Public reference verification: Android common's upstream shared-frag patch `ea866a84e8b71f1eb3c97344b4e7aa77d1cfe4f1` is indexed with a 2026-05-26 commit date. This upstream presence is informative, not proof that the exact Samsung beta image contains the fix. Reference: https://android.googlesource.com/kernel/common/+/ea866a84e8b71f1eb3c97344b4e7aa77d1cfe4f1 .

**Revised highest-value missing input**: the previous instance's *already extracted* exact ZZI4 raw `Image` (expected 39,115,264 bytes and historical SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`) or its exact `boot.img`. The private current ZZIC boot image is already locally available and validated. Compare these exact two before asking for the entire multi-gigabyte OTA chain; inventory OTA packages only if the prior Image cannot be recovered. Never infer vulnerability from the patch reference, version number, BTF equivalence or broad image change percentages.

The primary phone remains entirely untouched, the existing RMG root paths and kernel payloads are not modified, and production stays frozen. This is a **static research tool**, not a runnable DFReroot integration or a Dirty Frag patch verdict.


## 14. Exact ZZIC vs ZZI4 compiled Dirty Frag paths: read-only symbol recovery and disassembly (2026-09-23)

The user ran a root-authorized **read-only** collection of `/proc/kallsyms` on exact installed ZZIC and privately supplied `RMG_DFR_KALLSYMS_ZZIC.tar.gz`. The tar's SHA-256 manifest and reported identity were confirmed. The file contained 395,241 name/type entries but **all live addresses were masked as zero**, even in that `su` invocation; this alone cannot tell whether `kptr_restrict`, task capabilities, or another platform rule imposed the masking. No request to change kernel restrictions or repeat the live collection is needed.

**Static fallback succeeded.** Using the *already supplied* exact ZZI4 and ZZIC raw ARM64 `Image` files (SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef` and `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`), recovered their original embedded compressed kallsyms name/token/offset tables without relying on live addresses. Both contained 115,127 kernel-base symbols and matching complete name/type lists; recovered ZZIC names **exactly matched every first-base name/type entry** from the previously masked live file (0 of 115,127 mismatched). This is an independent sanity check on the offline symbol decoding and relative function boundaries.

Exact function-boundary comparisons, separately calculated from each image's own embedded offsets, establish that **all four reference-patch target function bodies are byte-for-byte unchanged from ZZI4 to ZZIC**:

| Kernel function | Length in both images | ZZI4→ZZIC changed bytes | SHA-256 of body in both |
|---|---:|---:|---|
| `esp_input` | 868 | 0 | `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95` |
| `esp6_input` | 868 | 0 | `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1` |
| `__ip_append_data` | 3,812 | 0 | `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01` |
| `__ip6_append_data` | 3,908 | 0 | `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621` |

The binary code was further disassembled as non-executable ARM64 ELF **analysis wrappers**, not compiled payloads. The exact kernel's BTF maps `skb_shared_info.flags` to byte offset zero and `frag_list` to byte offset eight. In both ESP input functions, the path which skips `skb_cow_data` checks the fragment-list pointer but does **not** additionally test the shared-fragment flag as required by the upstream reference fix. The IPv4 and IPv6 append-data functions also return from their `skb_splice_from_iter` success path to shared bookkeeping without the additional reference shared-fragment marking. These are positive observations about actual compiled instruction/control-flow behavior, rather than just absence of a kernel-version update or equality of BTF.

**Revised static finding:** the four-path mitigation in the publicly documented CVE-2026-43284 fix is **absent** from the exact compiled ZZI4 and ZZIC function bodies; their examined control flows correspond to the pre-reference-fix design. Source references:
- https://github.com/torvalds/linux/commit/f4c50a4034e62ab75f1d5cdd191dd5f9c77fdff4
- https://github.com/V4bel/dirtyfrag

This supersedes the prior status of `UNKNOWN` **only for whether these four reference-code-path changes are present**. It is **not** proof that an on-device DFReroot exploit will work: alternative out-of-function mitigation, network-stack permission constraints, system-server handoff, proprietary vendor differences, executable page-cache targets, module integrity/ABI/CFI, kernel trust requirements, SELinux and user-app recovery/rollback are independent unknowns. Treat *operational DFReroot compatibility* as UNVERIFIED, with the only device being the user's primary phone. Do not promote a first-stage root claim: upstream DFReroot's installer still requires prior root.

No raw private kernel images, full `kallsyms`, live addresses, or user archives were added to GitHub. No root code, payload bytes, production repository, release or device state was altered by this review.

**Next actionable work:** audit DFReroot's signed component/provenance/licensing, system-server/network-stack assumptions and KernelSU interoperability strictly from source and available offline firmware material, documenting independent compatibility gates. No need for additional device-root commands at this point.


## 14. Exact ZZIC kallsyms capture + embedded-kallsyms static Dirty Frag verdict

The user returned a private read-only exact-boot symbol archive collected on the installed ZZIC kernel. The archive identity matches SM-S938B / pa3q / `CP2A.260605.016.S938BXXUCZZIC` / kernel `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`. The full `/proc/kallsyms` capture contains 395,241 entries and its SHA-256 was independently rechecked. Kernel address exposure is masked: all captured addresses, including `_text`, `esp_input`, `esp6_input`, `__ip_append_data`, and `__ip6_append_data`, are zero. This does not block static analysis because the exact raw boot Images are already available.

The zero-address limitation was bypassed **offline only** by parsing the embedded kallsyms table directly from the exact ZZI4 and ZZIC raw Images and validating its decoded base-kernel symbol-name sequence against the live ZZIC `/proc/kallsyms` ordering. Validation facts:
- first loadable-module boundary in live kallsyms: 115,127 base-kernel entries;
- embedded base-kernel symbol count recovered from each exact Image: 115,127;
- decoded embedded symbol names vs the first 115,127 live kallsyms names: 0 mismatches;
- exact raw Image SHA-256: ZZI4 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`; ZZIC `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`.

This provides deterministic file offsets/function boundaries for the four CVE-2026-43284 reference paths without relying on masked live addresses.

Exact compiled-function comparison, ZZI4 -> ZZIC:
- `esp_input`: 868 bytes; byte-identical; SHA-256 `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95`.
- `esp6_input`: 868 bytes; byte-identical; SHA-256 `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1`.
- `__ip_append_data`: 3,812 bytes; byte-identical; SHA-256 `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01`.
- `__ip6_append_data`: 3,908 bytes; byte-identical; SHA-256 `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621`.

Manual AArch64 disassembly review against the published Dirty Frag merged fix shows the four exact Samsung functions retain the **pre-fix control-flow behavior**:
- ESP4/ESP6 skip-COW branch checks the frag-list condition but lacks the added shared-frag condition present in the merged fix.
- IPv4/IPv6 append-data paths return from the relevant splice/copy path without the merged fix's shared-frag flag marking.
- This aligns with the published reference diff in `V4bel/dirtyfrag`, which adds `!skb_has_shared_frag(skb)` to ESP4/ESP6 and adds `SKBFL_SHARED_FRAG` marking to IPv4/IPv6 append-data.

Static verdict for **exact ZZI4 and exact installed ZZIC**: the four-path merged upstream mitigation for CVE-2026-43284 is absent in the compiled code. Confidence is HIGH for patch-state because all four exact target functions were recovered from validated build-specific Images and independently matched by embedded-kallsyms/live-name ordering.

Important limitation: **absence of the reference mitigation is not itself a live exploit-success demonstration**. Runtime reachability, Samsung-specific surrounding changes, SELinux/process constraints, module compatibility, DFReroot's system-UID hop and late-load stage remain separate questions. Therefore:
- `dirtyfrag_reference_patch_state = ABSENT` for exact ZZI4 and ZZIC;
- `vulnerable_code_structure = PRESENT` for those four reference paths;
- `DFReroot end-to-end exploitability = UNVERIFIED`;
- do not run the exploit or persistent installer on the user's primary phone yet.

The current evidence materially clears the prior "patch-status UNKNOWN" gate. Next work should focus on **non-invasive DFReroot chain compatibility/provenance**: inspect the upstream system_server -> network_stack handoff assumptions on Android 17/One UI 9, module/ksud provenance and ABI expectations, and persistence/rollback design. Keep RMG's existing KernelSU and native root paths untouched. No production changes or executable Dirty Frag integration are authorized by this static verdict.


## 14. Actual masked kallsyms archive and static ESP evidence for ZZI4/ZZIC

Real user evidence (2026-09-23): a **private** `RMG_DFR_KALLSYMS_ZZIC.tar.gz` archive was collected from an already-authorized `su` session on the exact `SM-S938B`/`pa3q` `CP2A.260605.016.S938BXXUCZZIC` build. It contains `symbol_report.json` and compressed `kallsyms.txt.gz`. Identity/kernel release match prior diagnostic evidence. The captured list contains 395,241 entries, including 115,127 core kernel symbols followed by dynamically loaded module symbols. All seven initially selected symbols, and every inspected procfs symbol, have zero addresses: the runtime address column is **masked**. We did not determine the exact sysctl/LSM reason and did not change any setting to unmask it. Captured decompressed text SHA-256: `9e6596034af08186c6d244b4c174a1f06395e720d886eba7021664632c265f00`.

**Important research breakthrough:** recover kernel symbols from each exact raw `Image` offline instead of using the masked live addresses. Both independently validated OTA images contain the compressed embedded kallsyms tables (the kernel config includes `CONFIG_KALLSYMS_BASE_RELATIVE=y`). An independent read-only parser found the same 115,127 core symbol names in exactly the same order and mapped them using the embedded relative-offset array. All 115,127 names of the current image matched the names from the private runtime capture. Some symbols point into non-file-backed `.bss`, so their relative offsets may legitimately exceed the size of the image; only selected file-backed function windows were used.

Exact verified raw Image SHA-256:
- ZZI4 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`.
- ZZIC `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`.

Next-distinct-symbol windows (these may include trailing padding/aliases; do not misrepresent them as formal function sizes):

| Symbol | Image offset (both builds) | Window length (bytes) | SHA-256 (both builds) | Byte differences |
|---|---:|---:|---|---:|
| `esp_input` | `0x101803c` | 868 | `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95` | 0 |
| `esp6_input` | `0x10acffc` | 868 | `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1` | 0 |
| `__ip_append_data` | `0xf9963c` | 3812 | `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01` | 0 |
| `__ip6_append_data` | `0x105a538` | 3908 | `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621` | 0 |

This establishes byte-for-byte identity of all four selected compiled function windows from ZZI4 to ZZIC, despite the broader Image changing by 3.31%. It DOES NOT by itself prove the CVE is exploitable in either build.

**Separate independent AArch64 instruction/BTF review, targeted to the upstream mitigation:**
- The public merged shared-frag patch adds `!skb_has_shared_frag(skb)` alongside `!skb_has_frag_list(skb)` in both ESP receive functions. The upstream inline helper would consult `skb_shinfo(skb)->flags & SKBFL_SHARED_FRAG` when `skb` is nonlinear. Source evidence: https://github.com/V4bel/dirtyfrag/blob/main/assets/write-up.md and upstream `include/linux/skbuff.h`.
- The BTF embedded in the *matching Samsung Images* places `sk_buff.data_len` at byte `0x74`, `sk_buff.end` at `0xcc`, `sk_buff.head` at `0xd0`; `skb_shared_info.flags` at 0, `nr_frags` at 2, and `frag_list` at 8. This independently grounds the disassembly's field interpretation.
- In `esp_input`, the non-linear input path loads `skb_shared_info.frag_list`, branches directly to reading `nr_frags` when it is null, and **does not test `skb_shared_info.flags`** along that path. `esp6_input` has the equivalent direct frag_list -> nr_frags branch and the same absence of a shared-frag flags check.
- **Bounded result:** strong image-specific evidence that the *specific extra ESP branch condition introduced by the public upstream shared-frag fix is absent* in both ZZI4 and ZZIC, with the compiled fast-path behavior corresponding to the earlier unpatched branch. This is substantially more specific than the previous UNKNOWN based only on kernel numbers or BTF equality.
- Do **not** promote this to proven working CVE-2026-43284 / successful DFReroot root. We have not yet independently completed instruction-level assessment of the two UDP producers, alternative vendor mitigations, reachable user-space preconditions, third-party system-UID installation, SELinux transition, kernel-module load and alignment with RMG's pinned custom KernelSU.

LAB-only tool now available: `tools/dfreroot/offline_kallsyms_compare.py`. It consumes **only already-owned private** compressed kallsyms and raw Image files; reads and validates all matching core names and the embedded token/offset tables, computes separate ESP/UDP function hashes and exact equality, reports disassembly review as a separate necessary step, and makes no device or network calls. A five-case synthetic test suite in `tests/test_dfreroot_offline_kallsyms_compare.py` is isolated in the research CI. Verified CI execution: https://github.com/igorcv88/RMGLabs/actions/runs/35910198245 (successful). The real-device binary comparison was independently performed offline with equivalent parsing, rather than claiming the newly committed CLI itself was run on this private data.

**Decision:** stop requesting additional device symbols, extraction or changes to `kptr_restrict`; the needed static mapping exists within the already extracted Image. Complete static review of two UDP producers and possible alternate Samsung guards. Treat live exploitation/persistent DFReroot integration as a separate high-risk project, especially because the owner has only their primary phone. Preserve LAB native root paths and freeze production.


## 15. User-provided real ZZIC network_stack runtime preflight (read-only)

The user supplied a **private read-only on-device runtime prerequisite archive** `RMG_DFR_RUNTIME_PREREQS_ZZIC.tar.gz` after correcting an Android multi-user package-list permission failure by querying package metadata through their already-authorized root with explicit `--user 0`. This archive is not committed to the repository. Its `package_error.txt` and `dumpsys_error.txt` are empty; all listed files were readable. The latest archive does not contain a fresh build-identity file, so the ZZIC attribution comes from the user's contemporaneous exact-device session and the earlier verified current-boot archive, not an independent identity proof within *this* archive.

Observed process/package state from the user's provided archive:
- Live process `com.android.networkstack.process` exists. Its real/effective/saved/file-system UIDs and GIDs are all **1073**, and the SELinux context is **`u:r:network_stack:s0`**.
- Effective capabilities (`CapEff`) **`0x800003c00`** include capability bit **12 (`CAP_NET_ADMIN`)**. Other set bits in this captured mask are 10, 11, 13 and 35. This establishes the required *process capability* but does not prove the kernel accepts the exact XFRM operations or that Android's SEPolicy allows a particular file or library operation.
- `Seccomp: 2` (filter mode), `Seccomp_filters: 1`; `NoNewPrivs: 0` at capture time. Do not interpret these alone as guaranteed unrestricted execution/dlopen.
- `com.google.android.networkstack` and `com.google.android.networkstack.tethering` have package UID **1073**; Samsung's `com.samsung.android.networkstack` is a **distinct overlay package UID 10292**, not the network-stack process UID. Package dump links Google's networkstack to `android.uid.networkstack/1073` and a Samsung networkstack overlay targets Google's package.
- The previous `SecurityException` occurred only because ordinary Termux UID `u0a492` attempted a package query resolved to Android user 150; the corrected root+user0 package query and root package dump were captured successfully.

Bounded DFReroot runtime-prerequisite conclusion:
- Upstream `StageHop.kt` hardcodes process `com.android.networkstack.process` and UID 1073. Both values are **actually observed on this phone**; `network_stack` also holds the required effective `CAP_NET_ADMIN`. These three prerequisites **PASS**.
- The mere presence of the process and its capabilities does NOT validate the privileged `system_server -> network_stack` reflection/method call, stage-two receiver dispatch, `libexp.so` loading, netlink-XFRM SELinux permissions or Dirty Frag runtime reachability. Each remains `UNVERIFIED`.
- Source-only audit of pinned upstream DFReroot v2.0.1 (`9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77`) confirms its installer requires privileged persistent editing of `/data/system/packages.xml` signing history to install an `android.uid.system` app; the Android manifest requests `android:sharedUserId="android.uid.system"` and system hosting; a bundled `ksud` is staged to `/data/system/dfreroot-ksud`; its module is designed to disable SELinux enforcing. These stages are **not** validated by this read-only capture and conflict with the safety/KernelSU provenance constraints for the owner's primary phone. Sources: https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/README.md ; https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/java/com/polygraphene/df/reroot/StageHop.kt ; https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/AndroidManifest.xml .

**Next gate:** no further live root or exploit commands are required. Conduct a source-only audit of the StageHop assumptions, installer rollback, binary/module provenance, and alignment with the custom RMG KernelSU. For exact target proof of dispatch/reflection/SELinux restrictions, first seek off-device or isolated hardware evidence; do not inject signatures, execute Dirty Frag, load the LKM, make SELinux permissive, or modify production on the only primary S25 Ultra. Preserve the earlier bounded static result: exact compiled ESP reference guard is absent in both ZZI4 and ZZIC; independently validated full UDP shared-frag semantics and end-to-end exploitability are **not established**.

## 16. Pinned DFReroot ELF and lifecycle audit — second source-only pass (2026-09-23)

Scope: upstream `polygraphene/DFReroot@9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77`, exact target `pa3q-S938BXXUCZZIC`. The following is entirely off-device and adds to §15 and HANDOFF §33. No native exploit execution, signing-history write, module load, SELinux change, new capture, RMG root/payload modification, production change, or release was performed.

### 16.1 Generic Android15-6.6 module: independently inspected ELF

Decoded the **actual pinned upstream GitHub blob** `app/src/main/jni/dirtyfrag-android15-6.6.ko` (Git blob `f35f3741dbcbd4d2f25672802f2675cbc958ca55`), rather than extrapolating from `dirtyfrag-lkm/dirtyfrag.c`. Independent decoded-byte SHA-256 `6658df7da8b2e90a9d15dd551cbdc7a2405d707fdd13ee8892a1d7c635d884c0`; size 5,656 bytes. The standalone SHA-256 routine was checked against the standard SHA-256("abc") vector before use. The image is ELF64/AArch64 relocatable and contains:

- `.modinfo`: `license=GPL`, `name=dirtyfrag`, and **`vermagic=6.6.127-4k-g46a034eca005-dirty SMP preempt mod_unload modversions aarch64`**.
- `__versions` section **exists but its size is zero**, so there are no embedded nonempty per-symbol CRC records in that section.
- No `.BTF` section in the shipped stripped module. Undefined ELF imports include `memset`, `sprint_symbol`, `_printk`, and `__stack_chk_fail`.

The target's independently recorded exact kernel release is `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`. **The module's declared build identity is not the exact Samsung target**, regardless of their shared 6.6.127 major/minor/patch. This is an exact-target provenance FAIL, not proof that the stock loader necessarily rejects it.

Important Linux 6.6 nuance: upstream `kernel/module/main.c` calls `same_magic(modmagic, vermagic, info->index.vers)`. Upstream `kernel/module/version.c` skips the kernel-release prefix if it receives a nonzero version-section index. Because the inspected module has a *present but empty* `__versions` section, an unmodified loader may skip that prefix without actually validating CRC records. Consequently **do not claim certain loader rejection from the differing `vermagic` alone**. Equally, a possible loader acceptance would not establish symbol ABI, actual executable compatibility, kernel module verification policy, or safe operation. The exact Samsung loader/config/export/ABI evidence is unavailable in this source-only pass.

Primary code references:
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/jni/dirtyfrag-android15-6.6.ko
- https://raw.githubusercontent.com/torvalds/linux/v6.6/kernel/module/main.c
- https://raw.githubusercontent.com/torvalds/linux/v6.6/kernel/module/version.c
- https://docs.kernel.org/kbuild/modules.html

### 16.2 Binary and KernelSU provenance

`dirtyfrag-lkm/build.sh` uses container tags (`ghcr.io/ylarod/ddk-min:$version`) without digest pinning. The top-level `build.sh` compiles the application and packages the **already tracked** `.ko` files; it does not independently rebuild and attest those kernel modules. `app/src/main/assets/ksud` is an opaque tracked upstream binary (~6.0 MB; Git blob `f5f95a84c37f729f7a86b754f362f34d2f0b97e9`). The upstream README identifies a KernelSU fork/branch but no verified source-to-byte build receipt or RMG-specific compatibility evidence for that pinned asset was established.

`KsudStage.stageBytes()` writes the asset verbatim to `/data/system/dfreroot-ksud`, then chmods it, **without a digest or compatibility check**. `MainActivity.onCreate()` initiates that write on background startup, *before* any user-requested Dirty Frag operation. If staging fails, it tries `libksud.so` from the installed KernelSU manager without verifying that fallback's provenance. Therefore even launching the upstream application is not a read-only experiment. Preserve the RMG pinned custom KernelSU and defer all upstream daemon staging.

References:
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/dirtyfrag-lkm/build.sh
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/build.sh
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/java/com/polygraphene/df/reroot/KsudStage.kt

### 16.3 Binder, process lifecycle, and installer safety

`MainActivity` dynamically registers its `EVIL_ACTION` receiver with `Context.RECEIVER_EXPORTED` and no caller-identity or permission check before accepting an incoming `CONTROLLER` Binder. A cross-app broadcast/controller substitution is a **source-visible potential attack surface**, not a tested exploit. Its `controller` field is not cleared before a subsequent `runDfAll`, and `awaitController` can therefore reuse a previously received binder rather than proving that a new StageHop dispatch completed. These points are independent of whether Android17's hidden `scheduleReceiver` overload actually works. The observed process name, UID and CAP_NET_ADMIN prerequisite gates remain PASS; dispatch, SELinux/XFRM, native loading and Binder authenticity remain UNVERIFIED.

`PackagesXml.writeBack` first attempts `File.writeBytes` directly on the live package-manager database, which can truncate/write incrementally. It creates a backup only if absent; an existing backup may be stale. On fallback it renames a temporary file, but it does not provide all-or-nothing recovery for an interrupted direct overwrite, nor does it verify the resulting file with a full read-back after committing. `applyPerms` logs chmod/chown failures; `restorecon` return values inside `writeBack` are ignored. At the CLI level, `InjectMain` logs nonzero restorecon return codes without turning them into a mandatory failure. Parsing a patched XML in memory before write is not a transactional restore guarantee, and cannot establish package-manager acceptance on the exact device. Treat persistent signature-history injection as OUT OF SCOPE for this primary-device phase.

References:
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/java/com/polygraphene/df/reroot/MainActivity.kt
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/app/src/main/java/com/polygraphene/df/reroot/StageHop.kt
- https://github.com/polygraphene/DFReroot/blob/9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77/installer/src/main/java/com/polygraphene/df/installer/PackagesXml.kt

### 16.4 Bounded status and remaining work

- PASS (independently measured previously on exact device): network_stack process name, UID 1073, effective CAP_NET_ADMIN.
- REFERENCE_FIX_ABSENT (prior exact-image analysis): compiled ESP4/ESP6 shared-frag guard absent; four relevant function windows equal between independently verified ZZI4 and ZZIC images. Prior detailed report also records the UDP return-path interpretation, but this **second pass did not independently reproduce UDP disassembly**, because the private raw Images are not in this pass's working runtime. Reuse the already verified private files *offline* when available; do not request another on-device collection or infer UDP semantics solely from hashes.
- FAIL (strict RMG provenance rule): upstream generic module does not demonstrate exact ZZIC build identity; shipped daemon is unverified for RMG custom KernelSU. This is an integration-gate verdict, not an observed runtime loader result.
- UNKNOWN: Samsung-specific alternative mitigations outside four reference paths, module verifier/config and actual loading, `selinux_state` binary layout, XFRM SELinux/seccomp reachability, exact Android17 reflected receiver signature, trusted Binder return path, verified late-load behavior and device-safe rollback.
- Licensing: upstream repository has no root LICENSE and its GitHub API declares no repo license at the inspected state. A module's `MODULE_LICENSE("GPL")` does **not** establish licensing terms for the whole DFReroot codebase or the copied third-party components. Do not copy/bundle uncertain third-party code or binaries into RMG without verifying rights and provenance.

**Decision for this research phase:** retain documentation-only LAB status and exact-target fail-closed gates; no app/payload/production modifications, upstream installer, live Dirty Frag, daemon staging, module loading or device collection. The outstanding independent UDP and Samsung-specific checks need pre-existing private offline firmware material or separately verified source artifacts, not new operations on the primary phone.


## 17. Independent UDP producer call-site validation and fail-closed integration gates (2026-09-23)

Scope: offline-only continuation on **the previously verified exact** Samsung ZZI4 and installed ZZIC raw ARM64 kernel Images and previously collected private masked live kallsyms. No additional device collection, exploit, APK, module, sysfs modification or production change.

### 17.1 Reproduced reference UDP producer call-site analysis

A new standard-library LAB research utility, `tools/dfreroot/offline_udp_callsite_evidence.py`, requires **exact known raw Image SHA-256** for both builds and reuses the existing `offline_kallsyms_compare.py` core-symbol order validation against the user's private runtime capture. It resolves `skb_splice_from_iter` and the two UDP producer next-distinct-symbol windows, decodes direct AArch64 BL destinations, and extracts the narrow instruction sequence from the unique matching direct BL to the first post-call unconditional branch. It deliberately leaves the automated patch verdict `MANUAL_REVIEW_REQUIRED`.

This new independent real-data check succeeded **privately offline** on the two existing exact Images:
- `__ip_append_data` (IPv4): the relevant BL resolves to the exact `skb_splice_from_iter` symbol. Along the nonerror direct continuation, the compiled code stores the return value to a local, tests the negative-error bit, copies the returned byte count, updates accounting, and branches into the common loop. The inline `skb_shinfo(skb)->flags |= SKBFL_SHARED_FRAG` store added by the upstream Dirty Frag reference patch is **absent** between that BL and the common-loop branch.
- `__ip6_append_data` (IPv6): an equivalent uniquely resolved BL, negative-error test, result/accounting updates and common-loop branch; the added shared-frag flag marking is likewise **absent** on that direct continuation.
- Each entire UDP function next-distinct-symbol window and each post-splice call-site sequence is byte-identical between ZZI4 and ZZIC. The earlier independent ESP disassembly established the corresponding absence of the reference extra `skb_has_shared_frag` skip-COW guard in both exact Images.

This resolves the prior evidence gap around the **two specific compiled UDP source-diff insertion points**. The four *particular public merged patch additions* are not seen at their equivalent compiled sites in the exact two firmware Images. **It does not establish that Samsung lacks any alternative protection, that all execution paths are reachable, or that Dirty Frag/DFReroot works end-to-end.** No attempt was made to alter kernel state or perform the attack.

Independent primary reference: https://github.com/V4bel/dirtyfrag/blob/main/assets/write-up.md, public patch `f4c50a4034e62ab75f1d5cdd191dd5f9c77fdff4`. Linux v6.6 baseline `net/ipv4/ip_output.c` and `net/core/skbuff.c` were used as non-Samsung *control references only*; they must not be mislabeled exact OEM source. The full private instruction JSON `udp_callsite_evidence.json` and proprietary Images **remain out of GitHub**.

Tests: eight synthetic only tests confirm immediate BL/B decoding (positive and backward), rejection of ambiguous/absent symbols and mismatched exact kernel SHA before offsets are consumed. Local software-only synthetic tests and private exact-input execution passed independently; the LAB isolated CI also succeeded at https://github.com/igorcv88/RMGLabs/actions/runs/35914075774 . CI does **not** contain proprietary firmware or reproduce the private input conclusions.

### 17.2 Exact ZZIC integration gates — source-only, no runnable chain

| Prerequisite / stage | Current status | Narrow interpretation |
|---|---|---|
| Exact ZZIC identity and static upstream four-site comparison | VERIFIED OFFLINE | Exact kernel Images validated and compiled reference sites reviewed; alternate OEM mitigation/reachability remain UNKNOWN. |
| `com.android.networkstack.process`, UID 1073, effective CAP_NET_ADMIN | VERIFIED BY PRIOR READ-ONLY CAPTURE | Necessary prerequisites only; not evidence of XFRM operations allowed by SELinux. |
| Privileged `system_server` reflection and `IApplicationThread.scheduleReceiver` dispatch | UNVERIFIED | Pinned upstream picks method by name/12-parameter count; actual Samsung Android17 signatures and successful delivery unknown. |
| Binder controller authenticity and retry lifecycle | SOURCE-VISIBLE DESIGN RISK | Pinned upstream uses an exported dynamic receiver without explicit sender authentication and can retain a stale controller reference. |
| `NETLINK_XFRM` SELinux/seccomp, library/target paths, and actual exploit reachability | UNVERIFIED | Effective CAP_NET_ADMIN alone does not decide these. |
| Persistent system-UID installer and rollback | **BLOCKED FOR PRIMARY DEVICE** | Nontransactional direct `packages.xml` modification and one-time backup have no validated exact-device safe rollback. |
| Generic pinned `dirtyfrag-android15-6.6.ko` | **FAIL: EXACT-BUILD PROVENANCE** | Its declared `vermagic` differs from exact ZZIC; this is not a claim that a loader must reject a module with an empty but present `__versions`. Build/ABI/export/SELinux assumptions unverified. |
| Upstream packaged `ksud` / RMG pinned custom KernelSU | **FAIL: PROVENANCE / SEPARATION** | No supported verified substitution or reproduced exact-build compatibility. Upstream `MainActivity` automatically attempts daemon staging on launch, so launching it is not a read-only smoke test. |
| Generic upstream code redistribution/licensing | NEEDS REVIEW | No proven whole-project license in pinned tree; LKM's GPL tag is insufficient licensing evidence for other components. |
| Device-safe autonomous root recovery | NOT IMPLEMENTED/NOT VERIFIED | Upstream app persistence and a manual button do not equal boot-safe unattended recovery. |

**Fail-closed decision:** do not run/install/bundle upstream DFReroot on the user's sole daily phone or modify RMG's existing validated Manual/Auto Root, custom KernelSU or production repos. Subsequent phases require an audited independent persistence/rollback design, verified binary provenance and isolated test hardware or a suitable emulation environment. Further off-device source and kernel-binary comparison can continue without requesting device intervention.


### 17.3. Narrow helper-chain check on already validated exact Images

A further private static check recovered the same embedded core-symbol mapping for `skb_splice_from_iter`, `skb_append_pagefrags` and `__skb_zcopy_downgrade_managed` in **both** exact images. Their respective next-distinct-symbol windows are byte-identical between ZZI4 and ZZIC: 656 bytes (SHA-256 `4a25bc2705c762e8458b3f0ab5949f7ea599e39f0192ac6246bd359c162e1f7c`), 344 bytes (`f8af40bb5096790633fa120350108f18aedc30b145ae3a604152cb2788e97879`) and 168 bytes (`1d0c9cc6839e9673e5dca901b734666052b76d7635c777e9641d1a4cbe4c9550`). No proprietary binary was committed.

An exact private ARM64 disassembly review showed that `skb_splice_from_iter` directly calls `skb_append_pagefrags`. The latter reads `skb_shared_info.flags` and updates `nr_frags` and fragment descriptors but does not directly OR the shared-frag marking into `flags`; the conditional `__skb_zcopy_downgrade_managed` helper performs an AND mask (removing a flag bit), not the added marking in the merged public UDP producer fix. This narrows one potential alternative implementation inside the immediate helper chain, but it does NOT exclude all alternative Samsung mitigations, dynamic constraints, other branches or exploitable-path requirements. Remain strictly **offline** and maintain `DFReroot full-chain status = UNVERIFIED`.
