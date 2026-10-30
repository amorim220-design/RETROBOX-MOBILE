# Catálogo assinado

O catálogo é só **metadados**: título, console, tamanho, hash (MD5/CRC32), gênero e
URL opcional. O APK nunca traz ROM, ISO, BIOS ou chave de jogo — nem o catálogo de
exemplo.

O app recusa qualquer catálogo sem prova de origem:

1. **Ed25519** (normal, Android 13+): a assinatura cobre os bytes do payload.
2. **SHA-256 fixado** (`RETROBOX_CATALOG_PINNED_SHA256`): só para aparelhos sem
   Ed25519. Atualizar o catálogo passa a exigir atualizar o APK.

Sem uma das duas, nada é gravado em `Room`.

## Formatos do envelope

| Formato | `payload` | Quando usar |
|---|---|---|
| 1 | JSON UTF-8 puro | catálogo pequeno (exemplo) |
| 2 | JSON comprimido em **deflate cru** (sem cabeçalho zlib) | catálogo completo (37 mil jogos → 11,8 MB → 2,0 MB) |

Nos dois casos a assinatura é sobre **exatamente** os bytes que vão no campo
`payload`, então a verificação acontece antes de descompactar/parsear.

## Arquivos

| Arquivo | Papel |
| --- | --- |
| `CatalogTool.java` | keygen / sign / verify / pin — JDK 17+, sem dependências |
| `GenerateFullCatalog.java` | monta o catálogo completo a partir da base oficial do libretro |
| `catalog-demo-payload.json` | payload do catálogo de exemplo (6 itens de homebrew) |
| `catalog-full-payload.json` | payload completo gerado (não versionado: 11,8 MB) |
| `keys/<id>.seed` | chave privada (**não versionada**, ver `.gitignore`) |
| `keys/<id>.pub` | chave pública (32 bytes em base64) |
| `app/src/main/assets/catalog.json` | envelope assinado embutido no APK |

## Catálogo completo (37.253 jogos)

A base oficial do libretro (`libretro/libretro-database`) tem um `.rdb` por
console — MessagePack com `name`, `rom_name`, `size`, `crc`, `md5`, `sha1`,
`genre`, `developer`, `releaseyear`. O gerador lê isso e monta o JSON:

```powershell
# 1) baixa os .rdb (cache em tools/catalog/cache) e gera o payload
java tools/catalog/GenerateFullCatalog.java tools/catalog/catalog-full-payload.json

# 2) assina (comprimido, formato 2) para o asset do APK
java tools/catalog/CatalogTool.java sign tools/catalog/catalog-full-payload.json sample `
    tools/catalog/keys/sample.seed app/src/main/assets/catalog.json --deflate

# 3) confere (a assinatura é sobre o payload comprimido)
java tools/catalog/CatalogTool.java verify app/src/main/assets/catalog.json `
    tools/catalog/keys/sample.pub
```

Consolas cobertas: **GBA, Mega Drive, N64, PS1, PSP e SNES**.

O MD5 é a identidade do cartucho: com ele o app reconhece a ROM do usuário mesmo
renomeada, mostra o título oficial e a capa certa, e recusa um arquivo que não
seja o jogo escolhido.

## Chave de exemplo

```powershell
java tools/catalog/CatalogTool.java keygen sample tools/catalog/keys
```

A chave privada (seed) **não** vai para o repositório. Para trocar a chave:
gere um par novo, cole o `.pub` em `CatalogKeys.kt` (ou use
`RETROBOX_CATALOG_PUBLIC_KEY` no build) e assine de novo o catálogo.

## Catálogo remoto (operador)

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
  "format": 2,
  "keyId": "sample",
  "payload": "<base64 do payload (comprimido quando format=2)>",
  "signature": "<base64 Ed25519 (64 bytes) sobre os bytes do payload>"
}
```

## Regras do payload

`{ "version": Int, "updatedAt": String, "entries": [...] }` — cada entrada:

| Campo | Regra |
|---|---|
| `id` | `[a-z0-9][a-z0-9._-]{1,63}`, único |
| `title` | obrigatório |
| `console` | `nes`, `snes`, `gb`, `gbc`, `gba`, `megadrive`, `ps1`, `psp`, `n64`, `nds` |
| `url` | `https://` (ou `http://` em `127.0.0.1`/`localhost`/`10.0.2.2`, só dev) |
| `sha256` | **obrigatório quando há `url`** — é a única prova de integridade |
| `md5` / `crc32` | identidade do cartucho (usada para reconhecer a ROM do usuário) |
| `sizeBytes` | usado para reservar espaço e checar o download |
| `coverUrl` | `https://` opcional — sem ele o app monta a URL da box art pelo título |

`url: null` significa “traga seu próprio arquivo”: o app mostra o jogo e pede o
arquivo ao usuário.

## Segurança

- O seed do operador não entra no repositório nem no APK; assine em uma máquina
  confiável (idealmente offline) e publique só o envelope.
- Rotacionar chave exige uma nova versão do app (a chave pública está no APK).
  Mantenha a chave antiga em `CatalogKeys` durante a transição.
