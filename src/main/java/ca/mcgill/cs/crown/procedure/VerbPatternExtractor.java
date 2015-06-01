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
public class VerbPatternExtractor implements EnrichmentProcedure {

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
    static final Pattern TO_VERB =
        Pattern.compile("^[tT]o (?:[a-z\\-]+ly )?([a-z][a-z\\-]+[a-z])");


    public VerbPatternExtractor(IDictionary dict,
                                SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.VERB))
            return null;
        
        // Avoid trying to add new senses for verbs already in WN.  This ends up
        // being very noisy due to the limited gloss.
        if (WordNetUtils.isInWn(dict, e.getLemma(), POS.VERB))
            return null;

        for (String gloss :
                 e.getAnnotations().get(CrownAnnotations.Glosses.class)) {

            Matcher m = TO_VERB.matcher(gloss);
            if (!m.find()) {
                //System.out.printf("Could not find %s in %s%n", TO_VERB, gloss);
                continue;
            }

            String hypernymLemma = m.group(1).replaceAll("[\\p{Punct}]+$", "");
            POS pos = POS.VERB;
            
            // Skip trying to attach senses for which we found they hypernym but
            // in which case that hypernym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, hypernymLemma, pos))
                continue;

            //System.out.println("SEARCHING FOR: " + hypernymLemma);
            
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
                    WordNetUtils.getExtendedGloss(candidate);
                double score = simFunc.compare(gloss, wnExtendedGloss);
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
            
            // Choose the most similar gloss.
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("heuristic", "unlinked-to-verb");
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
