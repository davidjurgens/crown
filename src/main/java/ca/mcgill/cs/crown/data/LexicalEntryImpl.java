package ca.mcgill.cs.crown;

import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.POS;

import edu.stanford.nlp.util.ArrayCoreMap;
import edu.stanford.nlp.util.CoreMap;


/**
 *
 */
public class LexicalEntryImpl implements LexicalEntry {

    private final String lemma;

    private final String id;

    private final POS pos;

    private final CoreMap annotations;

    public LexicalEntryImpl(String lemma, String id, POS pos) {
        this.lemma = lemma;
        this.id = id;
        this.pos = pos;
        this.annotations = new ArrayCoreMap();
    }

    public boolean equals(Object o) {
        if (o instanceof LexicalEntry) {
            LexicalEntry e = (LexicalEntry)o;
            return pos.equals(e.getPos())
                && lemma.equals(e.getLemma())
                && id.equals(e.getId())
                && annotations.equals(e.getAnnotations());
        }
        return false;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override public String getLemma() {
        return lemma;
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override public POS getPos() {
        return pos;
    }

    /**
     * {@inheritDoc}
     */
    @Override public CoreMap getAnnotations() {
        return annotations;
    }

    public int hashCode() {
        return id.hashCode();
    }
    
    public String toString() {
        return String.format("%s (%s): %s", lemma, pos,
                             annotations.get(CrownAnnotations.Gloss.class));
    }
}
