package com.jetbrains.edu.python.learning.checkio.newProject;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.learning.checkio.CheckiOCourseContentGenerator;
import com.jetbrains.edu.learning.checkio.courseFormat.CheckiOCourse;
import com.jetbrains.edu.learning.checkio.courseFormat.CheckiOStation;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.python.learning.PyCourseBuilder;
import com.jetbrains.edu.python.learning.checkio.connectors.PyCheckiOApiConnector;
import com.jetbrains.edu.python.learning.newproject.PyCourseProjectGenerator;
import com.jetbrains.python.PythonFileType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PyCheckiOCourseProjectGenerator extends PyCourseProjectGenerator {
  private static final Logger LOG = Logger.getInstance(PyCheckiOCourseProjectGenerator.class);

  public PyCheckiOCourseProjectGenerator(@NotNull PyCourseBuilder builder,
                                         @NotNull Course course) {
    super(builder, course);
  }

  @Override
  protected void createAdditionalFiles(@NotNull Project project, @NotNull VirtualFile baseDir) {

  }

  @Override
  protected boolean beforeProjectGenerated() {
    try {
      final CheckiOCourseContentGenerator contentGenerator =
        new CheckiOCourseContentGenerator(PythonFileType.INSTANCE, PyCheckiOApiConnector.getInstance());

      final List<CheckiOStation> stations = contentGenerator.getStationsFromServerUnderProgress();

      stations.forEach(((CheckiOCourse) myCourse)::addStation);
      return true;
    }
    catch (Exception e) {
      // Notifications aren't able to be shown during course generating process,
      // so we just log the error and return false
      LOG.warn(e);
      return false;
    }
  }
}
