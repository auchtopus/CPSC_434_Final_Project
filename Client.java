import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Enumeration;

public class Client extends Thread {
    MPSock mpSock;

    public Client() {


        try {
            mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4444);
            mpSock.connect(InetAddress.getByName("127.0.0.1"), 4445);
            mpSock.write("hello".getBytes(), 0, "hello".getBytes().length); // only buf is used for now. Change function
                                                                            // in tcpMan later
            System.out.println("wrote packet");

            // buf = "hello!".getBytes();
            mpSock.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        /*
         * tcpMan or MPSock will need to be able to give back it's various states (eg.
         * closed, connected, closing, ...) depending on what the state diagram is.
         * 
         * Client needs to be able to call MPSock.write() in a loop
         * 
         * needs MPSock.close()
         */
    }


    public static void main(String[] args){
        Client client = new Client();
    }

}