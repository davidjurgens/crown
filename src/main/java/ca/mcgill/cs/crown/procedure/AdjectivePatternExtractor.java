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

import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s
 * glosses to identify TODO
 * 
 */
public class AdjectivePatternExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;    

    /**
     * Matches things like "To (adv)? [[verb]]\b"
     */
    static final Pattern PARATAXIS_ADJ =
        Pattern.compile("^([a-z][a-z\\-]+[a-z]), ([a-z][a-z\\-]+[a-z])(?:, [a-z][a-z\\-]+[a-z])");

    static final Pattern PERTAINYM_ADJ =
        Pattern.compile("^(?:[Oo]f(?:, or relating to)?|[Rr]elating to(?:, or of)?|[Pp]ertaining to) (?:a[n]? |the )?([a-z][a-z\\-]+[a-z])");

    // static final Map<Pattern,String> PATTERN_TO_NAME =
    //     new HashMap<Pattern,String>();
    // static {
    //     PATTERN_TO_NAME.put(PARATAXIS_ADJ, "parataxis-adj");
    //     PATTERN_TO_NAME.put(RELATED_ADJ, "related-adj");
    // }    
    

    public AdjectivePatternExtractor(IDictionary dict,
                                SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.ADJECTIVE))
            return null;
        
        POS pos = POS.ADJECTIVE;

        // Avoid trying to add new senses for verbs already in WN.  This ends up
        // being very noisy due to the limited gloss.
        if (WordNetUtils.isInWn(dict, e.getLemma(), pos))
            return null;

        for (String gloss :
                 e.getAnnotations().get(CrownAnnotations.Glosses.class)) {

            gloss = gloss.toLowerCase();
            AnnotatedLexicalEntry ale = findParataxis(gloss, e, pos);
            if (ale != null)
                return ale;
            
            ale = findRelation(gloss, e);
            if (ale != null) {
                // System.out.println("FOUND RELATION: " + ale);
                return ale;
            }
        }
        return null;
    }

    private AnnotatedLexicalEntry findParataxis(String gloss, LexicalEntry e,
                                                POS pos) {
        Matcher m = PARATAXIS_ADJ.matcher(gloss.toLowerCase());
        if (!m.find()) {
            // System.out.printf("Could not find %s in %s%n",
            //                   PARATAXIS_ADJ, gloss);
            return null;
        }
        
        List<String> synonyms = Arrays.asList(gloss.split("\\s*,\\s*"));
        // System.out.println("Found ADJ synonyms: " + synonyms);
            
        Duple<CrownOperations.Reason,ISynset> synonymOp =
            getEstimatedSynonym(synonyms, pos, gloss);
        
        if (synonymOp != null) {
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            ale.setOp(CrownOperations.Synonym.class,
                      synonymOp.x, synonymOp.y);
            return ale;
        }
        return null;
    }

    /**
     * Searches the gloss for indications of a pertainymy relationship with an
     * noun and then links this entry to the noun.
     */
    private AnnotatedLexicalEntry findRelation(String gloss, LexicalEntry e) {
        Matcher m = PERTAINYM_ADJ.matcher(gloss.toLowerCase());
        if (!m.find()) {
            // System.out.printf("Could not find %s in %s%n",
            //                   PERTAINYM_ADJ, gloss);
            return null;
        }

        // The first matching term should be a noun
        String firstMatchingTerm =
            m.group(1).replaceAll("[\\p{Punct}]+$", "").trim();

        // But the term could be a MWE (e.g., "computer program"), so generate a
        // possible list of related noun concepts
        List<String> candidates = m(gloss, firstMatchingTerm);
        //System.out.println("Petranym candidates: " + candidates);

        for (String pertainymLemma : candidates) {
            
            // Skip trying to attach senses for which we found they pertainym but
            // in which case that pertainym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, pertainymLemma, POS.NOUN)) {
                // System.out.printf("%s (%s) is not in WN%n", pertainymLemma, pos);
                continue;
            }

            // Now that we have a pertainym attachment, figure out which sense of
            // the pertainym is most similar to this gloss
            Set<ISynset> candidatePertainymSynsets =
                WordNetUtils.getSynsets(dict, pertainymLemma, POS.NOUN);
            
            if (candidatePertainymSynsets.isEmpty())
                continue;

            
            double maxScore = 0;
            ISynset best = null;
            String bestGloss = null;
            for (ISynset candidate : candidatePertainymSynsets) {
                String wnExtendedGloss =
                    //WordNetUtils.getExtendedGloss(candidate);
                    //candidate.getGloss();
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(gloss, wnExtendedGloss);

                // System.out.printf("ITEM1: %s\nITEM2: %s\nscore: %f%n",
                //                   cleanedGloss, wnExtendedGloss, score);
                
                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                }
            }

            //System.out.printf("For %s, best: %s, sim: %f%n", e, best, maxScore);

            if (best == null)
                continue;
            
            // Check that this sense isn't already in WN as a pertainym to the
            // noun we're trying to link.
            List<ISynsetID> adjsForNoun =
                best.getRelatedSynsets(Pointer.PERTAINYM);

            // This is the easy case; just create a new synset that links
            if (adjsForNoun == null || adjsForNoun.isEmpty()) {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "no-pertainyms");
                r.set("noun_max_score", maxScore);
                ale.setOp(CrownOperations.Pertainym.class, r, best);
                return ale;
            }
            // This is the harder case where we have to figure out which of the
            // existing adjectives this lemma should be merged into
            else {
                double adjMaxScore = 0;
                ISynset bestAdj = null;

                for (ISynsetID sid : adjsForNoun) {
                    ISynset adj = dict.getSynset(sid);
                    String adjGloss =
                        WordNetUtils.getGlossWithoutExamples(adj);
                    double score = simFunc.compare(gloss, adjGloss);

                    // System.out.printf("ITEM1: %s\nITEM2: %s\nscore: %f%n",
                    //                   cleanedGloss, wnExtendedGloss, score);

                    if (maxScore < score) {
                        adjMaxScore = score;
                        bestAdj = adj;
                    }
                }

                if (bestAdj == null)
                    continue;

                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "pertainyms");
                r.set("noun_max_score", maxScore);
                r.set("adj_max_score", adjMaxScore);
                ale.setOp(CrownOperations.Synonym.class, r, bestAdj);
                return ale;                
            }
        }
        return null;        
    }
    
    /**
     *
     */
    private Duple<CrownOperations.Reason,ISynset> getEstimatedSynonym(
        List<String> synonyms, POS pos, String gloss) {

        
        Counter<ISynset> synsetCounts =
            new ObjectCounter<ISynset>();

        List<String> lemmasInWn = new ArrayList<String>();
        for (String lemma : synonyms) {
            // Get the WordNet sysnet if it exists
            Set<ISynset> senses = WordNetUtils.getSynsets(dict, lemma, pos);
            if (senses.isEmpty()) 
                continue;

            lemmasInWn.add(lemma);
            synsetCounts.countAll(senses);
                
            // Get the hypernyms of the synset and count their occurrence too
            for (ISynset synset : senses) {
                for (ISynsetID hyper 
                         : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
                    synsetCounts.count(dict.getSynset(hyper));
                }
            }
        }

        // Return null if we couldn't find any of the lemma's synonyms or
        // hyponyms in WordNet
        if (synsetCounts.items().isEmpty())
            return null;

        // If there was only one lemma in this list in WordNet, try comparing
        // the glosses for just that word to find a match
        if (lemmasInWn.size() == 1) {
            double maxScore = 0;
            ISynset best = null;
            String bestGloss = null;
            Set<ISynset> candidateSynonymSynsets =
                WordNetUtils.getSynsets(dict, lemmasInWn.get(0), pos);
            for (ISynset candidate : candidateSynonymSynsets) {
                String wnExtendedGloss =
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(gloss, wnExtendedGloss);
                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                }
            }
            if (best = null)
                return null;
            
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("relation_type", "synonym");
            r.set("heuristic", "single-synonym");
            r.set("max_score", maxScore);
            r.set("num_senses", candidateSynonymSynsets.size());
            return new Duple<CrownOperations.Reason,ISynset>(r, best);           
        }

        else {
            // Check for whether there were ties in the max
            ISynset mostFreq = synsetCounts.max();
            int mostFreqCount = synsetCounts.getCount(mostFreq);
            List<ISynset> ties = new ArrayList<ISynset>();
            for (ISynset syn : synsetCounts.items()) {
                int c = synsetCounts.getCount(syn);
                if (c == mostFreqCount)
                    ties.add(syn);
            }

            // If there was only one synset that had the maximum count, then we
            // report this
            if (ties.size() == 1) {

                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("relation_type", "synonym");
                r.set("heuristic", "unambiguous-max");
                r.set("count", mostFreqCount);
                return new Duple<CrownOperations.Reason,ISynset>(r, mostFreq);
            }
            // Otherwise, we try breaking ties between the synsets using gloss
            // similarity
            else {

                double maxScore = 0;
                ISynset best = null;
                String bestGloss = null;
                for (ISynset candidate : ties) {
                    String wnExtendedGloss =
                        WordNetUtils.getGlossWithoutExamples(candidate);
                    double score = simFunc.compare(gloss, wnExtendedGloss);
                    if (maxScore < score) {
                        maxScore = score;
                        best = candidate;
                        bestGloss = wnExtendedGloss;
                    }
                }
                if (best == null)
                    return null;
                
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("relation_type", "synonym");
                r.set("heuristic", "tied-synonyms");
                r.set("max_score", maxScore);
                return new Duple<CrownOperations.Reason,ISynset>(r, best);
            }
        }
    }


    private List<String> m(String gloss, String firstMatchingTerm) {
        
        // TODO: refactor into faster code (non-replaceAll) code
        if (gloss.endsWith("."))
            gloss = gloss.substring(0, gloss.length() - 1);
        String[] tokens = gloss
            .replaceAll(" [\\p{Punct}]+\\b", " ")
            .replaceAll("\\b[\\p{Punct}]+ ", " ").split("\\s+");
        int start = -1;
        for (int i = 0; i < tokens.length; ++i) {
            if (tokens[i].equals(firstMatchingTerm)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            System.out.printf("WARNING: Could not find  %s in %s%n",
                              firstMatchingTerm, Arrays.toString(tokens));
            return Collections.<String>singletonList(firstMatchingTerm);
        }
        
        List<String> candidates = new ArrayList<String>();

        if (start + 2 < tokens.length) {
            candidates.add(tokens[start] + " " + tokens[start+1]
                           + " " + tokens[start+2]);
        }

        if (start + 1 < tokens.length) 
            candidates.add(tokens[start] + " " + tokens[start+1]);
        if (start + 2 < tokens.length)
            candidates.add(tokens[start+1] + " " + tokens[start+2]);

        if (start + 2 < tokens.length) {
            // If all three parts of speech are Nouns, then order them in reverse
            boolean isNoun1 = WordNetUtils.isInWn(dict, tokens[start], POS.NOUN);
            boolean isNoun2 = WordNetUtils.isInWn(dict, tokens[start+1], POS.NOUN);
            boolean isNoun3 = WordNetUtils.isInWn(dict, tokens[start+2], POS.NOUN);

            if (isNoun1 && isNoun2 && isNoun3) {
                candidates.add(tokens[start+1]);
                candidates.add(tokens[start+1]);
                candidates.add(tokens[start]);
            }
            else if (isNoun1 && isNoun2) {
                candidates.add(tokens[start+1]);
                candidates.add(tokens[start]);
            }
            else {
                if (isNoun1) 
                    candidates.add(tokens[start]);
                if (isNoun2) 
                    candidates.add(tokens[start+1]);
                if (isNoun3)
                    candidates.add(tokens[start+2]);
            }
        }
        else if (start + 1 < tokens.length) {
            // If all three parts of speech are Nouns, then order them in reverse
            boolean isNoun1 = WordNetUtils.isInWn(dict, tokens[start], POS.NOUN);
            boolean isNoun2 = WordNetUtils.isInWn(dict, tokens[start+1], POS.NOUN);
            
            if (isNoun1 && isNoun2) {
                candidates.add(tokens[start+1]);
                candidates.add(tokens[start]);
            }
            else {
                candidates.add(tokens[start]);
                candidates.add(tokens[start+1]);
            }
        }
        else {
            candidates.add(tokens[start]);
        }      

        return candidates;
    }
    
    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }
    
}
