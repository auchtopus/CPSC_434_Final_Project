import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class Server extends Thread {
    private MPSock mpSock;

    public Server() {
        try {
            this.mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4445); //same problem with manager
            /*Need to be able to make MPSock.listen() to port specified
            The listen() function might very possibly need the UDP version socket.receive() to get messages it receives 
            
            Must again need MPSock states such as closed, listening, connected, closing
            
            Of course MPSock.read() to send data from socket to application code [application code will most likely echo back received messages for now]
            
            MPSock.close() needed*/
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getPort(){
        return mpSock.getPort();
    }

    public InetAddress getAddress(){
        return mpSock.getAddr();
    }

    public static void main(String[] args){
        Server server = new Server();
        server.start();
        System.out.println("Server listening on port " + server.getPort());
    }

    public void run() {
        Boolean running = true;

        mpSock.bind(4445);
        mpSock.listen(4);
        mpSock.accept();
        while (running) {
            try {
                byte[] writeBuf = new byte[500];
                mpSock.read(writeBuf, 0, writeBuf.length);
                for(int i = 0; i < writeBuf.length; i++){
                    System.out.print((char) writeBuf[i]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        mpSock.close();
    }
}
