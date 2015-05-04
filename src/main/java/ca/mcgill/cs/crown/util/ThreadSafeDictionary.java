package ca.mcgill.cs.crown.util;

import edu.mit.jwi.*;
import edu.mit.jwi.item.*;
import edu.mit.jwi.item.POS;

import java.io.IOException;

import java.util.Iterator;

public class ThreadSafeDictionary implements IDictionary {

    private final IDictionary dict;

    public ThreadSafeDictionary(IDictionary dict) {
        this.dict = dict;
    }

    public synchronized void close() {
        dict.close();
    }
    
    public synchronized IExceptionEntry getExceptionEntry(IExceptionEntryID id) {
        return dict.getExceptionEntry(id);
    }

    public synchronized IExceptionEntry getExceptionEntry(String surfaceForm, POS pos)  {
        return dict.getExceptionEntry(surfaceForm, pos);
    }
    
    public synchronized Iterator<IExceptionEntry> getExceptionEntryIterator(POS pos) {
        return dict.getExceptionEntryIterator(pos);
    }

    public synchronized IIndexWord getIndexWord(IIndexWordID id) {
        return dict.getIndexWord(id);
    }

    public synchronized IIndexWord getIndexWord(String lemma, POS pos) {
        return dict.getIndexWord(lemma, pos);
    }

    public synchronized Iterator<IIndexWord> getIndexWordIterator(POS pos) {
        return dict.getIndexWordIterator(pos);
    }

    public synchronized ISenseEntry getSenseEntry(ISenseKey key) {
        return dict.getSenseEntry(key);
    }

    public synchronized Iterator<ISenseEntry> getSenseEntryIterator() {
        return dict.getSenseEntryIterator();
    }

    public synchronized ISynset getSynset(ISynsetID id) {
        return dict.getSynset(id);
    }

    public synchronized Iterator<ISynset> getSynsetIterator(POS pos) {
        return dict.getSynsetIterator(pos);
    }

    public synchronized IWord getWord(ISenseKey key) {
        return dict.getWord(key);
    }

    public synchronized IWord getWord(IWordID id) {
        return dict.getWord(id);
    }

    public synchronized IVersion getVersion() {
        return dict.getVersion();
    }
    
    public synchronized boolean isOpen() {
        return dict.isOpen();
    }
    
    public synchronized boolean open() throws IOException {
        return dict.open();
    }
}
