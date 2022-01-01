import java.util.concurrent.atomic.*;

public class SenderByteBuffer extends Buffer{
    byte[] buffer;
    volatile AtomicInteger sendBase;
    int sendMax; // write head; also the nextseqnum
    int wp;
    int size;
    // need 0 < wp - rp < size

    public SenderByteBuffer(int size) {
        buffer = new byte[size];
        sendBase = new AtomicInteger();
        sendMax = 0;
        wp = 0;
        this.size = size;
    }

    public int getSendBase(){
        return sendBase.intValue();
    }

    public int getSendMax(){
        return sendMax;
    }

    public int getWrite(){
        return wp;
    }
    public int getRead(){
        return -1;
    }


    public boolean canWrite() {
        return wp < sendBase.intValue() + this.size;
    }

    public boolean canRead() {
        return sendMax < wp; 
    }

    public int loc(int ptr) {
        return ptr % size;
    }
    
    public int getAvail(){
        return this.size - (wp - sendBase.intValue()); 
    }

    public int getSize(){
        return -1;
    }
    
    public int getUnsent(){
        return wp - sendMax;
    }

    public int getUnAcked(){
        return sendMax - sendBase.intValue();
    }

    public int write(byte[] srcBuf, int pos, int len) {
        // in from srcBuf
        int wrote = 0;

        while (pos < len && this.canWrite()) {
            buffer[loc(wp)] = srcBuf[pos];
            // parentSock.logOutput("BUF WRITING: wp: " + wp + " in val: " +srcBuf[pos] + " written: " + buffer[loc(wp)]);
            wrote++;
            wp++;
            pos++;
        }
        return wrote;
    }

    public int read(byte[] destBuf, int pos, int len) {
        // pos is in destbuf not this.buffer
        // out to destBuf
        int read = 0;
        while (read < len && this.canRead()) {

            destBuf[pos] = buffer[loc(sendMax)];
            // parentSock.logOutput("BUF READING: " + buffer[loc(sendMax)] + " to destbuf pos " + pos + " val: " + destBuf[pos]);
            sendMax++;
            pos++;
            read++;   
        }
        return read;
    }

    public synchronized int acknowledge(int newSendBase){
        if (newSendBase > sendMax || newSendBase < this.sendBase.get()){
            return -1;
        }
        int oldSendBase = this.sendBase.intValue();
        this.sendBase.set(newSendBase);
        return oldSendBase; 
    }

    public int reset(){
        sendMax = sendBase.intValue();
        return sendMax;
    } 

    public String toString(){
        return "sb:" + sendBase.get() + "|sm:" + sendMax + "|wp:" + wp + "cw:" + canWrite();
    }

}
