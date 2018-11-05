package server.app;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import server.api.ClientConnection;
import server.storage.cache.CacheDisplacementStrategy;
import server.storage.CacheManager;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Represents a simple Echo Server implementation.
 */
public class Server extends Thread {

    private static Logger logger = LogManager.getLogger(ClientConnection.class);

    private int port;
    private ServerSocket serverSocket;
    private boolean running;
    private CacheManager cm;

    /**
     * Start KV Server at given port
     *
     * @param port      given port for disk server to operate
     * @param cacheSize specifies how many key-value pairs the server is allowed
     *                  to keep in-memory
     * @param strategy  specifies the storage replacement strategy in case the storage
     *                  is full and there is a GET- or PUT-request on a key that is
     *                  currently not contained in the storage. Options are "FIFO", "LRU",
     *                  and "LFU".
     */
    public Server(int port, int cacheSize, CacheDisplacementStrategy strategy) {
        this.port = port;
        this.cm = new CacheManager(cacheSize, strategy);
        System.out.println("Server started on port " + this.port + " with cache size " + cacheSize + " and cache strategy " + strategy.name());
    }


    /**
     * Initializes and starts the server.
     * Loops until the the server should be closed.
     */
    public void run() {
        running = initializeServer();
        if (serverSocket != null) {
            while (isRunning()) {
                try {
                    Socket client = serverSocket.accept();
                    ClientConnection connection = new ClientConnection(client, cm);
                    new Thread(connection).start();

                    logger.info("Connected to "
                            + client.getInetAddress().getHostName()
                            + " on port " + client.getPort());
                } catch (IOException e) {
                    logger.error("Error! " +
                            "Unable to establish connection. \n", e);
                }
            }
        }
        logger.info("Server stopped.");
    }

    private boolean isRunning() {
        return this.running;
    }

    /**
     * Stops the server insofar that it won't listen at the given port any more.
     */
    public void stopServer() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            logger.error("Error! " +
                    "Unable to close socket on port: " + port, e);
        }
    }

    private boolean initializeServer() {
        logger.info("Initialize server ...");
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Server listening on port: "
                    + serverSocket.getLocalPort());
            return true;
        } catch (IOException e) {
            logger.error("Error! Cannot open server socket:");
            if (e instanceof BindException) {
                logger.error("Port " + port + " is already bound!");
            }
            return false;
        }
    }

    /**
     * Main entry point for the echo server application.
     *
     * @param args contains the port number at args[0].
     */
    public static void main(String[] args) {
        try {
            /*TODO adapt logging with LogSetup
            new LogSetup("logs/server.log", Level.ALL);*/

            if (args.length != 1) {
                System.out.println("Error! Invalid number of arguments!");
                System.out.println("Usage: Server <port>!");
            } else {
                int port = Integer.parseInt(args[0]);
                new Server(port, 100, CacheDisplacementStrategy.FIFO).start();
            }
        }
        /* TODO adapt logging with LogSetup
        catch (IOException e) {
            System.out.println("Error! Unable to initialize logger!");
            e.printStackTrace();
            System.exit(1);
        } */ catch (NumberFormatException nfe) {
            System.out.println("Error! Invalid argument <port>! Not a number!");
            System.out.println("Usage: Server <port>!");
            System.exit(1);
        }
    }
}