// File: p2p/controller/FileController.java
package p2p.controller;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.fileupload.FileUploadBase;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.util.Streams;

import p2p.adapter.HttpExchangeRequestContext;
import p2p.service.FileSharer;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;
    private final ScheduledExecutorService cleaner; // for timeout cleanup

    // Timeout in milliseconds (e.g. 10 minutes)
    private static final long FILE_TTL = TimeUnit.MINUTES.toMillis(5);

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = "/tmp/peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);
        this.cleaner = Executors.newSingleThreadScheduledExecutor();

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);

        // Schedule cleanup every 5 minutes
        cleaner.scheduleAtFixedRate(this::cleanupOldFiles, 5, 5, TimeUnit.MINUTES);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        cleaner.shutdown();
        System.out.println("API server stopped");
    }

    /** Deletes files older than FILE_TTL */
    private void cleanupOldFiles() {
        File dir = new File(uploadDir);
        File[] files = dir.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > FILE_TTL) {
                System.out.println("Deleting expired file: " + f.getName());
                f.delete();
            }
        }
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
    }



// ...

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                byte[] response = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                return;
            }

            try {
                FileUpload upload = new FileUpload();
                upload.setFileSizeMax(-1);  // allow single files of any size
                upload.setSizeMax(500L * 1024 * 1024); // total request size limit

                FileItemIterator iter = upload.getItemIterator(new HttpExchangeRequestContext(exchange));
                String storedFilePath = null;

                while (iter.hasNext()) {
                    FileItemStream item = iter.next();
                    if (!item.isFormField()) {
                        String originalFilename = item.getName();
                        if (originalFilename == null || originalFilename.trim().isEmpty()) {
                            originalFilename = "unnamed-file";
                        }

                        String uniqueFilename = UUID.randomUUID().toString() + "_" + new File(originalFilename).getName();
                        File file = new File(uploadDir, uniqueFilename);

                        try (InputStream input = item.openStream();
                             FileOutputStream fos = new FileOutputStream(file)) {
                            Streams.copy(input, fos, true);
                        }  catch (org.apache.commons.fileupload.FileUploadBase.FileUploadIOException ex) {
                            if (ex.getCause() instanceof org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException) {
                                sendTooLarge(exchange);
                                return;
                            }
                            throw ex;
                        }

                        storedFilePath = file.getAbsolutePath();
                    }
                }

                if (storedFilePath == null) {
                    byte[] response = "Bad Request: No file uploaded".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, response.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                    return;
                }

                int port = fileSharer.offerFile(storedFilePath);
                String jsonResponse = "{\"port\": " + port + "}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(jsonResponse.getBytes(StandardCharsets.UTF_8)); }

            } catch (org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException e) {
                sendTooLarge(exchange);
            } catch (org.apache.commons.fileupload.FileUploadBase.FileUploadIOException e) {
                if (e.getCause() instanceof org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException) {
                    sendTooLarge(exchange);
                } else {
                    sendServerError(exchange, e);
                }
            } catch (Exception e) {
                sendServerError(exchange, e);
            }

        }

        private void sendServerError(HttpExchange exchange, Exception e) throws IOException {
            String msg = "Upload failed: " + e.getMessage();
            exchange.sendResponseHeaders(500, msg.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes(StandardCharsets.UTF_8));
            }
            e.printStackTrace();
        }
        private void sendTooLarge(HttpExchange exchange) throws IOException {
            String msg = "File too large. Maximum allowed is 500MB.";
            exchange.sendResponseHeaders(413, msg.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("Rejected upload > 500MB");
        }
    }

    private class DownloadHandler implements HttpHandler {
        private String guessMimeType(File file) {
            try {
                String mime = Files.probeContentType(file.toPath());
                if (mime != null) return mime;
            } catch (IOException ignored) {}

            // fallback by extension
            String name = file.getName().toLowerCase();
            if (name.endsWith(".mkv")) return "video/x-matroska";
            if (name.endsWith(".mp4")) return "video/mp4";
            if (name.endsWith(".avi")) return "video/x-msvideo";
            if (name.endsWith(".zip")) return "application/zip";
            if (name.endsWith(".rar")) return "application/vnd.rar";
            if (name.endsWith(".7z")) return "application/x-7z-compressed";
            if (name.endsWith(".pdf")) return "application/pdf";
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".gif")) return "image/gif";
            if (name.endsWith(".mp3")) return "audio/mpeg";
            if (name.endsWith(".wav")) return "audio/wav";

            return "application/octet-stream"; // safe default
        }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            headers.set("Access-Control-Expose-Headers", "Content-Disposition");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                byte[] response = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String code = path.substring(path.lastIndexOf('/') + 1);

            String filePath = fileSharer.getFile(code);
            if (filePath == null) {
                byte[] response = "Invalid code".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                return;
            }

            File file = new File(filePath);
            if (!file.exists()) {
                byte[] response = "File not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                return;
            }

            String filename = file.getName();
            String safeName = filename.replace("\"", "");
            String encodedName = java.net.URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
            String contentDisp = "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName;
            headers.set("Content-Disposition", contentDisp);

            String mime = guessMimeType(file);
            headers.set("Content-Type", mime);
            headers.set("X-Content-Type-Options", "nosniff");

            long len = file.length();
            exchange.sendResponseHeaders(200, len);
            try (OutputStream os = exchange.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = fis.read(buf)) != -1) os.write(buf, 0, r);
            }

            // ✅ Delete file after serving
            System.out.println("Deleting file after download: " + file.getName());
            file.delete();
        }
    }
}