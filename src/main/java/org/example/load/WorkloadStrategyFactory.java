package org.example.load;

import org.example.dto.TestPlanSpec;

import java.util.concurrent.atomic.AtomicBoolean;

public class WorkloadStrategyFactory {

  public WorkloadStrategy createStrategy(TestPlanSpec.LoadModel loadModel,
                                         TestPhaseManager phaseManager,
                                         RequestExecutor requestExecutor,
                                         ResourceManager resourceManager,
                                         AtomicBoolean cancelled) {
    return switch (loadModel.getType()) {
      case CLOSED -> new ClosedWorkloadStrategy(phaseManager, requestExecutor, resourceManager, cancelled);
      case OPEN -> new OpenWorkloadStrategy(phaseManager, requestExecutor, resourceManager, cancelled);
    };
  }
}
