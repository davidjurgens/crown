/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;


/**
 * The interface for an object that indicates a relation between a synset and
 * lemma or sense in an external resource (such as Wiktionary).
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
