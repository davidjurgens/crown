import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public class PreprocessGlosses {

    public static void main(String[] args) throws Exception {

        File wiktFile = new File(args[0]);

        BufferedReader br = new BufferedReader(new FileReader(wiktFile));
        next_gloss:
        for (String line = null; (line = br.readLine()) != null; ) {
            try {
                process(line);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        br.close();
    }

    private static void process(String line) {
        //System.out.println("DEBUG: " + line);
        String[] arr = line.split("\t");
        if (arr.length < 2)
            return;
        String sense = arr[0];
        String gloss = arr[1];            
            
            // Remove all the extraneous annotations on the gloss, e.g.,
            // {{context| ...}}
            String[] annotations = new String[] {
                "context", "defdate", "qualifier", "label", "rfdef",
                "non-gloss definition", "cx", "&lit", "defn",
                "eye dialect", "rfquotek", "rfc-sense", "anchor",
                "rft-sense", "senseid", "rfex", "rfm-sense", "rfv-sense"};
            for (String anno : annotations) {
                int i = gloss.indexOf("{{" + anno);
                while (i >= 0) {
                    // Find the end brace, which could have embedded braces in it.
                    int j = i+2;
                    for (int braces = 2; braces > 0 && j < gloss.length(); ++j) {
                        char c = gloss.charAt(j);
                        if (c == '{')
                            braces++;
                        else if (c == '}')
                            braces--;
                    }
                    if (i == 0)
                        gloss = gloss.substring(j);
                    else 
                        gloss = gloss.substring(0, i) + " "
                            + gloss.substring(j);
                    i = gloss.indexOf("{{" + anno);
                }
            }

            // Some wiki editors put commas in braces like {{,}}.  Replace that
            // with just a comma
            gloss = gloss.replace("{{,}}", ",");

            // Strip off the trailing punctionation if it exists and remove all
            // extra white space
            if (gloss.endsWith("."))
                gloss = gloss.substring(0, gloss.length() - 1);
            gloss = gloss.trim();

            // Especially for verbs, the gloss might indicate that the word form
            // is a particular lexicalization of a lemma.  The text for these
            // varies (since it is manually entered), but we try to strip it out
            // by look for the {{<some explanation> of|lemma-form}}
            //
            // FYI: do/while is just so we can break out of this condition in
            // rare-but-weird cases where the gloss match this if-condition but
            // isn't actually one of the cases we expect
            do {
            if (gloss.startsWith("{{") 
                    && (gloss.indexOf(" of|") > 0 || gloss.endsWith("}}"))) {

                //System.out.println(gloss);
                
                int i = gloss.indexOf(" of|");
                // check for spacing because editors make mistakes
                if (i < 0)
                    i = gloss.indexOf(" of |");
                // check for non-standard lemmatization
                if (i < 0)
                    i = gloss.indexOf("short for|");
                if (i < 0) {
                    //System.out.println("unhandled wikt markup: " + line);
                    break;
                }
                i = gloss.indexOf('|', i);
                String lemmaForm = gloss.substring(i+1, gloss.length() - 2);
                
                // In some versions, the "form of|" is followed by which type of
                // form (e.g., "Attributive form").  Here, we strip out the
                // second column to get the third column
                if (gloss.contains("form of|")) {
                    i = lemmaForm.indexOf('|');
                    lemmaForm = lemmaForm.substring(i+1);
                }
                
                // Sometimes the lemma forms include the language, which must be
                // removed
                i = lemmaForm.indexOf('|');
                if (i >= 0)
                    lemmaForm = lemmaForm.substring(0, i);
                System.out.println(sense + "\tLEMMATIZED-FORM\t" + lemmaForm);
                return;
            } }while (false);
            
            // Lazily clean up the HTML escaping
            gloss = gloss.replace("&eacute;", "é");
            gloss = gloss.replace("&nbsp;", " ");
            
            // split into sub-definitions, which are usually denoted with ';'
            String[] subdefs = gloss.split(";");            
            
            for (String subdef : subdefs) {
                subdef = subdef.trim();

                // In some rare cases, a subdef is an alternative form 
                if (subdef.startsWith("{{") && subdef.endsWith("}}")) {
                    int i = gloss.indexOf(" of|");
                    // check for spacing because editors make mistakes
                    if (i < 0)
                        i = gloss.indexOf(" of |");
                    // check for non-standard lemmatization
                    if (i < 0)
                        i = gloss.indexOf("short for|");
                    if (i >= 0) {
                        i = gloss.indexOf('|', i);
                        String lemmaForm =
                            gloss.substring(i+1, gloss.length() - 2);

                        // In some versions, the "form of|" is followed by which
                        // type of form (e.g., "Attributive form").  Here, we
                        // strip out the second column to get the third column
                        if (gloss.contains("form of|")) {
                            i = lemmaForm.indexOf('|');
                            lemmaForm = lemmaForm.substring(i+1);
                        }
                        
                        // Sometimes the lemma forms include the language, which
                        // must be removed
                        i = lemmaForm.indexOf('|');
                        if (i >= 0)
                            lemmaForm = lemmaForm.substring(0, i);
                        System.out.println(sense + "\tLEMMATIZED-FORM\t"
                                           + lemmaForm);
                        continue;
                    }
                }

                
                Set<String> linkedConcepts = new LinkedHashSet<String>();

                // Some subdefinitions are first names, e.g., "Tatiana", which
                // we replace and report as forename
                int i = subdef.indexOf("{{given name|");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    if (j < 0)
                        throw new Error(subdef);
                    String wikiPage = subdef.substring(i+4, j);
                    subdef = subdef.replace(subdef.substring(i, j+2), "a forename");
                    i = subdef.indexOf("{{given name|");
                }


                // Some editors use {{w|article name}} to link to wikipedia.  We
                // treat these as definitional links too.
                i = subdef.indexOf("{{w|");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    // Ughhh... in some rare cases, the editor mixed up the
                    // braces and closed a tag with the wrong one
                    if (j < 0)
                        j = subdef.indexOf("]]", i+4);
                    if (j < 0)
                        throw new Error(subdef);
                    String wikiPage = subdef.substring(i+4, j);
                    subdef = subdef.replace(subdef.substring(i, j+2), wikiPage);
                    i = subdef.indexOf("{{w|");
                }

                // Some editors use {{l|lang|text}} to fancily link stuff.
                // Sometimes they use it incorrectly with {{l/lang|text}}
                // too. We treat these as definitional links too.
                i = subdef.indexOf("{{l");
                while (i >= 0) {
                    char c = subdef.charAt(i+3);
                    // This link must be some other kind of weird Wikt markup
                    if (!(c == '|' || c == '/')) {
                        i = subdef.indexOf("{{l", i + 4);
                        continue;
                    }

                    int j = subdef.indexOf("}}", i+2);
                    if (j < 0) {
                        throw new Error(line);
                    }

                    String inside = subdef.substring(i+4, j);
                    arr = inside.split("\\|");
                    // Could be {{l|**|link|text}} or just the text
                    if (arr.length == 2) {
                        String text = arr[1];
                        String link = arr[1];
                        // Treat this as a regularly linked item, which makes it
                        // easier for later processing
                        subdef = subdef.replace(subdef.substring(i, j+2),
                                                "[[" + text + "]]");
                    }
                    else if (arr.length >= 3) {
                        String text = arr[arr.length - 1];
                        String link = arr[arr.length - 2];
                        // Treat this as a regularly linked item, which makes it
                        // easier for later processing
                        subdef = subdef.replace(subdef.substring(i, j+2),
                                                "[[" +text + "]]");

                    }
                    else
                        throw new Error(subdef);
                    i = subdef.indexOf("{{l");
                }

                // Some editors include taxonomic information via taxlink, e.g,
                // {{taxlink|Larrea tridentata|species}}.  Strip out the markup
                // and use the middle item
                i = subdef.indexOf("{{taxlink");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    // Messy closing braces
                    if (j < 0) {
                        j = subdef.indexOf("}]", i+4);
                    }
                    if (j <= 0) {
                        System.err.println(line);
                        throw new Error(line);
                    }
                    String inside = subdef.substring(i + 2, j);
                    arr = inside.split("\\|");
                    String text = arr[1];
                    subdef = subdef.replace(subdef.substring(i, j+2), text);
                    linkedConcepts.add(text);
                    i = subdef.indexOf("{{taxlink");
                }

                // Some editors use the {{soplink|} markup which has a variable
                // number of parameters, {{soplink|deep|water}}.  The tag tries
                // to link all the entites in it into a single term if it
                // exists, and otherwise links the individual terms.  We just
                // treat it as one term.
                i = subdef.indexOf("{{soplink");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    // Messy closing braces
                    if (j < 0) {
                        j = subdef.indexOf("}]", i+4);
                    }
                    if (j <= 0) {
                        System.err.println(line);
                        throw new Error(line);
                    }
                    String inside = subdef.substring(i + 2, j);
                    arr = inside.split("\\|");
                    String text = arr[1];
                    //System.out.println(inside);
                    for (int k = 2; k < arr.length; ++k)
                        text += " " + arr[k]; // lazy, I know
                    subdef = subdef.replace(subdef.substring(i, j+2),
                                            "[[" + text + "]]");
                    linkedConcepts.add(text);
                    i = subdef.indexOf("{{soplink");
                }

                // Some editors include comparison links in the form
                // {{term|circannual|lang=en}}
                i = subdef.indexOf("{{term");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    // Messy closing braces
                    if (j < 0) {
                        j = subdef.indexOf("}]", i+4);
                    }
                    if (j <= 0) {
                        System.err.println(line);
                        throw new Error(line);
                    }
                    String inside = subdef.substring(i + 2, j);
                    arr = inside.split("\\|");
                    String text = arr[1];
                    // Pretend this is just a hyperlinked term
                    subdef = subdef.replace(subdef.substring(i, j+2),
                                            "[[" + text + "]]");
                    i = subdef.indexOf("{{term");
                }


                // Some editors add a gloss in its own markup, e.g,
                // {{gloss|stuff}}.  Strip out the markup and use the middle
                // item
                i = subdef.indexOf("{{gloss");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    // Somestimes people put ; inside the gloss definitions
                    // which destroys our parsing.  just set j to the end of the
                    // line
                    int offset = 2;
                    if (j < 0) {
                        j = subdef.length();
                        offset = 0;
                        // System.err.println(line);
                        // throw new Error(line);
                    }
                    String inside = subdef.substring(i + 3, j);
                    arr = inside.split("\\|");
                    String text = arr[1];
                    // Add a full stop before the gloss to ensure this text is
                    // processed separately from any other text 
                    subdef = subdef.replace(subdef.substring(i, j+offset),
                                            "." + text);
                    linkedConcepts.add(text);
                    i = subdef.indexOf("{{gloss");
                }


                // Some editors include unsupported text in the wikimedia markup
                // using {{unsupported, e.g., {{unsupported|#}}.  Strip out the
                // markup and use the text anyway
                i = subdef.indexOf("{{unsupported");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    String inside = subdef.substring(i + 2, j);
                    arr = inside.split("\\|");
                    String text = arr[1];
                    subdef = subdef.replace(subdef.substring(i, j+2), text);
                    linkedConcepts.add(text);
                    i = subdef.indexOf("{{unsupported");
                }

                // Some markup indicates the lemma is a surname, but uses
                // {{surname|...}} instead of the text "a [[surname]]" so we
                // grab these too
                i = subdef.indexOf("{{surname");
                while (i >= 0) {
                    int j = subdef.indexOf("}}", i+4);
                    subdef = subdef.replace(subdef.substring(i, j+2), "surname");
                    linkedConcepts.add("surname");
                    i = subdef.indexOf("{{surname");
                }

                // Copy the subdefinition with links to make it easier to look
                // for candidates in the later stages
                String subdefWithLinks = subdef;
                // Clean up the links by replacing [[link|text]] with just
                // [[link]] for later stages
                i = subdefWithLinks.indexOf("[[");
                while (i >= 0) {
                    int offset = 2;
                    int j = subdefWithLinks.indexOf("]]", i+2);
                    // Guard against bad closing braces
                    if (j < 0) {
                        j = subdefWithLinks.indexOf("]", i+2);
                        offset = 1;
                    }
                    // Handle the terrible case where someone has a mismatched
                    // brace and we're at the end the sentence
                    if (j < 0) {
                        j = subdefWithLinks.indexOf(' ', i+2);
                        if (j < 0)
                            j = subdefWithLinks.length();
                        offset = 0;
                    }
                    if (j < 0)
                        throw new Error(line);
                    String linkBody = subdefWithLinks.substring(i+2, j);
                    int k = linkBody.indexOf('|');
                    if (k > 0) {
                        String linked = linkBody.substring(0, k);
                        String text = linkBody.substring(k+1);
                        linkedConcepts.add(linked);
                        subdefWithLinks = subdefWithLinks.replace(subdefWithLinks.substring(i, j+offset),
                                                "[[" + linked + "]]");
                    }
                    else {
                        linkedConcepts.add(linkBody);
                        subdefWithLinks = subdefWithLinks.replace(subdefWithLinks.substring(i, j+offset),
                                                "[[" + linkBody + "]]");
                    }
                    i = subdefWithLinks.indexOf("[[", i+4);
                }


                i = subdef.indexOf("[[");
                while (i >= 0) {
                    int offset = 2;
                    int j = subdef.indexOf("]]", i+2);
                    // Guard against bad closing braces
                    if (j < 0) {
                        j = subdef.indexOf("]", i+2);
                        offset = 1;
                    }
                    // Handle the terrible case where someone has a mismatched
                    // brace and we're at the end the sentence
                    if (j < 0) {
                        j = subdef.indexOf(' ', i+2);
                        if (j < 0)
                            j = subdef.length();
                        offset = 0;
                    }
                    if (j < 0)
                        throw new Error(line);
                    String linkBody = subdef.substring(i+2, j);
                    int k = linkBody.indexOf('|');
                    if (k > 0) {
                        String linked = linkBody.substring(0, k);
                        String text = linkBody.substring(k+1);
                        linkedConcepts.add(linked);
                        subdef = subdef.replace(subdef.substring(i, j+offset), text);
                    }
                    else {
                        linkedConcepts.add(linkBody);
                        subdef = subdef.replace(subdef.substring(i, j+offset), linkBody);
                    }
                    i = subdef.indexOf("[[");
                }
                
                System.out.print(sense + "\tSUBDEF\t" + subdef + "\t" +
                                 subdefWithLinks);
                for (String concept : linkedConcepts)
                    System.out.print("\t" + concept);
                System.out.println();
            }
            
    }


}
