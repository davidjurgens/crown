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
 * glosses to identify TODO
 * 
 */
public class NounPatternExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;    

    /**
     * Matches things like TBD
     */
    static final Map<Pattern,String> PATTERN_TO_NAME =
        new HashMap<Pattern,String>();
    static {
        PATTERN_TO_NAME.put(Pattern.compile("^(?:[aA]|[aA]ny|[aA]n|[tT]he) ([a-z][a-z\\-]+[a-z]) (?:that|which)\\b"), "a-noun-that");
        PATTERN_TO_NAME.put(Pattern.compile("^(?:[aA]ny) of (?:[a-z][a-z\\-]+[a-z] )([a-z][a-z\\-]+[a-z]) (?:that|which)\\b"), "any-of-noun-that");
        PATTERN_TO_NAME.put(Pattern.compile("^(?:[aA]|[aA]ny|[aA]n|[tT]he) ([a-z][a-z\\-]+[a-z]),"), "a-noun,");
    }

    public NounPatternExtractor(IDictionary dict,
                                SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.NOUN))
            return null;
        
        // Avoid trying to add new senses for nouns already in WN.  This ends up
        // being very noisy due to the limited gloss.
        if (WordNetUtils.isInWn(dict, e.getLemma(), POS.NOUN))
            return null;

        for (String gloss :
                 e.getAnnotations().get(CrownAnnotations.Glosses.class)) {

            for (Map.Entry<Pattern,String> me : PATTERN_TO_NAME.entrySet()) {
                Matcher m = me.getKey().matcher(gloss);
                String heuristicName = me.getValue();
                
                
                if (m.find()) {
                    String firstMatchingTerm =
                        m.group(1); //.replaceAll("[\\p{Punct}]+$", "").trim();

                    
                    List<String> candidates = GlossUtils.extractNounCandidates(
                        dict, gloss, firstMatchingTerm, m.start(1));

                    // System.out.printf("%s ==> %s%n", gloss, candidates);
                    AnnotatedLexicalEntry ale =
                        findAttachment(candidates, e, gloss, heuristicName);
                    if (ale != null)
                        return ale;                
                }


            
                // // Skip trying to attach senses for which we found they hypernym
                // // but in which case that hypernym wasn't in WN/CROWN.
                // if (!WordNetUtils.isInWn(dict, hypernymLemma, pos))
                //     continue;
                
                // Now that we have a hypernym attachment, figure out which sense of
                // // the hypernym is most similar to this gloss
                // Set<ISynset> candidateHypernymSynsets =
                //     WordNetUtils.getSynsets(dict, hypernymLemma, pos);

                // if (candidateHypernymSynsets.isEmpty())
                //     continue;
                
                // double maxScore = -1;
                // ISynset best = null;
                // String bestGloss = null;
                // for (ISynset candidate : candidateHypernymSynsets) {
                //     String wnExtendedGloss =
                //         WordNetUtils.getExtendedGloss(candidate);
                //     double score = simFunc.compare(gloss, wnExtendedGloss);
                //     if (maxScore < score) {
                //         maxScore = score;
                //         best = candidate;
                //         bestGloss = wnExtendedGloss;
                //     }
                // }
                
                // // Check that this sense isn't already in WN near where we're trying
                // // to put it
                // if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(),
                //                                     pos, best)) {
                //     continue;
                // }
                
                // // Choose the most similar gloss.
                // AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                // CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                // r.set("heuristic", me.getValue());
                // r.set("max_score", maxScore);
                // ale.setOp(CrownOperations.Hypernym.class, r, best);
                // return ale;
            }
        }
        return null;
    }

    private AnnotatedLexicalEntry findAttachment(List<String> candidateHypers,
                                                 LexicalEntry e,
                                                 String cleanedGloss,
                                                 String heuristic) {
        for (String hypernymLemma : candidateHypers) {
            POS pos = POS.NOUN;
            
            // Skip trying to attach senses for which we found they hypernym but
            // in which case that hypernym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, hypernymLemma, pos)) {
                // System.out.printf("%s (%s) is not in WN%n", hypernymLemma, pos);
                continue;
            }

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
                    //WordNetUtils.getExtendedGloss(candidate);
                    //candidate.getGloss();
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(cleanedGloss, wnExtendedGloss);

                // System.out.printf("ITEM1: %s\nITEM2: %s\nscore: %f%n",
                //                   cleanedGloss, wnExtendedGloss, score);
                
                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                }
            }

            //System.out.printf("For %s, best: %s, sim: %f%n", e, best, maxScore);
            
            // Check that this sense isn't already in WN near where we're trying
            // to put it
            if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(), pos,best)) {
                continue;
            }
           
            // Choose the most similar gloss.
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("heuristic", heuristic);
            r.set("max_score", maxScore);
            ale.setOp(CrownOperations.Hypernym.class, r, best);
            return ale;
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
