# Catálogo assinado

O catálogo é só **metadados**: título, console, tamanho, SHA-256 e (opcionalmente)
uma URL. O APK nunca traz ROM, ISO, BIOS ou chave de jogo — nem o catálogo de
exemplo.

O app recusa qualquer catálogo sem prova de origem:

1. **Ed25519** (normal, Android 13+): a assinatura cobre os bytes do payload.
2. **SHA-256 fixado** (`RETROBOX_CATALOG_PINNED_SHA256`): só para aparelhos sem
   Ed25519. Atualizar o catálogo passa a exigir atualizar o APK.

Sem uma das duas, nada é gravado em `Room`.

## Arquivos

| Arquivo | Papel |
| --- | --- |
| `CatalogTool.java` | keygen / sign / verify / pin — JDK 17+, sem dependências |
| `catalog-payload.json` | payload do catálogo de exemplo (editável, versionado) |
| `keys/<id>.seed` | chave privada (**não versionada**, ver `.gitignore`) |
| `keys/<id>.pub` | chave pública (32 bytes em base64) |
| `app/src/main/assets/catalog.json` | envelope assinado embutido no APK |

## Fluxo

```powershell
# 1. par de chaves (uma vez; guarde o .seed fora do git)
java tools/catalog/CatalogTool.java keygen sample tools/catalog/keys

# 2. cole o valor do .pub em CatalogKeys.kt (chave "sample") ou exporte
#    RETROBOX_CATALOG_PUBLIC_KEY / RETROBOX_CATALOG_KEY_ID no build

# 3. edite o payload e assine para o asset embutido
java tools/catalog/CatalogTool.java sign tools/catalog/catalog-payload.json sample `
    tools/catalog/keys/sample.seed app/src/main/assets/catalog.json

# 4. confira (falha com código 1 se a assinatura não bater)
java tools/catalog/CatalogTool.java verify app/src/main/assets/catalog.json `
    tools/catalog/keys/sample.pub
```

Para publicar por URL (catálogo do operador), assine com a chave do operador e
suba o envelope em qualquer host HTTPS:

```powershell
$env:RETROBOX_CATALOG_URL        = "https://exemplo.com/catalog.json"
$env:RETROBOX_CATALOG_PUBLIC_KEY = "<base64 de 32 bytes>"
$env:RETROBOX_CATALOG_KEY_ID     = "loja-2026"
$env:RETROBOX_CATALOG_PINNED_SHA256 = "9082...0182"   # opcional, fallback
.\gradlew.bat :app:assembleDebug
```

A URL também pode ser digitada em **Ajustes → Catálogo** (fica no DataStore e
tem prioridade sobre o valor do build).

## Envelope

```json
{
  "format": 1,
  "keyId": "sample",
  "payload": "<base64 do JSON>",
  "signature": "<base64 Ed25519 (64 bytes) sobre os bytes do payload>"
}
```

Assinar o *payload* (e não o envelope) permite evoluir o envelope sem quebrar
assinaturas antigas.

## Regras do payload

`{ "version": Int, "updatedAt": String, "entries": [...] }` — cada entrada:

| Campo | Regra |
| --- | --- |
| `id` | `[a-z0-9][a-z0-9._-]{1,63}`, único |
| `title` | obrigatório |
| `console` | `nes`, `snes`, `gb`, `gbc`, `gba`, `megadrive`, `ps1`, `psp`, `n64`, `nds` |
| `url` | `https://` (ou `http://` em `127.0.0.1`/`localhost`/`10.0.2.2`, só dev) |
| `sha256` | **obrigatório quando há `url`** — é a única prova de integridade |
| `sizeBytes` | usado para reservar espaço e checar o download |
| `coverUrl` | `https://` opcional |

`url: null` significa “traga seu próprio arquivo”: o app mostra o jogo e pede o
arquivo ao usuário.

## Segurança

- O seed do operador não entra no repositório nem no APK; assine em uma máquina
  confiável (idealmente offline) e publique só o envelope.
- Rotacionar chave exige uma nova versão do app (a chave pública está no APK).
  Mantenha a chave antiga em `CatalogKeys` durante a transição.
- O catálogo de exemplo (`sample`) não aponta para nenhum arquivo — só existe
  para o app ter uma lista na primeira execução.
