/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.POS;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.ErasureUtils;


/**
 *
 */
public final class CrownAnnotations {

    public static class Gloss implements CoreAnnotation<String> {
        public Class<String> getType() {
            return String.class;
        }
    }

    public static class Glosses implements CoreAnnotation<Set<String>> {
        public Class<Set<String>> getType() {
            return ErasureUtils.uncheckedCast(Set.class);
        }
    }

    public static class RawGlosses
            implements CoreAnnotation<Map<String,String>> {
        
        public Class<Map<String,String>> getType() {
            return ErasureUtils.uncheckedCast(Map.class);
        }
    }

    public static class Relations implements CoreAnnotation<List<Relation>> {
        public Class<List<Relation>> getType() {
            return ErasureUtils.uncheckedCast(List.class);
        }
    }
    
} 
