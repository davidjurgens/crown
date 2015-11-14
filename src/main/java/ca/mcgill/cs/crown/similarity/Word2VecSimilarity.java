/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/


package ca.mcgill.cs.crown.similarity;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOError;
import java.io.IOException;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;

import ca.mcgill.cs.crown.CrownAnnotations;
import ca.mcgill.cs.crown.LexicalEntry;

import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.Stopwords;

import edu.ucla.sspace.util.Counter;
import edu.ucla.sspace.util.HashMultiMap;
import edu.ucla.sspace.util.MultiMap;
import edu.ucla.sspace.util.ObjectCounter;

import edu.mit.jwi.IDictionary;

import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.POS;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.TreeCoreAnnotations.*;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;


/**
 * Compares two strings on the basis of their shared lemmas, where words are
 * weighted by the inverse of their frequeucy in the glosses and similarity is
 * the sum of all overlapping words' weights.
 */
public class Word2VecSimilarity implements SimilarityFunction {

    private static final Pattern WORD = Pattern.compile("[\\p{Punct}]*([^\\p{Punct}]*)[\\p{Punct}]*");
    
    private final Map<String,float[]> wordToVector;
    
    /**
     * A cache from a string to the lemmas contained in the string
     */
    private final Map<String,float[]> glossToVecCache;
    
	
    public Word2VecSimilarity(Collection<LexicalEntry> entries,
                              IDictionary dict,
                              File vectorsFile) {
        try {
            Set<String> words = loadWords(dict, entries);
            words.removeAll(Stopwords.STOP_WORDS);
            wordToVector = loadVectors(vectorsFile, words);
        } catch (IOException ie) {
            throw new IOError(ie);
        }
        glossToVecCache = new HashMap<String,float[]>(100_000);
    }   

    private Set<String> loadWords(IDictionary dict, Collection<LexicalEntry> entries) {
        CrownLogger.verbose("Getting set of unique words");
        Set<String> words = new HashSet<String>(100_000);

        for (LexicalEntry e : entries) {
            String gloss =
                e.getAnnotations().get(CrownAnnotations.Gloss.class);
            words.addAll(getWords(gloss));
        }

        for (POS pos : POS.values()) {
            Iterator <ISynset> iter = dict.getSynsetIterator(pos);
            while (iter.hasNext()) {
                ISynset synset = iter.next();
                words.addAll(getWords(synset.getGloss()));
            }
        }
               
        CrownLogger.verbose("Found %d unique words in glosses", words.size());
        return words;
    }

    private Set<String> getWords(String gloss) {
        String[] tokens = gloss.split("\\s+");       
        
        Matcher m = WORD.matcher("");
        Set<String> s = new HashSet<String>(tokens.length);
        
        for (String t : tokens) {
            m.reset(t);
            if (!m.matches())
                continue;
            s.add(m.group(1));
        }

        return s;
    }
    
    /**
     * TODO
     */
    private float[] getVector(String gloss) {
        float[] aggregatedVec = glossToVecCache.get(gloss);
        if (aggregatedVec != null)
            return aggregatedVec;

        for (String token : getWords(gloss)) {

            float[] vec = wordToVector.get(token);
            if (vec == null)
                continue;

            if (aggregatedVec == null) 
                aggregatedVec = Arrays.copyOf(vec, vec.length);
            else {
                for (int i = 0; i < vec.length; ++i)
                    aggregatedVec[i] += vec[i];
            }
        }

        glossToVecCache.put(gloss, aggregatedVec);
        return aggregatedVec;
    }
    
    /**
     * {@inheritDoc}
     */
    public double compare(String string1, String string2) {
        float[] v1 = getVector(string1);
        float[] v2 = getVector(string2);          

        if (v1 == null || v2 == null)
            return 0;

        float dotProduct = 0.0f;
        float aMagnitude = 0.0f;
        float bMagnitude = 0.0f;
        for (int i = 0; i < v1.length ; i++) {
            float aValue = v1[i];
            float bValue = v2[i];
            aMagnitude += aValue * aValue;
            bMagnitude += bValue * bValue;
            dotProduct += aValue * bValue;
        }
        aMagnitude = (float)Math.sqrt(aMagnitude);
        bMagnitude = (float)Math.sqrt(bMagnitude);
        return (aMagnitude == 0 || bMagnitude == 0)
            ? 0
            : dotProduct / (aMagnitude * bMagnitude);
    }


    private Map<String,float[]> loadVectors2(File vectorFile,
                                            Set<String> words) throws IOException {


        DataInputStream dis = new DataInputStream(
            new BufferedInputStream(new FileInputStream(vectorFile)));
        
        int numWords = dis.readInt();
        int size = dis.readInt();
        
        Map<String,float[]> wordToVec = new HashMap<String,float[]>(numWords);

        for (int i = 0; i < numWords; i++) {
            String word = dis.readUTF();
            if (words.contains(word)) {
                float[] vector = new float[size];
                for (int j = 0; j < size; j++)
                    vector[j] = dis.readFloat();
                wordToVec.put(word, vector);
            }
            else {
                for (int j = 0; j < size; j++)
                    dis.readFloat();
            }
            if (i % (numWords / 10) == 0) {
                CrownLogger.veryVerbose("Read %d/%d vectors, loaded %d",
                                        i, numWords, wordToVec.size());
            }
        }
        dis.close();

        return wordToVec;
    }
    
    private Map<String,float[]> loadVectors(File vectorFile,
                                            Set<String> words) throws IOException {

        CrownLogger.verbose("Loading Vectors");        
        int numWords;
        int vectorLength;
        double len;

        DataInputStream fis = new DataInputStream(
            new BufferedInputStream(new FileInputStream(vectorFile)));

        StringBuilder sb = new StringBuilder();
        char ch = (char) fis.read();
        while (ch != '\n') {
            sb.append(ch);
            ch = (char) fis.read();
        }

        String line = sb.toString();
        String[] parts = line.split("\\s+");
        numWords = (int) Long.parseLong(parts[0]);
        
        Map<String,float[]> wordToVec = new HashMap<String,float[]>(numWords);
        
        vectorLength = (int) Long.parseLong(parts[1]);

        byte[] orig = new byte[4];
        byte[] buf = new byte[4];
        for (int w = 0; w < numWords; w++) {

            String st = readString(fis);
            
            float[] m = new float[vectorLength];
            for (int j = 0; j < vectorLength; j++) {
                m[j] = readFloat(fis);
            }            

            if (words.contains(st)) {
                len = 0;
                for (int i = 0; i < vectorLength; i++)
                    len += m[i] * m[i];
                len = (float) Math.sqrt(len);
                for (int i = 0; i < vectorLength; i++)
                    m[i] /= len;
                
                wordToVec.put(st, m);
            }
            else {
                // System.out.printf("\"%s\" is not in the set%n", st);
            }
            if (w % (numWords / 10) == 0) {
                CrownLogger.veryVerbose("Read %d/%d vectors, loaded %d",
                                        w, numWords, wordToVec.size());
            }

        }

        return wordToVec;
    }


    /**
     * Read a float from a data input stream Credit to:
     * https://github.com/NLPchina/Word2VEC_java/blob/master/src/com/ansj/vec/Word2VEC.java
     *
     * @param is
     * @return
     * @throws IOException
     */
    public static float readFloat(InputStream is)
        throws IOException
    {
        byte[] bytes = new byte[4];
        is.read(bytes);
        return getFloat(bytes);
    }

    /**
     * Read a string from a data input stream Credit to:
     * https://github.com/NLPchina/Word2VEC_java/blob/master/src/com/ansj/vec/Word2VEC.java
     *
     * @param b
     * @return
     * @throws IOException
     */
    public static float getFloat(byte[] b)
    {
        int accum = 0;
        accum = accum | (b[0] & 0xff) << 0;
        accum = accum | (b[1] & 0xff) << 8;
        accum = accum | (b[2] & 0xff) << 16;
        accum = accum | (b[3] & 0xff) << 24;
        return Float.intBitsToFloat(accum);
    }
    
    public static String readString(DataInputStream dis)
        throws IOException
    {
        byte[] bytes = new byte[1024];
        byte b = dis.readByte();
        int i = -1;
        StringBuilder sb = new StringBuilder();
        while (b != 32 && b != 10) {
            i++;
            bytes[i] = b;
            b = dis.readByte();
            if (i == 49) {
                sb.append(new String(bytes));
                i = -1;
                bytes = new byte[1024];
            }
        }
        sb.append(new String(bytes, 0, i + 1));
        return sb.toString();
    }    
}
