package com.jetbrains.edu.python.learning.checkio.checker;

import com.intellij.openapi.project.Project;
import com.jetbrains.edu.learning.checkio.checker.CheckiOMissionCheck;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.python.learning.checkio.connectors.PyCheckiOOAuthConnector;
import com.jetbrains.edu.python.learning.checkio.utils.PyCheckiONames;
import org.jetbrains.annotations.NotNull;

public class PyCheckiOMissionCheck extends CheckiOMissionCheck {
  protected PyCheckiOMissionCheck(@NotNull Project project, @NotNull Task task) {
    super(project, task, PyCheckiOOAuthConnector.getInstance());
  }

  @Override
  protected String getInterpreter() {
    return PyCheckiONames.PY_CHECKIO_INTERPRETER;
  }

  @Override
  protected String getTestFormTargetUrl() {
    return PyCheckiONames.PY_CHECKIO_TEST_FORM_TARGET_URL;
  }
}
