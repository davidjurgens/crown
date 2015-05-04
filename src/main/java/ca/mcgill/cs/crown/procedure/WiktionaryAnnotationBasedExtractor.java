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

import ca.mcgill.cs.crown.util.WiktionaryUtils;
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s glosses
 * 
 */
public class WiktionaryAnnotationBasedExtractor implements EnrichmentProcedure {

    enum Operation { LEXICALIZE, MERGE, ATTACH, SIBLING }

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    private final Map<String,Operation> annotationToOperation;

    public WiktionaryAnnotationBasedExtractor(IDictionary dict,
                                              SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
        annotationToOperation = new HashMap<String,Operation>();
        loadAnnotations();
    }

    private void loadAnnotations() {
        annotationToOperation.put("alternative spelling of",
                                  Operation.LEXICALIZE);
        
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

        String lemma = e.getLemma();
        POS pos = e.getPos();
        for (Map.Entry<String,String> av : annotationToValues.entrySet()) {

            String annotationType = av.getKey();
            String relatedTerm = av.getValue();

            // Test whether we can do something with this annotation
            Operation opType = annotationToOperation.get(annotationType);
            if (opType == null) {
                continue;
            }

            // If this annotaiton is indicating a lexicalization, then there's
            // no reason to look for a synset
            if (opType.equals(Operation.LEXICALIZE)) {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "lexicalize");
                r.set("annotation_type", annotationType);
                ale.addOp(CrownOperations.Lexicalization.class, r, relatedTerm);
                return ale;                
            }
            
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
            if (relatedSynset == null) {
                continue;
            }
            
            // There's nothing we can do here to link adverbs
            if (pos.equals(POS.ADVERB) && !opType.equals(Operation.MERGE)) {
                continue;
            }
            
            switch (opType) {
            case MERGE: {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "merge");
                r.set("annotation_type", annotationType);
                ale.setOp(CrownOperations.Synonym.class, r, relatedSynset);
                return ale;
            }
            case ATTACH: {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "attach");
                r.set("annotation_type", annotationType);
                // Adjectives don't have a heirarchy so instead we just link
                // everything as SimilarTo
                if (pos.equals(POS.ADJECTIVE)) 
                    ale.setOp(CrownOperations.SimilarTo.class, r, relatedSynset);
                else 
                    ale.setOp(CrownOperations.Hypernym.class, r, relatedSynset);
                return ale;
            }
            case SIBLING: {
                // Sibling case
                List<ISynsetID> hypers = relatedSynset
                    .getRelatedSynsets(Pointer.HYPERNYM);
                
                // Not all POS's have hypernyms, so we only include this
                // data if it exists
                if (!hypers.isEmpty()) {
                    ISynset parent = dict.getSynset(hypers.get(0));
                    AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                    CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                    r.set("heuristic", "sibling");
                    r.set("annotation_type", annotationType);
                    // Adjectives don't have a heirarchy so instead we just link
                    // everything as SimilarTo
                    if (pos.equals(POS.ADJECTIVE)) {
                        ale.setOp(CrownOperations.SimilarTo.class,
                                  r, relatedSynset);
                    }
                    else
                        ale.setOp(CrownOperations.Hypernym.class, r, parent);
                    return ale;                    
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
