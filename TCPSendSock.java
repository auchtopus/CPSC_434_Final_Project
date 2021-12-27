import java.util.*;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.Thread;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.InterfaceAddress;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <p>
 * Title: CPSC 433/533 Programming Assignment
 * </p>
 *
 * <p>
 * Description: Fishnet socket implementation
 * </p>
 *
 * <p>
 * Copyright: Copyright (c) 2006
 * </p>
 *
 * <p>
 * Company: Yale University
 * </p>
 *
 * @author Hao Wang
 * @version 1.0
 */

public class TCPSendSock extends TCPSock implements Runnable {
    final int SENDER = 0;
    final int RECEIVER = 1;
    final int LISTENER = 2;
    final int DSEQ = 0; // this is a placeholder for what DSEQ should actually be in all Data packets
    final boolean STOPWAIT = false;
    final int BUFFERSIZE = 600;
    int MAX_PAYLOAD_SIZE = MPTransport.MAX_PAYLOAD_SIZE;
    int MAX_PACKET_SIZE = MPTransport.MAX_PACKET_SIZE;
    int MSS = 128;
    Date timeService = new Date();

    Verbose verboseState = Verbose.FULL;
    boolean DELAY = false;

    // TCP socket states

    // timer: java.lang.Integer
    // time_wait: keep responding to ack,
    Random randGen = new Random(43);

    static int maxPort = 1000;
    // Node node; //here
    MPSock mpSock;
    InetAddress addr;
    int synSeq;
    int finSeq;
    private int port;
    private State state;
    ConnID cID;
    int role = LISTENER;
    SenderByteBuffer dataBuffer;
    SenderIntBuffer dsnBuffer;
    private DatagramSocket UDPSock;
    // need to set a timeout
    private byte[] UDPBuf = new byte[MAX_PAYLOAD_SIZE + 8]; // 8 bytes for UDP Header

    /* Listen socket only */
    int backlogSize;
    int backlogMax;
    Queue<TCPSendSock> backlog = new LinkedList<TCPSendSock>();
    HashSet<ConnID> sockets = new HashSet<ConnID>();

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

    // MPTCP (move the UDP socket into this)
    private DatagramSocket socket;
    private InetAddress address;
    BlockingQueue<Message> dataQ;

    public TCPSendSock(MPSock mpSock) {
        this.mpSock = mpSock;
        this.addr = this.mpSock.getAddr(); // here - to be hardcoded during creation of socket
    }

    public TCPSendSock(MPSock mpSock, BlockingQueue<Message> dataQ) {
        this.mpSock = mpSock;
        this.addr = this.mpSock.getAddr(); // here - to be hardcoded during creation of socket
        this.dataQ = dataQ;
    }

    public TCPSendSock(MPSock mpSock, int sockType) {
        this.mpSock = mpSock;
        this.addr = this.mpSock.getAddr(); // here - to be hardcoded during creation of socket, use this constructor to
                                           // hardcode. TODO add array of port numbers
        setCCAlgorithm(sockType);
    }

    public void setCID(ConnID cID) { // create listen socket
        this.cID = cID;

    }

    public Boolean sendSegment(InetAddress srcAddr, InetAddress destAddr, MPTransport payload) {
        logOutput("===== SEND SEGMENT STATE ======");
        printTransport(payload);
        socketStatus();
        lastTransport = payload;
        timeSent = timeService.getTime();
        byte[] bytePayload = payload.pack();
        // Brian send!
        try {
            // payload = "hello!".getBytes();
            DatagramPacket packet = new DatagramPacket(bytePayload, bytePayload.length, destAddr, 4445); // dest port is
                                                                                                         // 4445,
                                                                                                         // transport
                                                                                                         // packet
                                                                                                         // should have
                                                                                                         // 4444
            UDPSock.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        DatagramPacket packet = new DatagramPacket(UDPBuf, UDPBuf.length);
        try {
            UDPSock.receive(packet);
            MPTransport transportPacket = MPTransport.unpack(packet.getData());
            handleReceive(cID, payload);
        } catch (SocketTimeoutException e) {
            String[] paramType = { "java.net.InetAddress", "java.net.InetAddress", "MPTransport" };
            Object[] params = { srcAddr, destAddr, payload };
            addTimer(timeout, "sendSegment", paramType, params);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;

    }

    /**
     *
     * @param localPort int local port number to bind the socket to
     * @return int 0 on success, -1 otherwise
     */
    public int bindListen(int localPort) {
        if (!mpSock.checkPort(localPort)) {
            this.port = localPort;
            this.state = State.CLOSED;
            return 0;
        } else {
            return -1;
        }

    }

    // bind non-unique establish socket
    public int bind(int localPort) {
        this.port = localPort;
        this.state = State.CLOSED;
        return 0;
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


    int finishHandshakeSender(ConnID cID, MPTransport ackTransport) {
        logOutput("input: " + ackTransport.getSeqNum() + " synSeq " + synSeq);
        if (ackTransport.getSeqNum() == synSeq) {
            estRTT = timeService.getTime() - timeSent;
            devRTT = 0;
            this.setState(State.ESTABLISHED);
            RWND = ackTransport.getWindow();
            return 0;
        } else {
            return -1;
        }

    }

    int receiveHandshakeMPSock(ConnID cID, MPTransport synTransport) {
        // Conn established and send ACK with MP_CAPABLE
        MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, MPTransport.MP_CAPABLE, dataBuffer.getAvail(),
                synTransport.getSeqNum(), 0, 0, new byte[0]);
        sendSegment(cID.srcAddr, cID.destAddr, ackTransport); //here
        logOutput("send connection handshake Ack: " + synTransport.getSeqNum());
        return 0;
    }

    /**
     * Accept a connection on a socket; this means to begin reading// remove from
     * the backlogs
     *
     * @return TCPSendSock The first established connection on the request queue
     */

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

    /* Timeout handler */

    // TOOD: move to cumulative ack
    /** Retransmission wrappers */
    public int sendSynRT(Integer synSeq) {
        if (state != State.SYN_SENT) {
            return 0;
        }
        logOutput("sent syn! " + synSeq);
        MPTransport synTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.SYN, MPTransport.MP_JOIN, 0, synSeq, DSEQ, 0, new byte[0]);
        sendSegment(cID.srcAddr, cID.destAddr, synTransport);//here
        String paramTypes[] = { "java.lang.Integer" };
        Object params[] = { synSeq };
        timeSent = timeService.getTime();
        addTimer(timeout, "sendSynRT", paramTypes, params); // no need to pass parameters to retransmit
        return -1;
    }

    public int sendDataRT(MPTransport dataTransport) {
        if (dataBuffer.getSendBase() > dataTransport.getSeqNum()
                || !(state == State.ESTABLISHED || state == State.SHUTDOWN)) {
            logOutput("disable timer!" + dataTransport.getSeqNum());
            return 0;
        }

        logOutput("Sending: " + dataTransport.getSeqNum() + " size: " + (dataTransport.getSeqNum()) + " ackwant: "
                + dataBuffer.getSendBase());
        sendSegment(cID.srcAddr, cID.destAddr, dataTransport);// here
        String paramTypes[] = { "Transport" };
        Object params[] = { dataTransport };
        addTimer(timeout, "sendDataRT", paramTypes, params);
        return -1;
    }

    public int sendAck(boolean goodAck) { // no timer needed on acks
        MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0, dataBuffer.getAvail(),
                dataBuffer.getWrite(), DSEQ, 0, new byte[0]);
        logOutput("AVAIL: " + dataBuffer.getAvail());
        logSendAck(goodAck);
        sendSegment(cID.srcAddr, cID.destAddr, ackTransport);// here
        return 0;
    }

    public int sendAckRT() {
        return 0;
    }

    public int sendFinRT() {
        if (state == State.CLOSED) {
            return 0;
        }

        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, dataBuffer.getSendMax(),
                dataBuffer.getSendMax(), DSEQ, 0, new byte[0]);
        sendSegment(cID.srcAddr, cID.destAddr,  finTransport);//here

        addTimer(timeout, "sendFinRT", null, null);
        return 0;
    }

    /**
     * Initiate connection to a remote socket; establish as a send socket
     *
     * @param destAddr int Destination node address
     * @param destPort int Destination port
     * @return int 0 on success, -1
     */

    public int connect(InetAddress destAddr, int destPort) {
        dataBuffer = new SenderByteBuffer(BUFFERSIZE);
        dsnBuffer = new SenderIntBuffer(BUFFERSIZE);
        this.cID = new ConnID(this.addr, this.port, destAddr, destPort);
        try {
            UDPSock = new DatagramSocket(this.port);
            UDPSock.setSoTimeout(20);
        } catch (Exception e) {
            e.printStackTrace();
        }
        state = State.SYN_SENT;
        synSeq = randGen.nextInt(50) + 10;
        logOutput("syn seq: " + synSeq);
        sendSynRT(synSeq);

        this.role = SENDER;

        // TODO: configure timer
        return 0;
    }

    public void addTimer(long deltaT, String methodName, String[] paramType, Object[] params) {
        try {
            JavaTimer timer = new JavaTimer(deltaT, this, methodName, paramType, params);
            // Method method = Callback.getMethod(methodName, this, paramType);
            // Callback cb = new Callback(method, this, params);
            // this.mpSock.manager.addTimer(addr, deltaT, cb);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */

    // receiever cannot inidiate shutdown
    public void close() {
        if (state == State.TIME_WAIT) {
            return;
        }
        this.state = State.SHUTDOWN;
        if (canTeardown()) {
            initTeardown();
        }

    }

    public boolean canTeardown() {
        return (role == SENDER && dataBuffer.getSendBase() == dataBuffer.getSendMax() && dataBuffer.getUnsent() == 0
                && dataBuffer.getUnAcked() == 0 && state == State.SHUTDOWN);
    }

    public int initTeardown() { // to send the fin
        // only called if the buffers are clear
        sendFinRT();
        finSeq = dataBuffer.getSendMax();
        state = State.FIN_SENT;
        addTimer(3000, "completeTeardown", null, null);
        return 0;
    }

    public void completeTeardown() { // complete teardown
        state = State.CLOSED;
        if (role == RECEIVER) {
            mpSock.removeReceiver(cID);
        } else if (role == SENDER) {
            mpSock.removeSender(cID);
        }
    }

    public void receiveFin(MPTransport finTransport) {
        logOutput("RECEIVED FIN, going into TIME_WAIT");
        sendAck(true);
        state = State.TIME_WAIT;
        addTimer(3000, "completeTeardown", null, null);

    }

    public void removeEstSocket(ConnID cID) {
        this.sockets.remove(cID);
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void refuse() {
        logOutput("refusing!");
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0, new byte[0]);
        sendSegment(cID.srcAddr, cID.destAddr, finTransport); //here
        return;
    }

    public void release() {
        refuse();
        state = State.FIN_SENT;

    }

    public long getRTT(int newAck) {
        if (lastTransport != null && lastTransport.getSeqNum() + lastTransport.getPayload().length == newAck) {
            return timeService.getTime() - timeSent;
        } else {
            return -1;
        }

    }

    public void renoAck(int oldAck, int recvAck) {
        assert (recvAck > oldAck);
        renoCWND += (int) ((recvAck - oldAck) * MSS / renoCWND);
    }

    public void renoLoss() {
        renoCWND = (int) (renoMD * renoCWND);
    }

    public double computeCubic(double timeElapse) {

        double wCubic = (double) (cubicC * Math.pow(timeElapse / 1000 - cubicK, 3) + wMax);
        // System.out.println("tElapse: " + timeElapse + " cubicK:" + cubicK + " wmax:"
        // + wMax + "wCubic:" + wCubic);
        return wCubic;
    }

    public void cubicAck(double RTT) {
        // if an RTT exists, use RTT; otherwwise pass -1 and use the estRTT;
        double elapse = timeService.getTime() - lossTimeStamp;
        double localRTT;
        if (RTT > 0) {
            localRTT = RTT;
        } else {
            localRTT = estRTT;
        }
        double wCubic = computeCubic(elapse + localRTT);
        cubicCWND += (wCubic - cubicCWND) / cubicCWND * MSS;
    }

    public void cubicLoss() {
        wMax = cubicCWND;
        cubicCWND = (int) (cubicBeta * cubicCWND);
        cubicK = (float) (Math.cbrt(wMax * (1 - cubicBeta) / cubicC));
        lossTimeStamp = timeService.getTime();
        // System.out.println("wMax:" + wMax + " cubkcK:" + cubicK + " cubicCWND:" +
        // cubicCWND);
    }

    // use estRTT instead of dMin

    public void updateAck(int oldAck, int recvAck, int newRWND, long RTT) { // updates the window (so adjusts sendbase,
                                                                            // queue, etc. etc.))
        // update rate control
        RWND = newRWND;
        // System.out.println("old cwnd:" + CWND);
        renoAck(oldAck, recvAck);
        if (useCubic && cubicInit) {
            cubicAck(RTT);
        }

        if (cubicInit) {
            if (Math.max(renoCWND, cubicCWND) < wMax) { // not probing
                CWND = Math.max(renoCWND, cubicCWND);
            } else {
                CWND = cubicCWND;
            }
        } else {
            CWND = renoCWND;
            cubicCWND = CWND;
        }
        // System.out.println("new cwnd:" + CWND + " cubic: " + cubicCWND + "reno:" +
        // renoCWND);
        // dataBuffer.getState();

    }

    public void updateLoss() {
        // System.out.println("LOSS EVENT");
        renoLoss();

        if (useCubic && !cubicInit) {
            lossTimeStamp = timeService.getTime();
            cubicInit = true;
            cubicLoss();
        } else {
            cubicLoss();
        }
        if (Math.max(renoCWND, cubicCWND) < wMax) { // not probing
            CWND = Math.max(renoCWND, cubicCWND);
        } else {
            CWND = cubicCWND;
        }
        // System.out.println("new cwnd:" + CWND + " cubic: " + cubicCWND + "reno:" +
        // renoCWND);
    }

    public void sendData() {
        int newPayloadSize = getPayloadSize();
        while (newPayloadSize > 0) {
            // read through the databuffer index looking for incongruity

            // prepare the byte buffer
            byte[] payloadBuffer = new byte[newPayloadSize];
            int dataAck = dataBuffer.getSendMax();
            int payloadWritten = dataBuffer.read(payloadBuffer, 0, newPayloadSize);
            if (payloadWritten != newPayloadSize) {
                logError("Write failure: payloadWritten: " + payloadWritten + " payloadSize" + newPayloadSize);
            }

            // retransmission
            MPTransport dataTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.DATA, 0, 0, dataAck, DSEQ, 0, 
                    payloadBuffer); //CHANGE mapping flag based on len

            // add to the queue once
            sendDataRT(dataTransport);
            newPayloadSize = getPayloadSize();
        }
        logOutput("Trying to send packet of 0 bytes");

    }

    public int getPayloadSize() {
        return Math.max(0, min(MAX_PAYLOAD_SIZE, dataBuffer.getUnsent(),
                Math.min(CWND, RWND) - (dataBuffer.getSendMax() - dataBuffer.getSendBase())));
    }

    public void fastRetransmit() {
        // we can assume that everything in the air has been lost?
        dataBuffer.reset();
        sendData();
    }

    public void handleReceive(ConnID cID, MPTransport payload) {
        logOutput("===== RECEIVE STATE ======");
        printTransport(payload);
        logReceive(payload);
        socketStatus();
        logOutput("==========================");
        if (payload.getType() == MPTransport.SYN && payload.getMpType() == MPTransport.MP_CAPABLE) { // incoming MPTCP
                                                                                                     // Connection
            mpSock.handleNewConn(payload);
            receiveHandshakeMPSock(cID, payload);
        } else if (payload.getType() == MPTransport.ACK && payload.getMpType() == MPTransport.MP_CAPABLE) { // established
                                                                                                            // MPTCP
                                                                                                            // Connection
            connect(cID.destAddr, cID.destPort); // send SYN to establish subflow
        }
        // else if (getState() == State.LISTEN) {
        // if (this.receiveHandshakeListener(cID, payload) == -1) {
        // refuse();
        // }
        // }
        else if (getState() == State.SYN_SENT) {
            this.finishHandshakeSender(cID, payload);
        } else if (getState() == State.ESTABLISHED || getState() == State.SHUTDOWN) {

            switch (payload.getType()) {
                case MPTransport.ACK: // we are sender and getting an ack

                    if (this.role == RECEIVER) {
                        logOutput("this should not happen?");
                        // refuse();
                    } else {
                        int ackNum = payload.getSeqNum();
                        sampleRTT = getRTT(ackNum);
                        if (ackNum > dataBuffer.getSendBase()) {
                            //
                            // extract this code
                            if (ackNum == lastTransport.getSeqNum() + lastTransport.getPayload().length) {
                                logOutput("SAMPLE! acknum" + ackNum + " targetseqnum:" + lastTransport.getSeqNum()
                                        + "sendBase" + dataBuffer.getSendBase() + "len"
                                        + lastTransport.getPayload().length);

                                estRTT = (long) (0.875 * estRTT + 0.125 * sampleRTT);
                                devRTT = (long) (0.75 * devRTT + 0.25 * Math.abs(sampleRTT - estRTT));
                                timeout = (int) (estRTT + 4 * devRTT);
                                logOutput("new Timeout:" + timeout);
                            }
                            ackCounter = 0;
                            int oldSendBase = dataBuffer.acknowledge(ackNum);
                            logOutput("old sendBase: " + oldSendBase + " acknum: " + ackNum);

                            if (oldSendBase == -1) {
                                // bad sendbase update
                                logError(
                                        "bad sendbase update of: " + ackNum + " " + "sendMax: "
                                                + this.dataBuffer.getSendMax());
                            }
                            updateAck(oldSendBase, ackNum, payload.getWindow(), sampleRTT);
                            sendData();
                        } else if (ackNum == dataBuffer.getSendBase()) {
                            if (dataBuffer.getSendBase() == dataBuffer.getSendMax()) {
                                // window update
                                RWND = payload.getWindow();
                                sendData();
                                break;
                            }
                            logOutput("bad ack! " + payload.getSeqNum() + "sendBase: " + dataBuffer.getSendBase());
                            ackCounter += 1;
                            if (ackCounter == 3) {
                                updateLoss();
                                fastRetransmit();
                            }
                        }

                    }
                    break;
                case MPTransport.DATA: // we are receiver and getting a data
                    if (this.role != RECEIVER) {
                        refuse();
                    } else {
                        if (dataBuffer.getWrite() != payload.getSeqNum()) { // receieve a bad packet
                            logOutput("out of sequence!! " + dataBuffer.getWrite() + " " + payload.getSeqNum());
                            sendAck(false);
                        } else { // receieve a good packet
                            byte[] payloadBuffer = payload.getPayload();
                            int bytesRead = dataBuffer.write(payloadBuffer, 0, payloadBuffer.length);
                            if (bytesRead != payloadBuffer.length) {
                                logError("bytes read: " + bytesRead + "buffer Length " + payloadBuffer.length);

                            } else {
                                sendAck(true);
                            }
                        }
                    }
                    break;

                case MPTransport.FIN: // someone told us to terminate

                    receiveFin(payload);

                this.close();
                break;
            case MPTransport.SYN:
                MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0, dataBuffer.getAvail(),
                        payload.getSeqNum(), DSEQ, 0, new byte[0]);
                sendSegment(cID.srcAddr, cID.destAddr,  ackTransport);//here
                break;
            }

            if (canTeardown()) { // sender ONLY
                initTeardown();
            }
        } else if (getState() == State.FIN_SENT) { // sender only
            if (payload.getType() == MPTransport.ACK) {
                // don't bother checking the ack LOL
                state = State.CLOSED;
                completeTeardown();
            } else if (payload.getType() == MPTransport.FIN) {
                sendAck(true);
            }
        } else if (getState() == State.TIME_WAIT) { // receiver only
            sendAck(false);
        } else if (getState() == State.CLOSED) {
            ;// we are done
        }

    }

    /**
     * Write to the socket up to len bytes from the buffer buf starting at position
     * pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *         than len; on failure, -1
     */
    public int write(byte[] buf, int pos, int len) {
        logOutput("===== Before write =====");
        // dataBuffer.getState();
        int bytesWrite = dataBuffer.write(buf, pos, len);
        if (bytesWrite == -1) {
            return -1;
        }
        sendData();
        logOutput("===== After write  =====");
        // dataBuffer.getState();
        return bytesWrite;
    }

    /**
     * Read from the socket up to len bytes into the buffer buf starting at position
     * pos.
     *
     * @param buf byte[] the buffer
     * @param pos int starting position in buffer
     * @param len int number of bytes to read
     * @return int on success, the number of bytes read, which may be smaller than
     *         len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {
        boolean sendUpdate = false;
        if (state == State.ESTABLISHED && !dataBuffer.canWrite()) {
            // buffer out of space
            sendUpdate = true;

        }

        if (state == State.TIME_WAIT && !dataBuffer.canRead()) {
            logOutput("Receiver buffer cleared, no more data incoming");
            state = State.CLOSED;
            return 0;
        }
        // dataBuffer.getState();
        logOutput("===== Before read  =====");
        int bytesRead = dataBuffer.read(buf, pos, len);
        logOutput("===== After read   =====");
        // dataBuffer.getState();

        // if (sendUpdate) {
        //     int currAck = dataBuffer.getWrite();
        //     // sendWindowUpdateRT(currAck);
        // }

        return bytesRead;
    }

    public void setCCAlgorithm(int type) {
        if (type == 1) {
            useCubic = true;
            return;
        }
        useCubic = false;
        return;

    }

    /*
     * Logging
     */

    public void printTransport(MPTransport t) {
        logOutput("type: " + t.getType() + "|seq:" + t.getSeqNum() + "|wsize:" + t.getWindow() + "|psize:"
                + t.getPayload().length);
    }

    public void socketStatus() {
        try {
            if (role == SENDER) {
                // buffer.getState();
                logOutput("sb: " + dataBuffer.getSendBase() + " sm " + dataBuffer.getSendMax() + " wp:"
                        + dataBuffer.getWrite()
                        + " state:" + state + " RWND:" + RWND + " CWND:" + CWND);
            } else {
                logOutput("rp" + dataBuffer.getRead() + " wp:" + dataBuffer.getWrite() + " state:" + state);
            }
        } catch (Exception E) {
            ;
        }

    }

    public void logSendAck(boolean goodAck) {
        // System.out.print("ACKPRINT");
        if (goodAck) {
            System.out.print(".");
        } else {
            System.out.print("?");
        }
    }

    public void logSender(MPTransport payload) {
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

    public void logReceive(MPTransport payload) {
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

    /* */
    public int min(int a, int b, int c) {
        return Math.min(a, (Math.min(b, c)));
    }


    public void run() {
        while (true) {
            // handle incoming data
            Message mapping;
            if (!dataQ.isEmpty()) {
                try {

                    mapping = dataQ.poll(100, TimeUnit.MILLISECONDS);

                    // update the dsn index
                    int[] newDSN = new int[mapping.getSize()];
                    for(int i= 0; i < mapping.getSize(); i++){
                        newDSN[i] = i + mapping.dsn;
                    }
                    dsnBuffer.write(newDSN, 0, mapping.getSize());

                    // update the actual data
                    dataBuffer.write(mapping.data, 0, mapping.getSize());

                    sendData();

                } catch (InterruptedException e) {
                    ;
                }
            }

            byte[] receiveData = new byte[MAX_PACKET_SIZE];

            while (true) {
                try {
                    DatagramPacket incomingPacket = new DatagramPacket(receiveData, receiveData.length);
                    UDPSock.receive(incomingPacket);
                    byte[] incomingPayload = incomingPacket.getData();
                    Integer incomingPort = incomingPacket.getPort();
                    InetAddress incomingAddress = incomingPacket.getAddress();
                    MPTransport incomingTransport = MPTransport.unpack(incomingPayload);
                    ConnID ackTuple = new ConnID(this.addr, this.port, incomingAddress,
                            incomingPort);
                    handleReceive(cID, incomingTransport);
                } catch (SocketTimeoutException e) {
                    e.printStackTrace();
                } catch (IOException e){
                    e.printStackTrace();
                }
            }

            // handle receieve
        }
    }

    void refuse(ConnID cID) {
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0, new byte[0]);
        sendSegment(cID.srcAddr, cID.destAddr, finTransport);

    }



    // logging


    public void logError(String output) {
        log(output, System.err);
    }

    public void logOutput(String output) {
        log(output, System.out);
    }

    public void log(String output, PrintStream stream) {
        if (this.verboseState == Verbose.FULL) {
            stream.println("Node " + this.addr + ": " + output);
        } else if (this.verboseState == Verbose.REPORT) {
            ;
        } else {
            ;
        }
    }


}

//

// release: garbage collect the buffer