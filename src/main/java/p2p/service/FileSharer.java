package p2p.service;

import java.io.File;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

import p2p.utils.UploadUtils;

public class FileSharer {

    private final HashMap<String, String> availableFiles;

    public FileSharer() {
        availableFiles = new HashMap<>();
    }

    public int offerFile(String filePath) {
        int code;
        Random random = new Random();
        do {
            code = 1 + random.nextInt(65535); // range: 1–65535
        } while (availableFiles.containsKey(String.valueOf(code)));

        availableFiles.put(String.valueOf(code), filePath);
        return code; // return as int so backend can send numeric JSON
    }

    public String getFile(String code) {
        return availableFiles.get(code);
    }

    @Deprecated
    public void startFileServer(int port) {
        String filePath = availableFiles.get(port);
        if (filePath == null) {
            // Logic to start the file server using the filePath
            System.out.println("No file found for port: " + port);
            return;
            // Here you would implement the actual server logic
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serving file " + new File(filePath).getName() + " on port " + port);
            Socket socket = serverSocket.accept();
            System.out.println("Client connected: " + socket.getInetAddress());
            new Thread(new FileSenderHandler(socket, filePath)).start();
        } catch (Exception e) {
            System.err.println("Error starting file server on port " + port + ": " + e.getMessage());
        }
    }

    private static class FileSenderHandler implements Runnable {
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try (FileInputStream fis = new FileInputStream(filePath);
                 OutputStream oss = clientSocket.getOutputStream()) {

                // Send the filename as a header
                String filename = new File(filePath).getName();
                String header = "Filename: " + filename + "\n";
                oss.write(header.getBytes());

                // Send the file content
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    oss.write(buffer, 0, bytesRead);
                }
                System.out.println("File '" + filename + "' sent to " + clientSocket.getInetAddress());
            } catch (IOException e) {
                System.err.println("Error sending file to client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }
    }
}