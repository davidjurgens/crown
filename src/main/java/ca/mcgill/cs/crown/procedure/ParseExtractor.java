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
import ca.mcgill.cs.crown.CrownAnnotations;
import ca.mcgill.cs.crown.CrownOperations;
import ca.mcgill.cs.crown.EnrichmentProcedure;
import ca.mcgill.cs.crown.LexicalEntry;

import ca.mcgill.cs.crown.similarity.SimilarityFunction;

import ca.mcgill.cs.crown.util.WiktionaryUtils;
import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;

import edu.stanford.nlp.util.*;
import edu.stanford.nlp.semgraph.*;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;


/**
 * An {@link EnrichmentProcedure} that examines a {@link LexicalEntry}'s glosses
 * using parsing to find possible matches
 * 
 */
public class ParseExtractor implements EnrichmentProcedure {

    // We use this pattern with Heuristic 3 to replace instances of "one who
    // ..." with the candidate for "person".  We need the pattern to guard
    // against weird puncutation (it happens :(  )
    private static final Pattern WHO = Pattern.compile("\\bwho\\b");
    

    /**
     * The similarity function used to compare the glosses of entries.
     */
    private final SimilarityFunction simFunc;

    /**
     * The CoreNLP pipeline used to parse glosses
     */
    private final StanfordCoreNLP pipeline;

    /**
     * The dictionary into which entries are to be integrated.
     */
    private IDictionary dict;

    
    public ParseExtractor(IDictionary dict,
                          SimilarityFunction simFunc) {
        this.dict = dict;
        this.simFunc = simFunc;
        java.util.Properties props = new java.util.Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma, parse");
        pipeline = new StanfordCoreNLP(props);        
    }

    /**
     * TODO
     */
    public AnnotatedLexicalEntry integrate(LexicalEntry e) {
        MultiMap<String,String> lemmaToHeuristics =
            getCandidateHypernyms(e);

        // System.out.printf("For %s.%s, found %d lemmas%n", e.getLemma(), e.getPos(),
        //                   lemmaToHeuristics.size());
        
        if (lemmaToHeuristics.isEmpty())
            return null;

        POS pos = e.getPos();
        String entGloss = e.getAnnotations().get(CrownAnnotations.Gloss.class);

        double maxScore = 0;
        ISynset best = null;
        String bestGloss = null;
        Set<String> bestHeuristics = null;
        int numSynsets = 0;
        
        // Get the list of candidate synsets
        for (Map.Entry<String,Set<String>> me
                 : lemmaToHeuristics.asMap().entrySet()) {
            
            String relatedLemma = me.getKey();
            Set<String> generatingHeuristics = me.getValue();
            
            // Skip trying to attach senses for which we found they hypernym but
            // in which case that hypernym wasn't in WN/CROWN.
            if (!WordNetUtils.isInWn(dict, relatedLemma, pos)) 
                continue;            

            // Now that we have a hypernym attachment, figure out which sense of
            // the hypernym is most similar to this gloss
            Set<ISynset> candidateHypernymSynsets =
                WordNetUtils.getSynsets(dict, relatedLemma, pos);
            
            if (candidateHypernymSynsets.isEmpty())
                continue;

            numSynsets += candidateHypernymSynsets.size();

            // System.out.printf("\t For %s.%s, checking the %d senses of " +
            //                   "candidate relation %s%n",
            //                   e.getLemma(), e.getPos(),
            //                   candidateHypernymSynsets.size(), relatedLemma);
            
            for (ISynset candidate : candidateHypernymSynsets) {

                // Check that this sense isn't already in WN near where we're
                // trying to put it
                if (WordNetUtils.isAlreadyInWordNet(dict, e.getLemma(),
                                                    pos, best))
                    continue;
                
                String wnExtendedGloss =
                    WordNetUtils.getGlossWithoutExamples(candidate);
                double score = simFunc.compare(entGloss, wnExtendedGloss);

                if (maxScore < score) {
                    maxScore = score;
                    best = candidate;
                    bestGloss = wnExtendedGloss;
                    bestHeuristics = generatingHeuristics;
                }
            }            
        }           

        if (best == null)
            return null;

        // Choose the most similar gloss.
        AnnotatedLexicalEntry ale = new AnnotatedLexicalEntryImpl(e);
        CrownOperations.Reason r = new CrownOperations.Reason(getClass());
        r.set("heuristic", String.join(",", bestHeuristics));
        r.set("max_score", maxScore);
        if (pos.equals(POS.ADJECTIVE))
            ale.setOp(CrownOperations.SimilarTo.class, r, best);
        // NOTE: it would probably be worth revisiting to see if adding in
        // Adverbs from this procedure is worthwhile
        else if (pos.equals(POS.ADVERB))
            ale.setOp(CrownOperations.Synonym.class, r, best);
        else 
            ale.setOp(CrownOperations.Hypernym.class, r, best);

        return ale;
    }
    
    private MultiMap<String,String> getCandidateHypernyms(LexicalEntry e) {
        
        // The set of lemmas whose synsets are  candidate hypernyms
        MultiMap<String,String> candidates =
            new HashMultiMap<String,String>();
        
        Map<String,String> rawGlosses =
            e.getAnnotations().get(CrownAnnotations.RawGlosses.class);

        StringBuilder sb = new StringBuilder();
        
        char sensePos = toChar(e.getPos());
        

        for (Map.Entry<String,String> g : rawGlosses.entrySet()) {

            // When looking at the raw gloss, strip out any leading annotations
            String rawGloss = WiktionaryUtils.stripAnnotations(g.getKey());

            // Strip out the quotation marks of the raw gloss too
            rawGloss = rawGloss.replaceAll("[']{2,}", "").trim();
            
            String cleanedGloss = g.getValue();
            
            // Parse the subdefintion
            Annotation document = new Annotation(cleanedGloss);
            pipeline.annotate(document);
            List<CoreMap> sentences = document.get(SentencesAnnotation.class);
            
            // In some rare cases, a subdefinition could had multiple sentences.
            // We use them all, though this should probably be analyzed
            for(CoreMap sentence: sentences) {

                // for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                //     String word = token.get(TextAnnotation.class);
                //     String pos = token.get(PartOfSpeechAnnotation.class);
                // }
                
                // Get the dependency parsed tree
                Tree tree = sentence.get(TreeAnnotation.class);
                SemanticGraph dependencies = sentence.get(
                    CollapsedCCProcessedDependenciesAnnotation.class);


                MultiMap<String,String> cands =
                    getCandidates(dependencies, cleanedGloss, e.getPos());
                candidates.putAll(cands);
                
                // Once we have the set of candidates, try to clean up some
                // errors where we have picked a word that is part of a longer
                // [[linked expression]] but not the full thing.
                int i = rawGloss.indexOf("[[");
                while (i >= 0) {
                    int j = rawGloss.indexOf("]]", i);
                    if (j < i)
                        break;
                    String text = rawGloss.substring(i+2, j);
                    
                    // See if this is a compound term
                    if (text.indexOf(' ') <= 0) {
                        i = rawGloss.indexOf("[[", i + 1);
                        continue;
                    }
                    
                    // If so, first see if we managed to extract it and if not,
                    // see if we have any of its terms as candidates
                    if (!cands.containsKey(text)) {
                        String[] tokens = text.split("\\s+");
                        for (String tok : tokens) {
                            if (cands.keySet().contains(tok)) {
                                candidates.put(text, "wiki MWE expansion");
                                break;
                            }
                        }
                    }
                    
                    i = rawGloss.indexOf("[[", i + 1);
                }               
            }
        }

        return candidates;
    }


    /** 
     * Gets the candidate hypernyms form the provided subdef
     *
     * @returns a mapping from the candidate to the heuristics that generated it
     */
    MultiMap<String,String> getCandidates(SemanticGraph dependencies,
                                          String subdef, POS spos_) {

        MultiMap<String,String> candidates =
            new HashMultiMap<String,String>();
        char sensePos = toChar(spos_);
        
        Collection<IndexedWord> roots = dependencies.getRoots();
        next_root:
        for (IndexedWord root : roots) {
            String word = root.get(TextAnnotation.class);
            String lm = root.get(LemmaAnnotation.class);
            String pos = root.get(PartOfSpeechAnnotation.class);
            char lemmaPos = pos.substring(0,1).toLowerCase().charAt(0);

            // null check
            String lemma = (lm != null) ? lm.toLowerCase() : "";

            //System.out.println("testing: " + lemma + "/" + pos);

            // If the lemma is a verb, check for phrasal verbal particle (e.g.,
            // "lead on", "edge out") and if present, add them to the lemma
            if (lemmaPos == 'v') {
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                    if (e.getRelation().getShortName().equals("prt")) {
                        IndexedWord dep = e.getDependent();
                        lemma = lemma + " " + dep.get(LemmaAnnotation.class);
                        break;
                    }
                }
            }

            
            // Heuristic 1: root matches exact POS
            if (lemmaPos == sensePos) {

                // Edge case for Heuristics 7: If the lemma is a noun and is
                // saying that this is an instance (e.g., "An instance of ..."),
                // then we take the dependent noun from instance
                //
                // Terrible example:
                //   The second of the two Books of Chronicles and the
                //   fourteenth book of the Old Testament of the Bible.
                //
                boolean foundExistentialDependent = false;
                if (lemma.equals("instance") || lemma.equals("example")
                    || lemma.equals("first") || lemma.equals("second")
                    || lemma.equals("third") || lemma.equals("fourth")
                    || lemma.equals("fifth") || lemma.equals("sixth")
                    || lemma.equals("series"))
                    {
                    // Check that there's actually a prepositional phrase
                    // attached
                    List<SemanticGraphEdge> edges =
                        dependencies.outgoingEdgeList(root);
                   
                    for (SemanticGraphEdge e : edges) {
                        if (e.getRelation().getShortName().equals("prep")) {
                            IndexedWord dep = e.getDependent();
                            String depLemma = dep.get(LemmaAnnotation.class);
                            char depPos = dep.get(PartOfSpeechAnnotation.class)
                                .substring(0,1).toLowerCase().charAt(0);

                            //System.out.println("HEURISTIC 7");
                            if (depPos == sensePos) {
                                candidates.put(depLemma, "Heuristic-7");
                                addSiblings(dep, candidates,
                                            sensePos, dependencies,
                                            "Heuristic-7");
                                foundExistentialDependent = true;
                            }
                        }
                    }
                }
                if (foundExistentialDependent)
                    continue next_root;

                // Heuristic 10: In the case of noun phrases, take the last noun
                // in the phrase, e.g., "Molten material", "pringtime snow
                // runoff"
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                boolean foundDependent = false;
                for (SemanticGraphEdge e : edges) {
                    if (e.getRelation().getShortName().equals("dep")) {
                        IndexedWord dep = e.getDependent();
                        String depLemma = dep.get(LemmaAnnotation.class);
                        char depPos = dep.get(PartOfSpeechAnnotation.class)
                            .substring(0,1).toLowerCase().charAt(0);

                        //System.out.println("HEURISTIC 10");
                        if (depPos == sensePos) {
                            foundDependent = true;
                            candidates.put(depLemma, "Heuristic-10");
                            addSiblings(dep, candidates,
                                        sensePos, dependencies, "Heuristic-10");
                        }                        
                    }
                }


                if (!foundDependent) {
                    //System.out.println("HEURISTIC 1");
                    candidates.put(lemma, "Heuristic-1");
                    addSiblings(root, candidates, sensePos, dependencies,
                                "Heuristic-1");
                }            
            }
            
            // Heuristic 2: subdef is either (1) one word or (2) two or more
            // word that *must be connected by a conjunction, and (3) the lemma
            // has the wrong part of speech, but could have the same POS (i.e.,
            // the lemma was probably POS-tagged incorrectly).  
            if (sensePos != lemmaPos) {
                
                // Only one word in the subdef, which can manifest itself as the
                // graph having no vertices! (size == 0)
                if (dependencies.size() < 1) {
                    // System.out.println("HEURISTIC 2a");
                    IIndexWord iword = dict.getIndexWord(lemma, spos_);
                    if (iword != null)
                        candidates.put(lemma, "Heuristic-2a");
                    else {
                        // Sometimes adjectves get lemmatized to a verb form
                        // which is in correct.  Check to see if the token
                        // matches
                        String token = root.get(TextAnnotation.class);
                        iword = dict.getIndexWord(token, spos_);
                        if (iword != null)
                            candidates.put(token, "Heuristic-2a");
                    }
                }
                else {
                    // System.out.println("HEURISTIC 2b");
                    Set<IndexedWord> tmp = new HashSet<IndexedWord>();
                    List<SemanticGraphEdge> edges =
                        dependencies.outgoingEdgeList(root);
                    for (SemanticGraphEdge e : edges) {
                        // System.out.printf("edge from %s -> %s %s%n", lemma,
                        //                   e.getRelation().getShortName(),
                        //                   e.getRelation().getLongName());
                        if (e.getRelation().getShortName().equals("conj")) {
                            if (tmp.size() == 0)
                                tmp.add(root);
                            tmp.add(e.getDependent());
                        }
                    }
                    if (!tmp.isEmpty()) {
                        for (IndexedWord iw : tmp) {
                            String lem = iw.get(LemmaAnnotation.class);
                            IIndexWord iword = dict.getIndexWord(lem, spos_);
                            if (iword != null)
                                candidates.put(lem, "Heuristic-2b");
                            else {
                                // Sometimes adjectves get lemmatized to a verb
                                // form which is in correct.  Check to see if
                                // the token matches
                                String token = iw.get(TextAnnotation.class);
                                iword = dict.getIndexWord(token, spos_);
                                if (iword != null)
                                    candidates.put(token, "Heuristic-2b");
                            }
                        }
                        //System.out.println(tmp);
                    }
                }
            }

            // Heuristics 3: the subdef is phrased as an overly-general description
            // of a person using "one", e.g., "one who does X".  Replace this with
            // "person"
            if (sensePos == 'n' &&
                (lemma.equals("one") || lemma.equals("someone"))) {
                // check the dependency graph for a "who" attachment
                
                // TODO
                
                // ... or be lazy and just check for the token
                Matcher m = WHO.matcher(subdef);
                if (m.find()) {
                    candidates.put("person", "Heuristic-3: Person");
                }
            }
            
            // Heuristic 4: if the root lemma is an adjective and the target
            // sense is a noun, look for a modifying a noun or set of nouns,
            // report those
            ///
            // Example: "a small, arched passageway"
            if (sensePos == 'n' && lemmaPos == 'j') {
                //System.out.println("HEURISTIC 4");
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                     // System.out.printf("edge from %s -> %s %s%n", lemma,
                     //                   e.getRelation().getShortName(),
                     //                   e.getRelation().getLongName());
                     
                     if (e.getRelation().getShortName().equals("appos")
                         || e.getRelation().getShortName().equals("dep")) {
                        IndexedWord dep = e.getDependent();
                        String depLemma = dep.get(LemmaAnnotation.class);
                        // System.out.println("!!! " + depLemma);
                        char depPos = dep.get(PartOfSpeechAnnotation.class)
                            .substring(0,1).toLowerCase().charAt(0);

                        if (depPos == sensePos) {
                            candidates.put(depLemma, "Heuristic-4: Head Noun");
                            addSiblings(dep, candidates,
                                        sensePos, dependencies,
                                        "Heuristic-4: Head Noun");
                        }
                        //break;
                        
                    }
                }

            }
            
            // Heuristic 5: if the root lemma is a verb and the target sense is
            // a noun, look for a subject noun
            if (sensePos == 'n' && lemmaPos == 'v') {
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                    if (e.getRelation().getShortName().equals("nsubj")) {
                        IndexedWord dep = e.getDependent();

                        String depLemma = dep.get(LemmaAnnotation.class);
                        char depPos = dep.get(PartOfSpeechAnnotation.class)
                            .substring(0,1).toLowerCase().charAt(0);

                        if (depPos == sensePos) {
                            candidates.put(depLemma, "Heuristic-5: Subject Noun");
                            addSiblings(dep, candidates,
                                        sensePos, dependencies,
                                        "Heuristic-5: Subject Noun");
                        }
                        break;
                        
                    }
                }
            }

            // Heuristic 6: if the root lemma is an existential quantifier or
            // something like it (e.g., "Any of ...") and
            // the target sense is a noun, look for a subject noun
            if (sensePos == 'n' && lemmaPos == 'd') {
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                    // System.out.printf("edge from %s -> %s %s%n", lemma,
                    //                    e.getRelation().getShortName(),
                    //                    e.getRelation().getLongName());

                    if (e.getRelation().getShortName().equals("prep")
                        || e.getRelation().getShortName().equals("dep")) {
                        IndexedWord dep = e.getDependent();

                        String depLemma = dep.get(LemmaAnnotation.class);
                        char depPos = dep.get(PartOfSpeechAnnotation.class)
                            .substring(0,1).toLowerCase().charAt(0);

                        // System.out.println(depLemma + "/" + depPos);

                        // This should be the common case
                        if (depPos == sensePos) {
                            candidates.put(depLemma,
                                           "Heuristic-6: Existential Example");
                            addSiblings(dep, candidates,
                                        sensePos, dependencies,
                                        "Heuristic-6: Existential Example");
                        }
                        // This is for some really (really) unusually parsed
                        // edge cases
                        else  {
                            List<SemanticGraphEdge> depEdges =
                                dependencies.outgoingEdgeList(dep);
                            for (SemanticGraphEdge e2 : depEdges) {
                                
                                if (e2.getRelation().getShortName().equals("rcmod")) {
                                    IndexedWord dep2 = e2.getDependent();
                                    String depLemma2 =
                                        dep2.get(LemmaAnnotation.class);
                                    char depPos2 =
                                        dep2.get(PartOfSpeechAnnotation.class)
                                        .substring(0,1).toLowerCase().charAt(0);

                                    if (depPos2 == sensePos) {
                                        candidates.put(depLemma2, "Heuristic-6: Existential Example");
                                        addSiblings(dep2, candidates,
                                                    sensePos, dependencies,
                                                    "Heuristic-6: Existential Example");
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Heuristic 8: if the root lemma is a verb and the sense is an
            // adjective, but the verb is modified by an adverb, this catches
            // that cases that Heuristics 2 does not
            if (sensePos == 'j' && lemmaPos == 'v') {
                
                Set<IndexedWord> tmp = new HashSet<IndexedWord>();
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                     // System.out.printf("edge from %s -> %s %s%n", lemma,
                     //                   e.getRelation().getShortName(),
                     //                   e.getRelation().getLongName());
                    if (e.getRelation().getShortName().equals("advmod")) {
                        IIndexWord iword = dict.getIndexWord(lemma, spos_);
                        if (iword != null)
                            candidates.put(lemma, "Heuristic-8: Adv-modified Verb");
                        else {
                            // Sometimes adjectves get lemmatized to a verb
                            // form which is in correct.  Check to see if
                            // the token matches
                            String token = root.get(TextAnnotation.class);
                            iword = dict.getIndexWord(token, spos_);
                            if (iword != null)
                                candidates.put(token, "Heuristic-8: Adv-modified Verb");
                        }
                    }
                }
            }

            
            // Heuristic 9: if the sense is an adjective and the root lemma
            // begins with with a negative *and* the gloss contains something
            // like "not [x]", then pull out the "x" and use it as the hypernym
            if (sensePos == 'j' && lemma.equals("not")) {
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                    // System.out.printf("edge from %s -> %s %s%n", lemma,
                    //                    e.getRelation().getShortName(),
                    //                    e.getRelation().getLongName());

                    if (e.getRelation().getShortName().equals("dep")) {
                        IndexedWord dep = e.getDependent();

                        String depLemma = dep.get(LemmaAnnotation.class);
                        char depPos = dep.get(PartOfSpeechAnnotation.class)
                            .substring(0,1).toLowerCase().charAt(0);

                        if (depPos == sensePos) {
                            candidates.put(depLemma, "Heuristic-9: negated adj");
                            addSiblings(dep, candidates,
                                        sensePos, dependencies,
                                        "Heuristic-9: negated adj");
                        }
                        break;
                        
                    }
                }
            }

            // Heuristic 11: if the sense is a verb and the root lemma
            // is "to", this is probably a case of mistaken POS-tagging
            if (sensePos == 'v' && lemma.equals("to")) {
                List<SemanticGraphEdge> edges =
                    dependencies.outgoingEdgeList(root);
                for (SemanticGraphEdge e : edges) {
                    if (e.getRelation().getShortName().equals("pobj")) {
                        IndexedWord dep = e.getDependent();
                        IIndexWord iword = dict.getIndexWord(lemma, spos_);
                        if (iword != null)
                            candidates.put(lemma, "Heuristic-11: verbal infinitive");
                        else {
                            // Sometimes verbs get lemmatized to a noun form
                            // that is incorrect.  Check to see if the token
                            // matches
                            String token = dep.get(TextAnnotation.class);
                            iword = dict.getIndexWord(token, spos_);
                            if (iword != null)
                                candidates.put(token, "Heuristic-9: verbal infinitive");
                        }
                    }
                }
            }

        }
        return candidates;
    }

    /**
     * If we know we want {@code toAdd}, get all of its siblings that are joined
     * by conjunctions as candidates too
     */
    void addSiblings(IndexedWord toAdd, MultiMap<String,String> candidates,
                     char targetPos, SemanticGraph parse,
                     String reason) {
        List<SemanticGraphEdge> edges =
            parse.outgoingEdgeList(toAdd);
        for (SemanticGraphEdge e : edges) {
            if (e.getRelation().getShortName().equals("conj")) {
                IndexedWord dep = e.getDependent();
                String depLemma = dep.get(LemmaAnnotation.class);
                char depPos = dep.get(PartOfSpeechAnnotation.class)
                    .substring(0,1).toLowerCase().charAt(0);
                if (targetPos == depPos) {
                    if (targetPos != 'v') {
                        candidates.put(depLemma, reason + " (In conjunction)");
                    }
                    // Check for phrasal verb particles
                    else {
                        List<SemanticGraphEdge> depEdges =
                            parse.outgoingEdgeList(dep);
                        for (SemanticGraphEdge e2 : depEdges) {
                            if (e2.getRelation().getShortName().equals("prt")) {
                                IndexedWord dep2 = e.getDependent();
                                depLemma = depLemma + " " +
                                    dep2.get(LemmaAnnotation.class);
                                break;
                            }
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

    static char toChar(POS pos) {
        switch(pos) {
        case NOUN: return 'n';
        case VERB: return 'v';
        case ADJECTIVE: return 'j';
        case ADVERB: return 'r';
        default: throw new AssertionError();
        }
    }
}
