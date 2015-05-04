package ca.mcgill.cs.crown;

import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.POS;

import edu.stanford.nlp.util.CoreMap;

public interface LexicalEntry {

    /**
     * Returns the identifier associated with this sense of the word.
     */
    String getId();

    /**
     * Returns the lemma for which the glosses describe a meaning.
     */
    String getLemma();

    /**
     * Returns the part of speech of the lemma.
     */
    POS getPos();

    /**
     * Returns the heterogeneous map of annotations on top of this entry
     */
    CoreMap getAnnotations();
}
