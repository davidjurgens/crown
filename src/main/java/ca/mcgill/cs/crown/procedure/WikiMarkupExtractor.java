/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.procedure;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import edu.ucla.sspace.util.*;
import edu.ucla.sspace.util.Duple;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;

import edu.stanford.nlp.util.ErasureUtils;

import ca.mcgill.cs.crown.AnnotatedLexicalEntry;
import ca.mcgill.cs.crown.AnnotatedLexicalEntryImpl;
import ca.mcgill.cs.crown.EnrichmentProcedure;
import ca.mcgill.cs.crown.LexicalEntry;
import ca.mcgill.cs.crown.CrownAnnotations;
import ca.mcgill.cs.crown.CrownOperations;

import ca.mcgill.cs.crown.similarity.SimilarityFunction;

import ca.mcgill.cs.crown.util.GlossUtils;
import ca.mcgill.cs.crown.util.WiktionaryUtils;
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s
 * unprocessed (raw) glosses to identify wikified texts that indicates a certain
 * word is the hypernym
 * 
 */
public class WikiMarkupExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    private static final Pattern TRAILING_PUNCT = Pattern.compile("([\\p{Punct}]+)$");
    
    /**
     * Matches things like "A[n]? [[noun]]\b"
     */
    static final Pattern A_LINKED_NOUN =
        Pattern.compile("\\b(?:A[n]?|The|Those) \\[\\[([^\\]]+)\\]\\]");

    /**
     * Matches a linked noun that occurs at the beginning of a gloss, either in
     * isolation, or immediately followed by clause-final punctuation
     */
    static final Pattern INITIAL_NOUN =
        Pattern.compile("^\\[\\[([^\\]]+)\\]\\](?:$|[,.;:])");
    
    static final Pattern PREP_PHRASE_A_LINKED_NOUN =
        Pattern.compile("^(?:In|Of|When|Because|From|On|Above|At)\\b .*, (?:the|a[n]?) \\[\\[([^\\]]+)\\]\\]s?");

    static final Map<Pattern,String> NOUN_PATTERN_TO_NAME
        = new HashMap<Pattern,String>();
    static {
        NOUN_PATTERN_TO_NAME.put(A_LINKED_NOUN, "linked-noun");
        NOUN_PATTERN_TO_NAME.put(INITIAL_NOUN, "initial-noun");
        NOUN_PATTERN_TO_NAME.put(PREP_PHRASE_A_LINKED_NOUN, "prep-phrase-linked-noun");
    }
    
    /**
     * Matches things like "To (adv)? [[verb]]\b"
     */
    static final Pattern TO_VERB_LINK =
        Pattern.compile("\\bTo (?:[a-z\\-]+ly)?\\s?\\[\\[([^\\]]+)\\]\\]");


    public WikiMarkupExtractor(IDictionary dict,
                              SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        switch (e.getPos()) {
        case VERB:
            return processRawVerbGloss(e);
        case NOUN:
            return processRawNounGloss(e);
        }
        return null;
    }

    private AnnotatedLexicalEntry processRawNounGloss(LexicalEntry e) {

        // Avoid trying to add new senses for nouns already in WN.  This ends up
        // being very noisy due to the limited gloss.
        if (WordNetUtils.isInWn(dict, e.getLemma(), POS.NOUN))
            return null;

        Map<String,String> rawGlosses =
            e.getAnnotations().get(CrownAnnotations.RawGlosses.class);

                
        for (Map.Entry<String,String> g : rawGlosses.entrySet()) {

            // When looking at the raw gloss, strip out any leading annotations
            String rawGloss = WiktionaryUtils.stripAnnotations(g.getKey());

            // Strip out the quotation marks of the raw gloss too
            rawGloss = rawGloss.replaceAll("[']{2,}", "").trim();
            
            String cleanedGloss = g.getValue();
            if (cleanedGloss.length() == 0)
                continue;
                       
            for (Map.Entry<Pattern,String> me
                     : NOUN_PATTERN_TO_NAME.entrySet()) {
                Pattern p = me.getKey();
                String heuristicName = me.getValue();
                
                Matcher m = p.matcher(rawGloss);
                if (m.find()) {
                    String firstMatchingTerm = m.group(1);

                    // Sometimes the Wiktinary [[word]] will have a pipe in it
                    // to symbolize a link to something else, e.g.,
                    // [[word|RealWord]].  Strip out this other word from the
                    // lemma and the sentence.
                    int i = firstMatchingTerm.lastIndexOf('|');
                    if (i > 0) {
                        String tmp = firstMatchingTerm.substring(i+1);
                        firstMatchingTerm = tmp;
                    }

                    // System.out.printf("%s found initial match: %s%n",
                    //                   heuristicName, firstMatchingTerm);
                    List<String> candidates = GlossUtils.extractNounCandidates(
                        dict, cleanedGloss, firstMatchingTerm, m.start(1));

                    // System.out.printf("%s ==> %s%n", rawGloss, candidates);
                    
                    Duple<CrownOperations.Reason,ISynset> op =
                        findSense(candidates, e, cleanedGloss);
                    if (op == null)
                        continue;

                    CrownOperations.Reason r = op.x;
                    r.set("heuristic", heuristicName);
                    ISynset related = op.y;
                    if (related == null)
                        continue;
                    
                    // See if this sense is slang.  If so, we'll attach this
                    // sense as a hypernym.  Otherwise, we'll keep it as a
                    // synonym
                    AnnotatedLexicalEntry ale =
                        new AnnotatedLexicalEntryImpl(e);
                    
                    ale.setOp(CrownOperations.Hypernym.class, r, related);
                    return ale;
                }
                else {
                    // System.out.printf("No pattern match for %s using %s%n",
                    //                   rawGloss, heuristicName);
                }
            }
        }
        return null;
    }
    
    private Duple<CrownOperations.Reason,ISynset> findSense(
            List<String> candidateHypers, LexicalEntry e,
            String cleanedGloss) {
        
        for (String hypernymLemma : candidateHypers) {
                 POS pos = POS.NOUN;
             
            // Skip trying to attach senses for which we found they hypernym but
            // in which case that hypernym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, hypernymLemma, pos))
                continue;

            // Now that we have a hypernym attachment, figure out which sense of
            // the hypernym is most similar to this gloss
            Set<ISynset> candidateHypernymSynsets =
                WordNetUtils.getSynsets(dict, hypernymLemma, pos);

            if (candidateHypernymSynsets.isEmpty())
                continue;

            double maxScore = 0;
            ISynset best = null;
            String bestGloss = null;
            for (ISynset candidate : candidateHypernymSynsets) {
                String wnExtendedGloss =
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(cleanedGloss, wnExtendedGloss);

                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                }
            }

            if (best == null)
                continue;

            /*
            assert best != null : "no match made";

            if (best == null) {
                Map<String,String> rawGlosses =
                    e.getAnnotations().get(CrownAnnotations.RawGlosses.class);
                
                
                for (Map.Entry<String,String> g : rawGlosses.entrySet()) {
                    System.out.printf("ERROR CASE, RAW: \"%s\"  ==> \"%s\"%n",
                                      g.getKey(), g.getValue());
                }
                for (ISynset synset : candidateHypernymSynsets) {
                    String wnGloss = WordNetUtils.getGlossWithoutExamples(synset);
                    double sim = simFunc.compare(cleanedGloss, wnGloss);
                    System.out.printf("ERROR CASE: %f for \"%s\" and \"%s\"%n",
                                      sim, cleanedGloss, wnGloss);            
                }
            }
            */            
            
            //System.out.printf("For %s, best: %s, sim: %f%n", e, best, maxScore);
            
            // Check that this sense isn't already in WN near where we're trying
            // to put it
            if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(), pos,best)) {
                continue;
            }
           
            // Choose the most similar gloss.
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("max_score", maxScore);
            return new Duple<CrownOperations.Reason,ISynset>(r, best);
        }
        return null;
    }
    
    // private List<String> getCandidates(String text, Pattern init) {
    //     Matcher m = init.matcher(text);

    //     // If we didn't find anything
    //     if (!m.find())
    //         return Collection.<String>emptyList();
        
    //     // Some nouns will have glosses with multiple entities tagged
    //     // together, e.g., [[integrated]] [[circuit]].  
    //     int end = m.end() + 1;

    //     List<String> candidates = new ArrayList();
    //     candidates.add(m.group(1).replaceAll("[\\p{Punct}]+$", ""));

    //     Matcher m2 = LINKED_TEXT.matcher(text);
    //     link_loop:
    //     while (m2.find(end)) {
    //         int start = m2.start();
    //         // check that all characters between start and end are whitespace
    //         for (int i = end; i < start; ++i) {
    //             if (!Character.isWhitespace(text.charAt(i)))
    //                 break link_loop;
    //         }
    //         candidates.add(m2.group(1).replaceAll("[\\p{Punct}]+$", ""));
    //         end = m2.end();
    //     }

    //     System.out.println(candidates);
        
    //     // Try combinining individual terms for linking, and report the
    //     // individual terms, beginning with the head word

    //     return candidates;
    // }
    
    private AnnotatedLexicalEntry processRawVerbGloss(LexicalEntry e) {
    
        // Avoid trying to add new senses for verbs already in WN.  This ends up
        // being very noisy due to the limited gloss.
        if (WordNetUtils.isInWn(dict, e.getLemma(), POS.VERB))
            return null;

        Map<String,String> rawGlosses =
            e.getAnnotations().get(CrownAnnotations.RawGlosses.class);
        

        for (Map.Entry<String,String> g : rawGlosses.entrySet()) {

            String rawGloss = g.getKey();
            String cleanedGloss = g.getValue();
            
            Matcher m = TO_VERB_LINK.matcher(rawGloss);
            if (!m.find())
                continue;

            String hypernymLemma = m.group(1).replaceAll("[\\p{Punct}]+$", "");
            POS pos = POS.VERB;
            
            // Skip trying to attach senses for which we found they hypernym but
            // in which case that hypernym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, hypernymLemma, pos))
                continue;

            // Now that we have a hypernym attachment, figure out which sense of
            // the hypernym is most similar to this gloss
            Set<ISynset> candidateHypernymSynsets =
                WordNetUtils.getSynsets(dict, hypernymLemma, pos);

            if (candidateHypernymSynsets.isEmpty())
                continue;

            double maxScore = -1;
            ISynset best = null;
            String bestGloss = null;
            for (ISynset candidate : candidateHypernymSynsets) {
                String wnExtendedGloss =
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(cleanedGloss, wnExtendedGloss);
                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                }
            }

            // Check that this sense isn't already in WN near where we're trying
            // to put it
            if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(),
                                                pos, best)) {
                continue;
            }

            if (best == null) {
                for (Map.Entry<String,String> g2 : rawGlosses.entrySet()) {
                    System.out.printf("ERROR CASE, RAW: \"%s\"  ==> \"%s\"%n",
                                      g2.getKey(), g2.getValue());
                }
                for (ISynset synset : candidateHypernymSynsets) {
                    String wnGloss = WordNetUtils.getGlossWithoutExamples(synset);
                    double sim = simFunc.compare(cleanedGloss, wnGloss);
                    System.out.printf("ERROR CASE: %f for \"%s\" and \"%s\"%n",
                                      sim, cleanedGloss, wnGloss);            
                }
            }

            // Choose the most similar gloss.
            if (best != null) {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);            
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "linked-verb");
                r.set("max_score", maxScore);
                ale.setOp(CrownOperations.Hypernym.class, r, best);
                return ale;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }
    
}
