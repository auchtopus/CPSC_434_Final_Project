import java.math.BigInteger;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

/**
 * <pre>
 *    
 * This conveys the header for reliable message transfer.
 * This is carried in the payload of a Packet, and in turn the data being
 * transferred is carried in the payload of the MPTransport packet.
 * </pre>
 */
public class MPTransport {

    public static final int MAX_PACKET_SIZE = 500;
    public static final int HEADER_SIZE = 28;
    public static final int MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE; // This is because the payload size
    public static final int MAX_PORT_NUM = 65535; // port numbers range from 0 to 255

    public static final int SYN = 0;
    public static final int ACK = 1;
    public static final int FIN = 2;
    public static final int DATA = 3;
    public static final int MP_CAPABLE = 4;
    public static final int MP_JOIN = 5;

    private int srcPort;
    private int destPort;
    private int type;
    private int mpType;
    private int window;
    private int seqNum;
    private byte[] payload;
    private int mapping = 0;
    private int dSeqNum; // MPTCP: DSN field for sender; DSACK for receiver;

    /**
     * Constructing a new MPTransport packet.
     * 
     * @param srcPort  The source port
     * @param destPort The destination port
     * @param type     The type of packet. Either SYN, ACK, FIN, or DATA
     * @param window   The window size
     * @param seqNum   The sequence number of the packet
     * @param payload  The payload of the packet.
     */
    public MPTransport(int srcPort, int destPort, int type, int mpType, int window, int seqNum, int dSeqNum,
            int mapping, byte[] payload)
            throws IllegalArgumentException {
        // System.out.print(srcPort);
        // System.out.print(destPort);
        // System.out.print(type);
        if (srcPort < 0 || srcPort > MAX_PORT_NUM || destPort < 0 || destPort > MAX_PORT_NUM || type < SYN
                || type > DATA || payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Illegal arguments given to MPTransport packet");
        }

        this.srcPort = srcPort;
        this.destPort = destPort;
        this.type = type;
        this.mpType = mpType;
        this.window = window;
        this.seqNum = seqNum;
        this.dSeqNum = dSeqNum;
        this.mapping = mapping;
        this.payload = payload;
    }

    /**
     * @return The source port
     */
    public int getSrcPort() {
        return this.srcPort;
    }

    /**
     * @return The destination port
     */
    public int getDestPort() {
        return this.destPort;
    }

    /**
     * @return The type of the packet
     */
    public int getType() {
        return this.type;
    }

    /**
     * @return The window size
     */
    public int getWindow() {
        return this.window;
    }

    /**
     * @return The sequence number
     */
    public int getSeqNum() {
        return this.seqNum;
    }

    /**
     * @return The payload
     */
    public byte[] getPayload() {
        return this.payload;
    }

    /**
     * @return The dSeqNum
     */
    public int getDSeqNum() {
        return this.dSeqNum;
    }

    /**
     * @return The dSeqNum
     */
    public int getMpType() {
        return this.mpType;
    }

    public int getLenMapping() {
        return this.mapping;
    }

    /**
     * Convert the MPTransport packet object into a byte array for sending over the
     * wire. Format: source port = 2 byte destination port = 2 byte type = 1 byte
     * mpType = 1 byte window size = 4 bytes sequence number = 4 bytes
     * dSequence number = 4 bytes packet length = 1 byte
     * payload <= MAX_PAYLOAD_SIZE bytes
     * 
     * @return A byte[] for transporting over the wire. Null if failed to pack for
     *         some reason
     */
    public byte[] pack() {

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        // // write 4 bytes for src port
        byte[] srcPortByteArray = (BigInteger.valueOf(this.srcPort)).toByteArray();
        int paddingLength = 4 - srcPortByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(srcPortByteArray, 0, Math.min(srcPortByteArray.length, 4));

        // // write 4 bytes for dest port
        byte[] destPortByteArray = (BigInteger.valueOf(this.destPort)).toByteArray();
        paddingLength = 4 - destPortByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(destPortByteArray, 0, Math.min(destPortByteArray.length, 4));

        // write 1 byte for type

        // write 1 byte for mptype
        byteStream.write(this.type);
        byteStream.write(this.mpType);

        // write 4 bytes for window size
        byte[] windowByteArray = (BigInteger.valueOf(this.window)).toByteArray();
        paddingLength = 4 - windowByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(windowByteArray, 0, Math.min(windowByteArray.length, 4));

        // write 4 bytes for sequence number
        byte[] seqByteArray = (BigInteger.valueOf(this.seqNum)).toByteArray();
        paddingLength = 4 - seqByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(seqByteArray, 0, Math.min(seqByteArray.length, 4));

        // write 4 bytes for dsequence number
        byte[] dSeqByteArray = (BigInteger.valueOf(this.dSeqNum)).toByteArray();
        paddingLength = 4 - dSeqByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(dSeqByteArray, 0, Math.min(dSeqByteArray.length, 4));

        // write 4 bytes for mapping
        byte[] mappingByteArray = (BigInteger.valueOf(this.mapping)).toByteArray();
        paddingLength = 4 - mappingByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(mappingByteArray, 0, Math.min(mappingByteArray.length, 4));

        // here!
        // System.out.println("HEADER_SIZE + this.payload.length" +
        // Integer.toString(HEADER_SIZE + this.payload.length));

        // write 2 bytes for packet size
        byte[] packetSizeByteArray = (BigInteger.valueOf(HEADER_SIZE + this.payload.length)).toByteArray();
        paddingLength = 2 - packetSizeByteArray.length;
        for (int i = 0; i < paddingLength; i++) {
            byteStream.write(0);
        }
        byteStream.write(packetSizeByteArray, 0, Math.min(packetSizeByteArray.length, 2));

        // write the payload
        byteStream.write(this.payload, 0, this.payload.length);

        return byteStream.toByteArray();
    }

    /**
     * Unpacks a byte array to create a MPTransport object Assumes the array has
     * been
     * formatted using pack method in MPTransport
     * 
     * @param packet String representation of the MPTransport packet
     * @return MPTransport object created or null if the byte[] representation was
     *         corrupted
     */
    public static MPTransport unpack(byte[] packet) {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(packet);

        byte[] srcPortByteArray = new byte[4];
        if (byteStream.read(srcPortByteArray, 0, 4) != 4) {
            return null;
        }
        int srcPort = (new BigInteger(srcPortByteArray)).intValue();

        byte[] destPortByteArray = new byte[4];
        if (byteStream.read(destPortByteArray, 0, 4) != 4) {
            return null;
        }
        int destPort = (new BigInteger(destPortByteArray)).intValue();

        // int srcPort = byteStream.read();
        // int destPort = byteStream.read();
        int type = byteStream.read();
        int mpType = byteStream.read();

        byte[] windowByteArray = new byte[4];
        if (byteStream.read(windowByteArray, 0, 4) != 4) {
            return null;
        }
        int window = (new BigInteger(windowByteArray)).intValue();

        byte[] seqByteArray = new byte[4];
        if (byteStream.read(seqByteArray, 0, 4) != 4) {
            return null;
        }
        int seqNum = (new BigInteger(seqByteArray)).intValue();

        byte[] dSeqByteArray = new byte[4];
        if (byteStream.read(dSeqByteArray, 0, 4) != 4) {
            return null;
        }
        int dSeqNum = (new BigInteger(dSeqByteArray)).intValue();

        byte[] mappingByteArray = new byte[4];
        if (byteStream.read(mappingByteArray, 0, 4) != 4) {
            return null;
        }
        int mapping = (new BigInteger(mappingByteArray)).intValue();

        // int packetLength = byteStream.read();

        byte[] packetSizeByteArray = new byte[2];
        if (byteStream.read(packetSizeByteArray, 0, 2) != 2) {
            return null;
        }
        int packetLength = (new BigInteger(packetSizeByteArray)).intValue();

        // System.out.println("packetlength: " + packetLength);

        byte[] payload = new byte[packetLength - HEADER_SIZE];
        int bytesRead = Math.max(0, byteStream.read(payload, 0, payload.length));

        if ((HEADER_SIZE + bytesRead) != packetLength) {
            return null;
        }

        try {
            return new MPTransport(srcPort, destPort, type, mpType, window, seqNum, dSeqNum, mapping, payload);
        } catch (IllegalArgumentException e) {
            // will return null
        }
        return null;
    }
}
