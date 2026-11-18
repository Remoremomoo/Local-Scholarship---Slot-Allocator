package Scholarship_Allocator;

/**
 * SMART value curve para sa academic performance (40%).
 *
 * Dating formula (linear): (grade - 75) / 25 * 100
 * Ngayon (SMART, convex): ((grade - 75) / 25) ^ 1.35 * 100
 *
 * Bakit ganito: sa linear, pantay-pantay ang agwat ng utility kada
 * puntos ng grade, kaya nagkakabuklod ang mga kalapit na aplikante.
 * Sa convex na curve, mas malaki ang agwat sa 85-100 range -- doon
 * talaga nagkakatalo ang mga seryosong aplikante.
 *
 *  75 -> 0      85 -> 29.0     90 -> 50.2
 *  95 -> 74.0  100 -> 100.0
 */
public class GradeCriterion extends Criterion {

    /** Grade kung saan zero ang utility. */
    private static final double PASSING = 75;

    /** Grade kung saan 100 ang utility. */
    private static final double PERFECT = 100;

    /** > 1 = convex (pinapahaba ang gap sa taas ng range). */
    private static final double CURVE = 1.35;

    public GradeCriterion(double w){
        super(w);
    }

    @Override
    public double computeScore(Applicant a){
        double ratio = (a.getGrade() - PASSING) / (PERFECT - PASSING);
        ratio = Math.max(0, Math.min(1, ratio));      // clamp bago i-power
        return Math.pow(ratio, CURVE) * 100;
    }
}
