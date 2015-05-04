/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.util.Set;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.CoreMap;

import edu.ucla.sspace.util.Duple;

import ca.mcgill.cs.crown.CrownOperations.Reason;


/**
 * A {@link LexicalEntry} that has been annotated with information on how it
 * should be integrated into the CROWN dictionary during the build process.
 *
 * @see CrownOperations
 */
public interface AnnotatedLexicalEntry extends LexicalEntry {

    /**
     * Returns the operation that should be performed on this entry.
     *
     * @return a {@code CoreMap} where the keys are fields in {@link
     * CrownOperations} indicating which operations are to be performed.
     */
    CoreMap getOperations();

    /**
     * For operations that relate this entry to only one other synset, indicates
     * that the operations should be performed with respect to {@code
     * operationValue} for the specified {@code reason}.  This method is provided
     * as a shorthand convenience for adding new operations to the {@code
     * CoreMap} returned by {@link #getOperations()}.
     */
    <T> void setOp(Class<? extends CoreAnnotation<Duple<Reason,T>>> opType,
                   Reason reason,
                   T operationValue);

    /**
     * For operations that relate this entry to <em>multiple</em> other synsets,
     * adds a new operation of the specified type for the specified {@code
     * operationValue} with the specified {@code reason} (in addition to all
     * other operations of this type).
     */
    <T> void addOp(Class<? extends CoreAnnotation<Set<Duple<Reason,T>>>> opType,
                   Reason reasaon,
                   T operationValue);
    
}
