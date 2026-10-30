import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Ferramenta de linha de comando do catalogo assinado do RetroBox.
 *
 * Faz o que o app so sabe verificar: gerar par de chaves Ed25519, assinar o
 * payload e conferir o envelope resultante. Nao tem dependencia externa
 * (JDK 17+ ja traz Ed25519; testado no JDK 21).
 *
 * Uso (Windows, a partir da raiz do repositorio):
 *
 *   java tools/catalog/CatalogTool.java keygen sample tools/catalog/keys
 *   java tools/catalog/CatalogTool.java sign catalog-payload.json sample \
 *        tools/catalog/keys/sample.seed app/src/main/assets/catalog.json
 *   java tools/catalog/CatalogTool.java verify app/src/main/assets/catalog.json \
 *        tools/catalog/keys/sample.pub
 *   java tools/catalog/CatalogTool.java pin catalog-payload.json
 *
 * Formato do envelope (o mesmo que CatalogVerifier.kt le):
 *
 *   {
 *     "format": 1,
 *     "keyId": "sample",
 *     "payload": "<base64 do JSON do catalogo>",
 *     "signature": "<base64 da assinatura Ed25519 sobre os bytes do payload>"
 *   }
 */
public final class CatalogTool {

    /** Prefixo ASN.1 de uma chave publica Ed25519 em SPKI. */
    private static final byte[] SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(args);
            case "sign" -> sign(args);
            case "verify" -> verify(args);
            case "pin" -> pin(args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
            uso:
              keygen <keyId> [dirDeSaida]           gera <keyId>.seed e <keyId>.pub
              sign   <payload.json> <keyId> [seed] [saida.json]
              verify <catalogo.json> <pubBase64|pubFile>
              pin    <payload.json>                 imprime o SHA-256 do payload

            O seed e a chave privada: guarde fora do repositorio. O .pub e o
            valor que vai em CatalogKeys.kt (ou em RETROBOX_CATALOG_PUBLIC_KEY).
            """);
    }

    // ------------------------------------------------------------- keygen ----

    private static void keygen(String[] args) throws Exception {
        String keyId = args[1];
        Path dir = Paths.get(args.length > 2 ? args[2] : ".");
        Files.createDirectories(dir);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();

        byte[] spki = pair.getPublic().getEncoded();
        if (spki.length != SPKI_PREFIX.length + 32) {
            throw new IllegalStateException("chave publica inesperada: " + spki.length + " bytes");
        }
        byte[] rawPublic = Arrays.copyOfRange(spki, SPKI_PREFIX.length, spki.length);

        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);

        Path seedFile = dir.resolve(keyId + ".seed");
        Path pubFile = dir.resolve(keyId + ".pub");
        writeSecret(seedFile, Base64.getEncoder().encodeToString(seed) + "\n");
        Files.writeString(pubFile, Base64.getEncoder().encodeToString(rawPublic) + "\n");

        System.out.println("keyId ........ " + keyId);
        System.out.println("seed ......... " + seedFile.toAbsolutePath() + "   (NAO commitar)");
        System.out.println("publica ...... " + pubFile.toAbsolutePath());
        System.out.println();
        System.out.println("cole em CatalogKeys.kt:");
        System.out.println("  \"" + keyId + "\" to \"" + Base64.getEncoder().encodeToString(rawPublic) + "\"");
    }

    // --------------------------------------------------------------- sign ----

    private static void sign(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(2);
        }
        Path payloadFile = Paths.get(args[1]);
        String keyId = args[2];
        Path seedFile = args.length > 3
            ? Paths.get(args[3])
            : Paths.get("tools/catalog/keys", keyId + ".seed");
        byte[] payload = Files.readAllBytes(payloadFile);
        if (payload.length == 0) throw new IOException("payload vazio: " + payloadFile);

        PrivateKey key = privateKey(Files.readString(seedFile).trim());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload);
        byte[] signature = signer.sign();

        String envelope = "{\n"
            + "  \"format\": 1,\n"
            + "  \"keyId\": \"" + escape(keyId) + "\",\n"
            + "  \"payload\": \"" + Base64.getEncoder().encodeToString(payload) + "\",\n"
            + "  \"signature\": \"" + Base64.getEncoder().encodeToString(signature) + "\"\n"
            + "}\n";

        if (args.length > 4) {
            Path out = Paths.get(args[4]);
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, envelope);
            System.out.println("envelope escrito em " + out.toAbsolutePath());
        } else {
            System.out.print(envelope);
        }
        System.out.println("payload ....... " + payload.length + " bytes");
        System.out.println("assinatura .... " + signature.length + " bytes");
        System.out.println("sha-256 ....... " + sha256Hex(payload));
    }

    // ------------------------------------------------------------- verify ----

    private static void verify(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(2);
        }
        String envelope = Files.readString(Paths.get(args[1]));
        String publicSpec = args[2];
        byte[] raw;
        if (Files.exists(Paths.get(publicSpec))) {
            raw = Base64.getDecoder().decode(Files.readString(Paths.get(publicSpec)).trim());
        } else {
            raw = Base64.getDecoder().decode(publicSpec.trim());
        }
        if (raw.length != 32) throw new IOException("chave publica deve ter 32 bytes (tem " + raw.length + ")");

        String keyId = extract(envelope, "keyId");
        byte[] payload = Base64.getDecoder().decode(extract(envelope, "payload"));
        byte[] signature = Base64.getDecoder().decode(extract(envelope, "signature"));

        PublicKey key = publicKey(raw);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(payload);
        boolean ok = verifier.verify(signature);

        System.out.println("keyId ......... " + keyId);
        System.out.println("payload ....... " + payload.length + " bytes");
        System.out.println("sha-256 ....... " + sha256Hex(payload));
        System.out.println(ok ? "ASSINATURA OK" : "ASSINATURA INVALIDA");
        if (!ok) System.exit(1);
    }

    // ---------------------------------------------------------------- pin ----

    private static void pin(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        byte[] payload = Files.readAllBytes(Paths.get(args[1]));
        System.out.println(sha256Hex(payload));
    }

    // ------------------------------------------------------------ internos ----

    private static PrivateKey privateKey(String seedBase64) throws Exception {
        byte[] seed = Base64.getDecoder().decode(seedBase64);
        if (seed.length != 32) throw new IOException("seed deve ter 32 bytes (tem " + seed.length + ")");
        return KeyFactory.getInstance("Ed25519")
            .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
    }

    private static PublicKey publicKey(byte[] raw) throws Exception {
        byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    /** Extrai um campo string plano do envelope (sem dependencia de JSON). */
    private static String extract(String json, String field) throws IOException {
        String marker = "\"" + field + "\"";
        int at = json.indexOf(marker);
        if (at < 0) throw new IOException("campo '" + field + "' ausente no envelope");
        int colon = json.indexOf(':', at + marker.length());
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (colon < 0 || firstQuote < 0 || secondQuote < 0) {
            throw new IOException("campo '" + field + "' malformado");
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void writeSecret(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            // POSIX: so o dono le (no Windows isso lanca e o catch ignora)
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows
        }
    }
}
