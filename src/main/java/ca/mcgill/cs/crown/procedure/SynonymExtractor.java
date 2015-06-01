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
public class SynonymExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    public SynonymExtractor(IDictionary dict,
                            SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
    }

    /**
     * Parses the glosses for this entry looking for single-word definitions
     * and, if found, returns the annotation merging this entry into the
     * appropriate sense of the synonymous word.
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {

        String lemma = e.getLemma();
        POS pos = e.getPos();

        // Extract the set of definitions within this gloss that are a single
        // word, indicating that this entry's word is synonymous with that word.
        List<String> synonyms = new ArrayList<String>();
        int numTokens = 0;
        Set<String> glosses =
            e.getAnnotations().get(CrownAnnotations.Glosses.class);
                
        for (String gloss : glosses) {
            String[] cols = gloss.split(";");
            for (String subdef : cols) {
                subdef = subdef.trim();
                String[] tokens = subdef.split("\\s+");
                numTokens += tokens.length;
                if (tokens.length > 2)
                    continue;
                
                int index = 0;
                // Work around some two-word definitions where the other word is a
                // determiner or 'to' for a verb
                if (tokens.length == 2) {
                    String t = tokens[0].toLowerCase();
                    if (!(pos.equals(POS.NOUN) && (t.equals("the") || t.equals("a")
                                                   || t.equals("an"))
                          || (pos.equals(POS.VERB) && t.equals("to"))))
                        continue;
                    index = 1;
                }
                
                // Strip off any trailing periods or other noise that would prevent
                // us from matching the lemma with one in WordNet
                String syn = tokens[index].replace(".", "");
                if (syn.length() > 0
                    && !(syn.length() == 1 && syn.charAt(0) == '.')) {
                    if (syn.endsWith("."))
                        syn = syn.substring(0, syn.length() - 1);
                    synonyms.add(syn);
                }
            }
        }

        // If we couldn't find any definition of the word that was a single
        // word, then we can't treat this entry as a synonym of another word, so
        // give up.
        if (synonyms.isEmpty())
            return null;       
        
        // In the event that the gloss is just a single word, the gloss
        // similarity is somewhat meaningless (i.e,. there's no disambiguating
        // context) so we simply use the first sense of the lemma.
        if (synonyms.size() == 1 && numTokens == 1) {
            String synonym = synonyms.iterator().next();
            ISynset attachmentPoint =
                WordNetUtils.getFirstSense(dict, synonym, pos);

            if (attachmentPoint == null) 
                return null;
            if (WordNetUtils.isAlreadyInWordNet(dict, lemma, pos,
                                                 attachmentPoint))
                return null;

            // buildState.merge(sense, attachmentPoint,
            //                  "synonym:first-sense");
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("heuristic", "first-sense");
            ale.setOp(CrownOperations.Synonym.class, r, attachmentPoint);
            return ale;

        }

        // Otherwise, the sense has at least one definition longer than a single
        // word, so we identify all cnadidate attachment points and then use our
        // similarity function to see which of the synonym's synsets are most
        // similar.
        
        Set<ISynset> candidateAttachments =
            WordNetUtils.getSynsets(dict, synonyms, pos);
        
        // Strip out all proper names from potential attachment points, which
        // never seem to be correct attachments in any case we've seen.
        Iterator<ISynset> iter = candidateAttachments.iterator();
        while (iter.hasNext()) {
            ISynset syn = iter.next();
            List<IWord> words = syn.getWords();
            if (words.isEmpty()) {
                // System.out.println("Empty words?? " + syn);
                iter.remove();
            }
            else if (Character.isUpperCase(words.get(0).getLemma().charAt(0)))
                iter.remove();
        }
        
        if (WordNetUtils.isAlreadyInWordNet(dict, lemma, pos,
                                             candidateAttachments)) {
            return null;
        }

        String combinedGloss =
            e.getAnnotations().get(CrownAnnotations.Gloss.class);
        
        // ISynset attachmentPoint =
        //     findAttachment(candidateAttachments, combinedGloss);

        double maxScore = 0;
        ISynset best = null;
        String bestGloss = null;
        for (ISynset candidate : candidateAttachments) {
            String wnExtendedGloss = WordNetUtils.getExtendedGloss(candidate);
            double score = simFunc.compare(combinedGloss, wnExtendedGloss);
            // System.out.printf("\t%f\t%s\n", score, wnExtendedGloss);
            if (maxScore < score) {
                maxScore = score;
                best = candidate;
                bestGloss = wnExtendedGloss;
            }
        }

        if (best != null) {                
            // buildState.merge(sense, attachmentPoint,
            //                  "synonym:similarity");
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("heuristic", "similarity");
            r.set("max_score", maxScore);
            ale.setOp(CrownOperations.Synonym.class, r, best);
            return ale;
        }

        // Unable to find any attachment
        return null;
    }
    
    ISynset findAttachment(Set<ISynset> candidateAttachments,
                           String combinedGloss) {
        
        // System.out.printf("Similarity of %s, gloss: %s\n", sense, combinedGloss);
        double maxScore = -1;
        ISynset best = null;
        String bestGloss = null;
        for (ISynset candidate : candidateAttachments) {
            String wnExtendedGloss = WordNetUtils.getExtendedGloss(candidate);
            double score = simFunc.compare(combinedGloss, wnExtendedGloss);
            // System.out.printf("\t%f\t%s\n", score, wnExtendedGloss);
            if (maxScore < score) {
                maxScore = score;
                best = candidate;
                bestGloss = wnExtendedGloss;
            }
        }
        return (maxScore > 0) ? best : null;
    }

    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        this.dict = dictionary;
    }
}
