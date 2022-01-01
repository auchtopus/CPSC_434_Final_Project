
import java.net.InetAddress;

public class Server {
    private MPSock mpSock;

    public Server() {

    }

    // public int getPort() {
    //     return mpSock.getPort();
    // }

    // public InetAddress InetAddress() {
    //     return mpSock.getAddr();
    // }

    public void run(String[] args) {
        Boolean running = true;

        int numBytes = 0;
        int targBytes;

        if (args.length > 0) {
            targBytes = Integer.parseInt(args[0]);
        } else {
            targBytes = 10000;
        }

        // int numPaths;
        // if (args.length > 2) {
        //     numPaths = Integer.parseInt(args[1]);
        // } else {
        //     numPaths = 1;
        // }

        int v;
        if (args.length > 1) {
            v = Integer.parseInt(args[1]);
        } else {
            v = 0;
        }


        try {
            this.mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4445, v);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        mpSock.bind(4445);
        mpSock.listen(4);
        mpSock.accept();

        System.out.println("Server listening on port " + this.mpSock.getPort());

        long startTime = System.currentTimeMillis();
        while (running) {
            try {
                byte[] readBuf = new byte[500];
                int readLen = mpSock.read(readBuf, 0, readBuf.length);
                // System.out.println("readlen:" + readLen);
                if (readLen > 0) {
                    for (int i = 0; i < readLen; i++) {
                        // System.out.print((int) readBuf[i]);
                        // System.out.print(",");
                        numBytes++;
                    }

                }
                if (numBytes == targBytes) {
                    long elapse = System.currentTimeMillis() - startTime;
                    System.out.println("time elapsed:" + elapse + "|"
                            + Double.toString((double) targBytes / (double) elapse * 1000) + "mbps" + "|flows:"
                            + mpSock.getNumConnections());
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        System.out.println("this close!");
        mpSock.close();
    }

    public static void main(String[] args) {
        Server server = new Server();

        server.run(args);
    }
}
