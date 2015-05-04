package ca.mcgill.cs.crown.util;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import edu.ucla.sspace.util.*;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;

import edu.mit.jwi.morph.IStemmer;
import edu.mit.jwi.morph.SimpleStemmer;


public class GlossUtils {

    private static final Pattern TRAILING_PUNCT = Pattern.compile("([\\p{Punct}]+)$");
    
    /**
     * Given a phrase that has been extracted as a potential hypernym candidate
     * for a noun, search the gloss to find other possiblities for the hypernym,
     * such a multiword expressions or the head noun of an expression, returning
     * a list of possbile noun hypernym candidates <i>ordered according to their
     * likelihood of being a hypernym</i>.  This method is designed to overcome
     * the inconsistencies in linking and glosses
     */
    public static List<String> extractNounCandidates(
        IDictionary dict, String gloss, String firstMatchingTerm,
        int locationInRawGloss) {

    
        // Search backwards starting at the location in the raw gloss for where
        // this term (which could be MWE) exists in the cleaned-up gloss
        int location = //locationInRawGloss-2; // -2 for [[
            gloss.indexOf(firstMatchingTerm);
        int n = firstMatchingTerm.length();
        // for (; location >= 0; --location) {
        //     // See if the string is present
        //     boolean isMatch = true;
        //     for (int i = 0; i < n && i < gloss.length(); ++i) {
        //         if (gloss.charAt(location + i) != firstMatchingTerm.charAt(i)) {
        //             isMatch = false;
        //             break;
        //         }
        //     }
        //     if (isMatch)
        //         break;
        // }

        // Just report the edge case, which should never happen
        if (location < 0) {
            CrownLogger.warning("Could not find %s in %s%n",
                              firstMatchingTerm, gloss);
            return Collections.<String>singletonList(firstMatchingTerm);
        }

        // System.out.printf("Found %s in %s at %d, next '%s'%n",
        //                   firstMatchingTerm, gloss, location,
        //                   gloss.charAt(location + n));
        
        // Special case for where there were trailing characters after the
        // markup, e.g., [[dog]s, which happens when an author wants to link but
        // use the plural form in in text.  The proper way of them doing this
        // would probably [[dog|dogs]].
        if (location + n < gloss.length()
                && !Character.isWhitespace(gloss.charAt(location + n))) {

            // Advance to the next non-whitespace
            int i = location + n;
            for (; i < gloss.length()
                     && !Character.isWhitespace(gloss.charAt(i)); ++i)
                ;
            // System.out.printf("CORRECTED %s to %s%n", firstMatchingTerm,
            //                   gloss.substring(location, i));
            firstMatchingTerm = gloss.substring(location, i);
            n = firstMatchingTerm.length();
        }
        
        // Once we know where the term occurs, see if we can find up to two
        // trailing terms that could comprise a MWE *and* which do not cross
        // some clause-ending boundary as indicated by punctuation.
        List<String> terms = new ArrayList<String>();
        terms.add(firstMatchingTerm);

        StringBuilder sb = new StringBuilder();
        for (int i = location + n; i < gloss.length(); ++i) {
            if (Character.isWhitespace(gloss.charAt(i))) {
                // If we're currently constructing a term, then add it and reset
                // the buffer
                if (sb.length() > 0) {
                    terms.add(sb.toString());
                    sb.setLength(0);
                    if (terms.size() == 2)
                        break;
                }
            }
            else {
                sb.append(gloss.charAt(i));
            }
            
            // if we've just seen the last character in the gloss and we're
            // still short on words, add it
            if (i + 1 == gloss.length()) {
                if (terms.size() < 2)
                    break;
                terms.add(sb.toString());                
            }
        }

        // Look for clause-ending punctuation as we strip out punctuation from
        // the terms
        ListIterator<String> iter = terms.listIterator();
        int numValidTerms = terms.size();
        while (iter.hasNext()) {
            String term = iter.next();
            Matcher m = TRAILING_PUNCT.matcher(term);
            if (m.find()) {
                String punct = m.group(1);
                if (punct.indexOf('.') >= 0 || punct.indexOf(',') >= 0
                        || punct.indexOf(';') >= 0 || punct.indexOf(':') >= 0) {

                    // Clean the term 
                    term = m.replaceAll("");
                    iter.set(term);
                    
                    numValidTerms = iter.nextIndex();
                    break;
                }
                // If the punctuation was something not clause-ending, just
                // strip it off and update the list of terms
                else {
                    term = m.replaceAll("");
                    iter.set(term);
                }
            }
        }

        // We can now search for MWEs in this list of terms
        terms = terms.subList(0, numValidTerms);

        List<String> candidates = new ArrayList<String>();
        if (terms.size() == 3) {
            candidates.add(String.join(" ", terms));
            candidates.add(terms.get(0) + " " + terms.get(1));
            candidates.add(terms.get(1) + " " + terms.get(2));
        }
        else if (terms.size() == 2) {
            candidates.add(terms.get(0) + " " + terms.get(1));
        }

        boolean isNoun1 = WordNetUtils.isInWn(dict, terms.get(0), POS.NOUN);
        boolean isNoun2 = terms.size() > 1 && WordNetUtils.isInWn(dict, terms.get(1), POS.NOUN);
        boolean isNoun3 = terms.size() > 2 && WordNetUtils.isInWn(dict, terms.get(2), POS.NOUN);
                
        if (terms.get(0).indexOf(' ') > 0) {
            String[] arr = terms.get(0).split("\\s+");
            candidates.add(terms.get(0));
            candidates.add(arr[1]);
            candidates.add(arr[0]);
            if (isNoun2) {
                if (isNoun3)
                    candidates.add(terms.get(2));
                candidates.add(terms.get(1));
            }            
        }
        else {
            if (isNoun2) {
                if (isNoun3)
                    candidates.add(terms.get(2));
                candidates.add(terms.get(1));
            }            
            candidates.add(terms.get(0));
        }
        return candidates;
    }
}
