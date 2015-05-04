package ca.mcgill.cs.crown;


/**
 *
 */
public class RelationImpl implements Relation {

    private final RelationType type;

    private final String lemma;

    private final String sense;

    public RelationImpl(String lemma, String sense, RelationType type) {
        this.lemma = lemma;
        this.sense = sense;
        this.type = type;
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getTargetLemma() {
        return lemma;
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getTargetSense() {
        return sense;
    }

    /**
     * {@inheritDoc}
     */
    @Override public RelationType getType() {
        return type;
    }

    public String toString() {
        return String.format("Relation[%s (%s): %s]", lemma, sense, type);
    }
}
