package server.api;

import client.api.Client;
import ecs.NodeInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocol.IMessage;
import server.app.Server;

import java.io.IOException;

public class Replicator implements Runnable {
    private static Logger LOG = LogManager.getLogger(Server.SERVER_LOG);
    private IMessage message;
    private Client client;
    private NodeInfo replica;

    public Replicator(NodeInfo replica) {
        this.replica = replica;
        resetClient();
        createClient();
    }

    private void createClient() {
        client = new Client(replica.getHost(), replica.getPort());
    }

    private void resetClient() {
        if (client != null && client.isConnected())
            client.disconnect();
        client = null;
    }

    @Override
    public void run() {
        try {
            if (client == null)
                createClient();

            if (!client.isConnected())
                client.connect();

            client.performPUT(message.getKey(), message.getValue()); // attention: message.getKey() returns a HASHED key
        } catch (IOException e) {
            LOG.error(e);
            resetClient();
        }
    }

    public void setMessage(IMessage message) {
        this.message = message;
    }

    public NodeInfo getReplica() {
        return replica;
    }
}