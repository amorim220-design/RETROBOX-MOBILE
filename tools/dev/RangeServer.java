import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Servidor HTTP de desenvolvimento com suporte a `Range` (206), usado para
 * testar o downloader com retomada no aparelho.
 *
 * Uso:
 *   java tools/dev/RangeServer.java <pasta> [porta]
 *   adb reverse tcp:8000 tcp:8000
 *
 * Serve qualquer arquivo da pasta informada. Nao use em producao.
 */
public final class RangeServer {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
        if (!Files.isDirectory(root)) throw new IOException("pasta inexistente: " + root);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try {
                serve(exchange, root);
            } catch (Exception e) {
                respond(exchange, 500, ("erro: " + e.getMessage()).getBytes());
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("servindo " + root + " em http://127.0.0.1:" + port + "/");
    }

    private static void serve(HttpExchange exchange, Path root) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.txt";
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            respond(exchange, 404, "nao encontrado".getBytes());
            return;
        }

        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        String range = exchange.getRequestHeaders().getFirst("Range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0];
            String[] parts = spec.split("-", -1);
            try {
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                } else if (parts.length > 1) {
                    start = size - Long.parseLong(parts[1]);
                    end = size - 1;
                }
                if (start < 0 || start >= size) {
                    exchange.getResponseHeaders().add("Content-Range", "bytes */" + size);
                    respond(exchange, 416, new byte[0]);
                    return;
                }
                end = Math.min(end, size - 1);
                partial = true;
            } catch (NumberFormatException ignored) {
                partial = false;
            }
        }

        long length = end - start + 1;
        String query = exchange.getRequestURI().getQuery();
        long delayMs = 0;
        if (query != null && query.contains("delay=")) {
            try {
                delayMs = Long.parseLong(query.substring(query.indexOf("delay=") + 6).split("&")[0]);
            } catch (NumberFormatException ignored) {
                delayMs = 0;
            }
        }
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        if (partial) {
            exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        exchange.sendResponseHeaders(partial ? 206 : 200, length);
        try (OutputStream out = exchange.getResponseBody()) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            try (var input = Files.newInputStream(file)) {
                input.skipNBytes(start);
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    out.flush();
                    remaining -= read;
                    if (delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
        System.out.println((partial ? "206 " : "200 ") + path + " " + start + "-" + end + "/" + size);
    }

    private static void respond(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
