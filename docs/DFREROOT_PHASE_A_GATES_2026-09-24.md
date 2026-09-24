# DFReroot Phase A — Android 17 / ZZIC gate disposition (2026-09-24)

Target: **pa3q / SM-S938B / S938BXXUCZZIC**.  
Pinned upstream: `polygraphene/DFReroot@9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77`.  
Scope: Phase A only. No DirtyFrag execution, package-database mutation, module
load, SELinux change, Auto Root integration or production change.

## Status vocabulary

- **CONFIRMED**: exact-target evidence proves the stated fact.
- **SUPPORTED-IN-PRINCIPLE**: public/reference source is compatible with the
  hypothesis, but exact Samsung ZZIC evidence is missing.
- **BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS**: the question is decidable,
  but the exact ZZIC userspace/policy artifact required to decide it was not
  retained in the current evidence set. This is an evidence-retention block,
  not evidence of incompatibility.
- **PASS / FAIL (candidate)**: result for one explicitly enumerated finite A3
  mitigation candidate.
- **UNKNOWN_BY_CONSTRUCTION**: mechanisms outside the explicitly enumerated A3
  candidate set. This is disclosed uncertainty and is not itself a permanent
  roadmap blocker.
- **UNKNOWN**: unresolved evidence outside the Phase A exit decision.

## Evidence already retained

No new device capture was performed in this PR.

Private exact-target material already available:
- exact ZZIC `boot.img`, `vendor_boot.img`, `init_boot.img` and kernel
  config;
- exact live ZZIC kallsyms capture;
- exact NetworkStack runtime prerequisite capture;
- exact ZZI4 raw Image evidence and exact ZZIC raw Image extracted from the
  authenticated boot image.

All machine-checked target constants remain sourced from
`tools/dfreroot/*`; this document does not introduce another hash table.

Existing bounded kernel findings remain unchanged:
- the four public CVE-2026-43284 reference-patch additions are absent at the
  exact compiled ZZI4/ZZIC sites already reviewed;
- `skb_splice_from_iter`, `skb_append_pagefrags`, and
  `__skb_zcopy_downgrade_managed` were reviewed as the immediate helper
  chain;
- those findings do not imply absence of every possible Samsung-specific
  mitigation.

## Reclassification of the Phase A blockers

The previous single word `BLOCKED` mixed two different situations.

A1 and A2 are **decidable exact-build questions** whose required ZZIC
userspace/policy artifacts were not retained. They are therefore classified
as `BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.

The old A3 question — "prove that no alternative Samsung mitigation exists" —
was an open-ended universal negative. It is not a valid finite engineering
gate. A3 is replaced below by a finite candidate matrix with explicit
PASS/FAIL per candidate and `UNKNOWN_BY_CONSTRUCTION` outside that set.

## A1 — StageHop / Android 17 reflection

Pinned DFReroot `StageHop.kt`:
- locates `com.android.networkstack.process` / UID 1073;
- retrieves an `IApplicationThread` candidate through
  `mOnewayThread`, `mThread`, `thread`, or `getOnewayThread()`;
- selects `scheduleReceiver` only by method name and
  `parameterCount == 12`.

Public Android 17 behavior is compatible in principle, but exact Samsung proof
requires the actual ZZIC framework implementation.

Required exact artifacts:
- `/system/framework/services.jar`;
- `/system/framework/framework.jar`;
- related bootclasspath artifacts only if the implementation is split or
  oat/vdex-backed in this build.

Offline decision:
1. inspect `ProcessRecord` and the concrete field/accessor holding the
   `IApplicationThread`;
2. inspect the exact `IApplicationThread$Stub$Proxy.scheduleReceiver`
   overload and argument semantics;
3. verify that the reflection strategy used by pinned StageHop addresses the
   exact Samsung classes/signatures.

Successful static inspection can prove API/reflection compatibility. Actual
receiver delivery still requires later isolated runtime evidence and is not
silently inferred from the jar audit.

**A1 status:
`BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.**

## A2 — NetworkStack native execution gates

Exact runtime evidence already confirms:
- process `com.android.networkstack.process`;
- UID 1073;
- SELinux domain `u:r:network_stack:s0`;
- effective `CAP_NET_ADMIN`;
- `Seccomp=2`.

These are prerequisites, not conclusions about the filters/policies.

### A2a — SELinux / NETLINK_XFRM

The decisive evidence is the policy used by the exact normal ZZIC boot.

Preferred artifact:
- read-only dump of `/sys/fs/selinux/policy` from the already-authorized
  rooted phone.

Important qualification: reading that file may itself be denied by
`security_t:security read_policy`. Failure to read it is an evidence result,
not permission to weaken SELinux.

Second-best evidence if the loaded policy cannot be read:
- exact policy inputs/binaries from
  `/system/etc/selinux/`,
  `/system_ext/etc/selinux/`,
  `/product/etc/selinux/`,
  `/vendor/etc/selinux/` and relevant odm paths.

That fallback must remain labeled weaker than a dump of the policy actually
loaded on the normal boot.

Offline analysis should query the compiled policy for at least:
- `network_stack -> netlink_xfrm_socket` create/read/write and relevant
  netlink-message permissions;
- any xperm/ioctl constraints used by the XFRM path;
- file/map/execute/native-library rules needed by the StageReceiver path.

The retained recovery `sepolicy` is not accepted as evidence for the normal
boot. Samsung `dpolicy` is DEFEX policy data and is not a substitute for the
loaded SELinux policy.

**A2a status:
`BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.**

### A2b — seccomp

Pinned native first-stage code requires operations including:
- Netlink/XFRM `socket`, `bind`, send/receive;
- UDP socket setup/options;
- `pipe`, `vmsplice`, `splice`;
- `clone` and `execve` of `crash_dump64`.

The exact artifact set needed for an offline decision is:
- exact ZZIC
  `/apex/com.android.runtime/lib64/bionic/libc.so`;
- exact `/system/etc/seccomp_policy/*` and equivalent policy locations when
  present;
- supporting bionic/runtime artifacts if the policy generator/table is split
  in this build.

The goal is to recover the exact syscall policy/filter inputs used by the
zygote/application process and derive whether every pre-bootstrap syscall
needed by the pinned first stage is admitted.

`Seccomp=2` proves a filter is installed. It does not prove which calls are
allowed.

**A2b status:
`BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.**

### A2c — StageReceiver / native loader / dlopen

Required exact artifacts:
- `/linkerconfig/ld.config.txt`;
- exact package placement / native-library paths for the candidate LAB APK;
- the exact SELinux evidence from A2a;
- framework/package-loader artifacts from A1 where needed to establish the
  class/native-loader context.

Offline analysis must decide which linker namespace applies when
`StageReceiver` executes in `network_stack`, whether the APK native library
is visible to that namespace, and whether SELinux permits the corresponding
file mapping/loading path.

**A2c status:
`BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.**

## A3 — finite Samsung mitigation candidate matrix

A3 is no longer "prove that no alternative mitigation exists".

The gate is now:

> Enumerate a justified finite candidate set, audit every candidate against
> exact retained evidence, record PASS/FAIL for each candidate, and disclose
> everything outside the set as UNKNOWN_BY_CONSTRUCTION.

Candidate semantics:
- **PASS**: no blocking mitigation was identified for that explicitly defined
  candidate, within the evidence scope stated for the row.
- **FAIL**: the candidate contains a concrete blocking control for the
  contemplated primitive/path.
- **PENDING**: the candidate has been enumerated but has not yet received a
  complete exact-target audit.
- Findings outside the matrix remain **UNKNOWN_BY_CONSTRUCTION** and do not
  silently become PASS.

Finite matrix after the exact retained-artifact audit:

| Candidate | Current state | Exact evidence / qualification |
|---|---|---|
| Four public CVE-2026-43284 reference additions | **PASS** for this candidate | Exact compiled ZZI4/ZZIC sites already reviewed; additions absent. |
| Immediate splice/page-frag helper chain | **PASS** for the bounded reviewed chain | Exact `skb_splice_from_iter`, `skb_append_pagefrags`, `__skb_zcopy_downgrade_managed` review already retained. |
| Direct RKP/KDP/DEFEX/FIVE/PROCA/hypervisor hooks in the seven exact primitive/helper windows | **PASS_BOUNDED** | Exact embedded-kallsyms resolution found no direct BL target with the enumerated security prefixes in those seven windows. This does not exclude indirect/global enforcement. |
| Beta-3-introduced code delta in a finite 12-function XFRM/ESP set | **PASS_BOUNDED** | Eleven windows are byte-identical. `xfrm_add_sa` differs in three words only; the differences are ADRP/ADD string-address materialization immediately feeding the same two `fortify_panic` calls, not a new XFRM guard. The narrow normalization is machine-checked and tested. |
| Exact CONFIG hardening inventory | **INVENTORIED_NOT_A_BLOCK_VERDICT** | Exact ZZIC config confirms RKP, KDP, DEFEX, KNOX_NCM, GKI XFRM hacks, CFI, hardened usercopy/list/freelist, KASAN HW tags and module-signing controls. Presence alone is not evidence that the DirtyFrag path is blocked. |
| DEFEX helper/module/mount semantics | **PENDING_EXACT_POLICY_PATH_REVIEW** | Exact vendor ramdisk contains Samsung `dpolicy`; the remaining finite question is whether its rules block the specific `crash_dump64` → `/vendor/bin/modprobe` → helper/module/mount path used by the pinned chain. |

The finite kernel-side candidates above were evaluated by
`tools/dfreroot/offline_a3_candidate_matrix.py` against exact retained
ZZI4/ZZIC Images, exact live symbol ordering and the authenticated diagnostic
config. Private raw Images/kallsyms/output JSON remain outside GitHub.

Anything not enumerated above remains
`UNKNOWN_BY_CONSTRUCTION`, not evidence of absence and not a perpetual
universal-negative blocker.

## Phase A exit matrix

| Gate | Current Phase A result | Meaning |
|---|---|---|
| A1 StageHop reflection/signature audit | **BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS** | Decidable once exact ZZIC framework/services artifacts are collected. |
| A2a NetworkStack SELinux/XFRM | **BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS** | Decidable from loaded normal-boot policy or weaker exact partition-policy reconstruction. |
| A2b NetworkStack seccomp | **BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS** | Decidable from exact bionic/seccomp artifacts. |
| A2c NetworkStack native-loader path | **BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS** | Decidable from exact linkerconfig + placement + SELinux evidence. |
| A3 finite OEM candidate matrix | **INCOMPLETE** | Four finite kernel-side candidates are PASS/PASS_BOUNDED and CONFIG is inventoried; the defined DEFEX helper/module/mount policy candidate remains PENDING. No universal-negative proof is required. |

## Implemented diagnostic executables

Phase A now has explicit read-only tooling rather than prose-only collection
instructions:

- `collect_zzic_userspace_gates.py`: exact-target-gated Termux collector for
  framework jars, loaded/fallback SELinux policy material, exact Bionic libc,
  seccomp-policy files, linkerconfig, package placement/labels and NetworkStack
  runtime identity. Existing authorized `su` is used for reads only; unreadable
  files are recorded without changing policy or permissions.
- `analyze_zzic_userspace_gates.py`: offline integrity check plus A1 JADX
  signature/reflection audit, exact loaded-policy `sesearch` audit, exact Bionic
  seccomp analysis and linker/package evidence inventory.
- `offline_bionic_seccomp.py`: ELF64/AArch64 parser for attributable
  `arm64_app_filter` / `arm64_app_filter_size`; evaluates each enumerated
  required syscall in the exact classic-BPF tree. A stripped filter symbol is
  reported as unresolved, not replaced with another Android build.
- `offline_a3_candidate_matrix.py`: finite exact-kernel candidate audit
  described above.

Fases B-F remain frozen while these evidence gates are unresolved.

## Collection scope change

The earlier statement that no further device operation was necessary applied
to the kernel-only audit. The unresolved Phase A gates are userspace/policy
questions, so a new **read-only userspace evidence collection** is justified.

Permitted collection characteristics:
- use only the already-authorized rooted environment;
- copy/read exact immutable userspace/policy artifacts;
- no exploit execution;
- no package database edit;
- no module load;
- no SELinux mode change;
- no persistence or boot receiver;
- no Auto Root invocation;
- record per-file SHA-256 and exact build/boot identity with the archive.

The collector should treat inability to read an artifact as a first-class
result rather than attempting to bypass the restriction.

## Stop decision and global state

Phase A still does **not** satisfy its exit criterion because A1/A2 remain
blocked on exact userspace evidence and the finite A3 matrix is incomplete.
Therefore Fases B–F remain frozen.

Consequences:
- no real `DfrBootstrapProvider` is registered;
- `DfrBootstrapFeatureGate.realProviderAvailable()` remains false;
- `DfrIntegrationContract` remains
  `EXPERIMENTAL_EXECUTION_NOT_IMPLEMENTED`;
- Auto Root cannot select DirtyFrag;
- Manual Root, current GhostLock/RMG Auto Root, FOPS/P0/timings, retry safety,
  KernelSU lifecycle and production remain untouched.

Project-level status for the current point in the roadmap:

`DIRTYFRAG_END_TO_END = BLOCKED_ON_USERSPACE_EVIDENCE`

This status is more specific than `UNVERIFIED`: it identifies the immediate
blocking dependency. It does **not** mean the end-to-end chain has been
validated once the artifacts are collected. After evidence collection and
offline analysis, the state must be recomputed from the individual gates.
