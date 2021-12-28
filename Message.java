// Message class. Two constructors, one for each primary type of message

public  class Message {

    Integer dack;
    Integer dsn;
    byte[] data;
    Integer length;
    enum Command{
        ACCEPT, CLOSE
    }
    Command command;
    public Message(byte[] data, Integer dsn, Integer length){
        this.data = data;
        this.dsn = dsn;
        this.length = length;

    };

    public Message(Integer dack){
        this.dack = dack;
    }

    public Message(Command command){
        this.command = command;
    }
    

    public int getSize(){
        return length;
    }

    public Command getCommand(){
        return command;
    }
    

}
