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



/**
 * Parses the preprocessed glosses to identify antonym pairs based on prefix
 * rules
 */
public class ExtractAntonyms {

    public static void main(String[] args) throws Exception {
        
        if (args.length != 2) {
            System.out.println("usage: java ExtractAntonyms " +
                               "sense-to-candidates.tsv WN-dir/ ");
            return;
        }

        String[] ANTONYM_PREFICES =
            new String[] { "anti", "non", "dis", "mal", "mis", "il", "un",
                           "in", "im", "de", "ir", };

        File senseToCandidatesFile = new File(args[0]);
        String wnpath = args[1];

        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        
        // construct the dictionary object and open it
        IDictionary dict = new edu.mit.jwi.RAMDictionary(url,
            edu.mit.jwi.data.ILoadPolicy.IMMEDIATE_LOAD);
        dict.open();


        for (String line : new LineReader(senseToCandidatesFile)) {
            String[] arr = line.split("\t");
            String sense = arr[0];
            String[] arr2 = sense.split("\\.");
            if (arr2.length < 3)
                continue;
            String lemma = arr2[0];
            char pos = arr2[1].charAt(0);
            String gloss = arr[1];

            // See if it could be antonym
            String stem = null;
            for (String prefix : ANTONYM_PREFICES) {
                if (lemma.startsWith(prefix)) {
                    stem = lemma.substring(prefix.length());
                    break;
                }
            }

            if (stem == null) {
                // if
                if (pos == 'v') {
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
                        if (!isInWn(dict, anto, POS.VERB))
                            anto = "super" + lemma.substring(5);
                    }

                    if (anto != null) {
                        if (isInWn(dict, anto, POS.VERB)) {
                            //System.out.printf("ANTO: %s <-> %s%n", lemma, anto);
                            System.out.println(sense + "\t" + anto);
                        }
                        else {
                            //System.out.printf("NO ANTO? %s%n", lemma);
                        }
                    }
                }
                continue;
            }

            String lc = gloss.toLowerCase();
            stem = stem.toLowerCase();

            boolean isAntonym = false;
            if (pos == 'n') {
                isAntonym = lc.contains("not a " + stem)
                    || lc.contains("not an " + stem)
                    || lc.contains("absence of " + stem)
                    || lc.contains("lack of " + stem);
            }
            else if (pos == 'v') {
                isAntonym = lc.contains("to not " + stem)
                    || lc.contains("to " + stem + " incorrectly")
                    || lc.contains("to " + stem + " badly")
                    || lc.contains("to " + stem + " wrongly")
                    || lc.contains("to " + stem + " improperly");
            }
            else if (pos == 'a' || pos == 'r') {
                isAntonym = lc.contains("not " + stem)
                    || lc.contains("opposing " + stem)
                    || lc.contains("opposed to " + stem)
                    || lc.contains("not capable of " + stem);                    
            }

            // System.out.printf("%s -> %s :: %s ? %s%n", lemma, stem,
            //                       gloss, isAntonym);
            if (isAntonym) {
                System.out.println(sense + "\t" + stem);
            }
           
        }
        
    }

    static boolean isInWn(IDictionary dict, String lemma, POS pos) {
        return dict.getIndexWord(lemma, pos) != null;
    }

}
