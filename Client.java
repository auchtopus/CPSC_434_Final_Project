import java.net.InetAddress;

public class Client {
    MPSock mpSock;

    public Client() {

    }

    public void run() {
        try {
            mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4444);
            mpSock.connect(InetAddress.getByName("127.0.0.1"), 4445);
            int bytesWritten = mpSock.write("hello".getBytes(), 0, "hello".getBytes().length);
            System.out.println("wrote packet: " + Integer.toString(bytesWritten));
            mpSock.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void main(String[] args) {
        Client client = new Client();
        client.run();
    }

}