package nrw.andresen.stbl.services.can;

import java.time.LocalDateTime;
import java.time.LocalTime;

public enum  ElsterType {

    et_default(0, String.class),
    et_dec_val(1, Integer.class),       // Auflösung: xx.x / auch neg. Werte sind möglich
    et_cent_val(2, Integer.class),      // x.xx
    et_mil_val(3, Integer.class),       // x.xxx
    et_byte(4, Byte.class),
    et_bool(5, Boolean.class),          // 0x0000 und 0x0001
    et_little_bool(6, Boolean.class),   // 0x0000 und 0x0100
    et_double_val(7, Double.class),
    et_triple_val(8, byte[].class),
    et_little_endian(9, byte[].class),
    et_betriebsart(10, byte[].class),
    et_zeit(11, LocalTime.class),
    et_datum(12, LocalDateTime.class),
    et_time_domain(13, LocalTime.class),
    et_dev_nr(14, byte[].class),
    et_err_nr(15, byte[].class),
    et_dev_id(16, byte[].class);

    public int id;
    public Class type;

    ElsterType(int id, Class type){
        this.type = type;
        this.id=id;
    }



}
