// Message class. Two constructors, one for each primary type of message

public  class Message {

    Integer dack;
    Integer dsn;
    byte[] data;
    Integer length;
    public Message(byte[] data, Integer dsn, Integer length){
        this.data = data;
        this.dsn = dsn;
        this.length = length;

    };

    public Message(Integer dack){
        this.dack = dack;
    }
    

    public int getSize(){
        return length;
    }

}
