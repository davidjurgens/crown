/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/


package ca.mcgill.cs.crown.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Stopwords {

    /**
     * The list of stopwords to exclude from comparisons and attachments.
     */
    private static final String[] STOP_WORDS_ = new String[] {
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but", "by", "can't", "cannot", "could", "couldn't", "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during", "each", "few", "for", "from", "further", "had", "hadn't", "has", "hasn't", "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here", "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's", "its", "itself", "let's", "me", "more", "most", "mustn't", "my", "myself", "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought", "our", "ours", "ourselves", "out", "over", "own", "same", "shan't", "she", "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such", "than", "that", "that's", "the", "their", "theirs", "them", "themselves", "then", "there", "there's", "these", "they", "they'd", "they'll", "they're", "they've", "this", "those", "through", "to", "too", "under", "until", "up", "very", "was", "wasn't", "we", "we'd", "we'll", "we're", "we've", "were", "weren't", "what", "what's", "when", "when's", "where", "where's", "which", "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would", "wouldn't", "you", "you'd", "you'll", "you're", "you've", "your", "yours", "yourself", "yourselves", };

    /**
     * The set of stopwords to exclude from comparisons and attachments.
     */    
    public static final Set<String> STOP_WORDS =
        new HashSet<String>(Arrays.asList(STOP_WORDS_));

    private static final String[] HYPERNYM_WORDS_TO_AVOID_ = new String[] {
        "city", "person", "military", "physics", "plural", "town", "law", "mineral",
        "enzyme", "protein", "body", "act", "compound", "drug", "kind", "form", "state",
        "member", "type", "act", "condition", "computer science", "term", "word"
    };

    /**
     * The set of potentially-noisy words to exclude as hypernym candidates in
     * the absence of other features.  Specialized attachment procedures (e.g.,
     * pattern-based) are free to ignore this recommendation, but high-recall
     * procedures are encouraged to avoid attaching any new synsets to any of
     * these words' synsets.
     */    
    public static final Set<String> HYPERNYM_WORDS_TO_AVOID =
        new HashSet<String>(Arrays.asList(HYPERNYM_WORDS_TO_AVOID_));
    
}
