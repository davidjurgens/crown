/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.mit.jwi.IDictionary;

import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.POS;

import edu.ucla.sspace.util.Counter;
import edu.ucla.sspace.util.Duple;
import edu.ucla.sspace.util.HashMultiMap;
import edu.ucla.sspace.util.LineReader;
import edu.ucla.sspace.util.MultiMap;
import edu.ucla.sspace.util.ObjectCounter;

import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;

import com.google.common.io.Files;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.CoreMap;

import ca.mcgill.cs.crown.CrownOperations.Reason;

import ca.mcgill.cs.crown.util.CrownLogger;


import edu.mit.jwi.morph.IStemmer;
import edu.mit.jwi.morph.SimpleStemmer;



/**
 *
 */
public class LexicographerFileCreator {

    /**
     * The empirically-determined maximum lemma length allowed in WordNet.
     */
    private static final int MAX_LEMMA_LENGTH = 47;

    /**
     * WordNet allows at most 999 Pointer relations to synsets, which we could
     * change in grind but this would end up breaking WordNet's parser and
     * likley, all the down-stream libraries.  This value is set intentionally
     * lower because using higher counts (but under the limit) still seem to
     * break grind.
     */
    private static final int MAX_POINTERS = 500;

    private static final int MAX_SENSES = 99;
    
    private static final  Pattern VALID_LEMMA =
        Pattern.compile("[a-zA-Z_0-9\\-']+[a-zA-Z0-9]");

    private static final Pattern ASCII = Pattern.compile("\\p{ASCII}+");

    private static final Pattern ENDS_WITH_NUMBER = Pattern.compile(".*[0-9]+");

    private static  final edu.mit.jwi.item.POS[] PARTS_OF_SPEECH =
        new edu.mit.jwi.item.POS[]
        { POS.NOUN,  POS.VERB, POS.ADJECTIVE, POS.ADVERB  };

    private static final IStemmer MORPHY = new SimpleStemmer();
    
    /**
     * A mapping from operation to perform to the symbol that indicates that
     * relation in the lexicographer files
     */
    private static final Map<Class<? extends CoreAnnotation<?>>,String> OPERATION_TO_SYMBOL =
        new HashMap<Class<? extends CoreAnnotation<?>>,String>();
    static {
        // This is in no way complete.  See
        // http://wordnet.princeton.edu/man/wninput.5WN.html
        OPERATION_TO_SYMBOL.put(CrownOperations.Antonym.class, "!");
        OPERATION_TO_SYMBOL.put(CrownOperations.Pertainym.class, "\\");
        OPERATION_TO_SYMBOL.put(CrownOperations.SimilarTo.class, "&");
        OPERATION_TO_SYMBOL.put(CrownOperations.MemberMeronym.class, "%m");
        //OPERATION_TO_SYMBOL.put(CrownOperations.SubstanceMeronym, "%s");
        OPERATION_TO_SYMBOL.put(CrownOperations.PartMeronym.class, "%p");
        OPERATION_TO_SYMBOL.put(CrownOperations.DomainTopic.class, ";c");
        // OPERATION_TO_SYMBOL.put(CrownOperations.DomainRegion, ";r");
        // OPERATION_TO_SYMBOL.put(CrownOperations.DomainUsage, ";u");
        OPERATION_TO_SYMBOL.put(CrownOperations.DerivationallyRelated.class, "+");
        OPERATION_TO_SYMBOL.put(CrownOperations.DerivedFromAdjective.class, "\\");
    }  

    private final IDictionary dict;

    /**
     * The count of how many pointers currently exist for each synset in the
     * present CROWN build.  This global counter is necessary to avoid going over
     * WordNet's internal limits, which 999 but in practice seems a bit less.
     */
    private final Counter<ISynset> pointerCounts;

    /**
     * The count for the number of senses for each word <strike>and POS
     * pair</strike> -- apparently sense counts are per lemma only.  We need to
     * keep track of this to avoid going over the maximum number of senses per
     * lemma.
     */
    //private final Counter<Duple<String,POS>> senseCounts;
    private final Counter<String> senseCounts;

    public LexicographerFileCreator(IDictionary dict) {
        this.dict = dict;
        pointerCounts = new ObjectCounter<ISynset>(250_000);
        senseCounts = new ObjectCounter<String>(250_000);
    }

    public List<AnnotatedLexicalEntry> integrate(
            File oldLexFileDir, File newLexFileDir,
            List<AnnotatedLexicalEntry> toIntegrate,
            File oldDictDir, File newDictDir)
                throws IOException {

        // Get the mapping from a synset to the string denoting it in the
        // lexicographer files (e.g., "noun.group:kingdom2")
        Map<ISynset,String> synsetToLexFileId 
            = mapSynsetsToLexFileIds(oldLexFileDir);
        MultiMap<String,ISynset> glossToSynset
            = getGlossSynsetMapping();
        
        pointerCounts.reset();
        for (POS pos : PARTS_OF_SPEECH) {
            Iterator<ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset syn = iter.next();
                int count = syn.getRelatedMap().size();
                if (count > 0)
                    pointerCounts.count(syn, count);

                for (IWord iw : syn.getWords()) {
                    senseCounts.count(iw.getLemma());
                }                                                    
            }
        }

        List<AnnotatedLexicalEntry> integrated =
            new ArrayList<AnnotatedLexicalEntry>();

        // Fundamentally, all enties at this point must have (a) a Synonym, (b)
        // a Hypernym operation (c) or, for adverbs and adjectives only, have
        // some valid relation to another synset, or (d) be a Lexicalization
        // operation.  Given this, we split them into to groups and strip out
        // the leixcalizations and then create a reverse mapping from the
        // existing ISynset to the operations that should be performed to it.
        // This simplifies the logic for later stages.
        MultiMap<ISynset,AnnotatedLexicalEntry> synsetToMergeOps
            = new HashMultiMap<ISynset,AnnotatedLexicalEntry>();
        MultiMap<ISynset,AnnotatedLexicalEntry> synsetToHypernymAttachOps
            = new HashMultiMap<ISynset,AnnotatedLexicalEntry>();
        // For ADJECTIVE and ADVERB instances (no hypernym to attach to!)
        List<AnnotatedLexicalEntry>  relationAttachOps =
            new ArrayList<AnnotatedLexicalEntry>(toIntegrate.size());
        List<AnnotatedLexicalEntry> exceptionOps =
            new ArrayList<AnnotatedLexicalEntry>(toIntegrate.size());

       
        // Figure out into which operational group this entry belongs
        for (AnnotatedLexicalEntry ale : toIntegrate) {

            // Do a bit of preprocessing to remove entries that have features
            // incompatible with grind's expectations.
            if (!isValidEntry(ale))
                continue;


            // Check that we haven't exceeded the maximum number of senses for
            // this lemma
            if (senseCounts.getCount(ale.getLemma()) > MAX_SENSES) {
                continue;
            }           
            
            // If the entry is listed as an exception, send it to that
            // processing, regardless of what else we were supposed to do (In
            // practice, it should *only* be a lexicalization anyway, so this is
            // the correct thing to do).
            Set<Duple<Reason,String>> excOp = ale.getOperations()
                .get(CrownOperations.Lexicalization.class);
            if (excOp != null) {
                exceptionOps.add(ale);
                continue;
            }
            
            Duple<Reason,ISynset> mergeOp = ale.getOperations()
                .get(CrownOperations.Synonym.class);
            Duple<Reason,ISynset> hypAttachOp = ale.getOperations()
                .get(CrownOperations.Hypernym.class);
            
            if (mergeOp == null && hypAttachOp == null && excOp == null
                && ! (ale.getPos().equals(POS.ADJECTIVE) 
                      || ale.getPos().equals(POS.ADVERB))) {
                throw new IllegalStateException(
                    "During lexicographer file creation, encountered entry "+
                    "with no operation to integrate it: " + ale);
            }
            assert (excOp != null && (mergeOp == null && hypAttachOp == null))
                || (mergeOp != null && (excOp == null && hypAttachOp == null))
                || (hypAttachOp != null && (excOp == null && mergeOp == null))
                : "conflicting operations for " + ale;

            if (mergeOp != null) {
                // Guard against the case where we have two senses with the same
                // lemma that are supposed to be merged into the same synset.
                Set<AnnotatedLexicalEntry> cur = synsetToMergeOps.get(mergeOp.y);
                boolean isLemmaAlreadyPresent = false;
                for (AnnotatedLexicalEntry ale2 : cur) {
                    if (ale2.getLemma().equals(ale.getLemma())) {
                        isLemmaAlreadyPresent = true;
                        break;
                    }
                }
                if (!isLemmaAlreadyPresent)
                    synsetToMergeOps.put(mergeOp.y, ale);
            }
            // If the entry is an ADJECTIVE or ADVERB and we're not merging,
            // then it needs to be attached by a relation, so send it there.
            else if (ale.getPos().equals(POS.ADJECTIVE)
                     || ale.getPos().equals(POS.ADVERB)) {
                relationAttachOps.add(ale);
                continue;
            }           
            else {
                synsetToHypernymAttachOps.put(hypAttachOp.y, ale);
            }
        }

        copyMiscFiles(oldLexFileDir, newLexFileDir);

        if (1==1) {
            // Generate the new noun and verb CROWN files, which only contain
            // pointers to other file's synsets.
            integrated.addAll(
            createAttachmentLexFiles(newLexFileDir, synsetToHypernymAttachOps,
                                     synsetToLexFileId));
        
        
            // Generate the new adverb and adjective CROWN files, which only
            // contain pointers to other file's synsets.
            integrated.addAll(
            createRelationBasedLexFiles(newLexFileDir, relationAttachOps,
                                        synsetToLexFileId));
        }
        

        // Generate the new CROWN files, which only contain pointers to other
        // file's synsets.
        if (1==0) {
            copyLexFiles(oldLexFileDir, newLexFileDir);
        }
        else {
            integrated.addAll(mergeNewLemmas(oldLexFileDir, newLexFileDir,
                                             synsetToMergeOps, glossToSynset));
        }
        
        // Last, generate the exception files that contain the morphological
        // variants to words that aren't recognized already by WordNet's
        // Morphy functionality.
        // See http://wordnet.princeton.edu/man/morphy.7WN.html
        integrated.addAll(
            createExceptionFiles(exceptionOps, oldDictDir, newDictDir));

        // Helpful debugging code for trying to determing why exactly grind is
        // crashing (which is often :( )
        /*
          
        PrintWriter tmp = new PrintWriter("sense-counts.tsv");
        for (Map.Entry<String,Integer> e9 : senseCounts) {
            //Duple<String,POS> d = e9.getKey();
            tmp.println(e9.getKey() + "\t" + e9.getValue());
        }
        tmp.close();

        tmp = new PrintWriter("pointer-counts.tsv");
        for (Map.Entry<ISynset,Integer> e9 : pointerCounts) {
            tmp.println(e9.getKey() + "\t" + e9.getValue());
        }
        tmp.close();
        */
        
        return integrated;
    }

    /**
     * Copies over the non-lexicographer files used by grind, such as the sense
     * counts.
     */
    private void copyMiscFiles(File oldLexFileDir, File newLexFileDir)
            throws IOException {
        
        for (File lexFile : oldLexFileDir.listFiles()) {
            String name = lexFile.getName();

            // Skip over the lexicographer files
            if (name.startsWith("adj") || name.startsWith("noun")
                  || name.startsWith("adv") || name.startsWith("verb")) {
                continue;
            }

            // Copy everything else
            Files.copy(lexFile, new File(newLexFileDir, name));
        }
    }
    
    /**
     * TODO.
     *
     * @return the list of {@link AnnotatedLexicalEntry} that were actually used
     *         added as new synsets in CROWN.  All other entries not in this list
     *         were discarded.
     */
    private List<AnnotatedLexicalEntry>
            createAttachmentLexFiles(File newLexFileDir,
                   MultiMap<ISynset,AnnotatedLexicalEntry> synsetToAttachOps,
                   Map<ISynset,String> synsetToLexFileId)
                throws IOException {

        // Set up all the POS-specific bookkeeping data we'll need
        Map<POS,Counter<String>> posToLemmaCounts
            = new HashMap<POS,Counter<String>>();
        Map<POS,Set<String>> posToLemmaIds
            = new HashMap<POS,Set<String>>();
        Map<POS,PrintWriter> posToLexFile = new HashMap<POS,PrintWriter>();
        for (POS pos : new POS[] { POS.NOUN, POS.VERB }) {
            posToLemmaCounts.put(pos, new ObjectCounter<String>());
            posToLemmaIds.put(pos, new HashSet<String>());
            // Create a new lexicographer file for this POS that doesn't
            // conflict with prior iterations' files
            for (int i = 0; i < 1000; ++i) {
                String filename = String.format("%s.%d.crown", toStr(pos), i);
                File lexFile = new File(newLexFileDir, filename);
                if (lexFile.exists())
                    continue;
                PrintWriter pw = new PrintWriter(lexFile);
                posToLexFile.put(pos, pw);
                break;
            }
            assert posToLexFile.size() == 2
                : "not all new lexfiles created";
        }

        // This list will contain all the entries we end up adding a new synsets
        List<AnnotatedLexicalEntry> incorporated =
            new ArrayList<AnnotatedLexicalEntry>(synsetToAttachOps.range());
                                 

        for (Map.Entry<ISynset,AnnotatedLexicalEntry> ent 
                 : synsetToAttachOps.entrySet()) {

            ISynset hypernym = ent.getKey();
            AnnotatedLexicalEntry toAttach = ent.getValue();

            // First check that we haven't already exceeded the maximum number
            // of pointers for this synset.  If so, we are forced to skip what
            // might be a valid operation. :(
            int parentPointerCount = pointerCounts.getCount(hypernym);
            if (parentPointerCount >= MAX_POINTERS)
                continue;
            
            String lemma = toAttach.getLemma();
            POS pos = toAttach.getPos();

            // Check that we haven't exceeded the maximum number of senses for
            // this lemma
            if (senseCounts.getCount(lemma) > MAX_SENSES) {
                continue;
            }
            
            String hypernymLexFileId = synsetToLexFileId.get(hypernym);
            assert hypernymLexFileId != null
                : "Unmapped synset in the lexicographer files: " + hypernym;

            String lemmaId = createLexFileId(lemma, posToLemmaCounts.get(pos),
                                             posToLemmaIds.get(pos));
            // This happens when a lemma appears more than 15 times in a single
            // lexicographer file.  Future work should probably spin off this
            // Entry into yet another lex file... -dj
            if (lemmaId == null)
                continue;

            // Clean up the gloss to ensure that it has matching parentheses,
            // which grind requires to properly parse.
            String gloss = toAttach.getAnnotations()
                .get(CrownAnnotations.Gloss.class);
            gloss = cleanGloss(gloss);

            // For all the other relations present in this entry, create a
            // mapping of the analogous relation marker in the lex file and the
            // lex-id of the related synset.  If we can't include a relation due
            // to it pointing to a synset with too many pointers, the delete it
            // so that the Entry is a compete representation of what was
            // included
            Map<String,String> lexfileIdToOtherRelation
                = new HashMap<String,String>();
            CoreMap operations = toAttach.getOperations();
            for (Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>> op
                     : CrownOperations.SET_ARG_OPERATIONS) {
                Set<Duple<Reason,ISynset>> relatedSyns = operations.get(op);
                if (relatedSyns == null)
                    continue;

                // This happens when we have more relation types than are
                // representable in WordNet
                String relSymbol = OPERATION_TO_SYMBOL.get(op);
                if (relSymbol == null)
                    continue;
                
                Iterator<Duple<Reason,ISynset>> iter = relatedSyns.iterator();
                next_relation:
                while (iter.hasNext()) {
                    Duple<Reason,ISynset> dup = iter.next();
                    ISynset related = dup.y;
                    int curPointerCount = pointerCounts.getCount(related);
                    if (curPointerCount >= MAX_POINTERS) {
                        iter.remove();
                        continue;
                    }
                    for (IWord iw : related.getWords()) {
                        if (senseCounts.getCount(iw.getLemma()) > MAX_SENSES) {
                            iter.remove();
                            continue next_relation;
                        }
                    }


                    pointerCounts.count(related);
                    String relatedLexId = synsetToLexFileId.get(related);
                    assert relatedLexId != null
                        : "Unmapped synset in the lex files: " + related;
                    lexfileIdToOtherRelation.put(relatedLexId, relSymbol);
                }
                // Remove this op if we did add anything from it
                if (relatedSyns.isEmpty())
                    operations.remove(op);
            }
            next_single_arg_relation:
            for (Class<? extends CoreAnnotation<Duple<Reason,ISynset>>> op
                     : CrownOperations.SINGLE_ARG_OPERATIONS) {
                Duple<Reason,ISynset> dup = operations.get(op);
                if (dup == null)
                    continue;
                // This happens when we have more relation types than are
                // representable in WordNet
                String relSymbol = OPERATION_TO_SYMBOL.get(op);
                if (relSymbol == null)
                    continue;

                ISynset related = dup.y;
                int curPointerCount = pointerCounts.getCount(related);
                if (curPointerCount >= MAX_POINTERS) {
                    operations.remove(op);
                    continue;
                }

                for (IWord iw : related.getWords()) {
                    if (senseCounts.getCount(iw.getLemma()) > MAX_SENSES) {
                        operations.remove(op);
                        continue next_single_arg_relation;
                    }
                }


                pointerCounts.count(related);
                String relatedLexId = synsetToLexFileId.get(related);
                assert relatedLexId != null
                    : "Unmapped synset in the lex files: " + related;
                lexfileIdToOtherRelation.put(relatedLexId, relSymbol);
            }

            // Write the entry in the appropriate lexicographer file
            PrintWriter lexFilePw = posToLexFile.get(pos);            
            switch (pos) {
            case NOUN: {
                lexFilePw.print("{ " + lemmaId + ", " + hypernymLexFileId + ",@ ");
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    lexFilePw.print(e.getKey() + "," + e.getValue() + " ");
                lexFilePw.println(" (" + gloss + ") }");
                break;
            }
            case VERB: {
                lexFilePw.print("{ " + lemmaId + ", " + hypernymLexFileId + ",@ ");
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    lexFilePw.print(e.getKey() + "," + e.getValue() + " ");

                // Grind (and later, WordNet) complains if a verb has no frames.
                // Most resources expect them too.  Since we don't really know
                // what the frames are, we fake it.  However, it would be useful
                // to try learning the frames for new words from corpora, if
                // possible.  One idea is to try adapting the work of "Inducing
                // Example-based Semantic Frames from a Massive Amount of Verb
                // Use" by Kawahara et al. @ EACL-2014
                lexFilePw.println(" frames: 1, 2 (" + gloss + ") }");
                break;
            }
            case ADJECTIVE: 
            case ADVERB:
            default:
                // These shouldn't be in this method
                throw new IllegalStateException("Unhandled POS: " + pos);
            }

            pointerCounts.count(hypernym);
            incorporated.add(toAttach);
            senseCounts.count(lemma);
        }

        for (PrintWriter pw : posToLexFile.values())
            pw.close();
        
        return incorporated;
    }

    /**
     * TODO.
     *
     * @return the list of {@link AnnotatedLexicalEntry} that were actually used
     *         added as new synsets in CROWN.  All other entries not in this list
     *         were discarded.
     */
    private List<AnnotatedLexicalEntry>
            createRelationBasedLexFiles(File newLexFileDir,
                   List<AnnotatedLexicalEntry> relationAttachOps,
                   Map<ISynset,String> synsetToLexFileId)
                throws IOException {

        // Only adjective and adverbs
        // Set up all the POS-specific bookkeeping data we'll need
        Map<POS,Counter<String>> posToLemmaCounts
            = new HashMap<POS,Counter<String>>();
        Map<POS,Set<String>> posToLemmaIds
            = new HashMap<POS,Set<String>>();
        Map<POS,PrintWriter> posToLexFile = new HashMap<POS,PrintWriter>();
        for (POS pos : new POS[] { POS.ADJECTIVE, POS.ADVERB }) {
            posToLemmaCounts.put(pos, new ObjectCounter<String>());
            posToLemmaIds.put(pos, new HashSet<String>());
            // Create a new lexicographer file for this POS that doesn't
            // conflict with prior iterations' files
            for (int i = 0; i < 1000; ++i) {
                String filename = String.format("%s.%d.crown", toStr(pos), i);
                File lexFile = new File(newLexFileDir, filename);
                if (lexFile.exists())
                    continue;
                PrintWriter pw = new PrintWriter(lexFile);
                posToLexFile.put(pos, pw);
                break;
            }
            assert posToLexFile.size() == 2
                : "not all new lexfiles created";
        }

        // This list will contain all the entries we end up adding a new synsets
        List<AnnotatedLexicalEntry> incorporated =
            new ArrayList<AnnotatedLexicalEntry>(relationAttachOps.size());
                                

        for (AnnotatedLexicalEntry ent : relationAttachOps) {


            String lemma = ent.getLemma();
            POS pos = ent.getPos();

            String lemmaId = createLexFileId(lemma, posToLemmaCounts.get(pos),
                                             posToLemmaIds.get(pos));
            // This happens when a lemma appears more than 15 times in a single
            // lexicographer file.  Future work should probably spin off this
            // Entry into yet another lex file... -dj
            if (lemmaId == null)
                continue;

            // Clean up the gloss to ensure that it has matching parentheses,
            // which grind requires to properly parse.
            String gloss = ent.getAnnotations()
                .get(CrownAnnotations.Gloss.class);
            gloss = cleanGloss(gloss);

            // For all the other relations present in this entry, create a
            // mapping of the analogous relation marker in the lex file and the
            // lex-id of the related synset.  If we can't include a relation due
            // to it pointing to a synset with too many pointers, the delete it
            // so that the Entry is a compete representation of what was
            // included
            Map<String,String> lexfileIdToOtherRelation
                = new HashMap<String,String>();
            CoreMap operations = ent.getOperations();
            next_set_arg_operation:
            for (Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>> op
                     : CrownOperations.SET_ARG_OPERATIONS) {
                Set<Duple<Reason,ISynset>> relatedSyns = operations.get(op);
                if (relatedSyns == null)
                    continue;

                // This happens when we have more relation types than are
                // representable in WordNet
                String relSymbol = OPERATION_TO_SYMBOL.get(op);
                if (relSymbol == null)
                    continue;
                
                Iterator<Duple<Reason,ISynset>> iter = relatedSyns.iterator();
                while (iter.hasNext()) {
                    Duple<Reason,ISynset> dup = iter.next();
                    ISynset related = dup.y;
                    int curPointerCount = pointerCounts.getCount(related);
                    if (curPointerCount >= MAX_POINTERS) {
                        iter.remove();
                        continue;
                    }
                    
                    for (IWord iw : related.getWords()) {
                        if (senseCounts.getCount(iw.getLemma()) > MAX_SENSES) {
                        iter.remove();
                        continue next_set_arg_operation;
                        }
                    }



                    pointerCounts.count(related);
                    String relatedLexId = synsetToLexFileId.get(related);
                    assert relatedLexId != null
                        : "Unmapped synset in the lex files: " + related;
                    lexfileIdToOtherRelation.put(relatedLexId, relSymbol);
                }
                // Remove this op if we did add anything from it
                if (relatedSyns.isEmpty())
                    operations.remove(op);
            }
            next_sao:
            for (Class<? extends CoreAnnotation<Duple<Reason,ISynset>>> op
                     : CrownOperations.SINGLE_ARG_OPERATIONS) {
                Duple<Reason,ISynset> dup = operations.get(op);
                if (dup == null)
                    continue;
                // This happens when we have more relation types than are
                // representable in WordNet
                String relSymbol = OPERATION_TO_SYMBOL.get(op);
                if (relSymbol == null)
                    continue;

                ISynset related = dup.y;
                int curPointerCount = pointerCounts.getCount(related);
                if (curPointerCount >= MAX_POINTERS) {
                    operations.remove(op);
                    continue;
                }
                for (IWord iw : related.getWords()) {
                    if (senseCounts.getCount(iw.getLemma()) > MAX_SENSES) {
                        continue next_sao;
                    }
                }


                pointerCounts.count(related);
                String relatedLexId = synsetToLexFileId.get(related);
                assert relatedLexId != null
                    : "Unmapped synset in the lex files: " + related;
                lexfileIdToOtherRelation.put(relatedLexId, relSymbol);
            }

            // Write the entry in the appropriate lexicographer file
            PrintWriter lexFilePw = posToLexFile.get(pos);            
            switch (pos) {
            case ADVERB:
            case ADJECTIVE: {
                lexFilePw.print("{ " + lemmaId + ", ");
                // Print all the extra relations found for this synset
                for (Map.Entry<String,String> e
                         : lexfileIdToOtherRelation.entrySet()) 
                    lexFilePw.print(e.getKey() + "," + e.getValue() + " ");

                lexFilePw.println(" (" + gloss + ") }");
                break;
            }
            case NOUN:
            case VERB:
            default:
                // These shouldn't be in this method
                throw new IllegalStateException("Unhandled POS: " + pos);
            }
            
            incorporated.add(ent);
            senseCounts.count(lemma);            
        }

        for (PrintWriter pw : posToLexFile.values())
            pw.close();
        
       
        return incorporated;
    }

    /**
     * A sanity check to ensure that the data represented by this entry can be
     * processed by grind.
     */
    private boolean isValidEntry(AnnotatedLexicalEntry ale) {

        String lemma = ale.getLemma();

        // Skip lemmas that invalidate WordNet's constraints
        if (!VALID_LEMMA.matcher(lemma).matches()
                || lemma.length() >= MAX_LEMMA_LENGTH) 
            return false;

        // If this entry is being integrated as a new synset, then it
        // must have a valid gloss for its synset
        if (ale.getOperations().get(CrownOperations.Lexicalization.class) == null
                && ale.getOperations().get(CrownOperations.Synonym.class) == null) {

            String gloss = ale.getAnnotations().get(CrownAnnotations.Gloss.class);
            
            // Sanity checks that the gloss is of sufficient length and is not
            // going to break something in grind.
            if (gloss == null) 
                return false;
            gloss = gloss.trim();
            if (gloss.length() < 3 || gloss.length() > 250)
                return false;

            // Try removing non-ascii glosses, which seem to wreck grind when
            // figuring out byte offsets :(
            Matcher m = ASCII.matcher(gloss);
            return m.matches();
        }
        return true;
    }

    /**
     *
     * @param lemma the lemma for which a new ID is needed in a lexicographer
     *        file
     * @param caseInsensitiveLemmaCounts the count of how many times a lemma has
     *        appeared in a synset in the current lexicographer file
     * @param alreadyUsedLemmaIds the set of IDs already in used by lemmas in
     *        the current lexicographer file
     *
     * @return the ID by which {@code lemma} should be referred, or {@code null}
     *         if this lemma cannot be inserted into an entry in the current
     *         lexicographer file because it contains more instances of that
     *         lemma than are allowed by grind.
     */
    static String createLexFileId(String lemma,
                                  Counter<String> caseInsensitiveLemmaCounts,
                                  Set<String> alreadyUsedLemmaIds) {

        String suffix = (ENDS_WITH_NUMBER.matcher(lemma).matches()) ? "\"" : "";

        int count = caseInsensitiveLemmaCounts
            .count(lemma.toLowerCase());
        if (count > 15)
            return null;
        String uniqueLemmaId = lemma + suffix + count;

        while (alreadyUsedLemmaIds.contains(uniqueLemmaId)) {
            count = caseInsensitiveLemmaCounts
                .count(lemma.toLowerCase());

            // Cannot have more than 15 versions of a lemma in a single file
            if (count > 15)
                return null;
            uniqueLemmaId = lemma + suffix + count;
        }

        alreadyUsedLemmaIds.add(uniqueLemmaId);

        //System.out.printf("%s -> %s%n", lemma, uniqueLemmaId);
        return uniqueLemmaId;
    }

    
    private void copyLexFiles(File oldLexFileDir, File newLexFileDir) 
            throws IOException {
            
        for (File lexFile : oldLexFileDir.listFiles()) {
            String name = lexFile.getName();
            
            // Skip over the non-lexicographer files
            if (!(name.startsWith("adj") || name.startsWith("noun")
                  || name.startsWith("adv") || name.startsWith("verb"))) {
                continue;
            }
            
            // Copy everything else
            Files.copy(lexFile, new File(newLexFileDir, name));
        }
    }
    
    /**
     *
     */
    private List<AnnotatedLexicalEntry>
        mergeNewLemmas(File oldLexFileDir, File newLexFileDir,
                       MultiMap<ISynset,AnnotatedLexicalEntry> synsetToMergeOps,
                       MultiMap<String,ISynset> glossToSynset)
                throws IOException {

        // This list will contain all the entries we end up adding a new synsets
        List<AnnotatedLexicalEntry> incorporated =
            new ArrayList<AnnotatedLexicalEntry>(synsetToMergeOps.range());

        for (File lexFile : oldLexFileDir.listFiles()) {
            String name = lexFile.getName();

            // Adjective lex files have a different format to deal with
            // sattelite adjectives, so we process them separately
            if (name.startsWith("adj")) {
                incorporated.addAll(
                     mergeLemmasIntoAdjLexFile(newLexFileDir, lexFile,
                                              synsetToMergeOps, glossToSynset));
            }
            // Process the lexicographer files
            else if (name.startsWith("noun") || name.startsWith("adv")
                     || name.startsWith("verb")) {

                incorporated.addAll(
                   mergeLemmasIntoRegularLexFile(
                     newLexFileDir, lexFile, synsetToMergeOps, glossToSynset));
            }
        }
        
        return incorporated;
    }

    /**
     * For noun, verb, and adverb instances, merges new lemmas specified in
     * {@link synsetToMergeOps} into the appropriate lexicographer entries.
     */
    private List<AnnotatedLexicalEntry>
        mergeLemmasIntoRegularLexFile(File newLexFileDir,
            File lexFile,
            MultiMap<ISynset,AnnotatedLexicalEntry> synsetToMergeOps,
            MultiMap<String,ISynset> glossToSynset)
            throws IOException {
        
        File mergedLexFile = new File(newLexFileDir, lexFile.getName());
        PrintWriter mergedLexPw = new PrintWriter(mergedLexFile);

        Counter<String> caseInsensitiveLemmaCounts
            = getLemmaCounts(lexFile);
        Set<String> alreadyUsedLemmaIds
            = getLemmaIds(lexFile);

        List<AnnotatedLexicalEntry> incorporated
            = new ArrayList<AnnotatedLexicalEntry>(1000);
            
        
        // For each of the synsets in the lex file, test whether it has a
        // merge operation to perform.  If not, just write it directly
        int lineNo = 0;
        for (String line : new LineReader(lexFile)) {

            // boolean debug = false;
            // if (line.contains("a genus of Psittacidae")) {
            //     System.out.println("ERROR LINE:" + line);
            //     debug = true;
            // }
            
            // Get the sense ID for this synset entry.  Not all lines are valid
            // entries (comments, errors, etc.), so if we can't recover a gloss,
            // just print the line as-is to preserve as much of the file as
            // originally present.
            String gloss = getGloss(line);
            Set<ISynset> synsets = glossToSynset.get(gloss);
            if (synsets.isEmpty()) {
                mergedLexPw.println(line);
                continue;
            }

            Set<String> lemmasInCurrentSynset = getLemmas(line);
            
            // Check whether we need to do anything to these synsets
            boolean needsMods = false;
            for (ISynset synset : synsets) {
                Set<String> priorLemmas =
                    WordNetUtils.toLemmas(synset);
                if (!synsetToMergeOps.get(synset).isEmpty() &&
                        !Collections.disjoint(priorLemmas,
                                              lemmasInCurrentSynset)) {
                    needsMods = true;
                    break;
                }
            }
            if (!needsMods) {
                mergedLexPw.println(line);
                continue;
            }

            
            // System.out.printf("MERGE:\n\t%s%n", line);
            // for (ISynset synset : synsets) {
            //     // See we have any modifications to make for this entry
            //     System.out.printf("\t%s\t==>\t%s%n", synset,
            //                       synsetToMergeOps.get(synset));
            // }

            
            
            // Otherwise, we know that at least one synset has some operation
            // that should be performed 
            for (ISynset synset : synsets) {

                Set<String> priorLemmas =
                    WordNetUtils.toLemmas(synset);

                // if (debug) {
                //     System.out.printf("PRIOR: %s, CURRENT: %s%n",
                //                       priorLemmas, lemmasInCurrentSynset);
                // }
                
                // Guard against repeating this merge operations for synsets
                // with ambiguous glosses
                if (Collections.disjoint(priorLemmas, lemmasInCurrentSynset)) {
                    // if (debug) System.out.println("PRINT SKIP SYN");
                    continue;
                }
                
                // See we have any modifications to make for this entry
                Set<AnnotatedLexicalEntry> toMerge
                    = synsetToMergeOps.get(synset);
                
                // If we aren't touching this synset, just print it as-is and
                // move on.
                if (toMerge.isEmpty()) {
                    //if (debug) System.out.println("EMPTY TO MERGE");
                    continue;
                }

                
                // Some glosses will begin with a complex entry in [ ] that
                // specify relations to particular lemmas in the synset.  We
                // look for these and try to insert after, if possible
                int bracketStartIndex = line.indexOf('[');
                // Other entries simply have a list of lemmas in the synset. 
                int firstSpaceIndex = line.indexOf(", ");
            
                StringBuilder sb = new StringBuilder();
                // If this gloss does not have brackets or the first lemma in
                // the synset occurs before the bracket start, insert the new
                // lemmas after the space.
                if (bracketStartIndex < 0
                        || firstSpaceIndex < bracketStartIndex) {
                
                    String start = line.substring(0, firstSpaceIndex+2);
                    sb.append(start);
                    
                    for (AnnotatedLexicalEntry ale : toMerge) {
                        String lemma = ale.getLemma();
                        if (priorLemmas.contains(lemma)) {
                            // CrownLogger.info("Avoiding merging %s into %s",
                            //                lemma, synset);
                            //if (debug) System.out.println("AVOID MERGE1");
                            continue;
                        }
                        
                        String lemmaId =
                            createLexFileId(lemma, caseInsensitiveLemmaCounts,
                                            alreadyUsedLemmaIds);
                        
                        if (lemmaId == null) {
                            //if (debug) System.out.println("PRINT NULL ID1");
                            continue;
                        }
                        
                        incorporated.add(ale);
                        senseCounts.count(lemma);
                        sb.append(lemmaId).append(", ");
                    }
                    sb.append(line.substring(firstSpaceIndex+2));
                }
                // Otherwise, the entry begins with the bracketed sense, so
                // insert the new lemmas after
                else {
                    int bracketEnd = line.indexOf("] ", bracketStartIndex);
                    if (bracketEnd < 0) { // !!!
                        CrownLogger.warning("Couldn't find end of bracket in " +
                                          line);
                        continue;
                    }
                    String start = line.substring(0, bracketEnd+2);
                    sb.append(start);
                    
                    for (AnnotatedLexicalEntry ale : toMerge) {
                        String lemma = ale.getLemma();
                        if (priorLemmas.contains(lemma)) {
                            // CrownLogger.info("Avoiding merging %s into %s",
                            //                lemma, synset);
                            //if (debug) System.out.println("PRINT AVOID MERGE2");
                            continue;
                        }
                        
                        String lemmaId =
                            createLexFileId(lemma, caseInsensitiveLemmaCounts,
                                            alreadyUsedLemmaIds);
                        
                        if (lemmaId == null) {
                            //if (debug) System.out.println("PRINT NULL LEMMA2");
                            continue;
                        }
                        
                        incorporated.add(ale);
                        senseCounts.count(lemma);
                        sb.append(lemmaId).append(", ");
                    }
                    sb.append(line.substring(bracketEnd+2));
                    //line = sb.toString();                                   
                }
                // System.out.printf("Merged %s in \n\t%s%n", toAdd, sb);
                //if (debug) System.out.println("PRINT ACTUAL LINE END");
                mergedLexPw.println(sb);
            }
        }
        mergedLexPw.close();

        return incorporated;
    }

    /**
     * For adjective instances, merges new lemmas specified in {@link
     * synsetToMergeOps} into the appropriate lexicographer entries.  This
     * method is needed to deal with the complex adjective file structure.
     */
    private List<AnnotatedLexicalEntry>
        mergeLemmasIntoAdjLexFile(File newLexFileDir,
            File lexFile,
            MultiMap<ISynset,AnnotatedLexicalEntry> synsetToMergeOps,
            MultiMap<String,ISynset> glossToSynset)
            throws IOException {

        File mergedLexFile = new File(newLexFileDir, lexFile.getName());
        PrintWriter mergedLexPw = new PrintWriter(mergedLexFile);

        Counter<String> caseInsensitiveLemmaCounts
            = getLemmaCounts(lexFile);
        Set<String> alreadyUsedLemmaIds
            = getLemmaIds(lexFile);

        List<AnnotatedLexicalEntry> incorporated
            = new ArrayList<AnnotatedLexicalEntry>(1000);

        // The adjective lex files are messy and a single sense cluster can span
        // multiple lines, so read all the content into a single string and then
        // we'll modify it and write it back out.
        StringBuilder sb = new StringBuilder();
        for (String line : new LineReader(lexFile)) {
            sb.append(line).append('\n');
        }

        String contents = sb.toString();
        String[] lines = contents.split("\n");

        // Print out the header
        for (String line : lines) {
            if (line.startsWith("{") 
                    || line.startsWith("["))
                break;
            
            mergedLexPw.println(line);
        }
        mergedLexPw.println();
    
        
        // Now start loading all the noisy adjective clusters
        Pattern p = Pattern.compile("\\[\\s*\\{(.+)\\}\\s*\\]",
                                    Pattern.MULTILINE | Pattern.DOTALL);
        Matcher m = p.matcher(contents);
        while (m.find()) {
            String clustersStr = m.group(0);

            // Split into groups of adjective senses, which are typically
            // separated by an antonym.
            String[] adjClusters = clustersStr
                .substring(1, clustersStr.length() - 1).split("[-]{3,}");

            // We'll optionally update the clusters and put the updated synset
            // cluster in this list
            List<String> updatedClusters = new ArrayList<String>();
            
            // For each cluster, find the synsets by splitting on new lines
            for (String cluster : adjClusters) {
                String[] senses = cluster.split("\n");

                // Check whether we've added a new lemma into this sense
                for (int i = 0; i < senses.length; ++i) {

                    String gloss = getGloss(senses[i]);
                    Set<ISynset> synsets = glossToSynset.get(gloss);
                    if (synsets.isEmpty())
                        continue;
                    
                    for (ISynset synset : synsets) {
                        // See we have any modifications to make for this entry
                        Set<AnnotatedLexicalEntry> toMerge
                            = synsetToMergeOps.get(synset);
                
                        // If we aren't touching this synset, just print it
                        // as-is and move on.
                        if (toMerge.isEmpty()) 
                            continue;                        
                       
                        // Get the lemmas we're going to merge into this synset
                        String updatedGloss = insertLemmas(
                            senses[i], toMerge, caseInsensitiveLemmaCounts,
                            alreadyUsedLemmaIds, synset, incorporated);
                                      
                        // Replace the current sense's gloss with the updated
                        // System.out.printf("Merged %s into %s%n",
                        //                   lemmasToAdd, updatedGloss);
                        senses[i] = updatedGloss;
                    }
                }
                updatedClusters.add(String.join("\n", senses));
            }

            // Remerge all of the updated clusters
            mergedLexPw.println("[" + String.join("\n----", updatedClusters)
                                + "]");
            mergedLexPw.println();
        }
        
        // Some times there are adjectives that aren't part of a cluster so they
        // aren't enclosed within [ ].
        int lb = 0;
        for (String line : lines) {
            if (lb == 0 && line.length() > 0 && line.charAt(0) == '{') {

                String gloss = getGloss(line);
                Set<ISynset> synsets = glossToSynset.get(gloss);
                if (synsets.isEmpty())
                    continue;
                
                // Check whether we need to do anything to these synsets
                boolean needsMods = false;
                for (ISynset synset : synsets) {
                    if (!synsetToMergeOps.get(synset).isEmpty()) {
                        needsMods = true;
                        break;
                    }
                }
                if (!needsMods) {
                    mergedLexPw.println(line);
                    continue;
                }

                // We have modifications to make at least one synset with this
                // entry's gloss
                for (ISynset synset : synsets) {

                    Set<AnnotatedLexicalEntry> toMerge
                        = synsetToMergeOps.get(synset);
              
                    // If we aren't touching this synset, just print it
                    // as-is and move on.
                    if (toMerge.isEmpty()) 
                        continue;              

                    String updatedGloss = insertLemmas(
                        line, toMerge, caseInsensitiveLemmaCounts,
                        alreadyUsedLemmaIds, synset, incorporated);

                    mergedLexPw.println(updatedGloss);
                    mergedLexPw.println();
                }
            }
            else {
                int n = line.length();
                for (int i = 0; i < n; ++i) {
                    if (line.charAt(i) == '[')
                        lb++;
                    else if (line.charAt(i) == ']')
                        lb--;
                }
            }
        }
        mergedLexPw.close();
        return incorporated;
    }
        
    /**
     * Counts how many times a lemma is used within a synset in this
     * lexicographer file.  These counts are necessary to distinguish uses of
     * the lemma between different synsets when creating pointers (linking)
     * between synsets.
     */
    private Counter<String> getLemmaCounts(File lexFile) {
        Counter<String> lemmaCounts = new ObjectCounter<String>();
        boolean isAdjFile = lexFile.getName().startsWith("adj");
        for (String line : new LineReader(lexFile)) {
            if (!(line.startsWith("{") || line.startsWith("[")))
                continue;
            // Strip out adjective positional markup, which interferes with
            // gloss identification
            if (isAdjFile) {
                line = line.replace("(a)", "").replace("(p)", "")
                    .replace("(ip)", "");
            }

            String[] arr = line.split("[\\s]+");
            for (String s : arr) {
                // Stop once we see the gloss start
                if (s.indexOf('(') >= 0)
                    break;

                // Only lemmas in the synset do this
                if (s.endsWith(",")) {
                    // Strip off the lemma number (if it exists) and
                    String lemma = s.replaceAll("[0-9]*,$", "");

                    // Guard against lemmas that end in numbers (which aren't a
                    // part of their counts
                    if (lemma.endsWith("\""))
                        lemma = lemma.substring(0, lemma.length() - 1);
                    // Lower case
                    lemma = lemma.toLowerCase();
                    
                    lemmaCounts.count(lemma);
                }
            }    
        }
        return lemmaCounts;
    }

    /**
     * Returns the set of lemmas defined within a synset of this line in this
     * lexicographer file.  
     */
    private Set<String> getLemmas(String line) {
        // Stop once we see the gloss start
        int i = line.indexOf('(');
        assert i >= 0;

        if (i < 0)
            return Collections.<String>emptySet();

        String[] arr = line.substring(0, i).split("\\s+");

        Set<String> lemmas = new HashSet<String>();
        for (String s : arr) {
            if (s.endsWith(",")) {
                s = s.substring(0, s.length() - 1);
                int n = s.length() - 1;
                while (n > 0 && Character.isDigit(s.charAt(n))) {
                    s = s.substring(0, s.length() - 1);
                    n = s.length() - 1;
                }
                if (s.endsWith("\""))
                    s = s.substring(0, s.length() - 1);
                if (s.length() > 0)
                    lemmas.add(s.toLowerCase());
            }
        }

        return lemmas;
    }

    
    /**
     * Returns the set of lemma identifiers used within the synsets in this
     * file.  This set is necessary to guard against cases where the lemma
     * identifiers are not contiguously ordered within a synset (which happens),
     * so merging a new synset based on count may actually clash with an
     * existing synset.
     */
    private Set<String> getLemmaIds(File lexFile) {
        Set<String> lemmaIds = new HashSet<String>();
        boolean isAdjFile = lexFile.getName().startsWith("adj");

        for (String line : new LineReader(lexFile)) {
            if (!(line.startsWith("{") || line.startsWith("[")))
                continue;
            // Strip out adjective positional markup, which interferes with
            // gloss identification
            if (isAdjFile) {
                line = line.replace("(a)", "").replace("(p)", "")
                    .replace("(ip)", "");
            }

            String[] arr = line.split("[\\s]+");
            for (String s : arr) {
                // Stop once we see the gloss start
                if (s.indexOf('(') >= 0)
                    break;

                // Only lemmas in the synset do this
                if (s.endsWith(",")) 
                    s = s.substring(0, s.length() - 1);
                while (s.startsWith("["))
                    s = s.substring(1);
                lemmaIds.add(s);
            }    
        }
        return lemmaIds;
    }

    /**
     * Returns the gloss of this synset, as specified in this entry in a
     * lexicographer file, or {@code null} if the line does not contain a gloss.
     */
    public String getGloss(String entry) {

        // Strip out comments
        entry = entry.replaceAll("\\(==[^\\)]*\\)", "");
        entry = entry.replaceAll("\\(\\+\\+[^\\)]*\\)", "");
        
        int i = entry.indexOf('(');
        
        int j = entry.lastIndexOf(')');
        if (i < 0 || j < 0)
            return null;

        // NOTE: adjectives have special markup that includes (a) (p)
        // (ip), so check to see if we're getting one of those
        while ((entry.charAt(i+1) == 'a' && entry.charAt(i+2) == ')')
               || (entry.charAt(i+1) == 'p' && entry.charAt(i+2) == ')')
               || (entry.charAt(i+1) == 'i' && entry.charAt(i+2) == 'p'
                   && entry.charAt(i+3) == ')')) {
            i = entry.indexOf('(', i + 1);
        }

        if (i < 0 || i > j) {
            // Not all lines will actually contain a gloss, so if we couldn't
            // find one, just return null.
            return null;
        }

        String gloss = entry.substring(i+1, j).trim();
        return gloss;
    }

    /**
     * Inserts the lemmas into the provided adjective entry
     */
    private String insertLemmas(String entry,
                                Set<AnnotatedLexicalEntry> toMerge,
                                Counter<String> caseInsensitiveLemmaCounts,
                                Set<String> alreadyUsedLemmaIds,
                                ISynset beingMergedInto,
                                List<AnnotatedLexicalEntry> incorporated) {



        // Figure out where to put them.  Some glosses will begin with a complex
        // entry in [ ] that specify relations to particular lemmas in the
        // synset.  We look for these and try to insert after, if possible
        int bracketStartIndex = entry.indexOf('[');

        // The head synset of an adjective cluster will begin with a '['.  We
        // want to ignore this bracket and instead just look for the brackets
        // that indicate lemma-specific relationships, which need to be taken
        // into account when merging.  
        if (bracketStartIndex == 0)
            bracketStartIndex = entry.indexOf('[', 1);
        // int glossStartIndex = gloss.indexOf('(');
        
        // Other entries simply have a list of lemmas in the synset. 
        int firstSpaceIndex = entry.indexOf(", ");

        Set<String> priorLemmas = WordNetUtils.toLemmas(beingMergedInto);
        Set<String> lemmasInCurrentEntry = getLemmas(entry);


        // boolean debug = entry.contains("in good health especially after having suffered illness or injury");
        // if (debug) System.out.printf("prior said: %s, getLemmas said: %s%n",
        //                              priorLemmas, lemmasInCurrentEntry);
        
        //System.out.println("INSERT: entry: " + entry);
        
        StringBuilder sb = new StringBuilder();

        // If this gloss does not have brackets or the first lemma in the synset
        // occurs before the bracket start, insert the new lemmas after the
        // space.
        if (bracketStartIndex < 0 || firstSpaceIndex < bracketStartIndex) {
            //System.out.println("INSERT: case1");
            String start = entry.substring(0, firstSpaceIndex+2);
            sb.append(start);
            //System.out.println("INSERT: start:" + start);
            for (AnnotatedLexicalEntry ale : toMerge) {
                String lemma = ale.getLemma();
                if (priorLemmas.contains(lemma)
                        || lemmasInCurrentEntry.contains(lemma.toLowerCase())) {
                    // CrownLogger.info("Avoiding merging %s into %s",
                    //                lemma, beingMergedInto);
                    continue;
                }
                
                String lemmaId = createLexFileId(
                    lemma, caseInsensitiveLemmaCounts,
                    alreadyUsedLemmaIds);

                if (lemmaId == null)
                    continue;

                incorporated.add(ale);
                senseCounts.count(lemma);
                sb.append(lemmaId).append(", ");
            }
            sb.append(entry.substring(firstSpaceIndex+2));
        }

        // Otherwise, the entry begins with the bracketed sense, so
        // insert the new lemmas after
        else {
            //System.out.println("INSERT: case1");
            int bracketEnd = entry.indexOf("] ", bracketStartIndex);
            if (bracketEnd < 0) { // !!!
                //CrownLogger.info("Mismatched brackets in " + gloss);
                //return "";
                int specialCase = entry.indexOf("]");
                if (specialCase < 0)
                    throw new Error("bad entry: " + entry);
                entry = entry.substring(0, specialCase+1) + " " +
                    entry.substring(specialCase+1);
                bracketEnd = entry.indexOf("] ", bracketStartIndex);
            }
            String start = entry.substring(0, bracketEnd+2);
            sb.append(start);
            //System.out.println("INSERT: start:" + start);
            for (AnnotatedLexicalEntry ale : toMerge) {
                String lemma = ale.getLemma();
                if (priorLemmas.contains(lemma)
                        || lemmasInCurrentEntry.contains(lemma.toLowerCase())) {
                    // CrownLogger.info("Avoiding merging %s into %s",
                    //                lemma, beingMergedInto);
                    continue;
                }
                
                String lemmaId = createLexFileId(
                    lemma, caseInsensitiveLemmaCounts,
                    alreadyUsedLemmaIds);
                
                if (lemmaId == null)
                    continue;

                incorporated.add(ale);
                senseCounts.count(lemma);
                sb.append(lemmaId).append(", ");
            }
            sb.append(entry.substring(bracketEnd+2));
        }

        //System.out.println("INSERT:\t" + sb);
        
        return sb.toString();
    }   
    
    /**
     * TODO.
     *
     * @return the list of {@link AnnotatedLexicalEntry} that were actually used
     *         in generated the exception files.  All other entries not in this
     *         list were discarded.
     */
    private List<AnnotatedLexicalEntry>
            createExceptionFiles(List<AnnotatedLexicalEntry> entries,
                                 File oldDictDir, File newDictDir)
            throws IOException {

        // A mapping from the part of speech to the exceptions for that
        // POS. Note that grind and WN both required that the exceptions be
        // sorted in alphabetic order
        Map<POS,SortedSet<String>> posToExceptions
            = new HashMap<POS,SortedSet<String>>();
        String[] posStrs = new String[] { "noun", "verb", "adj", "adv" };
        for (int i = 0; i < PARTS_OF_SPEECH.length; ++i) {
            POS pos = PARTS_OF_SPEECH[i];
            String posStr = posStrs[i];
            SortedSet<String> exceptions = new TreeSet<String>();
            posToExceptions.put(pos, exceptions);

            // Load the current exceptions
            File excFile = new File(oldDictDir, posStr + ".exc");
            for (String line : new LineReader(excFile))
                exceptions.add(line);
        }

        List<AnnotatedLexicalEntry> incorporated =
            new ArrayList<AnnotatedLexicalEntry>(entries.size());

        for (AnnotatedLexicalEntry ale : entries) {
            Set<Duple<Reason,String>> excOp = ale.getOperations()
                .get(CrownOperations.Lexicalization.class);

           
            // Nothing can have spaces in it
            String exception = ale.getLemma();
            POS pos = ale.getPos();
            exception = exception.replace(" ", "_");

            next_exception:
            for (Duple<Reason,String> d : excOp) {

                // This is the normal form of the term for which the exception
                // is an irregular morphological variant (e.g., for "ran", "run"
                // would be its base form.
                String baseForm = d.y;

                // Avoid bad Wiktionary base forms
                if (!VALID_LEMMA.matcher(baseForm).matches())  {
                    continue;
                }
                
                // Fast test to skip the cases that morphological analyzer can
                // probably pick up
                for (String stem : WordNetUtils.findStems(exception, pos)) {
                    if (stem.equals(baseForm)) {
                        continue next_exception;
                    }
                }

                
                // Perform a sanity check that we're reporting a lexicalization
                // for a lemma that is in WordNet.  In some cases, Wiktionary
                // flips the order where the officially-recognized version in
                // WordNet is considered the variant.  Such reversed
                // lexicalizations fail when looking them up, so flip the text.
                boolean isExceptionInWn =
                    WordNetUtils.isInWn(dict, exception, pos);
                boolean isBaseFormInWn =
                    WordNetUtils.isInWn(dict, baseForm, pos);
                // if (!isBaseFormInWn) {
                //     if (baseForm.contains("_")) {
                //         isBaseFormInWn = WordNetUtils
                //             .isInWn(dict, baseForm.replace("_", "-"), pos);
                //         baseForm = baseForm.replace("_", "-");
                //     }
                //     else if (baseForm.contains("-")) {
                //         isBaseFormInWn = WordNetUtils
                //             .isInWn(dict, baseForm.replace("-", "_"), pos);
                //         baseForm = baseForm.replace("-", "_");
                //     }
                // }

                // If neither (or both) are in WordNet, then don't bother
                // reporting this exception since it won't matter
                if (isExceptionInWn == isBaseFormInWn) {
                    continue;
                }

                // NOTE: no need to update the sense or pointer counts for the
                // entry since lexicalization do not count towards the maximums
                incorporated.add(ale);              

                // If Wiktionary's official-baseForm order is reversed, flip the
                // texts
                if (!isBaseFormInWn && isExceptionInWn) {
                    String tmp = exception;
                    exception = baseForm;
                    baseForm = tmp;
                }

                // Figure out in which file this exception belongs and add the
                // exception
                posToExceptions.get(pos).add(exception + " " + baseForm);
            }
        }

        // Once we have all the exceptions, output the .exc files
        for (Map.Entry<POS,SortedSet<String>> e
                 : posToExceptions.entrySet()) {
            String posPrefix = e.getKey().toString().toLowerCase();
            // "adverb" and "adjective" get shortened to their first three
            // letters
            if (posPrefix.length() > 4)
                posPrefix = posPrefix.substring(0, 3);
            File newExcFile = new File(newDictDir, posPrefix + ".exc");
            PrintWriter pw = new PrintWriter(newExcFile);
            for (String excEntry : e.getValue())
                pw.println(excEntry);
            pw.close();
        }
       
        return incorporated;
    }

    /**
     * Returns a mapping from a gloss to the synsets that have that gloss.
     */
    private MultiMap<String,ISynset> getGlossSynsetMapping() {

        MultiMap<String,ISynset> glossToSynset =
            new HashMultiMap<String,ISynset>();

        for (POS pos : PARTS_OF_SPEECH) {
            Iterator<ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset syn = iter.next();
                String gloss = syn.getGloss();
                glossToSynset.put(gloss, syn);
            }
        }
        return glossToSynset;
    }

    
    /**
     * Returns a mapping from an {@link ISynset} to the string that denotes that
     * synset within the lexicographer files.  This method is necessary because
     * WordNet synset IDs and sense keys are generated by grind itsef, so this
     * method provides a backward-mapping from the synset identifiers to their
     * corresponding identifiers in the files.
     */
    private Map<ISynset,String> mapSynsetsToLexFileIds(File lexFileDir)
            throws IOException {

        Map<String,String> glossToLexfileId =
            new HashMap<String,String>();

        Map<ISynset,String> synsetToLexFileId =
            new HashMap<ISynset,String>();

        for (File f : lexFileDir.listFiles()) {
            String lexFile = f.getName();
            // Skip non-lexicographer files
            if (!((lexFile.startsWith("noun")
                   || lexFile.startsWith("verb")
                   || lexFile.startsWith("adj")
                   || lexFile.startsWith("adv"))))
                continue;


            // Special handling of adjectives to deal with the head/satellite
            // synsets
            if (lexFile.startsWith("adj")) {
                parseAdjFile(f, glossToLexfileId);
                continue;
            }
            
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


        // Map each gloss to its synset-id
        for (POS pos : PARTS_OF_SPEECH) {
            Iterator<ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset syn = iter.next();
                String gloss = syn.getGloss();
                String lexfileId = glossToLexfileId.get(gloss);

                synsetToLexFileId.put(syn, lexfileId);
                if (lexfileId == null) {
                    throw new IllegalStateException(
                        "No lexfileId for " + syn.getWords() + " " + gloss);
                }
            }
        }

        return synsetToLexFileId;
    }

    private void parseAdjFile(File f, Map<String,String> glossToLexfileId) {
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

    private static String toStr(POS pos) {
        switch(pos) {
            case NOUN: return "noun";
            case VERB: return "verb";
            case ADJECTIVE: return "adj";
            case ADVERB: return "adv";
            default: throw new AssertionError();
        }        
    }

    private static String cleanGloss(String gloss) {
        int rightParen = 0, leftParen = 0;
        for (int i = 0; i < gloss.length(); ++i) {
            char c = gloss.charAt(i);
            if (c == '(')
                leftParen++;
            else if (c == ')')
                rightParen++;
        }
        while (leftParen <  rightParen) {
            gloss = "(" + gloss;
            leftParen++;
        }
        while (leftParen >  rightParen) {
            gloss  += ")"; 
            rightParen++;
            }
        
        return gloss;
    }
}
