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
import ca.mcgill.cs.crown.EnrichmentProcedure;
import ca.mcgill.cs.crown.LexicalEntry;
import ca.mcgill.cs.crown.CrownAnnotations;

import ca.mcgill.cs.crown.similarity.SimilarityFunction;

import ca.mcgill.cs.crown.util.WiktionaryUtils;
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s glosses
 * 
 */
public class WikiAnnotationBasedExtractor implements EnrichmentProcedure {

    enum Operation { MERGE, ATTACH, SIBLING }

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    private final Map<String,Operation> annotationToOperation;

    public WikiAnnotationBasedExtractor(IDictionary dict,
                                        SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
        annotationToOperation = new HashMap<String,Operation>();
        loadAnnotations();
    }

    private void loadAnnotations() {
        // Merge-as-synonym
        annotationToOperation.put("abbreviation of", Operation.MERGE);
        annotationToOperation.put("synonym of", Operation.MERGE);
        annotationToOperation.put("short for", Operation.MERGE);                
        annotationToOperation.put("form of", Operation.MERGE);

        // Attach-as-hypernym + Note informality(?)        
        annotationToOperation.put("informal form of", Operation.ATTACH);
        annotationToOperation.put("euphemistic form of", Operation.ATTACH);
        annotationToOperation.put("euphemistic spelling of", Operation.ATTACH);
        annotationToOperation.put("diminutive of", Operation.ATTACH);
        
        // Attach-as-sibling
        annotationToOperation.put("feminine of", Operation.SIBLING);
        annotationToOperation.put("neuter of", Operation.SIBLING);
        annotationToOperation.put("masculine of", Operation.SIBLING);
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {

        List<String> annotations = new ArrayList<String>();
        Map<String,String> rawGlosses =
            e.getAnnotations().get(CrownAnnotations.RawGlosses.class);
        
        boolean hasOneWordGloss = rawGlosses.size() == 1;
        for (Map.Entry<String,String> g : rawGlosses.entrySet()) {
            annotations.addAll(WiktionaryUtils.extractAnnotations(g.getKey()));
            if (g.getValue().indexOf(' ') > 0)
                hasOneWordGloss = false;
        }

        Map<String,String> annotationToValues =
            WiktionaryUtils.extractAnnotationValues(annotations);

        
        System.out.printf("for %s,\n\tvalues:%s%n", e, annotationToValues);

        String lemma = e.getLemma();
        POS pos = e.getPos();
        for (Map.Entry<String,String> av : annotationToValues.entrySet()) {

            String annotationType = av.getKey();
            String relatedTerm = av.getValue();

            // Test whether we can do something with this annotation
            Operation opType = annotationToOperation.get(annotationType);
            if (opType == null)
                continue;
            
            // Double check that this lemma isn't already in WN somewhere near
            // the related word's senses
            Set<ISynset> relatedTermSynsets =
                WordNetUtils.getSynsets(dict, relatedTerm, pos);
            if (WordNetUtils.isAlreadyInWordNet(dict, lemma, pos,
                                                relatedTermSynsets)) {
                continue;
            }

            // In the event that the gloss is just a single word, the gloss
            // similarity is somewhat meaningless (i.e,. there's no
            // disambiguating context) so we simply use the first sense of the
            // lemma.  Otherwise, try searching for which sense is closest in
            // semantic similarity based on the glosses
            ISynset relatedSynset = (hasOneWordGloss)
                ? WordNetUtils.getFirstSense(dict, relatedTerm, pos)
                : findAttachment(relatedTermSynsets, e);
            
            // If we weren't able to figure out how to attach this sense, report
            // that so that we might pick it up on a second pass.
            if (relatedSynset == null) 
                continue;

            switch (opType) {
            case MERGE:
                throw new Error();
            case ATTACH:
                throw new Error();
            case SIBLING: {
                // Sibling case
                List<ISynsetID> hypers = relatedSynset
                    .getRelatedSynsets(Pointer.HYPERNYM);
                
                // Not all POS's have hypernyms, so we only include this
                // data if it exists
                if (!hypers.isEmpty()) {
                    ISynset parent = dict.getSynset(hypers.get(0));
                    throw new Error();
                }
            }
            }
        }
        return null;
    }

    private ISynset findAttachment(Set<ISynset> candidateAttachments,
                                   LexicalEntry e) {

        String combinedGloss =
            e.getAnnotations().get(CrownAnnotations.Gloss.class);
        double maxScore = -1;
        ISynset best = null;
        for (ISynset candidate : candidateAttachments) {
            String wnExtendedGloss = WordNetUtils.getExtendedGloss(candidate);
            double score = simFunc.compare(combinedGloss, wnExtendedGloss);
            if (maxScore < score) {
                maxScore = score;
                best = candidate;
            }
        }
        return best;
    }


    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }    
}
