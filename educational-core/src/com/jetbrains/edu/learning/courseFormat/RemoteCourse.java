package com.jetbrains.edu.learning.courseFormat;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.KeyedLazyInstance;
import com.jetbrains.edu.learning.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.stepik.StepikConnector;
import com.jetbrains.edu.learning.stepik.StepikNames;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class RemoteCourse extends Course {

  private static final Logger LOG = Logger.getInstance(Course.class);

  private static List<String> ourSupportedLanguages;

  //course type in format "pycharm<version> <language>"
  @SerializedName("course_format") private String myType =
                        String.format("%s%d %s", StepikNames.PYCHARM_PREFIX, EduVersions.JSON_FORMAT_VERSION, getLanguageID());
  @SerializedName("is_idea_compatible") private boolean isCompatible = true;

  // in CC mode is used to store top-level lessons section id
  @SerializedName("sections") List<Integer> sectionIds = new ArrayList<>();
  List<Integer> instructors = new ArrayList<>();
  @Expose private int id;
  @Expose @SerializedName("update_date") private Date myUpdateDate = new Date(0);
  @Expose private boolean isAdaptive = false;
  @Expose @SerializedName("is_public") boolean isPublic;
  @Expose private boolean myLoadSolutions = true; // disabled for reset courses

  @SerializedName("additional_materials_update_date") private Date myAdditionalMaterialsUpdateDate = new Date(0);

  public String getType() {
    return myType;
  }

  @Override
  public void setLanguage(@NotNull final String language) {
    super.setLanguage(language);
    updateType(language);
  }

  public List<Integer> getSectionIds() {
    return sectionIds;
  }

  public void setSectionIds(List<Integer> sectionIds) {
    this.sectionIds = sectionIds;
  }

  public void setInstructors(List<Integer> instructors) {
    this.instructors = instructors;
  }

  public List<Integer> getInstructors() {
    return instructors;
  }

  @NotNull
  @Override
  public List<Tag> getTags() {
    final List<Tag> tags = super.getTags();
    if (getVisibility() instanceof CourseVisibility.FeaturedVisibility) {
      tags.add(new FeaturedTag());
    }
    if (getVisibility() instanceof CourseVisibility.InProgressVisibility) {
      tags.add(new InProgressTag());
    }
    return tags;
  }

  @Override
  public boolean isUpToDate() {
    if (id == 0 || !EduSettings.isLoggedIn()) return true;

    RemoteCourse courseFromServer = StepikConnector.getCourseInfo(EduSettings.getInstance().getUser(), id, isCompatible);
    if (courseFromServer == null) return true;

    final Date date = courseFromServer.getUpdateDate();
    if (date == null) return true;
    if (myUpdateDate == null) return true;
    if (EduUtils.isAfter(date, myUpdateDate)) {
      return false;
    }

    if (!checkSectionsNumber(courseFromServer)) return false;

    if (!checkTopLevelLessons(courseFromServer)) return false;

    for (StudyItem item : items) {
      if (item instanceof Section) {
        if (!((Section)item).isUpToDate()) {
          return false;
        }
      }
      else if (item instanceof Lesson) {
        if (!((Lesson)item).isUpToDate()) {
          return false;
        }
      }
    }

    return true;
  }

  private boolean checkTopLevelLessons(RemoteCourse courseFromServer) {
    if (!getLessons().isEmpty() && courseFromServer.sectionIds.size() > 0) {
      Section section = StepikConnector.getSection(courseFromServer.sectionIds.get(0));
      boolean hasNewTopLevelLessons = getLessons().size() < section.units.size();
      if (hasNewTopLevelLessons) {
        return false;
      }
    }
    return true;
  }


  // As we don't store additional sections locally, we ned to remove it for our courses. But Stepik
  // course doesn't have additional materials sections, so fo them we shouldn't do anything
  private boolean checkSectionsNumber(RemoteCourse courseFromServer) {
    boolean hasAdditional;
    if (courseFromServer.sectionIds.isEmpty()) {
      hasAdditional = false;
    }
    else {
      Section lastSection = StepikConnector.getSection(courseFromServer.sectionIds.get(courseFromServer.sectionIds.size() - 1));
      hasAdditional =
        EduNames.ADDITIONAL_MATERIALS.equals(lastSection.getName()) || StepikNames.PYCHARM_ADDITIONAL.equals(lastSection.getName());
    }
    int remoteSectionsWithoutAdditional = courseFromServer.sectionIds.size() - (hasAdditional ? 1 : 0);

    int localSectionsSize = (getLessons(false).isEmpty() ? 0 : 1) + getSections().size();

    if (localSectionsSize < remoteSectionsWithoutAdditional) {
      return false;
    }

    return true;
  }

  @Override
  public void setUpdated() {
    setUpdateDate(StepikConnector.getCourseUpdateDate(id));
    visitLessons((lesson) -> {
      if (lesson.getId() == 0) {
        return true;
      }
      Date lessonUpdateDate = StepikConnector.getLessonUpdateDate(lesson.getId());
      Date unitUpdateDate = StepikConnector.getUnitUpdateDate(lesson.unitId);
      if (lessonUpdateDate != null && unitUpdateDate != null && EduUtils.isAfter(lessonUpdateDate, unitUpdateDate)) {
        lesson.setUpdateDate(lessonUpdateDate);
      }
      else {
        lesson.setUpdateDate(unitUpdateDate);
      }

      for (Task task : lesson.getTaskList()) {
        if (task.getId() == 0) {
          continue;
        }
        task.setUpdateDate(StepikConnector.getTaskUpdateDate(task.getStepId()));
      }
      return true;
    });

    visitSections(section -> section.setUpdateDate(StepikConnector.getSectionUpdateDate((section).getId())));
  }

  public void setUpdateDate(Date date) {
    myUpdateDate = date;
  }

  public Date getUpdateDate() {
    return myUpdateDate;
  }

  @Override
  public boolean isAdaptive() {
    return isAdaptive;
  }

  public void setAdaptive(boolean adaptive) {
    isAdaptive = adaptive;
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  private void updateType(String language) {
    final int separator = myType.indexOf(" ");
    final String version;
    if (separator == -1) {
      version = String.valueOf(EduVersions.JSON_FORMAT_VERSION);
    }
    else {
      version = myType.substring(StepikNames.PYCHARM_PREFIX.length(), separator);
    }

    setType(String.format("%s%s %s", StepikNames.PYCHARM_PREFIX, version, language));
  }

  public void setType(String type) {
    myType = type;
    myCompatibility = courseCompatibility(this);
  }

  public boolean isPublic() {
    return isPublic;
  }

  public void setPublic(boolean isPublic) {
    this.isPublic = isPublic;
  }

  public boolean isLoadSolutions() {
    return myLoadSolutions;
  }

  public void setLoadSolutions(boolean myLoadSolutions) {
    this.myLoadSolutions = myLoadSolutions;
  }

  public boolean isCompatible() {
    return isCompatible;
  }

  public void setCompatible(boolean compatible) {
    isCompatible = compatible;
  }

  public Date getAdditionalMaterialsUpdateDate() {
    return myAdditionalMaterialsUpdateDate;
  }

  public void setAdditionalMaterialsUpdateDate(@NotNull Date additionalMaterialsUpdateDate) {
    myAdditionalMaterialsUpdateDate = additionalMaterialsUpdateDate;
  }

  @NotNull
  private static List<String> getSupportedLanguages() {
    if (ourSupportedLanguages == null) {
      final List<String> supportedLanguages = EduConfiguratorManager.allExtensions()
        .stream()
        .map(KeyedLazyInstance::getKey)
        .collect(Collectors.toList());
      ourSupportedLanguages = supportedLanguages;
      return supportedLanguages;
    } else {
      return ourSupportedLanguages;
    }
  }

  @NotNull
  private static CourseCompatibility courseCompatibility(@NotNull RemoteCourse courseInfo) {
    final List<String> supportedLanguages = getSupportedLanguages();

    if (courseInfo.isAdaptive()) {
      if (supportedLanguages.contains(courseInfo.getLanguageID())) {
        return CourseCompatibility.COMPATIBLE;
      } else {
        return CourseCompatibility.UNSUPPORTED;
      }
    }

    String courseType = courseInfo.getType();
    final List<String> typeLanguage = StringUtil.split(courseType, " ");
    String prefix = typeLanguage.get(0);
    if (!supportedLanguages.contains(courseInfo.getLanguageID())) return CourseCompatibility.UNSUPPORTED;
    if (typeLanguage.size() < 2 || !prefix.startsWith(StepikNames.PYCHARM_PREFIX)) {
      return CourseCompatibility.UNSUPPORTED;
    }
    String versionString = prefix.substring(StepikNames.PYCHARM_PREFIX.length());
    if (versionString.isEmpty()) {
      return CourseCompatibility.COMPATIBLE;
    }
    try {
      Integer version = Integer.valueOf(versionString);
      if (version <= EduVersions.JSON_FORMAT_VERSION) {
        return CourseCompatibility.COMPATIBLE;
      } else {
        return CourseCompatibility.INCOMPATIBLE_VERSION;
      }
    }
    catch (NumberFormatException e) {
      LOG.info("Wrong version format", e);
      return CourseCompatibility.UNSUPPORTED;
    }
  }
}
