import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import edu.ucla.sspace.common.Similarity;
import edu.ucla.sspace.graph.*;
import edu.ucla.sspace.matrix.*;
import edu.ucla.sspace.util.*;
import edu.ucla.sspace.util.primitive.*;
import edu.ucla.sspace.vector.*;


public class MakeSenseGlosses {
    
    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("usage: java sense-glosses.tsv");
            return;
        }

        Pattern emptyParens = Pattern.compile("\\(\\s*\\)");

        File s2gFile = new File(args[0]);

        Map<String,StringBuilder> senseToGloss
            = new HashMap<String,StringBuilder>(100000);

        for (String line : new LineReader(s2gFile)) {
            String[] arr = line.split("\t");
            // no gloss?
            if (arr.length < 3)
                continue;
            String sense = arr[0];
            String subdef = arr[2];
            StringBuilder sb = senseToGloss.get(sense);
            if (sb == null) {
                sb = new StringBuilder();
                sb.append(subdef);
                senseToGloss.put(sense, sb);
            }
            else {
                sb.append("; ").append(subdef);
            }
        }
        for (Map.Entry<String,StringBuilder> e : senseToGloss.entrySet()) {
            StringBuilder sb = e.getValue();
            String gloss = sb.toString();

            if (gloss.length() > 255) {
                gloss = gloss.substring(0, 255);
            }

            // Special case for certain glosses
            if (gloss.contains(" a) ")) {
                gloss = gloss.replace(" a) ", " (a) ");
                gloss = gloss.replace(" b) ", " (b) ");
                gloss = gloss.replace(" c) ", " (c) ");
            }
            else if (gloss.contains(" 1) ")) {
                gloss = gloss.replace(" 1) ", " (1) ");
                gloss = gloss.replace(" 2) ", " (2) ");
                gloss = gloss.replace(" 3) ", " (3) ");
            }

            
            // Check for matching parens
            int numRight = 0, numLeft = 0;
            for (int i = gloss.indexOf('('); i >= 0; i = gloss.indexOf('(',i+1))
                numLeft++;
            for (int i = gloss.indexOf(')'); i >= 0; i = gloss.indexOf(')',i+1))
                numRight++;

            // if (numRight != numLeft)
            //     System.out.println(numLeft + "\t" + numRight + "\t" + gloss);

            // Fix the glosses.  NOTE: this correction doesn't account for
            // mis-ordered )(, which can also cause havoc for Grind
            while (numLeft > numRight) {
                gloss =   gloss + ")";
                numRight++;
            }

            while (numLeft < numRight) {
                gloss =   "(" + gloss;
                numLeft++;
            }


            // Get rid of the empty parens
            Matcher m = emptyParens.matcher(gloss);
            gloss = m.replaceAll("");

            gloss = gloss.trim();
            
            if (gloss.length() == 0)
                throw new Error();


            //System.out.println(gloss.length());
         
            System.out.println(e.getKey() + "\t" + gloss + " ");
        }
    }
}
