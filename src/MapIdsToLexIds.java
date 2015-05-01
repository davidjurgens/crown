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


public class MapIdsToLexIds {
    
    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("usage: java lex-file-dir/ " +
                               "WN-dir/");
            return;
        }

        File lexFileDir = new File(args[0]);
        String wnpath = args[1];
        // File idToKeyFile = new File(args[3]);
        
        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        IDictionary dict = new edu.mit.jwi.Dictionary(url);
        dict.open();

        Map<String,String> glossToLexfileId =
            new HashMap<String,String>();

        // Try parsing the noun files to get their contexts
        for (File f : lexFileDir.listFiles()) {
            String lexFile = f.getName();
            // Skip other POS tags for now
            if (!((lexFile.startsWith("noun")
                   || lexFile.startsWith("verb")
                   || lexFile.startsWith("adj")
                   || lexFile.startsWith("adv"))
                  && !lexFile.contains("wikt")))
                continue;


            // Special handling of adjectives to deal with the head/satellite
            // synsets
            if (lexFile.startsWith("adj")) {
                parseAdjFile(f, glossToLexfileId);
                continue;
            }
            // else if (1 == 1)
            //     continue;
            
            for (String line : new LineReader(f)) {
                if (line.length() < 2
                    || !(line.charAt(0) == '{' || line.charAt(0) == '['
                         // SPECIAL CASE: space in front of the lexicographer entry
                         || (line.charAt(0) == ' ' &&
                             (line.charAt(1) == '{' || line.charAt(1) == '[')))) {

                        continue;
                }
                // First, strip off the { } from the markup
                line = line.substring(1, line.length() - 1);
                // Second, get the gloss
                int i = line.indexOf('(');
               
                int j = line.lastIndexOf(')');
                if (i < 0 || j < 0)
                    throw new Error(line);

                // NOTE: adjectives have special markup that includes (a) (p)
                // (ip), so check to see if we're getting one of those
                while ((line.charAt(i+1) == 'a' && line.charAt(i+2) == ')')
                    || (line.charAt(i+1) == 'p' && line.charAt(i+2) == ')')
                    || (line.charAt(i+1) == 'i' && line.charAt(i+2) == 'p'
                        && line.charAt(i+3) == ')')) {
                    i = line.indexOf('(', i + 1);
                }



                String gloss = line.substring(i+1, j).trim();

                // Third, strip out the gloss so that we're left with either (1)
                // lemmas in the synset or (2) related lemmas, which have
                // trailing markup
                line = line.substring(0, i);

                // SPECIAL CASES FOR WEIRD ENTRIES:
                if (line.contains("hymeneal,")) {
                    String fullLemmaId = lexFile + ":hymeneal";
                    glossToLexfileId.put(gloss, fullLemmaId);
                    continue;
                }
                else if (line.contains("body-surf,")) {
                    String fullLemmaId = lexFile + ":body-asurf";
                    glossToLexfileId.put(gloss, fullLemmaId);
                    continue;                   
                }
                else if (line.contains("half-yearly,adj")) {
                    String fullLemmaId = lexFile + ":half-yearly";
                    glossToLexfileId.put(gloss, fullLemmaId);
                    continue;                   
                }

                // Get all the space-separated tokens
                String[] arr = line.split(" ");

                
                // Find tokens that don't end in markup
                for (String token : arr) {
                    if (token.length() == 0)
                        continue;
                    // All related tokens have lexicography markup appended to
                    // the end (e.g,. '@', '^'), whereas an lemma in the synset
                    // just has a regular ',' ending.  Pull these out
                    if (token.charAt(token.length() - 1) == ',') {

                        // Sometimes lexicographers make mistakes too.  If the
                        // lemma begins with a '[', then remove it
                        if (token.charAt(0) == '[') {
                            token = token.substring(1);
                            //System.err.println("check: " + line);
                        }

                        // NOTE: The adjective lexicographer files sometimes put
                        // the first adjective lemma in upper-case.  However,
                        // when referring to this lemma, it needs to be in
                        // lower-case.  However, some nouns are actually in
                        // upper- or mixed-casing, so we can't do this generally
                        if (lexFile.startsWith("adj")) {

                            token = token.toLowerCase();

                            // Also get rid of the special adjective markup in
                            // parentheses
                            int k = token.indexOf('(');
                            if (k > 0)
                                token = token.substring(0, k+1);

                            // Last, correct for some mistakes where the words
                            // don't have spaces between them
                            k = token.indexOf(',');
                            if (k > 0)
                                token = token.substring(0, k+1);
                        }
                        
                        String fullLemmaId = lexFile + ":"
                            + token.substring(0, token.length() - 1);
                        
                        //System.out.println(fullLemmaId + "\t->\t" + gloss);

                        // NOTE: we just take the first lemma we find for
                        // equating a synset-id with a lexfile-id.  For some
                        // reason, hypernyms are specified according to lemmas,
                        // rather than synsets, but these are then converted
                        // into synset relations.  As a result, it doesn't seem
                        // to matter which lemma we use.
                        glossToLexfileId.put(gloss, fullLemmaId);
                        break;
                    }
                }
            }
        }


        edu.mit.jwi.item.POS[] poss = new edu.mit.jwi.item.POS[]
            { POS.NOUN, POS.VERB, POS.ADJECTIVE, POS.ADVERB };

        // Map each gloss to its synset-id
        for (POS pos : poss) {
            Iterator<ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset syn = iter.next();
                String gloss = syn.getGloss();
                String id = syn.getID().toString()
                    .replace("SID-", "").toLowerCase();
                String lexfileId = glossToLexfileId.get(gloss);
                if (lexfileId == null) {
                    throw new Error("No lexfileId for " + syn.getWords()
                                    + " " + gloss);
                }
                System.out.println(id + "\t" + lexfileId);
            }
        }

        
    }

    static void parseAdjFile(File f, Map<String,String> glossToLexfileId) {
        // Each adjective entry is inputted as a cluster, with a unique head
        // synset and then option satellite synsets.  In order to refer to the
        // satellite synsets in the cluster, we must use the form:
        //
        // filename:headlemma^satellitelemma
        String headLemma = null;

        boolean nextLineIsAlsoHead = false;

        for (String line : new LineReader(f)) {
            String origLine = line;
            line = line.trim();
            if (line.length() < 2)
                continue;

            if (line.startsWith("--")) {
                nextLineIsAlsoHead = true;
                headLemma = null;
                continue;
            }

            boolean isHeadSynset = false;
            if (nextLineIsAlsoHead) {
                isHeadSynset = true;
                nextLineIsAlsoHead = false;
            }
            
            if (line.startsWith("[{") || line.startsWith("[ {"))
                isHeadSynset = true;
            else if (!(line.charAt(0) == '{'))
                continue;
            
            // First, strip off the { } from the markup
            line = line.substring(1, line.length() - 1);
            // Second, get the gloss
            int i = line.indexOf('(');
            
            int j = line.lastIndexOf(')');
            if (i < 0 || j < 0)
                throw new Error(line);
            
            // NOTE: adjectives have special markup that includes (a) (p)
            // (ip), so check to see if we're getting one of those
            while ((line.charAt(i+1) == 'a' && line.charAt(i+2) == ')')
                   || (line.charAt(i+1) == 'p' && line.charAt(i+2) == ')')
                   || (line.charAt(i+1) == 'i' && line.charAt(i+2) == 'p'
                       && line.charAt(i+3) == ')')) {
                i = line.indexOf('(', i + 1);
            }

            String gloss = line.substring(i+1, j).trim();
            
            // Third, strip out the gloss so that we're left with either (1)
            // lemmas in the synset or (2) related lemmas, which have
            // trailing markup
            line = line.substring(0, i);
            
            // SPECIAL CASES FOR WEIRD ENTRIES:
            if (line.contains("hymeneal,")) {
                String fullLemmaId = f.getName() + ":hymeneal";
                glossToLexfileId.put(gloss, fullLemmaId);
                continue;
            }
            if (line.contains("LIGHT8 ,")) {
                String fullLemmaId = f.getName() + ":light8";
                glossToLexfileId.put(gloss, fullLemmaId);
                headLemma = "light8";
                continue;
            }

            
            // Get all the space-separated tokens
            String[] arr = line.split("\\s+");

            //if (origLine.contains("LIGHT8, "))
            //System.out.println(Arrays.toString(arr));
                
            // Find tokens that don't end in markup
            for (String token : arr) {
                //System.out.printf("Testing \"%s\"%n", token);
                if (token.length() < 2)
                    continue;
                // All related tokens have lexicography markup appended to
                // the end (e.g,. '@', '^'), whereas an lemma in the synset
                // just has a regular ',' ending.  Pull these out
                if (token.charAt(token.length() - 1) == ',') {
                    
                    // Sometimes lexicographers make mistakes too.  If the
                    // lemma begins with a '[', then remove it
                    if (token.charAt(0) == '[') {
                        token = token.substring(1);
                        //System.err.println("check: " + line);
                        if (token.length() == 1)
                            continue;
                    }

                    if (token.length() == 1)
                        continue;
                    
                    token = token.toLowerCase();
                    //System.out.printf("Testing \"%s\"%n", token);
                    
                    // Also get rid of the special adjective markup in
                    // parentheses
                    int k = token.indexOf('(');
                    if (k > 0)
                        token = token.substring(0, k+1);
                    
                    // Last, correct for some mistakes where the words
                    // don't have spaces between them
                    k = token.indexOf(',');
                    if (k > 0)
                       token = token.substring(0, k+1);
                
                
                    String lemma = token.substring(0, token.length() - 1);


                    String fullLemmaId = (headLemma == null) 
                        ? f.getName() + ":" + lemma
                        : f.getName() + ":" + headLemma + "^" + lemma;
                    
                    
                    //System.out.println(fullLemmaId + "\t->\t" + gloss);
                    
                    // NOTE: we just take the first lemma we find for equating a
                    // synset-id with a lexfile-id.  For some reason, hypernyms
                    // are specified according to lemmas, rather than synsets,
                    // but these are then converted into synset relations.  As a
                    // result, it doesn't seem to matter which lemma we use.
                    glossToLexfileId.put(gloss, fullLemmaId);
                    
                    // If this is the first sense in a cluster, then set this as the
                    // head lemma for others to use
                    if (isHeadSynset)
                        headLemma = lemma;
                    
                    // If this is the last synset in a sense cluster, then indicate
                    // that there is no head lemma for the next line
                    if (origLine.contains("}]") || origLine.contains("} ]"))
                        headLemma = null;
                    
                    break;
                }
            }
        }
    }

}
