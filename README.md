# RetroBox Mobile

Launcher + emulador multi-console em **um único APK**. A biblioteca, a UI neon e os núcleos de
emulação rodam no mesmo processo: o usuário não instala RetroArch, PPSSPP ou qualquer outro app.

- **Pacote:** `com.retrobox.mobile`
- **minSdk / targetSdk:** 26 / 36
- **ABIs:** `arm64-v8a` (celular) e `x86_64` (emulador Android para desenvolvimento)
- **Base:** esqueleto Compose/Room do projeto original + camada de emulação nova

---

## 1. Estado atual

| Camada | Situação |
|---|---|
| Biblioteca (grid, sidebar, busca, favoritos, Room) | ✅ do projeto original |
| Importação de ROM avulsa (SAF) + **importação em lote por pasta** | ✅ |
| Importação automática de `/Android/data/<app>/files/JOGOS` | ✅ |
| **Capas (box art) baixadas do libretro-thumbnails + cache local** | ✅ |
| Frontend libretro nativo (JNI, C++) | ✅ |
| Saída de vídeo GLES 3 em `TextureView` (software + hardware/FBO) | ✅ |
| Áudio AAudio com ring buffer | ✅ |
| Controles: overlay multitoque + gamepad físico | ✅ |
| Save state / SRAM (armazenamento interno do app) | ✅ |
| **Catálogo remoto assinado (Ed25519) com fallback de hash fixado** | ✅ |
| **Catálogo completo embutido: 33 mil jogos (GBA, MD, N64, PS1, PSP, SNES)** | ✅ |
| **Identificação de ROM por MD5: título e capa oficiais do catálogo** | ✅ |
| **Download com WorkManager: retomada (HTTP Range), SHA-256, notificação** | ✅ |
| **Gerenciador de BIOS importado pelo usuário (sem BIOS no APK)** | ✅ |
| **Identificação de ROM por SHA-256/CRC32 + casamento com o catálogo** | ✅ |
| **Backup/restauração de saves (zip) automático e manual** | ✅ |
| **Erros visíveis com relatório + `files/logs/last_error.txt`** | ✅ |
| **Configurações persistidas por console + autosave ao sair** | ✅ |
| Testado em aparelho real (arm64, Android 16) | ✅ SNES a 60 fps |

### Testes reais

| Cenário | Resultado |
|---|---|
| Importação em lote de 41 ROMs (SNES/GBA/N64/MD) | ✅ 41 jogos, 28 capas |
| NES (FCEUmm) | ✅ 60 fps, áudio OK, save state OK |
| SNES (Snes9x 2005 Plus) | ✅ **59,8–60,5 fps** em aparelho real (arm64) |
| Toque nos controles on-screen | ✅ (multitoque) |
| Save state / load state | ✅ (46 KB por estado) |
| Migração Room v2 → v3 (com `catalog`, `downloads`, `sha256`) | ✅ biblioteca preservada |
| Catálogo embutido assinado (Ed25519) | ✅ “catálogo v1 … (assinatura Ed25519)” |
| Catálogo remoto por HTTP + assinatura | ✅ recusado sem assinatura, aceito com Ed25519 |
| Download de 8 MB com SHA-256 | ✅ arquivo só entra na biblioteca depois do hash |
| Download interrompido (app morto no meio) | ✅ retomou com `206` a partir do `.part` |
| Backup automático de saves | ✅ zip diário com rotação (mantém 7) |
| Catálogo completo embutido (33.118 jogos) | ✅ assinado, comprimido (deflate), carregado em ~2 s |
| Busca no catálogo por nome | ✅ insensível a maiúsculas, até 400 resultados |
| Identificação por MD5 (base oficial do libretro) | ✅ ROM renomeada vira "Aladdin (USA)" com capa oficial |

---

## 2. Arquitetura

```
┌──────────────────────────────────────────────┐
│ Compose (HomeScreen, sidebar, grid)          │  UI
├──────────────────────────────────────────────┤
│ EmulatorActivity + TouchControls             │  sessão de jogo
├──────────────────────────────────────────────┤
│ EmulatorSession / CoreRegistry / Gamepad     │  Kotlin
│ LibraryImporter / CoverStore / GameTitle     │  biblioteca + capas
├──────────────────────────────────────────────┤
│ libretrobox.so  (JNI)                        │  C++
│   ├── render: EGL + GLES3 (sw + FBO hw)      │
│   ├── audio : AAudio 48 kHz estéreo          │
│   ├── input : joypad digital (botões/axis)   │
│   ├── env   : variáveis e opções do core     │
│   └── core  : dlopen(lib*_libretro.so)       │
├──────────────────────────────────────────────┤
│ Núcleos libretro embutidos em jniLibs        │  .so pré-compilados
└──────────────────────────────────────────────┘
```

Arquivos-chave:

| Arquivo | Papel |
|---|---|
| `app/src/main/cpp/emulator.cpp` | frontend libretro: renderer EGL/GLES, AAudio, loop, env callbacks |
| `app/src/main/cpp/jni_bridge.cpp` | ponte JNI (`NativeEmulator`) |
| `app/src/main/cpp/libretro.h` | header **oficial** do libretro (ABI correta dos núcleos) |
| `emulation/CoreRegistry.kt` | console → `.so`, caminho em `nativeLibraryDir` |
| `emulation/EmulatorSession.kt` | resolve ROM, cópia para o app, SRAM, save state |
| `emulation/LibraryImporter.kt` | importação em lote (SAF e pasta do app) |
| `emulation/CoverStore.kt` | download das capas + cache local |
| `emulation/GameTitle.kt` | limpeza de nome e candidatos para busca de capa |
| `emulation/BiosManager.kt` | requisitos de BIOS por console, importação e validação |
| `emulation/RomIdentifier.kt` | SHA-256, CRC32 e serial do cartucho |
| `emulation/ErrorReporter.kt` | relatório de erro gravado em `files/logs/` |
| `catalog/CatalogRepository.kt` | carrega/valida/grava o catálogo (URL ou asset) |
| `catalog/CatalogVerifier.kt` | Ed25519 + fallback de hash fixado |
| `catalog/CatalogKeys.kt` | chaves públicas aceitas |
| `download/RomDownloader.kt` | Range/retomada, SHA-256, rename atômico |
| `download/GameDownloadWorker.kt` | worker do WorkManager (progresso, notificação) |
| `backup/SaveBackupManager.kt` | zip de saves/states, rotação, export/restore |
| `data/StorageLayout.kt` | layout versionado de arquivos do app |
| `ui/emulator/EmulatorActivity.kt` | TextureView, menu in-game, FPS, fast-forward, aspecto |
| `ui/emulator/TouchControls.kt` | D-pad, A/B/X/Y, L/R, Start/Select |

### Fluxo de inicialização (ordem importa)

1. `nativeLoadCore` → `dlopen` + `dlsym` (não chama `retro_init`)
2. `nativeSetSurface` → guarda a `ANativeWindow`
3. `nativeStartGame` → **na thread de emulação**: EGL/GLES init → `retro_set_environment` +
   `retro_init` → `retro_load_game` → AAudio → loop de `retro_run`

O contexto GL precisa existir na mesma thread que roda `retro_run`, por isso tudo acontece lá.

---

## 3. Núcleos embutidos

| Console | Núcleo | `.so` | Licença |
|---|---|---|---|
| NES | FCEUmm | `libfceumm_libretro.so` | GPLv2 |
| SNES | Snes9x 2005 Plus | `libsnes9x2005_plus_libretro.so` | Snes9x (não comercial) |
| GB / GBC | Gambatte | `libgambatte_libretro.so` | GPLv2 |
| GBA | mGBA | `libmgba_libretro.so` | MPL 2.0 |
| Mega Drive | Genesis Plus GX | `libgenesis_plus_gx_libretro.so` | não comercial |
| PS1 | PCSX-ReARMed | `libpcsx_rearmed_libretro.so` | GPLv2 |
| N64 | Mupen64Plus-Next (GLES3) | `libmupen64plus_next_gles3_libretro.so` | GPLv2 |
| NDS | melonDS | `libmelonds_libretro.so` | GPLv3 |
| PSP | PPSSPP | `libppsspp_libretro.so` | GPLv2+ |

Os binários vêm do buildbot oficial do libretro (`nightly/android/latest/<abi>/`) e foram
renomeados para `lib*.so` porque o AGP só empacota arquivos com esse prefixo.

### Licença do app

Como o APK **liga** núcleos GPL (melonDS/GPLv3, FCEUmm/GPLv2, PCSX-ReARMed/GPLv2, Mupen64Plus/GPLv2,
PPSSPP/GPLv2+), a distribuição do conjunto precisa ser GPLv3 com o código-fonte disponível.
Uso pessoal/privado não muda nada tecnicamente, mas **não distribua o APK fechado**.

---

## 4. Compilar

Pré-requisitos (já instalados nesta máquina):

```powershell
# JDK 21, Android SDK (platforms 34–36, build-tools 34–36.1), NDK 27.2.12479018, CMake 3.22.1
cd C:\dev\retrobox-mobile
.\gradlew.bat :app:assembleDebug
# saída: app\build\outputs\apk\debug\app-debug.apk
```

`local.properties` (não versionado):

```properties
sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk
```

Assinatura debug: `debug.keystore` na raiz (gerado com `keytool`, alias `androiddebugkey`).

### Atualizar núcleos

```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch-cores.ps1 -Abi arm64-v8a
```

---

## 5. Usar

### Trazer os jogos

**Opção A — pasta do app (sem permissão, o mais fácil)**
Copie as ROMs para `/sdcard/Android/data/com.retrobox.mobile/files/JOGOS/<CONSOLE>/`
(por USB, explorador de arquivos ou `adb push`). Ao abrir o app, elas são importadas
automaticamente, com capa.

```
/sdcard/Android/data/com.retrobox.mobile/files/JOGOS/
├── SNES/     Aladdin (USA).sfc, Super Mario World (U) [!].smc, …
├── GBA/      *.gba
├── N64/      *.z64
├── MEGA DRIVE/ *.md
├── PS1/      *.cue + *.bin
└── PSP/      *.iso / *.cso
```

**Opção B — seletor de pasta (SAF)**
Botão **📁 Importar pasta** → escolha a pasta → o app varre a árvore, detecta o console
pelo nome da pasta (ou pela extensão), copia cada ROM para o armazenamento privado e
busca a capa.

**Opção C — arquivo avulso**
Botão **+** → escolha um arquivo. O console é detectado pela extensão.

### Jogar

1. Toque no jogo na grade → o emulador abre em paisagem (ligue a **rotação automática**).
2. Dentro do jogo: botão ☰ (ou **Voltar**) → salvar/carregar estado, reiniciar,
   fast-forward, filtro suave/nítido, aspecto (ajustar/esticar/inteiro), esconder
   controles, sair.

### Capas

As capas vêm do repositório oficial
[`libretro-thumbnails`](https://github.com/libretro-thumbnails) (box art de cada jogo),
baixadas na importação e **guardadas em cache** (`files/covers/`), redimensionadas para
480 px. O título do arquivo é normalizado antes da busca (`(USA)`, `(V1.2)`, `[!]`,
`# GBA`, `1840 - ` etc. são removidos e a região é traduzida). Sem internet, o app usa
uma capa gerada (gradiente do console + título).

BIOS (opcional): o app **não inclui nem baixa BIOS**. Em **Ajustes → Gerenciar BIOS** (ou no
menu do emulador) você importa o arquivo que já tem: `scph5501.bin` (PS1, 512 KB),
`bios7.bin`/`bios9.bin` (NDS), `firmware.bin` (opcional). O arquivo é validado por
tamanho e gravado em `files/system/`. Sem BIOS, o PCSX-ReARMed usa HLE; o app só
**avisa** (não bloqueia) e oferece *Continuar / Importar / Gerenciar*.

### Remover da biblioteca

Toque e segure um jogo na grade → *Remover e apagar arquivo* (tira da lista e apaga a
cópia em `files/roms/`) ou *Só da lista*. O arquivo original que você importou nunca é
apagado.

---

## 5b. Catálogo, download, backup e configuração

### Catálogo assinado

O catálogo é uma lista de **metadados** (título, console, tamanho, hash, gênero e URL
opcional). Ele nunca traz ROM nem BIOS, e o app **recusa** um catálogo sem prova de
origem: assinatura **Ed25519** sobre os bytes do payload ou, em aparelhos sem Ed25519
(Android < 13), um **SHA-256 fixado** no APK.

**O catálogo embutido tem 33.118 jogos** (GBA, Mega Drive, N64, PS1, PSP e SNES),
gerado da base oficial do libretro (`libretro/libretro-database`) e assinado com a
chave `sample`. Ele viaja no APK comprimido em deflate (11,8 MB de JSON → 2,0 MB).

```powershell
# regerar o catálogo completo (precisa de internet na primeira vez)
java tools/catalog/GenerateFullCatalog.java tools/catalog/catalog-full-payload.json

# assinar (comprimido) e embutir no APK
java tools/catalog/CatalogTool.java sign tools/catalog/catalog-full-payload.json sample `
    tools/catalog/keys/sample.seed app/src/main/assets/catalog.json --deflate

# conferir
java tools/catalog/CatalogTool.java verify app/src/main/assets/catalog.json `
    tools/catalog/keys/sample.pub
```

Detalhes do envelope, dos formatos (1 = cru, 2 = deflate) e da rotação de chaves:
[`tools/catalog/README.md`](tools/catalog/README.md).

Para apontar para um catálogo próprio: **Ajustes → URL do catálogo** (ou
`RETROBOX_CATALOG_URL` no build). `url` vazio = “traga seu próprio arquivo”.

### Identificação de ROM

Cada item do catálogo traz o **MD5** do cartucho (a mesma identidade da base oficial
do libretro). Quando você importa um jogo:

1. o app calcula SHA-256 + MD5 + CRC32 do arquivo (uma leitura só);
2. se o MD5 casar com o catálogo, o jogo entra com o **título oficial** e a **capa
   oficial** (box art do libretro-thumbnails), mesmo que o arquivo esteja renomeado;
3. se já estiver na biblioteca, a importação **atualiza** o que falta (título/capa) em
   vez de duplicar.

É por isso que a lista de 33 mil jogos não é só enfeite: ela é o dicionário que
transforma “arquivo_do_usuario.smc” em “The Mask (USA)”.

### Download

A aba **Catálogo** baixa o item: `.part` em `files/downloads` → HTTP Range (retoma) →
conferência de tamanho e **SHA-256** → `rename` atômico para `files/roms/<console>/`.
Só depois disso o jogo entra na biblioteca. O download roda em WorkManager (sobrevive ao
app em segundo plano), mostra progresso na tela e na notificação, e é retomado
automaticamente se o processo morrer.

### Backup dos saves

**Ajustes → Saves e backup**: backup automático diário (zip em `files/backup/`, mantém os
7 mais recentes), botão *Backup agora*, *Exportar* (SAF) e *Restaurar*. O zip tem
`manifest.json` + `saves/` + `states/`; a restauração ignora qualquer caminho fora dessas
pastas (zip-slip).

### Ferramentas de desenvolvimento

```powershell
# servidor HTTP local com Range (para testar download/retomada)
java tools/dev/RangeServer.java C:\caminho\de\testes 8000
adb reverse tcp:8000 tcp:8000
```

---

## 6. Limitações conhecidas

- **ROM dentro de `.zip`/`.7z` não é extraída** — importe o arquivo cru.
- **PS1 multi-bin**: use `.cue` com os `.bin` na mesma pasta (o core resolve pelo cue), ou `.chd`.
- **Sem BIOS embutida.**
- **Capas**: ~68% dos jogos têm arte no repositório oficial; hacks e multi-jogos
  (ex.: *Street Fighter 5 (Hack)*) ficam com a capa gerada.
- **N64/PSP**: núcleos pesados; espere FPS baixo em aparelhos de 4 GB.
- **Vulkan não implementado**: o frontend só oferece GLES 2/3. Cores configurados para Vulkan
  caem para GL quando o núcleo aceita; se não aceitar, falham (PPSSPP é o caso mais provável).
- **Opções por núcleo ainda não têm UI**: o frontend usa os valores padrão do core
  (a API de variáveis legadas é suportada; a de *core options v2* é aceita mas ignorada
  porque o layout da struct mudou entre versões do `libretro.h`).
- **Cores com `need_fullpath = 0`** recebem a ROM em memória (até 128 MB); `.iso` grandes
  de PSP/PS1 usam o caminho no disco.

---

## 7. Próximos passos sugeridos

1. Medir FPS/áudio de cada um dos 9 núcleos no aparelho real.
2. Extração de `.zip`/`.7z` no import (cache do app).
3. Mapeamento de botões por console (PS1: △○×□ em vez de A/B/X/Y; SNES: layout Nintendo).
4. UI de opções por núcleo (parsing seguro de `SET_CORE_OPTIONS_V2`).
5. `libretro-db.sqlite` embutido para identificar ROMs que não estão no catálogo
   (hoje a base do libretro já vem embutida como catálogo, com MD5).
6. Remover a ABI `x86_64` e os núcleos correspondentes antes de publicar.
7. Assinar a release (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) e publicar.

---

## 8. Notas técnicas (bugs resolvidos)

Registro dos problemas encontrados ao colocar os núcleos para funcionar — útil para quem
for mexer no `emulator.cpp`:

1. **`GL_RGBA` + `GL_UNSIGNED_SHORT_5_6_5` é combinação inválida em OpenGL ES.**
   O upload da textura falhava com `GL_INVALID_OPERATION` e a tela saía embaralhada.
   Para RGB565 o *internal format* tem que ser `GL_RGB565`.
2. **`GL_UNPACK_ROW_LENGTH` não é portável.** Alguns cores entregam `pitch` maior que a
   largura visível; agora as linhas são copiadas para um buffer “justo” na mão.
3. **`SurfaceView` recorta o input da janela.** O overlay de controles (Compose) ficava
   acima do vídeo, mas os toques sobre a área do vídeo eram descartados
   (`touchCropId` no SurfaceFlinger). Trocado por **`TextureView`**, que é composto dentro
   da janela e deixa o toque chegar na UI.
4. **Cores com `need_fullpath = 0` precisam da ROM em memória.** Com `data = NULL` o
   núcleo faz divisão por zero (`SIGFPE`) — a ROM agora é carregada em memória.
5. **`GET_LANGUAGE` (cmd 39) é obrigatório.** O Snes9x usa o idioma para escolher a
   região; sem ele o núcleo divide por zero.
6. **`GET_INPUT_BITMASKS` pode vir com `data = NULL`.** O handler nunca pode
   desreferenciar o ponteiro sem checar.
7. **O bit “experimental” (0x10000) do `libretro.h`** reaproveita o número de comandos
   padrão; o frontend mascara o bit antes de tratar o comando.
8. **Audio buffer status callback (cmd 62).** Sem chamar o callback, o Snes9x fica
   esperando espaço no buffer de áudio e a emulação trava em ~2 fps.
9. **`retro_core_option_v2_definition` mudou de layout** entre versões do `libretro.h`
   (ponteiro → array fixo). Por isso o frontend usa a API legada de variáveis, que é estável.

---

## 9. Legal

Nenhuma ROM, BIOS ou conteúdo protegido é distribuído com o projeto. Use apenas arquivos que você
possui. Os núcleos mantêm suas licenças originais; o app é um frontend libretro independente.

O catálogo remoto é apenas metadado (título, console, tamanho, hash e URL): quem publica um
catálogo é responsável pelo conteúdo apontado e pelos direitos dele.
