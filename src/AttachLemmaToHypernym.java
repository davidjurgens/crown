import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

import edu.ucla.sspace.common.Similarity;
import edu.ucla.sspace.graph.*;
import edu.ucla.sspace.matrix.*;
import edu.ucla.sspace.util.*;
import edu.ucla.sspace.util.primitive.*;
import edu.ucla.sspace.vector.*;

import gnu.trove.iterator.*;
import gnu.trove.map.*;
import gnu.trove.map.hash.*;
import gnu.trove.set.*;
import gnu.trove.set.hash.*;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;

import edu.stanford.nlp.util.*;
import edu.stanford.nlp.semgraph.*;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;




public class AttachLemmaToHypernym {

    /**
     * To avoid creating this all the time
     */
    static final String[] EMPTY_ARR = new String[0];

    /**
     * A minimum score needed for any synset to become a hyprenym.  This value
     * is current set for the jaccard index based on qualitative observation.
     */
    static final double MIN_SCORE = 0.12;

    /**
     * For lazy use by static methods
     */
    static IDictionary dict;

    /**
     * Weights assigned to each lemma
     */
    static TObjectDoubleMap<String> lemmaToWeight;

    /**
     * The set of lemmas that may refer to people, which is used when detecting
     * glosses for senses that must be people
     */
    static Set<String> peopleLemmas = null;

    /**
     * Multit-hreaded queue for processing the lemmas in parallel
     */
    static final WorkQueue WORK_QUEUE = WorkQueue.getWorkQueue();

    static boolean debug = false;

    
    public static void main(String[] args) throws Exception {

        if (args.length != 7) {
            System.out.println("usage: java AttachLemmaToHypernym " +
                               "sense-to-candidates.tsv WN-dir/ " +
                               "lemma-to-inv-gloss-freq.tsv " +
                               "wikt-sense-to-gloss.tsv " +
                               "sense-to-antonym.tsv " +
                               "wikisaurus.relations.tsv " +
                               "wiktionary.relations.tsv "
                               );
            return;
        }

        File senseToCandidatesFile = new File(args[0]);
        String wnpath = args[1];
        File weightFile = new File(args[2]);
        File senseToGlossFile = new File(args[3]);
        File senseToAntonymFile = new File(args[4]);

        File wikisaurusFile = new File(args[5]);
        File wiktionaryRelationsFile = new File(args[6]);

        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        
        // construct the dictionary object and open it
        dict = new edu.mit.jwi.RAMDictionary(url,
            edu.mit.jwi.data.ILoadPolicy.IMMEDIATE_LOAD);
        dict.open();
        
        // Get all the recognized lemmas that are persons, which we'll use later
        // to reduce error in the attachment by recognizing that a sense of the
        // *must* refer to a person and therefore has to attach to it somewhere.
        peopleLemmas = loadPeopleLemmas(dict);

        // Load the sense-to-antonym mapping.  The POS of the data is in the
        // sense's format lemma.pos.<num> so we can recover the expected POS of
        // the antonym
        final Map<String,String> senseToAntonym
            = new HashMap<String,String>(10000);
        for (String line : new LineReader(senseToAntonymFile)) {
            String[] arr = line.split("\t");
            senseToAntonym.put(arr[0], arr[1]);
        }
        
        java.util.Properties props = new java.util.Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma");
        pipeline = new StanfordCoreNLP(props);

        
        // Load the lemma weights
        lemmaToWeight = new TObjectDoubleHashMap<String>();
        for (String line : new LineReader(weightFile)) {
            String[] arr = line.split("\t");
            String lemma = arr[0];
            double invFreq = Double.parseDouble(arr[1]);
            lemmaToWeight.put(lemma, -Math.log10(invFreq));
        }

        // Group all the results by lemma
        final MultiMap<String,String> lemmaToSenses
            = new HashMultiMap<String,String>();

        final MultiMap<String,String> senseToSubdefs
            = new HashMultiMap<String,String>();

        final Map<String,String> senseToGloss = new HashMap<String,String>();
        for (String line : new LineReader(senseToGlossFile)) {
            String[] arr = line.split("\t");
            senseToGloss.put(arr[0], arr[1]);
        }

        // Load the candidates file.  Once loaded, we'll want to process it
        // under the assumption that a lemma has one sense, regardless of how
        // many senses it has in Wiktionary.  The two maps allow us to keep
        // track of all the sense options we have
        for (String line : new LineReader(senseToCandidatesFile)) {
            int i = line.indexOf('\t');            
            String sense = line.substring(0, i);
            if (sense.indexOf('.') < 0)
                continue;
            String lemma = sense.substring(0, sense.lastIndexOf('.'));
            String subdef = line.substring(i+1);
            // Check that this subdef actually has candidates, otherwise skip.
            String[] arr = subdef.split("\t");
            if (arr.length > 3) {
                lemmaToSenses.put(lemma, sense);
                senseToSubdefs.put(sense, subdef);
            }
        }

        // Load the Wikisaurus data.  The data itself is a complex so we
        // encapsulate its functionality in an inner class.  This makes it
        // easier to keep all the wikisaurus-specific data structures in one
        // place.
        final Wikisaurus wikisaurus = new Wikisaurus(wikisaurusFile, dict);

        // Load the Wiktionary synonym data.  The data set contains lots of
        // relations, but we only extract the relations and try to identify the
        // hypernyms of OOV words in groups of synonyms
        final WiktionarySynonyms wiktionarySynonyms =
            new WiktionarySynonyms(wiktionaryRelationsFile, dict);

        
        int lemmasAnnotated = 0;

        // Process each lemma by attempting to graft it to a hypernym in WordNet
        // based on the closest-matching gloss from any of its Wiktionary
        // senses.
        Object taskKey = WORK_QUEUE.registerTaskGroup(lemmaToSenses.size());
        for (Map.Entry<String,Set<String>> e_ :
                 lemmaToSenses.asMap().entrySet()) {
            final Map.Entry<String,Set<String>> e = e_;
            WORK_QUEUE.add(taskKey, new Runnable() {
                public void run() {
                    String lemma = e.getKey();
                    Set<String> wiktSenses = e.getValue();
                    attach(lemma, wiktSenses, senseToSubdefs, senseToGloss,
                           senseToAntonym, wikisaurus, wiktionarySynonyms);
                }
                });
        }
        WORK_QUEUE.await(taskKey);

    }

    static void attach(String lemma, Set<String> wiktSenses,
                       MultiMap<String,String> senseToSubdefs,
                       Map<String,String> senseToGloss,
                       Map<String,String> senseToAntonym,
                       Wikisaurus wikisaurus,
                       WiktionarySynonyms wiktionarySynonyms) {

        POS pos = toPos(lemma.split("\\.")[1].charAt(0));

        // System.out.printf("%s -> %s%n", lemma, wiktSenses);

        // Keep track of which wiktionary sense produced this candidate.  Use a
        // sorted set of Candidates, which orders the senses according to their
        // weighted gloss similarity and then breaks ties uses LCS
        SortedSet<Candidate> attachmentCandidates =
            new TreeSet<Candidate>();

        // SPECIAL CASE: Wikisaurus has already provided information on what
        // this OOV lemma's hypernym is, so just directly attach it
        if (wikisaurus.getParent(lemma) != null) {
            ISynset parent = wikisaurus.getParent(lemma);
            String id = parent.getID().toString()
                .replace("SID-", "").toLowerCase();

            // Even though we know the parent, we still need to quickly process
            // all the glosses to figure out which Wiktionary sense is actually
            // matching this parent.  Otherwise, we can't include the gloss in
            // CROWN at the end
            double highestSim = 0;
            String bestWiktSense = null;
            // Short-circuit early if we only have one Wiktionary sense for this
            // lemma
            if (wiktSenses.size() == 1) {
                bestWiktSense = wiktSenses.iterator().next();
            }
            else  {
                String extGloss = getExtendedGloss(parent);
                for (String wiktSense : wiktSenses) {
                    String fullWiktGloss = senseToGloss.get(wiktSense);
                    Set<String> wiktGlossLemmas = getLemmas(fullWiktGloss);
                    double weightedOverlap = weightedOverlap(
                        fullWiktGloss, wiktGlossLemmas, extGloss, parent);
                    if (weightedOverlap >= highestSim) {
                        highestSim = weightedOverlap;
                        bestWiktSense = wiktSense;
                    }
                }
            }
            
            // Fake the scores and report UNKNOWN for Wiktionary sense we're
            // attaching to since we just got the info from Wikisaurus
            System.out.println(lemma + "\t" + id + "\t" +
                               bestWiktSense + "\t" + highestSim
                               + "\t" + parent.getWords());

            CrownLogger.info("SOURCE\tWikisaurus");
            return;
        }


        // SPECIAL CASE: Wiktionary's synset groups has already provided
        // information on what this OOV lemma's hypernym is, so just directly
        // attach it
        if (wiktionarySynonyms.getParent(lemma) != null) {
            ISynset parent = wiktionarySynonyms.getParent(lemma);
            String id = parent.getID().toString()
                .replace("SID-", "").toLowerCase();

            // Even though we know the parent, we still need to quickly process
            // all the glosses to figure out which Wiktionary sense is actually
            // matching this parent.  Otherwise, we can't include the gloss in
            // CROWN at the end
            double highestSim = 0;
            String bestWiktSense = null;
            // Short-circuit early if we only have one Wiktionary sense for this
            // lemma
            if (wiktSenses.size() == 1) {
                bestWiktSense = wiktSenses.iterator().next();
            }
            else  {
                String extGloss = getExtendedGloss(parent);
                for (String wiktSense : wiktSenses) {
                    String fullWiktGloss = senseToGloss.get(wiktSense);
                    Set<String> wiktGlossLemmas = getLemmas(fullWiktGloss);
                    double weightedOverlap = weightedOverlap(
                        fullWiktGloss, wiktGlossLemmas, extGloss, parent);
                    if (weightedOverlap >= highestSim) {
                        highestSim = weightedOverlap;
                        bestWiktSense = wiktSense;
                    }
                }
            }
            
            // Fake the scores and report UNKNOWN for Wiktionary sense we're
            // attaching to since we just got the info from the Wiktionary
            // synset groups
            System.out.println(lemma + "\t" + id + "\t" +
                               bestWiktSense + "\t" + highestSim
                               + "\t" + parent.getWords());
            CrownLogger.info("SOURCE\tWiktionary Synonyms");
            return;
        }

        //
        //
        // DEFAULT PROCESSING LOOP:
        //
        // try to attach each Wiktionary sense
        //
        //        
        for (String wiktSense : wiktSenses) {
            
            Set<String> subdefs = senseToSubdefs.get(wiktSense);
            // Get the full gloss for this sense, which we'll use when
            // tie-breaking candidates
            String fullWiktGloss = senseToGloss.get(wiktSense);
            
            // The subdefs in the subdefs set are actually tab-separated
            // columns, so strip out just the simple, unwikified versions for
            // testing the special cases
            Set<String> simpleSubdefs = new HashSet<String>();
            for (String subdef : subdefs)
                simpleSubdefs.add(subdef.substring(0, subdef.indexOf('\t')));
            
            // SPECIAL CASE HANDLING::
            //
            // Some types of synsets get attached to wrong candidates by virtue
            // of (1) unique gloss formatting, (2) poor wikification in the
            // gloss or (3) bad candidate extraction on our part (i.e., from the
            // previous step.  After repeated error analysis, here we try to
            // catch several cases where we can apply simple, high-precision
            // heuristics to minimize the common worst-case errors
                
            // SPECIAL CASE #1: taxonomic defitions.  Usually these are
            // something like "any member of <scientific family name>"
            if (pos.equals(POS.NOUN) && isTaxonomicDef(simpleSubdefs)) {
                // If this is a taxonomic defition, see if we can figure out
                // which taxa this is coming from, then track down the instances
                // of that taxa in WordNet and attach to their parent synset.
                Candidate candidate =
                    getGenusCandidate(wiktSense, simpleSubdefs, dict);
                
                // Sometimes we cannot identify anything in WordNet for this
                // scientific family name, so rather than just attach it
                // randomly, we say it's an organism, which is hopefully better
                // than nothing :/
                if (candidate == null) {
                    IIndexWord organismIw =
                        dict.getIndexWord("organism", POS.NOUN);        
                    ISynset organismSyn =
                        dict.getSynset(organismIw.getWordIDs()
                                       .get(0).getSynsetID());
                    // Fake the scores. :(
                    attachmentCandidates.add(
                        new Candidate(wiktSense, organismSyn, 100d, 100));
                    // System.out.printf("%s is an organism, but couldn't find " +
                    //                   "the right synset in %s%n", wiktSense,
                    //                   fullWiktGloss);
                }
                else {
                    attachmentCandidates.add(candidate);
                    // System.out.printf("%s is an organism, which we think is %s " +
                    //                   " based on %s%n", wiktSense, candidate,
                    //                   fullWiktGloss);
                }


                CrownLogger.info("SOURCE\tTaxonomic Heuristic");
                // Skip to next sense
                continue; 
            }

            // SPECIAL CASE #2: the sense is defining a type of person.
            // These usually of the form "one who ...", but we handle a
            // variety of cases
            if (pos.equals(POS.NOUN)
                && mustHavePersonHypernym(simpleSubdefs, peopleLemmas)) {
                
                // NOTE: pass in the full subdefintions so that we can get a
                // list of options
                Candidate candidate =
                    getPersonCandidate(wiktSense, subdefs,
                                       dict, fullWiktGloss);
                
                // Sometimes we cannot identify anything in WordNet that
                // matches this type of person.  In such cases, we just say
                // that its parent is "person" and hope for the best
                if (candidate == null) {
                    IIndexWord personIw =
                        dict.getIndexWord("person", POS.NOUN);        
                    ISynset personSyn =
                        dict.getSynset(personIw.getWordIDs()
                                       .get(0).getSynsetID());
                    // Fake the scores. :(
                    attachmentCandidates.add(
                        new Candidate(wiktSense, personSyn, 100d, 100));
                     // System.out.printf("%s is a person, but couldn't find " +
                     //                   "the right synset in %s%n", wiktSense,
                     //                   fullWiktGloss);
                }
                else {
                    attachmentCandidates.add(candidate);
                    // System.out.printf("%s is a person, which we think is %s " +
                    //                   " based on %s%n", wiktSense, candidate,
                    //                   fullWiktGloss);
                }

                CrownLogger.info("SOURCE\tPerson Heuristic");
                // Skip to next sense
                continue; 
            }

            // SPECIAL CASE #3: recognized antonym.  For some senses, we have
            // parsed the subdefs and recognized that this particular sense is
            // an antonym of another lemma in WordNet.  In these cases, we don't
            // need to bother with the candidate generation since this sense
            // *must* be attached to one of the hypernyms of the antonym's
            // senses.
            if (senseToAntonym.containsKey(wiktSense)) {
                String antonym = senseToAntonym.get(wiktSense);
                
                IIndexWord antonymIw = dict.getIndexWord(antonym, pos);

                // Sometimes, WordNet will not have the antonym, in which case,
                // we just abort this special case and revert back to the
                // regular attachment process below
                //
                // NOTE: this might be a nice opportunity for a second pass
                // where we find mutual-OOV antonyms and must attach them to the
                // same place
                if (antonymIw != null) {
                 
                    double highestSim = 0;
                    ISynset bestMatch = null;

                    // SPECIAL CASE: the lemma only has one sense, so don't
                    // bother doing anything fancy.  This actually helps a lot
                    // when we're antonyms with a monosemous lemma and the
                    // glosses don't overlap at all.
                    if (antonymIw.getWordIDs().size() == 1) {
                        bestMatch = dict.getSynset(
                            antonymIw.getWordIDs().get(0).getSynsetID());
                    }
                    else {
                        Set<String> wiktGlossLemmas = getLemmas(fullWiktGloss);
                        for (IWordID id : antonymIw.getWordIDs()) {
                            ISynsetID synsetId = id.getSynsetID();
                            
                            ISynset synset = dict.getSynset(synsetId);
                            String extGloss = getExtendedGloss(synset);
                            double weightedOverlap = weightedOverlap(
                                fullWiktGloss, wiktGlossLemmas,
                                extGloss, synset);                        
                            if (weightedOverlap > highestSim) {
                                highestSim = weightedOverlap;
                                bestMatch = synset;
                            }
                        }
                    }

                    // If we were able to find a matching antonym synset, then
                    // attach this lemma to its parent (if it exists) and
                    // otherwise, just revert back to the regular attachment
                    // code.
                    if (bestMatch != null) {
                        ISynset antonymSynset = bestMatch;
                        List<ISynsetID> hypers = antonymSynset
                            .getRelatedSynsets(Pointer.HYPERNYM);
                        // Guard against some weird case where there's no
                        // parent.  Note that in the case of adjectives, they
                        // *can't* have parents, so we'll end up saying that the
                        // antonym is both this sense's parent *and* its
                        // antonym.  Thankfully, the LexFile generation code
                        // recognizes this later, but it's probably an
                        // unfortunate program design at the moment.
                        ISynset parentSynset = (hypers.isEmpty())
                            ? antonymSynset
                            : dict.getSynset(hypers.get(0));

                        // System.out.printf("ANTONYM: %s (%s) :: %s -> %s (%f)%n",
                        //                   wiktSense, fullWiktGloss, antonym,
                        //                   bestMatch, highestSim);
                        
                        String parentId = parentSynset.getID().toString()
                            .replace("SID-", "").toLowerCase();
                        String antoId = antonymSynset.getID().toString()
                            .replace("SID-", "").toLowerCase();
                        
                        System.out.println(lemma + "\t" + parentId + "\t" +
                                           wiktSense + "\t" + highestSim
                                           + "\t" + bestMatch.getWords()
                                           + "\tANTONYM:" + antoId);
                        
                        // No need to keep processing this synset.  NOTE: once we
                        // start allowing multiple senses for OOV code, we'll have
                        // to fix this.
                        CrownLogger.info("SOURCE\tAntonym Heuristic");
                        return;
                    }
                }
            }

            // As we process each synset, candidate, keep track of a total score
            // based on the degree of similarity.  If a sense has multiple
            // subdefintitions, if the candidates from those subdefitions share
            // synsets, then the synset's score will be the *sum* of its
            // similarites (which is a good thing).
            TObjectDoubleMap<ISynset> synsetToScore =
                new TObjectDoubleHashMap<ISynset>();
            
            // Keep track of the attachment candidates for this sense
            // separately at first - just for debugging purposes and then
            // add them to attachment candidates later.  
            SortedSet<Candidate> candidatesForSense =
                new TreeSet<Candidate>();
            
            for (String subdef : subdefs) {
                String[] arr = subdef.split("\t");
                
                String gloss = arr[0];
                String wikifiedGloss = arr[1];
                
                // These are the four main sources of candidates:
                //
                // 1) the head word that was wikified 
                // 2) the head word that was *not* wikified
                // 3) the first wikified word in the subdef that was the
                //    right POS
                // 4) MWE expansions of the 
                String[] wikifiedCandidates = arr[2].split(",");
                String[] unwikifiedCandidates =
                    (arr.length >= 4) ? arr[3].split(",") : EMPTY_ARR;
                String backupCandidate =
                    (arr.length >= 5) ? arr[4] : "";
                // This is the set of multi-word expressions that were
                // contained within the candidate words in the subdef
                Set<String> mwe = new HashSet<String>();
                if (arr.length >= 6) {
                    String[] mweToSingle = arr[5].split(",");
                    for (String s : mweToSingle) {
                        String[] arr2 = s.split("->");
                        mwe.add(arr2[0]);
                    }
                }
                
                // TODO: Test candidate types individual, which is
                // potentially redundant because of overlap
                //
                // Avoid doing more work, by just lumping everything in one
                // set...
                Set<String> lemmaCandidates = new HashSet<String>();
                lemmaCandidates.addAll(Arrays.asList(wikifiedCandidates));
                lemmaCandidates.addAll(Arrays.asList(unwikifiedCandidates));
                lemmaCandidates.add(backupCandidate);
                lemmaCandidates.addAll(mwe);
                
                // Load all the synsets that we will compare
                List<ISynset> synsetsToCompare
                    = new ArrayList<ISynset>();
                
                // Get all the synsets that we can reach from each candidate
                // according to the paths we define
                for (String lc : lemmaCandidates) {
                    synsetsToCompare.addAll(getSynsets(lc, pos));
                }
                
                // Compute the set of lemmas in this gloss
                Set<String> wiktGlossLemmas = getLemmas(fullWiktGloss);

                for (ISynset attachPoint : synsetsToCompare) {
                    double score = getSimilarity(
                        fullWiktGloss, wiktGlossLemmas, attachPoint);
                    double curScoreSum = synsetToScore.get(attachPoint);
                    synsetToScore.put(attachPoint, score + curScoreSum);
                    // candidateCounts.count(attachPoint);
                    // if (score > 0) 
                    //     System.out.printf("%s\t%s\t->\t%f%n", fullWiktGloss,
                    //                       attachPoint, score);
                }

                //System.out.println(synsetToScore);
            }

            
            
            // 
            if (synsetToScore.isEmpty())
                continue;
            
            // Once all the subdefinitions for this Wiktionary sense have
            // been processed, figure out which WordNet sense has the
            // highest score
            TObjectDoubleIterator<ISynset> iter = synsetToScore.iterator();
            while (iter.hasNext()) {
                iter.advance();
                double score = iter.value();
                int lcs = lcs(fullWiktGloss, iter.key().getGloss());
                
                // Ensure there was at least one match...
                if (score > 0) {
                    candidatesForSense.add(
                        new Candidate(wiktSense, iter.key(), score, lcs));
                }
            }
            attachmentCandidates.addAll(candidatesForSense);
        
            /*
             * The following is helpful debug print code for figuring out
             * how parents are rated 
             */
            // Iterator<Candidate> iter2 = candidatesForSense.iterator();
            // for (int i = 0; i < 5 && iter2.hasNext(); ++i)
            //     System.out.println(iter2.next());
            // System.out.println();
            
            // SortedMultiMap<Double,ISynset> sorted =
            //     new TreeMultiMap<Double,ISynset>(
            //     new Comparator<Double>() {
            //         public int compare(Double d1, Double d2) {
            //             return -Double.compare(d1, d2);
            //         }});
            
            //     iter = synsetToScore.iterator();
            // while (iter.hasNext()) {
            //     iter.advance();
            //     sorted.put(iter.value(), iter.key());
            // }

            // System.out.printf("%s -> %s%n", wiktSense,
            //                   senseToSubdefs.get(wiktSense));
            // int i = 0;
            // for (Map.Entry<Double,ISynset> e2 : sorted.entrySet()) {
            //     System.out.println("  " + e2.getKey() + " " + e2.getValue());
            //     if (++i >= 1000)
            //         break;
            // }
            // System.out.println();
            
        }

        // Once all the Wiktionary senses for a lemma have been seen, we
        // possibly have a candidate to report.  If so, report the lemma, which
        // wiktionary sense it was chosen from, the score, and the WordNet
        // synset ID
        if (attachmentCandidates.size() > 0) {
            Candidate best = attachmentCandidates.iterator().next();
            String id = best.parent.getID().toString()
                .replace("SID-", "").toLowerCase();

            System.out.println(lemma + "\t" + id + "\t" +
                               best.wiktSense + "\t" + best.score
                               + "\t" + best.parent.getWords());
        }
    }        

    /**
     * Returns the set of lemmas that can indicate a person.  This data is used
     * when testing whether a particular Wiktionary sense is referring to a person
     *
     * @see #mustHavePersonHypernym(Set,Set)
     */
    static Set<String> loadPeopleLemmas(IDictionary dict) {
        IIndexWord personIw = dict.getIndexWord("person", POS.NOUN);
        // We want the first sense of "person"
        ISynset root = dict.getSynset(personIw.getWordIDs().get(0).getSynsetID());
        Set<String> lemmas = new HashSet<String>();
        lemmas.add("person");
        lemmas.add("individual"); // hard code some notable exceptions
        
        // BFS the hyponyms to get all person lemma forms
        Set<ISynsetID> frontier = new HashSet<ISynsetID>();
        Set<ISynsetID> next = new HashSet<ISynsetID>();
        Set<ISynsetID> visited = new HashSet<ISynsetID>(); // guard against
                                                           // weird loops
        frontier.addAll(root.getRelatedSynsets(Pointer.HYPONYM));
        frontier.addAll(root.getRelatedSynsets(Pointer.HYPONYM_INSTANCE));
        
        
        while (!frontier.isEmpty()) {

            visited.addAll(frontier);
            for (ISynsetID id : frontier) {
                for (IWord iw : dict.getSynset(id).getWords()) {
                    // Only add the word if it is the person-descendent synset
                    // is the first sense of this word form
                    if (iw.getLexicalID() == 0) {
                        lemmas.add(iw.getLemma());
                        //System.out.println("Accepting: " + iw.getLemma());
                    }
                    // else
                    //     System.out.println("Rejecting: " + iw.getLemma());
                }
            }

            for (ISynsetID id : frontier) {
                ISynset syn = dict.getSynset(id);
                for (ISynsetID hypo : syn.getRelatedSynsets(Pointer.HYPONYM)) {
                    if (!visited.contains(hypo))
                        next.add(hypo);
                }
                for (ISynsetID hypo
                         : syn.getRelatedSynsets(Pointer.HYPONYM_INSTANCE)) {
                    if (!visited.contains(hypo))
                        next.add(hypo);
                }
            }

            frontier.clear();
            frontier.addAll(next);
            next.clear();
        }
        return lemmas;
    }

    /**
     * Returns {@code true} if these subdefs indicate the corresponding sense is
     * an instance of a scientific genus
     */
    static boolean isTaxonomicDef(Set<String> subdefs) {
        // There are ~3800 senses that follow this pattern, so it's a good first
        // heuristic
        for (String subdef : subdefs) {
            if (subdef.startsWith("Any member of the "))
                return true;
        }
        return false;
    }

    /**
     * Given that one of the subdef's is a genus name, identify if WordNet
     * contains that genus and then find instances of it, using their hypernym
     * as the official candidate hyperny (which is probably correct).
     */
    static Candidate getGenusCandidate(String wiktSense, 
                                      Set<String> subdefs,
                                      IDictionary dict) {
        // Figure out which subdef has the taxonomic indicator
        String prefix = "Any member of the ";
        for (String subdef : subdefs) {
            if (subdef.startsWith(prefix)) {
                int i = prefix.length();
                // Find the first upper-case name after the prefix, which is the
                // family name
                for (; i < subdef.length(); ++i) {
                    if (Character.isUpperCase(subdef.charAt(i)))
                        break;
                }

                // Find the next word break after
                int j = subdef.indexOf(' ', i+1);
                if (j < 0)
                    j = subdef.length();
                String genus = subdef.substring(i, j);

                // Sometimes the family name is surrounded by quotes or other
                // junk.  Strip that out.
                genus = genus.replaceAll("\\p{Punct}+", "").trim();

                // Sometimes we can't find it, which should probably be logged
                // for future special fixing
                if (genus.length() == 0) {
                    // REMINDER: log this somewhere
                    return null;
                }
                
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
                for (ISynsetID memberId : members) {
                    // See if this synset is an organism
                    ISynset memberSyn = dict.getSynset(memberId);
                    if (isOrganism(memberSyn, dict)) {
                        // If so, report its parent

                        List<ISynsetID> hypers = memberSyn
                            .getRelatedSynsets(Pointer.HYPERNYM);
                        if (hypers.size() == 0) {
                            hypers = memberSyn
                                .getRelatedSynsets(Pointer.HYPERNYM_INSTANCE);
                        }
                        // sanity check...
                        if (hypers.size() == 0)
                            throw new AssertionError();

                        // System.out.println(genus + "\t->\t" +
                        //                    dict.getSynset(hypers.get(0)));

                        // NOTE: Just get the first one.  Not sure what to do
                        // otherwise...
                        //
                        // Fake the scores. :(
                        return new Candidate(wiktSense,
                                             dict.getSynset(hypers.get(0)),
                                             100d, 100);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if this synset is a descendent of the organism
     * synset
     */
    static boolean isOrganism(ISynset start, IDictionary dict) {

        IIndexWord organismIw = dict.getIndexWord("organism", POS.NOUN);        
        ISynset organismSyn = dict.getSynset(organismIw.getWordIDs()
                                             .get(0).getSynsetID());
        ISynsetID goal = organismSyn.getID();

        return isDescendent(start, goal, dict);
    }

    /**
     * Returns {@code true} if this synset is a descendent of the person synset
     */
    static boolean isPerson(ISynset start, IDictionary dict) {

        IIndexWord organismIw = dict.getIndexWord("person", POS.NOUN);        
        ISynset organismSyn = dict.getSynset(organismIw.getWordIDs()
                                             .get(0).getSynsetID());
        ISynsetID goal = organismSyn.getID();
        return isDescendent(start, goal, dict);
    }

    /**
     * A utility function that returns whether the synset {@code start} is a
     * descendent of the synset {@code goal}.
     */
    static boolean isDescendent(ISynset start, ISynsetID goal,
                                IDictionary dict) {

        if (start.getID().equals(goal))
            return true;

        // Do a BFS search from the hypernyms to find the organism synset
        Set<ISynsetID> frontier = new HashSet<ISynsetID>();
        Set<ISynsetID> next = new HashSet<ISynsetID>();
        Set<ISynsetID> visited = new HashSet<ISynsetID>(); // guard against
                                                           // weird loops
        frontier.addAll(start.getRelatedSynsets(Pointer.HYPERNYM));
        frontier.addAll(start.getRelatedSynsets(Pointer.HYPERNYM_INSTANCE));

        while (!frontier.isEmpty()) {

            visited.addAll(frontier);
            if (frontier.contains(goal))
                return true;
            
            for (ISynsetID id : frontier) {
                ISynset syn = dict.getSynset(id);
                for (ISynsetID hyper : syn.getRelatedSynsets(Pointer.HYPERNYM)) {
                    if (!visited.contains(hyper))
                        next.add(hyper);
                }
                for (ISynsetID hyper
                         : syn.getRelatedSynsets(Pointer.HYPERNYM_INSTANCE)) {
                    if (!visited.contains(hyper))
                        next.add(hyper);
                }
            }

            frontier.clear();
            frontier.addAll(next);
            next.clear();
        }
        return false;
    }
 
    /**
     * Returns {@code true} if the lemma associated with the subdefs must be an
     * child of the person synset.
     */
    static boolean mustHavePersonHypernym(Set<String> subdefs,
                                          Set<String> peopleLemmas) {
        next_subdef:
        for (String subdef : subdefs) {
            if (subdef.length() == 0)
                continue;
            String[] tokens = subdef.split("\\s+");
            if (tokens.length == 0)
                continue;
            // CASE: "one who" or "someone who" must be people
            if ((tokens[0].equalsIgnoreCase("one")
                 || tokens[0].equalsIgnoreCase("someone")
                 || tokens[0].equalsIgnoreCase("somebody"))
                && tokens.length > 1 && tokens[1].equalsIgnoreCase("who"))
                return true;

            // CASE: a <person-type> (who|that)
            if (tokens.length >= 3 
                && (tokens[0].equalsIgnoreCase("a")
                    || tokens[0].equalsIgnoreCase("an")
                    || tokens[0].equalsIgnoreCase("the"))
                && (tokens[2].equalsIgnoreCase("who")
                    || tokens[2].equalsIgnoreCase("that"))
                && peopleLemmas.contains(tokens[1]))
                return true;                                                         
            
            // TODO: better cases that involve a full parse to catch cases like:
            //
            // 1) DET ADJ... <person-type> (who|that)

            
            Annotation document = new Annotation(subdef);
            pipeline.annotate(document);
            List<CoreMap> sentences = document.get(SentencesAnnotation.class);

            
            for (CoreMap sentence : sentences) {
                int z = 0; // token counter
                boolean startsWithOne = false;
                boolean nounSeen = false;
                for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                    z++;
                    String pos = token.get(PartOfSpeechAnnotation.class);
                    char c = pos.substring(0,1).toLowerCase().charAt(0);
                    // We allow all combinations of DET* ADV* ADJ* CONJ before the
                    // noun
                    if (c == 'j'  || pos.equals("CC") || c =='r' || c == 'd') {
                        continue;
                    }

                    String lemma = token.get(LemmaAnnotation.class);
                    if (pos.equals("CD") && lemma.toLowerCase().equals("one")) {
                        if (z == 1) {
                            startsWithOne = true;
                            continue;
                        }
                        else {
                            // System.out.println("NOT A PERSON: " + subdef);
                            continue next_subdef;
                        }
                    }


                    // This matches the "one <verb>" pattern, which is a bit
                    // noisy, but most often is a person
                    if (z == 2 && startsWithOne && c == 'v') {
                        // System.out.println("*IS* A PERSON: " + subdef);
                        // return true;
                    }

                    // If the next word is not a noun
                    if (c != 'n') {
                        // if we've already seen a noun that was a person and
                        // the next word is "who", it's definitely a person
                        if (nounSeen && lemma.equals("who")) {
                            // System.out.println("*IS* A PERSON: " + subdef);
                            return true;
                        }
                        else {
                            // System.out.println("NOT A PERSON: " + subdef);
                            continue next_subdef;
                        }
                    }                   

                    // The lemma must be a person type, otherwise, we skip
                    if (peopleLemmas.contains(lemma)) {
                        // System.out.println("*IS* A PERSON: " + subdef);
                        //return true;
                        nounSeen = true;
                    }
                    else {
                        // System.out.println("NOT A PERSON: " + subdef);
                        continue next_subdef;
                    }
                }
                break; // only use first sentence
            }
            
        }
        //System.out.println(peopleLemmas);
        return false;
    }

    /**
     * Given that this sense is a person, figure out where to attach
     */
    static Candidate getPersonCandidate(String wiktSense, 
                                        Set<String> subdefs,
                                        IDictionary dict,
                                        String fullGloss) {
        Set<ISynset> toTest = new HashSet<ISynset>();
        for (String subdef : subdefs) {
            String[] arr = subdef.split("\t");
                    
            String gloss = arr[0];
            String wikifiedGloss = arr[1];

            // This set will contain all the lemmas suggested by the
            // candidate-generation process
            Set<String> candLemmas = new HashSet<String>();
            // wikifiedCandidates
            candLemmas.addAll(Arrays.asList(arr[2].split(",")));
            // unwikifiedCandidates 
            if (arr.length >= 4)
                candLemmas.addAll(Arrays.asList(arr[3].split(",")));
            // backupCandidate
            if (arr.length >= 5)
                candLemmas.add(arr[4]);
            // This is the set of multi-word expressions that were
            // contained within the candidate words in the subdef
            if (arr.length >= 6) {
                String[] mweToSingle = arr[5].split(",");
                for (String s : mweToSingle) {
                    String[] arr2 = s.split("->");
                    candLemmas.add(arr2[0]);
                }
            }

            for (String wc : candLemmas) {
                if (wc.length() == 0)
                    continue;
                IIndexWord iw = dict.getIndexWord(wc, POS.NOUN);
                if (iw == null)
                    continue;
                for (IWordID id : iw.getWordIDs()) {
                    ISynsetID synsetId = id.getSynsetID();
                    ISynset syn = dict.getSynset(synsetId);
                    if (isPerson(syn, dict)) {
                        toTest.add(syn);
                        for (IPointer[] path : PERSON_PATH_TYPES) {
                            addSynsets(syn, path, dict, toTest);
                        }                        
                    }
                }
            }
        }

        // The backwards-looking path expansion could have potentially
        // introduced some synset that is not a person, so remove these
        Iterator<ISynset> iter = toTest.iterator();
        while (iter.hasNext()) {
            if (!isPerson(iter.next(), dict))
                iter.remove();
        }

        //System.out.println(toTest);

        Set<String> wiktGlossLemmas = getLemmas(fullGloss);

        // Once we have all the synsets to which we could attach the wiktionary
        // sense to a synyset in the person-subtree, score each according to a
        // similarity function (whch is configuratble).
        TObjectDoubleMap<ISynset> synsetToScore =
            new TObjectDoubleHashMap<ISynset>();
        for (ISynset attachPoint : toTest) {
            double score = getSimilarity(fullGloss, wiktGlossLemmas,
                                         attachPoint);
            double curScoreSum = synsetToScore.get(attachPoint);
            synsetToScore.put(attachPoint, score + curScoreSum);
        }

        // If we couldn't find any synsets that matched, then give up early
        if (synsetToScore.isEmpty())
            return null;
        
        // Once all the subdefinitions for this Wiktionary sense have been
        // processed and the candidate attachment points scored, figure out
        // which WordNet sense has the highest score
        SortedSet<Candidate> candidatesForSense =
            new TreeSet<Candidate>();
        TObjectDoubleIterator<ISynset> iter2 = synsetToScore.iterator();
        while (iter2.hasNext()) {
            iter2.advance();
            double score = iter2.value();
            // lcs is expensive, so only run it if we have to
            int lcs = (score > 0)
                ? lcs(fullGloss, iter2.key().getGloss()) : 0;
            candidatesForSense.add(
                new Candidate(wiktSense, iter2.key(), score, lcs));
        }

        // Return the best candidate
        return candidatesForSense.iterator().next();
    }


    static class Candidate implements Comparable<Candidate> {
        final String wiktSense;
        final ISynset parent;
        final double score;
        final int lcs;
        public Candidate(String wiktSense, ISynset parent,
                         double score, int lcs) {
            this.wiktSense = wiktSense;
            this.parent = parent;
            this.score = score;
            this.lcs = lcs;
        }

        public int compareTo(Candidate cand) {
            int c = -Double.compare(score, cand.score);
            return (c == 0)
                ? cand.lcs - lcs : c;
        }

        public String toString() {
            return wiktSense  + "\t" + parent + "\t" + score + "\t" + lcs;
        }
    }

    static final IPointer[][] PERSON_PATH_TYPES = new IPointer[][]
    {
        { Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM, Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM, Pointer.HYPONYM, Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM, Pointer.HYPONYM, Pointer.HYPONYM, Pointer.HYPONYM },
    };


    /**
     * The high-precision paths for expanding our initial guess of where to
     * attach
     */
    static final IPointer[][] PATH_TYPES = new IPointer[][]
    {
        { Pointer.HYPERNYM },
        { Pointer.HYPONYM },
        { Pointer.HYPERNYM, Pointer.HYPERNYM }, // only case of two hyper's
        { Pointer.HYPERNYM, Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPERNYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM },
        { Pointer.HYPERNYM, Pointer.HYPONYM,  Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPERNYM, Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPERNYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM },
        { Pointer.HYPERNYM, Pointer.HYPONYM,  Pointer.HYPONYM, Pointer.HYPONYM },
        { Pointer.HYPONYM,  Pointer.HYPONYM,  Pointer.HYPONYM, Pointer.HYPONYM },
    };

    /**
     * Returns the set of all synsets reachable from the lemma's synsets
     * according to the paths
     */
    static Set<ISynset> getSynsets(String lemma, POS pos) {
        if (lemma.length() == 0)
            return Collections.<ISynset>emptySet();
        
        // Start by getting all the senses of the candidate
        IIndexWord iw = dict.getIndexWord(lemma, pos);

        // If the candidate wasn't in WN, skip it
        if (iw == null)
            return Collections.<ISynset>emptySet();
        List<IWordID> wordIds = iw.getWordIDs();
        Set<ISynset> synsets = new HashSet<ISynset>(100);
        
        // Then for each of the senses, add in all the synsets reachable by
        // following one of the hypernym/hyponym paths list PATH_TYPES from from
        // the synset of the candidate. These paths were selected after
        // examining the error rate of monosemous nouns in WN and using only
        // those paths with at most length 3 and leading to the correct hypernym
        for (IWordID id : wordIds) {       
            ISynsetID synsetId = id.getSynsetID();
            ISynset syn = dict.getSynset(synsetId);
            synsets.add(syn);
            for (IPointer[] path : PATH_TYPES) {
                addSynsets(syn, path, dict, synsets);
            }
        }
        
        return synsets;
    }


    static void score(String candidate, POS pos, String wiktGloss,
                      TObjectDoubleMap<ISynset> synsetToScore,
                      Counter<ISynset> candidateCounts) {

        // Guard against empty string candidates
        if (candidate.length() == 0)
            return;

        // This will be all the synsets candidates to which we might assign the
        // wiktionary sense
        Set<ISynset> toCompare = new HashSet<ISynset>(1024);

        // Start by getting all the senses of the candidate
        IIndexWord iw = dict.getIndexWord(candidate, pos);

        // If the candidate wasn't in WN, skip it
        if (iw == null)
            return;
        List<IWordID> wordIds = iw.getWordIDs();

        // NOTE: In some cases, the gloss is only a single word or is a noun
        // with a determiner.  Do something special?

        // Then for each of the senses, add in all the synsets reachable by
        // following one of the hypernym/hyponym paths list PATH_TYPES from from
        // the synset of the candidate. These paths were selected after
        // examining the error rate of monosemous nouns in WN and using only
        // those paths with at most length 3 and leading to the correct hypernym
        for (IWordID id : wordIds) {       
            ISynsetID synsetId = id.getSynsetID();
            ISynset syn = dict.getSynset(synsetId);
            toCompare.add(syn);
            for (IPointer[] path : PATH_TYPES) {
                addSynsets(syn, path, dict, toCompare);
            }
        }

        Set<String> wiktGlossLemmas = getLemmas(wiktGloss);

        // Once we have all the synsets to which we could attach the wiktionary
        // sense, score each according to a similarity function (whch is
        // configuratble).
        for (ISynset attachPoint : toCompare) {
            double score = getSimilarity(wiktGloss, wiktGlossLemmas,
                                         attachPoint);
            double curScoreSum = synsetToScore.get(attachPoint);
            synsetToScore.put(attachPoint, score + curScoreSum);
            candidateCounts.count(attachPoint);
        }
    }

    /**
     * This follows the path, returning all the synonyms reachable from the seed
     * using the specified relations in order
     */
    static void addSynsets(ISynset seed, IPointer[] path,
                           IDictionary dict, Set<ISynset> toAddTo) {
        Set<ISynset> frontier = new HashSet<ISynset>();
        frontier.add(seed);
        Set<ISynset> next = new HashSet<ISynset>();
        for (IPointer rel : path) {
            for (ISynset syn : frontier) {
                for (ISynsetID id : syn.getRelatedSynsets(rel)) {
                    next.add(dict.getSynset(id));
                }
            }
            frontier.clear();
            frontier.addAll(next);
            next.clear();
        }
        // At the end, frontier contains all the synets with the path type
        toAddTo.addAll(frontier);
    }

    //

    enum GlossSimilarityType { LCS, TERM_OVERLAP, JACCARD_INDEX,
                               WEIGHTED_OVERLAP, FUZZY_OVERLAP }

    /**
     * Returns the similarity of the glosses
     */
    static double getSimilarity(String wiktGloss,
                                Set<String> wiktGlossLemmas, ISynset syn) {

        // NOTE: we probably eventually want to consider whether the
        // subdefinition was a single word vs. an actual gloss

        GlossSimilarityType sim = GlossSimilarityType.WEIGHTED_OVERLAP;
        String extGloss = getExtendedGloss(syn);
        
        switch(sim) {
            // Option #1: use LCS
        case LCS:
            return lcs(wiktGloss, extGloss);           
            // Option #2: use Bag of Words approach
        case JACCARD_INDEX:
            return jaccardIndex(wiktGloss, wiktGlossLemmas, extGloss, syn);

        case WEIGHTED_OVERLAP:
            return weightedOverlap(wiktGloss, wiktGlossLemmas, extGloss, syn);

            // TODO: add more
            
        default:
            throw new AssertionError();
        }
    }

    static StanfordCoreNLP pipeline = null;

    /**
     * A cache containing a synset's lemmas.  This speeds up the process a lot
     * by avoiding the need to re-run Stanford's CoreNLP for every gloss.
     */
    static Map<ISynset,Set<String>> synsetToGlossLemmasCache =
        new HashMap<ISynset,Set<String>>(160000);

    static double fuzzyOverlap(String wiktGloss,
                               Set<String> wiktGlossLemmas,
                               String extGloss,
                               ISynset syn) {
        

        Set<String> glossLemmas = synsetToGlossLemmasCache.get(syn);
        if (glossLemmas == null) {
            glossLemmas = getLemmas(extGloss);
            synsetToGlossLemmasCache.put(syn, glossLemmas);
        }

        if (glossLemmas.isEmpty() || wiktGlossLemmas.isEmpty())
            return 0;

        // Compute the Fuzzy overlap
        double inCommon = 0;        
        for (String s1 : wiktGlossLemmas) {
            int len1 = s1.length();
            for (String s2 : glossLemmas) {
                int overlap = lcs(s1, s2);
                if (overlap <= 3) 
                    continue;

                inCommon += (overlap / Math.max(len1, s2.length()));
            }            
        }

        
        return inCommon / (glossLemmas.size() * wiktGlossLemmas.size());         
    }

    static double weightedOverlap(String wiktGloss,
                                  Set<String> wiktGlossLemmas,
                                  String extGloss,
                                  ISynset syn) {
        

        
        Set<String> glossLemmas = null;
        synchronized(synsetToGlossLemmasCache) {
            glossLemmas = synsetToGlossLemmasCache.get(syn);
        }
        if (glossLemmas == null) {
            glossLemmas = getLemmas(extGloss);
            synchronized(synsetToGlossLemmasCache) {
                synsetToGlossLemmasCache.put(syn, glossLemmas);
            }
        }

        if (debug)
            System.out.printf("  wikt:%s\twn:%s%n", wiktGlossLemmas, glossLemmas);
        
        if (glossLemmas.isEmpty() || wiktGlossLemmas.isEmpty())
            return 0;

        // Compute the Jaccard Index
        double weightSum = 0;
        for (String s : wiktGlossLemmas) {
            if (glossLemmas.contains(s))
                weightSum += lemmaToWeight.get(s);
        }
        return weightSum;
    }


    static double jaccardIndex(String wiktGloss,
                               Set<String> wiktGlossLemmas,
                               String extGloss,
                               ISynset syn) {
       

        Set<String> glossLemmas = synsetToGlossLemmasCache.get(syn);
        if (glossLemmas == null) {
            glossLemmas = getLemmas(extGloss);
            synsetToGlossLemmasCache.put(syn, glossLemmas);
        }

        // Compute the Jaccard Index
        double inCommon = 0;
        for (String s : wiktGlossLemmas) {
            if (glossLemmas.contains(s))
                inCommon++;
        }

        return inCommon
            / ((wiktGlossLemmas.size() + glossLemmas.size()) - inCommon);
    }


    static final Set<String> STOP_VERBS = new HashSet<String>();
    static {
        String[] verbs = new String[] {
            "is", "am", "are", "was", "were", "have", "has", "had",
            "will", "would",  "shall", "should",
            "may", "might", "must", "be", "been", "being", 
        };
        STOP_VERBS.addAll(Arrays.asList(verbs));
    }

    static Set<String> getLemmas(String gloss) {
        if (pipeline == null) {
            java.util.Properties props = new java.util.Properties();
            props.put("annotators", "tokenize, ssplit, pos, lemma");
            pipeline = new StanfordCoreNLP(props);
        }

        Set<String> lemmas = new HashSet<String>();
        Annotation document = new Annotation(gloss);
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);

        // In some rare cases, a subdefinitoin could had multiple sentences.  We
        // use them all, though this should probably be analyzed
        for(CoreMap sentence: sentences) {
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                String lemma = token.get(LemmaAnnotation.class);
                String pos = token.get(PartOfSpeechAnnotation.class);
                // if (debug)
                //     System.out.println(lemma + "\t" + pos);
                char c = pos.substring(0,1).toLowerCase().charAt(0);
                // Not sure if we need to pos tag... but at least avoid putting
                // in everything but content
                if (c == 'n' || c == 'j' || c == 'r' 
                        || (c == 'v' && !STOP_VERBS.contains(lemma)))
                                       
                    lemmas.add(lemma.toLowerCase());
            }
        }
        return lemmas;
    }
        
        
    /**
     * Returns the length of the longest substring 
     */
    static int lcs(String x, String y) {
        int M = x.length();
        int N = y.length();

        // opt[i][j] = length of LCS of x[i..M] and y[j..N]
        int[][] opt = new int[M+1][N+1];

        // compute length of LCS and all subproblems via dynamic programming
        for (int i = M-1; i >= 0; i--) {
            for (int j = N-1; j >= 0; j--) {
                if (x.charAt(i) == y.charAt(j))
                    opt[i][j] = opt[i+1][j+1] + 1;
                else 
                    opt[i][j] = Math.max(opt[i+1][j], opt[i][j+1]);
            }
        }

        // recover LCS itself and print it to standard output
        int i = 0, j = 0;
        int len = 0;
        while(i < M && j < N) {
            if (x.charAt(i) == y.charAt(j)) {
                //System.out.print(x.charAt(i));
                len++;
                i++;
                j++;
            }
            else if (opt[i+1][j] >= opt[i][j+1]) i++;
            else                                 j++;
        }
        return len;
    }

    static StringBuilder sb = new StringBuilder();
    /**
     * Returns words in synset + the gloss
     */
    static synchronized String getExtendedGloss(ISynset syn) {
        sb.setLength(0);
        for (IWord iw : syn.getWords()) {
            sb.append(iw.getLemma()).append(' ');
        }
        sb.append(syn.getGloss());
        return sb.toString();
    }

    static Set<String> getIds(String lem, POS pos, IDictionary dict) {
        Set<String> ids = new HashSet<String>();

        IIndexWord iw = dict.getIndexWord(lem, pos);
        // Could have linked to an OOV word
        if (iw == null)
            return ids;
        List<IWordID> wordIds = iw.getWordIDs();
        for (IWordID wi : wordIds) {
            ISynsetID synsetId = wi.getSynsetID();
            String id = synsetId.toString()
                .replace("SID-", "").toLowerCase();
            ids.add(id);
        }
        return ids;
    }

    /**
     * Converts a character pos into JWI's POS object.
     */ 
    static POS toPos(char pos) {
        switch (pos) {
        case 'n': return POS.NOUN;
        case 'v': return POS.VERB;
        case 'a': return POS.ADJECTIVE;
        case 'j': return POS.ADJECTIVE;
        case 'r': return POS.ADVERB;
        default:
            throw new AssertionError("bad pos: " + pos);
        }
    }

    /**
     * Converts a character pos into JWI's POS object.
     */ 
    static char toChar(POS pos) {
        switch (pos) {
        case NOUN: return 'n';
        case VERB: return 'v';
        case ADJECTIVE: return 'a';
        case ADVERB: return 'r';
        default:
            throw new AssertionError("bad pos: " + pos);
        }
    }
    

    static class Wikisaurus {

        /**
         * The mapping from an OOV lemma (represented as lemma.pos) to its
         * estimated hypernym synset in WordNet
         */
        private final Map<String,ISynset> oovToParent;


        public Wikisaurus(File wsDataFile, IDictionary dict) {

            oovToParent = new HashMap<String,ISynset>();

            // The for supporting data structures
            Map<String,String> lemmaToGloss = new HashMap<String,String>();
            Map<String,POS> lemmaToPos = new HashMap<String,POS>();
            MultiMap<String,String> lemmaToSynonyms
                = new HashMultiMap<String,String>();
            MultiMap<String,String> lemmaToHyponyms
                = new HashMultiMap<String,String>();

            // Gather all the data from the file
            for (String line : new LineReader(wsDataFile)) {
                String[] arr = line.split("\t");
                if (arr[1].equals("POS")) {
                    lemmaToPos.put(arr[0],
                                   toPos(arr[2].toLowerCase().charAt(0)));
                }
                else if (arr[1].equals("GLOSS"))
                    lemmaToGloss.put(arr[0], arr[2]);
                else if (arr[1].equals("SYNONYM"))
                    lemmaToSynonyms.put(arr[0], arr[2]);
                else if (arr[1].equals("HYPONYM"))
                    lemmaToHyponyms.put(arr[0], arr[2]);
                else {
                    // Skip HYPERNYM 
                    //System.out.println("Unknown wikisaurus rel: " + arr[1]);
                }
            }

            // For each lemma for which we have a POS entry:
            //
            // (1) first, try to figure out which WordNet synset it corresponds
            //     to
            // (2) then, identify the OOV hyponyms/synonyms of this synset 
            for (Map.Entry<String,POS> e : lemmaToPos.entrySet()) {
                String lemma = e.getKey();
                POS pos = e.getValue();

                String wiktGloss = lemmaToGloss.get(lemma);
                Set<String> synonyms = lemmaToSynonyms.get(lemma);
                Set<String> hyponyms = lemmaToHyponyms.get(lemma);

                // Get the word data associated with this lemma in WordNet, if
                // it exists
                IIndexWord lemmaIw = dict.getIndexWord(lemma, pos);
                
                ISynset estimatedSynset = null;
                    
                // If the word *is* in WordNet, use the gloss to try figuring
                // out which of its WordNet senses is being referred to in the
                // Wikisaurus data
                if (lemmaIw != null && wiktGloss != null) {

                    // SPECIAL CASE: the lemma only has one sense, so don't
                    // bother doing anything fancy
                    if (lemmaIw.getWordIDs().size() == 1) {
                        estimatedSynset = dict.getSynset(
                            lemmaIw.getWordIDs().get(0).getSynsetID());
                    }
                    else {
                        // Get the lemmas in the gloss
                        Set<String> wiktGlossLemmas = getLemmas(wiktGloss);
                        
                        // For each of this word's senses, figure out which has the
                        // most similar gloss
                        double highestSim = 0;
                        ISynset match = null;
                        for (IWordID wordId : lemmaIw.getWordIDs()) {  
                            ISynset synset = dict.getSynset(wordId.getSynsetID());
                            String extGloss = getExtendedGloss(synset);
                            double weightedOverlap = weightedOverlap(
                                wiktGloss, wiktGlossLemmas,
                                extGloss, synset);
                            
                            // System.out.printf("%s :: %s -> %s\t%f\t(%f)%n",
                            //                   lemma, wiktGloss, extGloss,
                            //                   weightedOverlap, highestSim);
                            
                            if (weightedOverlap > highestSim) {
                                highestSim = weightedOverlap;
                                match = synset;
                            }
                        }
                        estimatedSynset = match;
                    }
                    
                }


                // If (1) the lemma isn't in WordNet, (2), we don't have a gloss
                // for it, or (3) we have both but couldn't figure out which
                // sense it was from the Wiktionary gloss, resort to using the
                // backup strategy of counting all the hypernyms of the supposed
                // synonyms (up to two levels) and hoping that the most frequent
                // hypernym of the synonym is the correct to associated with
                // this lemma
                if (estimatedSynset == null) {
                    estimatedSynset = getEstimatedSynset(
                        dict, pos, synonyms, hyponyms);

                    // System.out.printf("%s %s (%s) -> %s%n",
                    //                   lemma, pos, wiktGloss, estimatedSynset);
                }

                if (estimatedSynset == null) {
                    // System.err.printf("Unable to attach %s %s (%s)%n",
                    //                   lemma, pos, wiktGloss);
                    continue;
                }

                // Once we know where this Wiktionary definition is in WordNet
                // (as a synset), figure out which of its associated data
                // members are OOV
                Set<String> all =  new HashSet<String>(synonyms);
                all.addAll(hyponyms);

                // For both the synonys and hyponyms
                for (String child : all) {

                    // Get the WordNet sysnetif it exists
                    IIndexWord iw = dict.getIndexWord(child, pos);
                    if (iw == null) {
                        // System.out.printf("%s -> %s%n",
                        //                   child + "." + toChar(pos),
                        //                   estimatedSynset);
                        oovToParent.put(child + "." + toChar(pos),
                                        estimatedSynset);
                    }
                }
            }
        }

        public ISynset getParent(String lemmaAndPos) {
            return oovToParent.get(lemmaAndPos);
        }

        
        static ISynset getEstimatedSynset(IDictionary dict, POS pos,
                                            Set<String> synonyms,
                                            Set<String> hyponyms) {
            Counter<ISynsetID> hypernymCounts =
                new ObjectCounter<ISynsetID>();
            Set<String> all =  new HashSet<String>(synonyms);
            all.addAll(hyponyms);

            // For both the synonys and hyponyms
            for (String lemma : hyponyms) {

                // Get the WordNet sysnet if it exists
                IIndexWord iw = dict.getIndexWord(lemma, pos);

                // Some of Wiktionary won't be in WordNet
                if (iw == null)
                    continue;
                
                // For each of this word's senses
                for (IWordID wordId : iw.getWordIDs()) {                   
                    ISynset synset = dict.getSynset(wordId.getSynsetID());
                
                    // Get the hypernyms of the synset and count their
                    // occurrence
                    for (ISynsetID hyper 
                             : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
                        hypernymCounts.count(hyper);
                    }
                }
            }

            // If we couldn't find any of the hyponyms, back-off to the synonyms
            for (String lemma : synonyms) {
                // Get the WordNet sysnet if it exists
                IIndexWord iw = dict.getIndexWord(lemma, pos);

                // Some of Wiktionary won't be in WordNet
                if (iw == null)
                    continue;
                
                // For each of this word's senses
                for (IWordID wordId : iw.getWordIDs()) {                   
                    ISynset synset = dict.getSynset(wordId.getSynsetID());
                
                    // Get the hypernyms of the synset and count their
                    // occurrence
                    for (ISynsetID hyper 
                             : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
                        hypernymCounts.count(hyper);
                    }
                }
            }

            // System.out.println(hypernymCounts);

            // Return the most frequent hypernym or null, if we couldn't find
            // any of the lemma's synonyms or hyponyms in WordNet
            return hypernymCounts.items().isEmpty()
                ? null
                : dict.getSynset(hypernymCounts.max());
        }
    }



    static class WiktionarySynonyms {

        /**
         * The mapping from an OOV lemma (represented as lemma.pos) to its
         * estimated hypernym synset in WordNet
         */
        private final Map<String,ISynset> oovToParent;


        public WiktionarySynonyms(File wiktionaryRelationFile,
                                  IDictionary dict) {

            oovToParent = new HashMap<String,ISynset>();

            // The for supporting data structures
            MultiMap<String,String> lemmaToSynonyms
                = new HashMultiMap<String,String>();

            // Gather all the data from the file
            for (String line : new LineReader(wiktionaryRelationFile)) {
                String[] arr = line.split("\t");
                if (arr.length < 4)
                    continue;
                String lemma = arr[0];

                String pos = arr[1];
                String rel = arr[2];
                String related = arr[3];

                // Skip bad lemmas like "to add"
                if (lemma.startsWith("to ") || related.startsWith("to "))
                    continue;

                if (lemma.startsWith("the ") || related.startsWith("the "))
                    continue;
                                
                if (lemma.startsWith("Wikisaurus:")
                        || related.startsWith("Wikisaurus:"))
                    continue;
                    

                if (rel.equals("SYNONYM"))
                    lemmaToSynonyms.put(lemma +"\t" + pos, related);
            }

            // None of the lemmas have glosses, so we're forced to use the the
            // set of synonyms to figure out just which WordNet synset they
            // correspond to
            for (Map.Entry<String,Set<String>> e
                      : lemmaToSynonyms.asMap().entrySet()) {
            
                String s = e.getKey();
                String[] arr = s.split("\t");
                String lemma = arr[0];
                char c = arr[1].charAt(0);
                if (!(c == 'n' || c == 'v' || c == 'r' || c == 'a'))
                    continue;
                POS pos = toPos(c);

                // Add the lemma to the set of synonyms to make this a
                // Wiktionary "synset"
                Set<String> synonyms = e.getValue();
                synonyms.add(lemma);
                
                // Use the strategy of counting all the hypernyms of the
                // supposed synonyms and hoping that the most frequent hypernym
                // of the synonym is the correct to associated with this lemma
                ISynset estimatedHypernym =
                    getEstimatedHypernym(dict, pos, synonyms);

                if (estimatedHypernym == null) {
                    continue;
                }

                // Once we know where this Wiktionary definition is in WordNet
                // (as a synset), figure out which of its associated data
                // members are OOV.  
                
                for (String syn : synonyms) {

                    // Get the WordNet sysnetif it exists
                    IIndexWord iw = dict.getIndexWord(syn, pos);
                    if (iw == null) {
                        // System.out.printf("(WiktRel-) %s -> %s%n",
                        //                   syn + "." + toChar(pos),
                        //                   estimatedHypernym);
                        oovToParent.put(syn + "." + toChar(pos),
                                        estimatedHypernym);
                    }
                }
            }
        }

        public ISynset getParent(String lemmaAndPos) {
            return oovToParent.get(lemmaAndPos);
        }

        
        static ISynset getEstimatedHypernym(IDictionary dict, POS pos,
                                            Set<String> synonyms) {
            Counter<ISynsetID> hypernymCounts =
                new ObjectCounter<ISynsetID>();

            // If we couldn't find any of the hyponyms, back-off to the synonyms
            for (String lemma : synonyms) {
                // Get the WordNet sysnet if it exists
                IIndexWord iw = dict.getIndexWord(lemma, pos);

                // Some of Wiktionary won't be in WordNet
                if (iw == null)
                    continue;
                
                // For each of this word's senses
                for (IWordID wordId : iw.getWordIDs()) {                   
                    ISynset synset = dict.getSynset(wordId.getSynsetID());
                
                    // Get the hypernyms of the synset and count their
                    // occurrence
                    for (ISynsetID hyper 
                             : synset.getRelatedSynsets(Pointer.HYPERNYM)) {
                        hypernymCounts.count(hyper);
                    }
                }
            }

            // Return the most frequent hypernym or null, if we couldn't find
            // any of the lemma's synonyms or hyponyms in WordNet
            return hypernymCounts.items().isEmpty()
                ? null
                : dict.getSynset(hypernymCounts.max());
        }
    }
}
