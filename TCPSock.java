import java.io.PrintStream;
import java.net.InetAddress;

public abstract class TCPSock {
    public enum State {
        CLOSED, LISTEN, SYN_SENT, ESTABLISHED, SHUTDOWN, BUFFER_CLEAR, FIN_SENT, TIME_WAIT // close requested, FIN not
    }

    public enum Verbose {
        SILENT, REPORT, FULL
    }

    public Verbose verboseState;

    public InetAddress addr;
    public int port;   

    public abstract void close();

    public abstract State getState();

    public abstract void removeEstSocket(ConnID cID);
}
