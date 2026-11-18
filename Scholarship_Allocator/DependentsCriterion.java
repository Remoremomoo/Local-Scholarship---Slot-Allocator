package Scholarship_Allocator;

/**
 * SMART value curve para sa bilang ng dependents (20%).
 *
 * Dating formula: min(dep, 5) / 5 * 100
 *   Problema: 6 lang ang posibleng value (0, 20, 40, 60, 80, 100) at
 *   naka-cap sa 100 simula 5 dependents — kaya 5, 6, 7... dependents
 *   ay pare-pareho na ang score.
 *
 * Ngayon (SMART, saturating curve):
 *   100 * dep / (dep + 2)
 *
 *   0 -> 0    1 -> 33.3   2 -> 50.0   3 -> 60.0
 *   4 -> 66.7 5 -> 71.4   7 -> 77.8  10 -> 83.3
 *
 * Tumataas pa rin habang dumarami ang dependents, pero may diminishing
 * returns — hindi artificial na cap sa 5.
 */
public class DependentsCriterion extends Criterion {

    /** Ilang dependents bago makuha ang kalahati (50) ng utility. */
    private static final double HALF_POINT = 2.0;

    public DependentsCriterion(double w){
        super(w);
    }

    @Override
    public double computeScore(Applicant a){
        int dependents = Math.max(0, a.getDependents());
        return 100.0 * dependents / (dependents + HALF_POINT);
    }
}
