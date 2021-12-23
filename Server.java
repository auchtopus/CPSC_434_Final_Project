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

                



                // byte[] buf = byte[500];
                // DatagramPacket packet = new DatagramPacket(buf, buf.length);
                // mpSock.receive(packet);
                // MPTransport transport = MPTransport.unpack(packet.getData());
                // // String str = new String(packet.getData(), StandardCharsets.UTF_8);
                // // System.out.println(str);
                // // System.out.println(packet.getData());
                // System.out.println(transport.getSrcPort());
                // System.out.println(transport.getDestPort());
                // System.out.println(transport.getType());
                // System.out.println(transport.getMpType());
                // System.out.println(transport.getWindow());
                // System.out.println(transport.getSeqNum());
                // System.out.println(transport.getDSeqNum());
                // String received = new String(transport.getPayload(), 0, transport.getPayload().length);
                // System.out.println(received);
                // InetAddress address = packet.getAddress();
                // int port = packet.getPort();
                // packet = new DatagramPacket(buf, buf.length, address, port);
                // String received = new String(packet.getData(), 0, packet.getLength());

                // if (received.equals("end")) {
                //     running = false;
                //     continue;
                // }
                // socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        mpSock.close();
    }
}
