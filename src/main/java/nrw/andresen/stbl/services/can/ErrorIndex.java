package nrw.andresen.stbl.services.can;

public enum ErrorIndex {
    NO_ERROR(0x0000, "Kein Fehler"),
    SCHETZ_KLEBT(0x0002, "Schuetz klebt"),
    ERR_HD_SENSOR(0x0003, "ERR HD-SENSOR"),
    HOCHDRUCK(0x0004, "Hochdruck"),
    VERDAMPFERFUEHLER(0x0005, "Verdampferfuehler"),
    RELAISTREIBER(0x0006, "Relaistreiber"),
    RELAISPEGEL(0x0007, "Relaispegel"),
    HEXSCHALTER(0x0008, "Hexschalter"),
    DREHZAHL_LUEFTER(0x0009, "Drehzahl Luefter"),
    LUEFTERTREIBER(0x000a, "Lueftertreiber"),
    RESET_BAUSTEIN(0x000b, "Reset Baustein"),
    ND(0x000c, "ND"),
    ND2(0x000d, "ND"),
    QUELLEN_MINTEMP(0x000e, "QUELLEN MINTEMP"),
    ABTAUEN(0x0010, "Abtauen"),
    ERR_T_HEI_IWS(0x0012, "ERR T-HEI IWS"),
    ERR_T_FRO_IWS(0x0017, "ERR T-FRO IWS"),
    NIEDERDRUCK(0x001a, "Niederdruck"),
    ERR_ND_DRUCK(0x001b, "ERR ND-DRUCK"),
    ERR_HD_DRUCK(0x001c, "ERR HD-DRUCK"),
    HD_SENSOR_MAX(0x001d, "HD-SENSOR-MAX"),
    HEISSGAS_MAX(0x001e, "HEISSGAS-MAX"),
    ERR_HD_SENSOR2(0x001f, "ERR HD-SENSOR"),
    EINFRIERSCHUTZ(0x0020, "Einfrierschutz"),
    KEINE_LEISTUNG(0x0021, "KEINE LEISTUNG");

    int index;
    String name;

    ErrorIndex(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public static ErrorIndex getErrorIndex(int index){
        for (ErrorIndex errorIndex : ErrorIndex.values()){
            if (errorIndex.index == index){
                return errorIndex;
            }
        }
        return null;
    }

}
