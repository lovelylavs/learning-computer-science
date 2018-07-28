package LeetCode.src.Problems.P152_MaximumProductSubarray.Java;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class SolutionTest {
    
    Solution solution;
    
    @BeforeEach
    public void setUp() throws Exception {
        solution = new Solution();
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        solution = null;
    }
    
    @Test
    public void MainFunction() {
        assertTimeout(Duration.ofMillis(500), () -> {
             String[] args = new String[0];
             assertAll(() -> Solution.main(args));
        });
    }
    
    @Test
    public void TrivialCase1() {
        int[] nums = {2,3,-2,4};
        assertTimeout(Duration.ofMillis(500), () -> {
            int expected = 6;
            int actual = Solution.maxProduct(nums);
            assertEquals(expected, actual);
        });
    }

    @Test
    public void TrivialCase2() {
        int[] nums = {-2,0,-1};
        assertTimeout(Duration.ofMillis(500), () -> {
            int expected = 0;
            int actual = Solution.maxProduct(nums);
            assertEquals(expected, actual);
        });
    }
}