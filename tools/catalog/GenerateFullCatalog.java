import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o payload do catalogo completo do RetroBox a partir da base oficial do
 * libretro (libretro/libretro-database, arquivos .rdb).
 *
 * O .rdb e MessagePack: cabecalho "RARCHDB\0" + versao + numero de entradas e
 * depois um mapa msgpack por jogo (name, description, rom_name, size, crc,
 * md5, sha1). Aqui lemos so o que interessa:
 *
 *   name      -> titulo ("Super Mario Kart (USA) (Rev 1)")
 *   rom_name  -> nome original do arquivo ("Super Mario Kart (USA) (Rev 1).smc")
 *   size      -> tamanho em bytes
 *   crc       -> CRC32 (4 bytes)
 *   md5       -> MD5 (16 bytes)  <- identidade do jogo
 *
 * O resultado e o JSON do catalogo (metadados). Nenhuma ROM e baixada: apenas
 * nomes, tamanhos e hashes.
 *
 * Uso:
 *   java tools/catalog/GenerateFullCatalog.java &lt;saida.json&gt; [pastaDeCache]
 *
 * Consoles: GBA, Mega Drive, N64, PS1, PSP e SNES.
 */
public final class GenerateFullCatalog {

    /** Consolas do catalogo -> arquivo .rdb no libretro-database. */
    private record SystemDef(String console, String rdb, String thumbnails) { }

    private static final List<SystemDef> SYSTEMS = List.of(
        new SystemDef("gba", "Nintendo - Game Boy Advance.rdb", "Nintendo - Game Boy Advance"),
        new SystemDef("megadrive", "Sega - Mega Drive - Genesis.rdb", "Sega - Mega Drive - Genesis"),
        new SystemDef("n64", "Nintendo - Nintendo 64.rdb", "Nintendo - Nintendo 64"),
        new SystemDef("ps1", "Sony - PlayStation.rdb", "Sony - PlayStation"),
        new SystemDef("psp", "Sony - PlayStation Portable.rdb", "Sony - PlayStation Portable"),
        new SystemDef("snes", "Nintendo - Super Nintendo Entertainment System.rdb",
            "Nintendo - Super Nintendo Entertainment System")
    );

    private static final String RDB_BASE =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/rdb/";

    /** Chaves conhecidas nas entradas do .rdb (qualquer outra = dessincronia). */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
        "name", "description", "genre", "rom_name", "size", "releasemonth", "releaseyear",
        "developer", "publisher", "crc", "md5", "sha1", "players", "rating", "region",
        "serial", "title", "year", "comment", "manufacturer", "status", "category",
        "permissions", "lobby", "display_type", "runtime", "cheevos", "achievements",
        "cloneof", "similarto", "developer_notes", "notes"
    );

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "catalog-payload.json");
        Path cache = Paths.get(args.length > 1 ? args[1] : "tools/catalog/cache");
        Files.createDirectories(cache);

        List<String> jsonEntries = new ArrayList<>();
        int total = 0;

        for (SystemDef system : SYSTEMS) {
            Path file = cache.resolve(system.console() + ".rdb");
            if (!Files.exists(file) || Files.size(file) == 0) {
                System.out.println("baixando " + system.rdb() + "...");
                download(RDB_BASE + encode(system.rdb()), file);
            }
            List<Game> games = parse(file);

            int written = 0;
            for (Game game : games) {
                if (game.md5() == null || game.md5().isEmpty()) continue;
                if (game.title() == null || game.title().isBlank()) continue;
                String id = system.console() + "-" + game.md5();
                StringBuilder sb = new StringBuilder(260);
                sb.append("{\"id\":\"").append(id)
                  .append("\",\"title\":\"").append(escape(game.title()))
                  .append("\",\"console\":\"").append(system.console())
                  .append("\",\"fileName\":\"").append(escape(game.romName()))
                  .append("\",\"sizeBytes\":").append(game.size())
                  .append(",\"md5\":\"").append(game.md5()).append("\"")
                  .append(",\"crc32\":\"").append(game.crc())
                  .append("\",\"genre\":\"").append(escape(game.genre()))
                  .append("\",\"developer\":\"").append(escape(game.developer()))
                  .append("\",\"releaseYear\":").append(game.releaseYear())
                  .append("}");
                jsonEntries.add(sb.toString());
                written++;
            }
            total += written;
            System.out.printf("%-10s %6d jogos -> %6d entradas%n",
                system.console(), games.size(), written);
        }

        StringBuilder json = new StringBuilder(total * 200);
        json.append("{\n  \"version\": 2,\n  \"updatedAt\": \"")
            .append(java.time.LocalDateTime.now().withNano(0))
            .append("\",\n  \"entries\": [\n");
        for (int i = 0; i < jsonEntries.size(); i++) {
            json.append("    ").append(jsonEntries.get(i));
            if (i < jsonEntries.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");

        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        System.out.printf("%n%d entradas -> %s (%.1f MB)%n",
            total, out.toAbsolutePath(), Files.size(out) / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ rdb --

    private record Game(String title, String romName, long size, String md5, String crc,
                        String genre, String developer, int releaseYear) { }

    /** Le o .rdb (MessagePack) e devolve os jogos. */
    private static List<Game> parse(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 16 || !new String(data, 0, 7, StandardCharsets.US_ASCII).equals("RARCHDB")) {
            throw new IOException("arquivo .rdb invalido: " + file);
        }
        int count = (data[12] & 0xff) | ((data[13] & 0xff) << 8)
            | ((data[14] & 0xff) << 16) | ((data[15] & 0xff) << 24);

        MsgPack reader = new MsgPack(data, 16);
        String traceEntry = System.getenv("CATALOG_TRACE_ENTRY");
        int traceIndex = traceEntry == null ? -1 : Integer.parseInt(traceEntry);
        List<Game> games = new ArrayList<>(Math.max(16, Math.min(count, 1_000_000)));
        int lastEntryStart = 16;
        java.util.Set<String> unknownKeys = new java.util.LinkedHashSet<>();
        // O contador do cabecalho inclui o bloco de metadados no fim do arquivo;
        // por isso lemos ate o fim e paramos no mapa de metadados ("count").
        while (reader.pos < data.length) {
            int i = games.size();
            int entryStart = reader.pos;
            reader.trace = (i == traceIndex);
            Object map;
            try {
                map = reader.readValue();
            } catch (RuntimeException e) {
                StringBuilder hex = new StringBuilder();
                for (int k = lastEntryStart; k < Math.min(data.length, lastEntryStart + 150); k++) {
                    hex.append(String.format("%02x ", data[k] & 0xff));
                }
                throw new IOException("entrada " + i + " falhou (pos=" + reader.pos
                    + ", ultima chave='" + reader.lastKey + "')\n  entrada anterior em "
                    + lastEntryStart + ": " + hex, e);
            }
            lastEntryStart = entryStart;
            if (!(map instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            if (!entry.containsKey("name") && entry.containsKey("count")) {
                // bloco de metadados no fim do arquivo
                break;
            }
            for (Object key : entry.keySet()) {
                if (!KNOWN_KEYS.contains(String.valueOf(key))) {
                    unknownKeys.add(String.valueOf(key));
                }
            }
            String name = asString(entry.get("name"));
            String romName = asString(entry.get("rom_name"));
            long size = asLong(entry.get("size"));
            String md5 = asHex(entry.get("md5"));
            String crc = asHex(entry.get("crc"));
            String genre = asString(entry.get("genre"));
            String developer = asString(entry.get("developer"));
            int releaseYear = (int) asLong(entry.get("releaseyear"));
            if (name == null) continue;
            games.add(new Game(name, romName == null ? name : romName, size, md5, crc,
                genre == null ? "" : genre, developer == null ? "" : developer, releaseYear));
        }
        if (!unknownKeys.isEmpty()) {
            System.out.println("  chaves extras encontradas: " + unknownKeys);
        }
        return games;
    }

    private static String asString(Object value) {
        return (value instanceof String s) ? s : null;
    }

    private static long asLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i;
        return 0L;
    }

    /** Bytes binarios -> hex minusculo. */
    private static String asHex(Object value) {
        if (value instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        if (value instanceof String s) return s;
        return "";
    }

    /** Parser MessagePack (subconjunto usado pelo .rdb). */
    private static final class MsgPack {
        private final byte[] data;
        private int pos;
        private String lastKey = "";
        boolean trace;

        MsgPack(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        Object readValue() {
            int b = data[pos++] & 0xff;
            if (b <= 0x7f) return (long) b;                       // positive fixint
            if (b >= 0xe0) return (long) (b - 256);               // negative fixint
            if ((b & 0xf0) == 0x80) return readMap(b & 0x0f);     // fixmap
            if ((b & 0xf0) == 0x90) return readArray(b & 0x0f);   // fixarray
            if ((b & 0xe0) == 0xa0) return readString(b & 0x1f);  // fixstr
            return switch (b) {
                case 0xc0 -> null;                                // nil
                case 0xc2 -> Boolean.FALSE;
                case 0xc3 -> Boolean.TRUE;
                case 0xc4 -> readBinary(data[pos++] & 0xff);
                case 0xc5 -> readBinary(readU16());
                case 0xc6 -> readBinary(readU32());
                case 0xca -> { pos += 4; yield 0.0d; }
                case 0xcb -> { pos += 8; yield 0.0d; }
                case 0xcc -> (long) (data[pos++] & 0xff);
                case 0xcd -> (long) readU16();
                case 0xce -> (long) readU32();
                case 0xcf -> readU64();
                case 0xd0 -> readSigned(1);
                case 0xd1 -> readSigned(2);
                case 0xd2 -> readSigned(4);
                case 0xd3 -> readSigned(8);
                case 0xd9 -> readString(data[pos++] & 0xff);
                case 0xda -> readString(readU16());
                case 0xdb -> readString((int) readU32());
                case 0xdc -> readArray(readU16());
                case 0xdd -> readArray((int) readU32());
                case 0xde -> readMap(readU16());
                case 0xdf -> readMap((int) readU32());
                default -> throw new IllegalStateException("msgpack nao suportado: 0x" + Integer.toHexString(b));
            };
        }

        private Map<String, Object> readMap(int size) {
            Map<String, Object> map = new LinkedHashMap<>(Math.max(4, size));
            for (int i = 0; i < size; i++) {
                int keyPos = pos;
                Object key = readValue();
                lastKey = String.valueOf(key);
                if (trace) {
                    System.out.println("    chave '" + lastKey + "' @ " + keyPos + " (len " + (size) + ")");
                }
                if (!(key instanceof String)) {
                    throw new IllegalStateException("chave nao textual na pos " + keyPos
                        + " (valor " + key + ")");
                }
                Object value = readValue();
                if (trace) {
                    StringBuilder raw = new StringBuilder();
                    for (int k = Math.max(0, keyPos - 4); k < Math.min(data.length, keyPos + 10); k++) {
                        raw.append(String.format("%02x ", data[k] & 0xff));
                    }
                    System.out.println("      valor @ " + pos + " tipo "
                        + (value == null ? "null" : value.getClass().getSimpleName())
                        + (value instanceof String s ? " '" + (s.length() > 40 ? s.substring(0, 40) : s) + "'" : "")
                        + " bytes: " + raw);
                }
                map.put(lastKey, value);
            }
            return map;
        }

        private List<Object> readArray(int size) {
            List<Object> list = new ArrayList<>(Math.max(4, size));
            for (int i = 0; i < size; i++) list.add(readValue());
            return list;
        }

        private String readString(int length) {
            String s = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return s;
        }

        private byte[] readBinary(int length) {
            byte[] out = new byte[length];
            System.arraycopy(data, pos, out, 0, length);
            pos += length;
            return out;
        }

        private int readU16() {
            // msgpack e big-endian
            int v = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            pos += 2;
            return v;
        }

        private int readU32() {
            int v = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        private long readU64() {
            long v = 0L;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[pos + i] & 0xff);
            pos += 8;
            return v;
        }

        private long readSigned(int bytes) {
            long v = 0L;
            for (int i = 0; i < bytes; i++) v = (v << 8) | (data[pos + i] & 0xff);
            pos += bytes;
            return v;
        }
    }

    // ---------------------------------------------------------------- util ---

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "RetroBoxCatalogBuilder/1.0")
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " para " + url);
        }
        Files.write(target, response.body());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
