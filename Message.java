// Message class. Two constructors, one for each primary type of message

public class Message {

    Integer dack;
    Integer dsn;
    byte[] data;
    Integer length;
    ConnID cID;

    enum Command {
        ACCEPT, CLOSE, UPDATE_WINDOW
    }
    boolean DATAFIN = false;

    Command command;

    public Message(byte[] data, Integer dsn, Integer length) {
        this.data = data;
        this.dsn = dsn;
        this.length = length;

    };


    public Message(byte[] data, Integer dsn, Integer length, ConnID cID) {
        this.data = data;
        this.dsn = dsn;
        this.length = length;
        this.cID = cID;

    };

    public Message(byte[] data, Integer dsn, Integer length, boolean finish){
        this.data = data;
        this.dsn = dsn;
        this.length = length;
        this.DATAFIN = finish;
    };

    public Message(Integer dack){
        this.dack = dack;
    }

    public Message(Command command) {
        this.command = command;
    }

    public Message(Command command, int dack){
        this.command = command;
        this.dack = dack;
    }

    public int getSize() {
        return length;
    }

    public boolean getDATAFIN(){
        return DATAFIN;
    }

    public Command getCommand(){
        return command;
    }

    public int getDSN() {
        return this.dsn;
    }

}
