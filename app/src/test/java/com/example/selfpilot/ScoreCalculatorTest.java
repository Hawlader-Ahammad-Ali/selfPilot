package com.example.selfpilot;

import com.example.selfpilot.util.ScoreCalculator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScoreCalculatorTest {

    @Test
    public void testDisciplineScoreWithinBudget() {
        // 20 mins non-study app usage = 20/5 = 4 deduction -> 96 score
        int score = ScoreCalculator.calculateDisciplineScore(20, 240);
        assertEquals(96, score);
    }

    @Test
    public void testDisciplineScoreExceedingBudget() {
        // 300 mins non-study app usage (budget 240 mins):
        // 300/5 = 60 deduction + 15 budget penalty = 75 deduction -> 25 score
        int score = ScoreCalculator.calculateDisciplineScore(300, 240);
        assertEquals(25, score);
    }

    @Test
    public void testStudyPointsCalculation() {
        // 90 mins study app usage + 2 focus sessions (2 * 10 = 20 bonus) -> 110 pts
        int points = ScoreCalculator.calculateStudyPoints(90, 2);
        assertEquals(110, points);
    }

    @Test
    public void testHonorLevelProgression() {
        assertEquals("Master", ScoreCalculator.determineHonorLevel(95, 7));
        assertEquals("Disciplined", ScoreCalculator.determineHonorLevel(85, 3));
        assertEquals("Focused", ScoreCalculator.determineHonorLevel(75, 1));
        assertEquals("Consistent", ScoreCalculator.determineHonorLevel(55, 0));
        assertEquals("Learner", ScoreCalculator.determineHonorLevel(35, 0));
        assertEquals("Beginner", ScoreCalculator.determineHonorLevel(10, 0));
    }
}
