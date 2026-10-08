package nrw.andresen.stbl.can;

import nrw.andresen.stbl.services.can.ElsterIndex;
import nrw.andresen.stbl.services.can.ElsterMessage;
import org.junit.jupiter.api.Test;


import static nrw.andresen.stbl.services.CanBus.CAN_SENDER_ID;
import static nrw.andresen.stbl.services.can.ElsterTable.*;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * TODO Beschreibung angeben
 *
 * @author andr
 */
public class ElsterMessageTest {


    @Test
    public void testRequestNoPayloadCorrupt()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x480, new byte[]{(byte)0xfa, (byte)0x01, (byte)0xD6});
        String string = sut.getValue();
        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "UNKOWN_ELSTERINDEX_FFD6", elsterIndex.getName() );
        assertEquals( "PAYLOAD_CORRUPT", string );

    }
    @Test
    public void testRequestMSGcorrupt()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x480, new byte[]{(byte)0x16});
        String string = sut.getValue();
        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "UNKOWN_ELSTER_INDEX", elsterIndex.getName() );
        assertEquals( "PAYLOAD_CORRUPT", string );

    }


    @Test
    public void test_AUSSENTEMP ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t1807a0790c00350000");
        String string = sut.getValue();
        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "AUSSENTEMP", elsterIndex.getName() );
        assertEquals( "5,3", string );

    }

    @Test
    public void test_UNKOWN_FE09 ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t70079200fafe0900c9");
        String string = sut.getValue();
        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "201", string );
        assertEquals( false, sut.isRequest() );
        assertEquals( "UNKOWN_ELSTERINDEX_FE09", elsterIndex.getName() );

    }


    @Test
    public void test_Request_RUECKLAUFISTTEMP ()throws Exception{
        // t1007 31 00 16 00 00 00 00
        ElsterMessage sut = new ElsterMessage("t100731001600000000");

        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "180", sut.getReceiverIdAsString() );
        assertEquals( "100", sut.getIdAsString() );
        assertEquals( "RUECKLAUFISTTEMP", elsterIndex.getName() );
        assertEquals( true, sut.isRequest() );
    }


    @Test
    public void test_Response_RUECKLAUFISTTEMP ()throws Exception{
        // t1007 31 00 16 00 00 00 00
        ElsterMessage sut = new ElsterMessage("t180722001601090000");

        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( "100", sut.getReceiverIdAsString() );
        assertEquals( "180", sut.getIdAsString() );
        assertEquals( "26,5", sut.getValue() );
        assertEquals( "RUECKLAUFISTTEMP", elsterIndex.getName() );
        assertEquals( false, sut.isRequest() );
    }

    @Test
    public void test_Request_EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH()throws Exception{

        ElsterMessage sut = new ElsterMessage("t1007a114fa09210000");

        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( true, sut.isRequest() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH", elsterIndex.getName() );

    }

    @Test
    public void test_Response_EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH()throws Exception{
        // t5147 22 00 fa 09 21 00 02
        ElsterMessage sut = new ElsterMessage("t51472200fa09210002");

        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( false, sut.isRequest() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH", elsterIndex.getName() );
        assertEquals( "2", sut.getValue() );
    }


    @Test
    public void test_Request_EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH()throws Exception{

        ElsterMessage sut = new ElsterMessage("t1007a114fa09200000");

        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( true, sut.isRequest() );
        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "100", sut.getIdAsString() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH", elsterIndex.getName() );

    }

    @Test
    public void test_Response_EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH()throws Exception{
        // t5147 22 00 fa 09 21 00 02
        ElsterMessage sut = new ElsterMessage("t51472200fa092000a1");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "100", sut.getReceiverIdAsString() );
        assertEquals( "514", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH", elsterIndex.getName() );
        assertEquals( "161", sut.getValue() );
    }


    @Test
    public void test_Response_MODE_MULTIFUNKTIONSAUSGANG()throws Exception{
        ElsterMessage sut = new ElsterMessage("t70079200fafdf40134");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "700", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "MODE_MULTIFUNKTIONSAUSGANG", elsterIndex.getName() );
        assertEquals( "308", sut.getValue() );
    }

    @Test
    public void test_Request_MODE_MULTIFUNKTIONSAUSGANG()throws Exception{
        ElsterMessage sut = new ElsterMessage("t4807e100fafdf30000");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "700", sut.getReceiverIdAsString() );
        assertEquals( "480", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "STATUS_MULTIFUNKTIONSAUSGANG", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_Request_UNKOWN_ELSTERINDEX_07A8()throws Exception{
        ElsterMessage sut = new ElsterMessage("t50079200fa07a80008");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "500", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "UNKOWN_ELSTERINDEX_07A8", elsterIndex.getName() );
        assertEquals( "8", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "a114fa09200000");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "a114fa09210000");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Request_WAERMEERTRAG_HEIZ_SUM_KWH()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "a114fa09300000");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "WAERMEERTRAG_HEIZ_SUM_KWH", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_WAERMEERTRAG_HEIZ_SUM_MWH()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "a114fa09310000");

        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "WAERMEERTRAG_HEIZ_SUM_MWH", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Response_BETRIEBS_STATUS()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x514, "d201fa01760041");
        ElsterIndex elsterIndex = sut.getElsterIndex();
        assertEquals( CAN_SENDER_ID, sut.getReceiverId() );

        assertEquals( "681", sut.getReceiverIdAsString() );
        assertEquals( "514", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "BETRIEBS_STATUS", elsterIndex.getName() );
        assertEquals( "65", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_BETRIEBS_STATUS()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "3100fa01760000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "180", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "BETRIEBS_STATUS", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_Vorlaufist()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x680, "31000f00000000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "180", sut.getReceiverIdAsString() );
        assertEquals( "680", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "VORLAUFISTTEMP", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Response_Vorlaufist()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x500, "9200fa01d6013f");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "500", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "WPVORLAUFIST", elsterIndex.getName() );
        assertEquals( "31,9", sut.getValue() );
    }
    // t5147d2000e80000000 SPEICHERISTTEMP Payload: -3276,8
    // t5147d2000f80000000 VORLAUFISTTEMP Payload: -3276,8


    @Test
    public void test_SEND_test_Response_Ruecklaufist()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x514, "220016012a0000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "100", sut.getReceiverIdAsString() );
        assertEquals( "514", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "RUECKLAUFISTTEMP", elsterIndex.getName() );
        assertEquals( "29,8", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Request_Ruecklaufist()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x100, "a1141600000000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "514", sut.getReceiverIdAsString() );
        assertEquals( "100", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "RUECKLAUFISTTEMP", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }

    @Test
    public void test_SEND_test_Request_StromKompressor()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x480, "a100fa06b20000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "500", sut.getReceiverIdAsString() );
        assertEquals( "480", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "TEST_OBJEKT_113", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Response_StromKompressor()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x500, "9200fa06b20030");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "500", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "TEST_OBJEKT_113", elsterIndex.getName() );
        assertEquals( "4,8", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Request_SpanungInverter()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x480, "a100fa06b10000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "500", sut.getReceiverIdAsString() );
        assertEquals( "480", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "TEST_OBJEKT_112", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }


    @Test
    public void test_SEND_test_Respone_SpanungInverter()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x500, "9200fa06b1086e");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "500", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "TEST_OBJEKT_112", elsterIndex.getName() );
        assertEquals( "215,8", sut.getValue() );
    }
    /*
    @Test
    public void test_SEND_test_Respone_Wasserdruck()throws Exception{
        //t5007d201fa001f8000
       // t5007d201fa001f8000
       // ElsterMessage sut = new ElsterMessage(0x500, "9200fa06b1086e");
        ElsterMessage sut = new ElsterMessage(0x500, "d201fa001f8000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "480", sut.getReceiverIdAsString() );
        assertEquals( "500", sut.getIdAsString() );
        assertEquals( false, sut.isRequest() );
        assertEquals( "TEST_OBJEKT_112", elsterIndex.getName() );
        assertEquals( "215,8", sut.getValue() );
    }
*/
    @Test
    public void test_SEND_test_Request_Hochdruck()throws Exception{
        ElsterMessage sut = new ElsterMessage(0x480, "a100fa07a60000");
        ElsterIndex elsterIndex = sut.getElsterIndex();

        assertEquals( "500", sut.getReceiverIdAsString() );
        assertEquals( "480", sut.getIdAsString() );
        assertEquals( true, sut.isRequest() );
        assertEquals( "ANZEIGE_HOCHDRUCK", elsterIndex.getName() );
        assertEquals( "NO_PAYLOAD", sut.getValue() );
    }
    @Test
    public void test_convertReceiverID255()throws Exception{
        byte[] bytes = ElsterMessage.convertReceiverID(255);

        assertEquals( 0x10, bytes[0] );
        assertEquals( (byte)0x7F, bytes[1] );

        int id = ElsterMessage.convertReceiverID(bytes);
        assertEquals( 255, id );
    }


    @Test
    public void test_convertReceiverID256()throws Exception{
        byte[] bytes = ElsterMessage.convertReceiverID(256);

        assertEquals( (byte)0x20, bytes[0] );
        assertEquals( (byte)0x00, bytes[1] );

        int id = ElsterMessage.convertReceiverID(bytes);
        assertEquals( 256, id );

    }
    @Test
    public void test_convertReceiverID(){
        byte[] input = {(byte)0xD2, (byte)0x01};
        int i = ElsterMessage.convertReceiverID(input);
        byte[] bytes = ElsterMessage.convertReceiverID(i);



        assertEquals( (byte)0xD0, bytes[0] );
        assertEquals( (byte)0x01, bytes[1] );
    }

// CanIDs
    // 8*(X0 & f0) + (YZ & 0f)
    // t514 7 22 00 ==> XXX ==> 514
    // t100 7 a1 14 ==>  WPM ==> 100
    // t180 7 22 00 ==> XXX  ==> 180
    // t700 7 92 00 ==> XXXXX ==> 700
    // t480 7 e1 00 ==> XXXXX ==> 480
    // t500 7 92 00 ==> XXXX ==> 500

    /*
  000 - direkt
  180 - Kessel
  280 - atez
  300, 301 ... - Bedienmodule (bei mir 301, 302 und 303)
  400 - RaumfernfÃ¼hler
  480 - Manager
  500 - Heizmodul
  580 - Buskoppler
  600, 601 ... -  Mischermodule (bei mir 601, 602, 603)
  680 - PC (ComfortSoft)
  700 - Fremdgerät
  780 - DCF-Modul
     */


    @Test
    public void test_RawValue_BETRIEBS_STATUS ()throws Exception{
        // DHC 2 + Verdichter 1, decimal payload must not be parsed as hex
        ElsterMessage sut = new ElsterMessage("t18072200fa01762001");
        assertEquals( "BETRIEBS_STATUS", sut.getElsterIndex().getName() );
        assertEquals( "8193", sut.getValue() );
        assertEquals( (short)0x2001, sut.getRawValue() );
        assertEquals( true, sut.isResponse() );
    }

    @Test
    public void test_RawValue_BETRIEBS_STATUS_EVU_Sperre ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t18072200fa01769001");
        assertEquals( (short)0x9001, sut.getRawValue() );
        assertEquals( (short)0x8000, (short)(sut.getRawValue() & (short)0x8000) );
    }

    @Test
    public void test_RawValue_not_available ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t5007d201fa06748000");
        assertEquals( (short)0x8000, sut.getRawValue() );
        assertEquals( true, sut.isResponse() );
    }

    @Test
    public void test_RawValue_Request ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t100731001600000000");
        assertEquals( null, sut.getRawValue() );
        assertEquals( false, sut.isResponse() );
    }


    @Test
    public void test_readRequest_matches_existing_requests ()throws Exception{
        assertEquals( "a100fa063f0000", ElsterMessage.bytesToHex(
                ElsterMessage.readRequest(CAN_SENDER_ID, 0x500, (short)0x063f).getMessage().getData()).toLowerCase() );
        assertEquals( "3100fa01760000", ElsterMessage.bytesToHex(
                ElsterMessage.readRequest(CAN_SENDER_ID, 0x180, (short)0x0176).getMessage().getData()).toLowerCase() );
        assertEquals( "a114fa09200000", ElsterMessage.bytesToHex(
                ElsterMessage.readRequest(CAN_SENDER_ID, 0x514, (short)0x0920).getMessage().getData()).toLowerCase() );
        ElsterMessage manager = ElsterMessage.readRequest(CAN_SENDER_ID, 0x480, (short)0x501d);
        assertEquals( "9100fa501d0000", ElsterMessage.bytesToHex(manager.getMessage().getData()).toLowerCase() );
        assertEquals( "480", manager.getReceiverIdAsString() );
        assertEquals( true, manager.isRequest() );
    }

    @Test
    public void test_RawValue_scaled_index_above_0x4000 ()throws Exception{
        ElsterMessage sut = new ElsterMessage("t18072200fa4f4704d2");
        assertEquals( (short)0x4f47, sut.getElsterIndex().getIndex() );
        assertEquals( (short)1234, sut.getRawValue() );
    }


    @Test
    public void testWpl17Names() {
        // Answers from the log on 2026-09-25 10:13
        assertWpl17("t5007d201fa063f013a", "VERDICHTER_EINTRITTSTEMP", "31,4");
        assertWpl17("t5007d201fa07a9010a", "VERDAMPFERTEMP", "26,6");
        assertWpl17("t5007d201fa0a3901bb", "OELSUMPFTEMP", "44,3");
        assertWpl17("t5147d201fac1e60192", "UMGEBUNGSTEMPERATUR_INVERTER", "40,2");
        assertWpl17("t5147d201fac2830000", "ISTDREHZAHL_LUEFTER_HZ", "0");
    }

    private void assertWpl17(String frame, String name, String value) {
        ElsterMessage sut = new ElsterMessage(Integer.parseInt(frame.substring(1, 4), 16), frame.substring(5));
        assertEquals(name, sut.getElsterIndex().getName());
        assertEquals(value, sut.getValue());
    }

    private static String anfrage(int knoten, short index) {
        return ElsterMessage.bytesToHex(ElsterMessage.readRequest(CAN_SENDER_ID, knoten, index).getMessage().getData())
                .toLowerCase();
    }

    /**
     * The requests built from the table give the same bytes as the fixed hex strings used before
     */
    @Test
    public void test_readRequest_wie_feste_anfragen() {
        assertEquals("3100fa01760000", anfrage(0x180, BETRIEBS_STATUS));
        assertEquals("3100fa06740000", anfrage(0x180, ANZEIGE_HEIZUNGSDRUCK));
        assertEquals("a114fa09200000", anfrage(0x514, EL_AUFNAHMELEISTUNG_HEIZ_SUM_KWH));
        assertEquals("a114fa09210000", anfrage(0x514, EL_AUFNAHMELEISTUNG_HEIZ_SUM_MWH));
        assertEquals("a114fa091e0000", anfrage(0x514, EL_AUFNAHMELEISTUNG_HEIZ_TAG_WH));
        assertEquals("a114fa091f0000", anfrage(0x514, EL_AUFNAHMELEISTUNG_HEIZ_TAG_KWH));
        assertEquals("a114fa09300000", anfrage(0x514, WAERMEERTRAG_HEIZ_SUM_KWH));
        assertEquals("a114fa09310000", anfrage(0x514, WAERMEERTRAG_HEIZ_SUM_MWH));
        assertEquals("a114fa092e0000", anfrage(0x514, WAERMEERTRAG_HEIZ_TAG_WH));
        assertEquals("a114fa092f0000", anfrage(0x514, WAERMEERTRAG_HEIZ_TAG_KWH));
        assertEquals("a114fa09260000", anfrage(0x514, WAERMEERTRAG_2WE_HEIZ_TAG_WH));
        assertEquals("a114fa09270000", anfrage(0x514, WAERMEERTRAG_2WE_HEIZ_TAG_KWH));
        assertEquals("a114fa09280000", anfrage(0x514, WAERMEERTRAG_2WE_HEIZ_SUM_KWH));
        assertEquals("a114fa09290000", anfrage(0x514, WAERMEERTRAG_2WE_HEIZ_SUM_MWH));
        assertEquals("a100fa02590000", anfrage(0x500, LAUFZEIT_DHC1));
        assertEquals("a100fa025a0000", anfrage(0x500, LAUFZEIT_DHC2));
        assertEquals("a100fa08050000", anfrage(0x500, LAUFZEIT_DHC12));
        assertEquals("a100fa06b20000", anfrage(0x500, TEST_OBJEKT_113_STROM_INVERTER));
        assertEquals("a114fa01d60000", anfrage(0x514, WPVORLAUFIST));
        assertEquals("a1141600000000", anfrage(0x514, RUECKLAUFISTTEMP));
        assertEquals("a100fa06b10000", anfrage(0x500, TEST_OBJEKT_112_SPANNUNG_INVERTER));
        assertEquals("a100fa07a60000", anfrage(0x500, ANZEIGE_HOCHDRUCK));
        assertEquals("a100fa07a70000", anfrage(0x500, ANZEIGE_NIEDERDRUCK));
        assertEquals("a1000c00000000", anfrage(0x500, AUSSENTEMP));
        assertEquals("a100fa02650000", anfrage(0x500, HEISSGAS_TEMP));
        assertEquals("a100fa063d0000", anfrage(0x500, VERDICHTER_DREHZAHL));
        assertEquals("a100fa063f0000", anfrage(0x500, VERDICHTER_EINTRITTSTEMP));
    }

    /**
     * Code field of the fault list with a code the Elster table does not know (8116 = 0x1fb4)
     */
    @Test
    public void test_fehlercode_unbekannt() {
        ElsterMessage sut = new ElsterMessage(0x180, new byte[]{(byte) 0xd2, (byte) 0x01, (byte) 0xfa, (byte) 0x0b,
                (byte) 0x06, (byte) 0x1f, (byte) 0xb4});
        assertEquals("FEHLERFELD_6", sut.getElsterIndex().getName());
        assertEquals("INV H ROTORVEKTOR", sut.getValue());
    }
}
