package nrw.andresen.stbl.services.can;

import de.fischl.usbtin.CANMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SynchronizedUSBtinTest {

    @Test
    public void testSendAndSerialEventAreSynchronized() throws Exception {
        assertTrue(Modifier.isSynchronized(SynchronizedUSBtin.class.getMethod("send", CANMessage.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(SynchronizedUSBtin.class
                .getMethod("serialEvent", jssc.SerialPortEvent.class).getModifiers()));
    }

    @Test
    public void testSerialEventKeepsEventThreadAlive() {
        SynchronizedUSBtin usbtin = new SynchronizedUSBtin();
        // Not connected: the message stays in the FIFO, writing to the missing port fails
        assertThrows(NullPointerException.class, () -> usbtin.send(new CANMessage(0x680, new byte[]{0x31, 0x00})));
        assertEquals(1, usbtin.getTransmitFifoSize());
        // A failing event must not throw, the FIFO is cleared
        assertDoesNotThrow(() -> usbtin.serialEvent(null));
        assertEquals(0, usbtin.getTransmitFifoSize());
    }

    @Test
    public void testRahmenErstBeimSenden() throws Exception {
        CanStatistik statistik = new CanStatistik();
        SynchronizedUSBtin usbtin = new SynchronizedUSBtin(statistik);
        usbtin.setSerialPort(new jssc.SerialPort("test") {
            @Override
            public boolean writeBytes(byte[] buffer) {
                return true;
            }
        });
        usbtin.send(new CANMessage(0x681, new byte[]{(byte) 0xA1, 0x00, (byte) 0xFA, 0x01, (byte) 0xD6, 0x00, 0x00}));
        usbtin.send(new CANMessage(0x681, new byte[]{(byte) 0xA1, 0x00, 0x16, 0x00, 0x00, 0x00, 0x00}));
        // Both are counted, but only the first went out, the second waits for the acknowledge
        assertEquals(2, usbtin.getTransmitFifoSize());
        assertEquals(1, statistik.getRahmen().size());
        assertEquals((byte) 0xD6, statistik.getRahmen().get(0).daten()[4]);
    }
}
