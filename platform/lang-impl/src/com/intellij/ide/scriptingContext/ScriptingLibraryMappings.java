/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package com.intellij.ide.scriptingContext;

import com.intellij.lang.LanguagePerFileMappings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.libraries.LibraryType;
import com.intellij.openapi.roots.libraries.scripting.ScriptingLibraryManager;
import com.intellij.openapi.roots.libraries.scripting.ScriptingLibraryTable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Rustam Vishnyakov
 */
public class ScriptingLibraryMappings extends LanguagePerFileMappings<ScriptingLibraryTable.LibraryModel> {

  private final ScriptingLibraryManager myLibraryManager;
  private final Map<VirtualFile, CompoundLibrary> myCompoundLibMap = new HashMap<VirtualFile, CompoundLibrary>();
  private CompoundLibrary myProjectLibs = new CompoundLibrary();

  public ScriptingLibraryMappings(final Project project, final LibraryType libraryType) {
    super(project);
    myLibraryManager = new ScriptingLibraryManager(project, libraryType);
  }

  protected String serialize(final ScriptingLibraryTable.LibraryModel library) {
    if (library instanceof CompoundLibrary) {
      return "{" + library.getName() + "}";
    }
    return library.getName();
  }

  @NotNull
  @Override
  protected String getValueAttribute() {
    return "libraries";
  }

  @Override
  protected ScriptingLibraryTable.LibraryModel handleUnknownMapping(VirtualFile file, String value) {
    if (value == null || !value.contains("{")) return null;
    String[] libNames = value.replace('{',' ').replace('}', ' ').split(",");
    CompoundLibrary compoundLib = new CompoundLibrary();
    for (String libName : libNames) {
      ScriptingLibraryTable.LibraryModel libraryModel = myLibraryManager.getLibraryByName(libName.trim());
      if (libraryModel != null) {
        compoundLib.toggleLibrary(libraryModel);
      }
    }
    if (file == null) {
      myProjectLibs = compoundLib;
    }
    else {
      myCompoundLibMap.put(file, compoundLib);
    }
    return compoundLib;
  }

  @Override
  public Collection<ScriptingLibraryTable.LibraryModel> getAvailableValues(VirtualFile file) {
    List<ScriptingLibraryTable.LibraryModel> libraries = getSingleLibraries();
    if (myCompoundLibMap.containsKey(file)) {
      libraries.add(myCompoundLibMap.get(file));
      return libraries;
    }
    CompoundLibrary compoundLib = new CompoundLibrary();
    myCompoundLibMap.put(file, compoundLib);
    libraries.add(compoundLib);
    return libraries;
  }

  @Override
  public ScriptingLibraryTable.LibraryModel chosenToStored(VirtualFile file, ScriptingLibraryTable.LibraryModel value) {
    if (value instanceof CompoundLibrary) return value;
    CompoundLibrary compoundLib = file == null ? myProjectLibs : myCompoundLibMap.get(file);
    if (compoundLib == null) {
      compoundLib = new CompoundLibrary();
      myCompoundLibMap.put(file, compoundLib);
    }
    if (value == null) {
      compoundLib.clearLibraries();
    }
    else {
      compoundLib.toggleLibrary(value);
    }
    return compoundLib;
  }

  @Override
  public boolean isSelectable(ScriptingLibraryTable.LibraryModel value) {
    return !(value instanceof CompoundLibrary);
  }

  @Override
  protected List<ScriptingLibraryTable.LibraryModel> getAvailableValues() {
    return getSingleLibraries();
  }


  @Override
  protected ScriptingLibraryTable.LibraryModel getDefaultMapping(@Nullable VirtualFile file) {
    return null;
  }

  public List<ScriptingLibraryTable.LibraryModel> getSingleLibraries() {
    ArrayList<ScriptingLibraryTable.LibraryModel> libraryModels = new ArrayList<ScriptingLibraryTable.LibraryModel>();
    libraryModels.addAll(Arrays.asList(myLibraryManager.getLibraries()));
    return libraryModels;
  }

  public static class CompoundLibrary extends  ScriptingLibraryTable.LibraryModel {
    private List<ScriptingLibraryTable.LibraryModel> myLibraries = new ArrayList<ScriptingLibraryTable.LibraryModel>();

    public CompoundLibrary() {
      super(null);
    }

    public void clearLibraries() {
      myLibraries.clear();
    }

    public void toggleLibrary(@NotNull ScriptingLibraryTable.LibraryModel library) {
      for (ScriptingLibraryTable.LibraryModel lib : myLibraries) {
        if (lib == library) {
          myLibraries.remove(library);
          return;
        }
      }
      myLibraries.add(library);
    }

    @Override
    public String getName() {
      StringBuffer allNames = new StringBuffer();
      boolean isFirst = true;
      for (ScriptingLibraryTable.LibraryModel library : myLibraries) {
        allNames.append(isFirst ? "" : ", ");
        allNames.append(library.getName());
        isFirst = false;
      }
      return allNames.toString();
    }
  }

}
