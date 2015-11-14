/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.ucla.sspace.util.LineReader;

import com.google.common.io.Files;


/**
 * A utility class for invoking the WordNet {@code grind} program on the command
 * line to process lexicographer files
 */
public class Grind {

    public void createDb(File workingDir, File lexFileDir,
                         File curDictDir, File newDictDir) throws IOException {

        File grindLogFile = new File(workingDir, "grind-log.log");

        List<String> grindCmdTokens = new ArrayList<String>(
            Arrays.asList(new String[]
                { "grind", "-a", "-i", "-o", "cntlist",
                  "-n", "-L" + grindLogFile.getAbsolutePath()
                }));

        // We then need to specify which files grind will process
        for (String file : lexFileDir.list()) {
            if (file.startsWith("noun.") || file.startsWith("adj.")
                || file.startsWith("adv.") || file.startsWith("verb.")) {
                grindCmdTokens.add(file);
            }
        }

        ProcessBuilder grindProcessBuilder = new ProcessBuilder(grindCmdTokens);
        grindProcessBuilder.directory(lexFileDir);
            
        Process grindProcess = grindProcessBuilder.start();
        
        StreamConsumer outSc =
            new StreamConsumer(grindProcess.getInputStream());
        Thread outThread = new Thread(outSc);
        StreamConsumer errSc =
            new StreamConsumer(grindProcess.getErrorStream());
        Thread errThread = new Thread(errSc);
        
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        
        try {
            grindProcess.waitFor();

            // This is an unfortunate sleep to avoid some race condition with
            // the StreamConsumer where it's add lines to the output lists as we
            // try to read them.
            Thread.sleep(1000);
            
        } catch (InterruptedException ie) {
            throw new IllegalStateException(ie);
        }
        
        String errorLine = null;
        for (String line : outSc.lines) {
            if (line.contains("no database generated")
                    || line.contains("sanity error")) {
                errorLine = line;
            }
            if (!line.contains("Duplicate pointer"))
                System.out.println(line);
        }
        for (String line : errSc.lines) {
            if (line.contains("no database generated")
                    || line.contains("sanity error")) {
                errorLine = line;
            }
            if (!line.contains("Duplicate pointer"))                
                System.out.println(line);
        }
        
        // Check that grind didn't break on the input; otherwise it won't
        // generate the db files and any subsequent processing will
        // eventually break too.
        if (errorLine != null)
            throw new IllegalStateException("Grind errors; aborting: " + errorLine);
        
        // Copy grind's output to the directory that will contain the
        // data used by CROWN (a paralle of WN's dict/ directory)
        for (File f : lexFileDir.listFiles()) {
            String name = f.getName();
            if (name.startsWith("data.") || name.startsWith("index."))
                Files.copy(f, new File(newDictDir, name));                
        }

        // Create the new lexnames file, which is a mapping between file offset
        // and the lexicography file name.
        createLexnames(curDictDir, newDictDir, lexFileDir);
        
        // Copy over the verb sentence indices.  We don't actually modify
        // these (though we could) and for verb frames, list a default
        // frame, which is likely incorrect for a minority of verbs.
        Files.copy(new File(curDictDir, "sents.vrb"),
                   new File(newDictDir, "sents.vrb"));
        Files.copy(new File(curDictDir, "sentidx.vrb"),
                   new File(newDictDir, "sentidx.vrb"));
    }

    /**
     * Creates the lexnames file based on the new lexicographer files that were
     * introduced through the build process.
     */
    private void createLexnames(File curDictDir, File newDictDir,
                                File lexFileDir) throws IOException {

        PrintWriter lexnamesPw =
            new PrintWriter(new File(newDictDir, "lexnames"));
        
        Set<String> includedFiles = new HashSet<String>();
        int maxId = 0;
        
        for (String line : new LineReader(new File(curDictDir, "lexnames"))) {
            String[] arr = line.split("\t");
            includedFiles.add(arr[1]);
            int id = Integer.parseInt(arr[0]);
            if (maxId < id)
                maxId = id;
            // Print out the existing contents
            lexnamesPw.println(line);
        }

        // Scan for new lexicographer files and add them to the list
        for (File f : lexFileDir.listFiles()) {
            String name = f.getName();
            if (includedFiles.contains(name))
                continue;

            // Lexnames uses a 1-digit ID for indicating the content of the
            // files
            int syntacticCategoryId = -1;
            if (name.startsWith("noun"))
                syntacticCategoryId = 1;
            else if (name.startsWith("verb"))
                syntacticCategoryId = 2;
            else if (name.startsWith("adj"))
                syntacticCategoryId = 3;
            else if (name.startsWith("adv"))
                syntacticCategoryId = 4;

            // Skip other files in the directory that aren't lex files
            if (syntacticCategoryId < 0)
                continue;

            int id = ++maxId;
            lexnamesPw.printf("%02d\t%s\t%d%n", id, name, syntacticCategoryId);
        }

        lexnamesPw.close();
    }
    
    /**
     * A utility class for consuming the output of an input stream and recording
     * it in a buffer.
     */
    static class StreamConsumer implements Runnable {

        public final List<String> lines;

        public final BufferedReader br;

        public Throwable t;

        public StreamConsumer(InputStream is) {
            lines = new ArrayList<String>();
            br = new BufferedReader(new InputStreamReader(is));
        }

        public void run() {
            while (true) {
                try {
                    String line = br.readLine();
                    if (line  == null)
                        break;
                    lines.add(line);
                } catch(Throwable t) {
                    this.t = t;
                    break;
                }
            }
            try {
                br.close();
            } catch(Throwable t) { this.t = t; }
        }
    }    
}
