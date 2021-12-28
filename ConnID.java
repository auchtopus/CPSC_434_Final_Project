import java.util.Objects;
import java.net.InetAddress;

class ConnID {

    // invariant: when sending, always use srcAddr/srcPort as src
    //

    InetAddress srcAddr;
    int srcPort;
    InetAddress destAddr;
    int destPort;

    public ConnID(InetAddress srcAddr, int srcPort, InetAddress destAddr, int destPort) {
        this.srcAddr = srcAddr;
        this.srcPort = srcPort;
        this.destAddr = destAddr;
        this.destPort = destPort;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final ConnID other = (ConnID) obj;
        return (this.srcAddr == other.srcAddr && this.srcPort == other.srcPort && this.destAddr == other.destAddr
                && this.destPort == other.destPort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.srcAddr.toString() + Integer.toString(srcPort) + this.destAddr.toString() + Integer.toString(destPort));
    }

    @Override
    public String toString(){
        return "sA:" + this.srcAddr.toString() + " sP:" + Integer.toString(this.srcPort) + " dA:" + this.destAddr.toString() + " dP:" + Integer.toString(this.destPort);
    }

    public ConnID reverse(){
        return new ConnID(this.destAddr, this.destPort, this.srcAddr, this.srcPort);
    }
}


