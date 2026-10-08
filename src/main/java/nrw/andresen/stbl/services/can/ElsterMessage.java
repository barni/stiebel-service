package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * See http://juerg5524.ch/list_data.php
 *
 * 2Byte Elster ID  1Byte
 *
 * @author andr
 */
public class ElsterMessage  {
    private CANMessage internalMessage;
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private Logger logger = LoggerFactory.getLogger(ElsterMessage.class);
    private static ElsterTable elsterTable = new ElsterTable();
    private CANMessage message;
    private Instant timestamp;

    public ElsterMessage(CANMessage message){
        this.internalMessage = message;
        this.timestamp = Instant.now();
    }

    public ElsterMessage(int i, byte[] bytes) {
        internalMessage = new CANMessage(i, bytes);
        this.timestamp = Instant.now();
    }

    public ElsterMessage(int i, String bytes) {
        internalMessage = new CANMessage(i, hexStringToByteArray(bytes));
        this.timestamp = Instant.now();
    }

    public ElsterMessage(String msg) {
        internalMessage = new CANMessage(msg);
        this.timestamp = Instant.now();
    }


    public int getId(){
        return internalMessage.getId();
    }
    public String getIdAsString(){
        return String.format("%03x",internalMessage.getId());
    }

    public int getReceiverId(){
        if (internalMessage.getData().length < 2){
            return -1;
        }

        return convertReceiverID(internalMessage.getData());
    }

    public static byte[] convertReceiverID(Integer id){
        byte[] returnValue = new byte[2];


        int faktor = id / 0x80;
        faktor = faktor << 4;
        int rest = id % 0x80;


        returnValue[0] = ByteBuffer.allocate(4).putInt(faktor).array()[3];
        returnValue[1] = ByteBuffer.allocate(4).putInt(rest).array()[3];
        return returnValue;


    }
    
    /**
     * Creates a read request for an index in the 0xfa format
     */
    /**
     * Read request for the index; indices below 0xfa are sent in the short form with the index in the third byte,
     * all others in the long form after 0xfa
     */
    public static ElsterMessage readRequest(int senderId, int receiverId, short index){
        byte[] receiver = convertReceiverID(receiverId);
        byte[] data = index >= 0 && index < 0xfa
                ? new byte[]{(byte)(receiver[0] | 0x01), receiver[1], (byte)index, 0, 0, 0, 0}
                : new byte[]{(byte)(receiver[0] | 0x01), receiver[1], (byte)0xfa, (byte)(index >> 8), (byte)index, 0, 0};
        return new ElsterMessage(senderId, data);
    }

    public static int convertReceiverID(byte[] id){

        if (id.length < 2){
            return -1;
        }

        int returnValue = 8*(id[0] & 0xF0)+ ((id[1]& 0xff));

        return returnValue;
    }



    public String getReceiverIdAsString(){


        return String.format("%03x",getReceiverId());
    }

    public void setReceiverId(int id){
        internalMessage.setId(id);
    }

    public Boolean isRequest(){

        if ((internalMessage.getData()[0] & (byte)0x0F)==1){
            //2te Digit 2 heißt Anfrage
            return true;
        }else{
            //2te Digit 2 heißt Antwort
            return false;
        }

    }

    public Boolean isResponse(){
        //2te Digit 2 heißt Antwort
        return internalMessage.getData().length > 0 && (internalMessage.getData()[0] & 0x0F) == 2;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    private int getValuePosition(){
        //FA 01 D6 00 00 => 3 Request
        // 16 ==> 1 request
        // FA 01 D6 01 1B ==> 5 Response
        //16 01 17 ==> 3 response
        // 1  2  3  4   5  6  7
        // a0 79 0c 00 35 00 00

        // t70  07 92 00 fa fe 09 00 c9
        //         0  1   2  3  4  5  6
        //         92 00 FA FE 09 00 C9
        // t1007   31 00 16 00 00 00 00

        // 2 Digit 1 Anfrage
        // 2 Digit  2 Antwort
        // 2 byte 79 brodacast
        if ( isRequest() || internalMessage.getData().length < 5){
            return -1;
        }

        if (internalMessage.getData()[2] == (byte)0xfa){
            if ( isRequest() || internalMessage.getData().length < 7){
                return -1;
            }else{
                return 5;
            }

        }else {
            return 3;
        }

    }

    public ElsterIndex getElsterIndex(){
        if (internalMessage.getData().length > (7) ||
                internalMessage.getData().length < (3)){
            return ElsterIndex.UNKOWN_ELSTER_INDEX;
        }


        if (internalMessage.getData()[2] == (byte)0xfa) {
            if (internalMessage.getData().length < 5){
                return  ElsterIndex.UNKOWN_ELSTER_INDEX;
            }
            short index = ByteBuffer.wrap(new byte[]{internalMessage.getData()[3], internalMessage.getData()[4]}).getShort();

            return elsterTable.findByIndex(index);
        } else {
            short index = internalMessage.getData()[2];
            return elsterTable.findByIndex(index);

        }
    }

    public void setElsterIndex(ElsterIndex elsterIndex){
        byte[] data;
        if (elsterIndex.getIndex() < (byte)0x100)
        {
            //Len = 5;
            data = new byte[5];
            data[2] = (byte)elsterIndex.getIndex();
        } else {
            //Len = 7;
            data = new byte[7];
            data[2] = (byte) 0xfa;
            data[3] = (byte) (elsterIndex.getIndex() >> 8);
            data[4] = (byte) elsterIndex.getIndex();
        }
        internalMessage.setData(data);
    }

    /**
     * Raw 16 bit payload
     *
     * @return payload or null for requests and corrupt payloads
     */
    public Short getRawValue(){
        if (isRequest()){
            return null;
        }
        int valuePosition = getValuePosition();
        if (valuePosition == -1)
        {
            return null;
        }
        return ByteBuffer.wrap(new byte[]{internalMessage.getData()[valuePosition], internalMessage.getData()[valuePosition+1]}).getShort();
    }

    public String getValue(){
        if (isRequest()){
            return "NO_PAYLOAD";
        }
        Short rawValue = getRawValue();
        if (rawValue == null)
        {
            return "PAYLOAD_CORRUPT";
        }
        ElsterIndex elsterIndex = getElsterIndex();
        short s = rawValue;


        String value="";

        switch (elsterIndex.getType()){

            case et_zeit:
                value = String.format("%02d:%02d",  (s & 0xff), (s >> 8));
                break;
            case et_datum:
                value = String.format("%02d.%02d." , (s >> 8), (s & 0xff));
                break;
            case et_dev_nr:
                if (s >= 0x80)
                    value =  "--";
                else
                    value =  String.format("%d", (s + 1));
                break;
            case et_err_nr:
                // The table knows only the old codes, e.g. not 8116 INV H ROTORVEKTOR of the WPM3
                ErrorIndex errorIndex = ErrorIndex.getErrorIndex(s);
                value = errorIndex != null ? errorIndex.name : Fehlerliste.text(s);
                break;
            case et_dec_val:
                value =  String.format("%.1f",  (float)(s/10f));
                break;
            case et_mil_val:
                value =  String.format("%.3f",  (float)(s/1000f));
                break;
            case et_cent_val:
                value =  String.format("%.2f",  (float)(s/100f));
                break;
            case et_time_domain:
                if (s == 0x8000)
                    value =  "not used";
                else
                    value =  String.format("%02d:%02d-%02d:%02d", ((s >> 8)/4),
                            (15*((s>>8)*4)), ((s & 0xff) / 4), (15*(s % 4)));
                break;
            case et_little_endian:
                value =  String.format("%d", ((s >> 8) + 256*(s & 0xff)));
                break;
            case et_default:
            case et_dev_id:
            case et_bool:
            case et_byte:
            case et_double_val:
            case et_triple_val:
            case et_betriebsart:
            case et_little_bool:
            default:
                value =  String.format("%d", s);
        }

        return value;
    }

    public String toString(){
        return internalMessage.toString();
    }


    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public CANMessage getMessage() {
        return internalMessage;
    }
}
