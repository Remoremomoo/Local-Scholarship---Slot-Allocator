package Scholarship_Allocator;

/**
 * SMART value curve para sa family income (30%).
 *
 * Dating formula (step buckets):
 *   < P15,000 -> 100 | < P30,000 -> 90 | < P50,000 -> 80 | else 60
 *
 * Problema nito: 4 na value lang ang posible, kaya pareho ang score ng
 * P8,000 at P14,500 — walang differentiation, at dito nagmumula ang
 * malaking bahagi ng ties.
 *
 * Ngayon (SMART, continuous exponential decay):
 *   100 * exp(-(income - 15000) / 25000)      kung income > 15,000
 *   100                                       kung income <= 15,000
 *
 *  P10k -> 100    P15k -> 100    P20k -> 81.9    P30k -> 54.9
 *  P40k -> 36.8   P50k -> 24.7   P60k -> 16.5
 *
 * Bawat piso ng pagkakaiba sa kita ay may epekto na sa score.
 */
public class IncomeCriterion extends Criterion {

    /** Kita kung saan pababa ay full credit (100). */
    private static final double FULL_CREDIT = 15000;

    /** Gaano kabilis bumaba ang utility kapag lumalaki ang kita. */
    private static final double DECAY = 25000;

    public IncomeCriterion(double w){
        super(w);
    }

    @Override
    public double computeScore(Applicant a){
        double income = a.getIncome();
        if (income <= FULL_CREDIT){
            return 100;
        }
        double score = 100 * Math.exp(-(income - FULL_CREDIT) / DECAY);
        return Math.max(0, Math.min(100, score));
    }
}
