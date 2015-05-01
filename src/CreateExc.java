import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;


/**
 * Creates the WordNet exception files (.exc) that contain morphological
 * exceptions
 */
public class CreateExc {

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("usage: java CreateExc wikt.LEXICALIZATIONS.tsv"+
                               " WordNet-3.0/dict/");
            return;
        }

        File lexFile = new File(args[0]);
        String wnpath = args[1];

        // We exclude lemmas that don't match this regex
        Pattern validLemma = Pattern.compile("[a-zA-Z_'\\-0-9]+");

        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        
        // construct the dictionary object and open it
        IDictionary dict =
            // new edu.mit.jwi.RAMDictionary(url,
            //     edu.mit.jwi.data.ILoadPolicy.IMMEDIATE_LOAD);
            new edu.mit.jwi.Dictionary(url);
        dict.open();


        BufferedReader br = new BufferedReader(new FileReader(lexFile));
        next_line:
        for (String line = null; (line = br.readLine()) != null; ) {
            String[] arr = line.split("\t");
            if (arr.length < 3) {
                // System.out.println("ERROR: " + line);
                continue;
            }
            String sense = arr[0];
            int i = sense.lastIndexOf('.');
            String lexicalization = sense.substring(0, i);
            i = lexicalization.lastIndexOf('.');
            char pos = lexicalization.charAt(i+1);
            lexicalization = lexicalization.substring(0, i);
            String lemma = arr[2].replace(" ", "_");

            // Sometimes wiki markup will sneak into the lemmas.  We can still
            // salvage the lemma provided that
            // 
            // (1) all text occurs within [[ markup ]]
            // (2) markup is completed, i.e., no [[ markups
            //
            // If those conditions are met, strip out the markup

            int x = lemma.indexOf('['), y = lemma.indexOf(']');
            int lb = 0, rb = 0;
            for (int j = 0; j < lemma.length(); ++j) {
                char c = lemma.charAt(j);
                if (c == '[') {
                    lb++;
                }
                else if (c == ']') {
                    rb++;
                }
                // Everything but a space
                else if (!(c == '_')) {
                    // If we're outside of matching braces and the lemma
                    // contains some markup, then skip it
                    if (rb == lb && (x > 0 || y > 0)) {
                        //
                        //System.out.println("SKIPPING mixed-text:
                        // " +lexicalization + " " + lemma);
                        continue next_line;
                    }
                }
            }
            if (rb != lb) {
                // System.out.println("SKIPPING unbalanced: "
                //                    +lexicalization + " " + lemma);
            }

            lemma = lemma.replace("[", "");
            lemma = lemma.replace("]", "");
            
            // Avoid including junk that was accidentally extracted from
            // Wiktionary
            if (!validLemma.matcher(lemma).matches()
                || !validLemma.matcher(lexicalization).matches()) {
                //System.out.println("SKIPPING: " +lexicalization + " " + lemma);
                continue;
            }
            
            if (lexicalization.equals(lemma))
                continue;

            // Skip the cases that morphological analyzer can probably pick up
            if (lexicalization.equals(lemma + "s"))
                continue;

            // Perform a sanity check that we're reporting a lexicalization for
            // a lemma that is in WordNet.  In some cases, Wiktionary flips the
            // order where the officially-recognized version in WordNet is
            // considered the variant.  Such reversed lexicalizations fail when
            // looking them up, so flip the text.
            POS pos_ = toPos(pos);
            boolean isLexicalizationInWn = isInWn(dict, lexicalization, pos_);
            boolean isLemmaInWn = isInWn(dict, lemma, pos_);
            if (!isLemmaInWn) {
                if (lemma.contains("_")) {
                    isLemmaInWn = isInWn(dict, lemma.replace("_", "-"), pos_);
                    lemma = lemma.replace("_", "-");
                }
                else if (lemma.contains("-")) {
                    isLemmaInWn = isInWn(dict, lemma.replace("-", "_"), pos_);
                    lemma = lemma.replace("-", "_");
                }
            }

            // System.out.printf("%s -> %s ; %s -> %s%n",
            //                   lexicalization, isLexicalizationInWn,
            //                   lemma, isLemmaInWn);

            // If neither (or both) are in WordNet, then don't bother reported this
            // lexicalization since it won't matter
            if (isLexicalizationInWn == isLemmaInWn) {
                continue;
            }
            // If Wiktionary's official-lemma order is reversed, flip the texts
            else if (!isLemmaInWn && isLexicalizationInWn) {
                String tmp = lexicalization;
                lexicalization = lemma;
                lemma = tmp;
            }
            
            System.out.println(lexicalization + " " + lemma);
        }
        br.close();
    }

    static boolean isInWn(IDictionary dict, String lemma, POS pos) {
        return dict.getIndexWord(lemma, pos) != null;
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
