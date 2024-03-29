package ecs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import util.HashUtils;
import util.Validate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Maps server connections in a tree structure
 */
public class NodesChord {
    private static Logger LOG = LogManager.getLogger(ExternalConfigurationService.ECS_LOG);

    private TreeMap<String, KVServer> nodesMap = new TreeMap<>();
    private Metadata md = new Metadata();

    /**
     * gets the direct successor of a node having the {@param keyHashed}
     * @param keyHashed
     * @return the successor
     */
    public KVServer getSuccessor(String keyHashed) {
        return getNthSuccessor(keyHashed, 1);
    }

    /**
     * gets the n-th successor of a node having the {@param keyHashed}
     * @param keyHashed
     * @param n the distance from the node having the {@param keyHashed} to the successor in question
     * @return the n-th successor
     */
    public KVServer getNthSuccessor(String keyHashed, int n) {
        int i = Arrays.binarySearch(nodesMap.keySet().toArray(), keyHashed);
        Validate.isTrue(i >= 0, keyHashed + " is not in the tree map " + nodesMap.keySet());
        return nodes().get((i + n) % nodes().size());
    }

    /**
     * gets the direct predecessor of a node having the {@param keyHashed}
     * @param keyHashed
     * @return the predecessor
     */
    public KVServer getPredecessor(String keyHashed) {
        return getNthPredecessor(keyHashed, 1);
    }

    /**
     * gets the n-th predecessor of a node having the {@param keyHashed}
     * @param keyHashed
     * @param n the distance from the node having the {@param keyHashed} to the predecessor in question
     * @return the n-th predecessor
     */
    public KVServer getNthPredecessor(String keyHashed, int n) {
        int i = Arrays.binarySearch(nodesMap.keySet().toArray(), keyHashed);
        Validate.isTrue(i >= 0, keyHashed + " is not in the tree map " + nodesMap.keySet());
        return nodes().get((nodes().size() + i - n) % nodes().size());
    }


    /**
     * Adds a storage server node to the treemap
     * 
     * @param node node to be added
     * @return true if node was successfully added
     */
    public boolean add(KVServer node) {
        if (nodesMap.containsKey(node.getHashKey()))
            return false;
        nodesMap.put(node.getHashKey(), node);
        return true;
    }

    /**
     * REmoves a storage server node from the treemap
     * 
     * @param node node to be removed
     * @return true if node was successfully removed
     */
    public boolean remove(KVServer node) {
        if (!nodesMap.containsKey(node.getHashKey()))
            return false;
        nodesMap.remove(node.getHashKey());
        return true;
    }
    

    /**
     * Calculates and updates the metadata depending on the treemap
     */
    public void calcMetadata() {
        md = new Metadata();
        String[] keys = new String[nodesMap.size()];
        keys = new ArrayList<>(nodesMap.keySet()).toArray(keys);

        KVServer[] kvServers = new KVServer[nodesMap.size()];
        kvServers = new ArrayList<>(nodesMap.values()).toArray(kvServers);

        for (int i = 0; i < nodesMap.size(); i++) {
            String end = keys[i];
            int j = i - 1 < 0 ? nodesMap.size() - 1 : i - 1;
            String start = HashUtils.increaseHashBy1(keys[j]);
            KVServer node = kvServers[i];
            md.add(node.getServerId(), node.getHost(), node.getServicePort(), start, end);
        }
        LOG.info("METADATA ===> " + md);
    }

    /**
     * returns a random node from the treemap
     * 
     * @return KVServer
     */
    public Optional<KVServer> randomNode() {
        int n = ThreadLocalRandom.current().nextInt(this.nodesMap.size());
        for (KVServer kvS : this.nodes()) {
            if (n == 0)
                return Optional.ofNullable(kvS);
            n--;
        }
        return Optional.empty();
    }

    public Metadata getMetadata() {
        return md;
    }

    public List<KVServer> nodes() {
        return this.nodesMap.values().stream().sequential().collect(Collectors.toList());
    }

    public int size() {
        return nodesMap.size();
    }
    
    public KVServer findByHashKey(String hashKey) {
    	return nodesMap.get(hashKey);
    }

    /**
     * Checks if Treemap is empty
     *
     * @return true if treemap is empty
     */
    public boolean isEmpty() {
        return nodesMap.isEmpty();
    }

    public TreeMap<String, KVServer> getNodesMap() {
        return nodesMap;
    }
}
