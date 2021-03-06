import java.lang.*;
import java.lang.Integer;
import java.lang.reflect.Method;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TCPSendSock extends TCPSock implements Runnable {

    /* Send sock only */
    SenderByteBuffer dataBuffer;
    SenderIntBuffer dsnBuffer;

    // tracking the status of the currently processing messaging
    byte[] mappingDataBuffer;
    int[] mappingDSNBuffer;
    int dataLoaded = 0;
    int mappingLen = 0;
    int mappingDSN = 0;
    boolean conn_fin = false;

    /*
     * For ListenSock only (which has no mQ and is managed entirely by the MPSock)
     */

    public TCPSendSock(MPSock mpSock, BlockingQueue<Message> dataQ, Verbose verboseState) {
        super();
        this.mpSock = mpSock;
        this.addr = this.mpSock.getAddr(); // here - to be hardcoded during creation of socket
        this.port = this.mpSock.getPort();
        this.dataQ = dataQ;
        this.role = SENDER;
        this.verboseState = verboseState;
    }


    public TCPSendSock(MPSock mpSock, InetAddress srcAddr, int srcPort, BlockingQueue<Message> dataQ, Verbose verboseState) {
        super();
        this.mpSock = mpSock;
        this.addr = srcAddr; // here - to be hardcoded during creation of socket
        this.port = srcPort;
        this.dataQ = dataQ;
        this.role = SENDER;
        this.verboseState = verboseState;
    }

    public void run() {
        while (true) {
            // handle incoming data
            Message mapping;
            logOutput("run level state: " + state + "|dataQ.isEmpty(): " + dataQ.isEmpty());

            if (!dataQ.isEmpty() && (this.getState() == State.ESTABLISHED || this.getState() == State.SHUTDOWN)) {
                if (mappingLen == 0) {
                    mapping = dataQ.poll();
                    dataLoaded = 0;
                    logOutput("dQ.poll:" + mapping.getSize());
                    mappingLen = mapping.getSize();
                    mappingDSN = mapping.getDSN();
                    if (mapping.getDATAFIN()) {
                        logOutput("DATA FIN in Message on sender side|dsnbuf can write: " + dsnBuffer.canWrite() + "|data loaded: " + dataLoaded + "|mappingLen: " + mappingLen);
                        this.conn_fin = true;
                    }

                    mappingDSNBuffer = new int[mapping.getSize()];
                    for (int i = 0; i < mapping.getSize(); i++) {
                        mappingDSNBuffer[i] = i + mappingDSN;
                    }

                    mappingDataBuffer = mapping.data;
                }

                // create the mirror dsn buffer; doing this outside for easier logic

                while (dsnBuffer.canWrite() && dataLoaded < mappingLen) {

                    // update dsnBuffer
                    dsnBuffer.write(mappingDSNBuffer, dataLoaded, mappingLen);

                    // update dataBuffer
                    int wrote = dataBuffer.write(mappingDataBuffer, dataLoaded, mappingLen - dataLoaded);
                    dataLoaded += wrote;
                    sendData();
                }

                if (this.conn_fin && mappingLen == 1) {
                    sendConnFinRT();
                }                


                // logOutput("dL:" + dataLoaded + "mL:" + mappingLen);
                if (dataLoaded == mappingLen) {
                    // finished loading this current mapping into the buffer!
                    // logOutput("finished mapping!");
                    mappingLen = 0;
                    dataLoaded = 0;
                }
                // update the dsn index

            }

            try {
                receive();
            } catch (SocketTimeoutException e) {
                ;
            } catch (Exception e) {
                ;
            }

            // handle receieve
        }

    }

    public void addTimer(long deltaT, String methodName, String[] paramType, Object[] params) {
        try {
            JavaTimer timer = new JavaTimer(deltaT, this, methodName, paramType, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* Connection Startup */

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

        return 0;
    }


    int finishHandshakeSender(ConnID cID, MPTransport ackTransport) {

        logOutput("input: " + ackTransport.getSeqNum() + " synSeq " + synSeq);
        if (ackTransport.getSeqNum() == synSeq) {
            estRTT = timeService.getTime() - timeSent;
            devRTT = 0;
            this.setState(State.ESTABLISHED);
            this.cID = cID.reverse(); // incoming from the new est socket
            RWND = ackTransport.getWindow();
            return 0;
        } else {
            return -1;
        }

    }

    /* Data Senders */

    public int sendSynRT(Integer synSeq) {
        if (state != State.SYN_SENT) {
            return 0;
        }
        logOutput("sent syn! " + synSeq);
        MPTransport synTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.SYN, MPTransport.MP_JOIN, 0,
                synSeq, DSEQ, 0, new byte[0]);
        sendSegment(cID, synTransport);// here
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

        sendSegment(cID, dataTransport);// here
        String paramTypes[] = { "MPTransport" };
        Object params[] = { dataTransport };
        addTimer(timeout, "sendDataRT", paramTypes, params);
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

        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, 0,
                dataBuffer.getSendMax(),
                dataBuffer.getSendMax(), DSEQ, 0, new byte[0]);
        sendSegment(cID, finTransport);// here

        addTimer(timeout, "sendFinRT", null, null);
        return 0;
    }

    public int sendConnFinRT() {
        if (state == State.CLOSED) {
            return 0;
        }

        MPTransport finTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.FIN, MPTransport.DATA_FIN,
                dataBuffer.getSendMax(),
                dataBuffer.getSendMax(), DSEQ, 0, new byte[0]);
        sendSegment(cID, finTransport);// here

        addTimer(timeout, "sendConnFinRT", null, null);
        this.state = State.FIN_SENT;
        return 0;
    }

    /* Transmission */

    void sendData() {
        int newPayloadSize = getPayloadSize(); // only a getter, no contract!

        while (newPayloadSize > 0) {
            // TODO: use the dsnBuffer!

            int mapping = 0;
            // prepare the byte buffer

            int[] payloadDsnBuffer = new int[newPayloadSize];
            int dsn = dsnBuffer.getSendMaxVal();
            int dsnWritten = dsnBuffer.read(payloadDsnBuffer, 0, newPayloadSize);

            byte[] payloadBuffer = new byte[newPayloadSize];
            int seq = dataBuffer.getSendMax();
            int payloadWritten = dataBuffer.read(payloadBuffer, 0, newPayloadSize);

            // error check
            if (payloadWritten != newPayloadSize) {
                logError("Write failure: payloadWritten: " + payloadWritten + " payloadSize" + newPayloadSize);
            }
            socketStatus();
            logOutput("unsent:" + dataBuffer.getUnsent());
            if (dataBuffer.getUnsent() == 0) {
                mapping = payloadWritten;
            }

            // retransmission
            MPTransport dataTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.DATA, 0, 0, seq,
                    dsn, mapping,
                    payloadBuffer); // CHANGE mapping flag based on len

            // add to the queue once
            sendDataRT(dataTransport);
            newPayloadSize = getPayloadSize();
        }

    }

    public void handleReceive(ConnID cID, MPTransport payload) {
        logOutput("===== RECEIVE STATE ======");
        printTransport(payload);
        logReceive(payload);
        printcID(cID);
        socketStatus();
        logOutput("==========================");
        if (payload.getType() == MPTransport.ACK && payload.getMpType() == MPTransport.MP_CAPABLE) {
            connect(cID.destAddr, cID.destPort); // send SYN to establish subflow
        } else if (getState() == State.SYN_SENT) {
            this.finishHandshakeSender(cID, payload);
        } else if (getState() == State.ESTABLISHED || getState() == State.SHUTDOWN) {

            switch (payload.getType()) {
                case MPTransport.ACK: // we are sender and getting an ack

                    int ackNum = payload.getSeqNum();
                    sampleRTT = getRTT(ackNum);
                    int dack = payload.getDSeqNum();

                    if (ackNum > dataBuffer.getSendBase()) {

                        // sampling...
                        if (ackNum == lastTransport.getSeqNum() + lastTransport.getPayload().length) {
                            sample(ackNum, sampleRTT);
                        }

                        // update sendbase
                        ackCounter = 0;
                        int oldSendBase = dataBuffer.acknowledge(ackNum);
                        dsnBuffer.acknowledge(ackNum);
                        logOutput("old sendBase: " + oldSendBase + " acknum: " + ackNum);
                        mpSock.sendBuffer.acknowledge(dack);
                        if (oldSendBase == -1) {
                            logError(
                                    "bad sendbase update of: " + ackNum + " " + "sendMax: "
                                            + this.dataBuffer.getSendMax());
                        }

                        // update FC and CC
                        updateAck(oldSendBase, ackNum, payload.getWindow(), sampleRTT);

                        // update dack


                        sendData();
                    } else if (ackNum == dataBuffer.getSendBase()) {
                        if (dataBuffer.getSendBase() == dataBuffer.getSendMax()) {
                            logOutput("window update");
                            logOutput("qlen:" + dataQ.size());
                            RWND = payload.getWindow();
                            mpSock.sendBuffer.acknowledge(dack);
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

                    break;

                case MPTransport.FIN: // someone told us to terminate

                    receiveFin(payload);

                    this.close();
                    break;
                case MPTransport.SYN:
                    MPTransport ackTransport = new MPTransport(cID.srcPort, cID.destPort, MPTransport.ACK, 0,
                            dataBuffer.getAvail(),
                            payload.getSeqNum(), DSEQ, 0, new byte[0]);
                    sendSegment(cID, ackTransport);// here
                    break;
            }

            if (canTeardown()) { // sender ONLY
                initTeardown();
            }
        } else if (getState() == State.FIN_SENT) { // sender only
            logOutput("got fin ack, type " + payload.getType());
            if (payload.getType() == MPTransport.ACK) {
                // don't bother checking the ack LOL
                this.state = State.CLOSED;
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

    /* releasse immediately (abortive shutdown) */
    public void release() {
        refuse(cID);
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
        logOutput("State: " + state);
        state = State.CLOSED;
        UDPSock.close();
        if (role == RECEIVER) {
            mpSock.removeReceiver(cID);
        } else if (role == SENDER) {
            mpSock.removeSender(cID);
        }
        System.exit(0);
    }

    void receiveFin(MPTransport finTransport) {
        logOutput("RECEIVED FIN, going into TIME_WAIT");
        sendAck(true);
        state = State.TIME_WAIT;
        addTimer(3000, "completeTeardownRT", null, null);

    }

    // void removeEstSocket(ConnID cID) {
    // this.sockets.remove(cID);
    // }

    /* CC, FC, Reno, Cubic */

    void sample(int ackNum, long sampleRTT) {
        logOutput("SAMPLE! acknum" + ackNum + " targetseqnum:" + lastTransport.getSeqNum()
                + "sendBase" + dataBuffer.getSendBase() + "len"
                + lastTransport.getPayload().length);

        estRTT = (long) (0.875 * estRTT + 0.125 * sampleRTT);
        devRTT = (long) (0.75 * devRTT + 0.25 * Math.abs(sampleRTT - estRTT));
        timeout = (int) (estRTT + 4 * devRTT);
        logOutput("new Timeout:" + timeout);
    }

    long getRTT(int newAck) {
        if (lastTransport != null && lastTransport.getSeqNum() + lastTransport.getPayload().length == newAck) {
            return timeService.getTime() - timeSent;
        } else {
            return -1;
        }

    }

    void renoAck(int oldAck, int recvAck) {
        assert (recvAck > oldAck);
        renoCWND += (int) ((recvAck - oldAck) * MSS / renoCWND);
    }

    void renoLoss() {
        renoCWND = (int) (renoMD * renoCWND);
    }

    double computeCubic(double timeElapse) {

        double wCubic = (double) (cubicC * Math.pow(timeElapse / 1000 - cubicK, 3) + wMax);
        // System.out.println("tElapse: " + timeElapse + " cubicK:" + cubicK + " wmax:"
        // + wMax + "wCubic:" + wCubic);
        return wCubic;
    }

    void cubicAck(double RTT) {
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

    void cubicLoss() {
        wMax = cubicCWND;
        cubicCWND = (int) (cubicBeta * cubicCWND);
        cubicK = (float) (Math.cbrt(wMax * (1 - cubicBeta) / cubicC));
        lossTimeStamp = timeService.getTime();
        // System.out.println("wMax:" + wMax + " cubkcK:" + cubicK + " cubicCWND:" +
        // cubicCWND);
    }

    void updateAck(int oldAck, int recvAck, int newRWND, long RTT) { // updates the window (so adjusts sendbase,
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

    void updateLoss() {
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

    int getPayloadSize() {
        logOutput("mps:" + Integer.toString(MAX_PAYLOAD_SIZE) + "|unsent:" +
                Integer.toString(dataBuffer.getUnsent())
                + "|window:"
                + Integer.toString(Math.min(CWND, RWND) - (dataBuffer.getSendMax() -
                        dataBuffer.getSendBase())));
        return Math.max(0, min(MAX_PAYLOAD_SIZE,
                dataBuffer.getUnsent(),
                Math.min(CWND, RWND) - (dataBuffer.getSendMax() - dataBuffer.getSendBase())));
    }

    void fastRetransmit() {
        // we can assume that everything in the air has been lost?
        dataBuffer.reset();
        dsnBuffer.reset();
        sendData();
    }

    void setCCAlgorithm(int type) {
        if (type == 1) {
            useCubic = true;
            return;
        }
        useCubic = false;
        return;

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
    // todo: determine if anybody even calls
    // public int write(byte[] buf, int pos, int len) {
    // logOutput("===== Before write =====");
    // int bytesWrite = dataBuffer.write(buf, pos, len);
    // if (bytesWrite == -1) {
    // return -1;
    // }
    // sendData();
    // logOutput("===== After write =====");
    // return bytesWrite;
    // }

    // /**
    // * Read from the socket up to len bytes into the buffer buf starting at
    // position
    // * pos.
    // *
    // * @param buf byte[] the buffer
    // * @param pos int starting position in buffer
    // * @param len int number of bytes to read
    // * @return int on success, the number of bytes read, which may be smaller than
    // * len; on failure, -1
    // */
    // public int read(byte[] buf, int pos, int len) {
    // boolean sendUpdate = false;
    // if (state == State.ESTABLISHED && !dataBuffer.canWrite()) {
    // // buffer out of space
    // sendUpdate = true;

    // }

    // if (state == State.TIME_WAIT && !dataBuffer.canRead()) {
    // logOutput("Receiver buffer cleared, no more data incoming");
    // state = State.CLOSED;
    // return 0;
    // }
    // logOutput("===== Before read =====");
    // int bytesRead = dataBuffer.read(buf, pos, len);
    // logOutput("===== After read =====");

    // return bytesRead;
    // }

    public void socketStatus() {
        try {
            logOutput("sb: " + dataBuffer.getSendBase() + " sm " + dataBuffer.getSendMax() + " wp:"
                    + dataBuffer.getWrite()
                    + " state:" + state + " RWND:" + RWND + " CWND:" + CWND);
        } catch (Exception E) {
            ;
        }

    }

}
