package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * TODO Beschreibung angeben
 *
 * @author andr
 */
public class StblMessageHandler {
    private Logger logger = LoggerFactory.getLogger(StblMessageHandler.class);


    /*
    public <T extends Object> T getValue(ElsterType elsterType, CANMessage canMessage,  Class<T> type){

    }
    */
    public String getValue(ElsterMessage canMessage) throws Exception{



        return canMessage.getValue();

    }


}
