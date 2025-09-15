package p2p;

import p2p.controller.FileController;
import java.io.IOException;

public class App {
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            FileController fileController = new FileController(port);
            fileController.start();

            System.out.println("PeerLink server started on port " + port);
            System.out.println("Upload: POST http://localhost:" + port + "/upload");
            System.out.println("Download: GET http://localhost:" + port + "/download/{code}");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                fileController.stop();
            }));

            System.out.println("Press Enter to stop the server");
            System.in.read();

        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
