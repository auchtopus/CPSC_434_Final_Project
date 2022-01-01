import java.net.InetAddress;
import java.util.Random;
import java.util.function.LongUnaryOperator;

public class Client {
    MPSock mpSock;
    Random randGen;

    public Client() {
        randGen = new Random(42);
    }

    public void run(String[] args) {
        int targBytes;
        if (args.length > 0){
            targBytes = Integer.parseInt(args[0]);
        } else {
            targBytes = 10000;
        }

        int numPaths;
        if (args.length > 1){
            numPaths = Integer.parseInt(args[1]);
        } else {
            numPaths = 1;
        }

        int v;
        if (args.length > 2){
            v = Integer.parseInt(args[2]);
        } else {
            v = 0;
        }
        System.out.println("v:" + v);
        boolean added = false;
        try {
            mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4444, v);
            mpSock.connect(InetAddress.getByName("127.0.0.1"), 4445); // always hitting the welcome socket!
            for(int i = 0; i < numPaths - 1; i++){
                mpSock.addSubflow(InetAddress.getByName("10.0.0.1"), 8001 + i, InetAddress.getByName("127.0.0.1"), 4445);
            }

            int i = 0;
            while (i < targBytes) {
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
        client.run(args);
    }

}