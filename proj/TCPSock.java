/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

public class TCPSock {
	// TCP socket states
	enum State {
		CLOSED, LISTEN, SYN_SENT, ESTABLISHED, SHUTDOWN
	}

	private State state;

	// Types of TCPSock
	enum Type {
		SERVER, CLIENT, LISTENER
	}

	private Type type = null;

	// Defaults and start values
	private static final int DEFAULT_WINDOW_SIZE = 2 * 1024; // Start at 2kB
	private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // ~1mB buffer

	private static final double TIMEOUT_ALPHA = 0.125;
	private static final double TIMEOUT_BETA = 0.25;
	private static final int DEFAULT_TIMEOUT = 1000;

	private static final int WINDOW_AI = Transport.MAX_PAYLOAD_SIZE;
	private static final double WINDOW_MD = 0.5;

	private Node node;
	private Manager manager;
	private TCPManager tcpManager;
	// dest* are -1 for listening sockets, to be compatible with Connection
	// See TCPManager for more details.
	public int localAddr, localPort, destAddr, destPort;

	private Queue<TCPSock> listenQueue; // The queue of requests for a listening
										// socket
	private int backlog; // Max number of socks we can have in the queue

	// For sending and receiving data
	private int nextSequenceNumber, sendBase;

	// Window size
	private int currentWindowSize;

	private TCPBuffer buffer; // Read or write buffer
	private OutOfOrderBuffer ooobuffer; // Out of order packets

	private boolean timerSet; // Is there a timer currently?
	private boolean timerRestart; // For restarting the timer for fast
									// retransmit

	// Timeout variables
	private long currentTimeout;
	private long RTT, devRTT;
	private int baselineSeq; // Helpers so we can calculate RTT
	private long transmitTime;

	// For fast retransmit
	int numDuplicates;

	// This is the constructor for a raw TCPSock
	public TCPSock(Node node, Manager manager, TCPManager tcpManager,
			int localAddress) {
		this.node = node;
		this.manager = manager;
		this.tcpManager = tcpManager;
		this.localAddr = localAddress;

		this.destAddr = -1;
		this.destPort = -1;
		this.state = State.CLOSED; // All sockets start closed until they
									// are bound to a port or connect.
		timerSet = false;
	}

	// Constructor for server sockets
	protected TCPSock(TCPSock s, int dest, int port, int seqNum) {
		this(s.node, s.manager, s.tcpManager, s.localAddr);

		this.destAddr = dest;
		this.destPort = port;
		this.localPort = s.localPort;

		this.nextSequenceNumber = seqNum + 1; // Start at next sequence number.

		this.buffer = new TCPBuffer(DEFAULT_BUFFER_SIZE); // Read Buffer
		this.ooobuffer = new OutOfOrderBuffer();

		this.state = State.ESTABLISHED; // Well, at least from the server side
		this.type = Type.SERVER;

		tcpManager.register(this);

		// Upon creation we have to send ACK to tell client we are established
		sendPacket(Transport.ACK, nextSequenceNumber, TCPManager.dummy);
	}

	// "Constructor" for client sockets
	private void constructClient(int toAddr, int toPort) {
		// tcpManager.deregister(this);
		this.destAddr = toAddr;
		this.destPort = toPort;
		this.buffer = new TCPBuffer(DEFAULT_BUFFER_SIZE); // Write Buffer

		this.type = Type.CLIENT;

		this.nextSequenceNumber = new Random(System.currentTimeMillis())
				.nextInt(10000); // Random starting sequence number

		this.currentWindowSize = DEFAULT_WINDOW_SIZE;
		this.currentTimeout = DEFAULT_TIMEOUT;
		this.transmitTime = this.devRTT = this.RTT = this.baselineSeq = -1;
		this.timerSet = false;
		this.timerRestart = false;

		tcpManager.register(this);
	}

	/*
	 * Bind a socket to a local port
	 */
	public int bind(int localPort) {
		if (state != State.CLOSED) {
			node.logError(f("Attempting to bind cooked (not raw haha) socket."));
			return -1;
		}
		int ret = tcpManager.bind(localPort, this);
		if (ret != -1)
			this.localPort = localPort; // successful
		return ret;
	}

	/*
	 * Listen for connections on a socket
	 */
	public int listen(int backlog) {
		if (localPort < 0) {
			node.logError(f("Attempting to listen on unbound socket."));
			return -1;
		}
		state = State.LISTEN;
		type = Type.LISTENER; // We are a listening socket!
		this.listenQueue = new LinkedList<TCPSock>();
		this.backlog = backlog;
		return 0;
	}

	/*
	 * Accept a connection on a socket
	 */
	public TCPSock accept() {
		if (state != State.LISTEN || type != Type.LISTENER) {
			node.logError(f("Cannot accept on socket that is not listening."));
			return null;
		}
		return listenQueue.poll(); // Poll instead of remove so we don't throw
									// an exception
	}

	/*
	 * Connect to a remote host
	 */
	public int connect(int destAddr, int destPort) {
		if (state == State.CLOSED && localPort > 0) {
			constructClient(destAddr, destPort);
			sendPacket(Transport.SYN, nextSequenceNumber, TCPManager.dummy); // Send
																				// SYN
																				// to
																				// the
																				// server
			addTimer(nextSequenceNumber);
			state = State.SYN_SENT;
			nextSequenceNumber++; // We will be expecting an ACK back with the
									// next sequence number
			return 0;
		} else {
			node.logError("Attempting to connect on already connected or unbound socket.");
			return -1;
		}
	}

	/*
	 * Initiate closure of a connection (graceful shutdown)
	 */
	public void close() {
		node.logDebug(f(
				"Attempting to close socket with - sendBase:%d and nextSequenceNumber:%d and remainingBytes:%d.",
				sendBase, nextSequenceNumber, buffer.size()));
		// We can release immediately if we aren't set up, or have sent all data
		if (state == State.CLOSED || type == Type.LISTENER
				|| type == Type.SERVER || buffer.size() == 0) {
			release();
		} else
			state = State.SHUTDOWN;
	}

	/*
	 * Release a connection immediately (abortive shutdown)
	 */
	public void release() {
		if (state != State.CLOSED) {
			switch (this.type) {
			case SERVER:
				break;
			case CLIENT:
				sendPacket(Transport.FIN, nextSequenceNumber, TCPManager.dummy); // Send
																					// a
																					// fin
																					// packet
																					// to
																					// tell
																					// the
																					// other
																					// side
																					// we're
																					// closing
				tcpManager.deregister(this);
				break;
			case LISTENER:
				// Deregister this socket and everything in its queue.
				tcpManager.deregister(this);
				for (TCPSock t : listenQueue)
					t.close(); // Should this be release?
			default:
				break;
			}
			state = State.CLOSED;
		}
	}

	public boolean isConnectionPending() {
		return (state == State.SYN_SENT);
	}

	public boolean isClosed() {
		return (state == State.CLOSED);
	}

	public boolean isConnected() {
		return (state == State.ESTABLISHED);
	}

	public boolean isClosurePending() {
		return (state == State.SHUTDOWN);
	}

	/*
	 * Write to the socket up to len bytes from the buffer buf starting at
	 * position pos.
	 */
	public int write(byte[] buf, int pos, int len) {
		node.logDebug(f("Attempting to write %d bytes. Current State: %s.",
				len, this.toString()));
		// Make sure we're not shutting down (we don't want to write any new
		// data)
		if (state == State.SHUTDOWN) {
			node.logError(f("Attempting to write to socket in shutdown."));
		}
		// Check validity of write spec
		if (pos >= buf.length) {
			return -1;
		}
		// Find out how much we can actually write
		int written = 0;
		int canWrite = Math.min(len, buffer.remaining());
		if (canWrite > 0) {
			byte[] toWrite = getBytes(buf, pos, canWrite);
			written = toWrite.length;
			// Put this in the buffer
			node.logDebug(f("Putting %d bytes into the buffer.", written));
			buffer.put(toWrite);
			// See if we can actually send anything
			int canSend = Math.min(written, remainingInWindow());
			if (canSend > 0) {
				node.logDebug(f("Sending %d bytes with sequence number %d.",
						canSend, nextSequenceNumber));
				sendData(getBytes(toWrite, 0, canSend), nextSequenceNumber);
				addTimer(nextSequenceNumber);
				nextSequenceNumber += canSend; // Update sequence number by
												// bytes sent
			}
		}
		return written;
	}

	/*
	 * Read from the socket up to len bytes into the buffer buf starting at
	 * position pos.
	 */
	public int read(byte[] buf, int pos, int len) {
		if (pos < 0 || pos >= buf.length)
			return -1;
		node.logDebug(f("Attempting to read %d bytes. Current Size: %d.", len,
				buffer.size()));
		byte[] fromBuffer = buffer.getBuffer(len, 0);
		int i = 0;
		buffer.advance(fromBuffer.length);
		node.logDebug(f("Read %d bytes.", fromBuffer.length));
		for (i = 0; i < fromBuffer.length && pos < buf.length; i++, pos++) {
			buf[pos] = fromBuffer[i];
		}
		if (state == State.SHUTDOWN) {
			if (buffer.remaining() == 0)
				node.logDebug("CLOSING CLIENT");
			release();
		}
		return i;
	}

	public void handlePacket(Transport packet, int from) {
		if (state == State.CLOSED)
			return; // We can't be closed and handling a packet
		node.logDebug(f("Handling packet from: %d packet: %s", from,
				TCPUtility.stringifyPacket(packet)));
		printCharForPacket(packet);
		switch (packet.getType()) {
		case Transport.SYN:
			this.syn(packet, from);
			break;
		case Transport.ACK:
			this.ack(packet, from);
			break;
		case Transport.FIN:
			this.fin(packet, from);
			break;
		case Transport.DATA:
			this.data(packet, from);
		}

		if (state == State.SHUTDOWN && type != Type.SERVER) {
			// try to close again
			node.logDebug(f("Attempting to close socket."));
			close();
		}
	}

	// Handle SYN
	private void syn(Transport packet, int from) {
		// two cases, we get a syn on a listening socket, or we get a duplicate
		// syn on an already established socket because ACK got lost
		if (state == State.LISTEN) {
			node.logDebug(f(
					"Received SYN to listening socket. Current State: %s",
					this.toString()));
			if (listenQueue.size() < backlog) {
				listenQueue.add(new TCPSock(this, from, packet.getSrcPort(),
						packet.getSeqNum()));
			} else
				node.logDebug(f("Too many sockets in the backlog!"));
		} else if (state == State.ESTABLISHED && type == Type.SERVER) {
			// second case
			node.logDebug(f("Received duplicate SYN. Current State: %s",
					this.toString()));
			System.out.print("!"); // For the receipt
			sendPacket(Transport.ACK, nextSequenceNumber, TCPManager.dummy);
			System.out.print("!"); // For the send
		}
	}

	// Handle ACK
	private void ack(Transport packet, int from) {
		// two cases, ack to start the connection or ack of data packet.
		if (state == State.SYN_SENT) {
			if (packet.getSeqNum() != nextSequenceNumber) {
				node.logDebug(f("ACK: Incorrect sequence number for SYN reply."));
				return;
			}
			state = State.ESTABLISHED;
			this.sendBase = nextSequenceNumber; // Here we go, now we can start
												// sending
			// data
		} else {
			// We got the acknowledgement for a data packet!
			int packetseq = packet.getSeqNum();
			if (packetseq > sendBase) {
				node.logDebug(f("Cumulative ACKing %d from %d.", packetseq,
						sendBase));
				// Cumulative ack up till packetseq
				buffer.advance(packetseq - sendBase);
				sendBase = packetseq;

				numDuplicates = 0;
				if (packetseq > baselineSeq)
					recomputeTimeout();
				additiveIncrease();
				flowControl(packet.getWindow());
				timerSet = false;
				addTimer(sendBase);
				sendBuffer(); // Send as much of the buffer as possible
			} else {
				// This is a duplicate ACK!
				node.logDebug(f("Received duplicate ACK."));
				System.out.print("!");
				if (++numDuplicates == 3) { // Fast retransmit
					byte[] payload = buffer.getBuffer(
							Transport.MAX_PAYLOAD_SIZE, 0);
					if (payload.length != 0) { // These could be duplicate ACK's
												// we're getting at the end
						node.logDebug(f(
								"TCP-Fast Retransmit for sequence number %d.",
								packetseq));
						sendPacket(Transport.DATA, packetseq, payload);
						System.out.print("!");
						timerSet = false;
						timerRestart = true;
						addTimer(packetseq);
						numDuplicates = 0; // Reset duplicates b/c we resent the
											// packet
					}
				}
			}
		}
	}

	// Handle DATA
	private void data(Transport packet, int from) {
		int packetseq = packet.getSeqNum();
		node.logDebug(f("DATA packet with sequence number %d, expecting %d.",
				packetseq, nextSequenceNumber));
		if (packetseq == nextSequenceNumber) {
			// In-order
			int k = buffer.put(packet.getPayload());
			node.logDebug(f(
					"Put %d bytes into buffer from packet with payload length %d.",
					k, packet.getPayload().length));
			nextSequenceNumber += packet.getPayload().length;
			getPotentialOOOPackets();
			currentWindowSize = buffer.remaining();
		} else {
			// out of order
			// node.logDebug(f("Ignoring DATA packet with seqno %d.",
			// packetseq));
			if (packetseq < nextSequenceNumber)
				System.out.print("!"); // Duplicate Data Packet
			else {
				node.logDebug(f("Out of Order Buffer: %s\n Adding %d : %d",
						ooobuffer, packetseq, packet.getPayload().length));
				ooobuffer.add(ooobuffer.new OOOElement(packetseq, packet
						.getPayload()));
				node.logDebug(f("Out of Order Buffer: %s\n Adding %d : %d",
						ooobuffer, packetseq, packet.getPayload().length));
			}
		}
		acknowledgePacket();
	}

	// Handle FIN
	private void fin(Transport packet, int from) {
		node.logDebug(f("Received FIN packet."));
		if (this.type == Type.SERVER) {
			node.logDebug("Server receieved FIN, going into shutdown.");
			state = State.SHUTDOWN;
		} else if (state != State.SHUTDOWN || state != State.CLOSED) {
			release();
		}
	}

	public void getPotentialOOOPackets() {
		node.logDebug(f("Out of Order Buffer: %s\n", ooobuffer));
		byte[] pot = ooobuffer.remove(nextSequenceNumber);
		if (pot != null) {
			int k = buffer.put(pot);
			node.logDebug(f(
					"Put %d bytes into buffer from out of order buffer from %d.",
					k, pot.length));
			nextSequenceNumber += pot.length;
		}
		node.logDebug(f("Out of Order Buffer: %s\n", ooobuffer));
	}

	/*
	 * Acknowledge receipt of data packet.
	 */
	private void acknowledgePacket() {
		sendPacket(Transport.ACK, nextSequenceNumber, TCPManager.dummy);
	}

	/*
	 * Attempt to send as much unsent data from the buffer as possible (governed
	 * by the window)
	 */
	private void sendBuffer() {
		node.logDebug(f(
				"Sending buffer from sock:%s. Buffer Size:%d, Window Size: %d, SendBase:%d, Next Sequence Number:%d",
				this, buffer.size(), currentWindowSize, sendBase,
				nextSequenceNumber));
		int canSend = currentWindowSize - (nextSequenceNumber - sendBase);
		// Add the timer and send out all the data thats not been sent yet
		byte[] toSend = buffer.getBuffer(canSend,
				(nextSequenceNumber - sendBase));
		if (toSend != null && toSend.length > 0) {
			node.logDebug(f("Sending %d bytes from the buffer.", toSend.length));
			sendData(toSend, nextSequenceNumber);
			// addTimer(nextSequenceNumber);
			nextSequenceNumber += toSend.length;
		}
	}

	private void sendPacket(int type, int seq, byte[] payload) {
		node.logDebug(f(
				"Assembling packet from port %d to %d, with windowsize %d, seqno %d, type %d, and payload length %d.",
				localPort, destPort, currentWindowSize, seq, type,
				payload.length));
		Transport t = new Transport(localPort, destPort, type,
				currentWindowSize, seq, payload);
		printCharForPacket(t);
		node.logDebug(f("Sending packet with seq:%d at seq:%d", seq,
				nextSequenceNumber));
		tcpManager.sendPacket(localAddr, destAddr, t);
	}

	// Add a timer, also update the tracked seq no.
	private void addTimer(int seqNo) {
		if (!timerSet) { // We only have one timer at a time for TCP
			if (timerRestart) {
				timerRestart = !timerRestart;
				return;
			}
			try {
				Object[] params = { new Integer(seqNo) };
				String[] types = { "java.lang.Integer" };
				Method m = Callback.getMethod("handleTimeout", this, types);
				Callback c = new Callback(m, this, params);
				this.manager.addTimer(localAddr, currentTimeout, c);
				node.logDebug(f("Adding timer with timeout: %d for sNo: %d",
						currentTimeout, seqNo));
				timerSet = true;
				baselineSeq = seqNo;
				transmitTime = manager.now();
			} catch (Exception e) {
				node.logError(String.format(
						"Unable to addtimer for sequence number:%d", seqNo));
				e.printStackTrace();
			}
		}
	}

	private void recomputeTimeout() {
		node.logDebug(f("Old Timeout: %d", currentTimeout));
		long diff = manager.now() - transmitTime;
		if (this.RTT == -1) { // first measurement
			RTT = diff;
			devRTT = diff / 2;
		} else {
			devRTT = (int) ((1 - TIMEOUT_BETA) * devRTT + TIMEOUT_BETA
					* Math.abs(RTT - diff)); // From slides
			RTT = (int) ((1 - TIMEOUT_ALPHA) * RTT + TIMEOUT_ALPHA * diff);
		}
		currentTimeout = RTT + Math.max(1, 4 * devRTT);
		node.logDebug(f("New Timeout: %d", currentTimeout));

	}

	// Timeout handler
	public void handleTimeout(Integer seqNo) {
		timerSet = false;
		// First make sure this timer has not been cancelled
		if (seqNo < sendBase) {
			node.logDebug(f(
					"Timer for seqNo: %d has been cancelled (sendBase: %d)",
					seqNo, sendBase));
			return;
		}
		System.out.print("!"); // We are going to be sending a repeat packet.
		// Two cases, either timeout on syn packet or data packet
		if (state == State.SYN_SENT) {
			// first case
			node.logDebug(f("SYN Timed out."));
			sendPacket(Transport.SYN, seqNo, TCPManager.dummy);
			addTimer(seqNo);
		}
		// Timeout of DATA packet
		else if ((state == State.ESTABLISHED || state == State.SHUTDOWN)) {
			node.logDebug(f("Data packet %d timed out.", seqNo));
			currentTimeout *= 2; // Change the timeout value b/c it might be too
									// short
			multiplicativeDecrease(); // Decrease the window size
			byte[] packet = buffer.getBuffer(Transport.MAX_PAYLOAD_SIZE, 0);
			sendPacket(Transport.DATA, seqNo, packet);
			numDuplicates = 0; // Reset duplicates b/c we resent the packet
			addTimer(seqNo);
		}
	}

	private void multiplicativeDecrease() {
		node.logDebug(f("Old Window Size: %d", currentWindowSize));
		currentWindowSize = (int) (currentWindowSize * WINDOW_MD);
		node.logDebug(f("New Window Size: %d", currentWindowSize));
	}

	private void additiveIncrease() {
		node.logDebug(f("Old Window Size: %d", currentWindowSize));
		currentWindowSize += WINDOW_AI;
		node.logDebug(f("New Window Size: %d", currentWindowSize));
	}

	private void flowControl(int window) {
		node.logDebug(f("Old Window Size: %d", currentWindowSize));
		currentWindowSize = Math.min(window, currentWindowSize); // make sure we
																	// dont
																	// overwhelm
																	// the
																	// receiver.
		node.logDebug(f("New Window Size: %d", currentWindowSize));
	}

	/*
	 * Arrays.copyOf kind of, gets len bytes starting at pos in buf
	 */
	private byte[] getBytes(byte[] buf, int pos, int toWrite) {
		int size = Math.min(buf.length - pos, toWrite);
		byte[] ret = new byte[size];
		for (int i = 0; i < size; i++) {
			ret[i] = buf[pos];
			pos++;
		}
		return ret;
	}

	/*
	 * Sends packets of uptil Transport.MAX_PAYLOAD bytes of payload
	 */
	private void sendData(byte[] payload, int seqNo) {
		// Send segments of <= MAX_PAYLOAD_SIZE
		for (int i = 0; i < payload.length; i += Transport.MAX_PAYLOAD_SIZE) {
			int split = Math
					.min(Transport.MAX_PAYLOAD_SIZE, payload.length - i);
			// node.logDebug(f("Split:%d", split));
			byte[] splitBuf = new byte[split];
			for (int j = 0; j < split; j++) {
				splitBuf[j] = payload[i + j];
				// node.logDebug(f("INDEX:%d DATA:%x", i + j, splitBuf[j]));
			}
			sendPacket(Transport.DATA, seqNo + i, splitBuf);
		}
	}

	/*
	 * Prints the appropriate character for the packet received or sent
	 */
	private void printCharForPacket(Transport packet) {
		switch (packet.getType()) {
		case Transport.SYN:
			System.out.print("S");
			break;
		case Transport.ACK:
			if (type == Type.CLIENT) {
				if (packet.getSeqNum() > sendBase)
					System.out.print(":");
				else
					System.out.print("?");
			}
			break;
		case Transport.DATA:
			System.out.print(".");
			break;
		case Transport.FIN:
			System.out.print("F");
		}
	}

	public String toString() {
		switch (this.type) {
		case CLIENT:
			return dumpClient();
		case LISTENER:
			return dumpListener();
		case SERVER:
			return dumpServer();
		default:
			return "Raw Socket!";
		}
	}

	/*
	 * Dumps the state of a client socket.
	 */
	private String dumpClient() {
		return String
				.format("Client Socket: Sequence Number:%d Window Size:%d SendBase:%d Remaining in Buffer:%d Timeout:%d",
						nextSequenceNumber, currentWindowSize, sendBase,
						buffer.size(), currentTimeout);
	}

	private String dumpServer() {
		return String.format("Server Socket: ");
	}

	private String dumpListener() {
		return String.format("Listening Socket: address:%d port%d", localAddr,
				localPort);
	}

	/*
	 * Tells us how many more bytes to go before we hit the windowsize limit
	 */
	private int remainingInWindow() {
		return currentWindowSize - (nextSequenceNumber - sendBase);
	}

	/*
	 * Alias for String.format that adds class name
	 */
	private String f(String template, Object... args) {
		StackTraceElement[] stacktrace = Thread.currentThread().getStackTrace();
		StackTraceElement e = stacktrace[2];// maybe this number needs to be
											// corrected
		String methodName = e.getMethodName();
		return String.format("TCPSock- " + methodName + ":" + template, args);
	}

}
