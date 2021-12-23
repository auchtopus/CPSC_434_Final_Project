import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.util.Enumeration;




public class Client extends Thread{
    MPSock mpSock;

    public Client() {
        // try {
        //     address = InetAddress.getByName("127.0.0.1");
        //     System.out.println(address);
        //     Transport transport = new Transport(4444, 4445, 0, 1, 39, 20, 40, "hello!".getBytes());
        //     buf = transport.pack();
            
        //     // buf = "hello!".getBytes();
        //     DatagramPacket packet 
        //     = new DatagramPacket(buf, buf.length, address, 4445);
        //     socket.send(packet);
        //     System.out.println("send packet");
        // } catch (Exception e){
        //     e.printStackTrace();
        // }

        try {
            mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4444);
            //MPTransport transport = new MPTransport(4444, 4445, 0, 1, 39, 20, 40, "hello!".getBytes());
            //byte[] buf = transport.pack();
            mpSock.connect(InetAddress.getByName("127.0.0.1"), 4445);
            mpSock.write("hello".getBytes(), 0, "hello".getBytes().length); //only buf is used for now. Change function in tcpMan later
            System.out.println("wrote packet");
            
            // buf = "hello!".getBytes();
            mpSock.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        /*tcpMan or MPSock will need to be able to give back it's various states (eg. closed, connected, closing, ...) depending on what the state diagram is.
        
        Client needs to be able to call MPSock.write() in a loop
        
        needs MPSock.close()*/ 
    }

    public static void main(String[] args){
        Client client = new Client();
    }

    // public void getAddrs() throws Exception{    
    //     System.out.println("getting addres");
    //     Enumeration<NetworkInterface> interfaceList = NetworkInterface.getNetworkInterfaces();
    //     while (interfaceList.hasMoreElements()){
    //         NetworkInterface iface = interfaceList.nextElement();   
    //         System.out.println(iface.getName());
    //         for (InterfaceAddress addr: iface.getInterfaceAddresses()){
    //             System.out.println(addr.getAddress());
    //         }
    //     }
    //     System.out.println(InetAddress.getLocalHost());
    // }

    // public String sendEcho(String msg) throws Exception{
    //     buf = msg.getBytes();
    //     DatagramPacket packet 
    //       = new DatagramPacket(buf, buf.length, address, 4445);
    //     socket.send(packet);
    //     System.out.println("send packet");
    //     packet = new DatagramPacket(buf, buf.length);
    //     socket.receive(packet);
    //     String received = new String(
    //       packet.getData(), 0, packet.getLength());
    //     return received;
    // }

    // public void close() {
    //     socket.close();
    // }
}