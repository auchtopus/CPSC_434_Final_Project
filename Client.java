import java.net.InetAddress;
import java.util.Random;

public class Client {
    MPSock mpSock;
    Random randGen;

    public Client() {
        randGen = new Random(42);
    }

    public void run() {
        try {
            mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4444);
            mpSock.connect(InetAddress.getByName("127.0.0.1"), 4445);
            int i = 0;
            while (i < 10000) {
                int randSize = randGen.nextInt(400);
                byte[] message = new byte[randSize];
                for (int j = 0; j < randSize; j++) {
                    message[j] = (byte) ((i + j) % 128);
                }
                int bytesWritten = mpSock.write(message, 0, randSize);
                // System.out.println("wrote packet size : " + Integer.toString(bytesWritten));
                i += bytesWritten;

            }
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