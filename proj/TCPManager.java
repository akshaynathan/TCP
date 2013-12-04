/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

import java.util.HashMap;

public class TCPManager {

	private Node node;
	private int addr;
	private Manager manager;

	private HashMap<Connection, TCPSock> connections; // hash table of all our
														// connections

	public static byte[] dummy = new byte[0];

	public TCPManager(Node node, int addr, Manager manager) {
		this.node = node;
		this.addr = addr;
		this.manager = manager;
		connections = new HashMap<Connection, TCPSock>();
	}

	/*
	 * Start this TCP manager
	 */
	public void start() {

	}

	/*
	 * Returns a new socket.
	 */
	public TCPSock socket() {
		return new TCPSock(node, manager, this, addr);
	}

	/*
	 * Binds a socket to the port specified. Returns -1 if there is already a
	 * socket bound at this port.
	 */
	public int bind(int port, TCPSock t) {
		Connection c = new Connection(addr, port, -1, -1);
		if (!addConnection(c, t)) {
			node.logError(f("Unable to bind socket to port %d.", port));
			return -1;
		}
		return 0;
	}

	/*
	 * Register is just another version of bind that adds connections that have
	 * a destination.
	 */
	public void register(TCPSock t) {
		Connection c = new Connection(t.localAddr, t.localPort, t.destAddr,
				t.destPort);
		if (!addConnection(c, t)) {
			node.logDebug(f("Attempting to register connection that is already in use."));
			return;
		}
	}

	/*
	 * Removes a connection from the HashTable
	 */
	public void deregister(TCPSock t) {
		node.logDebug(f("Connections: %s", connections.keySet().toString()));
		Connection c = new Connection(t.localAddr, t.localPort,
				t.destAddr, t.destPort);
		TCPSock s = connections.remove(c);
		node.logDebug(f("removing %s", c.toString()));
		if (s == null)
			node.logDebug(f("Attempting to deregister a connection that does not exist!"));
	}

	/*
	 * This is our demultiplexer. Passes on to appropriate TCPSock's
	 * handlePacket method.
	 */
	public void handlePacket(Transport packet, int src) {
		int src_port = packet.getSrcPort(), dst_port = packet.getDestPort();
		node.logDebug(f("Received packet to: %d:%d from: %d:%d", addr,
				dst_port, src, src_port));

		// First check for full connections (with a destination)
		Connection c = new Connection(addr, dst_port, src, src_port);
		TCPSock l = connections.get(c);
		if (l != null) {
			node.logDebug(f("Handing over to socket: " + l.toString()));
			l.handlePacket(packet, src);
		} else {
			// If there's no connection there should be a listening socket
			Connection q = new Connection(addr, dst_port, -1, -1);
			l = connections.get(q);
			if (l != null) {
				node.logDebug(f("Sending to (listening) socket " + l.toString()));
				l.handlePacket(packet, src);
			} else {
				// connection refused, send FIN packet
				sendPacket(addr, src, new Transport(dst_port, src_port,
						Transport.FIN, 0, packet.getSeqNum() + 1, dummy));
			}
		}
	}

	/*
	 * Called from TCPSock to send a packet from one node to another
	 */
	public void sendPacket(int from, int to, Transport packet) {
		node.logDebug(f("Sending packet - %s - from %d to %d.",
				TCPUtility.stringifyPacket(packet), from, to));
		node.sendSegment(from, to, Protocol.TRANSPORT_PKT, packet.pack());
	}

	/*
	 * Helper method to add to the hashtable
	 */
	private boolean addConnection(Connection c, TCPSock s) {
		if (connections.get(c) != null)
			return false;
		connections.put(c, s);
		return true;
	}

	/*
	 * An alias for String.format that includes our class name.
	 */
	private String f(String template, Object... args) {
		return String.format("TCPManager: " + template, args);
	}
}
