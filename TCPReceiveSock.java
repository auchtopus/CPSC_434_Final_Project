import java.util.*;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.Thread;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.InterfaceAddress;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.BlockingQueue;

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

public class TCPReceiveSock extends TCPSock implements Runnable {
    final int SENDER = 0;
    final int RECEIVER = 1;
    final int LISTENER = 2;
    final int DSEQ = 0; // this is a placeholder for what DSEQ should actually be in all Data packets
    final boolean STOPWAIT = false;
    final int BUFFERSIZE = 600;
    final int MAX_PACKET_SIZE = 500;
    int MAX_PAYLOAD_SIZE = MPTransport.MAX_PAYLOAD_SIZE;
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
    State state;
    ConnID cID;
    int role = LISTENER;
    ReceiverByteBuffer dataBuffer;
    ReceiverIntBuffer dsnBuffer;
    private DatagramSocket UDPSock;
    private byte[] UDPBuf = new byte[MAX_PAYLOAD_SIZE + 8]; // 8 bytes for UDP Header

    /* Listen socket only */
    int backlogSize;
    int backlogMax;
    Queue<TCPReceiveSock> backlog = new LinkedList<TCPReceiveSock>();
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
    BlockingQueue<Message> dataQ;
    BlockingQueue<Message> commandQ;
    
    public TCPReceiveSock(MPSock mpSock, InetAddress addr, int port, BlockingQueue<Message> dataQ, BlockingQueue<Message> commandQ) {
        ///
        this.mpSock = mpSock;
        this.addr = addr;
        this.port = port;
        this.commandQ = commandQ;
        this.dataQ = dataQ;
        this.dataBuffer = new ReceiverByteBuffer(BUFFERSIZE);
        this.dsnBuffer = new ReceiverIntBuffer(BUFFERSIZE);
        try {
            UDPSock = new DatagramSocket(this.port, this.addr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setSocketTimeout(int timeout){
        try{
            UDPSock.setSoTimeout(timeout);
        } catch (SocketException e){
            e.printStackTrace();
        }
    }

    public void run() { // shared by listen socket and establish sockets
        while (true) {
            logOutput("spin!");
            if (commandQ.peek() != null) { //
                // process commands
                Message.Command command = commandQ.poll().getCommand();

                switch (command) {
                    case ACCEPT:
                        this.accept();
                        break;
                    case CLOSE:
                        break;
                }
            }
            try {
                receive();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NullPointerException e){
                ;
            }

        }
    }

    public void setCID(ConnID cID) { // create listen socket
        this.cID = cID;
    }

    void receive() throws SocketTimeoutException, IOException{
        byte[] receiveData = new byte[MAX_PACKET_SIZE];
        DatagramPacket incomingPacket = new DatagramPacket(receiveData, receiveData.length);
        UDPSock.receive(incomingPacket);
        byte[] incomingPayload = incomingPacket.getData();
        Integer incomingPort = incomingPacket.getPort();
        InetAddress incomingAddress = incomingPacket.getAddress();
        MPTransport incomingTransport = MPTransport.unpack(incomingPayload);
        ConnID incomingcID = new ConnID(incomingAddress, incomingTransport.getSrcPort(), this.addr, incomingTransport.getDestPort());
        handleReceive(incomingcID, incomingTransport);
        return;
}

    public Boolean sendSegment(ConnID cID, MPTransport payload) {
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
        } catch (NullPointerException e){
            ;
        }


        return true;

        // logOutput("===============================");
        // logSender(payload);

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

    InetAddress getAddr(){
        return this.addr;
    }
    /**
     * Listen for connections on a socket
     * 
     * @param backlog int Maximum number of pending connections
     * @return int 0 on success, -1 otherwise
     */
    public int listen(int backlog) {
        this.state = State.LISTEN;
        this.backlogMax = backlog;
        this.backlogSize = 0;
        this.mpSock.addListenSocket(this);
        this.role = LISTENER;
        return 0;
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

        // need a new dest port
        MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, MPTransport.MP_CAPABLE,
                dataBuffer.getAvail(),
                synTransport.getSeqNum(), 0, 0, new byte[0]);
        sendSegment(cID, ackTransport); // here
        logOutput("send connection handshake Ack: " + synTransport.getSeqNum());
        return 0;
    }

    int receiveHandshakeListener(ConnID cID, MPTransport synTransport) { // create an est socket
        if (backlogSize >= backlogMax) {
            return -1;
        }
        logOutput("received handshake:" + cID.toString());
        // use the next avail port
        // port tracking
        TCPReceiveSock newEstSock = mpSock.createEstSocket(cID); // here set hardcoded ports
        if (newEstSock == null){
            return 0;
        }
        
        this.backlogSize += 1;
        this.backlog.add(newEstSock);
        MPTransport ackTransport = new MPTransport(newEstSock.getPort(), cID.srcPort, MPTransport.ACK, MPTransport.MP_JOIN,
                newEstSock.dataBuffer.getAvail(),
                synTransport.getSeqNum(), 0, 0, new byte[0]);

        ConnID newcID = new ConnID(newEstSock.getAddr(), newEstSock.getPort(), cID.srcAddr, cID.srcPort); // inversion!
        sendSegment(newcID, ackTransport); // here
        logOutput("send handshake Ack: " + synTransport.getSeqNum());
        newEstSock.setState(State.ESTABLISHED);
        return 0;
    }

    /**
     * Accept a connection on a socket; this means to begin reading// remove from
     * the backlogs
     *
     * @return TCPReceiveSock The first established connection on the request queue
     */
    public TCPReceiveSock accept() { // only used by a listen socket
        if (this.state == State.LISTEN) {
            logOutput("accepting!");
            while (true){
                TCPReceiveSock nextSock = this.backlog.poll();
                if (nextSock == null) {
                    // error! called accept with no
                    logOutput("called accept with nothing!");
                    return null;
                } else {
                    nextSock.logOutput(nextSock.cID.toString() + " state: " + nextSock.getState());
                    if (nextSock.getState() == State.ESTABLISHED) {
                        this.sockets.add(nextSock.cID);
                        // 3. change the state of the appropriate child socket
                        logOutput("est socket state: " + nextSock.getState());
                        return nextSock;
                    }
                }
            }
            
        }
        return null;
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

    public int sendWindowUpdateRT(Integer targAck) {
        if (dataBuffer.getWrite() > targAck) {
            return 0;
        }
        MPTransport updateTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0,
                dataBuffer.getAvail(),
                dataBuffer.getWrite(), DSEQ, 0, new byte[0]);
        logOutput("window update:rp" + dataBuffer.getRead() + " wp " + dataBuffer.getWrite());
        sendSegment(cID, updateTransport);// here
        String paramTypes[] = { "java.lang.Integer" };
        Object params[] = { targAck };
        addTimer(timeout, "sendWindowUpdateRT", paramTypes, params);
        return -1;
    }

    public int sendAck(boolean goodAck) { // no timer needed on acks
        MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0, dataBuffer.getAvail(),
                dataBuffer.getWrite(), DSEQ, 0, new byte[0]);
        logOutput("AVAIL: " + dataBuffer.getAvail());
        logSendAck(goodAck);
        sendSegment(cID, ackTransport);// here
        return 0;
    }

    public int sendAckRT() {
        return 0;
    }

    public int sendFinRT() {
        if (state == State.CLOSED) {
            return 0;
        }

        MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0,
                dataBuffer.getSendMax(),
                dataBuffer.getSendMax(), DSEQ, 0, new byte[0]);
        sendSegment(cID, ackTransport);// here

        addTimer(timeout, "sendFinRT", null, null);
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
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0,
                new byte[0]);
        sendSegment(cID, finTransport); // here
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

    public void handleReceive(ConnID cID, MPTransport payload) {
        logOutput("===== RECEIVE STATE ======");
        printTransport(payload);
        logReceive(payload);
        printcID(cID);
        socketStatus();
        logOutput("==========================");
        if (payload.getType() == MPTransport.SYN && payload.getMpType() == MPTransport.MP_CAPABLE) { // incoming MPTCP
                                                                                                     // Connection
            mpSock.handleNewConn(payload);
            receiveHandshakeMPSock(cID, payload);
        } else if (getState() == State.LISTEN) {
            if (this.receiveHandshakeListener(cID, payload) == -1) {
                refuse();
            }
        } else if (getState() == State.ESTABLISHED || getState() == State.SHUTDOWN) {
            switch (payload.getType()) {
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
                                // want to check whether the mapping is complete to send to dataQ for MP Sock
                                int[] newDSN = new int[bytesRead];
                                for(int i= 0; i < bytesRead; i++){
                                    newDSN[i] = i + payload.getDSeqNum();
                                }
                                dsnBuffer.write(newDSN, 0, bytesRead);
                                if (payload.getLenMapping() != 0) { //len present, final bit in mapping 
                                    Integer len = dataBuffer.getWrite()-dataBuffer.getRead();
                                    byte[] messagePayload = new byte[len];
                                    int[] dumpPayload = new int[len];
                                    dataBuffer.read(messagePayload, 0, len);
                                    dsnBuffer.read(dumpPayload, 0, len);
                                    //send message to BlockingQ
                                    Message mapping = new Message(messagePayload, newDSN[bytesRead-1]-len, len);
                                    this.dataQ.offer(mapping);
                                }
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
                    MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0,
                            dataBuffer.getAvail(),
                            payload.getSeqNum(), DSEQ, 0, new byte[0]);
                    sendSegment(cID, ackTransport); 
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

    /*
     * Read from the socket up to len bytes into the buffer buf starting at position
     * pos.
     *
     * @param buf byte[] the buffer
     * 
     * @param pos int starting position in buffer
     * 
     * @param len int number of bytes to read
     * 
     * @return int on success, the number of bytes read, which may be smaller than
     * len; on failure, -1
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

        if (sendUpdate) {
            int currAck = dataBuffer.getWrite();
            sendWindowUpdateRT(currAck);
        }

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


    public void printcID(ConnID cID){
        logOutput(cID.toString());
    }

    public void socketStatus() {
        try {
            if (role == SENDER) {
                // buffer.getState();
                logOutput("sb: " + dataBuffer.getSendBase() + " sm " + dataBuffer.getSendMax() + " wp:"
                        + dataBuffer.getWrite()
                        + " state:" + state + " RWND:" + RWND + " CWND:" + CWND);
            } else {
                logOutput("rp:" + dataBuffer.getRead() + " wp:" + dataBuffer.getWrite() + " state:" + state);
            }
        } catch (Exception e) {
            e.printStackTrace();
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

    void refuse(ConnID cID) {
        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0, 0, 0, DSEQ, 0,
                new byte[0]);
        sendSegment(cID, finTransport);

    }

    public void logError(String output) {
        log(output, System.err);
    }

    public void logOutput(String output) {
        log(output, System.out);
    }

    public void log(String output, PrintStream stream) {
        if (this.verboseState == Verbose.FULL) {
            stream.println(this.addr + ": " + this.port + ":" + output);
        } else if (this.verboseState == Verbose.REPORT) {
            ;
        } else {
            ;
        }
    }

}

//

// release: garbage collect the buffer