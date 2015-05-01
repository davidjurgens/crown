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

public class ComputeInvLemmaGlossFreq {

    static StanfordCoreNLP pipeline = null;

    public static void main(String[] args) throws Exception {

        File glossesFile = new File(args[0]);
        
        java.util.Properties props = new java.util.Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma");
        pipeline = new StanfordCoreNLP(props);

        Counter<String> lemmaCounts = new ObjectCounter<String>();
        int numGlosses = 0;

        for (String line : new LineReader(glossesFile)) {
            ++numGlosses;
            String[] arr = line.split("\t");
            String gloss = arr[1];
            for (String lemma : getLemmas(gloss))
                lemmaCounts.count(lemma);
        }

        for (Map.Entry<String,Integer> e : lemmaCounts) {
            double freq = e.getValue().doubleValue() / numGlosses;
            System.out.println(e.getKey() + "\t" + freq + "\t" + Math.log(freq));
        }
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
                char c = pos.substring(0,1).toLowerCase().charAt(0);
                // Not sure if we need to pos tag... but at least avoid putting
                // in everything but content
                if (c == 'n' || c == 'j' || c == 'r' 
                        || (c == 'v' && !STOP_VERBS.contains(lemma)))
                                       
                    lemmas.add(lemma);
            }
        }
        return lemmas;
    }
       

}
