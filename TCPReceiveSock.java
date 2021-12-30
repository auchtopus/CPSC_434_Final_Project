import java.lang.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.BlockingQueue;


public class TCPReceiveSock extends TCPSock implements Runnable {

    /* Listen socket only */
    int backlogSize;
    int backlogMax;
    Queue<TCPReceiveSock> backlog = new LinkedList<TCPReceiveSock>();
    HashSet<ConnID> sockets = new HashSet<ConnID>();
    ReceiverByteBuffer dataBuffer;
    ReceiverIntBuffer dsnBuffer;

    public TCPReceiveSock(MPSock mpSock, InetAddress addr, int port, BlockingQueue<Message> dataQ,
            BlockingQueue<Message> commandQ) {
        ///
        super();
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

    public void run() { // shared by listen socket and establish sockets
        socketStatus();
        while (true) {
            socketStatus();
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
                socketStatus();
                receive();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                ;
            }

        }
    }

    /* Connection Startup */

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

    /**
     * Accept a connection on a socket; this means to begin reading// remove from
     * the backlogs
     *
     * @return TCPReceiveSock The first established connection on the request queue
     */
    public TCPReceiveSock accept() { // only used by a listen socket
        if (this.state == State.LISTEN) {
            logOutput("accepting!");
            while (true) {
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
        TCPReceiveSock newEstSock = mpSock.createEstSocket(cID); 
        if (newEstSock == null) {
            return 0;
        }

        this.backlogSize += 1;
        // this.backlog.add(newEstSock);
        MPTransport ackTransport = new MPTransport(newEstSock.getPort(), cID.srcPort, MPTransport.ACK,
                MPTransport.MP_JOIN,
                newEstSock.dataBuffer.getAvail(),
                synTransport.getSeqNum(), 0, 0, new byte[0]);

        ConnID newcID = new ConnID(newEstSock.getAddr(), newEstSock.getPort(), cID.srcAddr, cID.srcPort); // inversion!
        sendSegment(newcID, ackTransport); // here
        logOutput("send handshake Ack: " + synTransport.getSeqNum());
        newEstSock.setState(State.ESTABLISHED);
        return 0;
    }

    /* data senders */
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
                dataBuffer.getWrite(), mpSock.receiverBuffer.getWrite(), 0, new byte[0]);
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* Transmission */

    void handleReceive(ConnID cID, MPTransport payload) {
        logOutput("===== RECEIVE STATE ======");
        printTransport(payload);
        logReceive(payload);
        printcID(cID);
        socketStatus();
        logOutput("==========================");
        if (payload.getType() == MPTransport.SYN && payload.getMpType() == MPTransport.MP_CAPABLE && this.role == LISTENER) { 
            // only for creating a new MPTCP connection, so this is only used by the original listenersocket
            logOutput("hello!");
            mpSock.handleNewConn(payload);
            receiveHandshakeMPSock(cID, payload);
        } else if (getState() == State.LISTEN) {
            logOutput("hello2!");
            if (this.receiveHandshakeListener(cID, payload) == -1) {
                refuse(cID.reverse());
            }
        } else if (getState() == State.ESTABLISHED || getState() == State.SHUTDOWN) {
            switch (payload.getType()) {
                case MPTransport.DATA: // we are receiver and getting a data
                    if (dataBuffer.getWrite() != payload.getSeqNum()) { // receieve a bad packet
                        logOutput("out of sequence!! " + dataBuffer.getWrite() + " " + payload.getSeqNum());
                        sendAck(false);
                    } else { // receieve a good packet on subflow acks
                        byte[] payloadBuffer = payload.getPayload();
                        int bytesRead = dataBuffer.write(payloadBuffer, 0, payloadBuffer.length);
                        if (bytesRead != payloadBuffer.length) {
                            logError("bytes read: " + bytesRead + "buffer Length " + payloadBuffer.length);
                        } else {
                            // want to check whether the mapping is complete to send to dataQ for MP Sock
                            int[] newDSN = new int[bytesRead];
                            for (int i = 0; i < bytesRead; i++) {
                                newDSN[i] = i + payload.getDSeqNum();
                            }
                            dsnBuffer.write(newDSN, 0, bytesRead);
                            if (payload.getLenMapping() != 0) { // len present, final bit in mapping
                                Integer len = dataBuffer.getWrite() - dataBuffer.getRead();
                                byte[] messagePayload = new byte[len];
                                int[] dumpPayload = new int[len];
                                dataBuffer.read(messagePayload, 0, len);
                                dsnBuffer.read(dumpPayload, 0, len);
                                // send message to BlockingQ
                                Message mapping = new Message(messagePayload, newDSN[bytesRead - 1] - len, len);
                                this.dataQ.offer(mapping);
                            }
                            sendAck(true);
                        }
                    }

                    break;

                case MPTransport.FIN: 

                    receiveFin(payload);
                    logOutput("FIN CLOSE");
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
                completeTeardownRT();
            } else if (payload.getType() == MPTransport.FIN) {
                sendAck(true);
            }
        } else if (getState() == State.TIME_WAIT) { // receiver only
            sendAck(false);
        } else if (getState() == State.CLOSED) {
            ;// we are done
        }

    }

    /* Shutdown */

    /* Graceful shutdown */
    public void close() {
        if (state == State.TIME_WAIT) {
            return;
        }
        this.state = State.SHUTDOWN;
        if (canTeardown()) {
            initTeardown();
        }

    }

    /* release immediately (abortive shutdown) */
    public void release() {
        refuse(this.cID);
        state = State.FIN_SENT;

    }

    boolean canTeardown() {
        return (role == SENDER && dataBuffer.getSendBase() == dataBuffer.getSendMax() && dataBuffer.getUnsent() == 0
                && dataBuffer.getUnAcked() == 0 && state == State.SHUTDOWN);
    }

    int initTeardown() { // to send the fin
        // only called if the buffers are clear
        sendFinRT();
        finSeq = dataBuffer.getSendMax();
        state = State.FIN_SENT;
        addTimer(3000, "completeTeardownRT", null, null);
        return 0;
    }

    public void completeTeardownRT() { // complete teardown
        logOutput("teardown?!");
        state = State.CLOSED;
        if (role == RECEIVER) {
            mpSock.removeReceiver(cID);
        } else if (role == SENDER) {
            mpSock.removeSender(cID);
        }
    }

    void receiveFin(MPTransport finTransport) {
        logOutput("RECEIVED FIN, going into TIME_WAIT");
        sendAck(true);
        state = State.TIME_WAIT;
        addTimer(3000, "completeTeardownRT", null, null);

    }

    void removeEstSocket(ConnID cID) {
        this.sockets.remove(cID);
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

    public void socketStatus() {
        try {
            logOutput("rp:" + dataBuffer.getRead() + " wp:" + dataBuffer.getWrite() + " state:" + state);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
