/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.util.Iterator;
import java.util.Map;

import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;


public interface EnrichmentOperation extends Iterable<Map.Entry<ISynset,IPointer>> {

    /**
     * Returns an iterator over all the synsets to which the target should be related.
     */
    Iterator<Map.Entry<ISynset,IPointer>> iterator();

    /**
     * Returns a mapping of the synsets to which the target should be related
     * and how these synsets should be related.
     */
    Map<ISynset,IPointer> relations();

    /**
     * Returns an identifier indicating why this operation would be performed.
     * This identifier is combined with the type of the EnrichmentProcedure that
     * generated it to track the sources (reasons) of all enrichments.
     */
    String reasonId();
}
