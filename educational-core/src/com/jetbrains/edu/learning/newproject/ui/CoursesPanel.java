package com.jetbrains.edu.learning.newproject.ui;

import com.google.common.collect.Lists;
import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.OnePixelDivider;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiser;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.edu.learning.EduLanguageDecorator;
import com.jetbrains.edu.learning.EduNames;
import com.jetbrains.edu.learning.EduSettings;
import com.jetbrains.edu.learning.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.RemoteCourse;
import com.jetbrains.edu.learning.courseFormat.Tag;
import com.jetbrains.edu.learning.courseFormat.ext.CourseExt;
import com.jetbrains.edu.learning.statistics.EduUsagesCollector;
import com.jetbrains.edu.learning.stepik.StepicUser;
import com.jetbrains.edu.learning.stepik.StepikConnector;
import kotlin.collections.SetsKt;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

import static com.jetbrains.edu.learning.PluginUtilsKt.enablePlugins;

public class CoursesPanel extends JPanel {
  private static final JBColor LIST_COLOR = new JBColor(Gray.xFF, Gray.x39);
  private static final Logger LOG = Logger.getInstance(CoursesPanel.class);
  private static final String NO_COURSES = "No courses found";

  private JPanel myMainPanel;
  private JPanel myCourseListPanel;
  private FilterComponent mySearchField;
  private HyperlinkLabel myErrorLabel;
  private JSplitPane mySplitPane;
  private JPanel mySplitPaneRoot;
  private JBList<Course> myCoursesList;
  private CoursePanel myCoursePanel;
  private List<Course> myCourses;
  private List<CourseValidationListener> myListeners = new ArrayList<>();
  private MessageBusConnection myBusConnection;

  private ErrorState myErrorState = ErrorState.NothingSelected.INSTANCE;

  public CoursesPanel(@NotNull List<Course> courses) {
    myCourses = courses;
    setLayout(new BorderLayout());
    add(myMainPanel, BorderLayout.CENTER);
    initUI();
  }

  private void initUI() {
    GuiUtils.replaceJSplitPaneWithIDEASplitter(mySplitPaneRoot, true);
    mySplitPane.setDividerLocation(0.5);
    mySplitPane.setResizeWeight(0.5);
    myCoursesList = new JBList<>();
    myCoursesList.setEmptyText(NO_COURSES);
    updateModel(myCourses, null);
    myErrorLabel.setVisible(false);

    ColoredListCellRenderer<Course> renderer = getCourseRenderer();
    myCoursesList.setCellRenderer(renderer);
    myCoursesList.addListSelectionListener(e -> processSelectionChanged());

    ToolbarDecorator toolbarDecorator = ToolbarDecorator.createDecorator(myCoursesList).
      disableAddAction().disableRemoveAction().disableUpDownActions().setToolbarPosition(ActionToolbarPosition.BOTTOM);
    DefaultActionGroup group = new DefaultActionGroup(new ImportCourseAction());
    toolbarDecorator.setActionGroup(group);

    JPanel toolbarDecoratorPanel = toolbarDecorator.createPanel();
    toolbarDecoratorPanel.setBorder(null);
    myCoursesList.setBorder(null);
    myCourseListPanel.add(toolbarDecoratorPanel, BorderLayout.CENTER);
    myCourseListPanel.setBorder(JBUI.Borders.customLine(OnePixelDivider.BACKGROUND, 1, 1, 1, 1));
    myCoursesList.setBackground(LIST_COLOR);

    myErrorLabel.addHyperlinkListener(e -> {
      if (myErrorState == ErrorState.NotLoggedIn.INSTANCE || myErrorState == ErrorState.LoginRequired.INSTANCE) {
        addLoginListener(this::updateCoursesList);
        StepikConnector.doAuthorize(EduUtils::showOAuthDialog);
      } else if (myErrorState == ErrorState.IncompatibleVersion.INSTANCE) {
        PluginsAdvertiser.installAndEnablePlugins(SetsKt.setOf(EduNames.PLUGIN_ID), () -> {});
      } else if (myErrorState instanceof ErrorState.RequiredPluginsDisabled) {
        List<String> disabledPluginIds = ((ErrorState.RequiredPluginsDisabled)myErrorState).getDisabledPluginIds();
        enablePlugins(disabledPluginIds);
      }
    });

    processSelectionChanged();
  }

  private void addLoginListener(Runnable... postLoginActions) {
    if (myBusConnection != null) {
      myBusConnection.disconnect();
    }
    myBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
    myBusConnection.subscribe(EduSettings.SETTINGS_CHANGED, () -> {
      if (EduSettings.isLoggedIn()) {
        ApplicationManager.getApplication().invokeLater(() -> {
          for (Runnable action : postLoginActions) {
            action.run();
          }
          myBusConnection.disconnect();
          myBusConnection = null;
        }, ModalityState.any());
      }
    });
  }

  private void updateCoursesList() {
    Course selectedCourse = myCoursesList.getSelectedValue();
    List<Course> courses = EduUtils.getCourseInfosUnderProgress();
    myCourses = courses != null ? courses : Lists.newArrayList();
    updateModel(myCourses, selectedCourse.getName());
    myErrorLabel.setVisible(false);
    notifyListeners(true);
  }

  @NotNull
  private ColoredListCellRenderer<Course> getCourseRenderer() {
    return new ColoredListCellRenderer<Course>() {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Course> jList, Course course, int i, boolean b, boolean b1) {
          Icon logo = getLogo(course);
          setBorder(JBUI.Borders.empty(5, 0));
          append(course.getName(), course.getVisibility().getTextAttributes());
          setIcon(CourseExt.getDecoratedLogo(course, logo));
          setToolTipText(CourseExt.getTooltipText(course));
        }
      };
  }

  private void processSelectionChanged() {
    Course selectedCourse = myCoursesList.getSelectedValue();
    myErrorState = ErrorState.forCourse(selectedCourse);
    notifyListeners(myErrorState.getCourseCanBeStarted());
    updateCourseInfoPanel(selectedCourse, myErrorState);
  }

  private void updateCourseInfoPanel(@Nullable Course selectedCourse, @NotNull ErrorState errorState) {
    if (selectedCourse != null) {
      myCoursePanel.bindCourse(selectedCourse);
    }

    ErrorMessage message = errorState.getMessage();
    if (message != null) {
      myErrorLabel.setVisible(true);
      myErrorLabel.setHyperlinkText(message.getBeforeLink(), message.getLink(), message.getAfterLink());
    } else {
      myErrorLabel.setVisible(false);
    }
    myErrorLabel.setForeground(errorState.getForegroundColor());
  }

  private static List<Course> sortCourses(List<Course> courses) {
    return ContainerUtil.sorted(courses, (first, second) -> {
      int visibilityCompared = first.getVisibility().compareTo(second.getVisibility());
      if (visibilityCompared != 0) {
        return visibilityCompared;
      }
      return first.getName().compareTo(second.getName());
    });
  }

  private void updateModel(List<Course> courses, @Nullable String courseToSelect) {
    DefaultListModel<Course> listModel = new DefaultListModel<>();
    courses = sortCourses(courses);
    for (Course course : courses) {
      listModel.addElement(course);
    }
    myCoursesList.setModel(listModel);
    if (myCoursesList.getItemsCount() > 0) {
      myCoursesList.setSelectedIndex(0);
    } else {
      myCoursePanel.hideContent();
    }
    if (courseToSelect == null) {
      return;
    }
    myCourses.stream()
        .filter(course -> course.getName().equals(courseToSelect))
        .findFirst()
        .ifPresent(newCourseToSelect -> myCoursesList.setSelectedValue(newCourseToSelect, true));
  }

  public Course getSelectedCourse() {
    return myCoursesList.getSelectedValue();
  }

  private void createUIComponents() {
    myCoursePanel = new CoursePanel(false, true);
    mySearchField = new FilterComponent("Edu.NewCourse", 5, true) {
      @Override
      public void filter() {
        Course selectedCourse = myCoursesList.getSelectedValue();
        String filter = getFilter();
        List<Course> filtered = new ArrayList<>();
        for (Course course : myCourses) {
          if (accept(filter, course)) {
            filtered.add(course);
          }
        }
        updateModel(filtered, selectedCourse != null ? selectedCourse.getName() : null);
      }
    };
    myCoursePanel.bindSearchField(mySearchField);
    UIUtil.setBackgroundRecursively(mySearchField, UIUtil.getTextFieldBackground());
  }

  private static boolean accept(@NonNls String filter, Course course) {
    if (filter.isEmpty()) {
      return true;
    }

    final Set<String> filterParts = getFilterParts(filter);
    final String courseName = course.getName().toLowerCase(Locale.getDefault());

    for (String filterPart : filterParts) {
      if (courseName.contains(filterPart))
        return true;

      for (Tag tag : course.getTags()) {
        if (tag.accept(filterPart)) {
          return true;
        }
      }

      for (String authorName : course.getAuthorFullNames()) {
        if (authorName.toLowerCase(Locale.getDefault()).contains(filterPart)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static Icon getLogo(@NotNull Course course) {
    Language language = course.getLanguageById();
    EduLanguageDecorator decorator = EduLanguageDecorator.INSTANCE.forLanguage(language);
    if (decorator == null) {
      LOG.info("language decorator is null, language: " + language.getDisplayName());
      return null;
    }
    return decorator.getLogo();
  }

  @NotNull
  public String getLocationString() {
    String locationString = myCoursePanel.getLocationString();
    // We use `myCoursePanel` with location field
    // so `myCoursePanel.getLocationString()` must return not null value
    assert locationString != null;
    return locationString;
  }

  @NotNull
  public Object getProjectSettings() {
    return myCoursePanel.getProjectSettings();
  }

  @Override
  public Dimension getPreferredSize() {
    return JBUI.size(600, 400);
  }

  public void addCourseValidationListener(CourseValidationListener listener) {
    myListeners.add(listener);
    listener.validationStatusChanged(myErrorState.getCourseCanBeStarted());
  }

  private void notifyListeners(boolean canStartCourse) {
    for (CourseValidationListener listener : myListeners) {
      listener.validationStatusChanged(canStartCourse);
    }
  }

  public interface CourseValidationListener {
    void validationStatusChanged(boolean canStartCourse);
  }

  public static Set<String> getFilterParts(@NonNls String filter) {
    return new HashSet<>(Arrays.asList(filter.toLowerCase().split(" ")));
  }

  class ImportCourseAction extends AnAction {

    public ImportCourseAction() {
      super("Import Course", "Import local or Stepik course", AllIcons.ToolbarDecorator.Import);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      String localCourseOption = "Import local course";
      String stepikCourseOption = "Import Stepik course";

      BaseListPopupStep<String> popupStep = new BaseListPopupStep<String>(null, Arrays.asList(localCourseOption, stepikCourseOption)) {

        @Override
        public PopupStep onChosen(String selectedValue, boolean finalChoice) {
          return doFinalStep(() -> {
            if (localCourseOption.equals(selectedValue)) {
              importLocalCourse();
            }
            else if (stepikCourseOption.equals(selectedValue)) {
              if (!EduSettings.isLoggedIn()) {
                int result = Messages.showOkCancelDialog("Stepik authorization is required to import courses", "Log in to Stepik", "Log in", "Cancel", null);
                if (result == Messages.OK) {
                  addLoginListener(CoursesPanel.this::updateCoursesList,  () -> importStepikCourse());
                  StepikConnector.doAuthorize(EduUtils::showOAuthDialog);
                }
              }
              else {
                importStepikCourse();
              }
            }
          });
        }
      };

      ListPopup listPopup = JBPopupFactory.getInstance().createListPopup(popupStep);
      Icon icon = getTemplatePresentation().getIcon();
      Component component = e.getInputEvent().getComponent();

      RelativePoint relativePoint = new RelativePoint(component, new Point(icon.getIconWidth() + 6, 0));
      listPopup.show(relativePoint);
    }

    private void importLocalCourse() {
      final FileChooserDescriptor fileChooser = new FileChooserDescriptor(true, false, false, true, false, false) {
        @Override
        public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
          return file.isDirectory() || EduUtils.isZip(file.getName());
        }

        @Override
        public boolean isFileSelectable(VirtualFile file) {
          return EduUtils.isZip(file.getName());
        }

      };
      FileChooser.chooseFile(fileChooser, null, VfsUtil.getUserHomeDir(),
              file -> {
                String fileName = file.getPath();
                Course course = EduUtils.getLocalCourse(fileName);
                if (course != null) {
                  EduUsagesCollector.courseArchiveImported();
                  myCourses.add(course);
                  updateModel(myCourses, course.getName());
                }
                else {
                  Messages.showErrorDialog("Selected archive doesn't contain a valid course", "Failed to Add Local Course");
                }
              });
    }

    private void importStepikCourse() {
      ImportStepikCourseDialog dialogWrapper = new ImportStepikCourseDialog();
      if (dialogWrapper.showAndGet()) {
        String courseLink = dialogWrapper.courseLink();
        StepicUser user = EduSettings.getInstance().getUser();
        assert user != null;
        RemoteCourse course = StepikConnector.getCourseInfoByLink(user, courseLink);
        List<Language> languages = getLanguagesUnderProgress(course);

        if (languages == null || languages.isEmpty()) {
          Messages.showErrorDialog("No supported languages available for the course", "Failed to Import Course");
          return;
        }
        if (course == null) {
          showFailedToAddCourseNotification();
          return;
        }
        Language language;
        if (languages.size() == 1) {
          language = languages.get(0);
        }
        else {
          ChooseStepikCourseLanguageDialog chooseLanguageDialog = new ChooseStepikCourseLanguageDialog(languages, course.getName());
          if (chooseLanguageDialog.showAndGet()) {
            language = chooseLanguageDialog.selectedLanguage();
          }
          else {
            return;
          }
        }
        course.setType("pycharm2 " + language.getID());
        course.setLanguage(language.getID());
        myCourses.add(course);
        updateModel(myCourses, course.getName());
      }
    }

    private List<Language> getLanguagesUnderProgress(RemoteCourse course) {
      return ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
        return EduUtils.execCancelable(() -> StepikConnector.getSupportedLanguages(course));
      }, "Getting Available Languages", true, null);
    }

    private void showFailedToAddCourseNotification() {
      Messages.showErrorDialog("Cannot add course from Stepik, please check if link is correct", "Failed to Add Stepik Course");
    }
  }
}
