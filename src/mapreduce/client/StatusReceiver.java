package mapreduce.client;

import ecs.NodeInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocol.mapreduce.CallbackInfo;
import util.Validate;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static protocol.Constants.MR_TASK_HANDLER_PORT_DISTANCE;

public class StatusReceiver implements Runnable {
    private static Logger LOG = LogManager.getLogger(Driver.MAPREDUCE_LOG);

    private static final int PORT_UPPER = 20000;
    private static final int PORT_LOWER = 10000;
    private boolean running;

    private ServerSocket callbackSocket;
    private int port;

    private CallbackInfo callbackInfo;
    private Set<String> expectedConnections;
    private HashSet<Thread> workerConnections;

    private Driver driver;

    public StatusReceiver(Driver driver, List<NodeInfo> nodes) {
        this.driver = driver;
        expectedConnections = nodes.stream().map(node -> "/"+node.getHost()+":"+ (node.getPort() + MR_TASK_HANDLER_PORT_DISTANCE)).collect(Collectors.toSet());
        running = openCallbackSocket();
    }

    private int getRandomPort() {
        return (int) (Math.random() * ((PORT_UPPER - PORT_LOWER) + 1)) + PORT_LOWER;
    }

    public Set<String> getExpectedConnections() {
        return expectedConnections;
    }

    public CallbackInfo getCallbackInfo() {
        return callbackInfo;
    }


    public boolean isRunning() {
        return running;
    }

    public ConcurrentHashMap<String, String> getOutputCollection() {
        return driver.getOutputs();
    }

    @Override
    public void run() {
        Validate.isTrue(!expectedConnections.isEmpty(), "expectedConnections is empty");
        workerConnections = new HashSet<>(expectedConnections.size());

        LOG.info("expectedConnections " + Arrays.toString(expectedConnections.toArray()));
        LOG.info("Is running? " + running);
        while (running) {
            if (expectedConnections.size() <= 0)
                break;
            try {
                LOG.info("Waiting for worker connection");
                Socket worker = callbackSocket.accept();
                boolean isRemoved = expectedConnections.remove(worker.getRemoteSocketAddress().toString());
                if (!isRemoved) {
                    LOG.warn("Ignore unexpected connection");
                    continue;
                }

                WorkerConnection workerConnection = new WorkerConnection(this, worker);
                Thread t = new Thread(workerConnection);
                workerConnections.add(t);
                t.start();
                LOG.info("A WorkerConnection started");
                LOG.info("Connected to " + worker.getInetAddress().getHostName() + " on servicePort " + callbackSocket.getLocalPort());
            } catch (IOException e) {
                LOG.error("Error! " + "Server socket interrupted. \n", e);
                running = false;
            }
        }
        LOG.info("No expected connections left. StatusReceiver stopped listening to connections. StatusReceiver is now wating for all WorkerConnections to finish.");
        waitWorkerConnections();
        try {
            callbackSocket.close();
            callbackSocket = null;
            LOG.info("Callback socket closed.");
        } catch (IOException e) {
            LOG.error(e);
        }
    }

    private void waitWorkerConnections() {
        for (Thread t : workerConnections) {
            try {
                if (!t.isAlive())
                    continue;
                t.join();
            } catch (InterruptedException e) {
                LOG.error(e);
            }
        }
        LOG.info("Clearing all worker connections");
        workerConnections.clear();
    }

    private boolean openCallbackSocket() {
        final int RETRY_NUMBER = 3;
        for (int i = 0; i < RETRY_NUMBER; i++) {
            try {
                port = getRandomPort();
                callbackSocket = new ServerSocket(port);
                LOG.info("StatusReceiver is listening on servicePort: " + callbackSocket.getLocalPort() + " for MapReduce jobs");
                callbackInfo = new CallbackInfo(callbackSocket.getInetAddress().getHostAddress(), port);
                return true;
            } catch (IOException e) {
                LOG.error("Error occurs when opening socket on StatusReceiver", e);
                if (e instanceof BindException) {
                    LOG.error("Port " + port + " is already bound!", e);
                }
            }
        }
        return false;
    }
}
