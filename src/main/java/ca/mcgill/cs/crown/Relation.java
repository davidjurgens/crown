package ca.mcgill.cs.crown;


/**
 *
 */
public interface Relation {

    public enum RelationType {
        SYNONYM,
        
        ANTONYM,
        
        HYPERNYM,
        
        HYPONYM,
        
        HOLONYM,
        
        MERONYM,
        
        COORDINATE_TERM,

        TROPONYM,

        SEE_ALSO,
        
        DERIVED_TERM,
        
        ETYMOLOGICALLY_RELATED_TERM,

        DESCENDANT,     
        
        /** 
         * The target represents a word or phrase that co-occurs frequently with
         * $ARTICLE (e.g., "strong" co-occurs often with "tea" - "strong tea" is
         * usually used although "powerful tea" would essentially denote the
         * same meaning.
         */ 
        CHARACTERISTIC_WORD_COMBINATION;
    };

    
    String getTargetLemma();

    String getTargetSense();

    RelationType getType();

}
