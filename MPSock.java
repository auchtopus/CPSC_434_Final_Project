import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.midi.Receiver;
import javax.sound.sampled.SourceDataLine;

import java.net.*;
import java.io.*;

public class MPSock extends TCPSock {
    final int SENDER = 0;
    final int EVER = 1;

    int timeout = 1000;
    final int BUFFERSIZE = 1000;
    Date timeService = new Date();
    long timeSent;
    int numMessages = 0;

    // MPTCP
    Hashtable<ConnID, BlockingQueue<Message>> dataQMap;
    Hashtable<ConnID, BlockingQueue<Message>> commandQMap;
    public SenderByteBuffer sendBuffer;
    public ReceiverByteBuffer receiverBuffer;

    // debug
    Verbose verboseState;

    // buffering for reading from the dataQ
    byte[] mappingDataBuffer;
    int[] mappingDSNBuffer;
    int dataRead;
    int mappingLen;

    HashMap<ConnID, TCPSock> estMap = new HashMap<ConnID, TCPSock>();
    HashMap<Integer, TCPSock> listenMap = new HashMap<Integer, TCPSock>();
    Queue<TCPSock> backlog = new LinkedList<TCPSock>();

    public MPSock(InetAddress addr, int port) {
        this.addr = addr; // fishnet version
        this.port = port;
        dataQMap = new Hashtable<ConnID, BlockingQueue<Message>>();
        commandQMap = new Hashtable<ConnID, BlockingQueue<Message>>();
        verboseState = Verbose.FULL;

        // keep hashmap of port -> socket and track the state
    }

    /* Connection Creation */

    // listen on port
    public int listen(int backlog) {
        BlockingQueue<Message> commandQueue = new LinkedBlockingQueue<Message>();
        TCPReceiveSock listenSock = new TCPReceiveSock(this, this.addr, this.port, null, commandQueue);
        listenSock.listen(backlog);
        this.state = State.LISTEN;
        Runnable listenSockRunnable = (Runnable) listenSock;
        Thread listenSockThread = new Thread(listenSockRunnable);
        listenSockThread.start();
        this.role = LISTENER;
        return 0;
    }

    // Establish a MPTCP connection
    public int connect(InetAddress destAddr, int destPort) {
        // this is the declared "virtual" cID -- the actual send ports on both sides are
        // not this
        ConnID cID = new ConnID(this.addr, this.port, destAddr, destPort);
        this.state = State.SYN_SENT;
        sendBuffer = new SenderByteBuffer(BUFFERSIZE);
        // establish a send socket
        BlockingQueue<Message> dataQ = new LinkedBlockingQueue<Message>();
        dataQMap.put(cID, dataQ);
        TCPSendSock sendSock = new TCPSendSock(this, dataQ);
        sendSock.setCID(cID);
        logOutput("calling sendsock connect");
        sendSock.connect(destAddr, destPort);
        Runnable sendSockRunnable = (Runnable) sendSock;
        Thread sendSockThread = new Thread(sendSockRunnable);
        sendSockThread.start();

        System.out.println();
        this.state = State.SYN_SENT;

        // configure the state for opened connections

        // send SYN with MP_CAPABLE
        return 0;
    }

    // accept connection
    public MPSock accept() throws NullPointerException {
        if (this.state == State.LISTEN) {
            TCPReceiveSock listenSock = (TCPReceiveSock) listenMap.get(this.port);
            if (listenSock == null) {
                throw new NullPointerException("no listensock found");
            }
            Message acceptMsg = new Message(Message.Command.ACCEPT);
            logOutput("adding accept");
            listenSock.commandQ.offer(acceptMsg);
            // assert listenSock.commandQ.peek() != null;
            // this needs to block!

            receiverBuffer = new ReceiverByteBuffer(BUFFERSIZE);
            // System.out.println("mp: " + receiverBuffer.wp);
        }
        return this; // return this MPSock as we only have one connection
    }

    int addSubflow(InetAddress destAddr, int destPort) {
        connect(destAddr, destPort); // initiate subflow connection
        return 0;
    }

    public int sendSynRT(TCPSendSock sock) {
        // send SYN packet to establish connection
        if (this.state != State.SYN_SENT) {
            return 0;
        }
        MPTransport synTransport = new MPTransport(sock.cID.srcPort, sock.cID.destPort, MPTransport.SYN,
                MPTransport.MP_CAPABLE, 0, 0, 0, 0, new byte[0]);
        sock.sendSegment(sock.cID, synTransport);// here
        String paramTypes[] = { "TCPSendSock" };
        Object params[] = { sock };
        timeSent = timeService.getTime();
        addTimer(timeout, "sendSynRT", paramTypes, params); // no need to pass parameters to retransmit
        return -1;
    }

    int addListenSocket(TCPReceiveSock listenSock) {
        listenSock.logOutput("Adding listensock at port: " + listenSock.getPort());
        listenMap.put(listenSock.getPort(), listenSock);
        return 0;
    }

    TCPSendSock addSendSocket(ConnID cID, TCPSendSock sendSock) {
        estMap.put(cID, sendSock);
        return (TCPSendSock) estMap.get(cID);
    }

    TCPReceiveSock createEstSocket(ConnID cID) {
        ConnID reversecID = cID.reverse();
        if (estMap.containsKey(reversecID)) {
            return null;
        } else {
            estMap.put(reversecID, null);

        }
        int lowestPort = this.port;

        DatagramSocket checkPort = null;
        while (true) {
            try {
                checkPort = new DatagramSocket(lowestPort);
            } catch (IOException e) {
                lowestPort++;
                continue;
            }
            checkPort.close();
            break;
        }

        ConnID newcID = new ConnID(cID.destAddr, lowestPort, cID.srcAddr, cID.srcPort);
        logOutput("Calling createEstSocket" + ": " + newcID.toString());
        BlockingQueue<Message> dataQ = new LinkedBlockingQueue<Message>();
        BlockingQueue<Message> commandQ = new LinkedBlockingQueue<Message>();
        this.dataQMap.put(newcID, dataQ);
        this.commandQMap.put(newcID, commandQ);
        TCPReceiveSock newSock = new TCPReceiveSock(this, newcID.srcAddr, newcID.srcPort, dataQ, commandQ);
        newSock.setCID(newcID);
        newSock.setState(State.ESTABLISHED);
        estMap.put(newcID, newSock);
        newSock.dataBuffer = new ReceiverByteBuffer(BUFFERSIZE);
        newSock.dsnBuffer = new ReceiverIntBuffer(BUFFERSIZE);
        newSock.setSocketTimeout(5);
        newSock.socketStatus();
        assert (estMap.containsKey(cID));

        // run the new sock as a separate thread
        Runnable receiveSockRunnable = (Runnable) newSock;
        Thread receiveSockThread = new Thread(receiveSockRunnable);
        receiveSockThread.start();

        return newSock;
    }

    void handleNewConn(MPTransport payload) {
        // received SYN with MP_CAPABLE
        // ESTABLISH CONNECTION
        receiverBuffer = new ReceiverByteBuffer(BUFFERSIZE);
        this.state = State.ESTABLISHED;

    }

    /* Transmission */
    /**
     * Write to the socket up to len bytes from the buffer buf starting at position.
     * The write function then breaks data written into packets to send on each
     * subflow.
     * pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *         than len; on failure, -1
     */
    public int write(byte[] buf, int pos, int len) {
        // logOutput("===== Before write =====");
        // buffer.getState();
        int bytesWrite = sendBuffer.write(buf, pos, len);
        if (bytesWrite == -1) {
            return -1;
        }
        if (bytesWrite > 0) {
            logOutput(sendBuffer.toString());
        }
        readToQ();
        // logOutput("===== After write =====");
        // buffer.getState();
        return bytesWrite;
    }

    /*
     * moves data into queue
     */
    ConnID computeFairness() {
        // return the cID to handle the next mapping
        numMessages++;
        List<ConnID> keyList = new ArrayList<ConnID>(dataQMap.keySet());
        return keyList.get(numMessages % dataQMap.size());
    }

    public int readToQ() {
        // create new mapping
        int mappingSize = Math.min(sendBuffer.getUnsent(), MPTransport.MAX_PAYLOAD_SIZE);
        byte[] mappingPayload = new byte[mappingSize];
        int dsn = sendBuffer.getSendMax();
        int dataRead = sendBuffer.read(mappingPayload, 0, mappingSize);
        if (dataRead > 0) {
            assert dataRead == mappingSize;
            Message mapping = new Message(mappingPayload, dsn, mappingSize);
            // logOutput("map:dsn:" + dsn + "|declared size:" + mappingSize + "|actual
            // size:" + dataRead);
            // assign mapping
            dataQMap.get(computeFairness()).add(mapping);
            logOutput("enQ:" + mapping.getDSN() + "|sz:" + mapping.getSize() + "|buf:" + sendBuffer.toString());
        }

        return mappingSize;
    }

    /* Connection Closure */

    public void close() {
        // close all established TCPSock
        for (TCPSock sock : estMap.values()) {
            sock.close();
        }
        this.state = State.SHUTDOWN;
        addTimer(timeout, "completeTeardownRT", null, null);
    }

    public void completeTeardownRT() {
        for (TCPSock sock : estMap.values()) {
            if (sock.getState() != TCPReceiveSock.State.CLOSED) {
                addTimer(timeout, "completeTeardownRT", null, null);
                return;
            }
        }
        this.state = State.CLOSED; // all connection sockets are closed
    }

    void removeReceiver(ConnID cID) {
        estMap.remove(cID);
        listenMap.get(cID.srcPort).removeEstSocket(cID);
        // if (listenMap.get(cID.srcPort).sockets.size() == 0){
        // listenMap.remove(cID.srcPort);
        // }
    }

    void removeSender(ConnID cID) {
        estMap.remove(cID);
        // TOOD: consider adding logic to remove a listen socket upon clearing the
        // listenMap for a given listen socket
    }

    void removeEstSocket(ConnID cID) {
        ;
    }
    /*
     * Begin socket API
     */

    /* Utilities */

    public void addTimer(long deltaT, String methodName, String[] paramType, Object[] params) {
        // Edited for timer using Java Timer
        try {
            JavaTimer timer = new JavaTimer(deltaT, this, methodName, paramType, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    boolean checkPort(int portQuery) {
        return (listenMap.containsKey(portQuery));
    }

    /**
     * Read to the application up to len bytes from the message queues. The write
     * function then breaks data written into packets to send on each subflow.
     * pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *         than len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {

        if (mappingLen > 0) {
            // read from the cur message
            dataRead += receiverBuffer.write(mappingDataBuffer, dataRead, mappingLen - dataRead);
            if (dataRead == mappingLen) {
                dataRead = 0;
                mappingLen = 0;
            }
        }

        // logOutput("===== Before write =====");
        // buffer.getState();
        // peek all the blocks in the dataQlist and compare with DSN expected
        int expectedDseq = receiverBuffer.getWrite();
        // System.out.println("expdseq:" + expectedDseq);
        ArrayList<ConnID> keyList = new ArrayList<ConnID>(dataQMap.keySet());
        boolean polled = true;
        while (polled && receiverBuffer.canWrite()) {
            polled = false;
            for (ConnID cID : keyList) {
                BlockingQueue<Message> current = dataQMap.get(cID);
                Message curMsgPeek = (Message) current.peek();
                if (curMsgPeek != null && curMsgPeek.dsn == expectedDseq && mappingLen == 0) { // in order write to
                                                                                               // buffer
                    logOutput("peek:" + curMsgPeek);
                    Message curMsg = (Message) current.poll();

                    // load to local buffer
                    mappingDataBuffer = curMsg.data;
                    mappingLen = curMsg.getSize();
                    dataRead = 0;

                    dataRead += receiverBuffer.write(mappingDataBuffer, 0, curMsg.length);
                    polled = true;

                    if (dataRead == mappingLen) {
                        // resdt!
                        dataRead = 0;
                        mappingLen = 0;
                    } else {
                        break; // theoretically, this should preclude the mappingLen check, but it's doubled up
                               // for safety
                    }

                }
            }
        }

        //
        int bytesRead = receiverBuffer.read(buf, 0, len);
        // logOutput("===== After write =====");
        // buffer.getState();
        return bytesRead;
    }

    // method to print detials about a socket
    public void socketStatus() {
        if (role == SENDER) {
            logOutput("addr:" + this.getAddr() + "|port:" + this.getPort());
        } else {
            logOutput("addr:" + this.getAddr() + "|port:" + this.getPort());
        }
    }
}
