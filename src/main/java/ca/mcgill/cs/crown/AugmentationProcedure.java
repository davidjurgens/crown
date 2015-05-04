package ca.mcgill.cs.crown;

import edu.mit.jwi.IDictionary;

public interface AugmentationProcedure {

    /**
     * Examines the information associated with the entry and adds additional
     * semantic links between the entry and other entries (such as
     * derivationally-reated links or topical links) without changing the
     * taxonomic structure integrating the entry within the ontology.  This
     * method is typically called only after the entry has been successfully
     * integrated.
     */
    void augment(AnnotatedLexicalEntry e);

    /**
     * Sets the dictionary used by this procedure for determining where to
     * attach new entries.
     */
    void setDictionary(IDictionary dictionary);
}
