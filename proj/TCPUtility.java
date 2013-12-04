
public class TCPUtility {
	
	// Returns a string type of a Transport packet type
	public static String getType(int type) {
		String t = "INVALID TYPE";
		switch (type) {
			case Transport.SYN:
				t = "SYN";
				break;
			case Transport.ACK:
				t = "ACK";
				break;
			case Transport.FIN:
				t = "FIN";
				break;
			case Transport.DATA:
				t = "DATA";
		}
		return t;
	}
	
	public static String stringifyPacket(Transport packet) {
		return String.format("Type:%s Sequence Number:%d Origin Port:%d Dest Port:%d Payload Size:%d",
				TCPUtility.getType(packet.getType()), packet.getSeqNum(),
				packet.getSrcPort(), packet.getDestPort(), packet.getPayload().length);
	}
}
