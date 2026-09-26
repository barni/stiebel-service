package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import de.fischl.usbtin.USBtin;
import de.fischl.usbtin.USBtinException;
import jssc.SerialPortEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * USBtin with a thread safe transmit FIFO. USBtinLib 1.2.0 uses the FIFO (a LinkedList) without synchronization from
 * two threads: send() from the scheduler adds a message, serialEvent() from the jssc event thread removes the
 * acknowledged one and sends the next. On 2026-09-25 14:43 both collided, getFirst() threw NoSuchElementException,
 * the exception ended the event thread and no CAN message was received until the USB restart 4 minutes later.
 */
public class SynchronizedUSBtin extends USBtin {

    private static final Logger logger = LoggerFactory.getLogger(SynchronizedUSBtin.class);

    @Override
    public synchronized void send(CANMessage canmsg) throws USBtinException {
        super.send(canmsg);
    }

    /**
     * An exception must not leave this method, it would end the event thread and nothing would be received anymore.
     * The FIFO is cleared, a message whose acknowledge is lost would otherwise block all further sending.
     */
    @Override
    public synchronized void serialEvent(SerialPortEvent event) {
        try {
            super.serialEvent(event);
        } catch (RuntimeException e) {
            logger.error("Error in the USBtin event thread, clearing the transmit FIFO", e);
            fifoTX.clear();
        }
    }

    int getTransmitFifoSize() {
        return fifoTX.size();
    }
}
