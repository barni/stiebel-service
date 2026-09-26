package nrw.andresen.stbl.services.can;

public class ElsterIndex {
    public static ElsterIndex UNKOWN_ELSTER_INDEX = new ElsterIndex("UNKOWN_ELSTER_INDEX",
            (short)0x0000, ElsterType.et_default);
    private String name;
    private short index;
    private ElsterType type;

    public ElsterIndex(String name, short index, ElsterType type) {
        this.name = name;
        this.index = index;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public short getIndex() {
        return index;
    }

    public ElsterType getType() {
        return type;
    }
}
