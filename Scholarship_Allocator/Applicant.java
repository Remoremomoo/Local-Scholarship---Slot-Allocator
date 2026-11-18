package Scholarship_Allocator;

public class Applicant extends Person{
    private double grade;
    private double income;
    private int dependents;
    private String barangay;

    /** Username ng student account na nag-submit (null kung walang account). */
    private String owner;

    Applicant(String name, String barangay, double grade, double inc, int dep){
        super(name);
        setGrade(grade);
        setIncome(inc);
        setDependents(dep);
        setBarangay(barangay);
    }
    public double getGrade(){
        return grade;
    }
    public void setGrade(double x){
        if(x >= 0 && x <= 100){
            this.grade = x;
        }
    }
    public double getIncome(){
        return income;
    }
    public void setIncome(double x){
        if (x >= 0){
            this.income = x;
        }
    }
    public int getDependents(){
        return dependents;
    }
    public void setDependents(int x){
        if (x >= 0){
            this.dependents = x;
        }
    }
    public String getBarangay(){
        return barangay;
    }
    public void setBarangay(String x){
        this.barangay = x;
    }
    public String getOwner(){
        return owner;
    }
    public void setOwner(String x){
        this.owner = x;
    }
}
