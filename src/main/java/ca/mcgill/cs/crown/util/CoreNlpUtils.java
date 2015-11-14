/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.util;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;


import java.util.Properties;

/**
 * A collection of utility functions around CoreNLP, which allows thread-safe
 * access to thread-local instances of CoreNLP, if needed.
 */
public class CoreNlpUtils {

    private static final ThreadLocal<StanfordCoreNLP> pipelines
        = new ThreadLocal<StanfordCoreNLP>();

    /**
     * Returns the thread-local copy of a {@link StanfordCoreNLP} instance.
     *
     * @return the thread-local copy of a {@link StanfordCoreNLP} instance.
     */
    public static StanfordCoreNLP get() {
        StanfordCoreNLP pipeline = pipelines.get();
        if (pipeline == null) {
            Properties props = new Properties();
            props.put("annotators", "tokenize, ssplit, pos, lemma, parse");
            props.put("tokenize.options", "untokenizable=noneDelete");
            pipeline = new StanfordCoreNLP(props);
            pipelines.set(pipeline);
        }
        return pipeline;
    }
    
}
