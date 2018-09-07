/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.edu.learning.ui.taskDescription;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.Project;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.edu.learning.StudyTaskManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.IOException;

public class SwingToolWindow extends TaskDescriptionToolWindow {
  private static final Logger LOG = Logger.getInstance(SwingToolWindow.class);
  private static final String HINT_PROTOCOL = "hint://";
  private JTextPane myTaskTextPane;

  public SwingToolWindow() {
    super();
  }

  @Override
  public JComponent createTaskInfoPanel(Project project) {
    myTaskTextPane = new JTextPane();
    final JBScrollPane scrollPane = new JBScrollPane(myTaskTextPane);
    myTaskTextPane.setContentType(new HTMLEditorKit().getContentType());

    final EditorColorsScheme editorColorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
    int fontSize = editorColorsScheme.getEditorFontSize();
    final String fontName = editorColorsScheme.getEditorFontName();
    final Font font = new Font(fontName, Font.PLAIN, fontSize);
    String dimmedColor = ColorUtil.toHex(ColorUtil.dimmer(UIUtil.getPanelBackground()));
    int size = font.getSize();
    String bodyRule = String.format("body { font-family: %s; font-size: %dpt; } \n" +
                                    "a {}", font.getFamily(), size);
    String preRule = String.format("pre {font-family: Courier; font-size: %dpt; " +
      "display: inline; ine-height: 50px; padding-top: 5px; padding-bottom: 5px; " +
      "padding-left: 5px; background-color:%s;}", fontSize, dimmedColor);
    String codeRule = String.format("code {font-family: Courier; font-size:%dpt; display: flex; " +
      "float: left; background-color: %s;}", fontSize, dimmedColor);
    String sizeRule = String.format("h1 { font-size: %dpt; } h2 { font-size: %fpt; }", 2 * fontSize, 1.5 * fontSize);
    HTMLEditorKit htmlEditorKit = UIUtil.getHTMLEditorKit(false);

    StyleSheet styleSheet = htmlEditorKit.getStyleSheet();
    styleSheet.addRule(bodyRule);
    styleSheet.addRule(preRule);
    styleSheet.addRule(codeRule);
    styleSheet.addRule(sizeRule);

    myTaskTextPane.setEditorKit(htmlEditorKit);

    myTaskTextPane.setEditable(false);
    if (!UIUtil.isUnderDarcula()) {
      myTaskTextPane.setBackground(EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
    }
    myTaskTextPane.setBorder(JBUI.Borders.empty(20, 20, 0, 10));
    myTaskTextPane.addHyperlinkListener(new EduHyperlinkListener(project));
    return scrollPane;
  }

  public void setText(@NotNull String text) {
    Document document = wrapHintsIntoLink(text);
    myTaskTextPane.setText(document.html());
  }

  @NotNull
  private static Document wrapHintsIntoLink(@NotNull String text) {
    Document document = Jsoup.parse(text);
    Elements hints = document.getElementsByClass("hint");
    for (int i = 0; i < hints.size(); i++) {
      org.jsoup.nodes.Element hint = hints.get(i);
      String link = String.format("<a href='hint://', value='%s'>Hint %d</a>", hint.child(0).ownText(), i + 1);
      hint.html(link);
    }
    return document;
  }

  @Override
  protected void updateLaf(boolean isDarcula) {
    myTaskTextPane.setBackground(EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
  }

  class EduHyperlinkListener implements HyperlinkListener {
    private Project myProject;
    private String HINT_TEXT_PATTERN = "<div class='hint_text'><br>%s<br></div>";

    public EduHyperlinkListener(@NotNull Project project) {
      myProject = project;
    }

    @Override
    public void hyperlinkUpdate(HyperlinkEvent event) {
      if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
        return;
      }

      String url = event.getDescription();
      if (url.startsWith(TaskDescriptionToolWindow.PSI_ELEMENT_PROTOCOL)) {
        TaskDescriptionToolWindow.navigateToPsiElement(myProject, url);
        return;
      }

      if (url.startsWith(HINT_PROTOCOL)) {
        Element element = event.getSourceElement();
        toggleHintElement(element);
        return;
      }

      BrowserHyperlinkListener.INSTANCE.hyperlinkUpdate(event);
    }

    private void toggleHintElement(@NotNull Element element) {
      try {
        HTMLDocument document = (HTMLDocument)myTaskTextPane.getDocument();
        Element parent = element.getParentElement();
        String className = (String) parent.getParentElement().getAttributes().getAttribute(HTML.Attribute.CLASS);
        if (!"hint".equals(className)) {
          LOG.warn(String.format("Div element with hint class not found. Course: %s", StudyTaskManager.getInstance(myProject).getCourse()));
          return;
        }
        Element  hintTextElement = getHintTextElement(parent);
        if (hintTextElement == null) {
          Object hintText = ((SimpleAttributeSet)element.getAttributes().getAttribute(HTML.Tag.A)).getAttribute(HTML.Attribute.VALUE);
          document.insertAfterEnd(element, String.format(HINT_TEXT_PATTERN, hintText));
        }
        else {
          document.removeElement(hintTextElement);
        }
      }
      catch (BadLocationException | IOException e) {
        LOG.warn(e.getMessage());
      }
    }

    @Nullable
    private Element getHintTextElement(@NotNull Element parent) {
      Element hintTextElement = null;
      for (int i = 0; i < parent.getElementCount(); i++) {
        Element child = parent.getElement(i);
        AttributeSet childAttributes = child.getAttributes();
        String childClassName = (String)childAttributes.getAttribute(HTML.Attribute.CLASS);
        if ("hint_text".equals(childClassName)) {
          hintTextElement = child;
        }
      }
      return hintTextElement;
    }
  }
}

