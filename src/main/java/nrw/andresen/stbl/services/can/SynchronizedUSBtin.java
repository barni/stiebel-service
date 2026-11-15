package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import de.fischl.usbtin.FilterChain;
import de.fischl.usbtin.USBtin;
import de.fischl.usbtin.USBtinException;
import jssc.SerialPortEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * USBtin with a thread safe transmit FIFO. USBtinLib 1.2.0 uses the FIFO (a LinkedList) without synchronization from
 * two threads: send() from the scheduler adds a message, serialEvent() from the jssc event thread removes the
 * acknowledged one and sends the next. On 2026-09-25 14:43 both collided, getFirst() threw NoSuchElementException,
 * the exception ended the event thread and no CAN message was received until the USB restart 4 minutes later.
 */
public class SynchronizedUSBtin extends USBtin {

    private static final Logger logger = LoggerFactory.getLogger(SynchronizedUSBtin.class);

    private final CanStatistik statistik;
    // Message last kept as sent: after an error (BEL) the library writes the same message again
    private CANMessage zuletztUebertragen;

    public SynchronizedUSBtin() {
        this(null);
    }

    /**
     * Counts every sent message in statistik
     */
    public SynchronizedUSBtin(CanStatistik statistik) {
        this.statistik = statistik;
    }

    @Override
    public synchronized void send(CANMessage canmsg) throws USBtinException {
        super.send(canmsg);
        if (statistik != null) {
            statistik.gesendet(canmsg.getData().length);
        }
    }

    /**
     * Writes the first message of the FIFO to the adapter: right away if the FIFO was empty, otherwise after the
     * acknowledge of the message before, and again after an error. Only then it is kept for the CAN bus page with
     * the time it first went out, a repeat after an error only once: the adapter rejected the first try, the bus saw
     * the message once.
     */
    @Override
    protected void sendFirstTXFifoMessage() throws USBtinException {
        super.sendFirstTXFifoMessage();
        if (statistik != null && !fifoTX.isEmpty() && fifoTX.getFirst() != zuletztUebertragen) {
            zuletztUebertragen = fifoTX.getFirst();
            statistik.uebertragen(zuletztUebertragen.getId(), zuletztUebertragen.getData(), Instant.now());
        }
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
            if (statistik != null) {
                statistik.fehler("Fehler im Empfangs-Thread: " + e, Instant.now());
            }
        }
    }

    /**
     * Changes the filter of the open adapter: the filter registers can be written only with the CAN channel closed.
     * Closing removes the event listener, opening adds it again. Takes milliseconds, a message on the bus in this
     * time is lost. Requests not yet acknowledged are dropped, their acknowledge would never come.
     */
    public synchronized void filterSetzen(FilterChain[] filter, int bitrate) throws USBtinException {
        closeCANChannel();
        fifoTX.clear();
        setFilter(filter);
        openCANChannel(bitrate, OpenMode.ACTIVE);
    }

    /**
     * Serial port without connect, for tests
     */
    void setSerialPort(jssc.SerialPort port) {
        serialPort = port;
    }

    int getTransmitFifoSize() {
        return fifoTX.size();
    }
}
