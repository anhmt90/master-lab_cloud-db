package ecs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import util.Validate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static util.StringUtils.WHITE_SPACE;

/**
 * Manages the storage service and the connection to its nodes
 */
public class ExternalConfigurationService implements IECS {
    public static final String ECS_LOG = "ECS";
    private static Logger LOG = LogManager.getLogger(ECS_LOG);


    /**
     * Structures keeping track of the servers in the storage service and their connections
     */
    private NodesChord chord;
    private List<KVServer> serverPool = new ArrayList<>();

    private boolean isRingUp = false;
    private boolean serving = false;

    /**
     * Standard parameters for server failure restructuring
     */
    private static final String DEFAULT_REPLACEMENT_STRATEGY = "FIFO";
    private static final int DEFAULT_CACHE_SIZE = 1000;
    public static final int REPORT_PORT = 12345;
    public static final String ECS_ADDRESS = "127.0.0.1";
    private FailureReportPortal reportManager;

    /**
     * Sends the updated local metadata to all servers participating in the storage service
     */
    private void broadcastMetadata(KVServer... excld) {
        Set<KVServer> excluded = new HashSet<>(Arrays.asList(excld));
        Metadata md = chord.getMetadata();
        for (KVServer kvServer : this.chord.nodes()) {
            if (excluded.contains(kvServer))
                continue;
            boolean success = kvServer.update(md);
            Validate.isTrue(success, "Server " + kvServer.getServerId() + " couldn't update metadata");
        }
    }

    @Override
    public void initService(int numberOfNodes, int cacheSize, String displacementStrategy) {
        if (numberOfNodes > serverPool.size()) {
            LOG.warn("Number of available servers is less than chosen to initialize. Starting all available servers");
            numberOfNodes = this.serverPool.size();
        }

        LOG.debug("Picking servers to initialize");
//        pickEquallyServersWithOddAndEvenPort(numberOfNodes);
        pickServersRandomly(numberOfNodes);

        Validate.isTrue(chord.nodes().size() == numberOfNodes, "Not enough nodes are added. numberOfNodes=" + numberOfNodes + " while serverPool=" + serverPool.size() + "and chord=" + chord.size());
        chord.calcMetadata();

        LOG.debug("Launching selected servers");
        for (KVServer kvServer : chord.nodes()) {
            kvServer.launch(launched -> {
                if (launched) {
                    LOG.debug(String.format("Server %s:%d launched with admin port %d", kvServer.getHost(), kvServer.getServicePort(), kvServer.getAdminPort()));
                    kvServer.init(chord.getMetadata(), cacheSize, displacementStrategy);
                } else {
                    LOG.error("Couldn't initialize server " + kvServer.getServerId() + ", bringing it back to server pool");
                    putBackToPool(kvServer);
                    return;
                }
            });
        }
        if (chord.size() == numberOfNodes)
            isRingUp = true;
    }

    private void pickServersRandomly(int numberOfNodes) {
        while (chord.size() < numberOfNodes) {
            int n = ThreadLocalRandom.current().nextInt(serverPool.size());
            KVServer kvS = this.serverPool.get(n);

            this.chord.add(kvS);
            this.serverPool.remove(n);
        }
    }

    private void pickEquallyServersWithOddAndEvenPort(int numberOfNodes) {
        int prevSelectedServicePort = -1;
        while (chord.size() < numberOfNodes) {
            int n = ThreadLocalRandom.current().nextInt(serverPool.size());
            KVServer kvS = this.serverPool.get(n);

            if (chord.size() == 0) {
                prevSelectedServicePort = kvS.getServicePort();
            } else if (kvS.getServicePort() % 2 == prevSelectedServicePort % 2)
                continue;

            this.chord.add(kvS);
            this.serverPool.remove(n);
            prevSelectedServicePort = kvS.getServicePort();

        }
    }


    @Override
    public void startService() {
        int numServerStarted = 0;
        for (KVServer kvServer : chord.nodes()) {
            boolean success = kvServer.startServer();
            if (!success) {
                LOG.error(kvServer + " couldn't start");
                break; //TODO Handle reconnecting or picking another idle server to start instead
            }
            numServerStarted++;
        }
        if (numServerStarted == chord.size() && chord.size() > 0)
            serving = true;
    }

    @Override
    public void stopService() {
        int numServerStopped = 0;
        for (int i = 0; i < chord.size(); i++) {
            KVServer kvServer = chord.nodes().get(i);
            boolean success = kvServer.stopServer();
            if (!success) {
                LOG.error(kvServer + " couldn't stop");
                break; //TODO Handle trying to reconnect to the server
            }
            numServerStopped++;
        }
        if (numServerStopped == chord.size() && chord.size() > 0)
            serving = false;
    }

    @Override
    public void shutdown() {
        int numServerShutdown = chord.size();
        for (KVServer kvServer : chord.nodes()) {
            if (!kvServer.isLaunched())
                continue;

            boolean success = kvServer.shutdown();
            if (!success) {
                continue; //TODO Handle trying to reconnect to the server
            }
            putBackToPool(kvServer);
            kvServer.setLaunched(!success);
            numServerShutdown--;
        }


        if (numServerShutdown != 0) {
            LOG.warn("Couldn't shut all server down!!!");
        }
        handleAfterShutdown();
    }

    private void handleAfterShutdown() {
        if (closeSockets()) {
            serving = false;
            isRingUp = false;
        }
    }

    public void shutdown(String serverId) {
        for (KVServer kvServer : chord.nodes()) {
            if (!kvServer.getServerId().toLowerCase().equals(serverId))
                continue;
            boolean success = kvServer.shutdown();

            if (success)
                putBackToPool(kvServer);

            if (chord.size() == 0)
                handleAfterShutdown();
        }
    }

    private void putBackToPool(KVServer kvServer) {
        chord.remove(kvServer);
        serverPool.add(kvServer);
    }

    private boolean closeSockets() {
        for (KVServer kvServer : chord.nodes()) {
            try {
                kvServer.closeSocket();
            } catch (IOException e) {
                LOG.error("Couldn't close socket or streams of " + kvServer);
                return false;
            }
        }
        return true;
    }

    @Override
    public void addNode(int cacheSize, String displacementStrategy) {
        int n = ThreadLocalRandom.current().nextInt(this.serverPool.size());
        KVServer newNode = this.serverPool.get(n);

        serverPool.remove(newNode);
        chord.add(newNode);
        chord.calcMetadata();

        newNode.launch(launched -> {
            if (launched) {
                boolean done = newNode.init(chord.getMetadata(), cacheSize, displacementStrategy);
                Validate.isTrue(done, "Init failed!");

                done = newNode.startServer();
                Validate.isTrue(done, "Start failed!");

                done = newNode.lockWrite();
                Validate.isTrue(done, "lock write on new node failed!");

                // TODO handles case when done = false e.g. retry and add another server instead
                broadcastMetadata(newNode);

                done = newNode.unlockWrite();
                Validate.isTrue(done, "lock write on new node failed!");
            }
        });

    }

    @Override
    public void removeNode() {
        boolean done;
        int n = ThreadLocalRandom.current().nextInt(chord.size());
        KVServer nodeToRemove = chord.nodes().get(n);
        KVServer predecessor2 = chord.getNthPredecessor(nodeToRemove.getHashKey(), 2);

        putBackToPool(nodeToRemove);
        chord.calcMetadata();

        done = nodeToRemove.shutdown();
        Validate.isTrue(done, "shutdown nodeToRemove failed!");

        broadcastMetadata();
    }

    private KeyHashRange getWriteRange(KVServer node) {
        return chord.getMetadata().getCoordinator(node.getHashKey()).getWriteRange();
    }


    /**
     * Handles failure of a node in the storage service by removing it first from the ring and then later
     * trying to re-add it or another node with standard parameters from the server pool
     *
     * @param failedServerRange KeyHashRange of the failed server reported by one of the Replicas
     */
    public boolean handleFailure(KeyHashRange failedServerRange) {
        KVServer failedNode = chord.findByHashKey(failedServerRange.getEnd());
        if (failedNode == null) {
            LOG.error(new IllegalStateException("Failed node not found in chord. Possible false report or node was removed properly. HashKey of failed node:" + failedServerRange.getEnd()));
            return false;
        }

        try {
            failedNode.closeSocket();
        } catch (IOException ex) {
            LOG.error("Failed to close failed socket");
        }
        chord.remove(failedNode);
        chord.calcMetadata();
        broadcastMetadata();

        //TODO: Try to restart failed server and add it instead
        addNode(failedNode.getCacheSize(), failedNode.getDisplacementStrategy());
        return true;
    }


    public ExternalConfigurationService(String configFile) throws IOException {
        chord = new NodesChord();
        List<String> lines = Files.readAllLines(Paths.get(configFile));
        Set<String> serverIds = new HashSet(lines.size());

        Collections.shuffle(lines);
        for (String line : lines) {
            String[] serverParams = line.split(WHITE_SPACE);
            String serverId = serverParams[0];
            String serverHost = serverParams[1];
            String serverPort = serverParams[2];
            String mgmtPort = serverParams[3];
            KVServer kvS = new KVServer(serverId, serverHost, serverPort, mgmtPort);
            serverPool.add(kvS);
            if (serverIds.contains(serverId))
                throw new IllegalArgumentException("Duplicated node ID! serverId " + serverId + " already assigned");

            serverIds.add(serverId);
        }

        reportManager = new FailureReportPortal(this);
        new Thread(reportManager).start();
    }

    /**
     * Checks if the storage service is currently isRingUp.
     *
     * @return true if service is isRingUp
     */
    public boolean isRingUp() {
        return isRingUp;
    }

    /**
     * Checks if the storage service is currently offering the service to the client.
     *
     * @return true if service is being offered to the client
     */
    public boolean isServing() {
        return isRingUp && serving;
    }

    /**
     * Checks if the current List of active nodes is empty
     *
     * @return boolean true if service has no active nodes
     */
    public boolean isEmpty() {
        return chord.isEmpty();
    }

    public NodesChord getChord() {
        return this.chord;
    }

    public int getReportPort() {
        return REPORT_PORT;
    }

    public FailureReportPortal getReportManager() {
        return reportManager;
    }

    public List<KVServer> getPool() {
        return serverPool;
    }

    public void setRingUp(boolean ringUp) {
        isRingUp = ringUp;
    }

    public void setServing(boolean serving) {
        this.serving = serving;
    }
}
