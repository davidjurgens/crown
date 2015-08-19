/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

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


/**
 * A collection of methods for working with WordNet through the JWI {@link
 * IDictionary} interface.
 */
public class WordNetUtils {

    static final IStemmer MORPHY = new SimpleStemmer();
    
    static StringBuilder glossSb = new StringBuilder();

    static final Pattern USAGE_IN_GLOSS = Pattern.compile(";[\\s]*\"[^\"]+\"");
    
    /**
     * Returns the gloss of this synset, with all of its synonyms appended to
     * it.
     */
    public static synchronized String getExtendedGloss(ISynset syn) {
        glossSb.setLength(0);
        glossSb.append(syn.getGloss());
        for (IWord iw : syn.getWords()) {
            glossSb.append(iw.getLemma()).append(' ');
        }
        return glossSb.toString();
    }

    /**
     * Returns the gloss of this synset, without the examples contained in it
     */
    public static synchronized String getGlossWithoutExamples(ISynset syn) {
        Matcher m = USAGE_IN_GLOSS.matcher(syn.getGloss());
        return m.replaceAll("");
    }
    
    public static synchronized ISynset getFirstSense(IDictionary dict,
                                                     IIndexWord iw) {
        List<IWordID> wids = iw.getWordIDs();
        ISynset synset = dict.getSynset(wids.get(0).getSynsetID());
        return synset;
    }

    public static synchronized ISynset getFirstSense(IDictionary dict,
                                                     String lemma, POS pos) {

        // Short circuit for bad inputs
        if (lemma == null || lemma.length() == 0)
            return null;
        ALL_WHITESPACE.reset(lemma);
        if (ALL_WHITESPACE.matches())
            return null;
        
        // Get the WordNet sysnet if it exists
        IIndexWord iw = null;
        try {
            iw = dict.getIndexWord(lemma, pos);
        } catch (Throwable t) { }
        //System.out.printf("getIndexWord(%s, %s) -> %s%n", lemma, pos, iw);
        if (iw != null) {
            ISynset fs = getFirstSense(dict, iw);
            if (fs != null)
                return fs;
        }
                       
        // Check for morphological variants as recognized by WN's Morphy
        List<String> stems = findStems(lemma, pos);
        for (String stem : stems) {
            if (!stem.equals(lemma)) {
                iw = dict.getIndexWord(stem, pos);
                //System.out.printf("getIndexWord(%s, %s) -> %s%n", stem, pos, iw);
                if (iw != null) {
                    ISynset fs = getFirstSense(dict, iw);
                    if (fs != null)
                        return fs;
                }
            }
        }

        // Manually guard against the case where we have a compound term which
        // isn't being properly stemmed because the POS isn't being recognized
        // correctly for its terms
        if (stems.isEmpty() && lemma.indexOf(' ') >= 0) {
            stems = getCompoundStems(lemma, pos);
            // System.out.printf("Compound stems:: %s -> %s%n", lemma, stems);
            for (String stem : stems) {
                iw = dict.getIndexWord(stem, pos);
                //System.out.printf("getIndexWord(%s, %s) -> %s%n", stem, pos, iw);
                if (iw != null) {
                    ISynset fs = getFirstSense(dict, iw);
                    if (fs != null)
                        return fs;
                }
            }
        }

        // Some of Wiktionary won't be in WordNet, but they may be as
        // exceptions.  This also helps us if the lemma could be interpretted as
        // an alternate lexicalization of a different set of senses.
        IExceptionEntry ee = dict.getExceptionEntry(lemma, pos);
        if (ee != null) {
            for (String root : ee.getRootForms()) {
                iw = dict.getIndexWord(root, pos);
                //System.out.printf("getIndexWord(%s, %s) -> %s%n", root, pos, iw);
                // not sure how null is possible, but it is...
                if (iw != null) {
                    ISynset fs = getFirstSense(dict, iw);
                    if (fs != null)
                        return fs;
                }
            }
        }
        
        // CrownLogger.warning("Unable to find %s (%s) in dictionary", lemma, pos);
        
        return null;
    }

    
    public static synchronized Set<ISynset> getSynsets(IDictionary dict,
                                   Collection<String> lemmas, POS pos) {
        Set<ISynset> synsets = new LinkedHashSet<ISynset>();
        for (String lemma : lemmas)
            synsets.addAll(getSynsets(dict, lemma, pos));
        return synsets;
    }

    public static synchronized Set<ISynset> getSynsets(IDictionary dict,
                                   String[] lemmas, POS pos) {
        Set<ISynset> synsets = new LinkedHashSet<ISynset>();
        for (String lemma : lemmas)
            synsets.addAll(getSynsets(dict, lemma, pos));
        return synsets;
    }

    static final Matcher ALL_WHITESPACE = Pattern.compile("[\\s]+").matcher("");

    public synchronized static Set<ISynset>
           getSynsets(IDictionary dict, String lemma, POS pos) {
        
        Set<ISynset> synsets = new LinkedHashSet<ISynset>();

        // Short circuit for bad inputs
        if (lemma == null || lemma.length() == 0)
            return synsets;
        ALL_WHITESPACE.reset(lemma);
        if (ALL_WHITESPACE.matches())
            return synsets;
        
        // Get the WordNet sysnet if it exists
        IIndexWord iw = null;
        try {
            iw = dict.getIndexWord(lemma, pos);
        } catch (Throwable t) { }
        if (iw != null) {
            for (IWordID wordId : iw.getWordIDs()) {                   
                ISynset synset = dict.getSynset(wordId.getSynsetID());
                synsets.add(synset);
            }
        }
        
        // Check for morphological variants as recognized by WN's Morphy
        List<String> stems = findStems(lemma, pos);
        for (String stem : stems) {
            if (!stem.equals(lemma)) {
                iw = dict.getIndexWord(stem, pos);
                if (iw != null) {
                    for (IWordID wordId : iw.getWordIDs()) {                   
                        ISynset synset = dict.getSynset(wordId.getSynsetID());
                        synsets.add(synset);
                    }
                }
            }
        }

        // Manually guard against the case where we have a compound term which
        // isn't being properly stemmed because the POS isn't being recognized
        // correctly for its terms
        if (stems.isEmpty() && lemma.indexOf(' ') >= 0) {
            stems = getCompoundStems(lemma, pos);
            // System.out.printf("Compound stems:: %s -> %s%n", lemma, stems);
            for (String stem : stems) {
                iw = dict.getIndexWord(stem, pos);
                if (iw != null) {
                    for (IWordID wordId : iw.getWordIDs()) {                   
                        ISynset synset = dict.getSynset(wordId.getSynsetID());
                        synsets.add(synset);
                    }
                }
            }
        }

        // Some of Wiktionary won't be in WordNet, but they may be as
        // exceptions.  This also helps us if the lemma could be interpretted as
        // an alternate lexicalization of a different set of senses.
        IExceptionEntry ee = dict.getExceptionEntry(lemma, pos);
        if (ee != null) {
            for (String root : ee.getRootForms()) {
                iw = dict.getIndexWord(root, pos);
                // not sure how null is possible, but it is...
                if (iw != null) {
                    for (IWordID wordId : iw.getWordIDs()) {                   
                        ISynset synset = dict.getSynset(wordId.getSynsetID());
                        synsets.add(synset);
                    }
                }
            }
        }
        
        return synsets;
    }

    public static synchronized List<String> findStems(String lemma, POS pos) {
        try {
            return MORPHY.findStems(lemma, pos);
        } catch (Exception e) {
            return Collections.<String>emptyList();
        }
    }
    
    private static List<String> getCompoundStems(String lemma, POS pos) {
        String[] tokens = lemma.split("\\s+");
        List<List<String>> variants = new ArrayList<List<String>>();
        for (String token : tokens) {
            List<String> stems = findStems(token, pos);
            if (stems.isEmpty())
                stems = Collections.<String>singletonList(token);
            variants.add(stems);
        }

        List<String> stems = new ArrayList<String>();
        makeStems(0, "", variants, stems);
        return stems;
    }

    private static void makeStems(int curIndex, String curString,
                                  List<List<String>> variants, List<String> stems) {
        if (curIndex == variants.size())
            return;
        List<String> options = variants.get(curIndex);
        for (String option : options) {
            String s = (curIndex == 0) ? option : curString + " " + option;
            if (curIndex + 1 == variants.size())
                stems.add(s);
            else
                makeStems(curIndex + 1, s, variants, stems);
        }
    }
    
    /**
     * Returns {@code true} if WordNet contains an entry for this lemma and part
     * of speech, either as a sysnet or an exception.
     */
    public static synchronized boolean isInWn(IDictionary dict, String lemma, POS pos) {
        if (lemma == null || lemma.equals(""))
            return false;
        try {
            if (dict.getIndexWord(lemma, pos) != null
                    || dict.getExceptionEntry(lemma, pos) != null)
                return true;
        } catch (Throwable t) {
            return false;
        }
        List<String> stems = findStems(lemma, pos);
        for (String stem : stems) {
            if (!stem.equals(lemma) && dict.getIndexWord(stem, pos) != null)
                return true;            
        }
        return false;
    }

    /**
     * Returns {@code true} if WordNet already contains a synset for this lemma
     * and part of speech within three edges of the specified synset.
     */
    public static synchronized boolean isAlreadyInWordNet(IDictionary dict,
                                             String lemma, POS pos,
                                             ISynset candidateAttachment) {

        return isAlreadyInWordNet(dict, lemma, pos,
                                  Collections.<ISynset>singleton(candidateAttachment));
    }

    /**
     * Returns {@code true} if WordNet already contains a synset for this lemma
     * and part of speech within three edges of any of the the specified
     * synsets.
     */
    public static synchronized boolean isAlreadyInWordNet(IDictionary dict, String lemma,
            POS pos, Collection<ISynset> candidateAttachments) {
        
        List<String> lemmaAndVariants = new ArrayList<String>();
        lemmaAndVariants.add(lemma);
        // if (lemma.indexOf('-') >= 0) {
        //     lemmaAndVariants.add(lemma.replace("-", " "));
        //     lemmaAndVariants.add(lemma.replace("-", ""));
        // }

        // if (lemma.indexOf(' ') >= 0) {
        //     lemmaAndVariants.add(lemma.replace(" ", "-"));
        //     lemmaAndVariants.add(lemma.replace(" ", ""));
        // }

        Set<ISynset> targetSynsets =
            getSynsets(dict, lemmaAndVariants, pos);
        
        // Check each synset and those up to two hops away
        for (ISynset s1 : candidateAttachments) {
            assert s1 != null : "included null candidate attachment";
            if (targetSynsets.contains(s1)) {
                return true;
            }
            for (ISynset s2 : oneAway(dict, s1, pos)) {
                if (targetSynsets.contains(s2)) {
                    return true;
                }
                for (ISynset s3 : oneAway(dict, s2, pos)) {
                    if (targetSynsets.contains(s3)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the synset {@code start} is a descendent of the
     * synset {@code goal}.
     */
    public static synchronized boolean isDescendent(IDictionary dict, ISynset start,
                                       ISynsetID goal) {

        if (start.getID().equals(goal))
            return true;

        // Do a BFS search from the hypernyms to find the organism synset
        Set<ISynsetID> frontier = new HashSet<ISynsetID>();
        Set<ISynsetID> next = new HashSet<ISynsetID>();
        Set<ISynsetID> visited = new HashSet<ISynsetID>(); // guard against
                                                           // weird loops
        frontier.addAll(start.getRelatedSynsets(Pointer.HYPERNYM));
        frontier.addAll(start.getRelatedSynsets(Pointer.HYPERNYM_INSTANCE));

        while (!frontier.isEmpty()) {

            if (frontier.contains(goal))
                return true;

            visited.addAll(frontier);
            
            for (ISynsetID id : frontier) {
                ISynset syn = dict.getSynset(id);
                for (ISynsetID hyper : syn.getRelatedSynsets(Pointer.HYPERNYM)) {
                    if (!visited.contains(hyper))
                        next.add(hyper);
                }
                for (ISynsetID hyper
                         : syn.getRelatedSynsets(Pointer.HYPERNYM_INSTANCE)) {
                    if (!visited.contains(hyper))
                        next.add(hyper);
                }
            }

            frontier.clear();
            frontier.addAll(next);
            next.clear();
        }
        return false;
    }
    
    /**
     * Returns all synsets that are one edge away from the provided synset in
     * either hypernym or hypernym, or in the case of adjectives, an edge from
     * similar_to or also_see pointers.
     */
    private static Set<ISynset> oneAway(IDictionary dict, ISynset syn, POS pos) {
        //assert syn != null : "synset cannot be null";
        if (syn == null)
            return Collections.<ISynset>emptySet();
        
        Set<ISynset> results = new HashSet<ISynset>();
        for (ISynsetID sid : syn.getRelatedSynsets(Pointer.HYPERNYM))
            results.add(dict.getSynset(sid));
        for (ISynsetID sid : syn.getRelatedSynsets(Pointer.HYPERNYM_INSTANCE))
            results.add(dict.getSynset(sid));
        for (ISynsetID sid : syn.getRelatedSynsets(Pointer.HYPONYM))
            results.add(dict.getSynset(sid));
        for (ISynsetID sid : syn.getRelatedSynsets(Pointer.HYPONYM_INSTANCE))
            results.add(dict.getSynset(sid));

        // Adjectives have lots of related synsets that aren't in a strict
        // hypernym/hyponym structure, so include these too to avoid having
        // similar-meaning adjectives close to each other
        if (pos.equals(POS.ADJECTIVE) || pos.equals(POS.ADVERB)) {
            for (ISynsetID sid : syn.getRelatedSynsets(Pointer.SIMILAR_TO))
                results.add(dict.getSynset(sid));
            for (ISynsetID sid : syn.getRelatedSynsets(Pointer.ALSO_SEE))
                results.add(dict.getSynset(sid));
        }
        return results;
    }

    public static synchronized IDictionary open(String wnpath) throws Exception {
        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null)
            return null;
        IDictionary dict =
             new edu.mit.jwi.Dictionary(url);
        // new edu.mit.jwi.RAMDictionary(url, ILoadPolicy.IMMEDIATE_LOAD);
        dict.open();
        //dict.open();
        return new ThreadSafeDictionary(dict);            
    }

    public static synchronized IDictionary open(File wnDictDir) {
        // Load the WN library
        try {
            IDictionary dict = new edu.mit.jwi.Dictionary(wnDictDir.toURI().toURL());
            if (!dict.open()) {
                throw new Error("Unable to open dictionary at " + wnDictDir);
            }
            ICachingDictionary cache = new CachingDictionary(dict);
            cache.getCache().setEnabled(true);
            cache.getCache().setMaximumCapacity(500_000);
            return new ThreadSafeDictionary(cache);
        } catch (Throwable t) {
            throw new Error(t);
        }
    }

    /**
     * Returns the set of lemmas contained in this synset
     */
    public static synchronized Set<String> toLemmas(ISynset s) {
        Set<String> lemmas = new HashSet<String>();
        for (IWord iw : s.getWords())
            lemmas.add(iw.getLemma());
        return lemmas;
    }    
}
