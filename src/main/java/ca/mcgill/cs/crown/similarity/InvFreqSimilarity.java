/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/


package ca.mcgill.cs.crown.similarity;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;

import ca.mcgill.cs.crown.CrownAnnotations;
import ca.mcgill.cs.crown.LexicalEntry;

import ca.mcgill.cs.crown.util.CrownLogger;

import edu.ucla.sspace.util.Counter;
import edu.ucla.sspace.util.HashMultiMap;
import edu.ucla.sspace.util.MultiMap;
import edu.ucla.sspace.util.ObjectCounter;

import edu.mit.jwi.IDictionary;

import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.POS;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;


/**
 * Compares two strings on the basis of their shared lemmas, where words are
 * weighted by the inverse of their frequeucy in the glosses and similarity is
 * the sum of all overlapping words' weights.
 */
public class InvFreqSimilarity implements SimilarityFunction {

    /**
     * The set of verbs to exclude from similarity calculations.
     */
    static final Set<String> STOP_VERBS = new HashSet<String>();
    static {
        String[] verbs = new String[] {
            "is", "am", "are", "was", "were", "have", "has", "had",
            "will", "would",  "shall", "should",
            "may", "might", "must", "be", "been", "being", 
        };
        STOP_VERBS.addAll(Arrays.asList(verbs));
    }

    /**
     * Weights assigned to each lemma based on its relative frequency across all
     * glosses.
     */
    private final TObjectDoubleMap<String> lemmaToWeight;

    /**
     * A cache from a string to the lemmas contained in the string
     */
    private final MultiMap<String,String> stringToLemmasCache;

    /**
     * The CoreNLP pipeline used to annotate data
     */
    private final StanfordCoreNLP pipeline;
    
	
    public InvFreqSimilarity(Collection<LexicalEntry> entries,
                             IDictionary dict) {

        lemmaToWeight = new TObjectDoubleHashMap<String>(entries.size());
        // NOTE: make this some kind of proper cache?
        stringToLemmasCache = new HashMultiMap<String,String>();
        java.util.Properties props = new java.util.Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma");
        pipeline = new StanfordCoreNLP(props);

        reset(dict, entries);
    }

    /**
     * Extracts the content-word lemmas from a string, storing the mapping in a
     * cache for fast lookup later.
     */
    private Set<String> getLemmas(String gloss) {
        synchronized(stringToLemmasCache) {
            Set<String> lemmas = stringToLemmasCache.get(gloss);
            if (!lemmas.isEmpty())
                return lemmas;
        }
        
        Set<String> lemmas = new HashSet<String>();
        Annotation document = new Annotation(gloss);
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);

        // In some rare cases, a subdefinitoin could had multiple sentences.  We
        // use them all, though this should probably be analyzed
        for(CoreMap sentence: sentences) {
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                String lemma = token.get(LemmaAnnotation.class);
                String pos = token.get(PartOfSpeechAnnotation.class);
                char c = pos.substring(0,1).toLowerCase().charAt(0);
                // Not sure if we need to pos tag... but at least avoid putting
                // in everything but content
                if (c == 'n' || c == 'j' || c == 'r' 
                        || (c == 'v' && !STOP_VERBS.contains(lemma)))
                                       
                    lemmas.add(lemma);
            }
        }
        synchronized(stringToLemmasCache) {
            stringToLemmasCache.putMany(gloss, lemmas);
        }
        return lemmas;
    }
    
    /**
     * {@inheritDoc}
     */
    public double compare(String string1, String string2) {
        Set<String> s1lemmas = getLemmas(string1);
        Set<String> s2lemmas = getLemmas(string2);
            
       
        if (s1lemmas.isEmpty() || s2lemmas.isEmpty())
            return 0;

        // Compute the Jaccard Index
        double weightSum = 0;
        for (String s : s1lemmas) {
            if (s2lemmas.contains(s))
                weightSum += lemmaToWeight.get(s);
        }
        return weightSum;
    }

    public void reset(IDictionary dict, Collection<LexicalEntry> entries) {
        CrownLogger.verbose("Calculating lemma weights");
        Counter<String> lemmaCounts = new ObjectCounter<String>();
        int numGlosses = 0;

        for (LexicalEntry e : entries) {
            String gloss =
                e.getAnnotations().get(CrownAnnotations.Gloss.class);
            ++numGlosses;
            for (String lemma : getLemmas(gloss))
                lemmaCounts.count(lemma);
        }

        for (POS pos : POS.values()) {
            Iterator <ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset synset = iter.next();
                for (String lemma : getLemmas(synset.getGloss()))
                    lemmaCounts.count(lemma);
            }
        }
        
        for (Map.Entry<String,Integer> e : lemmaCounts) {
            double freq = e.getValue().doubleValue() / numGlosses;
            //System.out.println(e.getKey() + "\t" + freq + "\t" + Math.log(freq));
            lemmaToWeight.put(e.getKey(), -Math.log(freq  / (double)numGlosses));
        }
        CrownLogger.verbose("Done calculating lemma weights");        
    }
}
