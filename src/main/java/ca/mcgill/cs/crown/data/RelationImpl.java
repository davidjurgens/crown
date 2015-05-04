/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown;


/**
 * A basic implementation of the {@link Relation} interface.
 */
public class RelationImpl implements Relation {

    private final RelationType type;

    private final String lemma;

    private final String sense;

    public RelationImpl(String lemma, String sense, RelationType type) {
        this.lemma = lemma;
        this.sense = sense;
        this.type = type;
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getTargetLemma() {
        return lemma;
    }

    /**
     * {@inheritDoc}
     */
    @Override public String getTargetSense() {
        return sense;
    }

    /**
     * {@inheritDoc}
     */
    @Override public RelationType getType() {
        return type;
    }

    public String toString() {
        return String.format("Relation[%s (%s): %s]", lemma, sense, type);
    }
}
