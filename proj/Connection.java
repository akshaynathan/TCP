public class Connection {
	int src_addr;
	int src_port;
	int dst_addr; // These are -1 for listening sockets
	int dst_port;
	
	public Connection(int src_addr, int src_port, int dst_addr, int dst_port) {
		this.src_addr = src_addr;
		this.src_port = src_port;
		this.dst_addr = dst_addr;
		this.dst_port = dst_port;
	}

	public int hashCode() {
		int hash = 7;
		hash = 31 * hash + src_addr;
		hash = 31 * hash + src_port;
		hash = 31 * hash + dst_addr;
		hash = 31 * hash + dst_port;
		return hash;
	}
	
	public boolean equals(Object d) {
		return ((Connection)d).src_addr == src_addr && ((Connection)d).src_port == src_port
				&& ((Connection)d).dst_addr == dst_addr && ((Connection)d).dst_port == dst_port;
	}
	
	public String toString() {
		return String.format("%d:%d %d:%d", src_addr, src_port, dst_addr, dst_port);
	}
}