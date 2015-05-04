/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.similarity;


/**
 * An interface representing the abstraction of a similarity function for
 * comparing the meaning of two glosses.
 */
public interface SimilarityFunction {

    /**
     * Compares the two texts and returns their semantic similarity.
     */
    double compare(String text1, String text2);
}
