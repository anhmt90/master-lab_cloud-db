package ecs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Metadata implements Serializable {
	List<NodeInfo> meta = new ArrayList<>();

	public void add(String nodeName, String host, int port, String start, String end) {
		NodeInfo kvSMeta = new NodeInfo(nodeName, host, port, start, end);
		this.meta.add(kvSMeta);
	}

	/**
	 * Finds a matching server for a hex key
	 * 
	 * @param hexKey hashed key in hex format
	 * @return String containing server address and port
	 */
	public NodeInfo findMatchingServer(String hexKey) {
		for (NodeInfo nodeInfo : meta) {
			if (nodeInfo.getRange().inRange(hexKey)) {
				return nodeInfo;
			}
		}
		return null;
	}

	/**
	 * get metadata size
	 *
	 * @return metadata size
	 */
	public int getSize() {
		return meta.size();
	}

	public List<NodeInfo> get() {
		return meta;
	}
}
