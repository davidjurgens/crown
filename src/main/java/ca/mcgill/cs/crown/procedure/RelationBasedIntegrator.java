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
import ca.mcgill.cs.crown.Relation;
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
public class RelationBasedIntegrator implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;    



    public RelationBasedIntegrator(IDictionary dict,
                                   SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        
        String gloss = e.getAnnotations().get(CrownAnnotations.Gloss.class);
        POS pos = e.getPos();

        List<Relation> relations =
            e.getAnnotations().get(CrownAnnotations.Relations.class);
      
        // Try getting a synonym operation first
        Set<String> synonyms = new HashSet<String>();
        for (Relation r : relations) {
            if (r.getType().equals(Relation.RelationType.SYNONYM))
                synonyms.add(r.getTargetLemma());
        }
        if (synonyms.size() > 0) {
            Duple<CrownOperations.Reason,ISynset> synonymOp =
                getEstimatedSynonym(e.getLemma(), synonyms, pos, gloss);
        
            if (synonymOp != null && synonymOp.y != null) {
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                ale.setOp(CrownOperations.Synonym.class,
                          synonymOp.x, synonymOp.y);
                return ale;
            }
        }

        
        return null;
    }

    /**
     *
     */
    private Duple<CrownOperations.Reason,ISynset> getEstimatedSynonym(
        String targetLemma, Set<String> synonyms, POS pos, String gloss) {

        
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
                // Do a sanity check that avoids attaching this Entry if its
                // lemma appears anywhere near the synonoyms.  This check
                // potentially has some false positives since we might avoid
                // putting the lemma somewhere valid (in which case it would
                // have more than would valid location) but is used to avoid
                // noisy integration
                if (WordNetUtils.isAlreadyInWordNet(dict, targetLemma,
                                               pos, synset)) {
                    return null;
                }

                for (ISynsetID hyper 
                         : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
                    ISynset hyperSyn = dict.getSynset(hyper);
                        if (WordNetUtils.isAlreadyInWordNet(dict, targetLemma,
                                                       pos, hyperSyn)) {
                            return null;
                        }
                    synsetCounts.count(hyperSyn);
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
            double maxScore = -1;
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
            
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("relation_type", "synonym");
            r.set("heuristic", "single-synonym");
            r.set("max_score", maxScore);
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

                double maxScore = -1;
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
                
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("relation_type", "synonym");
                r.set("heuristic", "tied-synonyms");
                r.set("max_score", maxScore);
                return new Duple<CrownOperations.Reason,ISynset>(r, best);
            }
        }
    }


    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }
    
}
