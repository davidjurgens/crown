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

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;

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
 */
public class AdverbExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    private static final Pattern ADV_MANNER_PATTERN = Pattern.compile(
        "\\bin a ([\\w]+) (manner|way)\\b", Pattern.CASE_INSENSITIVE);
    

    public AdverbExtractor(IDictionary dict,
                           SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.ADVERB))
            return null;
        
        // First check that this sense isn't in WN
        if (WordNetUtils.isInWn(dict, e.getLemma(), POS.ADVERB))
            return null;        

        Set<String> glosses =
            e.getAnnotations().get(CrownAnnotations.Glosses.class);

        // Try to find an adjective from which this adverb derives
        for (String gloss : glosses) {
            
            Matcher m = ADV_MANNER_PATTERN.matcher(gloss);
            if (!m.find())
                continue;

            String adj = m.group(1).replaceAll("[\\p{Punct}]+$", "");
            IIndexWord iw = dict.getIndexWord(adj, POS.ADJECTIVE);
            if (iw == null || iw.getWordIDs().size() == 0) 
                continue;
            
            // Easy case: this adjective has only one sense
            if (iw.getWordIDs().size() == 1) {
                ISynset synset = dict.getSynset(iw.getWordIDs().get(0).getSynsetID());
                // buildState.relate(sense, "derivationally-from-adjective",
                //                   synset, "pattern-based:adverb:single-sense");
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "single-sense");
                ale.setOp(CrownOperations.DerivedFromAdjective.class, r, synset);
                return ale;
            }
            // Harder case: we have to choose between the senses.  If we have
            // some additional context in the gloss (i.e., multiple subglosses),
            // then run ADW.  
            //
            // NOTE: add 2 to the match length to account for periods.
            else if (gloss.length() > m.end()+2 || glosses.size() > 1) {
                double maxSim = 0;
                ISynset best = null;
                
                String combGloss =
                    e.getAnnotations().get(CrownAnnotations.Gloss.class);


                for (IWordID wordId : iw.getWordIDs()) {                   
                    ISynset synset = dict.getSynset(wordId.getSynsetID());
                    String wnExtendedGloss =
                        WordNetUtils.getExtendedGloss(synset);
                    double sim = simFunc.compare(combGloss, wnExtendedGloss);
                    if (maxSim < sim) {
                        maxSim = sim;
                        best = synset;
                    }
                }

                // buildState.relate(sense, "derivationally-from-adjective",
                //                   best, "pattern-based:adverb:adw-sim");
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "gloss-similarity");
                r.set("max_score", maxSim);
                ale.setOp(CrownOperations.DerivedFromAdjective.class, r, best);
                return ale;
            }
            // Otherwise, just pick the first sense
            else {
                ISynset synset = dict.getSynset(iw.getWordIDs().get(0).getSynsetID());
                // buildState.relate(sense, "derivationally-from-adjective",
                //                   synset, "pattern-based:adverb:first-sense");
                //throw new Error();
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "first-sense");
                ale.setOp(CrownOperations.DerivedFromAdjective.class, r, synset);
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
