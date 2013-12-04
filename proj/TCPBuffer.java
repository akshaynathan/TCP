
public class TCPBuffer {

	private int start; // Where the data starts in the buffer
	private int pos; // The next available empty position in the buffer
	private byte[] buffer;
	private boolean full; // is the buffer full?

	public TCPBuffer(int size) {
		buffer = new byte[size];
		start = 0;
		pos = 0;
		full = false;
	}

	/*
	 * Space remaining in the buffer
	 */
	public int remaining() {
		if(full) // None remaining if it is full.
			return 0;
		if(pos == start) // If its not full and pos == start, then we havent written anything yet
			return buffer.length;
		int k =  (start - pos) % buffer.length;
		return (k < 0) ? k + buffer.length : k;
	}
	
	/*
	 * Current length of data in the buffer
	 */
	public int size() {
		return buffer.length - remaining();
	}
	
	/*
	 * Writes buf into the buffer starting at pos
	 * Returns the number of bytes actually written.
	 */
	public int put(byte[] buf) {
		int i = 0;
		int canWrite = Math.min(remaining(), buf.length);
		while(i < canWrite) {
			buffer[pos++] = buf[i++];
			pos = pos % buffer.length; // wrap-around
		}
		if(pos == start && buf.length != 0)
			full = true;
		return i;
	}
	
	/*
	 * Advances the start position of the buffer.
	 * Start can only be advanced until pos.
	 */
	public boolean advance(int bytes) {
		if(bytes > size())
			return false;
		start = (start + bytes) % buffer.length;
		if(bytes > 0)
			full = false;
		return true;
	}
	
	/*
	 * Retrieves a buffer of max size potential from the start of the buffer.
	 * DOES NOT ADVANCE start
	 */
	public byte[] getBuffer(int potential, int pos) {
		int actualSize = Math.min(potential, size() - pos);
		if(actualSize < 0)
			return null;
		byte ret[] = new byte[actualSize];
		for(int i = start + pos, k = 0; k < actualSize; i = (i + 1) % buffer.length, k++) {
			ret[k] = buffer[i];
		}
		return ret;
	}
	
	
	
	public String toString() {
		return String.format("Buffer Length: %d, Start: %d, Pos: %d, Full: %b", buffer.length, start, pos, full);
	}
}
