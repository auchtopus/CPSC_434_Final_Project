import java.io.PrintStream;
import java.net.InetAddress;
import java.util.Date;
import java.util.Random;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.BlockingQueue;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.net.SocketException;

public abstract class TCPSock {

    /* constants */
    final int SENDER = 0;
    final int RECEIVER = 1;
    final int LISTENER = 2;
    final int BUFFERSIZE = 600;
    final int MAX_PACKET_SIZE = 500;
    int MAX_PAYLOAD_SIZE = MPTransport.MAX_PAYLOAD_SIZE;
    int MSS = 128;
    boolean DELAY = false;
    public Verbose verboseState = Verbose.FULL;

    /* services */
    Date timeService = new Date();
    Random randGen = new Random(43);

    /* Socket */
    public InetAddress addr;
    public int port;
    State state;
    ConnID cID;
    int role;

    /* Timeout controls */
    MPTransport lastTransport;
    long timeSent;

    long estRTT;
    long sampleRTT;
    long devRTT;

    /* TCP controls */
    int CWND = 64;
    int RWND = 0;

    /* Reno */
    double renoMD = 0.5;
    int renoCWND = MSS * 2;

    /* Cubic */
    double cubicBeta = 0.7;
    double cubicC = 2;
    int cubicCWND = CWND;
    int ackCnt = 0;
    int epochStart;
    long lossTimeStamp;
    double wMax = 0;
    boolean cubicInit = false;
    float cubicK;
    boolean useCubic = false;

    int ackCounter = 0;

    /* timer controls */
    int timeout = 1000;
    /* open/close acks */
    int nextAckNum = 0;

    /* MPTCP */
    int DSEQ = 0;
    MPSock mpSock;
    int synSeq;
    int finSeq;
    DatagramSocket UDPSock;

    /* messageQs */
    BlockingQueue<Message> dataQ;
    BlockingQueue<Message> commandQ;

    /* Buffers */
    Buffer dataBuffer;
    Buffer dsnBuffer;

    public enum State {
        CLOSED, LISTEN, SYN_SENT, ESTABLISHED, SHUTDOWN, BUFFER_CLEAR, FIN_SENT, TIME_WAIT // close requested, FIN not
    }

    public enum Verbose {
        SILENT, REPORT, FULL
    }

    public TCPSock() {
        ;
    }

    /* Getters and Setters */

    public void setSocketTimeout(int timeout) {
        try {
            UDPSock.setSoTimeout(timeout);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    void setState(State state) {
        this.state = state;
    }

    public State getState() {
        return this.state;
    }

    int getPort() {
        return this.port;
    }

    void setPort(int port) {
        this.port = port;
    }

    void setAddr(InetAddress addr) {
        this.addr = addr;
    }

    InetAddress getAddr() {
        return this.addr;
    }

    public void setCID(ConnID cID) { // create listen socket
        this.cID = cID;

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
        return (state == State.SHUTDOWN || state == State.FIN_SENT);
    }

    /* Socket Functions */
    void receive() throws SocketTimeoutException, IOException {
        byte[] receiveData = new byte[MAX_PACKET_SIZE];
        DatagramPacket incomingPacket = new DatagramPacket(receiveData, receiveData.length);
        UDPSock.receive(incomingPacket);
        byte[] incomingPayload = incomingPacket.getData();
        Integer incomingPort = incomingPacket.getPort();
        InetAddress incomingAddress = incomingPacket.getAddress();
        MPTransport incomingTransport = MPTransport.unpack(incomingPayload);
        ConnID incomingcID = new ConnID(incomingAddress, incomingTransport.getSrcPort(), this.addr,
                incomingTransport.getDestPort());
        handleReceive(incomingcID, incomingTransport);
        return;
    }

    Boolean sendSegment(ConnID cID, MPTransport payload) {
        logOutput("===== SEND SEGMENT STATE ======");
        printTransport(payload);
        printcID(cID);
        socketStatus();
        lastTransport = payload;
        timeSent = timeService.getTime();
        byte[] bytePayload = payload.pack();
        // Brian send!
        try {
            // payload = "hello!".getBytes();
            DatagramPacket packet = new DatagramPacket(bytePayload, bytePayload.length, cID.destAddr, cID.destPort);
            UDPSock.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        try {
            receive();
        } catch (SocketTimeoutException e) {
            ; // don't need to reset timer for sendsegment;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            ;
        }

        return true;

        // logOutput("===============================");
        // logSender(payload);

    }

    void handleReceive(ConnID cID, MPTransport payload){};

    int bindListen(int localPort) {
        if (!mpSock.checkPort(localPort)) {
            this.port = localPort;
            this.state = State.CLOSED;
            return 0;
        } else {
            return -1;
        }

    }

    void removeEstSocket(ConnID cID) {
    }


    /* Socket API */

    public int bind(int localPort) {
        this.port = localPort;
        this.state = State.CLOSED;
        return 0;
    }

    public abstract void close();

    public void refuse() {
        logOutput("refusing!");
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0,
                new byte[0]);
        sendSegment(cID, finTransport); // here
        return;
    }

    public void refuse(ConnID cID) {
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0,
                new byte[0]);
        sendSegment(cID, finTransport);

    }

    /* Logging */

    void printTransport(MPTransport t) {
        logOutput("type: " + t.getType() + "|seq:" + t.getSeqNum() + "|wsize:" + t.getWindow() + "|psize:"
                + t.getPayload().length);
    }

    void printcID(ConnID cID) {
        logOutput(cID.toString());
    }

    void logError(String output) {
        log(output, System.err);
    }

    void logOutput(String output) {
        log(output, System.out);
    }

    void log(String output, PrintStream stream) {
        if (this.verboseState == Verbose.FULL) {
            stream.println(this.addr + ":" + this.port + ": " + output);
        } else if (this.verboseState == Verbose.REPORT) {
            ;
        } else {
            ;
        }
    }

    void logSendAck(boolean goodAck) {
        // System.out.print("ACKPRINT");
        if (goodAck) {
            System.out.print(".");
        } else {
            System.out.print("?");
        }
    }

    void logSender(MPTransport payload) {
        if (verboseState == Verbose.REPORT) {
            // System.out.print("SENDPRINT");
            if (payload.getType() == MPTransport.SYN) {
                System.out.print("S");
            } else if (payload.getType() == MPTransport.FIN) {
                System.out.print("F");
            } else if (payload.getType() == MPTransport.DATA) {
                if (payload.getSeqNum() + payload.getPayload().length == dataBuffer.getSendMax()) {
                    System.out.print(".");
                } else if (payload.getSeqNum() + payload.getPayload().length < dataBuffer.getSendMax()) {
                    System.out.print("!");
                }
            } else if (payload.getType() == MPTransport.ACK) {
                // System.out.print("ERROR");
                ; // this function does not log!
            }
        }

    }

    void logReceive(MPTransport payload) {
        if (verboseState == Verbose.REPORT) {
            // System.out.print("RECEIVEPRINT");
            if (payload.getType() == MPTransport.SYN) {
                System.out.print("S");
            } else if (payload.getType() == MPTransport.FIN) {
                System.out.print("F");
            } else if (payload.getType() == MPTransport.DATA) {
                if (role == SENDER) {
                    ;
                } else if (role == RECEIVER) {
                    if (payload.getSeqNum() == dataBuffer.getWrite()) {
                        System.out.print(".");
                    } else {
                        System.out.print("!");
                    }

                } else if (role == LISTENER) {

                }

            } else if (payload.getType() == MPTransport.ACK) {
                if (role == SENDER) {
                    if (payload.getSeqNum() == dataBuffer.getSendBase()) {
                        System.out.print("?");
                    } else if (payload.getSeqNum() > dataBuffer.getSendBase()) {
                        System.out.print(".");
                    } else {
                        System.out.print("ERROR");
                    }
                } else if (role == RECEIVER) {
                    if (payload.getSeqNum() == dataBuffer.getWrite()) {
                        System.out.print(".");
                    } else {
                        System.out.print("?");
                    }

                } else if (role == LISTENER) {

                }

            }
        }
    }


    void socketStatus(){}
    /* Utilities */

    public int min(int a, int b, int c) {
        return Math.min(a, (Math.min(b, c)));
    }

}
