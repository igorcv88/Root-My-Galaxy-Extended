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

- Tag: `lab-v0.1.35`
- Release name: `RMG Labs 0.1.35-lab`
- Published: 2026-09-23
- Application source used by the release: `adc998e7e3937d9e9ca1b0bed278224e5ec9d92c`
- Payload snapshot used by the release: `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`
- Release-order trigger commit: `3e7bd7573536fe8c0c0eedce036d5aca1bedc964`
- Release build workflow: `35893046498` (success)
- Release APK: `RMG-Labs-0.1.35-lab.apk`
- Published APK SHA-256: `a22651b77d21249239865a6e30d8478b99895025fe4128c59688da945c0c260e`

Previous release: `lab-v0.1.34` (2026-09-22), application source `fc0cfdd81020d09be495432d0dca377107aabf3e`, same payload snapshot `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`.

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


## 20. Manual transport fallback defect and external research (2026-09-23)

Scope: diagnostic research and architecture proposal only. **No application/native/payload source or test changes are implemented in this task.** Production mirroring of this document is not production feature promotion.

New user-observed symptom: Manual repeatedly chooses Shizuku or paired Local ADB and never reaches an app-local standalone option if Shizuku is down and there is no *actually usable* Local ADB connection (notably when Wi-Fi is disconnected).

Confirmed current LAB source cause:

- `InstallViewModel.chooseManualRunTransport(shellRequired, shizukuRequested, shizukuUsable, localAdbPaired)` treats a *saved pairing record* (`localAdbPaired`) as Local ADB availability. `selectRunTransport()` does not establish a working Local ADB shell session before returning that choice; actual connection is deferred until `runExploitAndKernelSu()`, where failure causes the Manual operation to terminate.
- The current function explicitly returns `null` when `shellRequired` is true and both Shizuku and the pairing record are unavailable; `selectRunTransport()` then throws. It does not express or select the exact pa3q app-local fallback envelope.
- Current contract test `ManualShellTransportPolicyTest.shellRequiredNeverFallsBackToAppDomain` explicitly protects this behavior. Its purpose must be reconsidered *only in conjunction with* verified exact standalone identity/provenance; blindly replacing `null` with `App` would execute the ordinary shell-preferred feed payload in the wrong envelope.
- `AutoRootService.selectTransport()` does a stronger Local ADB readiness check before selecting the transport; `payloadsForTransport()` also isolates the exact standalone policy, and `AutoRootRunner` selects `UniRootZzi4Exploit` for the exact app-local route. These are architecture references for the Manual design, not code that should be indiscriminately copied.
- Shizuku availability is a live Binder/session/authorization property, not app-installed state and not Wi-Fi availability. A started Shizuku server can remain usable after its startup Wi-Fi is disconnected. Preference for Shizuku should be reconciled with the user's selected transport policy.
- ADB's saved pairing is separate from a current network path and authenticated shell session. Android's documentation says pairing can persist after network disconnect; Android 17 Wi-Fi 2.0 adds reconnection behavior on trusted networks, making live verification, rather than the presence of pairing metadata, important.

Research checked on 2026-09-23:

- Android Developers ADB documentation: https://developer.android.com/tools/adb (separates pairing from connection and describes Android 17 Wi-Fi behavior).
- Shizuku API guide: https://github.com/RikkaApps/Shizuku-API (Binder lifecycle, permission, UID distinction).
- Reference orchestration: https://github.com/kuuky29/UniRoot and current project handoff.
- Similar root-project native failure report: https://github.com/BuSung-dev/Root-My-Galaxy/issues/280 (similar native log signature on a **different firmware**).
- Diagnostic isolation example: https://github.com/BuSung-dev/Root-My-Galaxy/issues/652 (another model/firmware, not a transferable fix).
- XDA thread: https://xdaforums.com/t/one-ui-9-root-zzhl-and-zzi4-sm93xx.4801097/ ; indexed result includes a poster's statement that Shizuku is not required for the S25 series. XDA returned HTTP 403 on full-page retrieval, so do not claim independent full-thread review or treat that comment as ZZIC device validation.

Architecture investigation outcomes:

1. Treat transport eligibility as an observed preflight capability, not merely a stored preference or a saved ADB pairing: active authorized/verified Shizuku, or connected Wi-Fi and an authenticated working Local ADB shell. A live Shizuku Binder can remain independently valid when Wi-Fi disappears.
2. A no-shell selection may be represented as a separate **exact validated standalone plan** only for targets with such an artifact. Preserve distinct native payload/envelope/policy identities and fail closed if exact asset integrity/firmware identity cannot be established.
3. Keep transport and payload readiness separate from exploit execution: a connection failure discovered before launch should not consume exploit retry budgets. A failure after launching a native race must not silently trigger a different native envelope in the same uncertain kernel state.
4. Tests should cover Shizuku running/authorized, absent or permission denied, pairing saved with Wi-Fi disconnected, Wi-Fi connected but unreachable ADB, working authenticated Local ADB, and no usable shell with and without an exact verified standalone asset. Respect existing Manual/Auto Root separation while potentially sharing only the eligibility/policy decision logic.
5. Investigate the previously observed native failure and the post-KernelSU app-`su` denial as distinct issues. Current three hardware logs do not establish a causal native-timing fix or reliable transport ranking.

Open: implement and validate any Manual transport decision and exact standalone asset integration deliberately in LAB before promoting. This section records a confirmed Manual transport policy defect and research references, **not** a tested correction or a release.


## 21. LAB 2026-09-23 Manual fallback and Apply Modules implementation

New hardware input: `RootMyGalaxy-20260923-193921-succeeded.log.txt` from exact ZZIC. The run selected app-local standalone (no verified Shizuku or Local ADB), loaded the exact `app-physical-p0-oracle` build, acquired physical slide `0x10000` on attempt 3, missed the later FOPS shot there, and completed FOPS/physrw/bootstrap-root on attempt 4. The first P0 shot failed its write window; the second registered a write but failed the oracle gate. This is direct evidence of at least one working standalone native route on real ZZIC hardware and of intermittent outcomes within the same run; it does **not** establish a causal FOPS timing modification. Native payload bytes and retry/race constants are unchanged in this task.

The standalone handoff log then reported `KernelSU late-load rc=0` but app-context global readiness failed with `su: connect daemon: Permission denied`. The old service unconditionally logged `KernelSU_control_result=active` after this advisory outcome, and its notification offered Apply Modules. The notification action went through a short-lived `BroadcastReceiver.goAsync` and `RootRecoveryActions.kernelSuSoftReboot` first called `verifiedRootBoot()`, which tested only app helper/Shizuku; that preflight prevented the existing `PostRootAutomation` Local ADB fallback from being attempted.

LAB changes on `main` on 2026-09-23, with initial application HEAD before this documentation update `c25e856b97a4ad362134449a1d2b5409a9f80049`:

- Manual selector: discriminate live Shizuku Binder/authorization/shell identity and live Local ADB session over connected Wi-Fi rather than considering a saved ADB pairing to mean connection. Preserve the Shizuku user preference. A valid Shizuku server can be used independently of current Wi-Fi connectivity.
- Exact ZZI4/ZZIC Manual no-shell path: route to the existing dedicated bundled standalone coordinator (`AutoRootRunner` + exact `UniRootZzi4Exploit`) after exact firmware matching and bundled feed verification. The ordinary shell feed payload is never substituted for this separate app-local artifact. If neither verified shell nor exact standalone is available for a shell-preferring target, fail closed. Non-exact legacy app route remains separate.
- Manual standalone safety: latch same-boot bootstrap-root acquisition before optional KernelSU handoff, suppressing repeat Manual standalone races after root is already acquired even if a later handoff fails.
- Apply Modules: a foreground `AutoRootService` action now owns long-running post-root recovery instead of holding a broadcast open; preexisting notification broadcasts are forwarded for compatibility. Errors appear in the same notification, leaving a retry action visible.
- Soft reboot preflight: preserve kernel boot identity and require a current-boot install receipt or native KernelSU proof, but let `PostRootAutomation` actually select/verify a root-capable Shizuku, app-helper, authorized app `su`, or paired Local ADB path before any detached soft-reboot handoff. User-granted KernelSU Manager app permissions remain required for the direct app `su` path.
- Result accuracy: `AutoRootRunner` tracks whether global KernelSU readiness was actually verified. Auto Root distinguishes advisory post-late-load success from confirmed global readiness in terminal logs and success notification text. This avoids claiming KernelSU control based only on standalone `late-load rc=0`.

Modified LAB Kotlin components: `InstallViewModel.kt`, `AutoRootRunner.kt`, `AutoRootService.kt`, `AutoRootBootReceiver.kt`, `AutoRootNotification.kt`, `RootRecoveryActions.kt`, `RootHelperShell.kt`, `KernelSuRuntime.kt`. Updated LAB tests: `ManualShellTransportPolicyTest.kt`, `Zzi4PostRootRuntimeTest.kt`.

Validation completed at documentation time: targeted current-source/static contract checks passed for dispatch, exact standalone separation, boot guard, root-status logging, and notification service wiring; this is **not** Android compilation or device validation. Validation result (updated 2026-09-23):
- Initial pinned LAB release job `35892511842`: debug and release Kotlin compilation succeeded, but a historical source-string test that forbade *all* Manual references to the UniRoot coordinator failed (126 of 127 unit tests passed). No APK was published from this attempt.
- Updated `UniRootZzi4AutoRootContractTest.kt` to preserve the meaningful invariant: Manual may reuse the existing exact standalone coordinator but must not hardcode imported standalone binary names or asset paths.
- Final pinned LAB release job `35893046498`: **success**. Unit tests, lint, release assembly, LAB-only isolation tests, pinned payload provenance, unsigned artifact integrity, signed APK verification and release publication all completed successfully. Publication: `https://github.com/igorcv88/RMGLabs/releases/tag/lab-v0.1.35` (APK SHA-256 `a22651b77d21249239865a6e30d8478b99895025fe4128c59688da945c0c260e`).
- These are CI/software checks, **not** hardware validation. Confirm the updated Manual live-transport/standalone paths, foreground Apply Modules behavior, KernelSU Manager grant interaction and reboot side effects on real exact ZZIC hardware before considering the UX issues resolved.
- No changes to native FOPS/race parameters or any LAB or production payload bytes, and no production app code promotion. Hardware confirmation still required for (a) Manual no-Wi-Fi/no-Shizuku standalone, (b) Manual verified Shizuku, (c) Manual verified Wi-Fi/Local ADB, and (d) Apply Modules with an already-authorized root bridge versus without it.

Payload repository remains at `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`; no source, binary, native FOPS/timing, or provenance changes were made there. Production application/payload implementation remains frozen; only this handoff is mirrored to production. Do not characterize lack of native FOPS changes as an established fix to intermittent FOPS misses.


## 22. DFReroot compatibility research and non-invasive design (2026-09-23)

Scope: user requested research into BOTH a post-first-root recovery mechanism and a truly independent first-stage root option, with only their main Galaxy S25 Ultra available for testing. This phase is source-only and off-device. No persistence, APK deployment, kernel operations, modifications to /data/system/packages.xml, signing-key injection, SELinux changes, custom module loading, production source changes, payload changes or releases are authorized.

Detailed source audit and architecture plan: `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` (LAB only), pinned upstream `polygraphene/DFReroot@9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77` (v2.0.1).

Confirmed architectural facts:
- Upstream requires previous root to modify Android's package-signature history and install a persistent system-UID companion; it supplies no independent first-stage root by itself.
- A generic `android15-6.6` kernel module candidate exists and its selector would match the kernel-family string of exact ZZIC. Neither kernel-family matching nor security patch dates prove vulnerability or module compatibility on the exact Samsung build.
- The upstream manifest does not itself implement a boot receiver for hands-free recovery. Upstream includes its own `ksud`, which must not replace RMG's pinned custom KernelSU. Its system-UID persistence and page-cache/SELinux effects raise substantial security/boot-recovery concerns.
- Public upstream compatibility issues #2/#3 report partial Samsung progress but failure at later Dirty Frag stages; #4 reports package-database permission damage in a porting attempt. These are reports, not proof of ZZIC behavior.
- The exact Samsung ZZIC kernel source or a verified matching offline stock image was not located/verified during this session. The xfrm/ESP fix status for CVE-2026-43284 remains UNKNOWN.

Design: preserve existing RMG native Manual, Auto Root, exact firmware routes, boot guard and KernelSU pipeline. Explore only a non-executable, opt-in LAB compatibility/reporting boundary until exact source patch status, licensing/artifact provenance and recovery constraints are established. Cross-exploit same-boot fallback is prohibited by default. No new runnable Dirty Frag stage and no background automatic exploitation is implemented.

Research gate before further work: obtain exact ZZIC source/legitimate offline stock image; independently assess the published fix and kernel-specific compatibility; inspect third-party licenses and binary provenance; document state recovery and security implications; create only software-only policy tests at first. With only the primary device available, defer destructive/persistent hardware experiments.

The earlier LAB `lab-v0.1.35` and sections 18–21 remain the execution baseline; this documentation-only research did not change release/payload hashes or establish a hardware success rate.

Production `HANDOFF.md` is deliberately NOT mirrored during this task because the user expressly requested no production modifications. Mirror the research status during a later explicitly authorized documentation synchronization, not as code promotion.


## 23. Read-only ZZIC device archive confirms kernel features (2026-09-23)

User-provided private diagnostic archive `RMG_DFReroot_20260923_202335.tar.gz` was analyzed **offline only**. Do not commit or upload the raw archive or the extracted `boot.img`, `vendor_boot.img`, `init_boot.img` to public repositories.

The archive contained the system/firmware report, `/proc/config.gz` plus decompressed config, and read-only copies of active B-slot boot, vendor_boot and init_boot. Internal manifest SHA-256 matches verified for the five config/image entries; this does not independently establish OEM signing provenance.

Report/build strings confirm the exact `SM-S938B`/`pa3q` ZZIC Android 17, kernel `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`, vendor/system patch properties `2026-08-05`; bootloader reports locked and verified boot green. The kernel config declares XFRM, XFRM_USER, XFRM_ESP, INET_ESP and INET6_ESP built in (`=y`), plus module signature protection and embedded debug/BTF metadata. The shell-visible absence of standalone `esp4`/`esp6` modules does not contradict compiled-in support.

The `boot.img` has a header-v4 format with a directly readable ARM64/EFI-style kernel Image and internal build strings matching ZZIC. These observations support further **offline** kernel research; they do not prove the presence/absence of Samsung's CVE-2026-43284 backport or DFReroot exploitability.

Detailed evidence and remaining verification gates: `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md`, section 10. The exact Samsung ZZIC source or independently verified matching stock source tag remains outstanding. Existing experimental LAB 0.1.35 and production code/payloads were not changed. No device operations, exploit execution or release occurred during this research.

Production handoff mirroring is deferred because this research task explicitly excludes production modifications.


## 24. DFReroot research: offline audit implemented and public GKI fix located (2026-09-23)

A private diagnostic archive from exact ZZIC was assessed offline using a newly implemented and tested read-only utility. The original tar.gz, partition images and private JSON report were **not** uploaded to GitHub.

New LAB-only files:
- `tools/dfreroot/readonly_compatibility.py`: standard-library offline exact firmware/config/image SHA-256 audit, optional candidate-source patch marker scan; never asserts unknown exploitability as proven or executes privileged operations.
- `tests/test_dfreroot_readonly_compatibility.py`: five isolated synthetic unit tests.
- `tools/dfreroot/README.md`: repeatable non-root usage and stop criteria.
- Detailed findings in `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` section 11.

Validation:
- Python syntax compile passed and 5/5 off-device synthetic tests passed on the exact source/test file contents subsequently committed to LAB.
- The actual private archive passed exact ZZIC identity checks, all five file manifest hashes matched, the relevant XFRM/ESP kernel config capabilities were `=y`, and the `boot.img` header was v4.
- The utility's intended patch status result is `UNKNOWN` and DFReroot executable compatibility is `UNVERIFIED`. Do not promote those to a positive result because kernel features exist.

Important new upstream evidence: the May 2026 Dirty Frag shared-frag fix was integrated into Android common/GKI's android15-6.6 family in May (Google commit `ea866a84e8b71f1eb3c97344b4e7aa77d1cfe4f1`, integration timestamp May 26). Linux 6.6 stable separately documents the fix at 6.6.138. Exact ZZIC reports base 6.6.127 but a September 16 build; the low base version **does not prove vulnerability** because OEM/GKI security fixes may be backported without updating the stable-number suffix. Equally, GKI receipt is not proof that Samsung's exact ZZIC firmware incorporated the fix. Sources are linked in the detailed report.

Next open gate: obtain authoritative exact Samsung S938BXXUCZZIC kernel source or independently matched source/image provenance, then review the relevant source and OEM patch history. Also review DFReroot's unspecified code license and prebuilt module/ksud provenance before any copying. The user has only their main S25 Ultra: no persistent installer, live Dirty Frag experiment, cross-exploit fallback or automatic reroot should be deployed under the present evidence.

No app runtime code, original RMG native exploit, payloads, production files, CI release trigger or signed APK was changed by this investigation. As requested for this scope, production's living HANDOFF is not synchronized by this LAB-only research update.


## 25. DFReroot read-only audit CI result (2026-09-23)

The LAB-only research added a narrowly scoped GitHub Actions workflow `.github/workflows/dfreroot-offline-audit.yml`, triggered by edits to the offline audit tool, its synthetic unit tests, or the workflow file itself. It checks out the project, runs Python 3.12 bytecode compilation and executes only the synthetic test suite. It does not obtain user device files, alter Android code, access signing secrets, build an APK or publish a release.

Verified first workflow run: https://github.com/igorcv88/RMGLabs/actions/runs/35896719152, status completed/success; each job step passed. This follows the separately verified 5/5 local tests and a private offline audit of the user's existing archive. The exact kernel patch status is still UNKNOWN and DFReroot execution is still UNVERIFIED.

Source, test and README were added under `tools/dfreroot/` and `tests/`; the research doc contains the full analysis and links. There is no integration with the real manual/automatic root pipeline or production. Keep the DFReroot work gated by exact vendor source/provenance and availability of a safely recoverable isolated test device.


## 26. Firmware/OTA-led DFReroot research correction (2026-09-23)

The user clarified that they retain the exact installed firmware context and the entire One UI 8.5 -> 9 Beta 1 -> Beta 2 -> Beta 3 OTA chain. Prior research should not block on an unlikely Samsung beta kernel *source* upload. Compiled firmware is **not** original C source, but the exact boot images and OTA binary transitions permit a much more targeted offline code/patch-state investigation. The current ZZIC boot/vendor_boot/init_boot images were already privately collected and internally hash-verified; **do not request another live partition extraction**.

New LAB-only tooling:
- `tools/dfreroot/ota_inventory.py`: safely inventories local ZIP, Samsung AP TAR(.md5), raw A/B `payload.bin` and other binary containers, listing candidate boot images, sizes and relevant package metadata. It never extracts, applies or flashes OTAs.
- `tests/test_dfreroot_ota_inventory.py`: four synthetic container/format tests (ZIP+metadata, Samsung AP TAR, raw payload magic, unknown binary).
- Existing isolated CI `.github/workflows/dfreroot-offline-audit.yml` now syntax-checks and runs both the current exact ZZIC archive auditor and this inventory suite. Run https://github.com/igorcv88/RMGLabs/actions/runs/35898608691 completed successfully; no Android APK was built.
- Operating instructions and example multi-package usage are in `tools/dfreroot/README.md`.
- Revised, detailed end-to-end evidence plan in `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md`, section 12.

Next user input: a small JSON inventory of the available OTA packages (and a full One UI 8.5 AP package if available). Determine true package format and *actual* source/target version chain from package metadata. For incremental/delta OTAs, recover an exact base boot image before offline reconstruction; never presume an arbitrary vendor package is self-contained or uses standard `payload.bin`. Compare a reconstructed final ZZIC boot image against the previously collected private final image; mismatched bytes are not a valid exact match. Recovered disassembly/observable behavior is not synonymous with original source and must not lead to overconfident vulnerability verdicts. Continue independent DFReroot binary provenance and Android package-state/SELinux risk research. The only user testing device is the primary S25 Ultra: no live Dirty Frag operations, persistent signature injection or automated cross-exploit fallback. LAB docs/tools only; production untouched.


## 27. Prior firmware handoff reconciled; ZZIC BTF independently verified (2026-09-23)

The parallel Beta 1/Beta 2 reverse-engineering instance provided a comprehensive handoff and confirmed its earlier symbol/firmware research focused on CVE-2026-43499, **not** the separate Dirty Frag CVE-2026-43284. Do not inherit a patch-state conclusion for Dirty Frag from its GhostLock findings.

Previous ZZI4-to-ZZIC report available in the user's prior library records historical raw ZZI4 kernel Image SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`, Image length `39,115,264`, and BTF digest `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`. The ZZI4 binary itself was **not** accessible in the current session.

Independently re-opened the user's private **current ZZIC** diagnostic archive locally (nothing uploaded). Its boot.img SHA-256 is `a49046afa23e357b2c843edb45f6b31ece558e0e749d619ee9bbf4aafaec7f06`, boot header v4; extracted ARM64 Image length `39,115,264` SHA-256 `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`; embedded BTF at raw Image offset `0x18aca6c`, `6,425,607` bytes SHA-256 `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`. Parsed all 147,790 BTF metadata records and observed FUNC entries for `esp_input`, `esp6_input`, `__ip_append_data` and `__ip6_append_data`. The digest matches the earlier **reported** Beta 2 BTF metadata digest; this proves neither exact Beta 2 file integrity nor identical ESP/UDP machine instructions nor unpatched Dirty Frag.

New LAB-only read-only direct-image comparator: `tools/dfreroot/kernel_pair_inventory.py` plus five synthetic tests in `tests/test_dfreroot_kernel_pair_inventory.py`. It checks known raw kernel Image SHA-256, embedded BTF integrity, image size/equality and total byte changes. It explicitly does **not** attempt to conclude kernel vulnerability or deploy an exploit. The isolated no-APK CI workflow includes this third suite and successfully completed at https://github.com/igorcv88/RMGLabs/actions/runs/35900442286 .

Highest-priority next input: the **already extracted, exact ZZI4** raw Image (historically 39,115,264 bytes, SHA above) or its exact boot.img. The current ZZIC boot image is available from the earlier private tar.gz. Compare the two directly, then determine what additional public control reference or further symbol-level offline work is justified. A full four-step OTA reconstruction is *fallback only* if the missing previous binary cannot be retrieved. This research has no effect on the LAB 0.1.35 root payload/Manual/Auto Root and did not modify production. Primary-device exploit/persistence experiments remain deferred.


## 28. Real OTA manifest resolves boot extraction dependency (2026-09-23)

The user executed the private Termux `RMG OTA Kit` scan successfully on these local archives:
- `SAMFW.COM_SM-S938B_INS_S938BXXSBCZG3_fac.zip` (full old CZG3, ~28 GB ZIP),
- `S938B-CZH1_ZZHL.zip.jar` (CZH1 -> ZZHL),
- `S938B_ZZHL_ZZI4.zip` (ZZHL -> ZZI4),
- `S938B__ZZI4_ZZIC.zip.jar` (ZZI4 -> ZZIC).

**Decisive result:** the actual OTA manifests independently mark `boot`, `init_boot`, `vendor_boot`, `dtbo`, and `vbmeta` as `needs_old=false` for all three beta update packages; their boot operations are self-contained `REPLACE_BZ`, `REPLACE_XZ` and `ZERO`. Consequently the CZG3 -> CZH1 full-firmware gap does NOT block recovery of any of those boot-related beta artifacts; skip costly ~28 GB AP extraction entirely for the Dirty Frag kernel-comparison stage. `system_dlkm`, `vendor_dlkm`, `system` etc. DO have old-image dependencies and should NOT be blindly attempted without exact base partitions.

Manifest-reported boot outputs:
- ZZHL: 101122048 bytes SHA-256 `c7d2cbe7c83b11780edc10158a6f8a3a94ac729f58dfb17c28fee607069d9d0f`.
- ZZI4: 101122048 bytes SHA-256 `a03eafb7c62c841efed0c5f4143f1941769f55238226c7afd4116340803ca7d1`.
- ZZIC: 101122048 bytes SHA-256 `a49046afa23e357b2c843edb45f6b31ece558e0e749d619ee9bbf4aafaec7f06`, equal to the previously privately collected installed ZZIC boot.img hash (internal provenance correlation, not standalone OEM signature validation).

All three packages were parsed with no reported scan errors; their metadata connects exact `CZH1 -> ZZHL -> ZZI4 -> ZZIC`. This is evidence from manifest parsing ONLY: target images must still actually be extracted and verified before the tool can claim operational success. The locally installed RMG OTA Kit supports this already: use `python ~/RMG_OTA_Kit/rmg_ota_kit.py chain --parts boot` after installing pinned `payload-dumper-go` to recover all three target boot images without any old image or CZG3 AP extraction. The kit hashes each extracted target against the parsed OTA manifest, extracts its raw Image and BTF, and writes logs. User should send the metadata/ZZI4 kernel-only evidence bundle first; the full private diagnostic archive for current ZZIC is still available privately from the prior turn.

If it is later necessary to examine other boot-adjacent partitions, repeat `chain --parts boot,init_boot,vendor_boot,dtbo,vbmeta` (all reported base-independent). Defer large system/vendor/DLKM reconstruction pending a concrete scientific need and exact old partition images.

No new vulnerability/Dirty Frag patch status conclusion: still UNKNOWN for all beta builds until independently analyzed matching binary instructions. Do not confuse manifest-based no-base status with proven extracted binary integrity.


## 29. ZZI4 boot extraction independently validated against ZZIC (2026-09-23)

User returned the real extraction outputs: `RMG_OTA_EVIDENCE_ZZI4_KERNEL.zip` and `02_OTA_EXTRACTION.json`. The extraction report records exit code 0 and exact manifest-matching boot images for ZZHL, ZZI4 and ZZIC. ZZI4 boot SHA-256: `a03eafb7c62c841efed0c5f4143f1941769f55238226c7afd4116340803ca7d1`, raw Image SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`, Image size `39,115,264`, BTF SHA-256 `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`; this matches the historical Beta 2 digest. The ZZIC extraction independently reproduces boot SHA-256 `a49046afa23e357b2c843edb45f6b31ece558e0e749d619ee9bbf4aafaec7f06`, raw Image SHA-256 `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`, same Image size, and the same BTF digest. The extraction report also marks the extracted ZZIC boot as matching the previously collected installed boot image.

Current-session independent offline comparison of the two raw Images confirms:
- exact equal size: `39,115,264` bytes each;
- ZZI4/ZZIC Image digests above;
- `1,294,850` differing bytes (`3.3103445%`);
- `136,593` contiguous differing ranges under direct byte comparison;
- embedded BTF begins at raw Image offset `0x18aca6c` in ZZIC and is byte-identical to the extracted ZZI4 `btf.bin` (`6,425,607` bytes, digest above).

This independently confirms the earlier broad ZZI4/ZZIC binary/BTF report and removes the need for OTA reconstruction for the target boot images. **It still does not determine CVE-2026-43284 patch status**: BTF equality is type/function metadata equality, not machine-code equality for `esp_input`, `esp6_input`, `__ip_append_data`, or `__ip6_append_data`.

Highest-value next evidence is a read-only current-boot kallsyms capture for exact ZZIC, sufficient to map those function virtual addresses to offsets in the already validated raw Image and perform targeted offline disassembly against the upstream patched/vulnerable control-flow signatures. Collect only symbol addresses/names and boot identity; do not execute Dirty Frag, load modules, change SELinux, write package state, or alter kernel memory. Production remains frozen.


## 30. ZZIC static compiled-path Dirty Frag audit completed (2026-09-23)

The user-supplied private exact ZZIC `/proc/kallsyms` archive was read offline. It contains 395,241 names/types but ALL addresses were masked to zero despite an authorized `su` read. Do NOT ask to reduce kernel address-masking controls or repeat this collection.

Crucial workaround: independently recovered compressed kallsyms name/token/offset tables from the previously verified private ZZI4/ZZIC raw ARM64 `Image` binaries. Each contained 115,127 kernel-base symbols; the complete static ZZIC name/type list agrees with the first 115,127 user-collected live entries with ZERO mismatches. This validates function boundaries without relying on runtime kernel address disclosure.

Compared the EXACT compiled function bodies against each other (not just BTF):
- `esp_input`: 868 bytes, identical ZZI4/ZZIC, SHA-256 `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95`
- `esp6_input`: 868 bytes, identical, SHA-256 `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1`
- `__ip_append_data`: 3812 bytes, identical, SHA-256 `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01`
- `__ip6_append_data`: 3908 bytes, identical, SHA-256 `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621`

Independently disassembled these ARM64 bodies offline and compared the relevant compiled control flow with Linux upstream reference correction `f4c50a4034e62ab75f1d5cdd191dd5f9c77fdff4`. In both ESP functions, the branch that skips skb_cow_data on a null fragment list lacks the extra shared-frag flag guard. In both UDP append functions, the successful splice path goes to shared bookkeeping without the patch's shared-frag marking. The exact ZZIC BTF confirmed `skb_shared_info.flags` at byte offset 0 and `frag_list` at byte offset 8, so the disassembly observation is structure-grounded.

**Result change:** The reference CVE-2026-43284 four-path correction is **not present** in these exact ZZI4/ZZIC compiled paths. This is stronger than a low stable-version guess. Do NOT equate the absence of this fix with demonstrated exploitability or guaranteed DFReroot success; alternative mitigations, user-space chain requirements and module ABI/integrity remain unverified. DFReroot also still cannot act as independent first-time root by itself.

Detailed reproducible evidence and limits: `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` section 14. A separate private Markdown analysis report was generated for the user. Raw boot images, root-collected kallsyms, boot-specific addresses and private report contents must NOT be committed to the public or private repository without separate authorization.

Next step: source-only DFReroot v2.0.1 persistence/permissions/KernelSU/provenance audit against available exact firmware material, recording gates; no new phone operations needed yet. LAB documentation was updated only, no production code, payload, real-device change or release.


## 30. Exact ZZIC Dirty Frag reference patch is absent in compiled code (2026-09-23)

User returned `RMG_DFR_KALLSYMS_ZZIC.tar.gz` from the exact installed ZZIC boot. The report correctly identifies SM-S938B/pa3q/ZZIC and captures the complete `/proc/kallsyms`, but Samsung/kernel hardening masks all addresses to zero even through the existing root read. This is not a blocker.

Offline analysis used the exact previously validated ZZI4/ZZIC raw Images plus embedded kallsyms. The embedded base-kernel table recovers 115,127 symbols and matches the first 115,127 live ZZIC kallsyms symbol names with **zero mismatches**, providing trustworthy build-specific function boundaries without live kernel addresses.

The four functions modified by the merged CVE-2026-43284 Dirty Frag fix are byte-identical between exact ZZI4 and exact ZZIC:
- `esp_input`: 868 bytes, SHA-256 `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95`;
- `esp6_input`: 868 bytes, SHA-256 `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1`;
- `__ip_append_data`: 3812 bytes, SHA-256 `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01`;
- `__ip6_append_data`: 3908 bytes, SHA-256 `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621`.

AArch64 disassembly was manually checked against the published merged Dirty Frag fix. ESP4/ESP6 retain the pre-fix frag-list-only skip-COW condition (no added shared-frag condition), and IPv4/IPv6 append-data retain the pre-fix relevant return path without the added shared-frag flag marking. Therefore the **reference four-path mitigation is absent** in both exact ZZI4 and exact installed ZZIC compiled code. This supersedes the earlier patch-state UNKNOWN status.

Record the distinction precisely:
- exact patch state: `REFERENCE_FIX_ABSENT` (high confidence static finding);
- vulnerable reference code structure: present;
- end-to-end DFReroot exploitability/stability: **UNVERIFIED**;
- no live exploit, module loading, SELinux modification, shared-UID/signature injection, or package-state changes have been performed.

Next research gate is no longer "is the Dirty Frag fix present?". Instead, audit DFReroot runtime prerequisites against exact One UI 9: system_server/network_stack process-hop assumptions, CAP/SELinux constraints, bundled module compatibility/provenance, bundled ksud interaction with RMG's pinned KernelSU, persistence rollback and boot-safety. Keep this LAB-only and do not modify production or current RMG root paths.


Read-only runtime prerequisite collector added after the exact static patch verdict:
- `tools/dfreroot/collect_runtime_prereqs.py` records exact build identity, discovers `com.android.networkstack.process`, reads its UID/capability/SELinux context through already-authorized root only when needed, and records network-stack package metadata.
- It performs no exploit, package mutation, SELinux change, module load, kernel write, or reboot.
- Purpose: validate DFReroot's upstream assumptions (process name, UID 1073, CAP_NET_ADMIN and SELinux domain) on exact One UI 9 before considering any runnable integration.


## 30. Masked live kallsyms resolved offline; original ESP shared-frag guard absent

2026-09-23 user uploaded `RMG_DFR_KALLSYMS_ZZIC.tar.gz` from existing authorized KernelSU `su`; correct exact ZZIC identity but **all** symbols, including seven targets, had zeroed virtual addresses. This is a kernel address-disclosure restriction of undetermined exact mechanism; do not request attempts to disable or alter `kptr_restrict`/SELinux or new root paths.

A private, source-only independent offline analysis resolved the problem. Both already-validated ZZI4 and ZZIC raw ARM64 Images contain embedded compressed kallsyms; 115,127 core symbol names were recovered in matching order, agreeing exactly with the corresponding initial core segment of the captured runtime list. `CONFIG_KALLSYMS_BASE_RELATIVE=y` offset tables were independently parsed. Select four next-distinct-symbol windows and SHA-256 compare across the two builds:

- `esp_input`, Image offset `0x101803c`, 868 bytes, both SHA-256 `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95`.
- `esp6_input`, `0x10acffc`, 868 bytes, both `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1`.
- `__ip_append_data`, `0xf9963c`, 3812 bytes, both `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01`.
- `__ip6_append_data`, `0x105a538`, 3908 bytes, both `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621`.

**All four matched byte-for-byte** between ZZI4 and ZZIC; this is more specific than identical BTF or whole-image hashes.

BTF field layouts recovered from the exact Samsung image (matching in both): `sk_buff.data_len=0x74`, `end=0xcc`, `head=0xd0`; `skb_shared_info.flags=0`, `nr_frags=2`, `frag_list=8` (all offsets in bytes). An offline ARM64 disassembly of both ESP functions shows that the non-linear fast path checks `skb_shared_info.frag_list` and, when empty, branches directly to `nr_frags`, without examining `skb_shared_info.flags`. The public merged Dirty Frag shared-frag fix explicitly adds a negative `skb_has_shared_frag` check in this same branch, which would examine the flags. **Bounded verdict: the specific additional upstream ESP shared-frag guard is absent from both ZZI4 and ZZIC**; this is evidence from actual compiled code and matched vendor BTF, not from the older kernel version string.

Do **not** claim the complete CVE-2026-43284 is proven exploitable, or that DFReroot supports the exact ZZIC device. Remaining: static review of the two UDP producers, alternative Samsung mitigations/reachability, installer/SELinux/third-party binary provenance and custom KernelSU compatibility. User has only a primary S25 Ultra; persistent system changes and live exploitation are deferred.

New LAB-only standard-library utility: `tools/dfreroot/offline_kallsyms_compare.py`; synthetic tests `tests/test_dfreroot_offline_kallsyms_compare.py`. It recovers the firmware's original embedded table offline even if the live procfs address column is masked; never calls device commands or runs exploits. CI https://github.com/igorcv88/RMGLabs/actions/runs/35910198245 completed successfully. The equivalent private real-data analysis was performed separately offline; do not incorrectly represent the new CLI as privately executed or treat CI as hardware testing. Updated evidence and boundaries: `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` section 14. User archive and complete static analysis remain private and **not** committed to GitHub. All original LAB Manual/Auto Root and native payloads preserved, production frozen.


## 31. Runtime collector multi-user package listing correction

User ran the read-only DFR runtime preflight in Termux and received `java.lang.SecurityException: Permission Denial: runListPackages ... asks to run as user 150 but is calling from uid u0a492; requires INTERACT_ACROSS_USERS_FULL` from `cmd package list packages -U`. This is an Android package-manager **multi-user permission issue**, not a failure of Dirty Frag, root acquisition or network_stack capability. The independent network_stack `/proc` read (step 2) had already executed. The shell script explicitly tolerated the step-3 package-list failure, so its final step may still have completed; inspect its existing files before rerunning anything.

Fixed the LAB-only maintained `tools/dfreroot/collect_runtime_prereqs.py` to perform the *read-only* package query via existing authorized `su` and explicit `--user 0`; similarly, `dumpsys package` runs via existing `su` to avoid the ordinary app permission error, with captured return codes/errors instead of silent success. No privileged state modification, exploit execution, new kernel privileges or production changes.

For the previously run ad-hoc shell snippet, recover in-place without recapturing symbols or re-running extraction: `su -c 'cmd package list packages -U --user 0' | grep -i networkstack` and `su -c 'dumpsys package' | grep -i networkstack`, checking the command exit status separately from grep. If `--user 0` is unsupported or the root context is blocked, preserve error and report metadata as inaccessible; never request INTERACT_ACROSS_USERS_FULL grants or alter system policy. The substantive runtime outcome (actual network_stack process UID/CapEff/SELinux) remains unverified until the user supplies the corresponding preflight outputs.


## 32. Real network_stack runtime prerequisites verified on user's S25 Ultra

The user uploaded the corrected private `RMG_DFR_RUNTIME_PREREQS_ZZIC.tar.gz`. It contains a read-only process status/SELinux capture and corrected root+`--user 0` package/dumpsys results; both stderr/error files are empty. The archive does not contain a fresh build-identity file; link its environment to the user's surrounding exact-ZZIC session and separately verified earlier archive rather than pretending the latest TAR proves its own build identity.

Real findings:
- Exact upstream target process **`com.android.networkstack.process`** is present.
- Its effective/real/saved/fs UID and GID are **1073**, its SELinux domain is **`u:r:network_stack:s0`**.
- `CapEff = 0x800003c00`, including **`CAP_NET_ADMIN` bit 12**; also bits 10, 11, 13, 35. `Seccomp=2`, 1 seccomp filter, `NoNewPrivs=0`.
- `com.google.android.networkstack` has package UID **1073**. `com.samsung.android.networkstack` is a separate UID **10292** Samsung overlay, NOT the running network-stack main process; the package dump confirms overlay targeting the Google package and Google's shared networkstack UID.
- Existing authorized root with explicit user0 solved the ordinary Termux app UID's user150 package-list `INTERACT_ACROSS_USERS_FULL` error; no privileged policy change or cross-user permission grant.
- The key upstream StageHop hardcoded **process name, UID, CAP_NET_ADMIN prerequisites now pass on this real device**. This does not prove reflection dispatch, ability to load native libraries in the observed SELinux domain, usable XFRM socket permissions or exploit success. Do not conflate passed prerequisites with working DFReroot.

Pinned upstream DFReroot source audit shows the remaining steps include `android.uid.system` app persistence by editing packages signing history, privileged system-server hop, bundled `ksud` staging and an LKM that changes SELinux enforcement. These remain unverified, potentially disruptive and outside authorization for a primary phone. Do not change the validated pinned RMG KernelSU, Android package DB or production.

The detailed bounded findings, explicit upstream pinned-source links and safety gates were recorded in `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` section 15. The report captures only necessary generalized process and UID/capability results; original user archive and full private outputs were NOT uploaded to GitHub. Continue **offline-only** comparison/source audit and developer documentation; the latest independently established binary finding on the exact kernel is absence of the *specific upstream ESP shared-frag guard*, whereas a complete independently justified UDP path verdict and end-to-end DFReroot compatibility are still outstanding. No more device collection is required at this point.


## 33. DFReroot v2.0.1 static integration audit — partial handoff

2026-09-23 partial source-only continuation, no on-device changes, no APK or payload release. Read `AGENTS.md`, this handoff, and `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` before continuing. Upstream DFReroot pinned at `polygraphene/DFReroot@9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77` (v2.0.1). **This section documents partial findings; detailed end-to-end compatibility is not yet established.**

Confirmed earlier: exact OTA-recovered ZZI4/ZZIC `Image` hashes and embedded kallsyms permit exact offline symbol mapping. Compiled ESP4 and ESP6 branches **lack the upstream Dirty Frag shared-frag guard** on both images. The four selected function windows are byte-identical ZZI4/ZZIC, but full independent UDP patch semantics, alternative Samsung mitigations, exploit reachability and DFReroot end-to-end success remain unverified. The user-uploaded read-only runtime archive shows `com.android.networkstack.process`, UID 1073, effective `CAP_NET_ADMIN`, SELinux `u:r:network_stack:s0`; the earlier Termux user150 package-list denial was solved using authorized root and explicit user0.

NEW partial pinned-upstream audit:
1. `StageHop.kt` requires an `android.uid.system` process-hosted app, reflects into `ActivityManagerService`/`ProcessRecord`, looks for `com.android.networkstack.process` UID 1073 and an `IApplicationThread.scheduleReceiver` overload with **12 arguments selected only by name/arity**. The process name/UID/capability prerequisites pass on the exact device, but the actual Android17/OneUI9 method signature, privileged cross-process receiver dispatch, native library loading, XFRM SELinux policy, and seccomp behavior were **not** proven.
2. The upstream installer modifies the high-impact `/data/system/packages.xml` system shared-user signing history. `PackagesXml.kt` backs up **once**, tries direct overwrite then file-rename fallback, and uses best-effort chmod/chown and restorecon with failures logged rather than all enforced. Backup freshness and transactional rollback are not assured. This is not suitable for experimental deployment on the user's only primary phone; upstream public Issue #4 reports Android10 package-manager file-permission damage. No installer action was performed.
3. The upstream manifest explicitly declares `android:sharedUserId="android.uid.system"` and `android:process="system"`; no standard boot receiver was identified in that manifest, so persistence of the app is **not** hands-free auto-reroot. Further boot-start orchestration would be a separate design project.
4. Pinned upstream includes an opaque binary `app/src/main/assets/ksud` (~6,014,920 bytes; git blob `f5f95a84c37f729f7a86b754f362f34d2f0b97e9`), plus several prebuilt `.ko` modules. The pinned generic `dirtyfrag-android15-6.6.ko` is 5,656 bytes (git blob `f35f3741dbcbd4d2f25672802f2675cbc958ca55`). A git blob ID is **not** an independently audited binary SHA-256 or a reproducibility receipt. The upstream README points to a custom `polygraphene/KernelSU` `kdp-612-3.3.0` branch; do **not** substitute it for the RMG custom pinned KernelSU.
5. In `exp.c` `select_ko_image()` selects module only by the substring `android15` extracted from `uname` and kernel `6.6`; it contains a same-major/minor fallback. This would select the generic android15-6.6 module for this exact Android17 phone's `6.6.127-android15-...` kernel **without proving Samsung ZZIC ABI/vermagic/export/BTF compatibility**; fail closed in any future design. `dirtyfrag-lkm/dirtyfrag.c` dynamically searches for `kallsyms_lookup_name`, assumes `selinux_state.enforcing` is the first byte, and writes it to zero, which would disable SELinux enforcement if reached; this is incompatible with a non-invasive validation gate.
6. Public upstream Issues #2 (Samsung S24 Ultra) and #3 (S26) report unsuccessful second-stage outcomes despite earlier successful initial stages. Issue #3 specifically reports a received networkstack controller binder followed by a later patch failure: hop viability does **not** establish patch/runtime success. Upstream repo API returned no declared license and the inspected tree has no root LICENSE; audit licensing before copying implementation or bundling upstream artifacts.
7. Upstream 2.0.1 GitHub release supplies SHA-256 metadata for the APKs. Those checksums are release-download integrity references, NOT proof of audited embedded binaries or a source-reproducible build.

NEXT (offline only): finish independent static UDP function comparison against exact compiled ZZIC behavior; assess alternative OEM mitigations; audit pinned upstream installer rollback/ABX edge cases and module/ksud provenance without copying GPL/unknown-license code into RMG; design isolated policy/gates showing PASS (observed name/UID/CAP) vs UNKNOWN (all executable/persistent stages). Do **not** run Dirty Frag, inject shared-UID signing history, load upstream module, modify SELinux, replace KernelSU, or touch production. No new phone collection is currently necessary.

## 34. DFReroot pinned ELF/lifecycle second pass (2026-09-23; offline only)

A further source-only audit of `polygraphene/DFReroot@9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77` identified a NEW exact-binary fact. The shipped `dirtyfrag-android15-6.6.ko` (Git blob `f35f3741dbcbd4d2f25672802f2675cbc958ca55`, 5656 bytes, independently decoded SHA-256 `6658df7da8b2e90a9d15dd551cbdc7a2405d707fdd13ee8892a1d7c635d884c0`) is ELF64 AArch64 with `vermagic=6.6.127-4k-g46a034eca005-dirty SMP preempt mod_unload modversions aarch64`, unlike exact ZZIC `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`. `__versions` **exists but is empty**; `.BTF` is absent. This fails our strict exact-target provenance gate, but **does not prove runtime rejection**: Linux v6.6's `same_magic` may ignore the release prefix when `__versions` is present, even empty. Samsung-specific loader/ABI behavior remains UNKNOWN. No module was loaded.

Additional pinned-source safety findings: DFReroot `MainActivity.onCreate` stages opaque upstream `ksud` to `/data/system` automatically, without hash/pin comparison, falling back to a manager `libksud.so` if needed; thus simply launching it is not a read-only test. Its dynamically registered `EVIL_ACTION` receiver is exported and accepts a `CONTROLLER` Binder without explicit sender validation; the previous controller reference is not cleared for each run. Installer direct file overwrite, one-time backup, best-effort chmod/chown/restorecon and no post-commit reread mean neither mutation nor rollback is transaction-safe. Upstream module build is not fully provenance-pinned, and its license note does not license the entire repo; upstream repo license remains unidentified. These are static code observations, not live exploit results.

Status stays: PASS for observed real network_stack process/UID/CAP; exact compiled reference ESP guard absence established previously; UDP independently repeatable instruction-level audit deferred because private raw Images are not available **in this pass**, despite prior report's UDP interpretation and verified cross-build hashes. UNKNOWN for Samsung alternative mitigations, reflected Android17 dispatch, full XFRM/SELinux path, module loader, and RMG KernelSU interoperability. Do not replace RMG KernelSU, patch production, use upstream APK/installer on the primary device, execute Dirty Frag or request fresh phone capture. Full new evidence, source links, subtleties and gate matrix are in `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` §16.

Per user's production freeze, this documentation update is LAB ONLY; mirroring the new HANDOFF section to production remains deliberately deferred until authorized.


## 35. Independent UDP producer call-site proof, full four-site reference fix absent

2026-09-23 continuation, **offline only**. The previous sections 33–34 established upstream DFReroot v2.0.1 source risks and a module/vermagic exact-target mismatch; section 30 independently identified missing reference ESP shared-frag fast-path guards. The missing independently reproducible UDP site analysis was completed during this iteration using only pre-existing private exact kernel Images and read-only captured `kallsyms` (no new phone collection).

New LAB-only deliverables: `tools/dfreroot/offline_udp_callsite_evidence.py` and eight synthetic tests in `tests/test_dfreroot_offline_udp_callsite_evidence.py`, included in isolated DFR research CI. CI https://github.com/igorcv88/RMGLabs/actions/runs/35914075774 completed successfully (software-only). A separate real-data **private off-device invocation** with ZZI4 Image SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`, ZZIC Image SHA-256 `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`, and the earlier private kallsyms archive succeeded.

The exact validated embedded symbols identify one direct AArch64 BL to `skb_splice_from_iter` in each producer's next-distinct-symbol function window. The immediate nonerror return paths of `__ip_append_data` and `__ip6_append_data` store/test the call result, update copy/accounting and branch directly into their preexisting common loops **without the additional `SKBFL_SHARED_FRAG` marking added by the public upstream Dirty Frag patch**. The unique call site, contiguous post-call instructions and entire selected UDP function window agree byte-for-byte between exact ZZI4/ZZIC. This independent site check corroborates the earlier private manual UDP assessment in `_dfr_private/static_summary.json`. Separately the previously checked compiled ESP4/ESP6 skip-COW branch lacks the upstream shared-frag guard.

**NARROW PATCH VERDICT:** all FOUR exact *reference merged fix additions* are absent at their corresponding compiled sites on ZZI4 and ZZIC. This is **NOT** evidence that Samsung lacks every possible alternate mitigation or that the exploit/DFReroot is reachable or stable. The automated UDP CLI intentionally marks patch verdict `MANUAL_REVIEW_REQUIRED`; the conclusion relies on a separate manual ARM64/source-diff review, not on CI or hash equality alone. Do not extend to CZG3 or ZZHL.

The independent end-to-end compatibility gate remains **NOT CLEARED**. Prior real capture PASS: network_stack process, UID1073, CAP_NET_ADMIN. Unverified: Android17 reflected scheduleReceiver dispatch, sender-authenticated Binder delivery, XFRM SELinux/seccomp operations, runtime exploit and Samsung alternate mitigations. Fail-closed integration blockers: upstream system shared-user packages.xml mutation has no device-proven transactional rollback; generic module vermagic is not exact ZZIC provenance (not a claim of guaranteed loader rejection); opaque auto-staged upstream ksud not vetted against pinned custom RMG KernelSU; upstream repo licensing/third-party source rights unsettled.

Detailed evidence and status matrix: `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md` §17. Private full research report `DFR_ZZIC_UDP_AND_INTEGRATION_AUDIT_20260923.md` and raw `_dfr_private/udp_callsite_evidence.json` exist **only** as private conversation artifacts, not GitHub. Do not upload private images/kallsyms/call-site dumps. Next useful work: inspect alternate mitigations in Samsung `skb_splice_from_iter` / callee chain and isolate Android17 API contracts from available non-device sources. No extra user phone operations, build of runnable DFR, payload mutation, production promotion, package database edit, module loading, SELinux change or release.


Additional §35 offline helper-chain evidence: exact `skb_splice_from_iter` (656 B), `skb_append_pagefrags` (344 B), and `__skb_zcopy_downgrade_managed` (168 B) symbol windows are separately byte-identical ZZI4/ZZIC. Private ARM64 review found `skb_splice_from_iter` calls `skb_append_pagefrags`; that helper reads `skb_shared_info.flags` but no inline OR to mark shared-frag was observed, and the downgrade helper AND-clears a bit instead of adding the shared-frag marking. This narrows only the immediate helper chain; global OEM mitigation and end-to-end DFReroot remain UNVERIFIED. The expanded private report was updated; no new phone tests.


## 36. Existing exact-ZZIC RMG KernelSU reusable; read-only preflight implemented

2026-09-23 specific user request: move from repeating feasibility analysis to concrete integration work reusing the **existing RMG** custom KernelSU instead of using the opaque upstream DFReroot daemon. Rechecked current GitHub code and LAB payload repository, not only old handoffs.

Actual existing LAB exact-ZZIC pair in `igorcv88/RMGLabs-Payloads/main`:
- `kernelsu/ksud-pa3q-S938BXXUCZZIC-kdp-v3.3.0` SHA-256 `9a3ccc16e079895624a61cef8cb15bfec2b938fa99d69d9573c3bbfb7e1e10c5`;
- `kernelsu/android15-6.6_kernelsu-pa3q-S938BXXUCZZIC-kdp-v3.3.0.ko` SHA-256 `da73e7e23e0bc6d53ea051aa920d2391930a3a708dd834a4283f9ea45aa15bc4`.
The dedicated `RMGLabs-Payloads/.github/workflows/build-zzic-exact-port.yml` embeds the exact module in the pinned custom RMG daemon. These are actual existing assets; it is WRONG to imply RMG still needs to develop an exact ZZIC KernelSU pair or must adopt the upstream generic DFReroot module and opaque daemon. Upstream's `dirtyfrag*.ko` is a different temporary SELinux-changing module, NOT the KernelSU module; exact RMG KSU assets cannot simply replace it.

LAB-only isolated implementation branch: `research/zzic-reuse-existing-ksu-preflight`, independent of prior DFR offline PR #14. Files:
- `app/src/main/java/dev/busung/s25uroot/DfrKsuReuseGate.kt`: pure fail-closed decision and *explicit read-only diagnostic* `inspectAlreadyActive(context, profile, daemonFile)` reusing exact device/profile checks, existing file size/SHA verifier, current kernel boot identity, `KernelSuRuntime` and the existing PID1 namespace current-boot marker from `KernelSuGlobalReadiness`. Returns ALREADY_READY **only** if existing KSU is already active and fully current-boot ready. Does NOT stage/reload anything or invoke any exploit. Preserves Manual/Auto root hot paths unchanged.
- `app/src/test/java/dev/busung/s25uroot/DfrKsuReuseGateTest.kt`: exact-target/pin/daemon-file/boot-id/current-ready positive/negative unit tests.
- `.github/workflows/dfr-ksu-reuse-gate.yml`: isolated app Kotlin unit tests on pull requests; verify final CI status.
- `docs/DFR_EXISTING_RMG_KSU_REUSE_IMPLEMENTATION.md`: commands for read-only current KSU root/boot marker inspection with actual Termux and interactive `gh auth login` for the private repo.

Critical scope and truthfulness: This implements and tests the **existing-KernelSU-reuse/ready gate**, not a functioning Dirty Frag exploit route or new privileged bootstrap. The currently bundled `app/src/main/res/raw/autoroot_target_v3.json` in main does NOT yet contain the ZZIC profile; the gate will fail closed until a correct exact ZZIC `TargetProfile` is selected from verified remote LAB payload/feed or separately implemented in a reviewed feed/bundle change. Do not silently alias ZZI4. The pinned daemon SHA in this experimental gate must be updated only in an intentional reviewed provenance change, not when an arbitrary binary arrives. A negative readiness result is NOT permission to retry an exploit. Production and payload repositories untouched.

Missing actual *Dirty Frag* executable chain remains independently unverified: privileged process hop on exact Samsung Android17, SELinux/NETLINK_XFRM/seccomp, alternative OEM fixes, authenticated bootstrap-handoff semantics, safe persistence/rollback and isolated hardware trials. Existing GhostLock-specific helper principal must not be assumed interchangeable. No runnable DFReroot exploit or persistence modifications made on the primary phone. Do not merge the experimental research gate as a claim of a working Dirty Frag port.


## 37. PR #15 expanded into an executable read-only diagnostic and consolidated offline audit (2026-09-23)

Scope and original root baseline: LAB branch `research/zzic-reuse-existing-ksu-preflight`, PR
https://github.com/igorcv88/RMGLabs/pull/15 . No production changes, no LAB
payload modifications, no changes to existing Manual/Auto Root hot paths, no
release or APK publication and **no new runnable DFReroot exploit/persistence**.

The PR #15 isolated Android CI originally failed BEFORE Gradle because its
workflow assumed `ANDROID_HOME` was already populated on the runner; an older
setup action also requested the removed legacy Android SDK `tools` package.
The isolated gate now owns the ONE Android job. The independent offline
audit workflow runs Python-only; a temporary duplicated Android CI job was
removed after the original isolated gate proved usable. The Android job
explicitly installs pinned command-line tools (version 15859902), the
**actual published Android 17 platform package** `platforms;android-37.0`
(not nonexistent `platforms;android-37`), build-tools 37.0.0, CMake 3.22.1
and JDK21 with `${{ github.workspace }}/.android-sdk` as its SDK root. The
job runs the full existing plus new Android/JUnit unit suite, `lintDebug`
and assembles an **unsigned debug APK inside CI only**. This is NOT a release
or deployment to the actual phone. An earlier narrower selected-JUnit
Android compilation/test run already passed at head
`3b2a70efa4c10b0801da51059abbafa621d5ef8b`:
https://github.com/igorcv88/RMGLabs/actions/runs/35920916832 .
Do not claim the expanded final-head build passed before checking its run.

New **separate user-action diagnostic** (not called from Auto Root):
- `DfrZzicDiagnostics.kt`: diagnostics-only exact 11-field ZZIC firmware
  match and size/SHA-256 receipts from reviewed LAB payload manifest
  `support/targets-v3.json` blob `e1eabb5762efb0f90fcc720e5efa372a462d073b`.
  Explicitly selected local `ksud` and KernelSU `.ko` documents are streamed
  and hash-checked independently. The currently bundled Auto Root manifest
  remains untouched and no ZZIC-to-ZZI4 alias is introduced.
- `DfrKsuDiagnosticsActivity.kt` plus isolated independent launcher
  `AndroidManifest.xml` entry. The user explicitly chooses optional files
  and taps `Run read-only preflight`; the UI reports exact firmware, each
  artifact state (NOT_SUPPLIED/UNAVAILABLE/MISMATCH/VERIFIED), current kernel
  boot ID, observed existing KernelSU control, current-boot RMG marker and
  the existing PID1 namespace/global-readiness probe. Readiness is
  independent of whether a copy of the `ksud` binary is available locally.
  The stricter `DfrKsuReuseGate` result is also displayed and requires
  the pinned local `ksud` receipt for `ALREADY_READY`.
- Runtime marker verification uses the already-existing, READ-ONLY
  `KernelSuGlobalReadiness.command` only with an existing KernelSU app
  `su` grant or Shizuku-authenticated root transport. It deliberately does
  not assume that the unrelated GhostLock bootstrap helper accepts the
  identity/authentication of an experimental DFR stage.
- `DfrIntegrationContract.kt`: typed five-layer, software-only integration
  decision boundary (exact target/artifact receipts; first-stage proof;
  privileged principal/authentication; existing KernelSU status; same-boot
  readiness). No state permits unvalidated alternate helper bootstrap,
  even with positive simulated evidence. Unit tests cover negative and
  simulated positive transitions.
- `tools/dfreroot/termux_existing_zzic_ksu_preflight.sh`: executable
  independent diagnostic using only local device properties/boot ID,
  optional exact RMG `--ksud` and `--module` paths, `sha256sum`, and
  existing read-only privileged marker/ns observations, fail-closed. Does
  not require GitHub once available on the device. Syntax and synthetic
  error cases are exercised by `tests/test_dfreroot_termux_preflight.py`.

PR #14 `audit/dfreroot-zzic-final-gates-20260923` was inspected; its most
recent `offline_lkm_contract.py`, 13 tests, final bounded audit report, and
**only the newer README suffix** were incorporated into PR #15 without
overwriting previous files. The offline audit CI now runs eight suites: six earlier read-only/offline
suites, independent Termux verification, and a four-test **static
experimental isolation regression guard** against accidental helper,
bootstrap or exploit entrypoints. The 53-test offline suite passed
on https://github.com/igorcv88/RMGLabs/actions/runs/35921540633
(head `0a37cef14b11286ddda7f7fa2112938ea75b32e5`). This is a software
test pass at that head, distinct from the final Android CI and not functional
validation on hardware. PR #14's original head had its
own successful independent offline run
https://github.com/igorcv88/RMGLabs/actions/runs/35916231597 .
Keep PRs unmerged until code and new CI are reviewed.

Termux after cloning/checking out the experimental PR branch:
```bash
pkg install -y bash coreutils
cd ~/RMGLabs
bash tools/dfreroot/termux_existing_zzic_ksu_preflight.sh
# Optional, only when BOTH original files are actually present locally:
bash tools/dfreroot/termux_existing_zzic_ksu_preflight.sh \
  --ksud "$HOME/ksud-pa3q-S938BXXUCZZIC-kdp-v3.3.0" \
  --module "$HOME/android15-6.6_kernelsu-pa3q-S938BXXUCZZIC-kdp-v3.3.0.ko"
```

Invariants: no upstream opaque `ksud`, no generic DFR module treated as
the original RMG KSU module, no automatic fallback/auto-run on boot, no
privilege escalation, package manager DB edits, SELinux changes or test
deployment on the main phone. Remaining hardware/OEM evidence from the
original static reports remains unproven. Do not interpret any simulated
success or offline missing-fix finding as a successful live DFReroot port.

Further boundary correction: the original PR15
`DfrKsuReuseGate.inspectAlreadyActive()` no longer calls
`KernelSuGlobalReadiness.probe(context, boot)` as a fallback (that probe
uses `RootHelperShell` tied to the existing exploit principal). The PR15
diagnostic and original gate now use only the normal existing authorized
app-root or Shizuku-root transport, checking both the exact marker success
line and matching current `boot_id`. The four-source static regression
suite `tests/test_dfreroot_experimental_isolation.py` tests this boundary.
The optional artifact UI explicitly differentiates NOT_SUPPLIED,
UNAVAILABLE (selected but unreadable), MISMATCH (readable but wrong
size/SHA-256), and VERIFIED (readable and exact size/SHA-256). The offline
script remains fail-closed and independent of the app.


## 38. PR #15 final contract refinement and final-head CI rule (2026-09-24)

The existing-KernelSU integration contract now treats a proven **current-boot
KernelSU control + global readiness** as independent of whether the user also
selected local copies of the exact ksud/module for hashing. Exact firmware and
current boot identity remain mandatory. Local artifact receipts remain separate
diagnostic evidence and become mandatory before any hypothetical alternate
bootstrap handoff; they cannot erase an already-proven running KernelSU state.

The diagnostic reuse gate additionally checks the exact daemon filename together
with its pinned SHA-256/source contract, and unit tests pin both exact LAB
artifact filenames, sizes, hashes and LAB repository prefix. No Auto Root,
Manual Root, native exploit, payload repository, production repository, SELinux,
package database, module loading or release path changed.

Implementation run https://github.com/igorcv88/RMGLabs/actions/runs/35949297139
passed the complete app JVM suite, lintDebug and assembleDebug at head
`045f99b0bcac96261d95bca94c4ed117830d4295`; offline run
https://github.com/igorcv88/RMGLabs/actions/runs/35949297126 passed all 53
Python/shell audit tests at the same head. This final refinement intentionally
updates the branch CI path filter so **the final PR head is revalidated** after
this documentation/code commit. The authoritative final-head result is the PR
check itself; do not create another documentation-only commit merely to paste
its run ID here.


## 39. PR #15 merged, review defects corrected, LAB 0.1.36 published (2026-09-24)

PR #15 was merged into LAB `main` as
`8d89d9f36d117b6d41e4627a75b11dd0f13d1020`. PR #14 was closed without
merge as superseded by the consolidated implementation.

Before merge, two reviewed audit-contract defects and one related collector
schema mismatch were corrected rather than waived:

- `verify_live_identity()` now consumes the actual
  `collect_exact_boot_symbols.py` schema (`uname_a`, `proc_version`,
  exact model/device/build/fingerprint) instead of a synthetic `kernel`
  field that the maintained collector never emitted.
- Runtime evidence now consumes the maintained
  `RMG_DFR_RUNTIME_PREREQS_ZZIC.tar.gz` contract containing
  `runtime_prereqs.json`, validates exact ZZIC identity/kernel, parses the
  captured network-stack status and independently recomputes CAP_NET_ADMIN.
- Diagnostic kernel config evidence now requires exact archive identity,
  manifest SHA-256 entries for both `kernel/config.txt` and
  `kernel/config.gz`, verifies both digests, decompresses the gzip copy and
  requires byte-for-byte parity before reporting module-signing CONFIG facts.

Final PR-head validation:
- DFR/offline run
  https://github.com/igorcv88/RMGLabs/actions/runs/35956661254:
  **60/60 synthetic Python/shell tests PASS**.
- Android/reuse-gate run
  https://github.com/igorcv88/RMGLabs/actions/runs/35956661242:
  full JVM tests, Termux diagnostic suite, lintDebug and assembleDebug PASS.
- Merge-triggered offline run
  https://github.com/igorcv88/RMGLabs/actions/runs/35957037921: PASS.

No LAB payload bytes changed in PR #15. The exact current payload snapshot remains
`igorcv88/RMGLabs-Payloads@2eef505d8a3990ea9b0d977401f2db2e2a346dc9`;
therefore no exploit/KernelSU rebuild or feed rewrite was required. Rebuilding
unchanged native artifacts would create unnecessary provenance churn.

LAB release publication:
- release-order trigger commit:
  `b865b7864a1990a8b707f9ba0f4bd224d02e7d21`;
- release workflow:
  https://github.com/igorcv88/RMGLabs/actions/runs/35957065459 — PASS through
  pinned-source validation, payload provenance, full test/lint/release
  assembly, unsigned isolation/integrity checks, signing, signature
  verification, tag reservation and publication;
- release: https://github.com/igorcv88/RMGLabs/releases/tag/lab-v0.1.36;
- application source pinned to
  `8d89d9f36d117b6d41e4627a75b11dd0f13d1020`;
- payload source pinned to
  `2eef505d8a3990ea9b0d977401f2db2e2a346dc9`;
- APK `RMG-Labs-0.1.36-lab.apk` SHA-256
  `6701ef087df90b5f2bd67ac6f03e4eaab0a87e9f81cbf57e9affdd02b81db8fc`.

This release exposes the explicit read-only ZZIC KernelSU diagnostic and the
software-only integration contracts; it is NOT evidence of live Dirty Frag
privilege acquisition. Manual/Auto Root native exploit/payload bytes were not
changed by this work. Production application/payload implementation remains
frozen pending hardware evidence.

NEXT testing order:
1. On the exact ZZIC phone while the existing RMG KernelSU is already active,
   install LAB 0.1.36 and run **ZZIC KSU diagnostics**. Confirm exact firmware,
   current boot ID, existing KSU control, same-boot marker and PID1/global
   readiness. Repeat independently with the Termux preflight. Optional exact
   local ksud/module files may be selected to verify their size/SHA receipts.
2. Negative fail-closed test only when temporary loss of root is acceptable:
   disable Auto Root, perform a full reboot and confirm diagnostics report
   control/readiness unavailable without launching any alternate exploit.
   Recover using the already-validated RMG route; this is not a Dirty Frag test.
3. Before executable Dirty Frag work on the primary phone, finish off-device
   exact Samsung Android 17 validation of StageHop dispatch/authentication,
   NETLINK_XFRM SELinux policy and network_stack seccomp, and inspect plausible
   OEM alternate mitigations beyond the four absent public reference additions.
4. Implement any future Dirty Frag first-stage only behind an explicit LAB
   manual entry point with no boot receiver/persistence. It must establish
   typed evidence for privileged context and authenticated handoff; it must
   never inherit the GhostLock helper identity by assumption.
5. First executable Dirty Frag/handoff trial should be on isolated/sacrificial
   exact-ZZIC hardware or an equivalently recoverable test environment, one-shot
   and nonpersistent. Do not edit packages.xml, disable SELinux or auto-run it
   at boot on the sole primary phone as the first validation.

Do not promote PR #15 behavior to the production app merely because LAB CI and
release publication passed. Production promotion requires real hardware results
for the relevant behavior.
