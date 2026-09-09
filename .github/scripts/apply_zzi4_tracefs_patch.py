from pathlib import Path

path = Path('app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt')
text = path.read_text()


def once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    text = text.replace(old, new, 1)


once(
    '                executeExploit(payloads.exploit)\n',
    '                executeExploit(payloads.exploit, profile.profileId)\n',
    'profile-aware executeExploit call',
)
once(
    '    private suspend fun executeExploit(payload: File) {\n        val shizuku = shizukuEnabled()\n',
    '    private suspend fun executeExploit(payload: File, profileId: String) {\n'
    '        val strictTracefs = profileId == TRACEFS_ZZI4_PROFILE\n'
    '        val shizuku = shizukuEnabled()\n'
    '        if (strictTracefs) {\n'
    '            require(shizuku) { "Exact ZZI4 tracefs route requires Shizuku shell transport" }\n'
    '            appendLog("[*] exploit route=tracefs-strict attempts=$TRACEFS_EXPLOIT_ATTEMPTS p0_fallback=off")\n'
    '        }\n',
    'strict tracefs prologue',
)

old_shizuku = '''        val process = if (shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            ShizukuController.exec(
                arrayOf("/system/bin/sh", "-c", "true"),
                shizukuEnvironment(bootToken, stagedPayload.absolutePath, helper.absolutePath),
            )
        } else {
'''
new_shizuku = '''        val process = if (shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            ShizukuController.exec(
                arrayOf(
                    helper.absolutePath,
                    "--run-payload",
                    stagedPayload.absolutePath,
                    helper.absolutePath,
                    SHIZUKU_LOG_PATH,
                ),
                shizukuEnvironment(bootToken, helper.absolutePath, strictTracefs),
                "/data/local/tmp",
            )
        } else {
'''
once(old_shizuku, new_shizuku, 'Shizuku helper runner')

old_env = '''            processBuilder.environment().apply {
                put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
                put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
                cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
            }
'''
new_env = '''            processBuilder.environment().apply {
                put(
                    "EXPLOIT_ATTEMPTS",
                    if (strictTracefs) TRACEFS_EXPLOIT_ATTEMPTS else EXPLOIT_ATTEMPTS,
                )
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
                if (strictTracefs) {
                    put("SLIDE_SOURCE", "tracefs")
                } else {
                    put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
                    cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
                }
            }
'''
once(old_env, new_env, 'standalone strict env')

count = text.count('                    cacheP0Offset(bootToken, rawLog)\n')
if count != 1:
    raise SystemExit(f'loop P0 cache: expected 1 match, got {count}')
text = text.replace(
    '                    cacheP0Offset(bootToken, rawLog)\n',
    '                    if (!strictTracefs) cacheP0Offset(bootToken, rawLog)\n',
    1,
)
count = text.count('            cacheP0Offset(bootToken, rawLog)\n')
if count != 1:
    raise SystemExit(f'final P0 cache: expected 1 match, got {count}')
text = text.replace(
    '            cacheP0Offset(bootToken, rawLog)\n',
    '            if (!strictTracefs) cacheP0Offset(bootToken, rawLog)\n',
    1,
)

old_fn = '''    private fun shizukuEnvironment(
        bootToken: String?,
        payloadPath: String,
        helperPath: String,
    ): Array<String> = buildList {
        add("EXPLOIT_ATTEMPTS=$EXPLOIT_ATTEMPTS")
        add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
        add("CVE43499_ROOT_HELPER=$helperPath")
        add("LD_PRELOAD=$payloadPath")
        cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
    }.toTypedArray()
'''
new_fn = '''    private fun shizukuEnvironment(
        bootToken: String?,
        helperPath: String,
        strictTracefs: Boolean,
    ): Array<String> = buildList {
        add(
            "EXPLOIT_ATTEMPTS=" +
                if (strictTracefs) TRACEFS_EXPLOIT_ATTEMPTS else EXPLOIT_ATTEMPTS,
        )
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
        add("CVE43499_ROOT_HELPER=$helperPath")
        if (strictTracefs) {
            add("SLIDE_SOURCE=tracefs")
        } else {
            add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
            cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
        }
    }.toTypedArray()
'''
once(old_fn, new_fn, 'Shizuku strict environment')

once(
    '        private const val EXPLOIT_ATTEMPTS = "24"\n',
    '        private const val TRACEFS_ZZI4_PROFILE = "pa3q-S938BXXUCZZI4"\n'
    '        private const val TRACEFS_EXPLOIT_ATTEMPTS = "4"\n'
    '        private const val EXPLOIT_ATTEMPTS = "24"\n',
    'strict route constants',
)

path.write_text(text)
