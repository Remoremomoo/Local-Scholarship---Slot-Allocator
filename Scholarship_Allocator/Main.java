package Scholarship_Allocator;

public class Main{
    public static void main(String[] args){
        ScholarshipAllocator allocator = new ScholarshipAllocator(2);  // 2 slots lang

        // Same weights na ginagamit ng website (api /api/config).
        allocator.addCriterion(new GradeCriterion(0.40));
        allocator.addCriterion(new IncomeCriterion(0.30));
        allocator.addCriterion(new DependentsCriterion(0.20));
        allocator.addCriterion(new NeedCriterion(0.10));

        allocator.addApplicant(new Applicant("Juan", "Brgy. Poblacion", 90, 12000, 4));
        allocator.addApplicant(new Applicant("Maria", "Brgy. San Jose", 95, 40000, 2));
        allocator.addApplicant(new Applicant("Pedro", "Brgy. Poblacion", 80, 9000, 5));

        allocator.printResults();
    }
}