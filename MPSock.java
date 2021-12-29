import java.net.Socket;
import java.util.*;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Enumeration;
import java.lang.NullPointerException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;


/**
 * <p>
 * Title: CPSC 433/533 Programming Assignment
 * </p>
 *
 * <p>
 * Description: Fishnet TCP manager
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
 * @author Hao Wanga
 * @version 1.0
 */

public class MPSock extends TCPSock{
    final int SENDER = 0;
    final int EVER = 1;

    private State state;
    int timeout = 1000;
    final int BUFFERSIZE = 1000;
    Date timeService = new Date();
    long timeSent;
    int numMessages = 0;
    // MPTCP
    List<BlockingQueue<Message>> dataQList;
    List<BlockingQueue<Message>> commandQList;
    SenderByteBuffer sendBuffer;
    ReceiverByteBuffer receiverBuffer;


    // debug
    Verbose verboseState;
    

    private byte[] buf;


    HashMap<ConnID, TCPSock> estMap = new HashMap<ConnID, TCPSock>();
    HashMap<Integer, TCPSock> listenMap = new HashMap<Integer, TCPSock>();
    Queue<TCPSock> backlog = new LinkedList<TCPSock>();

    public MPSock(InetAddress addr, int port) {
        this.addr = addr; // fishnet version
        this.port = port;
        dataQList = new ArrayList<BlockingQueue<Message>>();
        commandQList = new ArrayList<BlockingQueue<Message>>();
        verboseState = Verbose.FULL;

        // keep hashmap of port -> socket and track the state
    }

    public void addTimer(long deltaT, String methodName, String[] paramType, Object[] params) {
        // Edited for timer using Java Timer
        try {
            JavaTimer timer = new JavaTimer(deltaT, this, methodName, paramType, params);
            // Method method = Callback.getMethod(methodName, this, paramType);
            // Callback cb = new Callback(method, this, params);
            // this.manager.addTimer(addr, deltaT, cb);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // bind non-unique establish socket
    public int bind(int localPort) {
        this.port = localPort;
        this.state = State.CLOSED;
        return 0;
    }

    // create a new listening TCPSock
    public int listen(int backlog) {
        BlockingQueue<Message> commandQueue = new LinkedBlockingQueue<Message>();
        TCPReceiveSock listenSock = new TCPReceiveSock(this, this.addr, this.port, null, commandQueue);
        listenSock.listen(backlog);
        this.state = State.LISTEN;
        Runnable listenSockRunnable = (Runnable) listenSock;
        Thread listenSockThread = new Thread(listenSockRunnable);
        listenSockThread.start();
        return 0;
    }

    public MPSock accept() throws NullPointerException{
        if (this.state == State.LISTEN) {
            TCPReceiveSock listenSock = (TCPReceiveSock) listenMap.get(this.port);
            if (listenSock == null){
                throw new NullPointerException("no listensock found");
            }
            Message acceptMsg = new Message(Message.Command.ACCEPT);
            logOutput("adding accept");
            listenSock.commandQ.offer(acceptMsg);
            assert listenSock.commandQ.peek() != null;
            // this needs to block!
        }
        return this; // return this MPSock as we only have one connection
    }

    // fix this
    public int addSubflow(InetAddress destAddr, int destPort) {
        TCPSendSock sendSock = new TCPSendSock(this);
        sendSock.connect(destAddr, destPort); // initiate subflow connection
        return 0;
    }

    // Establish a MPTCP connection
    public int connect(InetAddress destAddr, int destPort) {
        ConnID cID = new ConnID(this.addr, this.port, destAddr, destPort);
        this.state = State.SYN_SENT;
        sendBuffer = new SenderByteBuffer(BUFFERSIZE);
        // establish a send socket
        BlockingQueue<Message> dataQ = new LinkedBlockingQueue<Message>();
        dataQList.add(dataQ);
        TCPSendSock sendSock = new TCPSendSock(this, dataQ);
        sendSock.setCID(cID);
        sendSock.connect(destAddr, destPort);
        Runnable sendSockRunnable = (Runnable) sendSock;
        Thread sendSockThread = new Thread(sendSockRunnable);
        sendSockThread.start();
        // send SYN with MP_CAPABLE
        return 0;
    }


    public int sendSynRT(TCPSendSock sock) {
        // send SYN packet to establish connection
        if (this.state != State.SYN_SENT) {
            return 0;
        }
        MPTransport synTransport = new MPTransport(sock.cID.srcPort, sock.cID.destPort, MPTransport.SYN, MPTransport.MP_CAPABLE, 0, 0, 0, 0, new byte[0]);
        sock.sendSegment(sock.cID, synTransport);//here
        String paramTypes[] = { "TCPSendSock" };
        Object params[] = { sock };
        timeSent = timeService.getTime();
        addTimer(timeout, "sendSynRT", paramTypes, params); // no need to pass parameters to retransmit
        return -1;
    }

    // TODO: client side to connect
    public void handleNewConn(MPTransport payload) {
        // received SYN with MP_CAPABLE
        // ESTABLISH CONNECTION
        receiverBuffer = new ReceiverByteBuffer(BUFFERSIZE);
        this.state = State.ESTABLISHED;

    }

    public void close() {
        // close all established TCPSock
        for (TCPSock sock : estMap.values()) {
            sock.close();
        }
        this.state = State.SHUTDOWN;
        addTimer(timeout, "completeTeardown", null, null);
    }

    public void completeTeardown(){
        for (TCPSock sock : estMap.values()) {
            if(sock.getState() != TCPReceiveSock.State.CLOSED){
                addTimer( timeout, "completeTeardown", null, null);
                return;
            }
        }
        this.state = State.CLOSED; // all connection sockets are closed
    }

    public InetAddress getAddr() {
        // return this.node.getAddr(); //hardcode so that one is 4444, the other is 4445
        return this.addr;
    }

    public int getPort() {
        return this.port;
    }

    public State getState(){
        return state;
    }

    public boolean checkPort(int portQuery) {
        return (listenMap.containsKey(portQuery));
    }

    public int addListenSocket(TCPReceiveSock listenSock) {
        listenSock.logOutput("Adding listensock at port: " + listenSock.getPort());
        listenMap.put(listenSock.getPort(), listenSock);
        return 0;
    }

    public TCPSendSock addSendSocket(ConnID cID, TCPSendSock sendSock) {
        estMap.put(cID, sendSock);
        return (TCPSendSock) estMap.get(cID);
    }

    public TCPReceiveSock createEstSocket(ConnID cID) {
        ConnID reversecID = cID.reverse();
        if (estMap.containsKey(reversecID)){
            return null;
        } else { 
            estMap.put(reversecID, null);

        }
        
        int lowestPort = this.port;
        DatagramSocket checkPort = null;
        while (true){
            try{
                checkPort = new DatagramSocket(lowestPort);
            } catch (IOException e){
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
        this.dataQList.add(dataQ);
        this.commandQList.add(commandQ);
        TCPReceiveSock newSock = new TCPReceiveSock(this, newcID.srcAddr, newcID.srcPort, dataQ, commandQ);
        newSock.setCID(newcID);
        estMap.put(newcID, newSock);
        newSock.bind(newcID.srcPort);
        newSock.dataBuffer = new ReceiverByteBuffer(BUFFERSIZE);
        newSock.dsnBuffer = new ReceiverIntBuffer(BUFFERSIZE);
        newSock.setSocketTimeout(20);
        assert (estMap.containsKey(cID));
        
        // run the new sock as a separate thread
        Runnable receiveSockRunnable = (Runnable) newSock;
        Thread receiveSockThread= new Thread(receiveSockRunnable);
        receiveSockThread.start();
        
        return newSock;
    }


    public void removeReceiver(ConnID cID) {
        estMap.remove(cID);
        listenMap.get(cID.srcPort).removeEstSocket(cID);
        // if (listenMap.get(cID.srcPort).sockets.size() == 0){
        // listenMap.remove(cID.srcPort);
        // }
    }

    public void removeSender(ConnID cID) {
        estMap.remove(cID);
        // TOOD: consider adding logic to remove a listen socket upon clearing the
        // listenMap for a given listen socket
    }

    public void removeEstSocket(ConnID cID){
        ;
    }
    /*
     * Begin socket API
     */

    /**
     * Create a socket
     *
     * @return TCPSock the newly created socket, which is not yet bound to a local
     *         port
     */

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
        readToQ();
        // logOutput("===== After write =====");
        // buffer.getState();
        return bytesWrite;
    }

    /*
     * moves data into queue
     */
    public int computeFairness() {
        numMessages++;
        return numMessages % dataQList.size();
    }

    public int read(byte[] buf, int pos, int len){
        return 0;
    }


    public int readToQ() {
        // create new mapping
        int mappingSize = Math.min(sendBuffer.getUnsent(), MPTransport.MAX_PAYLOAD_SIZE);
        byte[] mappingPayload = new byte[mappingSize];
        int dsn = sendBuffer.getSendMax();
        sendBuffer.read(mappingPayload, 0, mappingSize);
        Message mapping = new Message(mappingPayload, dsn, mappingSize);

        // assign mapping
        dataQList.get(computeFairness()).add(mapping);

        return mappingSize;
    }



    public void logError(String output) {
        log(output, System.err);
    }

    public void logOutput(String output) {
        log(output, System.out);
    }

    public void log(String output, PrintStream stream) {
        if (this.verboseState == Verbose.FULL) {
            stream.println(this.addr + ": " + output);
        } else if (this.verboseState == Verbose.REPORT) {
            ;
        } else {
            ;
        }
    }

    // method to print detials about a socket
    public String toString(){
        return this.addr.toString() + ":" + Integer.toString(this.port);
    }


}

 /**
     * Read to the application up to len bytes from the message queues. The write function then breaks data written into packets to send on each subflow.
     * pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *         than len; on failure, -1
     */
    // public int read(byte[] buf, int pos, int len) {
    //     // logOutput("===== Before write =====");
    //     // buffer.getState();
    //     // peek all the blocks in the dataQlist and compare with DSN expected
    //     Iterator iterator = dataQList.iterator();
    //     Integer expectedDseq = this.buffer.getWrite();
    //     while(iterator.hasNext()) {
    //         ;
    //     }
    //     int bytesWrite = buffer.write(buf, pos, len);
    //     if (bytesWrite == -1) {
    //         return -1;
    //     }
    //     readToQ(); 
    //     // logOutput("===== After write  =====");
    //     // buffer.getState();
    //     return bytesWrite;
    // }
