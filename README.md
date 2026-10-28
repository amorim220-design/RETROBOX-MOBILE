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
| Importação de ROM (SAF, permissão persistente, console por extensão) | ✅ |
| Frontend libretro nativo (JNI, C++) | ✅ compilando |
| Saída de vídeo GLES 3 na `SurfaceView` (software + hardware/FBO) | ✅ |
| Áudio AAudio com ring buffer | ✅ |
| Controles: overlay multitoque + gamepad físico | ✅ |
| Save state / SRAM (`/sdcard` interno do app) | ✅ |
| Teste em dispositivo real (Realme 6, arm64) | ⏳ pendente — feito em emulador x86_64 |
| BIOS de PS1/NDS/PSP | ⏳ pasta `system/` preparada, sem BIOS embutida |

---

## 2. Arquitetura

```
┌──────────────────────────────────────────────┐
│ Compose (HomeScreen, sidebar, grid)          │  UI
├──────────────────────────────────────────────┤
│ EmulatorActivity + TouchControls             │  sessão de jogo
├──────────────────────────────────────────────┤
│ EmulatorSession / CoreRegistry / Gamepad     │  Kotlin
├──────────────────────────────────────────────┤
│ libretrobox.so  (JNI)                        │  C++
│   ├── render: EGL + GLES3 (sw + FBO hw)      │
│   ├── audio : AAudio 48 kHz estéreo          │
│   ├── input : joypad digital (botões/axis)   │
│   └── core  : dlopen(lib*_libretro.so)       │
├──────────────────────────────────────────────┤
│ Núcleos libretro embutidos em jniLibs        │  .so pré-compilados
└──────────────────────────────────────────────┘
```

Arquivos-chave:

| Arquivo | Papel |
|---|---|
| `app/src/main/cpp/emulator.cpp` | frontend libretro: renderer EGL/GLES, AAudio, loop, callbacks |
| `app/src/main/cpp/jni_bridge.cpp` | ponte JNI (`NativeEmulator`) |
| `app/src/main/cpp/libretro_api.h` | subconjunto da API libretro usada |
| `emulation/CoreRegistry.kt` | console → `.so`, caminho em `nativeLibraryDir` |
| `emulation/EmulatorSession.kt` | resolve ROM (inclui `content://` via `/proc/self/fd/N`), SRAM, save state |
| `ui/emulator/EmulatorActivity.kt` | SurfaceView, menu in-game, FPS, fast-forward |
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

1. Instale o APK.
2. Toque em **+** e escolha a ROM (`.nes`, `.sfc`, `.gba`, `.iso`, `.chd`, `.nds`, `.cso`…).
   O console é detectado pela extensão; a permissão de leitura é persistida.
3. Toque no jogo para abrir o emulador (paisagem).
4. Dentro do jogo: botão ☰ (ou **Voltar**) → salvar/carregar estado, reiniciar, fast-forward,
   filtro suave/nítido, esconder controles, sair.

BIOS (opcional): copie para o armazenamento interno do app em
`/data/data/com.retrobox.mobile/files/system/` (`scph5501.bin`, `bios7.bin`, `bios9.bin`,
`firmware.bin`…). Sem BIOS, PCSX-ReARMed usa HLE; melonDS/PPSSPP podem exigir arquivos próprios.

---

## 6. Limitações conhecidas

- **ROM dentro de `.zip`/`.7z` não é extraída** ainda — importe o arquivo cru.
- **PS1 multi-bin**: use `.cue` com os `.bin` na mesma pasta (o core resolve pelo cue), ou `.chd`.
- **Sem BIOS embutida** e sem download automático de capas.
- **N64/PSP**: núcleos pesados; no Realme 6 (Mali-G76, 4 GB) espere FPS baixo.
- **Vulkan não implementado**: o frontend só oferece GLES 2/3. Cores configurados para Vulkan
  caem para GL quando o núcleo aceita; se não aceitar, falham (PPSSPP é o caso mais provável).
- **`content://` via `/proc/self/fd/N`**: funciona para leitura, mas se o provedor fechar o fd ou
  exigir seek em outro processo, a ROM precisa ser copiada para o armazenamento do app.
- **Arquivos `.iso` grandes** não são copiados — o save state e a SRAM ficam em `files/`.

---

## 7. Próximos passos sugeridos

1. Testar no aparelho real (arm64) e medir FPS/áudio nos 9 núcleos.
2. Extração de `.zip` no import (cache do app).
3. Mapeamento de botões por console (PS1: △○×□ em vez de A/B/X/Y; SNES: layout Nintendo).
4. Opções por núcleo (libretro `SET_CORE_OPTIONS_V2`) — hoje o frontend devolve `false` e o core
   usa os padrões.
5. Capas via libretro-thumbnails + nome do arquivo.
6. Salvar automaticamente o estado ao sair do jogo.
7. Remover a ABI `x86_64` e os núcleos correspondentes antes de publicar.

---

## 8. Legal

Nenhuma ROM, BIOS ou conteúdo protegido é distribuído com o projeto. Use apenas arquivos que você
possui. Os núcleos mantêm suas licenças originais; o app é um frontend libretro independente.
