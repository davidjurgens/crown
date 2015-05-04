package ca.mcgill.cs.crown;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.mit.jwi.item.POS;

import edu.stanford.nlp.ling.CoreAnnotation;

import edu.stanford.nlp.util.ArrayCoreMap;
import edu.stanford.nlp.util.CoreMap;

import edu.ucla.sspace.util.Duple;

import ca.mcgill.cs.crown.CrownOperations.Reason;


/**
 * The basic implementation of {@link AnnotatedLexicalEntry}
 *
 * @see CrownOperations
 */
public class AnnotatedLexicalEntryImpl implements AnnotatedLexicalEntry {

    /**
     * The backing entry
     */
    private final LexicalEntry entry;

    /**
     * The map of operations to perform
     */
    private final CoreMap operations;

    public AnnotatedLexicalEntryImpl(LexicalEntry entry) {
        this.entry = entry;
        operations = new ArrayCoreMap();
    }

    /**
     * {@inheritDoc}
     */
    public <T> void addOp(Class<? extends CoreAnnotation<Set<Duple<Reason,T>>>> opType,
                          Reason reason, 
                          T operationValue) {
        if (operationValue == null) {
            throw new NullPointerException(
                "The value of " + opType + " cannot be null; reason: " +reason);
        }
        
        Set<Duple<Reason,T>> curVals = operations.get(opType);
        Duple<Reason,T> newVal = new Duple<Reason,T>(reason, operationValue);

        if (curVals == null) {
            curVals = new HashSet<Duple<Reason,T>>();
            operations.set(opType, curVals);
        }
        
        curVals.add(newVal);        
    }

    /**
     * {@inheritDoc}
     */
    public <T> void setOp(Class<? extends CoreAnnotation<Duple<Reason,T>>> opType,
                          Reason reason, 
                          T operationValue) {
        if (operationValue == null) {
            throw new NullPointerException(
                "The value of " + opType + " cannot be null; reason: " +reason);
        }
        
        Duple<Reason,T> newVal = new Duple<Reason,T>(reason, operationValue);
        operations.set(opType, newVal);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override public String getLemma() {
        return entry.getLemma();
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getId() {
        return entry.getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override public POS getPos() {
        return entry.getPos();
    }

    /**
     * {@inheritDoc}
     */
    @Override public CoreMap getAnnotations() {
        return entry.getAnnotations();
    }
    
    /**
     * {@inheritDoc}
     */
    public CoreMap getOperations() {
        return operations;
    }

    public String toString() {
        return entry.toString() + " ops: " + operations;
    }
}
