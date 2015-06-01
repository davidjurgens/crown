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
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s glosses
 * to identify single-word definitions which indicate that the entry is a
 * synonym of that word.
 */
public class PersonPatternExtractor implements EnrichmentProcedure {

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    /**
     * The set of lemmas that are descendents of the person hypernym
     */
    private Set<String> personLemmas;

    private Map<ISynset,String> personSynsetToGloss;

    private ISynsetID personSynsetID;


    static final Pattern[] PEOPLE_PATTERNS = new Pattern[] {
        Pattern.compile("(?:one|someone|somebody)\\s+who\\s",
                        Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:a|an|the)\\s+([^\\s]+)\\s+(?:who|that)\\s",
                        Pattern.CASE_INSENSITIVE),
    };

    public PersonPatternExtractor(IDictionary dict,
                                  SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
        personLemmas = new HashSet<String>();
        personSynsetToGloss = new HashMap<ISynset,String>();
        loadPersonData();
    }

    /**
     * {@inheritDoc}
     */ 
    @Override public void setDictionary(IDictionary dictionary) {
        if (dictionary == null)
            throw new NullPointerException("dictionary cannot be null");
        this.dict = dictionary;
        personLemmas.clear();
        personSynsetToGloss.clear();
        loadPersonData();
    }

    /**
     * Parses the glosses for this entry looking for single-word definitions
     * and, if found, returns the annotation merging this entry into the
     * appropriate sense of the synonymous word.
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        if (!e.getPos().equals(POS.NOUN))
            return null;
        
        if (!isPerson(e))
            return null;

        // First check that this sense's lemma isn't already in WN somewhere as
        // a descendent of person
        Set<ISynset> alreadyThere =
            WordNetUtils.getSynsets(dict, e.getLemma(), POS.NOUN);
        for (ISynset syn : alreadyThere) {
            if (WordNetUtils.isDescendent(dict, syn, personSynsetID))
                return null;            
        }

        String combinedGloss =
            e.getAnnotations().get(CrownAnnotations.Gloss.class);


        // Next see if the gloss indicated the type of person
        Matcher personTypeMatcher = PEOPLE_PATTERNS[1].matcher(combinedGloss);
        if (personTypeMatcher.find()) {
            String personType = personTypeMatcher.group(1)
                .replaceAll("[\\p{Punct}]+$", "");

            // Skip the generic person since this is too low in the tree to be
            // really accurate
            if (!personType.equals("person")) {
                // By construction, the first sense of this lemma is a descendent of
                // person, so we can safely attach it to the first sense and be done
                IIndexWord iw = dict.getIndexWord(personType, POS.NOUN);
                if (iw != null) {
                    ISynset hypernym = dict.getSynset(iw.getWordIDs().get(0).getSynsetID());
                    //buildState.attach(sense, hypernym, "pattern-based:typed-person");

                    AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
                    CrownOperations.Reason r = new CrownOperations.Reason(getClass());
                    r.set("heuristic", "typed-person");
                    ale.setOp(CrownOperations.Hypernym.class, r, hypernym);
                    return ale;
                }
                else {
                    CrownLogger.info("No dict entry for %s", personType);
                }
            }
        }

        // Otherwise, we need to brute force the search over possible attachment
        // points
        ISynset best = null;
        double highestSim = 0;
        for (Map.Entry<ISynset,String> ent : personSynsetToGloss.entrySet()) {
            ISynset synset = ent.getKey();
            String wnGloss = ent.getValue();
            double sim = simFunc.compare(combinedGloss, wnGloss);
            if (sim > highestSim) {
                highestSim = sim;
                best = synset;
            }
        }

        if (best != null) {
            //buildState.attach(sense, best, "pattern-based:person\t" + highestSim);
            AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
            CrownOperations.Reason r = new CrownOperations.Reason(getClass());
            r.set("heuristic", "gloss-similarity");
            r.set("max_score", highestSim);
            ale.setOp(CrownOperations.Hypernym.class, r, best);
            return ale;
        }
        return null;
    }

    /**
     * Returns {@code true} if the lemma associated with the subdefs must be an
     * child of the person synset.
     */
    boolean isPerson(LexicalEntry e) {
        Set<String> glosses =
            e.getAnnotations().get(CrownAnnotations.Glosses.class);
        
        next_subdef:
        for (String subdef : glosses) {
            if (subdef.length() == 0)
                continue;

            for (Pattern p : PEOPLE_PATTERNS) {
                Matcher m = p.matcher(subdef);
                if (m.find()) {
                    // If this pattern has a matchable group, then it means we
                    // need to check that the group can be a person
                    if (m.groupCount() > 0) {
                        String possiblePerson = m.group(1)
                            .replaceAll("[\\p{Punct}]+$", "");
                        if (!personLemmas.contains(possiblePerson))
                            continue next_subdef;
                    }

                    // We know that the pattern is good, so start trying to
                    // attach this sense to descendant of person
                    return true;
                }
            }
        }

        return false;
    }


    /**
     * Returns the set of lemmas that can indicate a person.  This data is used
     * when testing whether a particular Wiktionary sense is referring to a person
     */
    private void loadPersonData() {

        IIndexWord personIw = dict.getIndexWord("person", POS.NOUN);
        // We want the first sense of "person"
        ISynsetID rootID = personIw.getWordIDs().get(0).getSynsetID();
        ISynset root = dict.getSynset(rootID);
        personLemmas.add("person");
        personLemmas.add("individual"); // hard code some notable exceptions
        personLemmas.add("somebody");   // hard code some notable exceptions
        personLemmas.add("someone");    // hard code some notable exceptions
        
        // BFS the hyponyms to get all person lemma forms
        Set<ISynsetID> frontier = new HashSet<ISynsetID>();
        Set<ISynsetID> next = new HashSet<ISynsetID>();
        Set<ISynsetID> visited = new HashSet<ISynsetID>(); // guard against
                                                           // weird loops
        frontier.addAll(root.getRelatedSynsets(Pointer.HYPONYM));       
        
        while (!frontier.isEmpty()) {

            visited.addAll(frontier);
            for (ISynsetID id : frontier) {
                ISynset syn = dict.getSynset(id);
                personSynsetToGloss.put(
                    syn, WordNetUtils.getGlossWithoutExamples(syn));
                
                for (IWord iw : syn.getWords()) {
                    // Only add the word if it is the person-descendent synset
                    // is the first sense of this word form
                    String lemma = iw.getLemma();

                    ISynset firstSense =
                        WordNetUtils.getFirstSense(dict, lemma, POS.NOUN);
                    assert firstSense != null
                        : "lemma in WN had null first sense";
                    if (firstSense.equals(syn)) {
                        personLemmas.add(lemma);
                    }
                }
            }

            for (ISynsetID id : frontier) {
                ISynset syn = dict.getSynset(id);
                for (ISynsetID hypo : syn.getRelatedSynsets(Pointer.HYPONYM)) {
                    if (!visited.contains(hypo))
                        next.add(hypo);
                }
                // for (ISynsetID hypo
                //          : syn.getRelatedSynsets(Pointer.HYPONYM_INSTANCE)) {
                //     if (!visited.contains(hypo))
                //         next.add(hypo);
                // }
            }

            frontier.clear();
            frontier.addAll(next);
            next.clear();
        }

        // CrownLogger.verbose("Saw %d lemmas for %d person synsets",
        //                   personLemmas.size(), personSynsetToGloss.size());
        this.personSynsetID = rootID;
    }
    
}
