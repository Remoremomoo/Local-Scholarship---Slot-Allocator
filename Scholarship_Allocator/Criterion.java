package Scholarship_Allocator;

public abstract class Criterion{
    private double weight;

    public Criterion(double w){
        this.weight = w;
    }
    public double getWeight(){
        return weight;
    }
    public abstract double computeScore(Applicant a);
}
