import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

public class OutOfOrderBuffer {

	LinkedList<OOOElement> packets;

	public OutOfOrderBuffer() {
		packets = new LinkedList<OOOElement>();
	}

	
	public static void main(String[] args) {
		// Test cases
		OutOfOrderBuffer oo = new OutOfOrderBuffer();
		oo.add(oo.new OOOElement(5, new byte[10]));
		System.out.println(oo);
		oo.add(oo.new OOOElement(7, new byte[10]));
		System.out.println(oo);
		oo.add(oo.new OOOElement(15, new byte[10]));
		System.out.println(oo);
		oo.add(oo.new OOOElement(35, new byte[10]));
		System.out.println(oo);
		
		byte[] f = oo.remove(5);
		System.out.println(f.length);
	}
	
	public void add(OOOElement k) {
		if (packets.isEmpty()) {
			packets.add(k);
			return;
		}
		int start = k.seqNo;
		int end = start + k.payloadLength;
		if (end < packets.getFirst().seqNo) {
			packets.addFirst(k);
			return;
		}
		ListIterator<OOOElement> it = packets.listIterator();
		OOOElement prev = it.next();
		while(start > prev.seqNo + prev.payloadLength && it.hasNext())
			prev = it.next();
		if(start > prev.seqNo + prev.payloadLength) { // this is the last element
			it.add(k);
			return;
		} else if(end < prev.seqNo) {
			it.previous();
			it.add(k);
			return;
		} 
		prev.combine(k);
		return;
	}

	public byte[] remove(int seqNum) {
		if (packets.isEmpty() || hasGap(seqNum))
			return null;
		OOOElement e = packets.poll();
		while (e != null && (seqNum > e.seqNo + e.payloadLength))
			e = packets.poll();
		if (e == null) // reached end of the list
			return null;
		if (seqNum < e.seqNo) { // new gap
			return null;
		} else {
			int length = e.seqNo + e.payloadLength - seqNum;
			byte[] ret = new byte[length];
			for (int i = 0, k = seqNum - e.seqNo; i < length; i++, k++)
				ret[i] = e.payload[k];
			return ret;
		}
	}

	private boolean hasGap(int seqNum) {
		return (seqNum < packets.getFirst().seqNo);
	}

	public String toString() {
		StringBuffer k = new StringBuffer();
		Iterator<OOOElement> it = packets.iterator();
		while (it.hasNext()) {
			k.append(it.next() + " ---> ");
		}
		k.append("NULL");
		return k.toString();
	}

	class OOOElement {
		int seqNo;
		byte[] payload;
		int payloadLength;
		
		public OOOElement(int k, byte[] arr) {
			seqNo = k;
			payload = arr;
			payloadLength = arr.length;
		}

		public void combine(OOOElement k) {
			if (k.seqNo < seqNo) {
				int length = seqNo + payloadLength - k.seqNo;
				byte[] tmp = new byte[length];
				int i = 0, f = seqNo - k.seqNo;
				for (i = 0; i < f; i++)
					tmp[i] = k.payload[i];
				for (f = 0; f < payloadLength; f++, i++)
					tmp[i] = payload[f];
				payload = tmp;
				payloadLength = tmp.length;

				seqNo = k.seqNo;
			} else if (k.seqNo > seqNo) {
				int length = k.seqNo + k.payloadLength - seqNo;

				byte[] tmp = new byte[length];
				int i = 0, f = k.seqNo - seqNo;
				for (i = 0; i < f; i++)
					tmp[i] = payload[i];
				for (f = 0; f < k.payloadLength; f++, i++)
					tmp[i] = k.payload[f];
				payload = tmp;
				payloadLength = tmp.length;
			} else {
				if (k.payloadLength > payloadLength) {
					payload = k.payload;
					payloadLength = payload.length;

				}
			}
		}

		public String toString() {
			return String.format("%d : %d", seqNo, payloadLength);
		}
	}

}
