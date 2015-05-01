
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import edu.ucla.sspace.util.*;

import edu.stanford.nlp.util.*;
import edu.stanford.nlp.semgraph.*;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;



public class GenerateHypernymCandidates {

    /**
     * The WordNet dictionary
     */
    static IDictionary dict;

    /**
     * Multit-hreaded queue for processing the lemmas in parallel
     */
    static final WorkQueue WORK_QUEUE = WorkQueue.getWorkQueue();

    // We use this pattern with Heuristic 3 to replace instances of "one who
    // ..." with the candidate for "person".  We need the pattern to guard
    // against weird puncutation (it happens :(  )
    static final Pattern who = Pattern.compile("\\bwho\\b");

    static StanfordCoreNLP pipeline = null;

    public static void main(String[] args) throws Exception {

        if (args.length != 3) {
            System.out.println("java GHC subdefs.tsv WN3.0-path/ " +
                               "<mode:(oov|wn-mono)>");
            return;
        }

        File subdefsFile = new File(args[0]);
        String wnpath = args[1];
        String mode = args[2];

        final PrintWriter pw = (args.length >= 4)
            ? new PrintWriter(args[3])
            : null;


        final boolean produceOOVdata = !mode.equals("wn-mono");

        if (!(mode.equals("oov") || mode.equals("wn-mono"))) {
            System.out.printf("Unrecognized mode \"%s\".  Mode must be " +
                              "either \"oov\" or \"wn-mono\"%n", mode);
            return;
        }

        // Load in a mapping from a sense to all it's subdef's

        MultiMap<String,String> senseToSubdefs =
            new HashMultiMap<String,String>();
        for (String line : new LineReader(subdefsFile)) {
            int i = line.indexOf('\t');
            int j = line.indexOf('\t', i+1);
            senseToSubdefs.put(line.substring(0, i), line.substring(j+1));
        }
        
        // Load Stanford's stuff
        java.util.Properties props = new java.util.Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma, parse");
        pipeline = new StanfordCoreNLP(props);

        // Load the WN library, which is a static member of the class
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        dict = new edu.mit.jwi.Dictionary(url);
        if (!dict.open())
            throw new Error();


        if (pw != null) {
            pw.println("lemma\tsubdef\twikified subdef\twikified candidates" +
                       "\tunwikified candidates\tbackoff candidate" +
                       "\tMWE expansions");
        }
        else {
            System.out.println("lemma\tsubdef\twikified subdef" +
                               "\twikified candidates" +
                               "\tunwikified candidates\tbackoff candidate" +
                               "\tMWE expansions");
        }
        
        int senseCount = 0;
                
        // For each of the senses, get a list of candidate attachments for it
        // from its subdefitinons
        for (Map.Entry<String,Set<String>> e
                 : senseToSubdefs.asMap().entrySet()) {
        }

        // Process each lemma by attempting to graft it to a hypernym in WordNet
        // based on the closest-matching gloss from any of its Wiktionary
        // senses.
        Object taskKey = WORK_QUEUE.registerTaskGroup(senseToSubdefs.size());
        for (Map.Entry<String,Set<String>> e_ :
                 senseToSubdefs.asMap().entrySet()) {
            final Map.Entry<String,Set<String>> e = e_;
            WORK_QUEUE.add(taskKey, new Runnable() {
                public void run() {
                    String lemma = e.getKey();
                    Set<String> subdefs = e.getValue();
                    getCandidates(lemma, subdefs, pw, produceOOVdata);
                }
                });
        }
        WORK_QUEUE.await(taskKey);

        if (pw != null)
            pw.close();
    }

    private static final ThreadLocal<StringBuilder> sbs
        = new ThreadLocal<StringBuilder>();

    /**
     *
     * @param subdefsAndLinked the are lines that have the subdefition and then
     *                         all of the terms that are wikified in the
     *                         definiton
     */
    static void getCandidates(String sense, Set<String> subdefsAndLinked,
                              PrintWriter pw, boolean produceOOVdata) {

        StringBuilder sb = sbs.get();
        if (sb == null) {
            sb = new StringBuilder();
            sbs.set(sb);
        }

        String[] tmp_ = sense.split("\\.");
        if (tmp_.length < 3 || tmp_[1].length() < 1) {
            return;
        }
        char sensePos = tmp_[1].charAt(0);
        if (!(sensePos == 'n' || sensePos == 'v' || sensePos == 'a'
              || sensePos == 'r')) {
            // System.out.println("Weird sense: " + sense);
            return;
        }
        String targetLemma = sense.split("\\.")[0];
        IIndexWord tmp = dict.getIndexWord(targetLemma, toPos(sensePos));
        
        //     if (++senseCount % 100 == 0)
        //         System.err.printf("Finished %d senses of %d%n", senseCount,
        //                           senseToSubdefs.size());
        
        
        // In the "oov" mode, we report hypernym candidates for OOV words in
        // WordNet, which we will eventually attach 
        if (produceOOVdata) {
            if (tmp != null)
                return;
        }
        // In the "wn-mono" mode, we only report candidates for lemmas
        // that are in WordNet and are also monosemous, which lets us
        // test the error in attachment rates in a controlled setting
        else {
            if (tmp == null || tmp.getWordIDs().size() != 1)
                return;
        }
        
        
        // The set of lemmas whose synsets are  candidate hypernyms
        Set<String> candidates = new HashSet<String>();
        
        for (String subdefAndLinked : subdefsAndLinked) {
            
            String[] arr = subdefAndLinked.split("\t");
            if (arr.length < 2) {
                // System.out.println("Bad line?: " + subdefAndLinked);
                continue;
            }
            String subdef = arr[0];
            String wikifiedSubdef = arr[1];
            Set<String> wikified = new HashSet<String>();
            for (int i = 2; i < arr.length; ++i)
                wikified.add(arr[i]);
            
            // Parse the subdefintion
            Annotation document = new Annotation(subdef);
            pipeline.annotate(document);
            List<CoreMap> sentences = document.get(SentencesAnnotation.class);
            
            // In some rare cases, a subdefinitoin could had multiple sentences.
            // We use them all, though this should probably be analyzed
            for(CoreMap sentence: sentences) {
                for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                    String word = token.get(TextAnnotation.class);
                    String pos = token.get(PartOfSpeechAnnotation.class);
                }
                
                // Get the dependency parsed tree
                Tree tree = sentence.get(TreeAnnotation.class);
                SemanticGraph dependencies = sentence.get(
                    CollapsedCCProcessedDependenciesAnnotation.class);
                
                Set<String> cands =
                    getCandidates(dependencies, subdef, sensePos);
                
                // System.out.println(sense + "\t" + subdef + "\t->\t" + cands);
                
                
                
                // Once we have the set of candidates, try to clean up some
                // errors where we have picked a word that is part of a longer
                // [[linked expression]] but not the full thing.  In some cases,
                // the full MWE is not in WN, while its head term is, e.g.,
                // "directed graph" is not present, while "graph" is.  We report
                // these in a separate column to allow the later stages to make
                // use of the data.
                Map<String,String> mweExpansions =
                    new HashMap<String,String>();
                int i = wikifiedSubdef.indexOf("[[");
                while (i >= 0) {
                    int j = wikifiedSubdef.indexOf("]]", i);
                    String text = wikifiedSubdef.substring(i+2, j);
                    
                    // See if this is a compound term
                    int k = text.indexOf(' ');
                    if (k > 0) {
                        // If so, first see if we managed to extract it and
                        // if not, see if we have any of its terms as
                        // candidates
                        if (!cands.contains(text)) {
                            String[] tokens = text.split("\\s+");
                            boolean contained = false;
                            for (String tok : tokens) {
                                if (cands.contains(tok)) {
                                    mweExpansions.put(text, tok);
                                    contained = true;
                                    break;
                                }
                            }
                            
                            // System.out.printf("%s -> %s -> %s -> %s%n",
                            //                   wikifiedSubdef, text,
                            //                   cands, contained);
                        }
                    }
                    
                    i = wikifiedSubdef.indexOf("[[", i + 1);
                }
                
                
                
                // Back-off heuristic: if we didn't find anything, add a
                // candidate from the first [[wikified]] term, provided that it
                // has a WordNet form in the correct part of speech.  Record
                // this as a separate candidate as well
                String backoffCandidate = null;
                i = wikifiedSubdef.indexOf("[[");
                if (i >= 0) {
                    int j = wikifiedSubdef.indexOf("]]", i);
                    String text = wikifiedSubdef.substring(i+2, j);
                    // Check for a WordNet term with this POS, but guard against
                    // some weirdness from JWI about the type arguments
                    try {
                        if (dict.getIndexWord(text, toPos(sensePos)) != null)
                            backoffCandidate = text;
                    } catch (Throwable t) {
                        // silent
                    }
                }
                
                
                sb.setLength(0);
                sb.append(sense).append('\t').append(subdef).append('\t')
                    .append(wikifiedSubdef).append('\t');
                
                boolean b = false;
                for (String candidate : cands) {
                    if (wikified.contains(candidate)) { 
                        sb.append(candidate).append(',');
                        b = true;
                    }
                }
                if (b) {
                    // trim off last comma
                    sb.setLength(sb.length() - 1);
                    b = false;
                }
                sb.append('\t');
                for (String candidate : cands) {
                    if (!wikified.contains(candidate)) { 
                        sb.append(candidate).append(',');
                        b = true;
                    }
                }
                if (b) {
                    // trim off last comma
                    sb.setLength(sb.length() - 1);
                }
                
                sb.append('\t');
                if (backoffCandidate != null)
                    sb.append(backoffCandidate);
                
                sb.append('\t');
                if (mweExpansions.size() > 0) {
                    for (Map.Entry<String,String> e2
                             : mweExpansions.entrySet()) {
                        sb.append(e2.getKey()).append("->")
                            .append(e2.getValue()).append(',');
                    }
                    sb.setLength(sb.length() - 1);
                }
                
                if (pw != null)
                    pw.println(sb);
                else
                    System.out.println(sb);
                
                
                // for (String candidate : cands) {
                //     if (wikified.contains(candidate))
                //         senseToWikifiedCandidates.put(sense, candidate);
                //     else
                //         senseToUnwikifiedCandidates.put(sense, candidate);
                // }
            }
        }

        // if (++senseCount % 100 == 0)
            //     System.err.printf("Finished %d senses of %d%n", senseCount,
            //                       senseToSubdefs.size());
    }

        // Set<String> sortedSenses =
        //     new TreeSet<String>(senseToUnwikifiedCandidates.keySet());
        // sortedSenses.addAll(senseToWikifiedCandidates.keySet());

        
        // PrintWriter pw = (args.length >= 3)
        //     ? new PrintWriter(args[2])
        //     : null;


        // StringBuilder sb = new StringBuilder();
        // for (String sense : sortedSenses) {
        //     sb.setLength(0);
        //     sb.append(sense).append('\t');
        //     Set<String> wikified = senseToWikifiedCandidates.get(sense);
        //     if (wikified.size() > 0) {
        //         for (String cand : wikified)
        //             sb.append(cand).append(',');
        //         // strip off the last ','
        //         sb.setLength(sb.length() - 1);
        //     }
        //     sb.append('\t');
        //     Set<String> unwikified = senseToUnwikifiedCandidates.get(sense);
        //     if (unwikified.size() > 0) {
        //         for (String cand : unwikified)
        //             sb.append(cand).append(',');
        //         // strip off the last ','
        //         sb.setLength(sb.length() - 1);
        //     }

        //     // Either write to a file or to Stdout
        //     if (pw == null)
        //         System.out.print(sb);
        //     else
        //         pw.println(sb);
        // }
        // if (pw != null)
        //     pw.close();


    /** 
     * Gets the candidate hypernyms form the provided subdef
     */
    static Set<String> getCandidates(SemanticGraph dependencies,
                                     String subdef, char sensePos) {
        if (sensePos == 'a')
            sensePos = 'j';
        POS spos_ = toPos(sensePos);

        Set<String> candidates = new HashSet<String>();
        // System.out.println();
        // System.out.println(subdef);
        // System.out.printf("num vertices: %d, size: %d%n",
        //                   dependencies.vertexSet().size(), dependencies.size());
        // dependencies.prettyPrint();
        // Look at the root words in each sentence.  

        Collection<IndexedWord> roots = dependencies.getRoots();
        next_root:
        for (IndexedWord root : roots) {
            String word = root.get(TextAnnotation.class);
            String lemma = root.get(LemmaAnnotation.class);
            String pos = root.get(PartOfSpeechAnnotation.class);
            char lemmaPos = pos.substring(0,1).toLowerCase().charAt(0);
            
            String lemmaLc = lemma.toLowerCase();

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
                                candidates.add(depLemma);
                                addSiblings(dep, candidates,
                                            sensePos, dependencies);
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
                            candidates.add(depLemma);
                            addSiblings(dep, candidates,
                                        sensePos, dependencies);
                        }                        
                    }
                }


                if (!foundDependent) {
                    //System.out.println("HEURISTIC 1");
                    candidates.add(lemma);
                    addSiblings(root, candidates, sensePos, dependencies);
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
                        candidates.add(lemma);
                    else {
                        // Sometimes adjectves get lemmatized to a verb form
                        // which is in correct.  Check to see if the token
                        // matches
                        String token = root.get(TextAnnotation.class);
                        iword = dict.getIndexWord(token, spos_);
                        if (iword != null)
                            candidates.add(token);
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
                                candidates.add(lem);
                            else {
                                // Sometimes adjectves get lemmatized to a verb
                                // form which is in correct.  Check to see if
                                // the token matches
                                String token = iw.get(TextAnnotation.class);
                                iword = dict.getIndexWord(token, spos_);
                                if (iword != null)
                                    candidates.add(token);
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
                Matcher m = who.matcher(subdef);
                if (m.find()) {
                    candidates.add("person");
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
                            candidates.add(depLemma);
                            addSiblings(dep, candidates,
                                        sensePos, dependencies);
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
                            candidates.add(depLemma);
                            addSiblings(dep, candidates,
                                        sensePos, dependencies);
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
                            candidates.add(depLemma);
                            addSiblings(dep, candidates,
                                        sensePos, dependencies);
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
                                        candidates.add(depLemma2);
                                        addSiblings(dep2, candidates,
                                                    sensePos, dependencies);
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
                            candidates.add(lemma);
                        else {
                            // Sometimes adjectves get lemmatized to a verb
                            // form which is in correct.  Check to see if
                            // the token matches
                            String token = root.get(TextAnnotation.class);
                            iword = dict.getIndexWord(token, spos_);
                            if (iword != null)
                                candidates.add(token);
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
                            candidates.add(depLemma);
                            addSiblings(dep, candidates,
                                        sensePos, dependencies);
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
                            candidates.add(lemma);
                        else {
                            // Sometimes verbs get lemmatized to a noun form
                            // that is incorrect.  Check to see if the token
                            // matches
                            String token = dep.get(TextAnnotation.class);
                            iword = dict.getIndexWord(token, spos_);
                            if (iword != null)
                                candidates.add(token);
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
    static void addSiblings(IndexedWord toAdd, Set<String> candidates,
                            char targetPos, SemanticGraph parse) {
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
                        candidates.add(depLemma);
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
}
