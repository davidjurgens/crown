/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.POS;

import edu.stanford.nlp.util.CoreMap;

public interface LexicalEntry {

    /**
     * Returns the identifier associated with this sense of the word.
     *
     * @return a string that uniquely identifies this entry
     */
    String getId();

    /**
     * Returns the lemma for which the glosses describe a meaning.  Multi-word
     * lemmas have their spaces replaced with the underscore character.
     *
     * @return the lemma for this entry
     */
    String getLemma();

    /**
     * Returns the part of speech of the lemma.
     *
     * @return the part of speech
     */
    POS getPos();

    /**
     * Returns the heterogeneous map of annotations on top of this entry
     *
     * @return the annotations for the entry
     */
    CoreMap getAnnotations();
}
