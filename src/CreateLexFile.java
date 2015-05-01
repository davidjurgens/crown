import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
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


public class CreateLexFile {
    
    public static void main(String[] args) throws Exception {

        if (args.length != 5) {
            System.out.println("usage: java lemma-to-parent.tsv " +
                               "synset-to-lexid.tsv " +
                               "sense-to-gloss.tsv " +
                               "wordnet/dict/ " +
                               "lexfile-pos-type");
            return;
        }

        File lemmaToParentFile = new File(args[0]);
        File s2lFile = new File(args[1]);
        File s2gFile = new File(args[2]);
        String wnpath = args[3];
        String lexfilePosType = args[4];

        Pattern validLemma = Pattern.compile("[a-zA-Z_0-9\\-']+[a-zA-Z0-9]");
        Pattern endsInNumber = Pattern.compile(".*[0-9]");
        Pattern ascii = Pattern.compile("\\p{ASCII}+");

        boolean isAdj = lexfilePosType.equals("adj");
        boolean isAdv = lexfilePosType.equals("adv");
        boolean isVerb = lexfilePosType.equals("verb");
        
        // Load the WN library
        URL url = null;
        try{ url = new URL("file", null, wnpath); } 
        catch (MalformedURLException e){ e.printStackTrace(); }
        if (url == null) return;
        IDictionary dict = new edu.mit.jwi.Dictionary(url);
        dict.open();
        edu.mit.jwi.item.POS[] poss = new edu.mit.jwi.item.POS[]
            { POS.NOUN,  POS.VERB, POS.ADJECTIVE, POS.ADVERB  };
        
        Counter<String> hyponymCount = new ObjectCounter<String>();

        // Map each gloss to its synset-id
        for (POS pos : poss) {
            Iterator<ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset syn = iter.next();
                List<ISynsetID> hypos = syn.getRelatedSynsets(Pointer.HYPONYM);
                String id = syn.getID().toString()
                    .replace("SID-", "").toLowerCase();
                if (hypos.size() > 0)
                    hyponymCount.count(id, hypos.size());
            }
        }


        // Load in the mapping from synset-id to the canonical name of lemmas
        // used in the lexicographer files
        Map<String,String> synsetIdToLexfileId =
            new HashMap<String,String>();
        for (String line : new LineReader(s2lFile)) {
            String[] arr = line.split("\t");
            synsetIdToLexfileId.put(arr[0], arr[1]);
        }

        Map<String,String> senseToGloss =
            new HashMap<String,String>();
        for (String line : new LineReader(s2gFile)) {
            String[] arr = line.split("\t");
            senseToGloss.put(arr[0], arr[1]);
        }


        // Grind's lexfile processing is case insensitive, so even if we have
        // "brutalist" and "Brutalist", they end up being tagged the same lemma,
        // causing a "is not unique" error.  Guard against this by keeping track
        // of how many time we see the lower-cased lemma and appending a number
        // to it appropriately
        Counter<String> lemmaCounts = new ObjectCounter<String>();

        // Print out the magic header
        System.out.println("(" + lexfilePosType + ".wikt)\n");

        //int cur = 0, start = 0, stop = 40000;

        // Read in each candidate and produce a lex-file entry for it
        for (String line : new LineReader(lemmaToParentFile)) {

            // if (++cur < start)
            //     continue;
            // if (cur == stop)
            //     break;
            
            String[] arr = line.split("\t");
            // Lemmas have a POS tag, so remove that
            String lemma = arr[0].substring(0, arr[0].indexOf('.'));
            if (!validLemma.matcher(lemma).matches()) {
                System.err.println("Excluding invalid lemma: " + lemma);
                continue;
            }

            // Exclude really long lemmas
            if (lemma.length() >=
                    "run_around_like_a_chicken_with_its_head_cut_off".length()) {
                System.err.println("Excluding too-long lemma: " + lemma);
                continue;
            }
            
            // Guard against grind's case-insensitivity
            int count = lemmaCounts.count(lemma.toLowerCase());
            if (count > 1)
                lemma += count; // append the number

            // WordNet's grind program interprets the numbers of the end of
            // files as sense markers.  Since everything here is monosemous, we
            // don't have these and need to indicate the number is a lexical
            // form
            if (endsInNumber.matcher(lemma).matches())
                lemma += "\"";

            String synsetId = arr[1];

            // Check that we haven't exceeded the maximum number of hyponyms for
            // this synset
            int numHypos = hyponymCount.getCount(synsetId);
            if (numHypos > 800) {
                // Can't attach :(
                System.err.printf("Excluding %s because over the limit " +
                                   "for %s%n", lemma, synsetId);
                continue;
            }
            hyponymCount.count(synsetId);
            
            String lexfileId = synsetIdToLexfileId.get(synsetId);
            if (lexfileId == null) {
                throw new Error("no lexfile mapping!: " + line);
            }
            String wiktSense = arr[2];
            String gloss = senseToGloss.get(wiktSense);

            // Try removing non-ascii glosses, which seem to wreck grind when
            // figuring out byte offsets :(
            Matcher m = ascii.matcher(gloss);
            if (!m.matches()) {
                System.err.printf("Excluding %s for having a gloss " +
                                  "with non-ascii characters%n", lemma);
                continue;
            }
            
            if (gloss == null) {
                throw new Error("no gloss mapping!: " + line);
            }

            // Check if this attachment has any extra relationships, which for
            // now are limited to antonym
            Map<String,String> lexfileIdToOtherRelation = 
                new HashMap<String,String>();
            for (int i = 5; i < arr.length; ++i) {
                String[] arr2 = arr[i].split(":");
                String rel = arr2[0]; // this is human readable
                String relatedSynsetId = arr2[1];

                // Lookup how we relate this synset in the lexfile
                String lexfileRel = "!"; // FIXME
                
                // Get what is related
                String relLexfileId = synsetIdToLexfileId.get(synsetId);

                lexfileIdToOtherRelation.put(relLexfileId, lexfileRel);
                
            }

            
            // Adjectives do not have hypernyms.  Instead we report the
            // relationships as "Similar to"
            if (isAdj) {
                // We seem to have gloss collisions with a few nouns, so ensure
                // we're pointing to the right POS
                if (lexfileId.startsWith("noun")) {
                    System.out.println("{ " + lemma + ", (" +
                                       gloss + ") }");                    
                }
                else {
                    // NOTE: During the attachment process, we'll try to
                    // identify the most similar sysnet, which sometimes is
                    // actually the antonym.  If the antonym extraction has
                    // succeeded, then lexfileIdToOtherRelation will contain a
                    // key with the same lexfileId indicating the item is an
                    // antonym.  If so, don't report lexfileId as both "similar
                    // to" and an antonym.
                    System.out.print("{ " + lemma + ", ");
                    if (!lexfileIdToOtherRelation.containsKey(lexfileId))
                        System.out.print(lexfileId + ",& ");

                    // Print all the extra relations found for this synset
                    for (Map.Entry<String,String> e
                             : lexfileIdToOtherRelation.entrySet()) 
                        System.out.print(e.getKey() + "," + e.getValue() + " ");
                    
                    System.out.println(" (" + gloss + ") }");
                }
            }
            // Adverbs don't have any relatioships except for derivational ones
            // (i.e., from which adjective an adverb may be derived).
            // Therefore, we just add the gloss
            else if (isAdv) {
                System.out.print("{ " + lemma + ", ");
                // Print all the extra relations found for this synset
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    System.out.print(e.getKey() + "," + e.getValue() + " ");

                System.out.println(" (" + gloss + ") }");
            }
            // Grind (and later, WordNet) complain if a verb has no frames.
            // Most resources expect them too.  Since we don't really know what
            // the frames are, we fake it.
            else if (isVerb) {
                System.out.print("{ " + lemma + ", " + lexfileId + ",@ ");
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    System.out.print(e.getKey() + "," + e.getValue() + " ");
                System.out.println(" frames: 1, 2 (" + gloss + ") }");
            }
            else {
                System.out.print("{ " + lemma + ", " + lexfileId + ",@ ");
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    System.out.print(e.getKey() + "," + e.getValue() + " ");
                System.out.println(" (" + gloss + ") }");
            }            
        }        
    }
    
}
