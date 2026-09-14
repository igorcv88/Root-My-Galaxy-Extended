from pathlib import Path

path = Path("tools/apply-zzi4-postroot-notification-fix.py")
text = path.read_text(encoding="utf-8")

replacements = {
    "'Reiniciar Zygote após root': 'Soft reboot do KernelSU após root',":
        "'Reiniciar Zygote após o root': 'Soft reboot do KernelSU após o root',",
    "'Depois que o KernelSU for verificado, recria o Zygote e o system_server para que Zygisk e LSPosed possam se conectar sem um reboot completo do userspace do KernelSU.': 'Depois que o KernelSU for verificado, executa o soft reboot nativo do userspace para iniciar módulos, Zygisk e LSPosed na ordem normal do ciclo de vida.',":
        "'Depois que o KernelSU for verificado, recria o Zygote e o system_server para que Zygisk e LSPosed entrem sem executar um soft reboot completo do KernelSU.': 'Depois que o KernelSU for verificado, executa o soft reboot nativo do userspace para iniciar módulos, Zygisk e LSPosed na ordem normal do ciclo de vida.',",
    "'Reiniciando Zygote após root': 'Iniciando soft reboot do KernelSU após root',":
        "'Reiniciando o Zygote após o root': 'Iniciando soft reboot do KernelSU após o root',",
    "'Recria o Zygote e o system_server sem reiniciar o kernel. Use quando Zygisk, LSPosed ou hooks de apps não se conectarem após o root.': 'Recria apenas o Zygote e o system_server. Serve como diagnóstico leve; no ZZI4, prefira o soft reboot do KernelSU quando Zygisk ou LSPosed não iniciar.',":
        "'Recria o Zygote e o system_server sem reiniciar o kernel. Use quando Zygisk, LSPosed ou hooks de aplicativos não entrarem após o root.': 'Recria apenas o Zygote e o system_server. Serve como diagnóstico leve; no ZZI4, prefira o soft reboot do KernelSU quando Zygisk ou LSPosed não iniciar.',",
    "'Executa o reinício nativo do userspace e o ciclo de vida dos módulos do KernelSU. Use quando vários módulos ou mounts precisarem de uma recarga limpa do userspace.': 'Executa o reinício nativo do userspace e o ciclo de vida completo dos módulos do KernelSU. É a opção preferida no ZZI4 quando Zygisk ou LSPosed precisa iniciar de forma limpa com o DEFEX corrigido.',":
        "'Executa o reinício nativo do userspace e o ciclo de módulos do KernelSU. Use quando vários módulos ou mounts precisarem de uma recarga limpa do userspace.': 'Executa o reinício nativo do userspace e o ciclo de vida completo dos módulos do KernelSU. É a opção preferida no ZZI4 quando Zygisk ou LSPosed precisa iniciar de forma limpa com o DEFEX corrigido.',",
}
for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected translation patch once, found {count}: {old[:72]}")
    text = text.replace(old, new, 1)

needle = """text = text.replace(\n    'import android.util.Log\\n',\n    'import android.util.Log\\nimport kotlinx.coroutines.CoroutineScope\\nimport kotlinx.coroutines.Dispatchers\\nimport kotlinx.coroutines.SupervisorJob\\nimport kotlinx.coroutines.launch\\n',\n    1,\n)\nregex_once(\n"""
replacement = needle.replace(")\nregex_once(\n", ")\nwrite(p, text)\nregex_once(\n")
count = text.count(needle)
if count != 1:
    raise SystemExit(f"expected receiver import persistence site once, found {count}")
text = text.replace(needle, replacement, 1)

path.write_text(text, encoding="utf-8")
print("Patched one-shot patcher for current pt-BR strings and receiver imports")
