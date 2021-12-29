import java.util.concurrent.atomic.*;
/*
Invariants:
- buf[loc(wp)] = 1st open to write to
    - unless wp = rp + size at which point buf is full
- buf[loc(rp)] = 1st unread
    - unless  wp == rp at which point buf is empty and there is nothign to read


*/

public class ReceiverIntBuffer extends Buffer{
    int[] buffer;
    int rp;
    AtomicInteger wp;
    int size;
    // need 0 < wp - rp < size

    public ReceiverIntBuffer(int size) {
        buffer = new int[size];
        rp = 0;
        wp = new AtomicInteger();
        this.size = size;
    }

    public int getWrite() {
        // returns ABSOLUTE write pointer index
        return wp.intValue();
    }

    public int getSize() {
        // returns size of data in buffer
        return wp.intValue() - rp;
    }

    public int getRead() {
        // returns ABSOLUTE read pointer
        return rp;
    }


    public int getUnsent() {
        return -1;
    }

    public int getUnAcked() {
        return -1;
    }

    public boolean canWrite() {
        // boolean of whether therre's space (flow control)
        return wp.intValue() < rp + this.size && wp.intValue() >= rp;
    }

    public boolean canRead() {
        // whether there's data to read
        return rp < wp.intValue();
    }

    public int loc(int ptr) {
        // modulo to convert ABSOLUTE to indexes
        return ptr % size;
    }

    public int getAvail() {
        // return available size
        return this.size - (wp.intValue() - rp);
    }

    public int write(int[] srcBuf, int pos, int len) {
        // in from srcBuf
        // Write INTO buffer
        int wrote = 0;

        while (wrote < len && this.canWrite()) {

            // if (wp == size){

            buffer[loc(wp.intValue())] = srcBuf[pos];
            // + wp);
            // }
            wrote++;
            wp.incrementAndGet();
            pos++;
        }
        return wrote;
    }

    public int read(int[] destBuf, int pos, int len) {
        // out to destBuf
        // read OUT 
        int read = 0;
        while (read < len && this.canRead()) {
            // parentSock.logOutput("BUF READING: " + buffer[loc(rp)]);

            destBuf[pos] = buffer[loc(rp)];
            // parentSock.logOutput("BUF READING: " + buffer[loc(rp)] + " to destbuf pos " +
            // pos + " val: " + destBuf[pos]);
            rp++;
            pos++;
            read++;
        }
        return read;
    }

    public int reset() {
        // ignore
        return -1;
    }

    public int acknowledge(int len) {
        // ignore
        return -1;
    }

    public int getSendBase() {
        return -1;
    }

    public int getSendMax() {
        return -1;
    }

}
