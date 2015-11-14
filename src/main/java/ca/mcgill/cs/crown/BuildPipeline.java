/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import edu.mit.jwi.IDictionary;

import ca.mcgill.cs.crown.similarity.SimilarityFunction;


public class BuildPipeline implements EnrichmentProcedure {

    private final List<EnrichmentProcedure> procedures;

    private final List<AugmentationProcedure> augmentationProcedures;    

    private IDictionary dictionary;

    private SimilarityFunction simFunc;
    
    public BuildPipeline() {
        procedures = new ArrayList<EnrichmentProcedure>();
        augmentationProcedures = new ArrayList<AugmentationProcedure>();
        simFunc = null;
    }

    public BuildPipeline(Collection<EnrichmentProcedure> procedures) {
        this.procedures = new ArrayList<EnrichmentProcedure>(procedures);
        augmentationProcedures = new ArrayList<AugmentationProcedure>();
        simFunc = null;
    }
    
    public void add(EnrichmentProcedure ep) {
        // Avoid recursive adding
        if (ep == this) {
            // TODO: logging
            return;
        }
        procedures.add(ep);
    }

    public void add(AugmentationProcedure ap) {
        // Avoid recursive adding
        if (ap == this) {
            // TODO: logging
            return;
        }
        augmentationProcedures.add(ap);
    }
    
    /**
     * Returns the current list of enrichment procedures in the order in which
     * they will be used to integrate new {@link LexicalEntry} instances.
     */
    public List<EnrichmentProcedure> getProcedures() {
        return procedures;
    }

    public IDictionary getDictionary() {
        return dictionary;
    }    
    
    @Override public void setDictionary(IDictionary dictionary) {
        for (EnrichmentProcedure ep : procedures)
            ep.setDictionary(dictionary);
        for (AugmentationProcedure ap : augmentationProcedures)
            ap.setDictionary(dictionary);
        
        this.dictionary = dictionary;
    }

    public void setSimilarityFunction(SimilarityFunction simFunc) {
        this.simFunc = simFunc;
    }

    public SimilarityFunction getSimilarityFunction() {
        return simFunc;
    }
    
    // /**
    //  * Applies each of the {@link EnrichmentProcedure} instances in order and
    //  * returns the first non-{@code null} {@code AnnotatedLexicalEntry}
    //  * integration provided by a procedure.
    //  */
    // public void augment(AnnotatedLexicalEntry ale) {
    //     for (AugmentationProcedure ap : augmentationProcedures) {
    //         ap.augment(ale);
    //     }
    // }
    
    /**
     * Applies each of the {@link EnrichmentProcedure} instances in order and
     * returns the first non-{@code null} {@code AnnotatedLexicalEntry}
     * integration provided by a procedure.
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        for (EnrichmentProcedure ep : procedures) {
            AnnotatedLexicalEntry integration = ep.integrate(e);
            //System.out.printf("%s on %s: %s%n", ep, e, integration);
            if (integration != null) {
                for (AugmentationProcedure ap : augmentationProcedures) {
                    ap.augment(integration);
                }                
                return integration;
            }
        }
        return null;
    }

    /**
     * Optimizes the order in which the pipeline's {@link EnrichmentProcedure}s
     * are applied in order to use the most accurate procedures first.
     */
    public void optimize(Set<AnnotatedLexicalEntry> labeledExamples) {

    }
    
}
