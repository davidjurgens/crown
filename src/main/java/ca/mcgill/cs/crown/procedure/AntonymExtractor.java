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

import ca.mcgill.cs.crown.util.WordNetUtils;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s glosses
 * to identify single-word definitions which indicate that the entry is a
 * synonym of that word.
 */
public class AntonymExtractor implements EnrichmentProcedure {

    static final String[] ANTONYM_PREFICES =
        new String[] { "anti", "non", "dis", "mal", "mis", "il", "un",
                       "in", "im", "de", "ir", };

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    public AntonymExtractor(IDictionary dict,
                            SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }   

    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }
    
    /**
     * Parses the glosses for this entry looking for single-word definitions
     * and, if found, returns the annotation merging this entry into the
     * appropriate sense of the synonymous word.
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {

        String lemma = e.getLemma();
        POS pos = e.getPos();
        //String gloss = e.glo
        
        // See if it could be antonym based on its prefix alone
        String antonym = prefixMatch(lemma, pos);

        // Try again with the noiser regex match
        if (antonym == null) 
            antonym = regexMatch(e);

        // Skip trying to attach lemmas that don't match antonym pattern
        if (antonym == null)
            return null;
            
        // Sometimes, WordNet will not have the antonym, in which case, we just
        // skip the sense and hope that some future iteration of the CROWN will
        // put this antonym in place for us
        IIndexWord antonymIw = dict.getIndexWord(antonym, pos);
        if (antonymIw == null)
            return null;
                 
        double highestSim = -1;
        ISynset bestMatch = null;

        // If the antonym lemma only has one sense, so don't bother doing
        // anything fancy.  This actually helps a lot when we're antonyms
        // with a monosemous lemma and the glosses don't overlap at all.
        if (antonymIw.getWordIDs().size() == 1) {
            bestMatch = dict.getSynset(
                antonymIw.getWordIDs().get(0).getSynsetID());
        }
        // If the wiktionary gloss is just a single content word (excluding all
        // the antonym indicating content), the text-based similarity measures
        // are meaningless, so we just attach based on the first sense
        else if (isSingleWordDef(e)) {
            bestMatch = WordNetUtils.getFirstSense(dict, antonym, e.getPos());
            // List<IWordID> wids = antonymIw.getWordIDs();
            // if (wids.size() == 0)
            //     return null;
            // ISynsetID synsetId = wids.get(0).getSynsetID();
            // bestMatch = dict.getSynset(synsetId);
            //System.out.println("BestMatch: " + bestMatch);
        }
        // Otherwise, use the similarity function to try to pick which sense
        // we're antonyms with.
        else {
            String combinedGloss =
                e.getAnnotations().get(CrownAnnotations.Gloss.class);
           
            for (ISynset synset
                     : WordNetUtils.getSynsets(dict, antonym, e.getPos())) {

                 String extGloss = WordNetUtils.getExtendedGloss(synset);
                
                 double sim = simFunc.compare(combinedGloss, extGloss);
                 
                 if (sim > highestSim) {
                     highestSim = sim;
                     bestMatch = synset;
                }
            }
            
        }

        //System.out.printf("Sim of %s for %s to %s%n", highestSim, antonym, bestMatch);
        
        // If we were able to find a matching antonym synset, then see if
        // that sense has an antonym.  If so, we merge this lemma with the
        // antonym's antonym.  Otherwise, we attach this lemma as a new
        // hypernym of the antonym's parent.
        if (bestMatch != null) {
            ISynset antonymSynset = bestMatch;
            
            List<ISynsetID> antonymsAntonyms = antonymSynset
                .getRelatedSynsets(Pointer.ANTONYM);
            // We found the antonym's antonym, so merge our lemma into this
            // synset.
            if (antonymsAntonyms.size() > 0) {
                ISynset toMergeInto =
                    dict.getSynset(antonymsAntonyms.get(0));
                
                // Check that this lemma doesn't already exist
                if (!WordNetUtils.isAlreadyInWordNet(
                        dict, lemma, pos, toMergeInto)) {
                    // buildState.merge(sense, toMergeInto,
                    //                  "antonym:reverse-lookup");

                    AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                    CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                    r.set("heuristic", "reverse-lookup");
                    ale.setOp(CrownOperations.Synonym.class, r, toMergeInto);
                    return ale;
                }
            }
            // No match, so attach to the parent (if it exists) and record
            // that there is an antonym relation between this sense and the
            // identified antonym
            else {
                List<ISynsetID> hypers = antonymSynset
                    .getRelatedSynsets(Pointer.HYPERNYM);
                
                AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                r.set("heuristic", "new-antonym");

                // Not all POS's have hypernyms, so we only include this
                // data if it exists
                if (!hypers.isEmpty()) {
                    ISynset parent = dict.getSynset(hypers.get(0));
                    
                    if (!WordNetUtils.isAlreadyInWordNet(
                            dict, lemma, pos, parent)) {
                        ale.setOp(CrownOperations.Hypernym.class, r, parent);
                    }
                    else {
                        // Avoid duplicates!
                        return null;
                    }
                }
                // If we're not going to assign it a hypernym, then it must be
                // and adjective or adverb.
                else if (!(pos.equals(POS.ADJECTIVE) || pos.equals(POS.ADVERB)))
                    return null;
                        
                ale.setOp(CrownOperations.Antonym.class, r, antonymSynset);
                return ale;
            }
        }
        
        return null;
    }

    private String prefixMatch(String lemma, POS pos) {
            String stem = null;
            for (String prefix : ANTONYM_PREFICES) {
                if (lemma.startsWith(prefix)) {
                    stem = lemma.substring(prefix.length());
                    break;
                }
            }

            if (stem == null) {
                // if
                if (pos.equals(POS.VERB)) {
                    String anto = null;

                    // See if we can match it with an over/under
                    if (lemma.startsWith("over")) {
                        anto = "under" + lemma.substring(4);
                    }
                    else if (lemma.startsWith("super")) {
                        anto = "under" + lemma.substring(5);
                    }
                    else if (lemma.startsWith("under")) {
                        anto = "super" + lemma.substring(5);
                        if (!WordNetUtils.isInWn(dict, anto, POS.VERB))
                            anto = "super" + lemma.substring(5);
                    }

                    if (anto != null) {
                        if (WordNetUtils.isInWn(dict, anto, POS.VERB)) {
                            //System.out.printf("ANTO: %s <-> %s%n", lemma, anto);
                            return anto;
                        }
                    }
                }
                // continue next_sense;
            }
            return null;
    }

    static final Pattern[] NOUN_ANTONYM_PATTERNS = new Pattern[] {
        Pattern.compile("(?:^|;)\\s*not [an ]{0,2}([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*absence of ([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*lack of ([\\w]+)\\b"),
    };

    static final Pattern[] VERB_ANTONYM_PATTERNS = new Pattern[] {
        Pattern.compile("(?:^|;)\\s*to not ([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*to ([\\w]+) incorrectly\\b"),
        Pattern.compile("(?:^|;)\\s*to ([\\w]+) badly\\b"),
        Pattern.compile("(?:^|;)\\s*to ([\\w]+) wrongly\\b"),
        Pattern.compile("(?:^|;)\\s*to ([\\w]+) improperly\\b"),        
    };

    static final Pattern[] MODIFIER_ANTONYM_PATTERNS = new Pattern[] {
        Pattern.compile("(?:^|;)\\s*not ([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*opposing ([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*opposed to ([\\w]+)\\b"),
        Pattern.compile("(?:^|;)\\s*not capable of ([\\w]+)\\b"),
    };


    static final Map<POS,Pattern[]> posToPatterns = new HashMap<POS,Pattern[]>();
    static {
        posToPatterns.put(POS.NOUN, NOUN_ANTONYM_PATTERNS);
        posToPatterns.put(POS.VERB, VERB_ANTONYM_PATTERNS);
        posToPatterns.put(POS.ADJECTIVE, MODIFIER_ANTONYM_PATTERNS);
        posToPatterns.put(POS.ADVERB, MODIFIER_ANTONYM_PATTERNS);
    }

    static final Pattern TRAILING_PUNCT = Pattern.compile("[\\p{Punct}]+$");

    private String regexMatch(LexicalEntry e) {

        Pattern[] patterns = posToPatterns.get(e.getPos());
        if (patterns == null) 
            return null;       

        for (Pattern p : patterns) {
            for (String subdef : e.getAnnotations()
                     .get(CrownAnnotations.Glosses.class)) {
                for (String superSubdef : subdef.split(",")) {
                    superSubdef = TRAILING_PUNCT.matcher(superSubdef).replaceAll("");

                    Matcher m = p.matcher(superSubdef);
                    if (m.find()) {
                        // Check antonym is in WN
                        String antonym = m.group(1);
                        // System.out.printf("Found %s in %s%n", antonym, superSubdef);
                        
                        if (WordNetUtils.isInWn(dict, antonym, e.getPos()))
                            return antonym;
                    }
                    else {
                        // System.out.printf("No match of %s in %s%n", p, superSubdef);
                    }
                }
            }
        }        
        return null;
    }

    /**
     * Returns true if this antonym's definition consists only of a single
     * content word (e.g., "not a [x]")
     */
    static boolean isSingleWordDef(LexicalEntry e) {
        Set<String> glosses =
            e.getAnnotations().get(CrownAnnotations.Glosses.class);
        return glosses.size() == 1
            && glosses.iterator().next().indexOf(',') < 0;
        // String[] subdefs = gloss.split(";");
        // if (subdefs.length > 1)
        //     return false;
        // else
        //     return subdefs[0].split(",").length == 1;
    }
}
