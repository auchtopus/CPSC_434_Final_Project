public abstract class TCPSock {
    enum State {
        CLOSED, LISTEN, SYN_SENT, ESTABLISHED, SHUTDOWN, BUFFER_CLEAR, FIN_SENT, TIME_WAIT // close requested, FIN not
    }

    public abstract void close();

    public abstract State getState();

    public abstract void removeEstSocket(ConnID cID);

}