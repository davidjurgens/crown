/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A collection of methods for working with Wiktionary data and glosses in their
 * raw format.
 */
public class WiktionaryUtils {

    // Wiki stuff...
    static final Pattern ANNOTATION = Pattern.compile("\\{\\{([^\\}]+)\\}\\}");
    static final Pattern MARKUP = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    static final Pattern EMPTY_PARENS = Pattern.compile("\\(\\s*\\)");
    static final Pattern SUP = Pattern.compile("<sup>([^<]+)</sup>");

    static final Set<String> SLANG_ANNOTATIONS = new HashSet<String>
        (Arrays.asList(new String[] { "informal", "euphemistic", "slang",
                                      "internet slang", "vulgar", "offensive",
                                      "pejorative", "ethnic slug" }));
    
    /**
     * Returns {@code true} if the annotation in this raw gloss indicate that
     * the sense is slang (using a general definition of slang).
     */
    public static boolean isSlangGloss(String rawGloss) {
        Matcher m = ANNOTATION.matcher(rawGloss);
        while (m.find()) {
            String annotation = m.group(1);
            if (!annotation.startsWith("context"))
                continue;
            String[] tokens = annotation.split("\\|");
            for (String token : tokens) {
                if (SLANG_ANNOTATIONS.contains(token))
                    return true;
            }
        }
        return false;
    }
    
    /**
     * Removes Wiktionary annotations from this gloss, which occur in double
     * curly braces.
     */
    public static String stripAnnotations(String gloss) {
        Matcher m = ANNOTATION.matcher(gloss);
        return m.replaceAll("").trim();
    }
    
    public static String cleanGloss(String gloss) {
        return cleanGloss(gloss, true);
    }

    public static String cleanGloss(String gloss, boolean removeMarkup) {    
        String orig = gloss;

        Matcher m = ANNOTATION.matcher(gloss);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String match = m.group(1);
            //System.out.println(match);
            String[] arr = match.split("\\|");
            if (arr.length > 1)
                    arr[1] = arr[1].replace("$", "\\$");
                
            // Some editors use the {{soplink|} markup which has a variable
            // number of parameters, {{soplink|deep|water}}.  The tag tries to
            // link all the entites in it into a single term if it exists, and
            // otherwise links the individual terms.  We just treat it as one
            // term.
            if (match.startsWith("soplink|")) {
                arr = Arrays.copyOfRange(arr, 1, arr.length);
                m.appendReplacement(sb, String.join(" ", arr));
            }
            // Some editors include taxonomic information via taxlink, e.g,
            // {{taxlink|Larrea tridentata|species}}.  Strip out the markup
            // and use the middle item
            else if (match.startsWith("taxlink")) {
                m.appendReplacement(sb, arr[1]);
            }
            // Some editors include comparison links in the form
            // {{term|circannual|lang=en}}            
            else if (match.startsWith("term|")) {
                m.appendReplacement(sb, arr[1]);
            }
            // Some editors use {{w|article name}} to link to wikipedia.  We
            // treat these as definitional links too.
            else if (match.startsWith("w|")) {
                m.appendReplacement(sb, arr[1]);
            }
            // Some editors include unsupported text in the wikimedia markup
            // using {{unsupported, e.g., {{unsupported|#}}.  Strip out the
            // markup and use the text anyway
            else if (match.startsWith("unsupported|")) {
                m.appendReplacement(sb, arr[1]);
            }
            // Some editors add a gloss in its own markup, e.g, {{gloss|stuff}}.
            // Strip out the markup and use the middle item
            else if (match.startsWith("gloss|")) {
                m.appendReplacement(sb, arr[1]);
            }
            else if (match.startsWith("non-gloss definition")) {
                if (arr.length > 1)
                    m.appendReplacement(sb, arr[1]);
                else
                    m.appendReplacement(sb, arr[0]);
            }
            else {
                m.appendReplacement(sb, " ");
            }
        }
        m.appendTail(sb);
        gloss = sb.toString().replace("\\$", "$");

        // Replace the non-ASCII quotation marks
        gloss = gloss.replaceAll("[“”]", "\"");

        gloss = gloss.replace("<ref name=SOED/>", "").trim();
        
        if (!removeMarkup)
            return gloss;

        m = MARKUP.matcher(gloss);
        sb.setLength(0);
        while (m.find()) {
            String wiki = m.group(1);
            String[] tokens = wiki.split("\\|");
            try {
                m.appendReplacement(sb, tokens[tokens.length-1]
                                    .replace("\\", "\\\\")
                                    .replace("$", "\\$"));
            } catch (Throwable t) {
                // Happens when the gloss is something like "\ ")
                // System.out.printf("%s\t::\t%s%n", tokens[tokens.length-1], wiki);
                throw new Error(t);
            }
        }
        m.appendTail(sb);
        
        return sb.toString().replace("\\$", "$").replace("\\\\", "\\").trim();
    }

    public static List<String> extractContiguousLinkedTerms(String text, int start) {
        Matcher m = MARKUP.matcher(text);

        // If we didn't find anything
        if (!m.find(start))
            return Collections.<String>emptyList();
        
        // Some nouns will have glosses with multiple entities tagged
        // together, e.g., [[integrated]] [[circuit]].  
        int end = m.end() + 1;

        List<String> candidates = new ArrayList<String>();
        candidates.add(m.group(1).replaceAll("[\\p{Punct}]+$", ""));

        link_loop:
        while (m.find(end)) {
            start = m.start();
            // check that all characters between start and end are whitespace
            for (int i = end; i < start; ++i) {
                if (!Character.isWhitespace(text.charAt(i)))
                    break link_loop;
            }
            candidates.add(m.group(1).replaceAll("[\\p{Punct}]+$", ""));
            end = m.end();
        }

        //System.out.println(candidates);
        return candidates;
    }

    
    /**
     * Extracts the annotations embedded within Wiktionary glosses as {{ }},
     * returning the list of annotation texts contained within the braces, or an
     * empty list if no annotations are present.
     */
    public static List<String> extractAnnotations(String gloss) {
        List<String> annotations = null;  
        while (gloss.startsWith("{{")) {
            int i = gloss.indexOf("}}");
            if (i > 0) {
                String annotation = gloss.substring(2, i);
                if (annotations == null)
                    annotations = new ArrayList<String>();
                annotations.add(annotation);
                gloss = gloss.substring(i+2).trim();
            }
        }
        return (annotations == null)
            ? Collections.<String>emptyList()
            : annotations;
    }

    static final Map<String,Integer> TAG_OFFSETS = new HashMap<String,Integer>();
    static {
        TAG_OFFSETS.put("surname", 0);
        TAG_OFFSETS.put("form of", 2);
    }


    /**
     * Given a list of Wiktionary annotations within a particular gloss, returns
     * a mapping from the annotation type to its main value.
     */
    public static Map<String,String> extractAnnotationValues(
            List<String> annotations) {

        Map<String,String> typeToValue = new LinkedHashMap<String,String>();

        for (String annotation : annotations) {
            String[] cols = annotation.split("\\|");
            String tagType = cols[0].trim();

            if (cols.length > 1) {
                int col = (TAG_OFFSETS.containsKey(tagType))
                    ? TAG_OFFSETS.get(tagType) : 1;
                    
                String value = cols[col];
                // If we encounter non-standard tag formations, make a
                // last-ditch effort to skip pass the Wiktionary markup
                // to a content word in the tag.  Since we start the
                // column at 1, this usually works.
                while ((value.contains("=") || value.contains("{"))
                       && col < cols.length - 1) {
                    col++;
                    value = cols[col];
                }
                typeToValue.put(tagType, value);
            }
        }
        return typeToValue;
    }
}
