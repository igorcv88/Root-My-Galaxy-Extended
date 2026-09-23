# HANDOFF.md — Root My Galaxy current project state

Last updated: 2026-09-23

This is the living handoff for the Root My Galaxy project family. It consolidates the previous large Auto Root handoffs, subsequent repository work, the One UI 9 Beta 3 port, the 2026-09-22 transport/payload regression investigation and the current LAB release state.

Update this file after meaningful implementation or validation work. Keep its copy synchronized between `igorcv88/RMGLabs` and `igorcv88/Root-My-Galaxy-Extended`.

Read `AGENTS.md` first. `AGENTS.md` contains durable rules; this file contains current facts, temporary decisions, unresolved questions and roadmap.

## 1. Repository map

Application repositories:

- LAB: `igorcv88/RMGLabs`
- Production: `igorcv88/Root-My-Galaxy-Extended`

Payload repositories:

- LAB: `igorcv88/RMGLabs-Payloads`
- Production: `igorcv88/Root-My-Galaxy-Payloads-Extended`

Reference implementation used during Auto Root investigation:

- `kuuky29/UniRoot`
- key reference commit historically analyzed: `59b2b1d81383ccbb45ea6c792e100383777c0daf`

Historical documents absorbed into this handoff include:

- `UNIROOT_AUTOROOT_PORT_HANDOFF.md`
- `AUTOROOT_0.1.16_IMMEDIATE_HANDOFF.md`
- `RMGLabs_AutoRoot_CloudCode_Handoff_2026-09-18.md`
- earlier Auto Root / timing / Shizuku / FOPS investigation notes

Do not treat the old pins in those files as current pins. Their architectural evidence is preserved here where still relevant.

## 2. Current LAB release

Current integrated LAB release:

- Tag: `lab-v0.1.34`
- Release name: `RMG Labs 0.1.34-lab`
- Published: 2026-09-22
- Application source used by the release: `fc0cfdd81020d09be495432d0dca377107aabf3e`
- Payload snapshot used by the release: `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`

Current RMGLabs `main` also contains the later release-trigger bookkeeping commit. Do not confuse the trigger commit with the pinned application source that was checked out for the release.

The release workflow completed successfully through:

- pinned app checkout;
- pinned private payload checkout;
- payload provenance verification;
- bundled payload preparation;
- LAB isolation checks;
- unit tests;
- lint;
- release APK assembly;
- unsigned APK payload integrity checks;
- signing;
- signed APK verification;
- release publication.

## 3. Current LAB target state

Primary validation family:

- device: Galaxy S25 Ultra SM-S938B
- device codename: `pa3q`
- Android 17 / One UI 9
- custom KernelSU 3.3.0 family, control version 32601

Exact One UI 9 LAB profiles currently relevant:

### ZZI4

Profile:

- `pa3q-S938BXXUCZZI4`

Current normal feed route policy:

- `slideRoute = auto`
- `attempts = 24`
- `attemptTimeoutSec = 120`
- `p0AttemptTimeoutSec = 45`
- `p0OffsetCache = true`
- `prefersShellTransport = true`

Current normal shell-capable exploit:

- size: `104128`
- SHA-256: `a7dc5fe9967de726ade5f4acaa4ba03812682faf700714b30f47acd324c85fdc`

Current dedicated physical-P0 fallback asset:

- build identity: `pa3q-S938BXXUCZZI4-app-physical-p0-oracle`
- size: `129872`
- SHA-256: `bbeaeed141d78460b0c51b8543e63389011ef9fc6409c8ae00937791e75bc833`

### ZZIC / One UI 9 Beta 3 exact port

Profile:

- `pa3q-S938BXXUCZZIC`

Exact device identity encoded in the LAB profile includes:

- model `SM-S938B`
- device `pa3q`
- build display `CP2A.260605.016.S938BXXUCZZIC`
- fingerprint `samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OXMCZZIC:user/release-keys`
- kernel release `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`
- SDK 37
- ABI arm64-v8a
- 4096-byte page size

ZZIC has its own target offsets and exact physical-P0 fingerprint table. It is not an alias of ZZI4.

Current normal feed route policy:

- `slideRoute = auto`
- `attempts = 24`
- `attemptTimeoutSec = 120`
- `p0AttemptTimeoutSec = 45`
- `p0OffsetCache = true`
- `prefersShellTransport = true`

Current normal shell-capable exploit:

- size: `104128`
- SHA-256: `0000e2fcf5ab3e93f6344d9242b72c39eec7eb54e7d4484ea9b9bdaee1e8fc5c`

Current dedicated physical-P0 fallback asset:

- build identity: `pa3q-S938BXXUCZZIC-app-physical-p0-oracle`
- size: `129872`
- SHA-256: `cfa895e6e653863a33ec922f548ad5cc825bdd79db7a3e1fabd4717d737a3e2d`

### Shared quiet root helper

Current helper contract:

- size: `32888`
- SHA-256: `4dd29619caae2b08aa491d7fcfc2e5d0d1ea11d0b6b9d1673d49f7580a40858f`

Current LAB validation receipt:

- source revision: `b7db5554369e5be48c1587ff654aae502af48ad2`
- workflow run: `35751805764`
- validation date: 2026-09-22
- receipt ZZI4 feed exploit SHA-256: `a7dc5fe9967de726ade5f4acaa4ba03812682faf700714b30f47acd324c85fdc`
- receipt ZZI4 ksud SHA-256: `d76b44c984b8d6f7121f712d8aa4e7cf0178ff36a8cd531bddaca9dd2fd361e2`

## 4. What failed before 0.1.34

The 2026-09-22 failure sequence looked like several unrelated broken systems, but it resolved into a small number of architectural regressions.

### 4.1 Shizuku was being treated as a hard dependency in Manual

Observed user-facing behavior:

- Use Shizuku was enabled.
- Shizuku itself was stopped.
- Wireless ADB pairing was valid.
- the Manual flow still stopped at Shizuku instead of continuing through Local ADB.

Root cause:

The selector only fell back from unavailable Shizuku to Local ADB in the shell-required branch. If the feed had already been misconfigured as non-shell-preferred, requesting Shizuku could return no transport even with a valid ADB pairing.

Fix:

Manual now treats Shizuku as a preferred shell transport. If Shizuku is requested but unavailable and paired Local ADB is available, it can fall back to Local ADB.

The Local ADB session still has to prove a real shell context before the exploit starts.

### 4.2 ZZI4/ZZIC feed policy had regressed to the standalone-style route

The LAB feed had been changed to values equivalent to the Local-P0 experimental series:

- legacy slide route;
- shorter timeout;
- P0 cache off;
- shell preference off.

That policy was useful for isolated app-local experiments but wrong as the main feed policy for the current shell-first architecture.

The feed was restored to:

- auto route;
- 24 attempts;
- 120 second attempt timeout;
- 45 second P0 attempt timeout;
- P0 cache on;
- shell preference on.

The validator now protects the ZZIC policy from silently drifting back to the bad configuration.

### 4.3 Auto Root used the physical-P0 exact asset even after obtaining a shell

This was the largest orchestration mistake.

Auto Root correctly selected Shizuku or Local ADB in some runs, but the exact pa3q runner still routed those shell transports through the dedicated UniRoot-compatible physical-P0 payload.

That meant the application had already obtained the better shell execution domain, then voluntarily discarded its main advantage and used the app-local P0 route anyway.

Fix:

For exact ZZI4/ZZIC:

- if Auto Root has Shizuku or Local ADB, it uses the normal feed payload and normal target policy;
- only if no shell is usable does Auto Root use the isolated exact physical-P0 runner.

This is now an explicit shell-vs-standalone split.

### 4.4 FOPS retry suppression could be poisoned by earlier P0 writes

The native code had a retry-state latch that was intended to mean “the dangerous FOPS write landed”.

However, the same latch was being published by a generic physical-write helper also used during P0/oracle work.

Result:

A successful write used to discover/verify the slide could set the latch. Later, if the actual FOPS shot missed, the supervisor could see the old latch and incorrectly refuse more FOPS retries on that boot.

Fix:

The generic physical write no longer sets the FOPS landed latch.

The latch is published at the FOPS call site, after the actual FOPS trigger result is known.

P0/oracle writes therefore no longer masquerade as successful FOPS writes.

### 4.5 Dirty P0/oracle state now surfaces as terminal

A dirty/uncertain P0 oracle is not treated as an ordinary missing-success-marker error.

Manual and Auto Root now classify that condition explicitly.

The intended next action is a reboot, not blind exploit replay.

### 4.6 One release test had frozen an old physical-P0 binary hash

After the native FOPS-latch fix, the exact physical-P0 assets were legitimately rebuilt.

A contract test still required the old fixed asset size/hash from Kotlin constants, so the release failed even though the new artifact was correctly rebuilt and had valid provenance.

Fix:

The test now validates the exact bundled P0 artifacts against the release-generated payload provenance and firmware-specific identity instead of requiring obsolete fixed bytes forever.

This is an important testing rule going forward: generated artifact provenance is the source of truth for intentionally rebuilt exact assets.

## 5. Current Manual behavior after 0.1.34

Transport selection happens before exploit execution.

Expected decision flow:

1. If Use Shizuku is enabled and Shizuku is actually running and authorized, use Shizuku.
2. If Use Shizuku is enabled but Shizuku is unavailable and a paired Local ADB path exists, fall back to Local ADB.
3. For a shell-required target, paired Local ADB remains a valid fallback even if Shizuku cannot be used.
4. If the selected route requires shell and no shell can be established, fail explicitly before starting the race.
5. App-local execution is used only when the route intentionally supports it.

For Local ADB, the app must establish the session and verify shell identity before exploit launch.

The shell payload remains the normal feed payload.

If the payload reports dirty P0/oracle state, Manual should stop with a reboot-oriented diagnostic rather than repeatedly retrying.

## 6. Current Auto Root behavior after 0.1.34

For exact ZZI4 and ZZIC, intended transport order is:

1. Shizuku;
2. Local ADB;
3. standalone app-local fallback.

Shell route:

- uses the normal feed exploit;
- keeps the target's shell-first `auto` route policy;
- can take advantage of tracefs in shell context;
- does not use the dedicated physical-P0 exact asset.

Standalone route:

- is selected only when no usable shell is available;
- uses the firmware-specific exact physical-P0 asset;
- keeps its own local fallback envelope;
- remains an emergency/offline recovery path rather than the preferred route.

The exact physical-P0 runner does not decide transport. Transport is selected by the Auto Root service/orchestration layer.

Bootstrap root is latched before KernelSU/post-root work. Once bootstrap root has landed, later failures must not reopen exploit retries.

## 7. Historical UniRoot work that still matters

The UniRoot investigation established several useful orchestration ideas:

- lightweight boot receiver;
- foreground service;
- delayed execution after boot stabilization;
- once-per-boot guard;
- progress driven by stage markers;
- live/ongoing notification;
- overlay during critical execution;
- Stop control;
- persistent run logs;
- controlled delayed retries.

RMGLabs intentionally did not copy every UniRoot choice.

Important deliberate differences:

- exact RMG firmware matching is stricter;
- current RMG uses Shizuku -> Local ADB -> standalone rather than mandatory standalone for S25;
- terminal notification handling should not reproduce UniRoot's old two-notification-ID issue;
- custom validated KernelSU is retained instead of auto-updating to latest;
- unsafe replay after root or dirty writer state is not allowed.

Older handoffs sometimes froze Manual while Auto Root was under investigation. That was the correct rule for those experiments. The 2026-09-22 fix intentionally touched Manual because a separate user-facing Manual transport bug was independently proven. Future changes should still avoid coupling Manual and Auto Root unnecessarily.

## 8. Current production state

Latest production application release at the time of this handoff:

- `v0.3.104`
- production app repository current HEAD at handoff creation: `680dad446ae57aefbeacc4010003d78888de4c05`

Production payload repository current HEAD at handoff creation:

- `8d997446d2a8e10eb9062b5588deec7d985f700c`

Production currently contains exact ZZI4 support with the stable shell-first feed policy:

- `slideRoute = auto`
- `attempts = 24`
- `attemptTimeoutSec = 120`
- `p0AttemptTimeoutSec = 45`
- `p0OffsetCache = true`
- `prefersShellTransport = true`

Current production ZZI4 exploit SHA-256 is still the earlier validated value:

- `14143d6c5385e4c46bd34dc335772e9d1bcae7f5c102e34a6b142b875395df2b`

Production does not currently contain the exact ZZIC profile in its production payload feed.

Production also still contains an older Auto Root coordinator/executor architecture. The current RMGLabs 0.1.34 shell-vs-standalone split must not be assumed to exist in production.

No automatic LAB-to-production sync should occur.

## 9. What is actually validated versus only implemented

Implemented and CI-validated in LAB 0.1.34:

- exact ZZIC feed identity;
- exact ZZIC target data;
- rebuilt normal ZZI4/ZZIC payloads;
- rebuilt dedicated ZZI4 physical-P0 asset;
- rebuilt dedicated ZZIC physical-P0 asset;
- refreshed quiet-helper validation receipt;
- restored shell-first feed policies;
- Manual Shizuku-to-Local-ADB fallback logic;
- Auto Root shell/standalone payload split;
- dirty-oracle terminal classification;
- FOPS landed-latch correction;
- release provenance-based exact-asset test;
- full APK build/sign/release pipeline.

Not yet established merely by CI:

- real hardware reliability of 0.1.34 on ZZIC;
- real hardware reliability of 0.1.34 on ZZI4 after native rebuild;
- whether Shizuku -> shell route consistently succeeds after boot on current One UI 9 builds;
- whether Local ADB fallback consistently succeeds after boot;
- standalone physical-P0 success probability after the latch fix;
- whether the older standalone KernelSU handoff failure from the 2026-09-18 investigation is still reproducible in the current architecture;
- comparative FOPS success probability across shell and standalone;
- whether any additional same-boot slide reuse is worth implementing.

Do not mark these as solved until logs from the current release prove them.

## 10. Immediate hardware validation plan

Use `lab-v0.1.34` as the current test APK.

First validate behavior, not statistics.

### Scenario A — Shizuku available

Preconditions:

- Use Shizuku enabled;
- Shizuku running;
- permission granted;
- ADB may also be paired.

Expected:

- Shizuku selected;
- shell context verified;
- normal feed payload used;
- shell/auto route used;
- dedicated physical-P0 asset not selected.

### Scenario B — Shizuku unavailable, Local ADB available

Preconditions:

- Shizuku stopped/unusable;
- Wireless Debugging pairing valid.

Expected:

- the flow does not stop merely because Shizuku is down;
- Local ADB is selected;
- session proves shell identity;
- normal feed payload is used;
- shell/auto route is used.

This scenario directly validates the user-facing regression fixed on 2026-09-22.

### Scenario C — no usable shell

Preconditions:

- Shizuku unavailable;
- Local ADB unavailable.

Expected:

- Auto Root may select standalone fallback;
- exact firmware-specific physical-P0 asset is used;
- shell payload is not misrepresented as standalone;
- dirty oracle stops the run rather than causing unsafe replay.

Manual should fail explicitly if its selected target/operation requires shell and no valid Manual shell fallback exists.

## 11. Logging evidence to preserve from the next tests

For every test, keep the full app history/run log.

The most useful markers are:

- selected transport and reason;
- uid / SELinux context;
- profile identity;
- payload/build label;
- route policy / slide source;
- KASLR/slide result;
- FOPS attempt/result;
- dirty-oracle markers;
- `done=1 root=1`;
- exploit completion marker;
- KernelSU handoff stage;
- KernelSU control/readiness result;
- post-root result;
- whether any external/local relaunch occurred.

Do not summarize a failure as “Shizuku failed” or “ADB failed” without separating connection, exploit and handoff stages.

## 12. Open technical questions

### 12.1 Shell reliability on ZZIC

The exact Beta 3 target and payload now exist and package correctly, but current hardware success rate still needs measurement.

If failures persist after transport selection is confirmed correct, investigate the native stage actually failing rather than changing orchestration again.

### 12.2 FOPS reliability

Historical logs repeatedly showed successful slide/KASLR acquisition followed by missed FOPS shots.

The incorrect early `write_landed` latch is fixed, so new logs are required before deciding whether FOPS itself still needs tuning.

Do not reuse old post-fix conclusions from pre-fix logs.

### 12.3 Standalone reliability

The exact physical-P0 fallback remains intentionally available.

Historical UniRoot-style standalone runs proved bootstrap root was possible, but reliability could be poor and repeated process relaunches could lose useful same-boot slide state.

Possible future LAB research:

- safe same-boot verified slide reuse across local relaunches;
- separating app-local slide acquisition from the later FOPS strategy;
- testing whether parts of the shell FOPS-bank/physical-alias route are truly shell-dependent;
- hybrid app-local route after a verified physical slide.

These are research directions, not current implementation instructions.

### 12.4 Standalone KernelSU handoff

A 2026-09-18 standalone run acquired bootstrap root but then failed during KernelSU handoff and incorrectly replayed the exploit.

The replay bug has since been addressed architecturally.

The underlying same-transport standalone KernelSU handoff should still be revalidated on the current release before being considered solved.

### 12.5 Auto Root boot timing

Historical UniRoot work used a fixed 90-second settle and later work investigated boot-state timing.

Do not change boot delay merely because a single run is slow or fast.

If timing is revisited, collect multiple boots and isolate the timing experiment from transport/native changes.

## 13. Production promotion roadmap

Do not promote 0.1.34 automatically.

Before production promotion:

1. Validate Scenario A, B and C behavior on current hardware.
2. Confirm that shell routes actually use the normal feed payload.
3. Confirm no exploit replay after bootstrap root.
4. Confirm dirty-oracle state is terminal.
5. Collect enough successful/failed runs to distinguish routing bugs from native race probability.
6. Decide which RMGLabs commits form the smallest coherent production port.
7. Port application changes intentionally into `Root-My-Galaxy-Extended`.
8. Port only required payload/native changes into `Root-My-Galaxy-Payloads-Extended`.
9. Add ZZIC production target only after exact Beta 3 hardware validation is accepted.
10. Run production preflight/release pipeline.
11. Validate the production APK separately.

Production should not receive experimental standalone/hybrid work merely because that work exists in LAB.

## 14. CI / release workflow state

The latest payload rebuild chain completed successfully:

- ZZI4 + ZZIC normal exploit rebuild;
- feed publication;
- ZZI4 exact physical-P0 rebuild;
- ZZIC exact physical-P0 rebuild;
- quiet-helper validation receipt refresh.

The final RMGLabs release workflow also completed successfully and published `lab-v0.1.34`.

A prior release attempt failed because the exact-P0 contract test pinned obsolete bytes. That test was corrected to use bundled provenance before the successful release.

When native source under the dedicated physical-P0 build changes again, rebuild the dedicated assets before the app release.

## 15. Files that deserve special attention

Application / orchestration:

- `app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt`
- `app/src/main/java/dev/busung/s25uroot/AutoRootService.kt`
- `app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt`
- `app/src/main/java/dev/busung/s25uroot/UniRootZzi4Exploit.kt`
- `app/src/main/java/dev/busung/s25uroot/ExploitRoutePolicy.kt`
- `app/src/main/java/dev/busung/s25uroot/PayloadRepository.kt`
- `app/src/main/java/dev/busung/s25uroot/OneUi9PostRootProfiles.kt`
- Local ADB / Shizuku transport helpers
- KernelSU runtime/handoff helpers

Important LAB tests:

- `ManualShellTransportPolicyTest.kt`
- `AutoRootShellTransportContractTest.kt`
- `UniRootZzi4AutoRootContractTest.kt`
- `Zzi4PostRootRuntimeTest.kt`
- integrity/release isolation tests

Payload/native:

- `support/targets-v3.json`
- `.github/scripts/validate_feed.py`
- `src/slide_app.c`
- exact target headers under `src/targets/`
- exact physical-P0 artifact directories
- KernelSU artifacts
- validation receipt

Release:

- RMGLabs `.github/scripts/prepare_lab_payload_bundle.py`
- RMGLabs `.github/workflows/release.yml`

## 16. Rules for the next implementation session

Before changing code, answer these questions:

- Which exact firmware/profile is failing?
- Which transport was selected?
- Was the transport actually verified?
- Which payload/build label ran?
- Which slide route ran?
- Did KASLR/slide succeed?
- Did FOPS succeed?
- Did bootstrap root land?
- Did KernelSU handoff fail after root?
- Is native state still safe to retry?
- Is the proposed change in transport, native exploit, handoff, post-root or packaging?

Do not change several of those layers simultaneously unless the task is an intentional architectural migration with enough tests to prove the boundary.

After implementing:

- update relevant tests;
- update payload artifacts/provenance when native bytes changed;
- update this `HANDOFF.md`;
- mirror the handoff update to both app repositories;
- produce a release only when there is an APK worth hardware-testing.

## 17. Current resume point

Continue from `lab-v0.1.34`.

The immediate next source of truth should be hardware logs from the current release, especially:

- Shizuku available;
- Shizuku unavailable with valid Local ADB;
- no shell available.

Do not reopen the 0.1.10–0.1.15 Local-P0 feed policy. That experiment already demonstrated why the standalone fallback must not become the normal shell policy.

Do not assume the remaining problem is transport if the current logs prove transport selection and shell identity are correct.

If shell routing is correct and the run still fails, diagnose the native stage from current logs before changing orchestration again.


## 18. Hardware evidence: 2026-09-23 ZZIC Auto Root failure

Evidence supplied: user-device run log `RootMyGalaxy-20260923-184832-failed.log.txt` from the exact `pa3q-S938BXXUCZZIC` profile. This records a failed real-device Auto Root run. The log itself does not include an independently verified APK/payload provenance receipt; do not assume the app binary is identical to the release pin solely from the profile label.

Observed results:

- Auto Root selected `local-adb` because Shizuku Binder was unavailable. The paired ADB shell was verified in the log: UID/EUID 2000, SELinux `u:r:shell:s0`.
- The loaded profile reported `slideRoute=auto`, 24 available attempts, P0 caching enabled and the `pa3q-S938BXXUCZZIC-app-tracefs-phys-alias` native build label.
- The first native attempt accepted a tracefs-derived slide candidate of `0x50000` (`slide-kaslr-ok source=tracefs`); later attempts reused that value (`source=forced`). The tracefs raw summary reports one candidate with one hit. The reported discovery success is *not* independent proof of the candidate's correctness.
- Across eight recorded FOPS shots, the native log repeatedly reported `pselect` return, followed by `p0 physical write status=256 ok=0`, `triggered=0`, no verified read/write, and `root=0`.
- The supervisor stopped at its eight-shot boundary; the final log reports missing exploit success markers and `terminal_result=failure`.
- No evidence of a KernelSU handoff failure appears in this run: bootstrap root was never reported.

Current-source comparison performed against LAB `main` on 2026-09-23:

- `AutoRootService` selects an established shell transport before choosing the effective payload; the shell leg uses the normal bundled feed payload rather than the dedicated physical-P0 fallback asset.
- `AutoRootRunner` and `InstallViewModel` both request the profile route policy and the normal feed payload for their respective shell execution paths. Both refer to the packaged `libcve43499root.so` helper as their source; staging and process lifecycle differ, but the current Kotlin wiring does not establish a distinct Manual-only KASLR discovery route.
- The LAB feed currently records `slideRoute=auto` and `prefersShellTransport=true` for exact ZZIC.

Status: the older Auto Root shell-versus-standalone misrouting has not been reproduced by this log. The current failure is observed after the native payload's claimed slide discovery, during the subsequent native stage. This observation does **not** establish whether the accepted slide was correct or which native operation is causally responsible. Subsequent Manual success and failure logs for the same firmware profile were supplied on 2026-09-23; see section 19. Exact artifact digests and matched kernel-boot context are still unavailable, so these logs are observational, not a controlled comparison.

This update records hardware evidence only. No exploit implementation, race parameters, transport selection, payload bytes, tests, or release workflow were changed. Production implementation and production payloads remain untouched. Preserve this distinction in the next investigation; do not classify this run as a transport failure or a proven KASLR failure.


## 19. Cross-run Manual/Auto Root evidence: 2026-09-22 to 2026-09-23

User-provided device logs additionally examined on 2026-09-23:

- `RootMyGalaxy-20260922-211645-succeeded.log.txt`: Manual successful execution using Local ADB on exact ZZIC profile.
- `RootMyGalaxy-20260923-190951-failed.log.txt`: Manual failed execution using Shizuku on exact ZZIC profile.
- Compare these with the Auto Root failure documented in section 18.

All three logs report the exact `pa3q-S938BXXUCZZIC` profile and native build label `pa3q-S938BXXUCZZIC-app-tracefs-phys-alias`, shell execution context `uid=2000 u:r:shell:s0`, `slide source mode=auto`, and the feed's configured 24 attempts. Each first attempt reports a tracefs slide candidate accepted by the native logger. The log labels and bundled verification markers do *not* independently prove identical APK/payload bytes between runs; the failed logs also lack an exposed kernel boot ID enabling same-boot matching.

Comparison of the *first occurrence* of the native output (the failed Manual log repeats its entire captured stdout in a later exception message; this is duplicate reporting, not a second set of attempts):

| Path and outcome | Shell transport | Reported slide | Native FOPS observations | Result |
| --- | --- | --- | --- | --- |
| Manual 2026-09-22 success | Local ADB | `0x50000` (tracefs candidate hits=2) | First FOPS shot: `pselect ret=0 window=0`, physical write `status=256 ok=0`; second shot: `pselect ret=7 window=1`, physical write `status=0 ok=1`, later physrw verification succeeds | Bootstrap root and KernelSU control acquired |
| Manual 2026-09-23 failure | Shizuku | `0x1d0000` (tracefs candidate hits=1) | Eight FOPS shots: `pselect ret=0 window=0`, physical write `status=256 ok=0`, no physrw verification | Stops after FOPS eight-shot budget with no root |
| Auto Root 2026-09-23 failure (section 18) | Local ADB | `0x50000` (tracefs candidate hits=1) | Eight FOPS shots: `pselect ret=0 window=0`, physical write `status=256 ok=0`, no physrw verification | Stops after FOPS eight-shot budget with no root |

Important interpretation boundaries:

- The two failed runs use different *shell transport implementations* but show the same native failure pattern. The successful Local ADB Manual run and failed Local ADB Auto Root run further rule out identifying Local ADB availability alone as the differentiator.
- The successful run shows a change in the logged `pselect` return/window indicators together with the later physical-write and physrw success. This is a correlation from one successful run, not proof of a causal parameter or recommended native timing modification.
- Reported slide values differ across executions; kernel address randomization and lack of matched boot IDs mean the values should not be treated as directly comparable. The logger's `slide-kaslr-ok` status is not an independent correctness check.
- A configured native attempt count of 24 did not mean 24 FOPS shots in the failed runs: the observed dedicated FOPS eight-shot guard terminated those executions. The duplicate exception text in the Manual failure must not be counted as additional attempts.
- These three runs alone do not establish reliability percentages or a causal preference for Shizuku, Local ADB, Manual, or Auto Root. They do establish that Auto Root's remaining failure is not exclusively caused by its orchestration, since current Manual can reproduce the same native failure signature.

The Manual successful run also revealed **separate post-root behavior** after KernelSU control was verified:

- The best-effort app optimization/dexopt step was skipped because root was not confirmed to that caller.
- The app-authenticated `su` helper reported `connect daemon: Permission denied`; the application then established Local Wireless ADB fallback and verified `KernelSU --allow-shell` there.
- Shizuku started through the native-lib path; KernelSU native soft-reboot request was accepted.
- These warnings occurred *after* confirmed KernelSU control and installation completion. They are post-root principal/readiness or optional automation diagnostics, not evidence that the native root acquisition failed. Do not relaunch the exploit in response.

Disposition: record a shared native-stage failure signature and separate post-root usability warnings. Preserve the Manual and Auto Root implementations, race geometry and retry guard pending independent validation; avoid selecting a purported 'better' KASLR/transport path based only on these three runs. Do not treat success of the APK/release workflow as hardware reliability proof. No source, payload, AGENTS.md, tests, or workflows are changed by this documentation-only update.
