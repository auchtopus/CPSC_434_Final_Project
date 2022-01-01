
import java.net.InetAddress;

public class Server {
    private MPSock mpSock;

    public Server() {
        try {
            this.mpSock = new MPSock(InetAddress.getByName("127.0.0.1"), 4445);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getPort() {
        return mpSock.getPort();
    }

    public InetAddress InetAddress() {
        return mpSock.getAddr();
    }

    public void run() {
        Boolean running = true;

        mpSock.bind(4445);
        mpSock.listen(4);
        mpSock.accept();
        while (running) {
            try {
                byte[] readBuf = new byte[500];
                int readLen = mpSock.read(readBuf, 0, readBuf.length);
                System.out.println("readlen:" + readLen);
                if (readLen > 0) {
                    for (int i = 0; i < readLen; i++) {
                        System.out.print((int) readBuf[i]);
                        System.out.print(",");
                    }

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
        System.out.println("Server listening on port " + server.getPort());
        server.run();
    }
}
