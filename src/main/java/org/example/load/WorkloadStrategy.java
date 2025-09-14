package org.example.load;

import org.example.dto.TestPlanSpec;

public interface WorkloadStrategy {
  void execute(TestPlanSpec testPlanSpec);
}