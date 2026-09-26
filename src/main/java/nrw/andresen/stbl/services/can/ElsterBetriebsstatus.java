package nrw.andresen.stbl.services.can;

public enum  ElsterBetriebsstatus {

    // Verdichter 1:        0x0001
    // Verdichter 2:        0x0002
    // Pufferladepumpe 1:   0x0040
    // Pufferladepumpe 2:   0x0080
    // DHC 1:               0x1000
    // DHC 2:               0x2000
    // Warmwasserladepumpe: 0x8000
    // EVU Sperre:          0x8000
    verdichter_1((short)0x0001,  "Verdichter 1"),
    verdichter_2((short)0x0002,  "Verdichter 2"),
    pufferladepumpe_1((short)0x0040,  "Pufferladepumpe 1"),
    pufferladepumpe_2((short)0x0080,  "Pufferladepumpe 2"),
    dhc_1((short)0x1000,  "DHC 1"),
    dhc_2((short)0x2000,  "DHC 2"),
    warmwasserladepumpe((short)0x4000,  "Warmwasserladepumpe"),
    evu_sperre((short)0x8000,  "EVU Sperre");


    public short id;
    public String name;

    ElsterBetriebsstatus(short id, String name){
        this.name = name;
        this.id=id;
    }

}
