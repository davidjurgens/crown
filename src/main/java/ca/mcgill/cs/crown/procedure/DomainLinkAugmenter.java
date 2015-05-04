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

import ca.mcgill.cs.crown.AugmentationProcedure;
import ca.mcgill.cs.crown.AnnotatedLexicalEntry;
import ca.mcgill.cs.crown.CrownAnnotations;
import ca.mcgill.cs.crown.CrownOperations;

import ca.mcgill.cs.crown.similarity.SimilarityFunction;

import ca.mcgill.cs.crown.util.WiktionaryUtils;
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s
 * unprocessed (raw) glosses to identify wikified texts that indicates a certain
 * word is the hypernym
 * 
 */
public class DomainLinkAugmenter implements AugmentationProcedure {

    /**
     * The set of {{context}} domain annotations that are to be excluded as
     * domains
     */
    private static final Set<String> EXCLUDED_DOMAINS 
        = new HashSet<String>();
    static {
        String[] domains = new String[] {
            "uncountable", "countable", "rare", "archaic", "transitive",
            "figurative", "slang", "humorous", "vulgar", "pejorative",
            "dated", "obsolete", "intransitive", "offensive", "colloquial",
        };
        EXCLUDED_DOMAINS.addAll(Arrays.asList(domains));
    }
    
    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;



    public DomainLinkAugmenter(IDictionary dict,
                              SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * TODO
     */
    @Override public void augment(AnnotatedLexicalEntry ale) {
        Map<String,String> rawGlosses =
            ale.getAnnotations().get(CrownAnnotations.RawGlosses.class);
        for (Map.Entry<String,String> e : rawGlosses.entrySet()) {
            String rawGloss = e.getKey();
            String cleanGloss = e.getValue();

            next_annotation:
            for (String annotation :
                     WiktionaryUtils.extractAnnotations(rawGloss)) {
                String[] cols = annotation.split("\\|");
                String tagType = cols[0].trim();
                if (tagType.equals("context")) {
                    if (cols.length < 2)
                        continue;

                    // Skip adding a domain term if the context is pointing to
                    // one of the non-domain-like annotations
                    for (int i = 1; i < cols.length; ++i) {
                        if (EXCLUDED_DOMAINS.contains(cols[i]))
                            continue next_annotation;
                    }

                    for (int i = 1; i < cols.length; ++i) {
                        String domain = cols[i];
                        // Skip things like "lang=en", which aren't domains
                        if (domain.contains("="))
                            continue;
                        // Sometimes columns are marked up with wiki stuff
                        domain = domain.replaceAll("[\\[\\]]", "");

                        Set<ISynset> domainSyns =
                            WordNetUtils.getSynsets(dict, domain, POS.NOUN);
                        if (domainSyns.isEmpty())
                            continue;

                        // If the domain term has only one sense, we can just
                        // link this term to it directly
                        if (domainSyns.size() == 1) {
                            CrownOperations.Reason r =
                                new CrownOperations.Reason(getClass());
                            r.set("heuristic", "single-sense");
                            ale.addOp(CrownOperations.DomainTopic.class, r,
                                      domainSyns.iterator().next());
                        }
                        // Otherwise, we need to figure out which sense of this
                        // domain is the right one
                        //
                        // NOTE: this is an ideal situation for ADW...
                        else {
                            ISynset best = null;
                            double highestSim = -1;
                            for (ISynset synset : domainSyns) {
                                String wnGloss = WordNetUtils
                                    .getExtendedGloss(synset);
                                double sim = simFunc
                                    .compare(cleanGloss, wnGloss);
                                if (sim > highestSim) {
                                    highestSim = sim;
                                    best = synset;
                                }
                            }
                            if (best == null)
                                continue;
                            CrownOperations.Reason r =
                                new CrownOperations.Reason(getClass());
                            r.set("heuristic", "gloss-similarity");
                            r.set("max_score", highestSim);
                            ale.addOp(CrownOperations.DomainTopic.class, r, best);
                        }
                    }
                }
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
