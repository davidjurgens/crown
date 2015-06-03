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
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.logging.Level;

import edu.mit.jwi.IDictionary;

import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.POS;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.CoreMap;

import edu.ucla.sspace.common.ArgOptions;

import edu.ucla.sspace.util.Counter;
import edu.ucla.sspace.util.Duple;
import edu.ucla.sspace.util.ObjectCounter;

import ca.mcgill.cs.crown.CrownOperations.Reason;

import ca.mcgill.cs.crown.data.WiktionaryReader;

import ca.mcgill.cs.crown.procedure.AdverbExtractor;
import ca.mcgill.cs.crown.procedure.AdjectivePatternExtractor;
import ca.mcgill.cs.crown.procedure.AntonymExtractor;
import ca.mcgill.cs.crown.procedure.ConjunctionProcedure;
import ca.mcgill.cs.crown.procedure.DomainLinkAugmenter;
import ca.mcgill.cs.crown.procedure.GroupExtractor;
import ca.mcgill.cs.crown.procedure.NearSynonymExtractor;
import ca.mcgill.cs.crown.procedure.NounPatternExtractor;
import ca.mcgill.cs.crown.procedure.ParseExtractor;
import ca.mcgill.cs.crown.procedure.PersonPatternExtractor;
import ca.mcgill.cs.crown.procedure.RelationBasedIntegrator;
import ca.mcgill.cs.crown.procedure.SynonymExtractor;
import ca.mcgill.cs.crown.procedure.TaxonomicExtractor;
import ca.mcgill.cs.crown.procedure.VerbPatternExtractor;
import ca.mcgill.cs.crown.procedure.WiktionaryAnnotationBasedExtractor;
import ca.mcgill.cs.crown.procedure.WikiMarkupExtractor;

import ca.mcgill.cs.crown.similarity.GreedyStringTiling;
import ca.mcgill.cs.crown.similarity.InvFreqSimilarity;
import ca.mcgill.cs.crown.similarity.SimilarityFunction;

import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WordNetUtils;

import com.google.common.io.Files;


/**
 * The main class for constructing CROWN.  This class is intended to be run as a
 * command-line executable program.
 */
public class CrownCreator {

    private static final int DEFAULT_NUM_ITERATIONS = 3;

    private final File wordNetDictDir;

    private final File wordNetLexFileDir;

    public CrownCreator(File wordNetDictDir, File wordNetLexFileDir) {
        this.wordNetDictDir = wordNetDictDir;
        this.wordNetLexFileDir = wordNetLexFileDir;
    }

    /**
     * Attempts to integrate the provided entries into the semantic network,
     * using the specified number of iterations and writing the output to the
     * provided directory.
     *
     * @param entries the entries to potentially be integrated into the WordNet
     *        semantic network
     * @param numIterations the number of iterations in which the collection of
     *        entries will be attempted to be integrated.  Setting this value to
     *        more than 1 allows new entries to be hyponyms of other
     *        newly-integrated entries.
     * @param outputDir the directory into which the final CROWN dictionary will
     *        be written
     */
    public void build(Collection<LexicalEntry> entries,
                      int numIterations, File outputDir) {
        File tmpDir = null;
        CrownLogger.info("Creating intermediate content at " + tmpDir);
        this.build(entries, numIterations, outputDir, tmpDir);
    }

    /**
     * Attempts to integrate the provided entries into the semantic network,
     * using the specified number of iterations and writing the output to the
     * provided directory and all temporary data to the provided intermediate
     * directory.
     *
     * @param entries the entries to potentially be integrated into the WordNet
     *        semantic network
     * @param numIterations the number of iterations in which the collection of
     *        entries will be attempted to be integrated.  Setting this value to
     *        more than 1 allows new entries to be hyponyms of other
     *        newly-integrated entries.   
     * @param outputDir the directory into which the final CROWN dictionary will
     *        be written
     * @param workingDir the directory into which intermediate build files will
     *        be written (useful for debugging)
     */
    public void build(Collection<LexicalEntry> entries,
                      int numIterations, File outputDir, File workingDir) {

        File curDictDir = wordNetDictDir;
        File curLexFileDir = wordNetLexFileDir;


        // Open the current version of the WN/CROWN dictionary that we use.
        // This is initially the WN dictionary, but on later passes, we add
        // new items and it is replaced with the expanded CROWN dictionary.
        IDictionary dict = WordNetUtils.open(curDictDir);

        Grind grind = new Grind();

        // TODO: one day replace this with ADW when it proves fast enough, or at
        // least test it out, whre possible
        InvFreqSimilarity gst =
            // new GreedyStringTiling(4);
            new InvFreqSimilarity(entries, dict);

        List<AnnotatedLexicalEntry> toIntegrate =
            new ArrayList<AnnotatedLexicalEntry>(500_000);
        BuildPipeline pipeline = new BuildPipeline();
                
        pipeline.add(new WiktionaryAnnotationBasedExtractor(dict, gst));
        pipeline.add(new RelationBasedIntegrator(dict, gst));
        pipeline.add(new AntonymExtractor(dict, gst));
        pipeline.add(new SynonymExtractor(dict, gst));
        pipeline.add(new NearSynonymExtractor(dict, gst));
        pipeline.add(new AdverbExtractor(dict, gst));
        pipeline.add(new TaxonomicExtractor(dict, gst));
        pipeline.add(new GroupExtractor(dict, gst));
        pipeline.add(new PersonPatternExtractor(dict, gst));
        pipeline.add(new ParseExtractor(dict, gst));
        pipeline.add(new ConjunctionProcedure(dict, gst));
        pipeline.add(new WikiMarkupExtractor(dict, gst));
        pipeline.add(new VerbPatternExtractor(dict, gst));
        pipeline.add(new NounPatternExtractor(dict, gst));
        pipeline.add(new AdjectivePatternExtractor(dict, gst));
        
        
        // For adding pointers
        pipeline.add(new DomainLinkAugmenter(dict, gst));
        
        // TODO: re-order the pipeline based on accuracy
        
        for (int iterNum = 0; iterNum < numIterations; ++iterNum) {

            CrownLogger.info("Beginning iteration %d with %d entries " +
                           "to integrate", iterNum, entries.size());

            // Open the current version of the WN/CROWN dictionary that we use.
            // This is initially the WN dictionary, but on later passes, we add
            // new items and it is replaced with the expanded CROWN dictionary.
            dict.close(); // close old version
            dict = WordNetUtils.open(curDictDir);

            // Update the similarity model based on the new dictionary
            if (iterNum > 0)
                gst.reset(dict, entries);
            
            // Update the integration pipeline so that it uses the new
            // dictionary for determining presence in CROWN.  This lets us attach
            // new items in this iteration as children of previously-added new
            // items.
            pipeline.setDictionary(dict);            

            toIntegrate.clear();
            toIntegrate.addAll(foobar(entries, pipeline));

            // This is where we will write the updated lexicographer files that
            // will contain data that has been merged in as well as new synsets.
            File updatedLexFileDir =
                new File(workingDir, "dbfiles-iter-" + iterNum);
            if (!updatedLexFileDir.exists())
                updatedLexFileDir.mkdir();

            // This is where we will deposit the dict/ directory for this
            // iteration of CROWN
            File nextDictDir = new File(outputDir, "crown-dict-iter-" + iterNum);
            if (!nextDictDir.exists())
                nextDictDir.mkdir();
            
            try {
                // Generate the new lexicographer files and keep track of which
                // annotations were actually included in creating the build.
                LexicographerFileCreator lfc = new LexicographerFileCreator(dict);
                CrownLogger.info("Generating new CROWN lexicographer files");
                List<AnnotatedLexicalEntry> successfulOperations =
                    lfc.integrate(curLexFileDir, updatedLexFileDir,
                                  toIntegrate, curDictDir, nextDictDir);

                CrownLogger.info("At the end of iteration %d, successfully " +
                               "attached %d entries out of %d",
                               iterNum, successfulOperations.size(),
                               toIntegrate.size());

                File operationsLog = new File(
                    workingDir, "operations-log." + iterNum + ".tsv");
                logOperations(successfulOperations, operationsLog);
                CrownLogger.info("Creating CROWN database");
                grind.createDb(workingDir, updatedLexFileDir,
                               curDictDir, nextDictDir);
                CrownLogger.info("Successfully created CROWN database");

                final Counter<String> successfulOpFreqs =
                    new ObjectCounter<String>();
                for (AnnotatedLexicalEntry ale : successfulOperations) 
                    incrementOperationFreq(successfulOpFreqs, ale);

                List<String> causesByFreq =
                    new ArrayList<String>(successfulOpFreqs.items());
                Collections.sort(causesByFreq, new Comparator<String>(){
                        public int compare(String s1, String s2) {
                            return successfulOpFreqs.getCount(s2) -
                                successfulOpFreqs.getCount(s1);
                        }
                    });

                StringBuilder sb = new StringBuilder(
                    "Integration operations contributing to the final build " +
                    "of iteration ").append(iterNum)
                    .append(", ordered by frequency");
                for (String cause : causesByFreq) {
                    sb.append("\n\t").append(cause).append(":\t")
                        .append(successfulOpFreqs.getCount(cause));
                }
                CrownLogger.info(sb.toString());
                
            } catch (IOException ioe) {
                throw new Error(ioe);
            }

            // Rotate the files and directories produced byt this iteration so
            // that the next round of CROWN creation uses the synsets and
            // relations added by this round
            curDictDir = nextDictDir;
            curLexFileDir = updatedLexFileDir;

        }
    }

    private List<AnnotatedLexicalEntry>
        foobar(Collection<LexicalEntry> entries,
               BuildPipeline pipeline) {
        
        final AtomicInteger numEntriesProcessed = new AtomicInteger(0);
        final AtomicInteger numEntriesAttached = new AtomicInteger(0);

        final Counter<String> operationFreqs =
            new ObjectCounter<String>();

        final ConcurrentMap<LexicalEntry,AnnotatedLexicalEntry> entryToIntegration
            = new ConcurrentHashMap<LexicalEntry,AnnotatedLexicalEntry>();
        
        entries.parallelStream().forEach(
            e -> tryIntegrate(e, pipeline, entryToIntegration, operationFreqs,
                              numEntriesAttached, numEntriesProcessed));

        List<AnnotatedLexicalEntry> toIntegrate =
            new ArrayList<AnnotatedLexicalEntry>(entryToIntegration.values());
        
        // Remove those Entries that are to be integrated to avoid having to
        // needlessly reprocess them again in the next iteration
        Iterator<LexicalEntry> iter = entries.iterator();
        while (iter.hasNext()) {
            LexicalEntry e = iter.next();
            if (entryToIntegration.containsKey(e))
                iter.remove();
        }

        return toIntegrate;
    }

    private void tryIntegrate(LexicalEntry entry,
                              BuildPipeline pipeline,
                              ConcurrentMap<LexicalEntry,AnnotatedLexicalEntry>
                                  entryToIntegration,
                              Counter<String> operationFreqs,
                              AtomicInteger numEntriesAttached,
                              AtomicInteger numEntriesProcessed) {
        //System.out.printf("Trying to integrate with %s%n", Thread.currentThread());
        AnnotatedLexicalEntry ale = pipeline.integrate(entry);
        if (ale != null) {
            entryToIntegration.put(entry, ale);
            numEntriesAttached.incrementAndGet();
            incrementOperationFreq(operationFreqs, ale);
            // System.out.printf("%s ==> %s%n", entry, ale);
        }

        if (numEntriesProcessed.incrementAndGet() % 10_000 == 0) {
            CrownLogger.verbose("Processed %d entries, " +
                              "attached %d tentatively",
                              numEntriesProcessed.get(),
                              numEntriesAttached.get());
            // If we're reporting a lot of stats, dump a list of the
            // current attachment causes
            if (CrownLogger.isLoggable(Level.FINE)) {
                synchronized(operationFreqs) {
                    List<String> causesByFreq =
                        new ArrayList<String>(operationFreqs.items());
                    Collections.sort(causesByFreq, new Comparator<String>(){
                        public int compare(String s1, String s2) {
                            return operationFreqs.getCount(s2) -
                                operationFreqs.getCount(s1);
                        }
                    });
                
                    StringBuilder sb = new StringBuilder(
                        "Integration operations by frequency");
                    for (String cause : causesByFreq) {
                        sb.append('\n').append(cause).append(":\t")
                            .append(operationFreqs.getCount(cause));
                    }
                    CrownLogger.verbose(sb.toString());
                }
            }
        }        
    }
    
    /**
     * Records information about the provided list of successfully-performed
     * operations to the specified log file.  This is a utility method and the
     * log file is designed for debugging purposes.
     */
    private void logOperations(List<AnnotatedLexicalEntry> successfulOperations,
                               File opLog) throws IOException {
        PrintWriter pw = new PrintWriter(opLog);
        for (AnnotatedLexicalEntry ale : successfulOperations) {
            CoreMap operations = ale.getOperations();            
            
            for (Class<? extends CoreAnnotation<Duple<Reason,ISynset>>> op
                     : CrownOperations.SINGLE_ARG_OPERATIONS) {
                Duple<Reason,ISynset> dup = operations.get(op);
                if (dup != null)
                    pw.println(toLogRecord(ale, op, dup));
            }

            for (Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>> op
                     : CrownOperations.SET_ARG_OPERATIONS) {
                Set<Duple<Reason,ISynset>> ops = operations.get(op);
                if (ops == null)
                    continue;

                for (Duple<Reason,ISynset> dup : ops) {
                    pw.println(toLogRecord(ale, op, dup));
                }
            }

            Set<Duple<Reason,String>> ops =
                operations.get(CrownOperations.Lexicalization.class);
            if (ops != null) {
                for (Duple<Reason,String> dup : ops) {
                    pw.println(toLogRecord(ale, dup));
                }
            }
        }
        pw.close();
    }

    private void incrementOperationFreq(Counter<String> operationFreqs,
                                        AnnotatedLexicalEntry ale) {
        CoreMap operations = ale.getOperations();
        for (Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>> op
                 : CrownOperations.SET_ARG_OPERATIONS) {
            Set<Duple<Reason,ISynset>> relatedSyns = operations.get(op);
            if (relatedSyns != null) {
                for (Duple<Reason,ISynset> d : relatedSyns) {
                    synchronized(operationFreqs) {
                        operationFreqs.count(
                            d.x.getOrigin().getName() + ":" + op.getName());
                    }
                }
            }
        }

        for (Class<? extends CoreAnnotation<Duple<Reason,ISynset>>> op
                 : CrownOperations.SINGLE_ARG_OPERATIONS) {
            Duple<Reason,ISynset> d = operations.get(op);
            if (d != null) {
                synchronized(operationFreqs) {
                    operationFreqs.count(
                        d.x.getOrigin().getName() + ":" + op.getName());
                }
            }
        }

        Set<Duple<Reason,String>> excOp = ale.getOperations()
                .get(CrownOperations.Lexicalization.class);
        if (excOp != null) {
            for (Duple<Reason,String> d : excOp) {
                synchronized(operationFreqs) {                
                    operationFreqs.count(
                        d.x.getOrigin().getName() + ":" +
                        CrownOperations.Lexicalization.class.getName());
                }
            }
        }
    }
    
    private String toLogRecord(AnnotatedLexicalEntry ale,
                               Class<?> op,
                               Duple<Reason,ISynset> argDetails) {
        String lemma = ale.getLemma();
        POS pos = ale.getPos();
        String id = ale.getId();
        String opStr = op.getName();
        int i = opStr.indexOf('$');
        if (i > 0)
            opStr = opStr.substring(i+1).trim();
        String aleGloss = ale.getAnnotations().get(CrownAnnotations.Gloss.class);
        
        ISynset arg = argDetails.y;
        String argId = (arg == null)
            ? "WARNING:NULL_SYNSET"
            : arg.getID().toString();
        String argLemmas = (arg == null)
            ? "WARNING:NULL_SYNSET"
            : String.join(",", WordNetUtils.toLemmas(arg));
        String argGloss = arg.getGloss();
        
        String details = argDetails.x.toJson().toString();
        return lemma + "\t" + pos + "\t" + id + "\t" + aleGloss + "\t" +
            opStr + "\t" + argId + "\t" + argLemmas + "\t" + argGloss +
            "\t" + details;
    }

    private String toLogRecord(AnnotatedLexicalEntry ale,
                               Duple<Reason,String> argDetails) {
        String lemma = ale.getLemma();
        POS pos = ale.getPos();
        String id = ale.getId();
        
        String opStr = CrownOperations.Lexicalization.class.getName();
        int i = opStr.indexOf('$');
        if (i > 0)
            opStr = opStr.substring(i+1).trim();
        String lexicalization = argDetails.y;
        String details = argDetails.x.toJson().toString();
        
        return lemma + "\t" + pos + "\t" + id + "\t" +
            opStr + "\t" + lexicalization + "\t" + details;
    }
    
    public static void main(String[] args) {
        try {
            ArgOptions opts = createOptions();            
            opts.parseOptions(args);

            if (opts.hasOption('v'))
                CrownLogger.setLevel(Level.FINE);
            if (opts.hasOption('V'))
                CrownLogger.setLevel(Level.FINER);
            
            
            // If verbose output is enabled, update all the loggers in the S-Space
            // package logging tree to output at Level.FINE (normally, it is
            // Level.INFO).  This provides a more detailed view of how the execution
            // flow is proceeding.
            
            // Check that we have an output directory
            if (opts.numPositionalArgs() != 3) {
                usage(opts);
                System.exit(1);
            }

            int numIterations = (opts.hasOption('i'))
                ? opts.getIntOption('i') : DEFAULT_NUM_ITERATIONS;
            File tmpDir = null;
            if (opts.hasOption('T')) {
                tmpDir = new File(opts.getStringOption('T'));
                if (!tmpDir.exists())
                    tmpDir.mkdir();
                else {
                    // Clean up any files that were there before.
                    deleteContents(tmpDir);
                }
            }
            else {
                tmpDir = Files.createTempDir();
            }
            CrownLogger.verbose("Using working directory %s", tmpDir);

            File wordNetDir= new File(opts.getPositionalArg(0));
            File wordNetDictDir = new File(wordNetDir, "dict");
            if (!wordNetDictDir.exists()) {
                System.out.printf("Cannot find WordNet dict directory at " +
                                  wordNetDictDir);
                System.exit(1);
            }
            File wordNetLexFileDir = new File(opts.getPositionalArg(1));
            
            File baseOutputDir = new File(opts.getPositionalArg(2));
            // Ensure we can create the output dictionary directories
            if ((!baseOutputDir.exists()) || !baseOutputDir.isDirectory())
                baseOutputDir.mkdir();

            File wiktDumpFile = (opts.hasOption('w'))
                ? new File(opts.getStringOption('w')) : null;
            File ukpWiktDir = (opts.hasOption('u'))
                ? new File(opts.getStringOption('u')) : null;
            File preprocessedWiktFile = (opts.hasOption('p'))
                ? new File(opts.getStringOption('p')) : null;

            WiktionaryReader wiktReader = new WiktionaryReader();
            List<LexicalEntry> entries = null;
            
            // Try working from the fastest-to-read input form first, creating
            // cached copies of the processed output where necessary.
            if (preprocessedWiktFile != null) {
                entries = wiktReader.loadFromPreprocessed(preprocessedWiktFile);
            }
            else if (ukpWiktDir != null) {
                File preprocessedWiktFileToCreate = (opts.hasOption('P'))
                    ? new File(opts.getStringOption('P')) : null;
                entries = wiktReader.loadFromDir(
                    ukpWiktDir, preprocessedWiktFileToCreate);
            }
            else if (wiktDumpFile != null) {
                File ukpWiktDirToCreate = (opts.hasOption('U'))
                    ? new File(opts.getStringOption('U')) : null;
                File preprocessedWiktFileToCreate = (opts.hasOption('P'))
                    ? new File(opts.getStringOption('P')) : null;
                entries = wiktReader.loadFromDump(
                    wiktDumpFile, ukpWiktDirToCreate,
                    preprocessedWiktFileToCreate);
            }
            else {
                System.out.println("No input specified; must specify at least "+
                                   "one of [TODO]");
                System.exit(1);
            }
            
            CrownCreator crownCreator =
                new CrownCreator(wordNetDictDir, wordNetLexFileDir);
            crownCreator.build(entries, numIterations, baseOutputDir, tmpDir);
        }
        catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Recursively deletes the contents of the provided directory but not the
     * directory itself
     */
    private static void deleteContents(File dir) {
        for (File f : dir.listFiles()) {
            if (f.isDirectory())
                deleteContents(f);
            else
                f.delete();
        }
    }
    
    private static ArgOptions createOptions() {
        ArgOptions options = new ArgOptions();

        // Basic Wiktionary Input
        options.addOption('w', "wiktionary-dump", 
                          "the XML file containing a Wiktionary dump",
                          true, "FILE", "Wiktionary Input Options"); 
        options.addOption('u', "ukp-wiktionary-dir", 
                          "the directory containing a Wiktionary dump " +
                          "that was processed using UKP's jktwl",
                          true, "DIR", "Wiktionary Input Options"); 
        options.addOption('p', "preprocessed-wiktionary", 
                          "the JSON file containing a preprocessed " +
                          "Wiktionary data (from jwktl)",
                          true, "FILE", "Wiktionary Input Options");

        // Allows for caching the output into files
        options.addOption('U', "save-ukp-wiktionary-dir",
                          "the directory into which a Wiktionary dump " +
                          "that was processed using UKP's jktwl will be saved",
                          true, "DIR", "Wiktionary Cached Input Options"); 
        options.addOption('P', "save-preprocessed-wiktionary", 
                          "the JSON file into which will be written the " +
                          "preprocessed Wiktionary data (from jwktl)",
                          true, "FILE", "Wiktionary Cached Input Options");

        // Various programmatic options
        options.addOption('i', "num-iterations", 
                          "The number of iterations that input data " +
                          "should be processed to allow for recursive " +
                          "linking (default: 3)",
                          true, "INT", "CROWN Options");

        options.addOption('v', "verbose", "prints verbose output",
                          false, null, "CROWN Options");
        options.addOption('V', "veryVerbose", "prints very verbose output, "+
                          "which is generally only useful for debugging",
                          false, null, "CROWN Options");


        // Orther random things
        options.addOption('T', "crown-tmp-dir", 
                          "the directory where all temporary build data will " +
                          "be written, which can be useful for debugging",
                          true, "DIR", "CROWN Misc. Options"); 

        return options;
    }
    

    /**
     * Prints out information on how to run the program to {@code stdout} using
     * the option descriptions for compound words, tokenization, .sspace formats
     * and help.
     */
    private static void usage(ArgOptions argOptions) {
        System.out.println(
            "usage: java " 
            + CrownCreator.class.getName()
            + " [options] WordNet-dir/ WordNet-lexfiles-dir/ " +
            "<base-output-dir>\n"
            + argOptions.prettyPrint());
    }
}
