# ADENDO OBRIGATÓRIO — Arquitetura DFReroot / Dirty Frag e evidência ZZIC

> Provenance: este adendo foi fornecido pelo mantenedor (2026-09-24) e persistido
> aqui como referência autoritativa de trabalho para a rota DirtyFrag no LAB.
> Complementa o handoff principal. Os **valores machine-checked** (hashes exatos,
> janelas de função, BTF, contrato do LKM, parsing de runtime) continuam tendo
> como fonte de verdade os scripts em `tools/dfreroot/` (ver §44); este documento
> não os substitui e não deve divergir deles silenciosamente. Dumps privados
> (imagens de boot, kallsyms completo, arquivos de runtime) NÃO estão no
> repositório (ver §45). LAB only; nada aqui altera produção, payloads, o hot
> path do exploit RMG atual ou o KernelSU validado.

Antes de estudar ou modificar a integração DirtyFrag, ler integralmente:

- `AGENTS.md`
- `HANDOFF.md`
- `docs/DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md`
- e este adendo.

Não redescobrir fatos já estabelecidos abaixo sem uma razão concreta.
Diferenciar sempre:

- **CONFIRMED** = provado por source audit, material exato ZZIC ou captura real do aparelho.
- **SUPPORTED-IN-PRINCIPLE** = existe a infraestrutura necessária, mas a etapa completa ainda não foi demonstrada.
- **UNKNOWN** = ainda não demonstrado.
- **BLOCKED** = não deve ser tratado como pronto.

## 1. Upstream exato analisado

Referência: `polygraphene/DFReroot`
Commit pinado: `9f1d6cd592d898b42d2e0c2d25ee1577e2aabe77`
Release correspondente: `v2.0.1`

Os arquivos principais auditados incluem:

- `README.md`
- `installer/src/main/java/com/polygraphene/df/installer/InjectMain.kt`
- `installer/src/main/java/com/polygraphene/df/installer/PackagesXml.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/polygraphene/df/reroot/MainActivity.kt`
- `app/src/main/java/com/polygraphene/df/reroot/StageHop.kt`
- `app/src/main/java/com/polygraphene/df/reroot/StageReceiver.kt`
- `app/src/main/java/com/polygraphene/df/reroot/KsudStage.kt`
- `app/src/main/java/org/lsposed/lspromise/DirtyFrag.java`
- `app/src/main/jni/exp.c`
- `app/src/main/jni/stage1.S`
- `dirtyfrag-lkm/dirtyfrag.c`

O próprio upstream descreve DFReroot como um mecanismo de **second-stage root**.
Ele não elimina a necessidade de uma obtenção inicial de root para preparar a
persistência da aplicação privilegiada.

O dispositivo declarado como testado pelo upstream **não** é o nosso ZZIC. O
README declara teste em Galaxy S26 / One UI 8.5. Portanto qualquer
compatibilidade com SM-S938B/ZZIC precisa ser demonstrada independentemente.

## 2. Modelo conceitual upstream

A arquitetura real do DFReroot é:

```
root temporário inicial
        ↓
DFInstaller
        ↓
modificação de packages.xml
        ↓
certificado da aplicação passa a integrar android.uid.system pastSigs
        ↓
soft reboot / PMS relê packages.xml
        ↓
DFReroot instalado como android.uid.system e executado no processo "system"
        ↓
system_server
        ↓
StageHop
        ↓
com.android.networkstack.process
        ↓
libexp.so
        ↓
Dirty Frag / XFRM page-cache corruption
        ↓
patches em arquivos mapeados
        ↓
stage1
        ↓
/vendor/bin/modprobe
        ↓
stage2
        ↓
finit_module()
        ↓
LKM temporário altera estado SELinux
        ↓
namespace/mount bridge
        ↓
ksud
        ↓
KernelSU late-load
```

Essa cadeia possui duas partes conceitualmente diferentes:

1. A **persistência da capacidade de tentar o exploit** após futuros reboots.
   Obtida tornando DFReroot uma aplicação aceita no shared UID de sistema.
2. O **root daquele kernel boot específico**. Precisa ser repetido depois de um
   hard reboot porque o KernelSU/root em memória não sobrevive ao reboot do kernel.

Portanto o nome "persistent root" do upstream significa essencialmente
persistência do **mecanismo de recuperação**, e não persistência do estado de
root do kernel.

## 3. Persistência inicial — DFInstaller

Aplicação instaladora: `com.polygraphene.df.installer`
Entrypoint privilegiado: `InjectMain` (executado depois que outro mecanismo já
forneceu root temporário).

A modificação importante ocorre em `/data/system/packages.xml`.
`PackagesXml.kt` suporta leitura tanto do formato ABX moderno quanto de XML textual.
Alvo padrão: `android.uid.system`.

A aplicação obtém sua própria chave/certificado de assinatura e a introduz no
`pastSigs` do shared-user correspondente. O upstream utiliza `flags="2"` e
adiciona a própria chave **duas vezes** ao `pastSigs`, conforme a semântica de
rotação de certificados/shared UID que o autor pretende explorar.

O código tenta preservar certificados estrangeiros já existentes naquele
`pastSigs`, consolidando-os no mesmo bloco em vez de substituí-los. Há
verificações estruturais antes da alteração (nó raiz esperado, quantidade
plausível de packages, existência do shared-user de destino). O código
explicitamente se recusa a criar um shared-user inexistente.

## 4. Backup e writeback de packages.xml

Backup definido upstream: `/data/system/packages.xml.bak-df-installer`, criado
somente se ainda não existir. É um **backup once**, não um snapshot transacional
renovado a cada operação.

O writeback tenta primeiro escrever diretamente no arquivo original; se falhar,
tenta criar um novo arquivo e fazer rename/swap. Owner/mode inferidos
preferencialmente do backup, com fallback aproximado para `0600 system:system`.
Há chamadas de `restorecon`. Nem toda a sequência constitui uma transação atômica
completa com rollback garantido.

**Para RMG:** o mecanismo upstream de modificação do package database não deve
ser considerado automaticamente seguro só porque possui um `.bak`. O uninstaller
tenta remover apenas os certificados do próprio DFReroot e preservar estrangeiros.
A alteração só é considerada pelo PackageManager depois que PMS relê o arquivo —
por isso o fluxo upstream exige restart do framework/soft reboot antes de instalar
DFReroot como aplicação `android.uid.system`.

## 5. Identidade persistente da aplicação DFReroot

O `AndroidManifest.xml` upstream declara `android:sharedUserId="android.uid.system"`
e `android:process="system"`. Quando a preparação de assinatura funcionou, a
aplicação pode ser carregada no contexto pretendido. Essa identidade é a origem do
acesso necessário ao `system_server` para o próximo estágio — não é detalhe de UI.

O manifest analisado **não** contém um Auto Root hands-free por `BOOT_COMPLETED`.
O upstream fornece persistência da aplicação, mas o mecanismo DirtyFrag ainda
precisa ser disparado. Auto Root no boot seria uma camada adicional de
orquestração **nossa**.

## 6. Staging do ksud upstream

`KsudStage.kt` usa por padrão `/data/system/dfreroot-ksud`. O asset `ksud`
incluído no APK é copiado verbatim com modo `0700`. O upstream **não** verifica
nesse estágio uma identidade criptográfica pinada equivalente à política RMG. Há
fallback que tenta extrair `libksud.so` do KernelSU Manager instalado caso o asset
não tenha sido staged.

**Esse comportamento NÃO deve ser herdado pelo RMG.** O RMG já possui um par
KernelSU exato e validado para ZZIC:

- Daemon RMG: `ksud-pa3q-S938BXXUCZZIC-kdp-v3.3.0`
  SHA-256 `9a3ccc16e079895624a61cef8cb15bfec2b938fa99d69d9573c3bbfb7e1e10c5`
- Módulo RMG: `android15-6.6_kernelsu-pa3q-S938BXXUCZZIC-kdp-v3.3.0.ko`
  SHA-256 `da73e7e23e0bc6d53ea051aa920d2391930a3a708dd834a4283f9ea45aa15bc4`

Não substituir esses artefatos pelo daemon ou KernelSU genérico upstream.

## 7. Entrada da execução DirtyFrag

`MainActivity.runDfAll()` inicia a execução. O upstream primeiro verifica `/dev/df`,
que funciona como mutex/indicador de que os hooks daquele boot já foram armados.
Se existir, a UI se recusa a executar novamente e diz que somente um hard reboot
limpa aquele estado.

Evidência arquitetural importante: o próprio upstream considera **replay da cadeia
de hooks no mesmo kernel boot inadequado** — consistente com a política RMG de não
repetir exploit quando o estado do kernel/processos está modificado ou incerto.

## 8. StageHop: system_server → network_stack

Próxima etapa: `StageHop.hopToNetworkStack()`. Objetivo explícito: escapar das
limitações do `system_server`, que não é contexto adequado para carregar as libs
nativas necessárias, enquanto `com.android.networkstack.process` possui
características úteis (acesso XFRM / CAP_NET_ADMIN).

Constantes upstream: processo `com.android.networkstack.process`, UID esperado `1073`.

No aparelho ZZIC real, **já confirmamos**:

- processo presente: `com.android.networkstack.process`
- UID/GID: `1073`
- SELinux: `u:r:network_stack:s0`
- `CapEff`: `0x800003c00` (inclui `CAP_NET_ADMIN`, bit 12; outros bits observados 10, 11, 13 e 35)
- `Seccomp=2` (1 filtro seccomp)
- `NoNewPrivs=0`
- Package UID do Google NetworkStack: `1073`
- Package Samsung correspondente é um overlay separado (UID `10292`) e NÃO é o
  processo principal usado pela cadeia upstream.

As premissas estáticas de nome do processo, UID e CAP_NET_ADMIN **passam** no
aparelho real. Isso **não** comprova que o StageHop inteiro passa.

## 9. Como StageHop encontra network_stack

`StageHop.kt` obtém `ActivityManagerService` via `ServiceManager`, depois procura o
`ProcessRecord` do processo network_stack. Alternativas para tolerar diferenças
entre builds:

- `getProcessRecordLocked(String, int)`
- `getProcessRecordLocked(String, int, boolean)`
- fallback estrutural: `mProcessList` → `mProcessNames` → lookup por process name + UID.

Depois precisa extrair o `IApplicationThread` associado ao `ProcessRecord`.
Candidatos: campos `mOnewayThread`, `mThread`, `thread`, e fallback método
`getOnewayThread()`. São dependências internas e não-API do Android; Samsung
Android 17 pode alterá-las.

## 10. scheduleReceiver

Após encontrar o application thread do NetworkStack, StageHop procura dinamicamente
um método `scheduleReceiver` com exatamente **12 parâmetros** (`parameterCount == 12`),
e então agenda `StageReceiver`.

Parte crítica ainda **não comprovada** no ZZIC. Process name/UID/CAP_NET_ADMIN
corretos não provam que: a overload existe com essa forma; a assinatura tem a mesma
semântica; a invocation via reflection passa; o BroadcastReceiver é carregado
corretamente naquele processo; verificações de framework/Samsung permitem o dispatch.

Estado ZZIC: processo/UID/capability **CONFIRMED**; dispatch `scheduleReceiver/12`
**UNKNOWN**.

## 11. StageReceiver dentro do NetworkStack

Se StageHop funcionar, `StageReceiver.onReceive()` executa dentro de
`com.android.networkstack.process` e chama `System.loadLibrary("exp")` para carregar
`libexp.so`. No ZZIC ainda é necessário demonstrar que o domínio
`u:r:network_stack:s0` pode de fato carregar a lib nativa da aplicação nesse cenário.

Depois que `libexp.so` é carregada, StageReceiver cria um Binder ("CONTROLLER") que
expõe as operações nativas. Transaction codes upstream:

- `1` → `DirtyFrag.patchMod()`
- `2` → `DirtyFrag.patchLibc()`
- `3` → `DirtyFrag.patchCxx()`
- `4` → `DirtyFrag.createOrphanProcess()`
- `5` → `DirtyFrag.runAll()`

O Binder é enviado de volta à aplicação via broadcast de ação
`com.polygraphene.df.reroot.EVIL`, de modo que a parte rodando no contexto de
sistema controle a execução que acontece no NetworkStack.

## 12. JNI DirtyFrag

A classe Java permanece intencionalmente no package `org.lsposed.lspromise`. Isso
não é cosmético: `exp.c` registra/procura os símbolos JNI usando esse nome. Renomear
a classe sem modificar a implementação nativa quebra o linkage.

A classe expõe `patchMod()`, `patchLibc()`, `patchCxx()`, `createOrphanProcess()`,
`runAll()`. Implementação efetiva em `exp.c` e `stage1.S`.

## 13. Primitive Dirty Frag usado pelo upstream

`exp.c` constrói a primitive em torno de: XFRM; ESP; UDP encapsulation;
NETLINK_XFRM; pipes; `vmsplice`; `splice`; page-cache-backed file data.

O NetworkStack precisa de privilégios suficientes para manipular o estado XFRM (por
isso `CAP_NET_ADMIN` é premissa importante). A primitive usa XFRM Security
Associations para carregar palavras de dados que acabam participando das alterações
pretendidas em páginas de arquivo. Helpers conceituais: `add_xfrm_sa()`,
`do_one_write()`, `patch_file()`. `patch_file()` divide a região em pequenos chunks
e repete a primitive para montar o conteúdo de destino.

Não é simplesmente "escrever kernel memory": usa Dirty Frag para alterar conteúdo de
páginas associadas aos arquivos-alvo e construir uma cadeia posterior de execução.

## 14. Evidência exata do kernel ZZIC relacionada à primitive

Material do kernel ZZIC recuperado e autenticado:

- Raw Image ZZIC: SHA-256 `470d40df59320e01b1449f8dbe962e0d44d735c817b99293dc6da286175ffcf3`
- Raw Image ZZI4: SHA-256 `7811e9413a3928079219347a435eadbfe0241f74ac28c459ad71b5195fdb8aef`
- Tamanho de ambos: `39,115,264 bytes`
- BTF: SHA-256 `e13df32a16b5536c43897542b4dbc2c7082f2aefb91249bc94a06bfc5870950c`
- BTF embedded no ZZIC: offset `0x18aca6c`, tamanho `6,425,607 bytes`

Comparação global dos Images: `1,294,850` bytes diferentes (≈ `3.3103445%`) em
`136,593` ranges contíguos. ZZI4 e ZZIC **não** são kernels globalmente idênticos.

## 15. Kallsyms e janelas das quatro funções Dirty Frag

Captura live de `/proc/kallsyms`: ≈ `395,241` entradas; endereços live todos
mascarados como zero (não tentar desativar essa proteção). Tabelas kallsyms
compressed recuperadas diretamente dos Images: `115,127` símbolos core; nomes
correspondem aos primeiros 115.127 símbolos da captura live com **zero mismatches**.
Isso permitiu limites estáticos confiáveis das funções relevantes.

Janelas exatas ZZIC/ZZI4:

| Função | offset | tamanho | SHA-256 |
|---|---|---|---|
| `esp_input` | `0x101803c` | `868` | `3fd93a0fba3a40625542254a84ed57f0c6750d0e3fb62f90281207af74e7ea95` |
| `esp6_input` | `0x10acffc` | `868` | `4386799d9b0d501ff089dc3c76ee1df39c83eff9a31cf30ede77d12acd4577a1` |
| `__ip_append_data` | `0xf9963c` | `3812` | `d68090fd43061212bdd23f961ab7fd7a496c95b629de3884f1ad52ff01042e01` |
| `__ip6_append_data` | `0x105a538` | `3908` | `1950e21642a6d2b0efbe5382581a557bcb69a7eb0d1ba02adc0d763b7213f621` |

As quatro janelas são byte-for-byte idênticas entre ZZI4 e ZZIC.

## 16. Estado do reference Dirty Frag fix

Análise ARM64 offline das quatro funções. Resultado: o reference four-path
shared-frag fix associado ao Dirty Frag **NÃO** está presente nessas quatro funções
compiladas do ZZIC. Nas funções ESP, o caminho relevante mantém a condição pré-fix
relacionada a `frag_list`, sem a condição adicional de shared-frag do fix upstream.
Nos append paths correspondentes, o comportamento compilado também não mostra a
adição de marking prevista no reference fix.

- `REFERENCE_FIX_ABSENT = CONFIRMED` para as quatro alterações públicas estudadas.
- `END_TO_END_EXPLOITABILITY = UNVERIFIED`.

A ausência do fix de referência NÃO prova que Samsung não tenha mitigação
alternativa ou que toda a cadeia upstream funcione.

## 17. Layouts BTF importantes

O BTF exato confirmou estruturas usadas na análise:

- `skb_shared_info.flags` offset `0`
- `skb_shared_info.nr_frags` offset `2`
- `skb_shared_info.frag_list` offset `8`
- `sk_buff.data_len` offset `0x74`
- `sk_buff.end` offset `0xcc`
- `sk_buff.head` offset `0xd0`

Esses layouts sustentam a interpretação da disassembly e não são inferências de um
kernel 6.6 genérico.

## 18. Config do kernel relevante

Config exato coletado e autenticado confirma:

`CONFIG_XFRM=y`, `CONFIG_XFRM_ALGO=y`, `CONFIG_XFRM_USER=y`, `CONFIG_XFRM_ESP=y`,
`CONFIG_INET_ESP=y`, `CONFIG_INET6_ESP=y`, `CONFIG_MODULES=y`, `CONFIG_MODULE_SIG=y`,
`CONFIG_MODULE_SIG_PROTECT=y`, `CONFIG_KALLSYMS=y`, `CONFIG_KALLSYMS_ALL=y`,
`CONFIG_DEBUG_INFO_BTF=y`, `CONFIG_DEBUG_INFO_DWARF5=y`.

As funcionalidades XFRM/ESP relevantes estão built-in. A ausência de `esp4/esp6` em
`/sys/module` não significa que os recursos não existam.

## 19. patch_ko upstream

A primeira parte de `runAll()` chama `patch_ko()`. Essa etapa primeiro altera a
página correspondente a `/apex/com.android.runtime/bin/crash_dump64`, inserindo um
helper usado pela próxima etapa. O arquivo vendor usado posteriormente é
`/vendor/lib64/libstagefrighthw.so`. O upstream usa o helper recém-introduzido para
transportar a imagem do `.ko` para a página/cache do arquivo vendor correspondente.

A seleção do `.ko` **não é firmware-exata**. Considera: Android common release
extraído de `uname`; kernel major; kernel minor. Tabela upstream com candidatos como
Android 15 / 6.6, Android 16 / 6.12, Android 17 / 6.18, etc. Se não encontrar
exatamente o Android common release mas encontrar o mesmo major/minor, aceita o
primeiro candidato como fallback.

Para nosso kernel `6.6.127-android15-...`, o parser upstream enxerga Android common
family `15`, kernel `6.6`. Portanto selecionaria `dirtyfrag-android15-6.6.ko`. Isso
**não** significa que o módulo seja exato para ZZIC.

## 20. Módulo upstream android15-6.6 auditado

O `.ko` upstream analisado: tamanho `5656 bytes`; SHA-256
`6658df7da8b2e90a9d15dd551cbdc7a2405d707fdd13ee8892a1d7c635d884c0`; ELF AArch64;
vermagic `6.6.127-4k-g46a034eca005-dirty SMP preempt mod_unload modversions aarch64`.

Kernel ZZIC real: `6.6.127-android15-8-p33f4ffe-abogkiS938BXXUCZZIC-4k`. A identidade
não corresponde exatamente. A seção `__versions` existe mas está vazia; `.BTF` não
está presente. Isso reprova o critério de provenance/identidade exata do RMG, mas
isoladamente não prova que o loader Linux rejeitaria o módulo em runtime.

- exact-target provenance: **FAIL**.
- runtime loader acceptance: **UNKNOWN**.

## 21. Função do LKM upstream

`dirtyfrag-lkm/dirtyfrag.c` **NÃO** é KernelSU. É um módulo temporário cuja
finalidade principal é alterar SELinux. Possui um endereço de `sprint_symbol`; usa
esse símbolo como âncora e varre uma região de endereços procurando
`kallsyms_lookup_name`; depois resolve `selinux_state` e altera diretamente o byte do
campo `enforcing` para zero. O módulo então retorna erro deliberadamente no init para
não permanecer carregado (mecanismo transitório para tornar SELinux permissive).

Não confundir com `android15-6.6_kernelsu-pa3q-S938BXXUCZZIC-kdp-v3.3.0.ko`. O módulo
KernelSU RMG e o módulo DirtyFrag upstream têm propósitos totalmente diferentes.

## 22. selinux_state no ZZIC

Análise BTF exata: estrutura `selinux_state` tamanho `128 bytes`, `9` campos;
`enforcing` bit offset `0`; `initialized` bit offset `8`. A premissa estrutural
específica do módulo upstream — `enforcing` como primeiro byte — coincide com o BTF
ZZIC observado. Isso apenas confirma layout; não autoriza o módulo nem prova que
todas as dependências dele são resolvidas.

## 23. patch_libc upstream

Após `patch_ko()`, `runAll()` chama `patch_libc()`. Alvo `/system/lib64/libc.so`,
símbolo `__libc_init`. O upstream encontra local para inserir o payload `stage2`,
substitui a primeira instrução do hook por uma branch AArch64 para esse payload e
prepara uma branch de retorno. A primeira instrução original é copiada para a
estrutura de stage2 para preservar o fluxo.

Duas alterações conceituais: stage2 payload dentro de uma área do arquivo;
trampoline/hook em `__libc_init`. Ambas via a mesma primitive Dirty Frag/page-cache.

## 24. patch_cxx upstream

Após `patch_libc()`, `runAll()` chama `patch_cxx()`. Alvo `/system/lib64/libc++.so`,
símbolo do construtor C++ `std::__1::basic_ostream<...>::sentry` (nome mangled no
código upstream). Payload inserido: `stage1`. Novamente: payload em área adequada;
trampoline AArch64 no hook; preservação da instrução original e branch de retorno.
Prepara execução em um processo posterior com propriedades de privilégio muito
diferentes do NetworkStack.

## 25. stage1.S

`stage1` contém vários gates. Só executa sua cadeia privilegiada quando encontra
UID `0` e TID `1` (contexto de init/thread principal esperado). Mutex `/dev/df`: se
existe, evita rearmar a cadeia. Depois cria um filho e prepara uma transição SELinux
escrevendo em `/proc/self/attr/exec` o contexto `u:r:vendor_modprobe:s0`; em seguida
tenta executar `/vendor/bin/modprobe`. Essa é a ponte entre o hook em libc++ e o
stage2 colocado em libc.

## 26. Por que há stage1 e stage2

A cadeia não vai diretamente NetworkStack → ksud. Usa modificações de page cache em
bibliotecas compartilhadas para alcançar contextos posteriores:

- NetworkStack tem as capacidades necessárias para Dirty Frag.
- Dirty Frag altera código/páginas de arquivos compartilhados.
- O hook em libc++ fornece stage1 num contexto privilegiado apropriado.
- stage1 provoca execução de `/vendor/bin/modprobe` sob o domínio pretendido.
- O hook em libc fornece stage2 quando o processo correspondente inicializa.

Não tratar `StageHop` como se ele próprio já tivesse fornecido root.

## 27. stage2.S

`stage2` valida que o executable path corresponde a `/vendor/bin/modprobe`. Quando
satisfeito, cria o marker `/dev/dfm1`. Depois abre `/vendor/lib64/libstagefrighthw.so`
(conteúdo preparado anteriormente para representar o módulo da cadeia) e chama
`finit_module()` sobre esse fd, para executar o LKM temporário que altera SELinux.

## 28. Namespace e bridge para ksud

Depois do `finit_module`, stage2 continua com a parte de namespace: `unshare(CLONE_NEWNS)`,
depois transforma `/` em mount tree privado (evitar que o próximo bind mount seja
global). Se funciona, cria `/dev/dfm2`. Depois faz bind mount de
`/data/system/dfreroot-ksud` sobre `/system/bin/logcat` dentro do namespace privado.
Se funciona, cria `/dev/dfm3`. Finalmente executa o pathname de `logcat`, que naquele
namespace aponta para o `ksud`. O argumento representa uma execução de KernelSU
late-load e inclui referências a `me.weishu.kernelsu` e ao caminho original staged do
daemon. Se `execve` retornar em vez de substituir o processo, cria `/dev/dfm4`
(failure da etapa final).

## 29. Semântica dos markers

- `/dev/df` — mutex indicando hooks/cadeia já armada naquele boot.
- `/dev/dfm1` — stage2 alcançado no processo esperado.
- `/dev/dfm2` — private mount namespace preparado.
- `/dev/dfm3` — bind mount do ksud sobre o pathname trampoline executável concluído.
- `/dev/dfm4` — `execve` retornou/falhou.

Esses markers **não** são equivalentes à verificação completa do KernelSU.
Especialmente `dfm3` significa que a preparação do bind mount chegou ao ponto
esperado; não prova KernelSU control ativo, módulo correto carregado, version/control
esperado, PID1 namespace readiness, same-boot marker RMG, ou module lifecycle.

## 30. runAll() upstream

```
patch_ko → patch_libc → patch_cxx → trigger orphan process → esperar markers → repetir trigger algumas vezes se necessário
```

O upstream tenta o trigger do processo órfão até **oito** vezes, observando `/dev/df`
e markers posteriores. Se `dfm4` aparece, trata como failure. Se `dfm3` aparece,
declara conclusão/sucesso na UI. Se nada conclusivo após os triggers, retorna estado
de sucesso não comprovado/timeout lógico.

**Para RMG essa definição de sucesso é insuficiente.** O RMG deve continuar até
verificar: KernelSU control; versão/control ID esperados; current kernel boot ID;
PID1 namespace/global readiness; same-boot readiness marker.

## 31. createOrphanProcess

Padrão double-fork para produzir um processo órfão. A finalidade não é "obter root
pelo fork": a criação/saída provoca a cadeia de execução preparada nos arquivos
hookados até o contexto onde stage1 pode atuar. No logging, tratar como **trigger da
cadeia preparada**, não como novo exploit independente.

## 32. Relação com init/modprobe

A cadeia depende de propriedades específicas: stage1 verifica UID 0; stage1 verifica
TID 1; stage1 prepara `vendor_modprobe`; stage2 só executa a parte principal quando o
executable path observado corresponde ao modprobe esperado. Demonstrar a write
primitive no NetworkStack **não** é suficiente: o encadeamento até init/vendor_modprobe
é uma dependência própria da implementação upstream.

## 33. KernelSU upstream versus KernelSU RMG

O upstream recomenda um build do fork `polygraphene/KernelSU`, branch `kdp-612-3.3.0`
— não automaticamente equivalente ao KernelSU RMG. Além disso, o asset `ksud` upstream
é um binário pré-compilado opaco para nosso provenance gate; não deve ser importado.

A adaptação RMG precisa verificar se nosso daemon exato aceita a modalidade de
invocation necessária ao handoff DFR. Não assumir que uma CLI semelhante implica
identidade funcional.

- RMG KSU artifacts: **CONFIRMED**.
- Upstream → RMG ksud invocation compatibility: **UNKNOWN** até teste específico.

## 34. Resultado recente do nosso Manual Root e relevância

O v36 já demonstrou no ZZIC: bootstrap root obtido; KernelSU control version `32601`;
same-boot marker correto; PID1 mount namespace correto; global readiness; Apply
Modules/soft reboot.

O pipeline pós-bootstrap RMG está **comprovado** pelo exploit RMG atual. Isso **não**
demonstra DFR, mas reduz o escopo da integração: não é necessário reinventar o
KernelSU lifecycle. A rota DFR precisa fornecer uma ponte válida até o pipeline RMG já
existente.

## 35. O que o DFReroot upstream realmente resolve

Fornece três ideias/componentes-chave: persistência de uma aplicação com identidade
privilegiada após um root inicial; StageHop para chegar do system_server ao
NetworkStack; cadeia DirtyFrag baseada em page-cache patches que culmina em execução
de ksud.

**Não** fornece diretamente para RMG: firmware-exact ZZIC artifact policy; nosso
KernelSU exato; nosso same-boot readiness; nosso once-per-boot Auto Root contract;
proteção contra unsafe replay; provenance policy; transport architecture; validação de
Samsung Android 17 ZZIC.

## 36. Pontos upstream já compatíveis no aparelho real

Evidência forte favorável para: exact kernel XFRM/ESP features built in; processo
NetworkStack existente; UID `1073`; SELinux `u:r:network_stack:s0`; CAP_NET_ADMIN
efetivo; ausência das quatro alterações do reference Dirty Frag fix nas funções
compiladas exatas; BTF e layouts necessários; estrutura `selinux_state.enforcing` no
offset esperado pelo LKM upstream.

Hipótese tecnicamente mais forte que uma simples comparação de versão de kernel.
Ainda assim não fecha a cadeia.

## 37. Gates userspace/policy e UNKNOWN residual

A1 e A2 não devem mais ser descritos apenas como `UNKNOWN`. Eles são perguntas
decidíveis, bloqueadas pela ausência dos artefatos exatos de userspace/policy do
ZZIC no conjunto atualmente retido:

- StageHop / `IApplicationThread` / `scheduleReceiver`:
  `BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`;
- SELinux normal-boot / NETLINK_XFRM:
  `BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`;
- seccomp do NetworkStack:
  `BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`;
- native-loader / `dlopen(libexp.so)`:
  `BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS`.

A3 também muda de forma. O enunciado aberto "provar que não existe nenhuma
mitigação Samsung alternativa" é um universal negativo e não deve ser usado como
gate. Ele é substituído por uma matriz finita de candidatos justificados com
PASS/FAIL por candidato. Mecanismos não enumerados permanecem
`UNKNOWN_BY_CONSTRUCTION`, explicitamente, sem serem convertidos em PASS.

Continuam `UNKNOWN` até evidência própria as etapas dinâmicas posteriores:
execução real do StageReceiver; primitive Dirty Frag live; layouts/caves
userspace; hooks libc/libc++; init/vendor_modprobe; `finit_module`; namespace/
bind stage; handoff para o ksud RMG; readiness global a partir desse mecanismo.

Detalhamento e matriz A3:
`docs/DFREROOT_PHASE_A_GATES_2026-09-24.md`.

## 38. Seccomp merece tratamento separado

`Seccomp=2` observado no NetworkStack indica filtragem seccomp ativa. CAP_NET_ADMIN
existir não prova que todo syscall usado pelo código nativo possa ser executado. A
análise restante deve verificar as operações efetivamente necessárias ao first-stage,
sem concluir por analogia com outro aparelho. Gate separado no relatório.

## 39. SELinux XFRM também merece gate separado

Contexto real `u:r:network_stack:s0`. CAP_NET_ADMIN é gate de Linux capability;
SELinux é outro gate; ambos precisam permitir o caminho. Não inferir permissão SELinux
a partir da capability. Identificar especificamente as classes/permissões relacionadas
às operações XFRM/socket usadas pela cadeia.

## 40. Fatores userspace são tão importantes quanto o kernel

Mesmo com a vulnerability primitive presente, DFReroot depende de: NetworkStack;
system_server internals; ActivityManager internals; IApplicationThread; dlopen; bionic
libc; libc++; init; vendor_modprobe; SELinux; DEFEX; module loader; mount namespaces;
ksud.

`REFERENCE_FIX_ABSENT` não significa `DFREROOT_WORKS`. Conclusão correta: kernel
reference-fix state = favorable/confirmed absent; full upstream chain = unverified.

## 41. Riscos específicos da cadeia upstream

Três classes distintas de risco:

1. A primitive DirtyFrag modifica page-cache-backed executable/library data daquele boot.
2. Os hooks de libc/libc++ deixam processos daquele boot em estado alterado até reboot
   (o upstream impede replay quando `/dev/df` está presente).
3. O persistence installer modifica `/data/system/packages.xml`, estado durável e muito
   mais sensível a corrupção persistente.

Não juntar "teste do primitive", "teste do stage chain" e "instalação persistente" em
um único experimento inicial.

## 42. Evidência de issues upstream

Reports públicos observados em que outros Samsung chegaram a etapas iniciais e
falharam depois. Confirma que receber controller NetworkStack, passar StageHop, ou
iniciar patches não é suficiente para declarar compatibilidade. Houve também report de
problemas de package database/permissões em tentativa de port. Tratar como evidência de
**risco**, não como prova de comportamento no ZZIC.

## 43. Provenance/licensing upstream

No snapshot auditado não foi estabelecida licença raiz clara autorizando copiar todo o
upstream. Alguns componentes são binaries/prebuilt. Antes de copiar para RMG,
distinguir: código com origem auditável; código proveniente de LSPromise/DirtyFrag;
binários upstream; módulos pré-compilados; KernelSU fork upstream. Não tratar um git
blob SHA como equivalente a reproducible build provenance.

## 44. Arquivos RMG que preservam nossa evidência

Consultar também: `tools/dfreroot/collect_exact_boot_symbols.py`,
`tools/dfreroot/collect_runtime_prereqs.py`, `tools/dfreroot/offline_kallsyms_compare.py`,
`tools/dfreroot/offline_lkm_contract.py`, `tools/dfreroot/readonly_compatibility.py`, e
respectivos testes.

`offline_lkm_contract.py` contém atualmente os exact hashes, exact function windows, BTF
validation, diagnostic archive authentication, runtime NetworkStack parsing e limites
explicitamente UNKNOWN. **Não duplicar esses valores** em uma nova implementação de forma
que possam divergir silenciosamente.

## 45. Artefatos privados que NÃO estão integralmente no repositório

Parte da evidência original permaneceu privada: archive de diagnóstico com material de
boot/config; captura completa kallsyms; runtime prerequisite archive; relatórios
privados derivados; raw/full outputs intermediários. O repositório guarda os resultados
necessários e contratos de validação, mas não necessariamente os dumps completos. Não
afirmar que `HANDOFF.md` é cópia byte-for-byte da investigação.

## 46. O que deve ser considerado fonte de verdade

Ordem de confiança:

`AGENTS.md` → `HANDOFF.md` + este adendo → `DFREROOT_FEASIBILITY_AND_ARCHITECTURE_2026-09-23.md`
→ scripts de auditoria atuais → material privado original quando necessário → código
DFReroot pinado → documentação/upstream issues como evidência auxiliar.

Um resultado de CI sintético nunca substitui evidência do hardware real. Uma observação
do hardware real nunca deve ser extrapolada para uma etapa diferente sem prova.

## 47. Estado resumido dos gates

| Gate | Estado |
|---|---|
| Target ZZIC exato | PASS |
| Kernel Image/provenance da análise | PASS |
| XFRM/ESP kernel config | PASS |
| Reference four-path fix ausente | PASS (finding estático) |
| A3: quatro adições públicas de referência | PASS (candidato finito) |
| A3: immediate splice/page-frag helper chain | PASS (candidato finito, escopo limitado) |
| A3: direct RKP/KDP/DEFEX/FIVE/PROCA/hypervisor BLs nos 7 corpos exatos | PASS_BOUNDED (nenhum target direto enumerado) |
| A3: DEFEX helper/module/mount semantics | PENDING_EXACT_POLICY_PATH_REVIEW |
| A3: Knox/hypervisor direct BLs nos 7 corpos exatos | PASS_BOUNDED (nenhum target direto enumerado) |
| A3: helper chain skb/page-cache definida | PASS_BOUNDED (escopo dos corpos revisados) |
| A3: delta ZZI4→ZZIC em conjunto finito de 12 XFRM/ESP funcs | PASS_BOUNDED (único delta = endereço de string para fortify_panic) |
| A3: `CONFIG_` hardening relevante | INVENTORIED_NOT_A_BLOCK_VERDICT |
| A3 fora do conjunto enumerado | UNKNOWN_BY_CONSTRUCTION |
| NetworkStack process | PASS |
| UID 1073 | PASS |
| CAP_NET_ADMIN | PASS |
| SELinux context identificado | PASS |
| NetworkStack seccomp compatibility | BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS |
| NETLINK_XFRM SELinux permission | BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS |
| StageHop Android17 reflection compatibility | BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS |
| `scheduleReceiver/12` real | BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS |
| StageReceiver inside NetworkStack | UNKNOWN (dynamic evidence) |
| `libexp.so` loading | BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS |
| DirtyFrag page-cache primitive live | UNKNOWN |
| libc hook compatibility | UNKNOWN |
| libc++ hook compatibility | UNKNOWN |
| init/stage1 execution | UNKNOWN |
| vendor_modprobe transition | UNKNOWN |
| generic upstream LKM exact provenance | FAIL |
| generic upstream LKM runtime acceptance | UNKNOWN |
| selinux_state layout assumption | PASS |
| finit_module path | UNKNOWN |
| namespace/bind stage | UNKNOWN |
| upstream opaque ksud provenance | FAIL (para política RMG) |
| exact RMG ksud availability | PASS |
| exact RMG KernelSU module availability | PASS |
| DFR → RMG ksud handoff compatibility | UNKNOWN |
| RMG KernelSU lifecycle after valid bootstrap | PASS (rota RMG existente) |
| end-to-end DirtyFrag root on ZZIC | BLOCKED_ON_USERSPACE_EVIDENCE |
| Auto Root DirtyFrag integration | NOT IMPLEMENTED |

`BLOCKED_ON_MISSING_EXACT_USERSPACE_ARTIFACTS` significa que a pergunta é
decidível, mas falta o artefato exato necessário. Não significa incompatibilidade.

A3 não exige prova de ausência universal. Cada candidato finito recebe PASS/FAIL
com escopo explícito; o exterior da matriz fica
`UNKNOWN_BY_CONSTRUCTION`.

Enquanto A1/A2 estiverem nessa condição, o estado global é
`DIRTYFRAG_END_TO_END = BLOCKED_ON_USERSPACE_EVIDENCE`.

Ferramentas LAB implementadas para fechar a evidência:
`collect_zzic_userspace_gates.py`, `analyze_zzic_userspace_gates.py`,
`offline_bionic_seccomp.py` e `offline_a3_candidate_matrix.py`. Elas são
read-only/offline e não habilitam o provider real.

## 48. Regra para futuras instâncias

Não iniciar outra investigação perguntando apenas "o kernel é vulnerável?". Esse gate já
foi substancialmente respondido: as quatro alterações públicas de referência estão
ausentes no código compilado exato. O trabalho restante começa nos próximos gates não
resolvidos, na ordem conceitual:

```
StageHop Android17 compatibility → real NetworkStack execution → SELinux + seccomp + XFRM viability
→ live first-stage behavior → userspace hooks → privileged stage identity → handoff
→ exact RMG ksud → exact RMG KernelSU module → KernelSU control 32601
→ PID1/current-boot readiness → Auto Root orchestration
```

Não usar o exploit RMG atual como evidência de que qualquer etapa DirtyFrag passou. Não
usar sucesso de StageHop como evidência de que DirtyFrag passou. Cada boundary produz sua
própria evidência.

## 49. Regra para logs futuros

Logs de uma futura implementação DFR precisam distinguir pelo menos: exact target
verification; bootstrap provider; system_server identity; ProcessRecord/network_stack
selection; remote process identity; StageReceiver reached; native library loaded; XFRM
primitive started; page-cache patch stage; patch_ko result; patch_libc result; patch_cxx
result; trigger count; `/dev/df`; `dfm1`; `dfm2`; `dfm3`; `dfm4`; privileged execution
identity; SELinux state/context observations; handoff principal; ksud invocation; KernelSU
control result; KernelSU version/control; boot ID; mount namespace; same-boot global
readiness; terminal classification.

"DirtyFrag failed" não é um diagnóstico suficientemente preciso.

## 50. Critério RMG de sucesso final

Não copiar a definição de sucesso do upstream baseada apenas em `dfm3`. A rota RMG só é
bem-sucedida quando o mesmo kernel boot apresentar evidência atribuível de: DirtyFrag
provider selecionado; first-stage concluído; contexto privilegiado esperado atingido;
handoff correto; exact RMG ksud utilizado; exact RMG KernelSU module utilizado; KernelSU
control version `32601`; PID1/global readiness; same-boot marker; Auto Root terminal
success; e nenhuma execução paralela do exploit RMG antigo responsável pelo root naquele
mesmo teste.

Enquanto os gates exatos de userspace/policy A1/A2 estiverem bloqueados por falta
de artefato, usar
`DIRTYFRAG_END_TO_END = BLOCKED_ON_USERSPACE_EVIDENCE`.

Depois que esses bloqueios forem resolvidos, mas antes da cadeia completa ser
observada no mesmo kernel boot, o estado volta a
`DIRTYFRAG_END_TO_END = UNVERIFIED`. Nunca usar "100% funcional" antes do
critério completo acima.

## 51. Separação obrigatória de conclusões

Manter sempre separadas:

- **A)** "O reference Dirty Frag fix está ausente no ZZIC." — fortemente sustentado.
- **B)** "O kernel possui XFRM/ESP e o NetworkStack possui CAP_NET_ADMIN." — confirmado no hardware.
- **C)** "DFReroot upstream funciona no ZZIC." — ainda NÃO demonstrado.
- **D)** "DirtyFrag pode entregar bootstrap utilizável pelo nosso KernelSU." — ainda NÃO demonstrado.
- **E)** "Nosso KernelSU funciona quando recebe bootstrap válido." — já demonstrado pela rota RMG existente.

Não colapsar A+B+E em C ou D.

## 52. Estado final para handoff

A hipótese DirtyFrag no ZZIC deixou de ser especulação baseada em número de versão. Há
evidência binária exata de que a correção upstream estudada está ausente e evidência real
de que vários pré-requisitos do NetworkStack existem. O maior desconhecido agora é o
caminho executável entre:

```
system_server → network_stack → primitive DirtyFrag → hooks userspace → contexto privilegiado posterior → handoff KernelSU
```

A integração futura deve preservar o pipeline RMG existente depois desse boundary e
substituir as suposições genéricas do upstream por exact-target checks e evidência do boot
atual. Não repetir coleta de kernel/BTF/kallsyms/config já realizada a menos que o firmware
mude ou alguma inconsistência concreta seja descoberta.
