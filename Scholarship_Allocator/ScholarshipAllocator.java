package Scholarship_Allocator;

import java.util.ArrayList;

public class ScholarshipAllocator{
    private int slots;
    private ArrayList<Applicant> applicants = new ArrayList<>();
    private ArrayList<Criterion> criteria = new ArrayList<>();

    public ScholarshipAllocator(int slots){
        this.slots = slots;
    }

    public void addApplicant(Applicant a){
        applicants.add(a);
    }

    public void addCriterion(Criterion c){
        criteria.add(c);
    }

    public int getSlots(){
        return slots;
    }

    public ArrayList<Applicant> getApplicants(){
        return new ArrayList<>(applicants);
    }

    public ArrayList<Criterion> getCriteria(){
        return new ArrayList<>(criteria);
    }

    public void clearApplicants(){
        applicants.clear();
    }

    public double computeTotalScore(Applicant a){
        double total = 0;
        for (Criterion c : criteria){
            total += c.computeScore(a) * c.getWeight();
        }
        return total;
    }

    /** Kita kada miyembro ng pamilya (applicant + dependents). */
    private static double perMemberIncome(Applicant a){
        return a.getIncome() / (a.getDependents() + 1);
    }

    /**
     * SMART ordering + tie-break cascade.
     *
     * Kahit pareho ang total score, deterministic pa rin ang ranking --
     * walang arbitrary na alphabetical o insertion-order na resulta:
     *
     *   1. Total weighted score   - pababa
     *   2. Grade                  - mas mataas na academic
     *   3. Kita kada miyembro     - mas mababa = mas nangangailangan
     *   4. Dependents             - mas marami
     *   5. Pangalan               - huling paniguro, stable at reproducible
     */
    public int compareApplicants(Applicant x, Applicant y){
        int byScore = Double.compare(computeTotalScore(y), computeTotalScore(x));
        if (byScore != 0) return byScore;

        int byGrade = Double.compare(y.getGrade(), x.getGrade());
        if (byGrade != 0) return byGrade;

        int byNeed = Double.compare(perMemberIncome(x), perMemberIncome(y));
        if (byNeed != 0) return byNeed;

        int byDependents = Integer.compare(y.getDependents(), x.getDependents());
        if (byDependents != 0) return byDependents;

        return x.getName().compareToIgnoreCase(y.getName());
    }

    public void printResults(){
        applicants.sort(this::compareApplicants);

        for (int i = 0; i < applicants.size(); i++){
            Applicant a = applicants.get(i);
            String result;

            if (i < slots){
                result = "GRANTED";
            } else{
                result = "WAITLIST";
            }

            System.out.println((i + 1) + ". " + a.getName() + " (" + a.getBarangay() + ") - "
                    + String.format("%.2f", computeTotalScore(a)) + " - " + result);
        }
    }
}
