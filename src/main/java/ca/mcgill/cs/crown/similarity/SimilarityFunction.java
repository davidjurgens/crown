package ca.mcgill.cs.crown.similarity;

public interface SimilarityFunction {

    /**
     * Compares the two texts and returns their semantic similarity.
     */
    double compare(String text1, String text2);
}
