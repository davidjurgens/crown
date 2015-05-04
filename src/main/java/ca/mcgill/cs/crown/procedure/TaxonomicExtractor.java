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
public class TaxonomicExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    public TaxonomicExtractor(IDictionary dict,
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

        Map<String,String> rawGlosses =
            e.getAnnotations().get(CrownAnnotations.RawGlosses.class);
        Set<String> glosses =
            e.getAnnotations().get(CrownAnnotations.Glosses.class);

        // Check the raw gloss first
        for (Map.Entry<String,String> g : rawGlosses.entrySet()) {
            AnnotatedLexicalEntry ae = extractTaxonomicAnnotation(e, g.getKey());
            if (ae != null)
                return ae;
        }
        
        String taxonomicGloss = null;
        for (String gloss : glosses) {
            if (gloss.startsWith("Any member of the ")) {
                taxonomicGloss = gloss;
                break;
            }
        }
        if (taxonomicGloss == null)
            return null;

        int i = "Any member of the ".length();
        // Find the first upper-case name after the prefix, which is the family
        // name
        for (; i < taxonomicGloss.length(); ++i) {
            if (Character.isUpperCase(taxonomicGloss.charAt(i)))
                break;
        }

        // Find the next word break after
        int j = taxonomicGloss.indexOf(' ', i+1);
        if (j < 0)
            j = taxonomicGloss.length();
        String genus = taxonomicGloss.substring(i, j);

        // Sometimes the family name is surrounded by quotes or other junk.
        // Strip that out.
        genus = genus.replaceAll("\\p{Punct}+", "").trim();

        // Sometimes we can't find it, which should probably be logged
        // for future special fixing
        if (genus.length() == 0) {
            CrownLogger.verbose("Unable to find taxon in taxonomic-like gloss: "
                              + taxonomicGloss);
            return null;
        }
        
        return attachGenus(e, genus);
    }


    /**
     *
     */
    private AnnotatedLexicalEntry extractTaxonomicAnnotation(LexicalEntry e,
                                                             String rawGloss) {
        for (String annotation : WiktionaryUtils.extractAnnotations(rawGloss)) {
            String[] cols = annotation.split("\\|");
            String tagType = cols[0].trim();
            if (tagType.equals("taxlink")) {
                if (cols.length < 2)
                    continue;
                String genus = cols[1];
                return attachGenus(e, genus);
            }
        }
        return null;
    }       
    
    private AnnotatedLexicalEntry attachGenus(LexicalEntry e, String genus) {

        // Get the WordNet sysnet for the genus if it exists
        IIndexWord genusIw = dict.getIndexWord(genus, POS.NOUN);

        // This genus isn't in WordNet, so give up early
        if (genusIw == null)
            return null;
        
        // Get the first synset, though we should probably check for
        // multiple synsets (genuses are supposed to be unique?)
        ISynset genusSyn = dict.getSynset(genusIw.getWordIDs()
                                          .get(0).getSynsetID());
                
        // The genus will usually have several member meronyms, one of
        // which is an instance of the organism in that instance.  Once
        // we find that, grab it's parent and report that.
        List<ISynsetID> members =
            genusSyn.getRelatedSynsets(Pointer.MERONYM_MEMBER);

        String lemma = e.getLemma();
        POS pos = POS.NOUN;

        for (ISynsetID memberId : members) {

            // See if this synset is an organism
            ISynset memberSyn = dict.getSynset(memberId);
            if (isOrganism(memberSyn)) {
                // If so, report its parent
                
                List<ISynsetID> hypers = memberSyn
                    .getRelatedSynsets(Pointer.HYPERNYM);
                if (hypers.size() == 0) {
                    hypers = memberSyn
                        .getRelatedSynsets(Pointer.HYPERNYM_INSTANCE);
                }
                
                if (hypers.size() == 0)
                    continue;
                
                ISynset hypernym = dict.getSynset(hypers.get(0));               
                
                // Check that this lemma isn't already in WN
                // if (WordNetUtils.isAlreadyInWordNet(dict, lemma, pos, hypernym))
                //     continue;
                
                // buildState.attach(wiktSense, hypernym,
                //                   "pattern-based:genus");
                // buildState.relate(wiktSense, "member-meronym", 
                //                   hypernym, "pattern-based:genus");
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "genus");
                ale.setOp(CrownOperations.Hypernym.class, r, hypernym);
                ale.addOp(CrownOperations.MemberMeronym.class, r, genusSyn);                
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
    
    /**
     * Returns {@code true} if this synset is a descendent of the organism
     * synset
     */
    private boolean isOrganism(ISynset start) {

        IIndexWord organismIw = dict.getIndexWord("organism", POS.NOUN);        
        ISynset organismSyn = dict.getSynset(organismIw.getWordIDs()
                                             .get(0).getSynsetID());
        ISynsetID goal = organismSyn.getID();

        return WordNetUtils.isDescendent(dict, start, goal);
    }

}
