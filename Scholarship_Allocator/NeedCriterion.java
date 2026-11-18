package Scholarship_Allocator;

/**
 * "Other criteria" ng design (10%): household need.
 *
 * Sinusukat kung gaano kabigat ang gastusin ng pamilya kada tao,
 * kaya hinahati ang buwanang kita sa bilang ng miyembro ng pamilya
 * (applicant + dependents). Mas maliit ang kita kada tao = mas mataas
 * ang pangangailangan = mas mataas ang score.
 */
public class NeedCriterion extends Criterion{

    /** Kita kada miyembro kung saan zero na ang score (sapat na ang kita). */
    private static final double COMFORTABLE = 30000;

    public NeedCriterion(double w){
        super(w);
    }

    @Override
    public double computeScore(Applicant a){
        double members = a.getDependents() + 1;          // kasama ang applicant
        double perMember = a.getIncome() / members;

        double score = (COMFORTABLE - perMember) / COMFORTABLE * 100;
        return Math.max(0, Math.min(100, score));
    }
}
