package ca.mcgill.cs.crown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.ISynset;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.ErasureUtils;

import edu.ucla.sspace.util.Duple;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * The class encapsulation operations that should be performed on an {@link
 * AnnotatedLexicalEntry} when integrating it into the CROWN dictionary.  The
 * static member fields of this class form the keys when retrieving operations
 * from the {@code CoreMap} returned by {@link
 * AnnotatedLexicalEntry#getOperations()}.
 */
public final class CrownOperations {   

    public static final List<Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>>> SET_ARG_OPERATIONS = new ArrayList<Class<? extends CoreAnnotation<Set<Duple<Reason,ISynset>>>>>();

    public static final List<Class<? extends CoreAnnotation<Duple<Reason,ISynset>>>> SINGLE_ARG_OPERATIONS = new ArrayList<Class<? extends CoreAnnotation<Duple<Reason,ISynset>>>>();

    static {
        SINGLE_ARG_OPERATIONS.add(Hypernym.class);
        SINGLE_ARG_OPERATIONS.add(SimilarTo.class);
        SINGLE_ARG_OPERATIONS.add(Synonym.class);
        SINGLE_ARG_OPERATIONS.add(Antonym.class);
        SINGLE_ARG_OPERATIONS.add(Pertainym.class);
        SINGLE_ARG_OPERATIONS.add(DerivationallyRelated.class);
        SINGLE_ARG_OPERATIONS.add(DerivedFromAdjective.class);

        SET_ARG_OPERATIONS.add(MemberMeronym.class);
        SET_ARG_OPERATIONS.add(PartMeronym.class);
        SET_ARG_OPERATIONS.add(DomainTopic.class);
    }
    
    public static class Lexicalization
            implements CoreAnnotation<Set<Duple<Reason,String>>> {
        public Class<Set<Duple<Reason,String>>> getType() {
            return ErasureUtils.uncheckedCast(Set.class);
        }
    }

    public static class Hypernym
        implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }

    public static class Synonym
        implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }
    
    public static class Antonym
            implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }

    public static class Pertainym
            implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }
    
    public static class SimilarTo
            implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }
    
    public static class MemberMeronym
            implements CoreAnnotation<Set<Duple<Reason,ISynset>>> {
        public Class<Set<Duple<Reason,ISynset>>> getType() {
            return ErasureUtils.uncheckedCast(Set.class);
        }
    }

    public static class PartMeronym
            implements CoreAnnotation<Set<Duple<Reason,ISynset>>> {
        public Class<Set<Duple<Reason,ISynset>>> getType() {
            return ErasureUtils.uncheckedCast(Set.class);
        }
    }

    public static class DomainTopic
            implements CoreAnnotation<Set<Duple<Reason,ISynset>>> {
        public Class<Set<Duple<Reason,ISynset>>> getType() {
            return ErasureUtils.uncheckedCast(Set.class);
        }
    }
    
    public static class DerivationallyRelated
            implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }

    public static class DerivedFromAdjective
            implements CoreAnnotation<Duple<Reason,ISynset>> {
        public Class<Duple<Reason,ISynset>> getType() {
            return ErasureUtils.uncheckedCast(Duple.class);
        }
    }


    /**
     * A class that encapsulates the logic for why a particular operation was
     * performed during CROWN construction.  This class acts as a general
     * respository for properties that any {@link
     * ca.mcgill.cs.crown.EnrichmentProcedure} might want to record as a part of
     * its documentation process.  The only required artifact is the class from
     * which the operation originated, which has to be specified in the
     * constructor.  Typically, the contents of this class will simply be
     * serialized as JSON and included in the logging.
     */
    public static class Reason {
        
        private final Class<?> origin;

        private final JSONObject props;

        public Reason(Class<?> origin) {
            this.origin = origin;
            props = new JSONObject();
            try {
                props.put("origin", origin.getName());
            } catch (JSONException je) {
                // ignore all this 
            }
        }

        public Class<?> getOrigin() {
            return origin;
        }

        public void set(String property, String value) {
            try {
                props.put(property, value);
            } catch (JSONException je) {
                // ignore all this 
            }
        }

        public void set(String property, int value) {
            try {
                props.put(property, value);
            } catch (JSONException je) {
                // ignore all this 
            }
        }

        public void set(String property, double value) {
            try {
                props.put(property, value);
            } catch (JSONException je) {
                // ignore all this 
            }
        }

        /**
         * 
         * @param values a collection of objects who will be coverted into
         * {@code String} instances and added as an array
         */
        public void set(String property, Collection<?> values) {
            try {
                JSONArray arr = new JSONArray();
                for (Object o : values) {
                    arr.put(String.valueOf(o));
                }
                props.put(property, arr);
            } catch (JSONException je) {
                // ignore all this 
            }
        }

        public JSONObject toJson() {
            return props;
        }

        public String toString() {
            return "Reason:" + props.toString();
        }
    }
} 
