/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.data;

import de.tudarmstadt.ukp.jwktl.JWKTL;

import de.tudarmstadt.ukp.jwktl.api.IWikiString;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryEdition;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryEntry;
import de.tudarmstadt.ukp.jwktl.api.IWiktionaryRelation;
import de.tudarmstadt.ukp.jwktl.api.IWiktionarySense;
import de.tudarmstadt.ukp.jwktl.api.PartOfSpeech;
import de.tudarmstadt.ukp.jwktl.api.RelationType;

import de.tudarmstadt.ukp.jwktl.api.filter.WiktionaryEntryFilter;

import de.tudarmstadt.ukp.jwktl.api.util.Language;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import edu.ucla.sspace.util.LineReader;

import edu.stanford.nlp.util.CoreMap;

import ca.mcgill.cs.crown.LexicalEntry;
import ca.mcgill.cs.crown.LexicalEntryImpl;
import ca.mcgill.cs.crown.Relation;
import ca.mcgill.cs.crown.RelationImpl;
import ca.mcgill.cs.crown.CrownAnnotations;

import ca.mcgill.cs.crown.util.CrownLogger;
import ca.mcgill.cs.crown.util.WiktionaryUtils;

import edu.mit.jwi.item.POS;


/**
 * A class for converting Wiktionary data in several forms into a series of
 * {@link LexicalEntry} objects.
 */
public class WiktionaryReader {

    public List<LexicalEntry> loadFromDump(File wiktionaryXmlDump,
                                           File owd,
                                           File outputPreprocessedFile)
            throws IOException {

        // Use the parent directory of outputPreprocessedFile for output if unspecified
        File outputWiktionaryDir = (owd != null) ? owd : outputPreprocessedFile.getParentFile();

        // Sanity check that we're not needlessly extracting from the dump file
        // by seeing if the directory already contains the extracted data
        if (outputWiktionaryDir.exists()
                && outputWiktionaryDir.listFiles().length > 0) {
            try {
                IWiktionaryEdition wikt = JWKTL.openEdition(outputWiktionaryDir);
                CrownLogger.info("Loading Wiktionary data from " +
                               "already-parsed result");
                List<JSONObject> rawEntries =
                    extract(wikt, outputPreprocessedFile);
                return convertToEntries(rawEntries);

            } catch (Throwable t) {
                // Ignore because we'll process the dump file to get the data
                // anyway
            }
        }

        JWKTL.parseWiktionaryDump(wiktionaryXmlDump, outputWiktionaryDir, true);
        return loadFromDir(outputWiktionaryDir, outputPreprocessedFile);
    }

    public List<LexicalEntry> loadFromDir(File wiktionaryDir,
                                          File preprocessedOutputFile) {
        IWiktionaryEdition wikt = JWKTL.openEdition(wiktionaryDir);
        List<JSONObject> rawEntries = extract(wikt, preprocessedOutputFile);
        return convertToEntries(rawEntries);
    }

    public List<LexicalEntry> loadFromPreprocessed(File preprocessedFile) {
        List<JSONObject> rawEntries = new ArrayList<JSONObject>(500_000);
        for (String line : new LineReader(preprocessedFile)) {
            try {
                JSONObject rawEntry = new JSONObject(line);
                rawEntries.add(rawEntry);
            } catch (JSONException je) {
                throw new IOError(je);
            }
        }
        return convertToEntries(rawEntries);
    }

    private static List<LexicalEntry>
        convertToEntries(List<JSONObject> rawEntries) {

        // Avoid the potential for duplicates in the entries
        Set<String> alreadyIncluded = new HashSet<String>();
        int excluded = 0;
        
        List<LexicalEntry> entries = new ArrayList<LexicalEntry>();
        for (JSONObject jo : rawEntries) {
            try {
                String posStr = jo.getString("pos").toUpperCase();
                String lemma = jo.getString("lemma");
                String id = jo.getString("id");
                // Check for duplicates
                if (alreadyIncluded.contains(lemma + "." + posStr + ":" + id)) {
                    excluded++;
                    continue;
                }
                alreadyIncluded.add(lemma + ":" + id);
                LexicalEntry e =
                    new LexicalEntryImpl(lemma, id, POS.valueOf(posStr));
                Set<String> glosses = new LinkedHashSet<String>();
                Map<String,String> rawGlossToCleaned =
                    new LinkedHashMap<String,String>();
                
                JSONArray glossArr = jo.getJSONArray("glosses");
                for (int i = 0; i < glossArr.length(); ++i) {
                    String rawGloss = glossArr.getString(i);
                    String cleaned = WiktionaryUtils.cleanGloss(rawGloss);
                    glosses.add(cleaned);
                    rawGlossToCleaned.put(rawGloss, cleaned);
                }
                
                String combinedGloss = String.join(" ", glosses);
                
                List<Relation> relations = new ArrayList<Relation>();
                JSONArray relationsArr = jo.getJSONArray("relations");
                for (int i = 0; i < relationsArr.length(); ++i) {
                    JSONObject relObj = relationsArr.getJSONObject(i);
                    Relation rel = new RelationImpl(
                        relObj.getString("targetLemma"),
                        relObj.optString("targetSense"),
                        Relation.RelationType.valueOf(
                            relObj.getString("type")));
                    relations.add(rel);
                }
            
                CoreMap m = e.getAnnotations();
                m.set(CrownAnnotations.Gloss.class, combinedGloss);
                m.set(CrownAnnotations.Glosses.class, glosses);
                m.set(CrownAnnotations.RawGlosses.class, rawGlossToCleaned);
                m.set(CrownAnnotations.Relations.class, relations);

                entries.add(e);
            }
            catch (JSONException je) {
                throw new IOError(je);
            }
        }

        CrownLogger.verbose("Excluded %d duplicate entries", excluded);
        
        return entries;
    }

    private List<JSONObject> extract(IWiktionaryEdition wikt,
                                     File outputFile) {
        try {
            return extract_(wikt, outputFile);
        } catch (Exception e) {
            // Ugh... lazy
            throw new IOError(e);
        }
    }
    
    private List<JSONObject> extract_(IWiktionaryEdition wikt,
                                     File outputFile)
            throws IOException, JSONException {
        
        List<JSONObject> rawEntries = new ArrayList<JSONObject>(500_000);
        WiktionaryEntryFilter filter = new WiktionaryEntryFilter();
        filter.setAllowedWordLanguages(Language.ENGLISH);

        PrintWriter pw = null;
        if (outputFile != null)
            pw = new PrintWriter(outputFile);

        int i = 0;
        for (IWiktionaryEntry entry : wikt.getAllEntries(filter)) {
            PartOfSpeech pos = entry.getPartOfSpeech();
            POS pos_ = null;
            if (pos == null) {
                if (++i % 10_000 == 0)
                    CrownLogger.info("Processed %d entries", i);
                continue;
            }
            String lemma = entry.getWord();
            char posChar = 'n';
            switch (pos) {
                case NOUN:
                case PROPER_NOUN:
                case MEASURE_WORD:
                    posChar = 'n';
                    pos_ = POS.NOUN;
                    break;
            case VERB:
                posChar = 'v';
                pos_ = POS.VERB;
                break;
            case ADJECTIVE:
                posChar = 'a';
                pos_ = POS.ADJECTIVE;
                break;
            case ADVERB:
                posChar = 'r';
                pos_ = POS.ADVERB;
                break;
                // We don't want to deal with other POS tags
            default:
                // System.out.printf("Skipping %s %s%n", lemma, pos);
                if (++i % 10_000 == 0)
                    CrownLogger.info("Processed %d entries", i);
                continue;
            }


            for (IWiktionarySense sense : entry.getSenses()) {
                IWikiString gloss = sense.getGloss();
                List<IWikiString> examples = sense.getExamples();
                if (examples == null)
                    examples = Collections.<IWikiString>emptyList();
                int senseNum = sense.getIndex();
                List<String> rawGlosses =
                    Arrays.asList(gloss.getText().split("\n"));

                JSONObject rawEntry = new JSONObject();
                rawEntry.put("sense", lemma + "." + posChar + "." + senseNum);
                rawEntry.put("id", lemma + ":" + sense.getId());
                rawEntry.put("lemma", lemma);
                rawEntry.put("pos", pos_.toString());
                
                JSONArray glossArr = new JSONArray();
                for (String rawGloss : rawGlosses)
                    glossArr.put(rawGloss);
                rawEntry.put("glosses", glossArr);

                JSONArray examplesArr = new JSONArray();
                for (IWikiString example : examples)
                    examplesArr.put(example.getText());
                rawEntry.put("examples", examplesArr);

                JSONArray relArr = new JSONArray();
                List<IWiktionaryRelation> relations = sense.getRelations();
                if (relations == null)
                    relations = Collections.<IWiktionaryRelation>emptyList();
                for (IWiktionaryRelation rel : relations) {
                    JSONObject relObj = new JSONObject();
                    relObj.put("targetLemma", rel.getTarget());
                    relObj.put("targetSense", rel.getTargetSense());
                    relObj.put("type", rel.getRelationType().toString());
                    relArr.put(relObj);
                }
                rawEntry.put("relations", relArr);
                
                if (pw != null)
                    pw.println(rawEntry.toString());

                rawEntries.add(rawEntry);
                //System.out.printf("%s.%s.%d: %s %s%n", lemma, pos, senseNum, rawGlosses, examples);
            }
            
            if (++i % 10_000 == 0) {
                CrownLogger.info("Processed %d entries", i);
            }
        }
        wikt.close();
        if (pw != null)
            pw.close();
        return rawEntries;
    }

}
