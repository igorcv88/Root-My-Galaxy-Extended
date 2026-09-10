#!/usr/bin/env python3
from pathlib import Path

path = Path('app/src/test/java/dev/busung/s25uroot/AutoRootShellTransportContractTest.kt')
text = path.read_text(encoding='utf-8')

replacements = {
    'assertFalse(runner.contains("ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))"))':
        'assertFalse(runner.contains("""ksuExec(arrayOf("-c", KernelSuGlobalReadiness.command(bootToken)))"""))',
    'assertTrue(runtime.contains("RootHelperShell.execute(context, "--ksu-info")"))':
        'assertTrue(runtime.contains("""RootHelperShell.execute(context, "--ksu-info")"""))',
}

for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f'expected malformed generated test anchor once: {old}')
    text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
print('Fixed generated Kotlin test quoting')
