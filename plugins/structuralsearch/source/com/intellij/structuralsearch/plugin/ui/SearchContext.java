package com.intellij.structuralsearch.plugin.ui;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.structuralsearch.impl.matcher.DataProvider;

/**
 * Context of the search to be done
 */
public final class SearchContext implements DataProvider, Cloneable {
  private PsiFile file;
  private Project project;
  private VirtualFile currentFile;

  public void setCurrentFile(VirtualFile currentFile) {
    this.currentFile = currentFile;
  }

  public PsiFile getFile() {
    if (currentFile != null && (file == null || !currentFile.equals(file.getContainingFile().getVirtualFile()))) {
      file = PsiManager.getInstance(project).findFile(currentFile);
    }

    return file;
  }

  public void setFile(PsiFile file) {
    this.file = file;
  }

  public Project getProject() {
    return project;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public void configureFromDataContext(DataContext context) {
    Project project = CommonDataKeys.PROJECT.getData(context);
    if (project == null) {
      project = ProjectManager.getInstance().getDefaultProject();
    }
    setProject(project);

    setFile(CommonDataKeys.PSI_FILE.getData(context));
    setCurrentFile(CommonDataKeys.VIRTUAL_FILE.getData(context));
  }

  public Editor getEditor() {
    return FileEditorManager.getInstance(project).getSelectedTextEditor();
  }

  protected Object clone() {
    try {
      return super.clone();
    } catch(CloneNotSupportedException ex) {
      return null;
    }
  }
}
