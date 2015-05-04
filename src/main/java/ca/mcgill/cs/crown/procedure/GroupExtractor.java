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
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s
 * unprocessed (raw) glosses to TODO.
 * 
 */
public class GroupExtractor implements EnrichmentProcedure {


        /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;
    
    /**
     * Matches things like "A[n]? group of [[noun]]\b"
     */
    static final Pattern A_GROUP_OF_NOUN =
        //Pattern.compile("\\b[A[n]? (configuration|group|type|version|kind) of (?:a|an|the)? ?\\[\\[([^\\]]+)\\]\\]");
        Pattern.compile("\\b(?:The|A[n]?) ([^\\s]+) of (?:a |an |the )?([a-zA-Z\\-]+)");



    private final Map<String,ISynset> groupTypeToSynset;

    
    public GroupExtractor(IDictionary dict,
                          SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
        groupTypeToSynset = new HashMap<String,ISynset>();
        loadGroupData();
    }


    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.NOUN))
            return null;

        for (String gloss :
                 e.getAnnotations().get(CrownAnnotations.Glosses.class)) {
            Matcher m = A_GROUP_OF_NOUN.matcher(gloss);
            if (!m.find())
                continue;

            String groupType = m.group(1);
            String meronym = m.group(2);

            ISynset hypernym = groupTypeToSynset.get(groupType);
            if (hypernym == null) {
                //CrownLogger.info(this + ": Unrecognized group type: " + groupType);
                continue;
            }

            // Add a pointer to appropriate hypernym synset too
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            ale.setOp(CrownOperations.Hypernym.class, r, hypernym);

            String restOfGloss = gloss.substring(m.end(2)).trim();
            List<String> meroCandidates = GlossUtils.extractNounCandidates(
                dict, gloss, meronym, m.start(1));

            found_meronym:
            for (String mero : meroCandidates) {
                Set<ISynset> meroSyns =
                    WordNetUtils.getSynsets(dict, mero, POS.NOUN);

                if (meroSyns.isEmpty()) {
                    continue;
                }

                // If there is any gloss left that we can compare against, try
                // measuring the string similarity to find a match
                if (restOfGloss.length() > 0) {
                    // See which of these we should link to
                    Duple<ISynset,Double> bestAndScore =
                        getMostSimilar(meroSyns, restOfGloss);
                    ISynset meroSyn = bestAndScore.x;
                    double highestSim = bestAndScore.y;
                    
                    // TODO: figure out which meronym type...
                    CrownOperations.Reason r2 =
                        new CrownOperations.Reason(getClass());
                    r2.set("heuristic", "most-similar");
                    r2.set("max_score", highestSim);
                    ale.addOp(CrownOperations.MemberMeronym.class, r2, meroSyn);
                    break found_meronym;                
                }
                // Otherwise, without any remaining gloss, just pick the first
                // sense of this meronym
                else {
                    ISynset meroSyn = meroSyns.iterator().next();

                    // TODO: figure out which meronym type...
                    CrownOperations.Reason r2 =
                        new CrownOperations.Reason(getClass());
                    r2.set("heuristic", "first-sense");
                    r2.set("comment", "no gloss to compare");
                    ale.addOp(CrownOperations.MemberMeronym.class, r2, meroSyn);
                    break found_meronym;
                }
            }

            return ale;            
        }
        return null;
    }

    private Duple<ISynset,Double> getMostSimilar(Collection<ISynset> syns,
                                                 String gloss) {
        ISynset best = null;
        double highestSim = -1;
        for (ISynset synset : syns) {
            String wnGloss = WordNetUtils.getGlossWithoutExamples(synset);
            double sim = simFunc.compare(gloss, wnGloss);
            if (sim > highestSim) {
                highestSim = sim;
                best = synset;
            }
        }

        if (best == null) {            
            for (ISynset synset : syns) {
                String wnGloss = WordNetUtils.getGlossWithoutExamples(synset);
                double sim = simFunc.compare(gloss, wnGloss);
                System.out.printf("ERROR CASE: %f for \"%s\" and \"%s\"%n",
                                  sim, gloss, wnGloss);
            }
            throw new AssertionError();
        }
        
        return new Duple<ISynset,Double>(best, highestSim);
    }
    
    // private AnnotatedLexicalEntry findAttachment(List<String> candidateHypers,
    //                                              String gloss) {
    //     for (String hypernymLemma : candidateHypers) {
    //              POS pos = POS.NOUN;
             
    //         // Skip trying to attach senses for which we found they hypernym but
    //         // in which case that hypernym wasn't in WN/CROWN.
    //         if (!WordNetUtils.isInWn(dict, hypernymLemma, pos))
    //             continue;

    //         // Now that we have a hypernym attachment, figure out which sense of
    //         // the hypernym is most similar to this gloss
    //         Set<ISynset> candidateHypernymSynsets =
    //             WordNetUtils.getSynsets(dict, hypernymLemma, pos);

    //         if (candidateHypernymSynsets.isEmpty())
    //             continue;



    //         double maxScore = -1;
    //         ISynset best = null;
    //         String bestGloss = null;
    //         for (ISynset candidate : candidateHypernymSynsets) {
    //             String wnExtendedGloss =
    //                 WordNetUtils.getExtendedGloss(candidate);
    //             double score = simFunc.compare(gloss, wnExtendedGloss);

    //             if (maxScore < score) {
    //                 maxScore = score;
    //                 best = candidate;
    //                 bestGloss = wnExtendedGloss;
    //             }
    //         }

    //         // Check that this sense isn't already in WN near where we're trying
    //         // to put it
    //         if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(), pos,best)) {
    //             continue;
    //         }

    //         // Choose the most similar gloss.
    //         AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
    //         CrownOperations.Reason r = new CrownOperations.Reason(getClass());
    //         r.set("heuristic", "group-noun");
    //         r.set("max_score", maxScore);
    //         ale.setOp(CrownOperations.Hypernym.class, r, best);
    //         return ale;
    //     }
    //     return null;
    // }    

    private void loadGroupData() {
        IIndexWord groupIw = dict.getIndexWord("group", POS.NOUN);
        // We want the first sense of "group"
        ISynsetID rootID = groupIw.getWordIDs().get(0).getSynsetID();
        ISynset root = dict.getSynset(rootID);

        groupTypeToSynset.clear();
        groupTypeToSynset.put("group", root);
                             

        List<ISynset> oneLevel = new ArrayList<ISynset>();
        for (ISynsetID sid : root.getRelatedSynsets(Pointer.HYPONYM)) {
            ISynset syn = dict.getSynset(sid);
            oneLevel.add(syn);
            for (IWord iw : syn.getWords()) {
                String lemma = iw.getLemma();

                ISynset firstSense =
                    WordNetUtils.getFirstSense(dict, lemma, POS.NOUN);
                if (firstSense != null && firstSense.equals(syn)) 
                    groupTypeToSynset.put(lemma, syn);
            }
        }

        List<ISynset> twoLevel = new ArrayList<ISynset>();
        for (ISynset descendant : oneLevel) {
            for (ISynsetID sid : descendant.getRelatedSynsets(Pointer.HYPONYM)) {
                ISynset syn = dict.getSynset(sid);
                twoLevel.add(syn);
                for (IWord iw : syn.getWords()) {
                    String lemma = iw.getLemma();
                    
                    ISynset firstSense =
                        WordNetUtils.getFirstSense(dict, lemma, POS.NOUN);
                    if (firstSense != null && firstSense.equals(syn)) 
                        groupTypeToSynset.put(lemma, syn);
                }
            }
        }

        for (ISynset descendant : twoLevel) {
            for (ISynsetID sid : descendant.getRelatedSynsets(Pointer.HYPONYM)) {
                ISynset syn = dict.getSynset(sid);
                for (IWord iw : syn.getWords()) {
                    String lemma = iw.getLemma();
                    
                    // Ensure this lemma's first sense is the derivative of group
                    // IIndexWord g = dict.getIndexWord(lemma, POS.NOUN);
                    // ISynsetID sid2 = g.getWordIDs().get(0).getSynsetID();
                    // ISynset syn2 = dict.getSynset(sid2);
                    // if (!syn.equals(syn2))
                    //     continue;

                    ISynset firstSense =
                        WordNetUtils.getFirstSense(dict, lemma, POS.NOUN);
                if (firstSense != null && firstSense.equals(syn)) 
                        groupTypeToSynset.put(lemma, syn);
                }
            }
        }       
    }
    
    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
        loadGroupData();
    }
}
